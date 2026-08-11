#include <jni.h>
#include <string>
#include <thread>
#include <mutex>
#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstdint>
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
#include <algorithm>
#include <cerrno>
#include <fstream>
#include <sstream>
#include <iomanip>
#include <cctype>

#include <mbedtls/ssl.h>
#include <mbedtls/ctr_drbg.h>
#include <mbedtls/entropy.h>
#include <mbedtls/error.h>
#include <mbedtls/net_sockets.h>
#include <mbedtls/sha256.h>
#include <mbedtls/base64.h>
#include <mbedtls/chachapoly.h>

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
static jmethodID g_onPingMethod = nullptr;
static jmethodID g_onPokeMethod = nullptr;
static jmethodID g_onScreenShareFrameMethod = nullptr;
static jmethodID g_onWebRtcSignalMethod = nullptr;
static jmethodID g_identityPublicKeyMethod = nullptr;
static jmethodID g_signIdentityNonceMethod = nullptr;

static std::string g_cachePath = "";
static constexpr size_t kMaxJsonLineBytes = 2 * 1024 * 1024;

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

uint64_t nowMs() {
    return static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count());
}

std::string hexEncode(const unsigned char* data, size_t len) {
    std::ostringstream oss;
    oss << std::hex << std::setfill('0');
    for (size_t i = 0; i < len; ++i)
        oss << std::setw(2) << static_cast<int>(data[i]);
    return oss.str();
}

std::vector<char> base64DecodeBytes(const std::string& b64) {
    if (b64.empty()) return {};
    size_t outLen = 0;
    mbedtls_base64_decode(nullptr, 0, &outLen,
                          reinterpret_cast<const unsigned char*>(b64.data()), b64.size());
    if (outLen == 0) return {};
    std::vector<unsigned char> out(outLen);
    if (mbedtls_base64_decode(out.data(), out.size(), &outLen,
                              reinterpret_cast<const unsigned char*>(b64.data()), b64.size()) != 0) {
        return {};
    }
    return std::vector<char>(reinterpret_cast<char*>(out.data()),
                             reinterpret_cast<char*>(out.data()) + outLen);
}

std::vector<unsigned char> makeVoiceNonce(uint32_t senderId, uint32_t counter, uint16_t seq) {
    std::vector<unsigned char> nonce(12, 0);
    memcpy(nonce.data(), &senderId, 4);
    memcpy(nonce.data() + 4, &counter, 4);
    memcpy(nonce.data() + 8, &seq, 2);
    return nonce;
}

std::vector<char> voiceEncryptAead(const char* data, int size, const std::vector<char>& key,
                                   uint32_t senderId, uint16_t seq, uint32_t counter) {
    std::vector<char> out;
    if (!data || size <= 0) return out;
    if (key.size() < 32) return std::vector<char>(data, data + size);
    std::vector<unsigned char> nonce = makeVoiceNonce(senderId, counter, seq);
    std::vector<unsigned char> cipher(size);
    unsigned char tag[16];
    mbedtls_chachapoly_context ctx;
    mbedtls_chachapoly_init(&ctx);
    int ret = mbedtls_chachapoly_setkey(&ctx, reinterpret_cast<const unsigned char*>(key.data()));
    if (ret == 0) {
        ret = mbedtls_chachapoly_encrypt_and_tag(&ctx, size, nonce.data(), nullptr, 0,
            reinterpret_cast<const unsigned char*>(data), cipher.data(), tag);
    }
    mbedtls_chachapoly_free(&ctx);
    if (ret != 0) return out;
    out.reserve(4 + size + 16);
    out.insert(out.end(), reinterpret_cast<char*>(&counter), reinterpret_cast<char*>(&counter) + 4);
    out.insert(out.end(), reinterpret_cast<char*>(cipher.data()), reinterpret_cast<char*>(cipher.data()) + cipher.size());
    out.insert(out.end(), reinterpret_cast<char*>(tag), reinterpret_cast<char*>(tag) + 16);
    return out;
}

std::vector<char> voiceDecryptAead(const char* data, int size, const std::vector<char>& key,
                                   uint32_t senderId, uint16_t seq) {
    std::vector<char> out;
    if (!data || size <= 0) return out;
    // Chaves de 16 bytes são do formato legado XOR. Não trate como plaintext,
    // senão a transmissão de Desktop antigo monta JPEG preto e nunca tenta o
    // fallback legacy correto.
    if (key.size() < 32) return out;
    if (size < 4 + 16) return out;
    uint32_t counter = 0;
    memcpy(&counter, data, 4);
    const int cipherLen = size - 4 - 16;
    const unsigned char* cipher = reinterpret_cast<const unsigned char*>(data + 4);
    const unsigned char* tag = reinterpret_cast<const unsigned char*>(data + 4 + cipherLen);
    std::vector<unsigned char> nonce = makeVoiceNonce(senderId, counter, seq);
    std::vector<unsigned char> plain(cipherLen);
    mbedtls_chachapoly_context ctx;
    mbedtls_chachapoly_init(&ctx);
    int ret = mbedtls_chachapoly_setkey(&ctx, reinterpret_cast<const unsigned char*>(key.data()));
    if (ret == 0) {
        ret = mbedtls_chachapoly_auth_decrypt(&ctx, cipherLen, nonce.data(), nullptr, 0,
            tag, cipher, plain.data());
    }
    mbedtls_chachapoly_free(&ctx);
    if (ret != 0) return out;
    out.assign(reinterpret_cast<char*>(plain.data()), reinterpret_cast<char*>(plain.data()) + plain.size());
    return out;
}

std::vector<char> voiceDecryptLegacyXor(const char* data, int size, const std::vector<char>& key, uint16_t seq) {
    std::vector<char> output;
    if (!data || size <= 0 || key.size() < 16) return output;
    output.assign(data, data + size);

    uint32_t state[4];
    memcpy(state, key.data(), 16);
    state[0] ^= seq;
    state[1] ^= (static_cast<uint32_t>(seq) << 16);
    state[2] ^= 0xDEADBEEF;
    state[3] ^= 0xCAFEBABE;

    auto rotl = [](uint32_t x, int k) -> uint32_t {
        return (x << k) | (x >> (32 - k));
    };
    auto next = [&]() -> uint32_t {
        const uint32_t result = rotl(state[0] + state[3], 7) * 9;
        const uint32_t t = state[1] << 9;
        state[2] ^= state[0];
        state[3] ^= state[1];
        state[1] ^= state[2];
        state[0] ^= state[3];
        state[2] ^= t;
        state[3] = rotl(state[3], 11);
        return result;
    };

    for (int i = 0; i < size; i += 4) {
        uint32_t ks = next();
        const int limit = std::min(4, size - i);
        for (int j = 0; j < limit; ++j) {
            output[i + j] ^= reinterpret_cast<char*>(&ks)[j];
        }
    }
    return output;
}

