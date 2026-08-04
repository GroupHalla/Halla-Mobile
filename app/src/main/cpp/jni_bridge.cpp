#include <jni.h>
#include <string>
#include <thread>
#include <mutex>
#include <atomic>
#include <chrono>
#include <cstdio>
#include <vector>
#include <map>
#include <sys/socket.h>
#include <sys/time.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <netdb.h>
#include <android/log.h>
#include <cstring>
#include <exception>

#ifndef MSG_NOSIGNAL
#define MSG_NOSIGNAL 0
#endif

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
static jmethodID g_onErrorMethod = nullptr;
static jmethodID g_onPokeMethod = nullptr;

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

// Escapa strings antes de inseri-las em mensagens JSON. O cliente mobile
// originalmente montava JSON por concatenação, então aspas, barras e quebras
// de linha em apelidos/senhas/mensagens quebravam o protocolo.
std::string jsonEscape(const std::string& input) {
    std::string out;
    out.reserve(input.size() + 8);
    static const char hex[] = "0123456789abcdef";
    for (unsigned char c : input) {
        switch (c) {
        case '\\': out += "\\\\"; break;
        case '"':  out += "\\\""; break;
        case '\b': out += "\\b"; break;
        case '\f': out += "\\f"; break;
        case '\n': out += "\\n"; break;
        case '\r': out += "\\r"; break;
        case '\t': out += "\\t"; break;
        default:
            if (c < 0x20) {
                out += "\\u00";
                out += hex[(c >> 4) & 0x0f];
                out += hex[c & 0x0f];
            } else {
                out += static_cast<char>(c);
            }
            break;
        }
    }
    return out;
}

