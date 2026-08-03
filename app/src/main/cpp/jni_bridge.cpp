#include <jni.h>
#include <string>
#include <thread>
#include <mutex>
#include <vector>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <netdb.h>
#include <android/log.h>
#include <cstring>

#define LOG_TAG "HallaCoreJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Global JNI state
static JavaVM* g_vm = nullptr;
static jclass g_coreClass = nullptr;

static jmethodID g_onConnectedMethod = nullptr;
static jmethodID g_onDisconnectedMethod = nullptr;
static jmethodID g_onChannelListMethod = nullptr;
static jmethodID g_onUserListMethod = nullptr;
static jmethodID g_onChatMessageMethod = nullptr;
static jmethodID g_onAudioFrameMethod = nullptr;
static jmethodID g_onConnectionFailedMethod = nullptr;

// Helpers to extract JSON string fields (zero dependencies, ultra fast)
std::string jsonExtractString(const std::string& json, const std::string& key) {
    size_t pos = json.find("\"" + key + "\"");
    if (pos == std::string::npos) return "";
    pos = json.find(":", pos);
    if (pos == std::string::npos) return "";
    pos = json.find("\"", pos);
    if (pos == std::string::npos) return "";
    size_t start = pos + 1;
    size_t end = json.find("\"", start);
    if (end == std::string::npos) return "";
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
    return std::stoi(json.substr(start, end - start));
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
    if (!g_vm || !g_coreClass || !g_onConnectedMethod) return;
    JNIEnv* env = nullptr;
    bool attached = false;
    jint res = g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (res == JNI_EDETACHED) {
        g_vm->AttachCurrentThread(&env, nullptr);
        attached = true;
    }
    if (env) {
        jstring jName = env->NewStringUTF(serverName.c_str());
        jstring jMotd = env->NewStringUTF(motd.c_str());
        env->CallStaticVoidMethod(g_coreClass, g_onConnectedMethod, jName, jMotd);
        env->DeleteLocalRef(jName);
        env->DeleteLocalRef(jMotd);
    }
    if (attached) g_vm->DetachCurrentThread();
}