static int tlsSendCallback(void* ctx, const unsigned char* buf, size_t len) {
    int fd = *static_cast<int*>(ctx);
    const ssize_t r = send(fd, buf, len, MSG_NOSIGNAL);
    if (r >= 0) return static_cast<int>(r);
    if (errno == EAGAIN || errno == EWOULDBLOCK) return MBEDTLS_ERR_SSL_WANT_WRITE;
    return MBEDTLS_ERR_NET_SEND_FAILED;
}

static int tlsRecvCallback(void* ctx, unsigned char* buf, size_t len) {
    int fd = *static_cast<int*>(ctx);
    const ssize_t r = recv(fd, buf, len, 0);
    if (r > 0) return static_cast<int>(r);
    if (r == 0) return MBEDTLS_ERR_SSL_PEER_CLOSE_NOTIFY;
    if (errno == EAGAIN || errno == EWOULDBLOCK) return MBEDTLS_ERR_SSL_WANT_READ;
    return MBEDTLS_ERR_NET_RECV_FAILED;
}

// Helpers to extract JSON string fields (zero dependencies, ultra fast & safe)
std::string jsonExtractString(const std::string& json, const std::string& key) {
    if (json.size() > kMaxJsonLineBytes || key.size() > 64) return "";
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
    if (json.size() > kMaxJsonLineBytes || key.size() > 64) return 0;
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

uint64_t jsonExtractUint64(const std::string& json, const std::string& key) {
    if (json.size() > kMaxJsonLineBytes || key.size() > 64) return 0;
    size_t pos = json.find("\"" + key + "\"");
    if (pos == std::string::npos) return 0;
    pos = json.find(":", pos);
    if (pos == std::string::npos) return 0;
    while (pos < json.size() && (json[pos] == ':' || json[pos] == ' ' || json[pos] == '\t')) pos++;
    uint64_t value = 0;
    bool found = false;
    while (pos < json.size() && json[pos] >= '0' && json[pos] <= '9') {
        found = true;
        value = value * 10 + static_cast<uint64_t>(json[pos] - '0');
        ++pos;
    }
    return found ? value : 0;
}

std::string jsonExtractArray(const std::string& json, const std::string& key) {
    if (json.size() > kMaxJsonLineBytes || key.size() > 64) return "[]";
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

std::string jsonExtractObject(const std::string& json, const std::string& key) {
    if (json.size() > kMaxJsonLineBytes || key.size() > 64) return "{}";
    size_t pos = json.find("\"" + key + "\"");
    if (pos == std::string::npos) return "{}";
    pos = json.find("{", pos);
    if (pos == std::string::npos) return "{}";
    const size_t start = pos;
    int depth = 0;
    bool inString = false;
    bool escaped = false;
    for (size_t i = start; i < json.size(); ++i) {
        const char c = json[i];
        if (inString) {
            if (escaped) escaped = false;
            else if (c == '\\') escaped = true;
            else if (c == '"') inString = false;
            continue;
        }
        if (c == '"') inString = true;
        else if (c == '{') ++depth;
        else if (c == '}') {
            --depth;
            if (depth == 0) return json.substr(start, i - start + 1);
        }
    }
    return "{}";
}

std::vector<std::string> jsonObjectStringValues(const std::string& obj) {
    std::vector<std::string> values;
    size_t pos = 0;
    while (true) {
        pos = obj.find(':', pos);
        if (pos == std::string::npos) break;
        pos = obj.find('"', pos);
        if (pos == std::string::npos) break;
        size_t start = pos + 1;
        size_t end = start;
        while (end < obj.size()) {
            end = obj.find('"', end);
            if (end == std::string::npos) return values;
            if (obj[end - 1] != '\\') break;
            ++end;
        }
        values.push_back(jsonUnescape(obj.substr(start, end - start)));
        pos = end + 1;
    }
    return values;
}


std::vector<std::pair<std::string, std::string>> jsonObjectStringMap(const std::string& obj) {
    std::vector<std::pair<std::string, std::string>> out;
    size_t pos = 0;
    auto readString = [&](size_t& cursor, std::string& value) -> bool {
        cursor = obj.find('"', cursor);
        if (cursor == std::string::npos) return false;
        const size_t start = cursor + 1;
        size_t end = start;
        while (end < obj.size()) {
            end = obj.find('"', end);
            if (end == std::string::npos) return false;
            if (obj[end - 1] != '\\') break;
            ++end;
        }
        value = jsonUnescape(obj.substr(start, end - start));
        cursor = end + 1;
        return true;
    };
    while (pos < obj.size()) {
        std::string key, value;
        if (!readString(pos, key)) break;
        pos = obj.find(':', pos);
        if (pos == std::string::npos) break;
        ++pos;
        if (!readString(pos, value)) break;
        out.emplace_back(key, value);
    }
    return out;
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

void invokeOnScreenShareFrame(int fromUserId, const char* data, int size) {
    if (!g_vm || !g_coreClass || !g_onScreenShareFrameMethod || !data || size <= 0) return;
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
        env->CallStaticVoidMethod(g_coreClass, g_onScreenShareFrameMethod, fromUserId, jArr);
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

void invokeOnPing(int pingMs, int packetLossPercent) {
    if (!g_vm || !g_coreClass || !g_onPingMethod) return;
    JNIEnv* env = nullptr;
    bool attached = false;
    jint res = g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (res == JNI_EDETACHED) {
        g_vm->AttachCurrentThread(&env, nullptr);
        attached = true;
    }
    if (env) {
        env->CallStaticVoidMethod(g_coreClass, g_onPingMethod, pingMs, packetLossPercent);
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

void invokeOnWebRtcSignal(const std::string& signalJson) {
    if (!g_vm || !g_coreClass || !g_onWebRtcSignalMethod) return;
    JNIEnv* env = nullptr;
    bool attached = false;
    jint res = g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (res == JNI_EDETACHED) { g_vm->AttachCurrentThread(&env, nullptr); attached = true; }
    if (env) {
        jstring j = env->NewStringUTF(signalJson.c_str());
        env->CallStaticVoidMethod(g_coreClass, g_onWebRtcSignalMethod, j);
        env->DeleteLocalRef(j);
    }
    if (attached) g_vm->DetachCurrentThread();
}

std::string callStaticStringString(jmethodID method, const std::string& a) {
    if (!g_vm || !g_coreClass || !method) return "";
    JNIEnv* env = nullptr;
    bool attached = false;
    jint res = g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (res == JNI_EDETACHED) { g_vm->AttachCurrentThread(&env, nullptr); attached = true; }
    std::string out;
    if (env) {
        jstring ja = env->NewStringUTF(a.c_str());
        jstring jr = (jstring)env->CallStaticObjectMethod(g_coreClass, method, ja);
        env->DeleteLocalRef(ja);
        if (jr) {
            const char* chars = env->GetStringUTFChars(jr, nullptr);
            if (chars) { out = chars; env->ReleaseStringUTFChars(jr, chars); }
            env->DeleteLocalRef(jr);
        }
    }
    if (attached) g_vm->DetachCurrentThread();
    return out;
}

std::string callStaticStringStringString(jmethodID method, const std::string& a, const std::string& b) {
    if (!g_vm || !g_coreClass || !method) return "";
    JNIEnv* env = nullptr;
    bool attached = false;
    jint res = g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (res == JNI_EDETACHED) { g_vm->AttachCurrentThread(&env, nullptr); attached = true; }
    std::string out;
    if (env) {
        jstring ja = env->NewStringUTF(a.c_str());
        jstring jb = env->NewStringUTF(b.c_str());
        jstring jr = (jstring)env->CallStaticObjectMethod(g_coreClass, method, ja, jb);
        env->DeleteLocalRef(ja); env->DeleteLocalRef(jb);
        if (jr) {
            const char* chars = env->GetStringUTFChars(jr, nullptr);
            if (chars) { out = chars; env->ReleaseStringUTFChars(jr, chars); }
            env->DeleteLocalRef(jr);
        }
    }
    if (attached) g_vm->DetachCurrentThread();
    return out;
}

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
        m_pingPending = false;
        m_lastPingSentMs = 0;
        m_pingTotal = 0;
        m_pingSuccess = 0;
        m_cryptoCounter = 0;
        {
            std::lock_guard<std::mutex> keyLock(m_keyMutex);
            m_channelKeys.clear();
            m_currentVoiceKey.clear();
            m_currentChannelId = 0;
            m_screenReassembly.clear();
        }

        // Inicializa o codificador Opus Oficial de voz VoIP
        int err = 0;
        m_encoder = opus_encoder_create(48000, 1, OPUS_APPLICATION_VOIP, &err);
        if (m_encoder) {
            opus_encoder_ctl(m_encoder, OPUS_SET_BITRATE(48000));
            opus_encoder_ctl(m_encoder, OPUS_SET_VBR(0));
            opus_encoder_ctl(m_encoder, OPUS_SET_DTX(0));
            opus_encoder_ctl(m_encoder, OPUS_SET_INBAND_FEC(1));
            opus_encoder_ctl(m_encoder, OPUS_SET_PACKET_LOSS_PERC(10));
            opus_encoder_ctl(m_encoder, OPUS_SET_COMPLEXITY(5));
            writeLog("Opus Encoder nativo inicializado com FEC/CBR para voz estável.");
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
            if (m_tlsReady) {
                mbedtls_ssl_close_notify(&m_ssl);
                m_tlsReady = false;
            }
            if (m_tcpSocket != -1) {
                shutdown(m_tcpSocket, SHUT_RDWR);
                close(m_tcpSocket);
                m_tcpSocket = -1;
            }
        }
        {
            std::lock_guard<std::mutex> udpLock(m_udpMutex);
            const int socketFd = m_udpSocket.load();
            if (socketFd != -1) {
                shutdown(socketFd, SHUT_RDWR);
                close(socketFd);
                m_udpSocket.store(-1);
            }
        }

        const std::thread::id self = std::this_thread::get_id();
        if (m_pingThread.joinable() && m_pingThread.get_id() != self) m_pingThread.join();
        if (m_natThread.joinable() && m_natThread.get_id() != self) m_natThread.join();
        if (m_udpThread.joinable() && m_udpThread.get_id() != self) m_udpThread.join();
        if (m_tcpThread.joinable() && m_tcpThread.get_id() != self) m_tcpThread.join();
        {
            std::lock_guard<std::mutex> tcpLock(m_tcpMutex);
            freeTlsLocked();
        }
        m_udpPort.store(0);
        m_voiceToken.store(0);
        m_authenticated = false;
        m_pingPending = false;

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
            m_encodePcm.clear();
            m_voiceSeq = 0;
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

    void sendSetCommander(int userId, bool on) {
        std::string msg = "{\"t\":\"commander\",\"id\":" + std::to_string(userId) +
                          ",\"on\":" + std::string(on ? "true" : "false") + "}\n";
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
                         const std::string& pass, int bitrate, bool noSymbol) {
        std::string msg = "{\"t\":\"chan_edit\",\"id\":" + std::to_string(channelId) +
                          ",\"name\":\"" + jsonEscape(name) +
                          "\",\"desc\":\"" + jsonEscape(desc) +
                          "\",\"pass\":\"" + jsonEscape(pass) +
                          "\",\"bitrate\":" + std::to_string(bitrate) +
                          ",\"noSymbol\":" + std::string(noSymbol ? "true" : "false") + "}\n";
        sendTcp(msg);
    }

    void sendRawJson(const std::string& json) {
        sendTcp(json + "\n");
    }

    void sendVoiceFrame(const char* pcm, int size, uint16_t seq) {
        std::lock_guard<std::mutex> udpLock(m_udpMutex);
        const int socketFd = m_udpSocket.load();
        const int udpPort = m_udpPort.load();
        const uint32_t voiceToken = m_voiceToken.load();
        if (socketFd == -1 || udpPort == 0 || voiceToken == 0) return;

        std::vector<char> payload;
        if (size > 0 && pcm != nullptr) {
            std::lock_guard<std::mutex> keyLock(m_keyMutex);
            payload = voiceEncryptAead(pcm, size, m_currentVoiceKey, m_selfId.load(), seq, ++m_cryptoCounter);
        }

        std::vector<char> packet(10 + payload.size());
        memcpy(packet.data(), "HALL", 4);
        memcpy(packet.data() + 4, &voiceToken, 4);
        memcpy(packet.data() + 8, &seq, 2);
        if (!payload.empty()) {
            memcpy(packet.data() + 10, payload.data(), payload.size());
        }

        m_serverUdpAddr.sin_port = htons(static_cast<uint16_t>(udpPort));
        const ssize_t sent = sendto(socketFd, packet.data(), packet.size(), 0,
                                    (struct sockaddr*)&m_serverUdpAddr,
                                    sizeof(m_serverUdpAddr));
        if (sent < 0) {
            writeLog("UDP: falha ao enviar frame de voz");
        }
    }

    // Envia um frame Opus de silêncio válido para registrar o endpoint UDP.
    // Alguns servidores/relays antigos ignoram o datagrama HALL de 10 bytes
    // usado apenas como keep-alive; nesse caso o destinatário só passa a ser
    // conhecido depois que alguém fala. Um frame Opus não vazio resolve o
    // handshake sem produzir áudio audível.
    void sendVoiceRegistration(uint16_t seq) {
        int16_t silence[960] = {};
        unsigned char opusBuf[512];
        int encoded = 0;
        {
            std::lock_guard<std::mutex> codecLock(m_codecMutex);
            if (m_encoder) {
                // DTX pode retornar um frame vazio para silêncio. Durante o
                // registro precisamos obrigatoriamente de um payload Opus
                // não vazio para abrir o caminho UDP em NATs/relays antigos.
                opus_encoder_ctl(m_encoder, OPUS_SET_DTX(0));
                encoded = opus_encode(m_encoder, silence, 960, opusBuf, sizeof(opusBuf));
                opus_encoder_ctl(m_encoder, OPUS_SET_DTX(1));
            }
        }
        if (encoded > 0)
            sendVoiceFrame(reinterpret_cast<const char*>(opusBuf), encoded, seq);
        else
            sendVoiceFrame(nullptr, 0, seq);
    }

    // Codifica PCM bruto de 16-bit Mono @ 48kHz em frames Opus VoIP e transmite
    void encodeAndSendVoice(const int16_t* pcm, int samples) {
        if (!pcm || samples <= 0 || m_udpPort.load() == 0 || m_voiceToken.load() == 0) return;
        // AudioRecord normalmente entrega 960 amostras, mas leituras parciais
        // também são possíveis. O Opus só aceita tamanhos de frame válidos;
        // acumular aqui evita descartar justamente a primeira fala.
        std::lock_guard<std::mutex> codecLock(m_codecMutex);
        if (!m_encoder) return;
        m_encodePcm.insert(m_encodePcm.end(), pcm, pcm + samples);
        while (m_encodePcm.size() >= 960) {
            unsigned char opusBuf[512];
            const int n = opus_encode(m_encoder, m_encodePcm.data(), 960,
                                      opusBuf, sizeof(opusBuf));
            m_encodePcm.erase(m_encodePcm.begin(), m_encodePcm.begin() + 960);
            if (n > 0) {
                sendVoiceFrame(reinterpret_cast<const char*>(opusBuf), n, ++m_voiceSeq);
            }
        }
    }

    void setCurrentChannelFromClient(int channelId) { setCurrentChannel(channelId); }
    void installChannelKeyFromClient(int channelId, const std::string& keyB64) { installChannelKey(channelId, keyB64); }

private:
    HallaClientCore() : m_tcpSocket(-1), m_udpSocket(-1), m_connected(false),
                         m_authenticated(false), m_pingPending(false),
                         m_lastPingSentMs(0), m_pingTotal(0), m_pingSuccess(0),
                         m_udpPort(0), m_voiceToken(0), m_selfId(0), m_encoder(nullptr),
                         m_tlsReady(false), m_tlsInited(false), m_cryptoCounter(0) {}
    ~HallaClientCore() {
        disconnect();
        std::lock_guard<std::mutex> tcpLock(m_tcpMutex);
        freeTlsLocked();
    }

    void sendTcp(const std::string& data) {
        std::lock_guard<std::mutex> lock(m_tcpMutex);
        if (m_tcpSocket == -1 || data.empty() || !m_tlsReady) return;
        size_t sent = 0;
        while (sent < data.size() && m_connected) {
            const int n = mbedtls_ssl_write(&m_ssl,
                reinterpret_cast<const unsigned char*>(data.data() + sent),
                data.size() - sent);
            if (n == MBEDTLS_ERR_SSL_WANT_READ || n == MBEDTLS_ERR_SSL_WANT_WRITE)
                continue;
            if (n <= 0) break;
            sent += static_cast<size_t>(n);
        }
    }

    void initTlsLocked() {
        freeTlsLocked();
        mbedtls_ssl_init(&m_ssl);
        mbedtls_ssl_config_init(&m_sslConf);
        mbedtls_ctr_drbg_init(&m_ctrDrbg);
        mbedtls_entropy_init(&m_entropy);
        m_tlsInited = true;
    }

    void freeTlsLocked() {
        if (!m_tlsInited) return;
        mbedtls_ssl_free(&m_ssl);
        mbedtls_ssl_config_free(&m_sslConf);
        mbedtls_ctr_drbg_free(&m_ctrDrbg);
        mbedtls_entropy_free(&m_entropy);
        m_tlsReady = false;
        m_tlsInited = false;
    }

    bool setupTls(const std::string& host, int port) {
        {
            std::lock_guard<std::mutex> lock(m_tcpMutex);
            initTlsLocked();
        }
        const char* pers = "halla-mobile-tls";
        int ret = mbedtls_ctr_drbg_seed(&m_ctrDrbg, mbedtls_entropy_func, &m_entropy,
                                        reinterpret_cast<const unsigned char*>(pers), strlen(pers));
        if (ret != 0) { writeLog("TLS: falha ao semear DRBG"); return false; }
        ret = mbedtls_ssl_config_defaults(&m_sslConf, MBEDTLS_SSL_IS_CLIENT,
                                          MBEDTLS_SSL_TRANSPORT_STREAM,
                                          MBEDTLS_SSL_PRESET_DEFAULT);
        if (ret != 0) { writeLog("TLS: config_defaults falhou"); return false; }
        mbedtls_ssl_conf_authmode(&m_sslConf, MBEDTLS_SSL_VERIFY_NONE);
        mbedtls_ssl_conf_rng(&m_sslConf, mbedtls_ctr_drbg_random, &m_ctrDrbg);
        ret = mbedtls_ssl_setup(&m_ssl, &m_sslConf);
        if (ret != 0) { writeLog("TLS: ssl_setup falhou"); return false; }
        mbedtls_ssl_set_hostname(&m_ssl, host.c_str());
        mbedtls_ssl_set_bio(&m_ssl, &m_tcpSocket, tlsSendCallback, tlsRecvCallback, nullptr);
        while ((ret = mbedtls_ssl_handshake(&m_ssl)) != 0) {
            if (ret == MBEDTLS_ERR_SSL_WANT_READ || ret == MBEDTLS_ERR_SSL_WANT_WRITE) continue;
            char errBuf[128];
            mbedtls_strerror(ret, errBuf, sizeof(errBuf));
            writeLog(std::string("TLS: handshake falhou: ") + errBuf);
            return false;
        }
        const mbedtls_x509_crt* cert = mbedtls_ssl_get_peer_cert(&m_ssl);
        if (!cert || cert->raw.len == 0) {
            writeLog("TLS: servidor não apresentou certificado");
            return false;
        }
        unsigned char hash[32];
        mbedtls_sha256(cert->raw.p, cert->raw.len, hash, 0);
        const std::string fingerprint = hexEncode(hash, sizeof(hash));
        if (!g_cachePath.empty()) {
            std::string safeHost = host;
            for (char& c : safeHost)
                if (!(std::isalnum(static_cast<unsigned char>(c)) || c == '.' || c == '-' || c == '_')) c = '_';
            const std::string pinPath = g_cachePath + "/tls_fingerprint_" + safeHost + "_" + std::to_string(port) + ".txt";
            std::ifstream in(pinPath);
            std::string saved;
            if (in.good()) std::getline(in, saved);
            if (saved.empty()) {
                std::ofstream out(pinPath, std::ios::trunc);
                out << fingerprint;
                writeLog("TLS: fingerprint salvo (TOFU)");
            } else if (saved != fingerprint) {
                writeLog("TLS: fingerprint mudou; conexão recusada");
                invokeOnConnectionFailed("ALERTA DE SEGURANÇA: o fingerprint TLS do servidor mudou.");
                return false;
            }
        }
        m_tlsReady = true;
        writeLog("TLS: canal de controle protegido e validado por TOFU");
        return true;
    }

    void installChannelKey(int channelId, const std::string& keyB64) {
        const std::vector<char> key = base64DecodeBytes(keyB64);
        if (channelId <= 0 || key.size() < 16) return;
        std::lock_guard<std::mutex> lock(m_keyMutex);
        m_channelKeys[channelId] = key;
        if (m_currentChannelId == channelId || m_currentVoiceKey.empty()) {
            m_currentVoiceKey = key;
        }
    }

    void setCurrentChannel(int channelId) {
        if (channelId <= 0) return;
        std::lock_guard<std::mutex> lock(m_keyMutex);
        m_currentChannelId = channelId;
        auto it = m_channelKeys.find(channelId);
        if (it != m_channelKeys.end()) {
            m_currentVoiceKey = it->second;
            writeLog("Cripto voz: canal atual definido para #" + std::to_string(channelId));
        }
    }

    void installWelcomeKeys(const std::string& line) {
        const std::string keysObj = jsonExtractObject(line, "channelKeys");
        for (const auto& entry : jsonObjectStringMap(keysObj)) {
            const int channelId = safeStoi(entry.first);
            const std::vector<char> key = base64DecodeBytes(entry.second);
            if (channelId > 0 && key.size() >= 16) {
                std::lock_guard<std::mutex> lock(m_keyMutex);
                m_channelKeys[channelId] = key;
                if (m_currentChannelId == channelId || m_currentVoiceKey.empty()) {
                    m_currentVoiceKey = key;
                }
            }
        }
        writeLog("Cripto voz: " + std::to_string(m_channelKeys.size()) + " chaves de canal carregadas do welcome");
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
        if (!dec || !opusData || size <= 0) return;

        int16_t pcm[960]; // Amortiza em blocos padrão de 20ms @ 48kHz
        int n = -1;

        // Primeiro tenta a chave ativa. Se o pacote veio de um canal vinculado
        // ou a ordem dos channel_key mudou, tenta todas as chaves conhecidas.
        // Isso corrige o caso PC -> Mobile em que o Desktop cifrava com uma
        // chave que o Mobile possuía, mas não era a m_currentVoiceKey.
        std::vector<std::vector<char>> candidateKeys;
        {
            std::lock_guard<std::mutex> keyLock(m_keyMutex);
            if (!m_currentVoiceKey.empty()) candidateKeys.push_back(m_currentVoiceKey);
            for (const auto& pair : m_channelKeys) {
                if (pair.second.empty()) continue;
                bool exists = false;
                for (const auto& k : candidateKeys) {
                    if (k == pair.second) { exists = true; break; }
                }
                if (!exists) candidateKeys.push_back(pair.second);
            }
        }
        for (const auto& key : candidateKeys) {
            std::vector<char> decrypted = voiceDecryptAead(opusData, size, key, fromId, seq);
            if (decrypted.empty()) continue;
            n = opus_decode(dec, reinterpret_cast<const unsigned char*>(decrypted.data()),
                            decrypted.size(), pcm, 960, 0);
            if (n > 0) break;
        }

        // Compatibilidade com Desktop <= v1.0.37: voz cifrada pelo antigo XOR
        // usando os 16 primeiros bytes da chave do canal. Usa decoder temporário
        // para não corromper o decoder principal caso a tentativa esteja errada.
        if (n <= 0) {
            for (const auto& key : candidateKeys) {
                std::vector<char> legacy = voiceDecryptLegacyXor(opusData, size, key, seq);
                if (legacy.empty()) continue;
                int trialErr = 0;
                OpusDecoder* trial = opus_decoder_create(48000, 1, &trialErr);
                if (!trial) continue;
                n = opus_decode(trial, reinterpret_cast<const unsigned char*>(legacy.data()),
                                legacy.size(), pcm, 960, 0);
                opus_decoder_destroy(trial);
                if (n > 0) break;
            }
        }

        // Último fallback: alguns desktops/intermediários podem ainda enviar
        // Opus puro mesmo com chaves presentes. Usa decoder temporário para
        // não danificar o estado do decoder principal se for ciphertext.
        if (n <= 0) {
            int trialErr = 0;
            OpusDecoder* trial = opus_decoder_create(48000, 1, &trialErr);
            if (trial) {
                n = opus_decode(trial, reinterpret_cast<const unsigned char*>(opusData), size, pcm, 960, 0);
                opus_decoder_destroy(trial);
            }
        }
        if (n > 0) {
            invokeOnAudioFrame(fromId, reinterpret_cast<const char*>(pcm), n * 2);
        } else {
            writeLog("UDP voz: falha ao decodificar pacote de #" + std::to_string(fromId) +
                     " bytes=" + std::to_string(size) +
                     " chaves=" + std::to_string(candidateKeys.size()));
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

        writeLog("TCP conectado. Iniciando TLS...");
        if (!setupTls(hostStr, port)) {
            m_connected = false;
            close(m_tcpSocket);
            m_tcpSocket = -1;
            if (!m_authenticated) invokeOnConnectionFailed("Falha no handshake TLS");
            return;
        }
        writeLog("TLS pronto. Enviando pacote Hello...");

        const std::string uid = m_uid.empty() ? "HALLAmobile0000000000000000000=" : m_uid;
        const std::string idPub = callStaticStringString(g_identityPublicKeyMethod, uid);
        std::string hello = "{\"t\":\"hello\",\"proto\":3,\"uid\":\"" +
                            jsonEscape(uid) + "\",\"idPub\":\"" + jsonEscape(idPub) +
                            "\",\"nick\":\"" + jsonEscape(m_nick) +
                            "\",\"pass\":\"" + jsonEscape(m_pass) +
                            "\",\"ver\":\"1.0.32-mobile\",\"platform\":\"Android\"}\n";
        sendTcp(hello);

        if (m_pingThread.joinable() && m_pingThread.get_id() != std::this_thread::get_id())
            m_pingThread.join();
        m_pingThread = std::thread(&HallaClientCore::pingLoop, this);

        std::string buffer;
        char tempBuf[1024];
        while (m_connected) {
            int n = mbedtls_ssl_read(&m_ssl, reinterpret_cast<unsigned char*>(tempBuf), sizeof(tempBuf) - 1);
            if (n == MBEDTLS_ERR_SSL_WANT_READ || n == MBEDTLS_ERR_SSL_WANT_WRITE) continue;
            if (n <= 0) {
                writeLog("TLS/TCP conexao fechada pelo servidor remoto.");
                break;
            }
            tempBuf[n] = '\0';
            buffer += tempBuf;
            if (buffer.size() > kMaxJsonLineBytes) {
                writeLog("TCP: linha JSON excedeu 2 MiB; encerrando conexão");
                m_connected = false;
                break;
            }

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
            const uint64_t ts = nowMs();
            m_lastPingSentMs = ts;
            const bool previousPending = m_pingPending.exchange(true);
            const int total = m_pingTotal.fetch_add(1) + 1;
            if (previousPending) {
                const int success = m_pingSuccess.load();
                const int loss = total > 0 ? ((total - success) * 100) / total : 0;
                invokeOnPing(-1, loss);
            }
            sendTcp("{\"t\":\"ping\",\"ts\":" + std::to_string(ts) + "}\n");
        }
    }

    void handleTcpPacket(const std::string& line) {
        try {
            if (line.size() > kMaxJsonLineBytes) {
                writeLog("TCP: pacote JSON ignorado por exceder 2 MiB");
                return;
            }
            writeLog("Recebido pacote TCP de tamanho: " + std::to_string(line.length()));
            std::string t = jsonExtractString(line, "t");
            writeLog("Tipo de pacote extraído (t): " + t);

            if (t == "identity_challenge") {
                const std::string nonce = jsonExtractString(line, "nonce");
                const std::string uid = m_uid.empty() ? "HALLAmobile0000000000000000000=" : m_uid;
                const std::string sig = callStaticStringStringString(g_signIdentityNonceMethod, uid, nonce);
                if (sig.empty()) {
                    invokeOnConnectionFailed("Não foi possível assinar o desafio da identidade");
                    m_connected = false;
                    return;
                }
                sendTcp("{\"t\":\"identity_proof\",\"sig\":\"" + jsonEscape(sig) + "\"}\n");
                return;
            }

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
                m_selfId.store(static_cast<uint32_t>(jsonExtractInt(line, "selfId")));
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
                    m_udpPort.store(jsonExtractInt(voiceObj, "udp"));
                    m_voiceToken.store(safeStoul(jsonExtractString(voiceObj, "token")));
                }
                writeLog("welcome: m_udpPort = " + std::to_string(m_udpPort.load()) + ", m_voiceToken = " + std::to_string(m_voiceToken.load()));
                installWelcomeKeys(line);

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
                m_udpPort.store(jsonExtractInt(line, "udp"));
                m_voiceToken.store(safeStoul(jsonExtractString(line, "token")));
                writeLog("voice_token: m_udpPort = " + std::to_string(m_udpPort.load()) + ", m_voiceToken = " + std::to_string(m_voiceToken.load()));
                setupUdpVoice();
                return;
            }

            if (t == "channel_key") {
                installChannelKey(jsonExtractInt(line, "channel"), jsonExtractString(line, "key"));
                return;
            }

            if (t == "pong") {
                const uint64_t sent = jsonExtractUint64(line, "ts");
                const uint64_t now = nowMs();
                if (sent > 0 && sent == m_lastPingSentMs && m_pingPending.exchange(false)) {
                    const int rtt = static_cast<int>(now >= sent ? now - sent : 0);
                    const int success = m_pingSuccess.fetch_add(1) + 1;
                    const int total = m_pingTotal.load();
                    const int loss = total > 0 ? ((total - success) * 100) / total : 0;
                    invokeOnPing(rtt, loss);
                }
                return;
            }

            if (t == "kicked") {
                const std::string reason = jsonExtractString(line, "reason");
                invokeOnError("kicked", reason);
                m_connected = false;
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

            if (t == "webrtc_watch_request" || t == "webrtc_watch_stop" ||
                t == "webrtc_offer" || t == "webrtc_answer" || t == "webrtc_ice") {
                invokeOnWebRtcSignal(line);
                return;
            }

            if (t == "user_joined" || t == "user_left" || t == "user_moved" ||
                t == "chan_update" || t == "chan_removed" || t == "user_state" ||
                t == "user_nick" || t == "user_desc" || t == "user_group" ||
                t == "user_screenshare_state" ||
                t == "server_edit" || t == "group_list" || t == "banlist" ||
                t == "ban_removed" || t == "complaint_list" ||
                t == "complaint_added" || t == "complaint_cleared") {
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
            const int socketFd = m_udpSocket.load();
            if (socketFd != -1) {
                shutdown(socketFd, SHUT_RDWR);
                close(socketFd);
                m_udpSocket.store(-1);
            }
        }
        if (m_udpThread.joinable() && m_udpThread.get_id() != std::this_thread::get_id())
            m_udpThread.join();
        if (m_natThread.joinable() && m_natThread.get_id() != std::this_thread::get_id())
            m_natThread.join();

        std::unique_lock<std::mutex> udpLock(m_udpMutex);
        const int socketFd = socket(AF_INET, SOCK_DGRAM, 0);
        m_udpSocket.store(socketFd);
        if (socketFd == -1) {
            writeLog("Erro: setupUdpVoice nao conseguiu criar socket UDP");
            return;
        }

        // Timeout curto permite que a thread UDP observe desconexões sem ficar
        // bloqueada indefinidamente em recvfrom.
        struct timeval timeout;
        timeout.tv_sec = 1;
        timeout.tv_usec = 0;
        setsockopt(socketFd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));

        struct sockaddr_in localAddr;
        memset(&localAddr, 0, sizeof(localAddr));
        localAddr.sin_family = AF_INET;
        localAddr.sin_port = htons(0);
        localAddr.sin_addr.s_addr = htonl(INADDR_ANY);
        if (bind(socketFd, (struct sockaddr*)&localAddr, sizeof(localAddr)) < 0) {
            writeLog("Erro: bind UDP falhou");
            close(socketFd);
            m_udpSocket.store(-1);
            return;
        }

        // Resolve o host do servidor uma vez de forma robusta e persistente.
        memset(&m_serverUdpAddr, 0, sizeof(m_serverUdpAddr));
        m_serverUdpAddr.sin_family = AF_INET;
        m_serverUdpAddr.sin_port = htons(static_cast<uint16_t>(m_udpPort.load()));
        if (inet_pton(AF_INET, m_host.c_str(), &m_serverUdpAddr.sin_addr) == 1) {
            writeLog("[NAT] IP do servidor resolvido via inet_pton.");
        } else {
            struct hostent* host = gethostbyname(m_host.c_str());
            if (host) {
                memcpy(&m_serverUdpAddr.sin_addr.s_addr, host->h_addr_list[0], host->h_length);
                writeLog("[NAT] Host do servidor resolvido via gethostbyname.");
            } else {
                writeLog("Erro: nao foi possivel resolver o host UDP");
                close(socketFd);
                m_udpSocket.store(-1);
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
            sendVoiceRegistration(static_cast<uint16_t>(i + 1));
            usleep(50000);
        }
        writeLog("Socket UDP configurado na porta " + std::to_string(m_udpPort.load()) +
                 " com token " + std::to_string(m_voiceToken.load()));

        m_natThread = std::thread(&HallaClientCore::udpPingLoop, this);
        writeLog("[NAT] Loop de keep-alive UDP iniciado.");
    }

    void udpPingLoop() {
        while (m_connected && m_udpSocket.load() != -1) {
            for (int i = 0; i < 20 && m_connected; ++i)
                std::this_thread::sleep_for(std::chrono::milliseconds(100));
            if (!m_connected || m_udpSocket.load() == -1 || m_udpPort.load() == 0
                    || m_voiceToken.load() == 0) break;
            sendVoiceRegistration(1);
        }
        writeLog("[NAT] Loop de keep-alive UDP finalizado.");
    }

    std::vector<std::vector<char>> currentVoiceKeyCandidates() {
        std::vector<std::vector<char>> candidateKeys;
        std::lock_guard<std::mutex> keyLock(m_keyMutex);
        if (!m_currentVoiceKey.empty()) candidateKeys.push_back(m_currentVoiceKey);
        for (const auto& pair : m_channelKeys) {
            if (pair.second.empty()) continue;
            bool exists = false;
            for (const auto& k : candidateKeys) {
                if (k == pair.second) { exists = true; break; }
            }
            if (!exists) candidateKeys.push_back(pair.second);
        }
        return candidateKeys;
    }

    std::vector<char> decryptScreenChunk(uint32_t fromId, uint16_t seq, const char* data, int size) {
        if (!data || size <= 0) return {};
        const std::vector<std::vector<char>> keys = currentVoiceKeyCandidates();

        // Desktop atualizado: cada chunk HALF é AEAD e tem contador/tag próprios.
        for (const auto& key : keys) {
            std::vector<char> plain = voiceDecryptAead(data, size, key, fromId, seq);
            if (!plain.empty()) return plain;
        }

        // Desktop antigo: cada chunk era XOR legado. Não valide JPEG aqui, pois
        // o JPEG completo só existe após juntar todos os chunks. Esse caminho é
        // o comportamento que já mostrava imagem antes; o congelamento era no
        // Desktop não enviando frames, não neste fallback.
        for (const auto& key : keys) {
            std::vector<char> plain = voiceDecryptLegacyXor(data, size, key, seq);
            if (!plain.empty()) return plain;
        }

        // Sem chaves ainda: aceita puro para compatibilidade/diagnóstico.
        return keys.empty() ? std::vector<char>(data, data + size) : std::vector<char>();
    }

    void handleScreenDatagram(uint32_t fromId, uint16_t seq, const char* data, int size) {
        if (!data || size < 2) return;
        const uint8_t chunkIdx = static_cast<uint8_t>(data[0]);
        const uint8_t chunkCount = static_cast<uint8_t>(data[1]);
        if (chunkCount == 0 || chunkIdx >= chunkCount) return;

        std::vector<char> chunk = decryptScreenChunk(fromId, seq, data + 2, size - 2);
        if (chunk.empty()) {
            writeLog("ScreenShare: falha ao decifrar chunk de #" + std::to_string(fromId) +
                     " seq=" + std::to_string(seq) + " idx=" + std::to_string(chunkIdx) +
                     " chaves=" + std::to_string(currentVoiceKeyCandidates().size()));
            return;
        }

        auto& bySeq = m_screenReassembly[fromId][seq];
        bySeq[chunkIdx] = std::move(chunk);
        if (bySeq.size() != chunkCount) return;

        std::vector<char> combined;
        for (int i = 0; i < chunkCount; ++i) {
            auto it = bySeq.find(i);
            if (it == bySeq.end()) return;
            combined.insert(combined.end(), it->second.begin(), it->second.end());
        }

        writeLog("ScreenShare: frame completo de #" + std::to_string(fromId) +
                 " bytes=" + std::to_string(combined.size()) +
                 " chunks=" + std::to_string(chunkCount));
        invokeOnScreenShareFrame(static_cast<int>(fromId), combined.data(), combined.size());
        m_screenReassembly[fromId].erase(seq);
        while (m_screenReassembly[fromId].size() > 20) {
            m_screenReassembly[fromId].erase(m_screenReassembly[fromId].begin());
        }
    }

    void udpLoop() {
        writeLog("udpLoop iniciado");
        char buf[2048];
        struct sockaddr_in sender;

        while (m_connected && m_udpSocket.load() != -1) {
            const int socketFd = m_udpSocket.load();
            if (socketFd == -1) break;
            socklen_t len = sizeof(sender);
            int n = recvfrom(socketFd, buf, sizeof(buf), 0,
                             (struct sockaddr*)&sender, &len);
            if (n <= 0) continue; // timeout de 1 s ou socket sendo encerrado

            if (n < 10) continue;
            const bool isVoice = memcmp(buf, "HALL", 4) == 0;
            const bool isScreen = memcmp(buf, "HALF", 4) == 0;
            if (!isVoice && !isScreen) continue;

            uint32_t fromId;
            uint16_t seq;
            memcpy(&fromId, buf + 4, 4);
            memcpy(&seq, buf + 8, 2);

            if (isVoice) {
                // Descompacta áudio Opus recebido e avisa o Kotlin.
                decodeAndNotifyVoice(fromId, seq, buf + 10, n - 10);
            } else {
                handleScreenDatagram(fromId, seq, buf + 10, n - 10);
            }
        }
        writeLog("udpLoop finalizado");
    }

    std::string m_host;
    std::string m_nick;
    std::string m_pass;
    std::string m_uid;
    int m_tcpSocket;
    std::atomic<int> m_udpSocket;
    std::atomic<bool> m_connected;
    std::atomic<bool> m_authenticated;
    std::atomic<bool> m_pingPending;
    std::atomic<uint64_t> m_lastPingSentMs;
    std::atomic<int> m_pingTotal;
    std::atomic<int> m_pingSuccess;
    std::mutex m_tcpMutex;
    std::mutex m_udpMutex;
    std::mutex m_codecMutex;
    std::mutex m_keyMutex;
    std::thread m_tcpThread;
    std::thread m_udpThread;
    std::thread m_pingThread;
    std::thread m_natThread;

    std::atomic<int> m_udpPort;
    std::atomic<uint32_t> m_voiceToken;
    std::atomic<uint32_t> m_selfId;
    struct sockaddr_in m_serverUdpAddr;
    std::vector<int16_t> m_encodePcm;
    uint16_t m_voiceSeq = 0;

    mbedtls_ssl_context m_ssl;
    mbedtls_ssl_config m_sslConf;
    mbedtls_ctr_drbg_context m_ctrDrbg;
    mbedtls_entropy_context m_entropy;
    bool m_tlsReady;
    bool m_tlsInited;
    uint32_t m_cryptoCounter;

    std::map<int, std::vector<char>> m_channelKeys;
    std::vector<char> m_currentVoiceKey;
    int m_currentChannelId = 0;
    std::map<uint32_t, std::map<uint16_t, std::map<int, std::vector<char>>>> m_screenReassembly;

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
    g_onPingMethod = env->GetStaticMethodID(g_coreClass, "triggerOnPing", "(II)V");
    g_onPokeMethod = env->GetStaticMethodID(g_coreClass, "triggerOnPoke", "(Ljava/lang/String;Ljava/lang/String;)V");
    g_onScreenShareFrameMethod = env->GetStaticMethodID(g_coreClass, "triggerOnScreenShareFrame", "(I[B)V");
    g_onWebRtcSignalMethod = env->GetStaticMethodID(g_coreClass, "triggerOnWebRtcSignal", "(Ljava/lang/String;)V");
    g_identityPublicKeyMethod = env->GetStaticMethodID(g_coreClass, "identityPublicKeyBase64", "(Ljava/lang/String;)Ljava/lang/String;");
    g_signIdentityNonceMethod = env->GetStaticMethodID(g_coreClass, "signIdentityNonceBase64", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");

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
Java_com_halla_mobile_HallaCore_setCurrentChannel(JNIEnv*, jclass, jint channelId) {
    HallaClientCore::getInstance().setCurrentChannelFromClient(channelId);
}

JNIEXPORT void JNICALL
Java_com_halla_mobile_HallaCore_installChannelKey(JNIEnv* env, jclass, jint channelId, jstring keyB64) {
    const char* nativeKey = env->GetStringUTFChars(keyB64, nullptr);
    HallaClientCore::getInstance().installChannelKeyFromClient(channelId, nativeKey);
    env->ReleaseStringUTFChars(keyB64, nativeKey);
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
Java_com_halla_mobile_HallaCore_sendSetCommander(JNIEnv* env, jclass clazz, jint userId, jboolean on) {
    HallaClientCore::getInstance().sendSetCommander(userId, on);
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
Java_com_halla_mobile_HallaCore_sendEditChannel(JNIEnv* env, jclass clazz, jint channelId, jstring name, jstring desc, jstring pass, jint bitrate, jboolean noSymbol) {
    const char* nativeName = env->GetStringUTFChars(name, nullptr);
    const char* nativeDesc = env->GetStringUTFChars(desc, nullptr);
    const char* nativePass = env->GetStringUTFChars(pass, nullptr);
    HallaClientCore::getInstance().sendEditChannel(channelId, nativeName, nativeDesc, nativePass,
                                                   std::clamp(int(bitrate), 16, 384),
                                                   noSymbol == JNI_TRUE);
    env->ReleaseStringUTFChars(name, nativeName);
    env->ReleaseStringUTFChars(desc, nativeDesc);
    env->ReleaseStringUTFChars(pass, nativePass);
}

}
