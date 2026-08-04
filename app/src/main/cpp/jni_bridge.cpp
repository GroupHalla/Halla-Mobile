#include <jni.h>
#include <string>
#include <thread>
#include <mutex>
#include <vector>
#include <map>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <netdb.h>
#include <android/log.h>
#include <cstring>
#include <exception>

// Inclui a biblioteca oficial Opus para compressão/descompressão VoIP de alta fidelidade
#include <opus.h>

#define LOG_TAG "HallaCoreJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Global JNI state
static JavaVM* g_vm = nullptr;
static jclass g_coreClass = nullptr;

static jmethodID g_onConnectedMethod = nullptr;
static jmethodID g_onDisconnectedMethod = nullptr;
static jmethodID g_onWelcomeMethod = nullptr;
static jmethodID g_onChannelListMethod = nullptr;
static jmethodID g_onUserListMethod = nullptr;
static jmethodID g_onChatMessageMethod = nullptr;
static jmethodID g_onAudioFrameMethod = nullptr;
static jmethodID g_onConnectionFailedMethod = nullptr;

static std::string g_cachePath = "";

// Logging local para depuração em tempo real no dispositivo
void writeLog(const std::string& msg) {
    LOGI("%s", msg.c_str());
    if (g_cachePath.empty()) return;
    std::string path = g_cachePath + "/halla_log.txt";
    FILE* f = fopen(path.c_str(), "a");
    if (f) {
        fprintf(f, "[C++] %s\n", msg.c_str());
        fclose(f);
    }
}

// Exception-safe helper conversions
int safeStoi(const std::string& str) {
    int val = 0;
    bool neg = false;
    for (char c : str) {
        if (c == '-') neg = true;
        else if (c >= '0' && c <= '9') {
            val = val * 10 + (c - '0');
        }
    }
    return neg ? -val : val;
}

uint32_t safeStoul(const std::string& str) {
    uint32_t val = 0;
    for (char c : str) {
        if (c >= '0' && c <= '9') {
            val = val * 10 + (c - '0');
        }
    }
    return val;
}

// Helpers to extract JSON string fields (zero dependencies, ultra fast & safe)
std::string jsonExtractString(const std::string& json, const std::string& key) {
    size_t pos = json.find("\"" + key + "\"");
    if (pos == std::string::npos) return "";
    pos = json.find(":", pos);
    if (pos == std::string::npos) return "";
    pos = json.find("\"", pos);
    if (pos == std::string::npos) return "";
    size_t start = pos + 1;
    size_t end = start;
    while (end < json.size()) {
        end = json.find("\"", end);
        if (end == std::string::npos) return "";
        if (json[end - 1] != '\\') {
            break;
        }
        end++;
    }
    return json.substr(start, end - start);
}

int jsonExtractInt(const std::string& json, const std::string& key) {
    size_t pos = json.find("\"" + key + "\"");
    if (pos == std::string::npos) return 0;
    pos = json.find(":", pos);
    if (pos == std::string::npos) return 0;
    while (pos < json.size() && (json[pos] == ':' || json[pos] == ' ' || json[pos] == '\t')) pos++;
    size_t start = pos;
    size_t end = start;
    while (end < json.size() && json[end] >= '0' && json[end] <= '9') end++;
    if (start == end) return 0;
    return safeStoi(json.substr(start, end - start));
}

std::string jsonExtractArray(const std::string& json, const std::string& key) {
    size_t pos = json.find("\"" + key + "\"");
    if (pos == std::string::npos) return "[]";
    pos = json.find("[", pos);
    if (pos == std::string::npos) return "[]";
    size_t start = pos;
    int bracketCount = 0;
    for (size_t i = start; i < json.size(); ++i) {
        if (json[i] == '[') bracketCount++;
        else if (json[i] == ']') {
            bracketCount--;
            if (bracketCount == 0) {
                return json.substr(start, i - start + 1);
            }
        }
    }
    return "[]";
}