// Reverte as sequências JSON mais comuns para que o texto exibido no Android
// não contenha barras extras (por exemplo: \\\"Olá\\\").
std::string jsonUnescape(const std::string& input) {
    std::string out;
    out.reserve(input.size());
    for (size_t i = 0; i < input.size(); ++i) {
        if (input[i] != '\\' || i + 1 >= input.size()) {
            out += input[i];
            continue;
        }
        const char c = input[++i];
        switch (c) {
        case '"': out += '"'; break;
        case '\\': out += '\\'; break;
        case '/': out += '/'; break;
        case 'b': out += '\b'; break;
        case 'f': out += '\f'; break;
        case 'n': out += '\n'; break;
        case 'r': out += '\r'; break;
        case 't': out += '\t'; break;
        default:
            out += '\\';
            out += c;
            break;
        }
    }
    return out;
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
    return jsonUnescape(json.substr(start, end - start));
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

void invokeOnChatMessage(const std::string& scope, int fromUserId, int toUserId,
                         const std::string& fromName, const std::string& text) {
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
        jstring jScope = env->NewStringUTF(scope.c_str());
        jstring jFrom = env->NewStringUTF(fromName.c_str());
        jstring jText = env->NewStringUTF(text.c_str());
        env->CallStaticVoidMethod(g_coreClass, g_onChatMessageMethod,
                                  jScope, fromUserId, toUserId, jFrom, jText);
        env->DeleteLocalRef(jScope);
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

void invokeOnError(const std::string& code, const std::string& msg) {
    writeLog("invokeOnError chamada: " + code + " - " + msg);
    if (!g_vm || !g_coreClass || !g_onErrorMethod) return;
    JNIEnv* env = nullptr;
    bool attached = false;
    jint res = g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (res == JNI_EDETACHED) {
        g_vm->AttachCurrentThread(&env, nullptr);
        attached = true;
    }
    if (env) {
        jstring jCode = env->NewStringUTF(code.c_str());
        jstring jMsg = env->NewStringUTF(msg.c_str());
        env->CallStaticVoidMethod(g_coreClass, g_onErrorMethod, jCode, jMsg);
        env->DeleteLocalRef(jCode);
        env->DeleteLocalRef(jMsg);
    }
    if (attached) g_vm->DetachCurrentThread();
}

void invokeOnPoke(const std::string& fromName, const std::string& msg) {
    writeLog("invokeOnPoke chamada!");
    if (!g_vm || !g_coreClass || !g_onPokeMethod) return;
    JNIEnv* env = nullptr;
    bool attached = false;
    jint res = g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (res == JNI_EDETACHED) {
        g_vm->AttachCurrentThread(&env, nullptr);
        attached = true;
    }
    if (env) {
        jstring jFrom = env->NewStringUTF(fromName.c_str());
        jstring jMsg = env->NewStringUTF(msg.c_str());
        env->CallStaticVoidMethod(g_coreClass, g_onPokeMethod, jFrom, jMsg);
        env->DeleteLocalRef(jFrom);
        env->DeleteLocalRef(jMsg);
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

    void connectToServer(const std::string& host, int port, const std::string& nick,
                         const std::string& pass, const std::string& uid) {
        disconnect();

        m_host = host;
        m_nick = nick;
        m_pass = pass;
        m_uid = uid;
        m_connected = true;
        m_authenticated = false;

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

        // shutdown acorda recv/recvfrom antes do join. O código anterior também
        // tentava dar join na própria thread TCP quando o servidor fechava a
        // conexão, o que terminava o processo com std::terminate.
        {
            std::lock_guard<std::mutex> tcpLock(m_tcpMutex);
            if (m_tcpSocket != -1) {
                shutdown(m_tcpSocket, SHUT_RDWR);
                close(m_tcpSocket);
                m_tcpSocket = -1;
            }
        }
        {
            std::lock_guard<std::mutex> udpLock(m_udpMutex);
            if (m_udpSocket != -1) {
                shutdown(m_udpSocket, SHUT_RDWR);
                close(m_udpSocket);
                m_udpSocket = -1;
            }
        }

        const std::thread::id self = std::this_thread::get_id();
        if (m_pingThread.joinable() && m_pingThread.get_id() != self) m_pingThread.join();
        if (m_natThread.joinable() && m_natThread.get_id() != self) m_natThread.join();
        if (m_udpThread.joinable() && m_udpThread.get_id() != self) m_udpThread.join();
        if (m_tcpThread.joinable() && m_tcpThread.get_id() != self) m_tcpThread.join();
        m_udpPort = 0;
        m_voiceToken = 0;
        m_authenticated = false;

        // Libera recursos do codec Opus somente depois que as threads de áudio
        // terminaram. A captura Android pode chamar encodeAndSendVoice em
        // paralelo com o botão Desconectar.
        {
            std::lock_guard<std::mutex> codecLock(m_codecMutex);
            if (m_encoder) {
                opus_encoder_destroy(m_encoder);
                m_encoder = nullptr;
            }
            for (auto& pair : m_decoders) {
                opus_decoder_destroy(pair.second);
            }
            m_decoders.clear();
        }
        writeLog("Recursos do Codec Opus liberados.");
    }

    void joinChannel(int channelId, const std::string& pass) {
        std::string msg = "{\"t\":\"move\",\"channel\":" + std::to_string(channelId);
        if (!pass.empty()) {
            msg += ",\"pass\":\"" + jsonEscape(pass) + "\"";
        }
        msg += "}\n";
        sendTcp(msg);
    }

    void sendChatMessage(const std::string& text) {
        sendChatMessageScoped("channel", 0, text);
    }

    void sendChatMessageScoped(const std::string& scope, int toUserId, const std::string& text) {
        std::string msg = "{\"t\":\"chat\",\"scope\":\"" + jsonEscape(scope) + "\"";
        if (toUserId > 0) msg += ",\"to\":" + std::to_string(toUserId);
        msg += ",\"text\":\"" + jsonEscape(text) + "\"}\n";
        sendTcp(msg);
    }

    void sendTalking(bool on) {
        sendTcp(std::string("{\"t\":\"talking\",\"on\":") + (on ? "true" : "false") + "}\n");
    }

    void sendStatus(bool mic, bool spk, bool away, bool rec, bool cc) {
        std::string msg = "{\"t\":\"status\",\"mic\":" + std::string(mic ? "true" : "false") + 
                          ",\"spk\":" + std::string(spk ? "true" : "false") + 
                          ",\"away\":" + std::string(away ? "true" : "false") + 
                          ",\"rec\":" + std::string(rec ? "true" : "false") + 
                          ",\"cc\":" + std::string(cc ? "true" : "false") + "}\n";
        sendTcp(msg);
    }

    void sendRename(const std::string& newName) {
        std::string msg = "{\"t\":\"nick\",\"name\":\"" + jsonEscape(newName) + "\"}\n";
        sendTcp(msg);
    }

    void sendPoke(int toUserId, const std::string& text) {
        std::string msg = "{\"t\":\"poke\",\"to\":" + std::to_string(toUserId) +
                          ",\"msg\":\"" + jsonEscape(text) + "\"}\n";
        sendTcp(msg);
    }

    void sendKick(int userId, bool fromServer, const std::string& reason) {
        std::string msg = "{\"t\":\"kick\",\"id\":" + std::to_string(userId) +
                          ",\"from\":\"" + (fromServer ? "server" : "channel") +
                          "\",\"reason\":\"" + jsonEscape(reason) + "\"}\n";
        sendTcp(msg);
    }

    void sendBan(int userId, const std::string& reason, int minutes) {
        std::string msg = "{\"t\":\"ban\",\"id\":" + std::to_string(userId) +
                          ",\"reason\":\"" + jsonEscape(reason) + "\",\"minutes\":" +
                          std::to_string(minutes) + "}\n";
        sendTcp(msg);
    }

    void sendMoveOther(int userId, int channelId) {
        std::string msg = "{\"t\":\"move_other\",\"id\":" + std::to_string(userId) + ",\"channel\":" + std::to_string(channelId) + "}\n";
        sendTcp(msg);
    }

    void sendUsePrivilegeKey(const std::string& key) {
        std::string msg = "{\"t\":\"privkey\",\"key\":\"" + jsonEscape(key) + "\"}\n";
        sendTcp(msg);
    }

    void sendEditChannel(int channelId, const std::string& name, const std::string& desc,
                         const std::string& pass) {
        std::string msg = "{\"t\":\"chan_edit\",\"id\":" + std::to_string(channelId) +
                          ",\"name\":\"" + jsonEscape(name) +
                          "\",\"desc\":\"" + jsonEscape(desc) +
                          "\",\"pass\":\"" + jsonEscape(pass) + "\"}\n";
        sendTcp(msg);
    }

    void sendRawJson(const std::string& json) {
        sendTcp(json + "\n");
    }

    void sendVoiceFrame(const char* pcm, int size, uint16_t seq) {
        std::lock_guard<std::mutex> udpLock(m_udpMutex);
        if (m_udpSocket == -1 || m_udpPort == 0 || m_voiceToken == 0) return;

        std::vector<char> packet(10 + size);
        memcpy(packet.data(), "HALL", 4);
        memcpy(packet.data() + 4, &m_voiceToken, 4);
        memcpy(packet.data() + 8, &seq, 2);
        if (size > 0 && pcm != nullptr) {
            memcpy(packet.data() + 10, pcm, size);
        }

        m_serverUdpAddr.sin_port = htons(m_udpPort);
        sendto(m_udpSocket, packet.data(), packet.size(), 0, (struct sockaddr*)&m_serverUdpAddr, sizeof(m_serverUdpAddr));
    }

    // Codifica PCM bruto de 16-bit Mono @ 48kHz em frames Opus VoIP e transmite
    void encodeAndSendVoice(const int16_t* pcm, int samples) {
        if (!pcm || samples <= 0 || m_udpSocket == -1 || m_udpPort == 0 || m_voiceToken == 0) return;

        std::lock_guard<std::mutex> codecLock(m_codecMutex);
        if (!m_encoder) return;
        static uint16_t voiceSeq = 0;
        unsigned char opusBuf[512];
        int n = opus_encode(m_encoder, pcm, samples, opusBuf, sizeof(opusBuf));
        if (n > 0) {
            sendVoiceFrame(reinterpret_cast<const char*>(opusBuf), n, ++voiceSeq);
        }
    }

private:
    HallaClientCore() : m_tcpSocket(-1), m_udpSocket(-1), m_connected(false),
                         m_authenticated(false), m_udpPort(0), m_voiceToken(0),
                         m_encoder(nullptr) {}
    ~HallaClientCore() { disconnect(); }

    void sendTcp(const std::string& data) {
        std::lock_guard<std::mutex> lock(m_tcpMutex);
        if (m_tcpSocket == -1 || data.empty()) return;
        size_t sent = 0;
        while (sent < data.size() && m_connected) {
            const ssize_t n = send(m_tcpSocket, data.data() + sent,
                                   data.size() - sent, MSG_NOSIGNAL);
            if (n <= 0) break;
            sent += static_cast<size_t>(n);
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
            m_connected = false;
            invokeOnConnectionFailed("Could not create socket");
            return;
        }

        struct hostent* host = gethostbyname(hostStr.c_str());
        if (!host) {
            writeLog("Erro: Nao foi possivel resolver host TCP");
            m_connected = false;
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
            m_connected = false;
            invokeOnConnectionFailed("Connection timed out or refused");
            close(m_tcpSocket);
            m_tcpSocket = -1;
            return;
        }

        writeLog("TCP Conectado! Enviando pacote Hello...");

        const std::string uid = m_uid.empty() ? "HALLAmobile0000000000000000000=" : m_uid;
        std::string hello = "{\"t\":\"hello\",\"proto\":3,\"uid\":\"" +
                            jsonEscape(uid) + "\",\"nick\":\"" + jsonEscape(m_nick) +
                            "\",\"pass\":\"" + jsonEscape(m_pass) +
                            "\",\"ver\":\"1.0.8-mobile\",\"platform\":\"Android\"}\n";
        sendTcp(hello);

        if (m_pingThread.joinable() && m_pingThread.get_id() != std::this_thread::get_id())
            m_pingThread.join();
        m_pingThread = std::thread(&HallaClientCore::pingLoop, this);

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
        m_connected = false;
        invokeOnDisconnected();
        // A limpeza e o join são feitos pela thread da UI via
        // disconnectFromServer(). Nunca faça join da própria thread aqui.
    }

    void pingLoop() {
        while (m_connected && m_tcpSocket != -1) {
            // Dorme em pequenos intervalos para desconectar sem esperar os
            // 15 segundos completos do keep-alive.
            for (int i = 0; i < 150 && m_connected; ++i)
                std::this_thread::sleep_for(std::chrono::milliseconds(100));
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
                std::string code = jsonExtractString(line, "code");
                std::string msg = jsonExtractString(line, "msg");
                writeLog("Erro do servidor recebido: " + code + " - " + msg);
                if (!m_authenticated) {
                    m_connected = false;
                    invokeOnConnectionFailed(msg.empty() ? code : msg);
                } else {
                    // Erros de permissão, senha de canal etc. são eventos da
                    // sessão; não podem derrubar a conexão inteira.
                    invokeOnError(code, msg);
                }
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

                // Prepara o UDP ANTES de notificar o Kotlin. O callback de
                // onConnected inicia a captura; antes isso criava uma janela
                // em que os primeiros frames eram descartados porque o socket
                // de voz ainda não existia.
                setupUdpVoice();
                m_authenticated = true;

                // Avisa o Kotlin sobre a conexão somente depois que TCP+UDP
                // estão prontos.
                invokeOnConnected(serverName, motd);
                invokeOnWelcome(line);
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
                std::string scope = jsonExtractString(line, "scope");
                int fromId = jsonExtractInt(line, "from");
                int toId = jsonExtractInt(line, "to");
                std::string from = jsonExtractString(line, "fromName");
                std::string text = jsonExtractString(line, "text");
                invokeOnChatMessage(scope, fromId, toId, from, text);
                return;
            }

            if (t == "poke") {
                std::string from = jsonExtractString(line, "fromName");
                std::string msg = jsonExtractString(line, "msg");
                invokeOnPoke(from, msg);
                return;
            }

            if (t == "user_joined" || t == "user_left" || t == "user_moved" ||
                t == "chan_update" || t == "chan_removed" || t == "user_state" ||
                t == "user_nick" || t == "user_desc" || t == "user_group" ||
                t == "server_edit") {
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

        // Se um voice_token novo chegar, encerra o socket/threads anteriores
        // de forma ordenada antes de trocar o endpoint.
        {
            std::lock_guard<std::mutex> udpLock(m_udpMutex);
            if (m_udpSocket != -1) {
                shutdown(m_udpSocket, SHUT_RDWR);
                close(m_udpSocket);
                m_udpSocket = -1;
            }
        }
        if (m_udpThread.joinable() && m_udpThread.get_id() != std::this_thread::get_id())
            m_udpThread.join();
        if (m_natThread.joinable() && m_natThread.get_id() != std::this_thread::get_id())
            m_natThread.join();

        std::unique_lock<std::mutex> udpLock(m_udpMutex);
        m_udpSocket = socket(AF_INET, SOCK_DGRAM, 0);
        if (m_udpSocket == -1) {
            writeLog("Erro: setupUdpVoice nao conseguiu criar socket UDP");
            return;
        }

        // Timeout curto permite que a thread UDP observe desconexões sem ficar
        // bloqueada indefinidamente em recvfrom.
        struct timeval timeout;
        timeout.tv_sec = 1;
        timeout.tv_usec = 0;
        setsockopt(m_udpSocket, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));

        struct sockaddr_in localAddr;
        memset(&localAddr, 0, sizeof(localAddr));
        localAddr.sin_family = AF_INET;
        localAddr.sin_port = htons(0);
        localAddr.sin_addr.s_addr = htonl(INADDR_ANY);
        if (bind(m_udpSocket, (struct sockaddr*)&localAddr, sizeof(localAddr)) < 0) {
            writeLog("Erro: bind UDP falhou");
            close(m_udpSocket);
            m_udpSocket = -1;
            return;
        }

        // Resolve o host do servidor uma vez de forma robusta e persistente.
        memset(&m_serverUdpAddr, 0, sizeof(m_serverUdpAddr));
        m_serverUdpAddr.sin_family = AF_INET;
        m_serverUdpAddr.sin_port = htons(m_udpPort);
        if (inet_pton(AF_INET, m_host.c_str(), &m_serverUdpAddr.sin_addr) == 1) {
            writeLog("[NAT] IP do servidor resolvido via inet_pton.");
        } else {
            struct hostent* host = gethostbyname(m_host.c_str());
            if (host) {
                memcpy(&m_serverUdpAddr.sin_addr.s_addr, host->h_addr_list[0], host->h_length);
                writeLog("[NAT] Host do servidor resolvido via gethostbyname.");
            } else {
                writeLog("Erro: nao foi possivel resolver o host UDP");
                close(m_udpSocket);
                m_udpSocket = -1;
                return;
            }
        }

        udpLock.unlock();
        m_udpThread = std::thread(&HallaClientCore::udpLoop, this);

        // O primeiro pacote de voz não pode depender de o PC ter falado antes.
        // Envia três registros imediatos do endpoint para atravessar NATs que
        // descartam o primeiro datagrama UDP e para garantir que o servidor já
        // conheça a porta local antes de a captura começar.
        for (int i = 0; i < 3; ++i) {
            sendVoiceFrame(nullptr, 0, static_cast<uint16_t>(i + 1));
            usleep(50000);
        }
        writeLog("Socket UDP configurado na porta " + std::to_string(m_udpPort) +
                 " com token " + std::to_string(m_voiceToken));

        m_natThread = std::thread(&HallaClientCore::udpPingLoop, this);
        writeLog("[NAT] Loop de keep-alive UDP iniciado.");
    }

    void udpPingLoop() {
        while (m_connected && m_udpSocket != -1) {
            for (int i = 0; i < 50 && m_connected; ++i)
                std::this_thread::sleep_for(std::chrono::milliseconds(100));
            if (!m_connected || m_udpSocket == -1) break;
            sendVoiceFrame(nullptr, 0, 1);
        }
        writeLog("[NAT] Loop de keep-alive UDP finalizado.");
    }

    void udpLoop() {
        writeLog("udpLoop iniciado");
        char buf[2048];
        struct sockaddr_in sender;

        while (m_connected && m_udpSocket != -1) {
            socklen_t len = sizeof(sender);
            int n = recvfrom(m_udpSocket, buf, sizeof(buf), 0,
                             (struct sockaddr*)&sender, &len);
            if (n <= 0) continue; // timeout de 1 s ou socket sendo encerrado

            if (n < 10 || memcmp(buf, "HALL", 4) != 0) continue;

            uint32_t fromId;
            uint16_t seq;
            memcpy(&fromId, buf + 4, 4);
            memcpy(&seq, buf + 8, 2);

            // Descompacta áudio Opus recebido e avisa o Kotlin.
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
    std::atomic<bool> m_connected;
    std::atomic<bool> m_authenticated;
    std::mutex m_tcpMutex;
    std::mutex m_udpMutex;
    std::mutex m_codecMutex;
    std::thread m_tcpThread;
    std::thread m_udpThread;
    std::thread m_pingThread;
    std::thread m_natThread;

    int m_udpPort;
    uint32_t m_voiceToken;
    struct sockaddr_in m_serverUdpAddr;

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
    g_onChatMessageMethod = env->GetStaticMethodID(g_coreClass, "triggerOnChatMessage", "(Ljava/lang/String;IILjava/lang/String;Ljava/lang/String;)V");
    g_onAudioFrameMethod = env->GetStaticMethodID(g_coreClass, "triggerOnAudioFrame", "(I[B)V");
    g_onConnectionFailedMethod = env->GetStaticMethodID(g_coreClass, "triggerOnConnectionFailed", "(Ljava/lang/String;)V");
    g_onErrorMethod = env->GetStaticMethodID(g_coreClass, "triggerOnError", "(Ljava/lang/String;Ljava/lang/String;)V");
    g_onPokeMethod = env->GetStaticMethodID(g_coreClass, "triggerOnPoke", "(Ljava/lang/String;Ljava/lang/String;)V");

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

    HallaClientCore::getInstance().connectToServer(nativeHost, port, nativeNick, nativePass, nativeUid);

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
Java_com_halla_mobile_HallaCore_joinChannel(JNIEnv* env, jclass clazz, jint channelId, jstring pass) {
    const char* nativePass = env->GetStringUTFChars(pass, nullptr);
    HallaClientCore::getInstance().joinChannel(channelId, nativePass);
    env->ReleaseStringUTFChars(pass, nativePass);
}

JNIEXPORT void JNICALL
Java_com_halla_mobile_HallaCore_sendChatMessage(JNIEnv* env, jclass clazz, jstring text) {
    const char* nativeText = env->GetStringUTFChars(text, nullptr);
    HallaClientCore::getInstance().sendChatMessage(nativeText);
    env->ReleaseStringUTFChars(text, nativeText);
}

JNIEXPORT void JNICALL
Java_com_halla_mobile_HallaCore_sendChatMessageScoped(JNIEnv* env, jclass clazz,
                                                       jstring scope, jint toUserId,
                                                       jstring text) {
    const char* nativeScope = env->GetStringUTFChars(scope, nullptr);
    const char* nativeText = env->GetStringUTFChars(text, nullptr);
    HallaClientCore::getInstance().sendChatMessageScoped(nativeScope, toUserId, nativeText);
    env->ReleaseStringUTFChars(scope, nativeScope);
    env->ReleaseStringUTFChars(text, nativeText);
}

JNIEXPORT void JNICALL
Java_com_halla_mobile_HallaCore_sendTalking(JNIEnv* env, jclass clazz, jboolean on) {
    HallaClientCore::getInstance().sendTalking(on == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_halla_mobile_HallaCore_sendVoiceFrame(JNIEnv* env, jclass clazz, jbyteArray pcmData) {
    jsize len = env->GetArrayLength(pcmData);
    jbyte* body = env->GetByteArrayElements(pcmData, nullptr);

    // Envia PCM de 16-bit cru capturado do mic para ser codificado nativamente com Opus
    HallaClientCore::getInstance().encodeAndSendVoice(reinterpret_cast<const int16_t*>(body), len / 2);

    env->ReleaseByteArrayElements(pcmData, body, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_com_halla_mobile_HallaCore_sendRawJson(JNIEnv* env, jclass clazz, jstring json) {
    const char* nativeText = env->GetStringUTFChars(json, nullptr);
    HallaClientCore::getInstance().sendRawJson(nativeText);
    env->ReleaseStringUTFChars(json, nativeText);
}

JNIEXPORT void JNICALL
Java_com_halla_mobile_HallaCore_sendStatus(JNIEnv* env, jclass clazz, jboolean mic, jboolean spk, jboolean away, jboolean rec, jboolean cc) {
    HallaClientCore::getInstance().sendStatus(mic, spk, away, rec, cc);
}

JNIEXPORT void JNICALL
Java_com_halla_mobile_HallaCore_sendRename(JNIEnv* env, jclass clazz, jstring newName) {
    const char* nativeText = env->GetStringUTFChars(newName, nullptr);
    HallaClientCore::getInstance().sendRename(nativeText);
    env->ReleaseStringUTFChars(newName, nativeText);
}

JNIEXPORT void JNICALL
Java_com_halla_mobile_HallaCore_sendPoke(JNIEnv* env, jclass clazz, jint toUserId, jstring msg) {
    const char* nativeText = env->GetStringUTFChars(msg, nullptr);
    HallaClientCore::getInstance().sendPoke(toUserId, nativeText);
    env->ReleaseStringUTFChars(msg, nativeText);
}

JNIEXPORT void JNICALL
Java_com_halla_mobile_HallaCore_sendKick(JNIEnv* env, jclass clazz, jint userId, jboolean fromServer, jstring reason) {
    const char* nativeText = env->GetStringUTFChars(reason, nullptr);
    HallaClientCore::getInstance().sendKick(userId, fromServer, nativeText);
    env->ReleaseStringUTFChars(reason, nativeText);
}

JNIEXPORT void JNICALL
Java_com_halla_mobile_HallaCore_sendBan(JNIEnv* env, jclass clazz, jint userId, jstring reason, jint minutes) {
    const char* nativeText = env->GetStringUTFChars(reason, nullptr);
    HallaClientCore::getInstance().sendBan(userId, nativeText, minutes);
    env->ReleaseStringUTFChars(reason, nativeText);
}

JNIEXPORT void JNICALL
Java_com_halla_mobile_HallaCore_sendMoveOther(JNIEnv* env, jclass clazz, jint userId, jint channelId) {
    HallaClientCore::getInstance().sendMoveOther(userId, channelId);
}

JNIEXPORT void JNICALL
Java_com_halla_mobile_HallaCore_sendUsePrivilegeKey(JNIEnv* env, jclass clazz, jstring key) {
    const char* nativeText = env->GetStringUTFChars(key, nullptr);
    HallaClientCore::getInstance().sendUsePrivilegeKey(nativeText);
    env->ReleaseStringUTFChars(key, nativeText);
}

JNIEXPORT void JNICALL
Java_com_halla_mobile_HallaCore_sendEditChannel(JNIEnv* env, jclass clazz, jint channelId, jstring name, jstring desc, jstring pass) {
    const char* nativeName = env->GetStringUTFChars(name, nullptr);
    const char* nativeDesc = env->GetStringUTFChars(desc, nullptr);
    const char* nativePass = env->GetStringUTFChars(pass, nullptr);
    HallaClientCore::getInstance().sendEditChannel(channelId, nativeName, nativeDesc, nativePass);
    env->ReleaseStringUTFChars(name, nativeName);
    env->ReleaseStringUTFChars(desc, nativeDesc);
    env->ReleaseStringUTFChars(pass, nativePass);
}

}
