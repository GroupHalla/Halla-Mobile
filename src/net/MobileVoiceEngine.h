#pragma once

#include <QObject>
#include <QAudioSource>
#include <QAudioSink>
#include <QAudioFormat>
#include <QMediaDevices>
#include <QTimer>
#include <QFile>
#include <QElapsedTimer>
#include <QByteArray>
#include <deque>

class MobileNetSession;

// Motor de Voz Móvel (Halla-Mobile)
// Responsável pela captura do microfone, reprodução de áudio, gravação local em WAV,
// processamento DSP (RMS / Detecção de Atividade de Voz - VAD) e transmissão de pacotes de voz.
class MobileVoiceEngine : public QObject {
    Q_OBJECT
    Q_PROPERTY(bool isRecording READ isRecording NOTIFY recordingChanged)
    Q_PROPERTY(bool isTalking READ isTalking NOTIFY talkingChanged)
    Q_PROPERTY(double voiceLevel READ voiceLevel NOTIFY voiceLevelChanged)

public:
    explicit MobileVoiceEngine(MobileNetSession* session, QObject* parent = nullptr);
    ~MobileVoiceEngine() override;

    bool isRecording() const { return m_recFile != nullptr; }
    bool isTalking() const { return m_talking; }
    double voiceLevel() const { return m_voiceLevel; }

    Q_INVOKABLE bool startRecording(const QString& wavPath);
    Q_INVOKABLE void stopRecording();
    Q_INVOKABLE void setTransmitEnabled(bool on);
    Q_INVOKABLE void setSpeakersEnabled(bool on);

    // Processa os pacotes de voz UDP recebidos do servidor
    void handleIncomingVoice(int fromId, quint16 seq, const QByteArray& payload);

signals:
    void recordingChanged(bool recording);
    void talkingChanged(bool talking);
    void voiceLevelChanged(double level);

private slots:
    void captureTick();
    void playbackTick();

private:
    void recWrite(const char* pcm, int bytes);
    void recFinalize();

    MobileNetSession* m_session;
    bool m_txEnabled = true;
    bool m_spkEnabled = true;

    QAudioSource* m_source = nullptr;
    QAudioSink* m_sink = nullptr;
    QIODevice* m_srcDev = nullptr;
    QIODevice* m_sinkDev = nullptr;

    QTimer* m_capTimer = nullptr;
    QTimer* m_playTimer = nullptr;

    QByteArray m_captureBuf;
    std::deque<QByteArray> m_playQueue;

    // Local recording (WAV)
    QFile* m_recFile = nullptr;
    quint32 m_recBytes = 0;

    // DSP / VAD
    double m_voiceLevel = 0.0;
    bool m_talking = false;
    QElapsedTimer m_silenceClock;
    quint16 m_seq = 0;

    // Opus state (only compiled if HAS_OPUS is defined)
#ifdef HAS_OPUS
    void* m_encoder = nullptr;
    void* m_decoder = nullptr;
#endif
};