// Thread-safe helpers to invoke JNI callbacks from background C++ threads
void invokeOnConnected(const std::string& serverName, const std::string& motd) {
    writeLog("invokeOnConnected chamada!");
    if (!g_vm || !g_coreClass || !g_onConnectedMethod) {
        writeLog("invokeOnConnected abortada: g_vm, g_coreClass ou g_onConnectedMethod nulo!");
        return;
    }
    JNIEnv* env = nullptr;
    bool attached = false;
    jint res = g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (res == JNI_EDETACHED) {
        writeLog("invokeOnConnected: Thread desconectada do JVM, anexando...");
        g_vm->AttachCurrentThread(&env, nullptr);
        attached = true;
    }
    if (env) {
        writeLog("invokeOnConnected: Invocando triggerOnConnected no Kotlin...");
        jstring jName = env->NewStringUTF(serverName.c_str());
        jstring jMotd = env->NewStringUTF(motd.c_str());
        env->CallStaticVoidMethod(g_coreClass, g_onConnectedMethod, jName, jMotd);
        env->DeleteLocalRef(jName);
        env->DeleteLocalRef(jMotd);
        writeLog("invokeOnConnected: triggerOnConnected invocado com sucesso!");
    }
    if (attached) {
        writeLog("invokeOnConnected: Desanexando thread...");
        g_vm->DetachCurrentThread();
    }
}

void invokeOnDisconnected() {
    writeLog("invokeOnDisconnected chamada!");
    if (!g_vm || !g_coreClass || !g_onDisconnectedMethod) return;
    JNIEnv* env = nullptr;
    bool attached = false;
    jint res = g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (res == JNI_EDETACHED) {
        g_vm->AttachCurrentThread(&env, nullptr);
        attached = true;
    }
    if (env) {
        env->CallStaticVoidMethod(g_coreClass, g_onDisconnectedMethod);
    }
    if (attached) g_vm->DetachCurrentThread();
}

void invokeOnWelcome(const std::string& welcomeJson) {
    writeLog("invokeOnWelcome chamada!");
    if (!g_vm || !g_coreClass || !g_onWelcomeMethod) {
        writeLog("invokeOnWelcome abortada: g_vm, g_coreClass ou g_onWelcomeMethod nulo!");
        return;
    }
    JNIEnv* env = nullptr;
    bool attached = false;
    jint res = g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (res == JNI_EDETACHED) {
        g_vm->AttachCurrentThread(&env, nullptr);
        attached = true;
    }
    if (env) {
        jstring jJson = env->NewStringUTF(welcomeJson.c_str());
        env->CallStaticVoidMethod(g_coreClass, g_onWelcomeMethod, jJson);
        env->DeleteLocalRef(jJson);
        writeLog("invokeOnWelcome: triggerOnWelcome invocado com sucesso!");
    }
    if (attached) g_vm->DetachCurrentThread();
}

void invokeOnChannelList(const std::string& channelsJson) {
    writeLog("invokeOnChannelList chamada!");
    if (!g_vm || !g_coreClass || !g_onChannelListMethod) return;
    JNIEnv* env = nullptr;
    bool attached = false;
    jint res = g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (res == JNI_EDETACHED) {
        g_vm->AttachCurrentThread(&env, nullptr);
        attached = true;
    }
    if (env) {
        jstring jJson = env->NewStringUTF(channelsJson.c_str());
        env->CallStaticVoidMethod(g_coreClass, g_onChannelListMethod, jJson);
        env->DeleteLocalRef(jJson);
    }
    if (attached) g_vm->DetachCurrentThread();
}

void invokeOnUserList(const std::string& usersJson) {
    writeLog("invokeOnUserList chamada!");
    if (!g_vm || !g_coreClass || !g_onUserListMethod) return;
    JNIEnv* env = nullptr;
    bool attached = false;
    jint res = g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (res == JNI_EDETACHED) {
        g_vm->AttachCurrentThread(&env, nullptr);
        attached = true;
    }
    if (env) {
        jstring jJson = env->NewStringUTF(usersJson.c_str());
        env->CallStaticVoidMethod(g_coreClass, g_onUserListMethod, jJson);
        env->DeleteLocalRef(jJson);
    }
    if (attached) g_vm->DetachCurrentThread();
}

