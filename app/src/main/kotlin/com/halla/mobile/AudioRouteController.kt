package com.halla.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.core.content.ContextCompat

/**
 * Roteamento de áudio extraído do MainActivity (refactor do monólito):
 * descoberta e roteamento para headset Bluetooth (SCO/A2DP/BLE), troca
 * entre alto-falante e auricular no stream de comunicação, sensor de
 * proximidade (apaga a tela no auricular), receiver de estado do
 * foreground service (mute/deaf/fala refletidos na UI) e receiver de SCO.
 *
 * O HallaAudioManager continua na Activity; este controller só decide
 * PARA ONDE a voz vai e mantém a UI do botão de rota em dia.
 */
class AudioRouteController(private val activity: MainActivity) {

    private var btnAudioRoute: Button? = null

    /** Chamado no onCreate: liga o botão, sensores, receivers e a
     *  descoberta inicial de headset Bluetooth. */
    internal fun wire() {
        btnAudioRoute = activity.findViewById(R.id.btnAudioRoute)
        btnAudioRoute?.setOnClickListener { toggleAudioRoute() }
        sensorManager = activity.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        proximitySensor = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        activity.registerReceiver(
            bluetoothReceiver,
            IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))
        ContextCompat.registerReceiver(
            activity,
            serviceStateReceiver,
            IntentFilter(HallaService.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        (activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
            .registerAudioDeviceCallback(audioDeviceCallback, activity.handler)
        // Fone enfiado/removido com a Activity aberta: ícone e toast seguem
        // a rota aplicada pelo manager (que também cobre o service ativo).
        activity.audioManager.onRouteChanged = { kind ->
            activity.runOnUiThread {
                updateRouteIcon()
                val msg = when (kind) {
                    HallaAudioManager.CommRouteKind.WIRED ->
                        activity.getString(R.string.wired_headset_connected)
                    HallaAudioManager.CommRouteKind.BLUETOOTH ->
                        activity.getString(R.string.bluetooth_connected)
                    HallaAudioManager.CommRouteKind.SPEAKER ->
                        activity.getString(R.string.audio_speaker)
                    HallaAudioManager.CommRouteKind.EARPIECE ->
                        activity.getString(R.string.audio_earpiece)
                }
                Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
            }
        }
        routeBluetoothIfAvailable()
    }

    /** Chamado no onDestroy: solta receivers, sensor e wakelock. */
    internal fun release() {
        try {
            activity.audioManager.onRouteChanged = null
        } catch (_: Exception) {}
        try {
            activity.unregisterReceiver(bluetoothReceiver)
            activity.unregisterReceiver(serviceStateReceiver)
        } catch (_: Exception) {}
        (activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
            .unregisterAudioDeviceCallback(audioDeviceCallback)
        sensorManager?.unregisterListener(proximityListener)
        if (wakeLock?.isHeld == true) wakeLock?.release()
    }

    /** Visibilidade do botão de rota (conectado x tela inicial). */
    internal fun setRouteButtonVisible(visible: Boolean) {
        btnAudioRoute?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private var isSpeakerPhone = true
    private var sensorManager: SensorManager? = null
    private var proximitySensor: Sensor? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            routeBluetoothIfAvailable()
        }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            routeBluetoothIfAvailable()
        }
    }
    // Roteamento de Áudio, Proximidade e Bluetooth
    // ============================================================================

    internal fun routeBluetoothIfAvailable() {
        // Roteamento unificado no HallaAudioManager: fio/USB > Bluetooth >
        // alto-falante/auricular (preferência do toggle). O controller só
        // reflete o resultado no ícone do botão.
        activity.audioManager.applyCommunicationRoute()
        updateRouteIcon()
    }

    /** Ícone do botão reflete a rota ATIVA (fone conectado ganha do toggle). */
    internal fun updateRouteIcon() {
        val kind = activity.audioManager.currentCommunicationKind()
        btnAudioRoute?.setBackgroundResource(when (kind) {
            HallaAudioManager.CommRouteKind.WIRED,
            HallaAudioManager.CommRouteKind.BLUETOOTH -> R.drawable.ic_headphones
            else -> if (activity.audioManager.userWantsSpeaker)
                R.drawable.ic_speaker else R.drawable.ic_headphones
        })
    }

    private fun toggleAudioRoute() {
        isSpeakerPhone = !isSpeakerPhone
        // Alto-falante x auricular no stream de comunicação (Android 12+ via
        // setCommunicationDevice; legado antes). Modo de comunicação e volume
        // de chamada ficam a cargo do HallaAudioManager. Fone com fio/USB/Bluetooth
        // conectado tem prioridade sobre o toggle (a voz segue no fone).
        activity.audioManager.setSpeakerphoneRoute(isSpeakerPhone)
        if (activity.audioManager.currentCommunicationKind() ==
                HallaAudioManager.CommRouteKind.WIRED ||
            activity.audioManager.currentCommunicationKind() ==
                HallaAudioManager.CommRouteKind.BLUETOOTH) {
            updateRouteIcon()
            return
        }
        if (isSpeakerPhone) {
            btnAudioRoute?.setBackgroundResource(R.drawable.ic_speaker)
            Toast.makeText(activity, activity.getString(R.string.audio_speaker), Toast.LENGTH_SHORT).show()

            // Desativa sensor de proximidade no viva-voz
            sensorManager?.unregisterListener(proximityListener)
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } else {
            btnAudioRoute?.setBackgroundResource(R.drawable.ic_headphones)
            Toast.makeText(activity, activity.getString(R.string.audio_earpiece), Toast.LENGTH_SHORT).show()

            // Ativa sensor de proximidade no modo auricular
            proximitySensor?.let {
                sensorManager?.registerListener(proximityListener, it, SensorManager.SENSOR_DELAY_NORMAL)
            }
        }
    }

    private val proximityListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type == Sensor.TYPE_PROXIMITY) {
                val distance = event.values[0]
                val isClose = distance < (proximitySensor?.maximumRange ?: 5f)
                if (!isSpeakerPhone && isClose) {
                    if (wakeLock == null) {
                        val powerManager = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
                        wakeLock = powerManager.newWakeLock(
                            PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                            "HallaMobile:ProximityScreenOff"
                        )
                    }
                    if (wakeLock?.isHeld == false) {
                        wakeLock?.acquire()
                    }
                } else {
                    if (wakeLock?.isHeld == true) {
                        wakeLock?.release()
                    }
                }
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private val serviceStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != HallaService.ACTION_STATE_CHANGED) return
            val talking = if (intent.hasExtra("talking"))
                intent.getBooleanExtra("talking", false) else null
            if (intent.hasExtra(HallaService.PREF_MIC_MUTED)) {
                activity.isMuted = intent.getBooleanExtra(HallaService.PREF_MIC_MUTED, activity.isMuted)
            }
            if (intent.hasExtra(HallaService.PREF_SPK_MUTED)) {
                activity.isDeaf = intent.getBooleanExtra(HallaService.PREF_SPK_MUTED, activity.isDeaf)
            }
            activity.runOnUiThread {
                activity.syncAudioUiFromPreferences()
                activity.updateScreenShareButton()
                if (talking != null) activity.updateTalkingUi(talking)
            }
        }
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_DISCONNECTED)
            if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED) {
                Toast.makeText(context, activity.getString(R.string.bluetooth_connected), Toast.LENGTH_SHORT).show()
            } else if (state == AudioManager.SCO_AUDIO_STATE_DISCONNECTED) {
                // O roteamento unificado do HallaAudioManager reage sozinho ao
                // headset sumir; aqui apenas re-aplica por segurança.
                activity.audioManager.applyCommunicationRoute()
            }
            updateRouteIcon()
        }
    }
}
