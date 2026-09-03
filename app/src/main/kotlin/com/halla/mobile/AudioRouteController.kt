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
        routeBluetoothIfAvailable()
    }

    /** Chamado no onDestroy: solta receivers, sensor e wakelock. */
    internal fun release() {
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
        try {
            val systemAudio = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val devices = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                systemAudio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
            } else emptyList()
            val bluetooth = devices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                                it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
            }
            if (bluetooth != null) {
                // Roteia a voz para o headset no stream de comunicação
                // (setCommunicationDevice no Android 12+; SCO legado antes).
                // A descoberta continua aqui porque é onde a permissão
                // BLUETOOTH_CONNECT é checada.
                activity.audioManager.setBluetoothRoute()
                btnAudioRoute?.setBackgroundResource(R.drawable.ic_headphones)
            }
        } catch (_: SecurityException) {
            // O headset continua sendo opcional quando a permissão Bluetooth
            // ainda não foi concedida pelo Android.
        }
    }

    private fun toggleAudioRoute() {
        isSpeakerPhone = !isSpeakerPhone
        // Alto-falante x auricular no stream de comunicação (Android 12+ via
        // setCommunicationDevice; legado antes). Modo de comunicação e volume
        // de chamada ficam a cargo do HallaAudioManager.
        activity.audioManager.setSpeakerphoneRoute(isSpeakerPhone)
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
            val audioManagerSystem = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED) {
                audioManagerSystem.isBluetoothScoOn = true
                audioManagerSystem.startBluetoothSco()
                Toast.makeText(context, activity.getString(R.string.bluetooth_connected), Toast.LENGTH_SHORT).show()
            } else if (state == AudioManager.SCO_AUDIO_STATE_DISCONNECTED) {
                audioManagerSystem.isBluetoothScoOn = false
                audioManagerSystem.stopBluetoothSco()
            }
        }
    }
}