void invokeOnChatMessage(const std::string& fromName, const std::string& text) {
    writeLog("invokeOnChatMessage chamada!");
    if (!g_vm || !g_coreClass || !g_onChatMessageMethod) return;
    JNIEnv* env = nullptr;
    bool attached = false;
    jint res = g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (res == JNI_EDETACHED) {
        g_vm->AttachCurrentThread(&env, nullptr);
        attached = true;
    }
    if (env) {
        jstring jFrom = env->NewStringUTF(fromName.c_str());
        jstring jText = env->NewStringUTF(text.c_str());
        env->CallStaticVoidMethod(g_coreClass, g_onChatMessageMethod, jFrom, jText);
        env->DeleteLocalRef(jFrom);
        env->DeleteLocalRef(jText);
    }
    if (attached) g_vm->DetachCurrentThread();
}

void invokeOnAudioFrame(int fromUserId, const char* data, int size) {
    if (!g_vm || !g_coreClass || !g_onAudioFrameMethod) return;
    JNIEnv* env = nullptr;
    bool attached = false;
    jint res = g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (res == JNI_EDETACHED) {
        g_vm->AttachCurrentThread(&env, nullptr);
        attached = true;
    }
    if (env) {
        jbyteArray jArr = env->NewByteArray(size);
        env->SetByteArrayRegion(jArr, 0, size, reinterpret_cast<const jbyte*>(data));
        env->CallStaticVoidMethod(g_coreClass, g_onAudioFrameMethod, fromUserId, jArr);
        env->DeleteLocalRef(jArr);
    }
    if (attached) g_vm->DetachCurrentThread();
}

void invokeOnConnectionFailed(const std::string& reason) {
    writeLog("invokeOnConnectionFailed chamada: " + reason);
    if (!g_vm || !g_coreClass || !g_onConnectionFailedMethod) return;
    JNIEnv* env = nullptr;
    bool attached = false;
    jint res = g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (res == JNI_EDETACHED) {
        g_vm->AttachCurrentThread(&env, nullptr);
        attached = true;
    }
    if (env) {
        jstring jReason = env->NewStringUTF(reason.c_str());
        env->CallStaticVoidMethod(g_coreClass, g_onConnectionFailedMethod, jReason);
        env->DeleteLocalRef(jReason);
    }
    if (attached) g_vm->DetachCurrentThread();
}

// Motor de Rede C++ Portável e de Alta Performance para o Halla Mobile
class HallaClientCore {
public:
    static HallaClientCore& getInstance() {
        static HallaClientCore instance;
        return instance;
    }

    void connectToServer(const std::string& host, int port, const std::string& nick, const std::string& pass) {
        disconnect();

        m_host = host;
        m_nick = nick;
        m_pass = pass;
        m_connected = true;

        // Inicializa o codificador Opus Oficial de voz VoIP
        int err = 0;
        m_encoder = opus_encoder_create(48000, 1, OPUS_APPLICATION_VOIP, &err);
        if (m_encoder) {
            opus_encoder_ctl(m_encoder, OPUS_SET_BITRATE(32000));
            opus_encoder_ctl(m_encoder, OPUS_SET_VBR(1));
            opus_encoder_ctl(m_encoder, OPUS_SET_DTX(1));
            writeLog("Opus Encoder nativo inicializado com sucesso.");
        } else {
            writeLog("Erro ao inicializar Opus Encoder nativo!");
        }

        m_tcpThread = std::thread(&HallaClientCore::tcpLoop, this, host, port);
    }

    void disconnect() {
        m_connected = false;
        if (m_tcpSocket != -1) {
            close(m_tcpSocket);
            m_tcpSocket = -1;
        }
        if (m_udpSocket != -1) {
            close(m_udpSocket);
            m_udpSocket = -1;
        }
        if (m_tcpThread.joinable()) m_tcpThread.join();
        if (m_udpThread.joinable()) m_udpThread.join();
        m_udpPort = 0;
        m_voiceToken = 0;

        // Libera recursos do codec Opus
        if (m_encoder) {
            opus_encoder_destroy(m_encoder);
            m_encoder = nullptr;
        }
        for (auto& pair : m_decoders) {
            opus_decoder_destroy(pair.second);
        }
        m_decoders.clear();
        writeLog("Recursos do Codec Opus liberados.");
    }

    void joinChannel(int channelId) {
        std::string msg = "{\"t\":\"move\",\"channel\":" + std::to_string(channelId) + "}\n";
        sendTcp(msg);
    }

