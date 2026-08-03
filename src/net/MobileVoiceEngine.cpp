#include "MobileVoiceEngine.h"
#include "MobileNetSession.h"
#include <QtMath>
#include <QDebug>

#ifdef HAS_OPUS
extern "C" {
#include <opus.h>
}
#endif

MobileVoiceEngine::MobileVoiceEngine(MobileNetSession* session, QObject* parent)
    : QObject(parent), m_session(session) {

    qDebug() << "HallaMobile: Inicializando motor de voz...";

#ifdef HAS_OPUS
    int err = 0;
    m_encoder = opus_encoder_create(48000, 1, OPUS_APPLICATION_VOIP, &err);
    m_decoder = opus_decoder_create(48000, 1, &err);
    if (m_encoder) {
        opus_encoder_ctl((OpusEncoder*)m_encoder, OPUS_SET_BITRATE(32000));
        opus_encoder_ctl((OpusEncoder*)m_encoder, OPUS_SET_VBR(1));
        opus_encoder_ctl((OpusEncoder*)m_encoder, OPUS_SET_DTX(1));
    }
    qDebug() << "HallaMobile: Codec Opus ativo para compressão de áudio.";
#else
    qDebug() << "HallaMobile: Compilado sem biblioteca Opus. Modo simulação de transmissão ativo.";
#endif

    // Formato padrão de voz: 48 kHz, Mono, 16-bit PCM (compatível com o protocolo Halla)
    QAudioFormat fmt;
    fmt.setSampleRate(48000);
    fmt.setChannelCount(1);
    fmt.setSampleFormat(QAudioFormat::Int16);

    // Configura Dispositivo de Entrada (Captura)
    QAudioDevice inDev = QMediaDevices::defaultAudioInput();
    if (!inDev.isNull()) {
        m_source = new QAudioSource(inDev, fmt, this);
        m_srcDev = m_source->start();
        m_captureBuf.reserve(960 * 2 * 2);

        m_capTimer = new QTimer(this);
        m_capTimer->setInterval(10);
        connect(m_capTimer, &QTimer::timeout, this, &MobileVoiceEngine::captureTick);
        m_capTimer->start();
        qDebug() << "HallaMobile: Dispositivo de microfone iniciado:" << inDev.description();
    } else {
        qWarning() << "HallaMobile: Nenhum microfone encontrado.";
    }

    // Configura Dispositivo de Saída (Alto-falante)
    QAudioDevice outDev = QMediaDevices::defaultAudioOutput();
    if (!outDev.isNull()) {
        m_sink = new QAudioSink(outDev, fmt, this);
        m_sink->setBufferSize(960 * 2 * 20); // ~400 ms de buffer de segurança
        m_sinkDev = m_sink->start();

        m_playTimer = new QTimer(this);
        m_playTimer->setInterval(10);
        connect(m_playTimer, &QTimer::timeout, this, &MobileVoiceEngine::playbackTick);
        m_playTimer->start();
        qDebug() << "HallaMobile: Dispositivo de reprodução de áudio iniciado:" << outDev.description();
    } else {
        qWarning() << "HallaMobile: Nenhum dispositivo de reprodução encontrado.";
    }
}

MobileVoiceEngine::~MobileVoiceEngine() {
    stopRecording();
    if (m_source) m_source->stop();
    if (m_sink) m_sink->stop();
#ifdef HAS_OPUS
    if (m_encoder) opus_encoder_destroy((OpusEncoder*)m_encoder);
    if (m_decoder) opus_decoder_destroy((OpusDecoder*)m_decoder);
#endif
}

bool MobileVoiceEngine::startRecording(const QString& wavPath) {
    stopRecording();
    QFile* f = new QFile(wavPath, this);
    if (!f->open(QIODevice::WriteOnly)) {
        delete f;
        return false;
    }
    m_recFile = f;
    m_recBytes = 0;

    // Escreve cabeçalho WAV de 44 bytes para gravação local de alta fidelidade
    QByteArray h(44, 0);
    memcpy(h.data() + 0, "RIFF", 4);
    memcpy(h.data() + 8, "WAVEfmt ", 8);
    quint32 fmtLen = 16;
    memcpy(h.data() + 16, &fmtLen, 4);
    quint16 audioFmt = 1, ch = 1;
    quint32 rate = 48000, byteRate = 48000 * 2;
    quint16 align = 2, bits = 16;
    memcpy(h.data() + 20, &audioFmt, 2);
    memcpy(h.data() + 22, &ch, 2);
    memcpy(h.data() + 24, &rate, 4);
    memcpy(h.data() + 28, &byteRate, 4);
    memcpy(h.data() + 32, &align, 2);
    memcpy(h.data() + 34, &bits, 2);
    memcpy(h.data() + 36, "data", 4);
    f->write(h);

    emit recordingChanged(true);
    qDebug() << "HallaMobile: Gravação local de voz iniciada em" << wavPath;
    return true;
}

void MobileVoiceEngine::stopRecording() {
    if (!m_recFile) return;
    recFinalize();
    delete m_recFile;
    m_recFile = nullptr;
    emit recordingChanged(false);
    qDebug() << "HallaMobile: Gravação local de voz finalizada.";
}