void invokeOnDisconnected() {
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

void invokeOnChannelList(const std::string& channelsJson) {
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

private:
    HallaClientCore() : m_tcpSocket(-1), m_udpSocket(-1), m_connected(false), m_udpPort(0), m_voiceToken(0) {}
    ~HallaClientCore() { disconnect(); }

    void sendTcp(const std::string& data) {
        std::lock_guard<std::mutex> lock(m_tcpMutex);
        if (m_tcpSocket != -1) {
            send(m_tcpSocket, data.c_str(), data.size(), 0);
        }
    }

    void tcpLoop(const std::string& hostStr, int port) {
        LOGI("TCP Loop: Conectando a %s:%d", hostStr.c_str(), port);
        
        m_tcpSocket = socket(AF_INET, SOCK_STREAM, 0);
        if (m_tcpSocket == -1) {
            invokeOnConnectionFailed("Could not create socket");
            return;
        }

        struct hostent* host = gethostbyname(hostStr.c_str());
        if (!host) {
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
            invokeOnConnectionFailed("Connection timed out or refused");
            close(m_tcpSocket);
            m_tcpSocket = -1;
            return;
        }

        LOGI("TCP Conectado! Enviando pacote Hello...");

        std::string hello = "{\"t\":\"hello\",\"proto\":3,\"uid\":\"HALLAmobile0000000000000000000=\",\"nick\":\"" 
                            + m_nick + "\",\"pass\":\"" + m_pass + "\",\"ver\":\"1.0.0-mobile\",\"platform\":\"Android\"}\n";
        sendTcp(hello);

        std::thread(&HallaClientCore::pingLoop, this).detach();

        std::string buffer;
        char tempBuf[1024];
        while (m_connected) {
            int n = recv(m_tcpSocket, tempBuf, sizeof(tempBuf) - 1, 0);
            if (n <= 0) {
                LOGI("TCP conexao fechada pelo servidor.");
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

        LOGI("TCP Loop finalizado. Desconectando...");
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
        std::string t = jsonExtractString(line, "t");

        if (t == "error") {
            invokeOnConnectionFailed(jsonExtractString(line, "msg"));
            disconnect();
            return;
        }

        if (t == "welcome") {
            std::string serverName = jsonExtractString(line, "name");
            if (serverName.empty()) {
                size_t srvPos = line.find("\"server\"");
                if (srvPos != std::string::npos) {
                    serverName = jsonExtractString(line.substr(srvPos), "name");
                }
            }
            std::string motd = jsonExtractString(line, "motd");
            
            std::string channelsJson = jsonExtractArray(line, "channels");
            std::string usersJson = jsonExtractArray(line, "users");

            size_t voicePos = line.find("\"voice\"");
            if (voicePos != std::string::npos) {
                std::string voiceObj = line.substr(voicePos);
                m_udpPort = jsonExtractInt(voiceObj, "udp");
                m_voiceToken = std::stoul(jsonExtractString(voiceObj, "token"));
            }

            invokeOnConnected(serverName.empty() ? m_host : serverName, motd);
            invokeOnChannelList(channelsJson);
            invokeOnUserList(usersJson);

            setupUdpVoice();
            return;
        }

        if (t == "voice_token") {
            m_udpPort = jsonExtractInt(line, "udp");
            m_voiceToken = std::stoul(jsonExtractString(line, "token"));
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
            invokeOnUserList(line); // Repassa a atualizacao em tempo real para a UI do Kotlin reconstruir a lista
        }
    }

    void setupUdpVoice() {
        if (m_udpSocket != -1) {
            close(m_udpSocket);
            m_udpSocket = -1;
        }

        m_udpSocket = socket(AF_INET, SOCK_DGRAM, 0);
        if (m_udpSocket == -1) {
            LOGE("Erro ao criar socket UDP");
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

        sendVoiceFrame(nullptr, 0, 1);
        LOGI("Socket UDP configurado com sucesso na porta %d com token %u", m_udpPort, m_voiceToken);
    }

    void udpLoop() {
        LOGI("UDP loop de escuta de voz iniciado...");
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

            invokeOnAudioFrame(fromId, buf + 10, n - 10);
        }
        LOGI("UDP loop de voz finalizado.");
    }

    std::string m_host;
    std::string m_nick;
    std::string m_pass;
    int m_tcpSocket;
    int m_udpSocket;
    bool m_connected;
    std::mutex m_tcpMutex;
    std::thread m_tcpThread;
    std::thread m_udpThread;

    int m_udpPort;
    uint32_t m_voiceToken;
};

// ============================================================================
// Implementação das Funções JNI do Kotlin
// ============================================================================
extern "C" {

JNIEXPORT void JNICALL
Java_com_halla_mobile_HallaCore_connectToServer(JNIEnv* env, jclass clazz, jstring host, jint port, jstring nick, jstring pass) {
    const char* nativeHost = env->GetStringUTFChars(host, nullptr);
    const char* nativeNick = env->GetStringUTFChars(nick, nullptr);
    const char* nativePass = env->GetStringUTFChars(pass, nullptr);

    HallaClientCore::getInstance().connectToServer(nativeHost, port, nativeNick, nativePass);

    env->ReleaseStringUTFChars(host, nativeHost);
    env->ReleaseStringUTFChars(nick, nativeNick);
    env->ReleaseStringUTFChars(pass, nativePass);
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
    static uint16_t voiceSeq = 0;
    jsize len = env->GetArrayLength(pcmData);
    jbyte* body = env->GetByteArrayElements(pcmData, nullptr);

    HallaClientCore::getInstance().sendVoiceFrame(reinterpret_cast<const char*>(body), len, ++voiceSeq);

    env->ReleaseByteArrayElements(pcmData, body, JNI_ABORT);
}

}