    void sendChatMessage(const std::string& text) {
        std::string msg = "{\"t\":\"chat\",\"scope\":\"channel\",\"text\":\"" + text + "\"}\n";
        sendTcp(msg);
    }

    void sendVoiceFrame(const char* pcm, int size, uint16_t seq) {
        if (m_udpSocket == -1 || m_udpPort == 0 || m_voiceToken == 0) return;

        std::vector<char> packet(10 + size);
        memcpy(packet.data(), "HALL", 4);
        memcpy(packet.data() + 4, &m_voiceToken, 4);
        memcpy(packet.data() + 8, &seq, 2);
        if (size > 0 && pcm != nullptr) {
            memcpy(packet.data() + 10, pcm, size);
        }

        struct sockaddr_in servAddr;
        memset(&servAddr, 0, sizeof(servAddr));
        servAddr.sin_family = AF_INET;
        servAddr.sin_port = htons(m_udpPort);
        
        struct hostent* host = gethostbyname(m_host.c_str());
        if (host) {
            memcpy(&servAddr.sin_addr.s_addr, host->h_addr_list[0], host->h_length);
            sendto(m_udpSocket, packet.data(), packet.size(), 0, (struct sockaddr*)&servAddr, sizeof(servAddr));
        }
    }

    // Codifica PCM bruto de 16-bit Mono @ 48kHz em frames Opus VoIP e transmite
    void encodeAndSendVoice(const int16_t* pcm, int samples) {
        if (!m_encoder || m_udpSocket == -1 || m_udpPort == 0 || m_voiceToken == 0) return;

        static uint16_t voiceSeq = 0;
        unsigned char opusBuf[512];
        int n = opus_encode(m_encoder, pcm, samples, opusBuf, sizeof(opusBuf));
        if (n > 0) {
            sendVoiceFrame(reinterpret_cast<const char*>(opusBuf), n, ++voiceSeq);
        }
    }

private:
    HallaClientCore() : m_tcpSocket(-1), m_udpSocket(-1), m_connected(false), m_udpPort(0), m_voiceToken(0), m_encoder(nullptr) {}
    ~HallaClientCore() { disconnect(); }

    void sendTcp(const std::string& data) {
        std::lock_guard<std::mutex> lock(m_tcpMutex);
        if (m_tcpSocket != -1) {
            send(m_tcpSocket, data.c_str(), data.size(), 0);
        }
    }

    OpusDecoder* getOrCreateDecoder(uint32_t userId) {
        auto it = m_decoders.find(userId);
        if (it != m_decoders.end()) return it->second;

        int err = 0;
        OpusDecoder* dec = opus_decoder_create(48000, 1, &err);
        if (dec) {
            m_decoders[userId] = dec;
            writeLog("Opus Decoder alocado com sucesso para o usuario #" + std::to_string(userId));
        }
        return dec;
    }

    void decodeAndNotifyVoice(uint32_t fromId, uint16_t seq, const char* opusData, int size) {
        OpusDecoder* dec = getOrCreateDecoder(fromId);
        if (!dec) return;

        int16_t pcm[960]; // Amortiza em blocos padrão de 20ms @ 48kHz
        if (size > 0) {
            int n = opus_decode(dec, reinterpret_cast<const unsigned char*>(opusData), size, pcm, 960, 0);
            if (n > 0) {
                // Envia o áudio descriptografado PCM cru para o Android tocar
                invokeOnAudioFrame(fromId, reinterpret_cast<const char*>(pcm), n * 2);
            }
        }
    }