void MobileVoiceEngine::setTransmitEnabled(bool on) {
    m_txEnabled = on;
    if (!on && m_talking) {
        m_talking = false;
        emit talkingChanged(false);
    }
}

void MobileVoiceEngine::setSpeakersEnabled(bool on) {
    m_spkEnabled = on;
}

void MobileVoiceEngine::captureTick() {
    if (!m_srcDev || !m_txEnabled) {
        if (m_srcDev) m_srcDev->readAll(); // Drena o microfone para não estourar buffer
        return;
    }

    m_captureBuf.append(m_srcDev->readAll());

    // O protocolo e o codec operam em quadros de 20 ms (960 amostras @ 48 kHz 16-bit)
    while (m_captureBuf.size() >= 960 * 2) {
        const int16_t* pcm = reinterpret_cast<const int16_t*>(m_captureBuf.constData());

        // ---- DSP / VAD: Medição de nível RMS em tempo real
        double sum = 0;
        for (int i = 0; i < 960; ++i) {
            sum += double(pcm[i]) * double(pcm[i]);
        }
        const double rms = qSqrt(sum / 960.0);
        
        // Conversão de nível linear para escala 0..100 para UI do celular
        double normLevel = (rms / 32768.0) * 100.0;
        if (normLevel > 100.0) normLevel = 100.0;

        if (qAbs(m_voiceLevel - normLevel) > 1.0) {
            m_voiceLevel = normLevel;
            emit voiceLevelChanged(m_voiceLevel);
        }

        // Limiar dinâmico para detecção de voz (VAD) similar ao desktop (padrão -45dB ou RMS > 150)
        bool voiceNow = rms > 150.0;

        if (voiceNow != m_talking) {
            if (voiceNow) {
                m_talking = true;
                emit talkingChanged(true);
            } else if (m_silenceClock.elapsed() > 350) { // Histerese de 350 ms para evitar soluços
                m_talking = false;
                emit talkingChanged(false);
            }
        }
        if (voiceNow) m_silenceClock.restart();

        // Se estiver falando, codifica e transmite via UDP
        if (voiceNow) {
#ifdef HAS_OPUS
            unsigned char out[512];
            if (m_encoder) {
                const int n = opus_encode((OpusEncoder*)m_encoder, pcm, 960, out, sizeof(out));
                if (n > 0) {
                    QByteArray payload(reinterpret_cast<char*>(out), n);
                    m_session->sendVoiceFrame(payload, ++m_seq);
                }
            }
#else
            // Sem Opus: Envia simulado ou pacote vazio para registrar atividade de ping
            m_session->sendVoiceFrame(QByteArray(), ++m_seq);
#endif
            // Salva na gravação de alta fidelidade se ativa
            if (m_recFile) {
                recWrite(reinterpret_cast<const char*>(pcm), 960 * 2);
            }
        }

        m_captureBuf.remove(0, 960 * 2);
    }

    if (m_captureBuf.size() > 960 * 2 * 8) {
        m_captureBuf.clear(); // Proteção contra estouro de buffer
    }
}

void MobileVoiceEngine::handleIncomingVoice(int fromId, quint16 seq, const QByteArray& payload) {
    Q_UNUSED(fromId);
    Q_UNUSED(seq);
    if (payload.isEmpty() || !m_spkEnabled) return;

#ifdef HAS_OPUS
    if (!m_decoder) return;
    int16_t pcm[960];
    int n = opus_decode((OpusDecoder*)m_decoder,
                        reinterpret_cast<const unsigned char*>(payload.constData()),
                        payload.size(), pcm, 960, 0);
    if (n > 0) {
        m_playQueue.push_back(QByteArray(reinterpret_cast<char*>(pcm), n * 2));
        while (m_playQueue.size() > 80) m_playQueue.pop_front(); // Limita atrasos (~1.6 s máximo)
    }
#endif
}

void MobileVoiceEngine::playbackTick() {
    if (!m_sinkDev || !m_spkEnabled) {
        m_playQueue.clear();
        return;
    }

    QByteArray chunk;
    while (!m_playQueue.empty() && chunk.size() < 960 * 2 * 8) {
        chunk.append(m_playQueue.front());
        m_playQueue.pop_front();
    }

    if (!chunk.isEmpty()) {
        const int free = int(m_sink->bytesFree());
        m_sinkDev->write(chunk.constData(), qMin<qint64>(chunk.size(), free));
        if (m_recFile) {
            recWrite(chunk.constData(), chunk.size()); // Grava o áudio recebido também!
        }
    }
}

void MobileVoiceEngine::recWrite(const char* pcm, int bytes) {
    if (!m_recFile) return;
    m_recFile->write(pcm, bytes);
    m_recBytes += quint32(bytes);
}

void MobileVoiceEngine::recFinalize() {
    if (!m_recFile) return;
    // Corrige os tamanhos dos blocos RIFF e Data no arquivo WAV
    m_recFile->seek(4);
    quint32 riff = 36 + m_recBytes;
    m_recFile->write(reinterpret_cast<const char*>(&riff), 4);
    m_recFile->seek(40);
    m_recFile->write(reinterpret_cast<const char*>(&m_recBytes), 4);
    m_recFile->close();
}