    void tcpLoop(const std::string& hostStr, int port) {
        writeLog("tcpLoop iniciado para " + hostStr + ":" + std::to_string(port));
        
        m_tcpSocket = socket(AF_INET, SOCK_STREAM, 0);
        if (m_tcpSocket == -1) {
            writeLog("Erro: Nao foi possivel criar socket TCP");
            invokeOnConnectionFailed("Could not create socket");
            return;
        }

        struct hostent* host = gethostbyname(hostStr.c_str());
        if (!host) {
            writeLog("Erro: Nao foi possivel resolver host TCP");
            invokeOnConnectionFailed("Could not resolve host");
            close(m_tcpSocket);
            m_tcpSocket = -1;
            return;
        }

        struct sockaddr_in servAddr;
        memset(&servAddr, 0, sizeof(servAddr));
        servAddr.sin_family = AF_INET;
        servAddr.sin_port = htons(port);
        memcpy(&servAddr.sin_addr.s_addr, host->h_addr_list[0], host->h_length);

        if (connect(m_tcpSocket, (struct sockaddr*)&servAddr, sizeof(servAddr)) < 0) {
            writeLog("Erro: Conexao TCP recusada ou timeout");
            invokeOnConnectionFailed("Connection timed out or refused");
            close(m_tcpSocket);
            m_tcpSocket = -1;
            return;
        }

        writeLog("TCP Conectado! Enviando pacote Hello...");

        std::string hello = "{\"t\":\"hello\",\"proto\":3,\"uid\":\"HALLAmobile0000000000000000000=\",\"nick\":\"" 
                            + m_nick + "\",\"pass\":\"" + m_pass + "\",\"ver\":\"1.0.0-mobile\",\"platform\":\"Android\"}\n";
        sendTcp(hello);

        std::thread(&HallaClientCore::pingLoop, this).detach();

        std::string buffer;
        char tempBuf[1024];
        while (m_connected) {
            int n = recv(m_tcpSocket, tempBuf, sizeof(tempBuf) - 1, 0);
            if (n <= 0) {
                writeLog("TCP conexao fechada pelo servidor remoto.");
                break;
            }
            tempBuf[n] = '\0';
            buffer += tempBuf;

            size_t pos;
            while ((pos = buffer.find('\n')) != std::string::npos) {
                std::string line = buffer.substr(0, pos);
                buffer.erase(0, pos + 1);
                handleTcpPacket(line);
            }
        }

        writeLog("TCP Loop finalizado.");
        invokeOnDisconnected();
        disconnect();
    }

    void pingLoop() {
        while (m_connected && m_tcpSocket != -1) {
            std::this_thread::sleep_for(std::chrono::seconds(15));
            if (!m_connected) break;
            sendTcp("{\"t\":\"ping\"}\n");
        }
    }

    void handleTcpPacket(const std::string& line) {
        try {
            writeLog("Recebido pacote TCP de tamanho: " + std::to_string(line.length()));
            std::string t = jsonExtractString(line, "t");
            writeLog("Tipo de pacote extraído (t): " + t);

            if (t == "error") {
                std::string msg = jsonExtractString(line, "msg");
                writeLog("Erro do servidor recebido: " + msg);
                invokeOnConnectionFailed(msg);
                disconnect();
                return;
            }

            if (t == "welcome") {
                std::string serverName;
                std::string motd;
                
                size_t srvPos = line.find("\"server\"");
                writeLog("welcome: srvPos = " + std::to_string(srvPos));
                if (srvPos != std::string::npos) {
                    std::string srvObj = line.substr(srvPos);
                    serverName = jsonExtractString(srvObj, "name");
                    motd = jsonExtractString(srvObj, "motd");
                }
                
                if (serverName.empty()) {
                    serverName = m_host;
                }
                writeLog("welcome: serverName = " + serverName + ", motd = " + motd);

                size_t voicePos = line.find("\"voice\"");
                writeLog("welcome: voicePos = " + std::to_string(voicePos));
                if (voicePos != std::string::npos) {
                    std::string voiceObj = line.substr(voicePos);
                    m_udpPort = jsonExtractInt(voiceObj, "udp");
                    m_voiceToken = safeStoul(jsonExtractString(voiceObj, "token"));
                }
                writeLog("welcome: m_udpPort = " + std::to_string(m_udpPort) + ", m_voiceToken = " + std::to_string(m_voiceToken));

                // Avisa o Kotlin sobre a conexao
                invokeOnConnected(serverName, motd);

                // Passa o JSON inteiro do welcome para o Kotlin de forma nativa e 100% segura
                invokeOnWelcome(line);

                setupUdpVoice();
                return;
            }

            if (t == "voice_token") {
                m_udpPort = jsonExtractInt(line, "udp");
                m_voiceToken = safeStoul(jsonExtractString(line, "token"));
                writeLog("voice_token: m_udpPort = " + std::to_string(m_udpPort) + ", m_voiceToken = " + std::to_string(m_voiceToken));
                setupUdpVoice();
                return;
            }

            if (t == "chat") {
                std::string from = jsonExtractString(line, "fromName");
                std::string text = jsonExtractString(line, "text");
                invokeOnChatMessage(from, text);
                return;
            }

            if (t == "user_joined" || t == "user_left" || t == "user_moved" || t == "chan_update" || t == "chan_removed" || t == "user_state") {
                invokeOnUserList(line);
            }
        } catch (const std::exception& e) {
            writeLog("Excecao na handleTcpPacket: " + std::string(e.what()));
        } catch (...) {
            writeLog("Excecao desconhecida na handleTcpPacket");
        }
    }

    void setupUdpVoice() {
        writeLog("setupUdpVoice iniciado");
        if (m_udpSocket != -1) {
            close(m_udpSocket);
            m_udpSocket = -1;
        }

        m_udpSocket = socket(AF_INET, SOCK_DGRAM, 0);
        if (m_udpSocket == -1) {
            writeLog("Erro: setupUdpVoice nao conseguiu criar socket UDP");
            return;
        }

        struct sockaddr_in localAddr;
        memset(&localAddr, 0, sizeof(localAddr));
        localAddr.sin_family = AF_INET;
        localAddr.sin_port = htons(0);
        localAddr.sin_addr.s_addr = htonl(INADDR_ANY);
        bind(m_udpSocket, (struct sockaddr*)&localAddr, sizeof(localAddr));

        if (m_udpThread.joinable()) m_udpThread.join();
        m_udpThread = std::thread(&HallaClientCore::udpLoop, this);

        // Dispara o primeiro sinal de furo de NAT
        sendVoiceFrame(nullptr, 0, 1);
        writeLog("Socket UDP configurado com sucesso na porta " + std::to_string(m_udpPort) + " com token " + std::to_string(m_voiceToken));

        // DISPARADOR DE PINGS CONTINUOS UDP PARA PUNCHING DE NAT (MANTÉM O CGNAT SEMPRE ATIVO E QUENTE!)
        std::thread(&HallaClientCore::udpPingLoop, this).detach();
        writeLog("[NAT] Loop de keep-alive UDP iniciado (Pings continuos de 5 segundos).");
    }

    void udpPingLoop() {
        while (m_connected && m_udpSocket != -1) {
            std::this_thread::sleep_for(std::chrono::seconds(5));
            if (!m_connected || m_udpSocket == -1) break;
            // Envia um datagrama UDP leve e vazio de 10-bytes. O servidor aprende nosso IP/Porta e nao faz relay.
            sendVoiceFrame(nullptr, 0, 1);
        }
        writeLog("[NAT] Loop de keep-alive UDP finalizado.");
    }

    void udpLoop() {
        writeLog("udpLoop iniciado");
        char buf[2048];
        struct sockaddr_in sender;
        socklen_t len = sizeof(sender);

        while (m_connected && m_udpSocket != -1) {
            int n = recvfrom(m_udpSocket, buf, sizeof(buf), 0, (struct sockaddr*)&sender, &len);
            if (n <= 0) break;

            if (n < 10 || memcmp(buf, "HALL", 4) != 0) continue;

            uint32_t fromId;
            uint16_t seq;
            memcpy(&fromId, buf + 4, 4);
            memcpy(&seq, buf + 8, 2);

            // Descompacta áudio Opus recebido e avisa o Kotlin
            decodeAndNotifyVoice(fromId, seq, buf + 10, n - 10);
        }
        writeLog("udpLoop finalizado");
    }

    std::string m_host;
    std::string m_nick;
    std::string m_pass;
    std::string m_uid;
    int m_tcpSocket;
    int m_udpSocket;
    bool m_connected;
    std::lock_guard<std::mutex>* m_dummy; // unused
    std::mutex m_tcpMutex;
    std::thread m_tcpThread;
    std::thread m_udpThread;

    int m_udpPort;
    uint32_t m_voiceToken;

    // Recursos nativos do Codec Opus
    OpusEncoder* m_encoder;
    std::map<uint32_t, OpusDecoder*> m_decoders;
};

// ============================================================================
// Implementação das Funções JNI do Kotlin
// ============================================================================
extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_vm = vm;
    JNIEnv* env;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    jclass localClass = env->FindClass("com/halla/mobile/HallaCore");
    if (!localClass) {
        return JNI_ERR;
    }
    g_coreClass = reinterpret_cast<jclass>(env->NewGlobalRef(localClass));
    
    g_onConnectedMethod = env->GetStaticMethodID(g_coreClass, "triggerOnConnected", "(Ljava/lang/String;Ljava/lang/String;)V");
    g_onDisconnectedMethod = env->GetStaticMethodID(g_coreClass, "triggerOnDisconnected", "()V");
    g_onWelcomeMethod = env->GetStaticMethodID(g_coreClass, "triggerOnWelcome", "(Ljava/lang/String;)V");
    g_onChannelListMethod = env->GetStaticMethodID(g_coreClass, "triggerOnChannelList", "(Ljava/lang/String;)V");
    g_onUserListMethod = env->GetStaticMethodID(g_coreClass, "triggerOnUserList", "(Ljava/lang/String;)V");
    g_onChatMessageMethod = env->GetStaticMethodID(g_coreClass, "triggerOnChatMessage", "(Ljava/lang/String;Ljava/lang/String;)V");
    g_onAudioFrameMethod = env->GetStaticMethodID(g_coreClass, "triggerOnAudioFrame", "(I[B)V");
    g_onConnectionFailedMethod = env->GetStaticMethodID(g_coreClass, "triggerOnConnectionFailed", "(Ljava/lang/String;)V");

    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL
Java_com_halla_mobile_HallaCore_connectToServer(JNIEnv* env, jclass clazz, jstring host, jint port, jstring nick, jstring pass, jstring cachePath, jstring uid) {
    const char* nativeHost = env->GetStringUTFChars(host, nullptr);
    const char* nativeNick = env->GetStringUTFChars(nick, nullptr);
    const char* nativePass = env->GetStringUTFChars(pass, nullptr);
    const char* nativeCache = env->GetStringUTFChars(cachePath, nullptr);
    const char* nativeUid = env->GetStringUTFChars(uid, nullptr);

    g_cachePath = nativeCache;
    writeLog("connectToServer: g_cachePath configurado para " + g_cachePath);

    HallaClientCore::getInstance().connectToServer(nativeHost, port, nativeNick, nativePass);

    env->ReleaseStringUTFChars(host, nativeHost);
    env->ReleaseStringUTFChars(nick, nativeNick);
    env->ReleaseStringUTFChars(pass, nativePass);
    env->ReleaseStringUTFChars(cachePath, nativeCache);
    env->ReleaseStringUTFChars(uid, nativeUid);
}

JNIEXPORT void JNICALL
Java_com_halla_mobile_HallaCore_disconnectFromServer(JNIEnv* env, jclass clazz) {
    HallaClientCore::getInstance().disconnect();
}

JNIEXPORT void JNICALL
Java_com_halla_mobile_HallaCore_joinChannel(JNIEnv* env, jclass clazz, jint channelId) {
    HallaClientCore::getInstance().joinChannel(channelId);
}

JNIEXPORT void JNICALL
Java_com_halla_mobile_HallaCore_sendChatMessage(JNIEnv* env, jclass clazz, jstring text) {
    const char* nativeText = env->GetStringUTFChars(text, nullptr);
    HallaClientCore::getInstance().sendChatMessage(nativeText);
    env->ReleaseStringUTFChars(text, nativeText);
}

JNIEXPORT void JNICALL
Java_com_halla_mobile_HallaCore_sendVoiceFrame(JNIEnv* env, jclass clazz, jbyteArray pcmData) {
    jsize len = env->GetArrayLength(pcmData);
    jbyte* body = env->GetByteArrayElements(pcmData, nullptr);

    // Envia PCM de 16-bit cru capturado do mic para ser codificado nativamente com Opus
    HallaClientCore::getInstance().encodeAndSendVoice(reinterpret_cast<const int16_t*>(body), len / 2);

    env->ReleaseByteArrayElements(pcmData, body, JNI_ABORT);
}

}
