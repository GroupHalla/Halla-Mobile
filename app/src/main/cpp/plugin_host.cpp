#include "plugin_host.h"
#include "halla_plugin_api.h"
#include "RadioVoiceDsp.h"

#include <android/log.h>
#include <dlfcn.h>

#include <atomic>
#include <chrono>
#include <cmath>
#include <cstring>
#include <map>
#include <mutex>
#include <sstream>

#include <mbedtls/base64.h>

#define PH_TAG "HallaPluginHost"
#define PH_LOGI(...) __android_log_print(ANDROID_LOG_INFO, PH_TAG, __VA_ARGS__)
#define PH_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, PH_TAG, __VA_ARGS__)

namespace {

std::string b64Encode(const uint8_t* data, size_t size) {
    size_t needed = 0;
    mbedtls_base64_encode(nullptr, 0, &needed, data, size);
    std::string out(needed, '\0');
    size_t written = 0;
    if (mbedtls_base64_encode(reinterpret_cast<unsigned char*>(&out[0]), out.size(),
                              &written, data, size) != 0)
        return {};
    out.resize(written);
    return out;
}

std::vector<uint8_t> b64Decode(const std::string& text) {
    size_t needed = 0;
    mbedtls_base64_decode(nullptr, 0, &needed,
                          reinterpret_cast<const unsigned char*>(text.data()), text.size());
    std::vector<uint8_t> out(needed);
    size_t written = 0;
    if (mbedtls_base64_decode(out.data(), out.size(), &written,
                              reinterpret_cast<const unsigned char*>(text.data()),
                              text.size()) != 0)
        return {};
    out.resize(written);
    return out;
}

std::string jsonEscapeLocal(const std::string& input) {
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
                out += hex[(c >> 4) & 0xf];
                out += hex[c & 0xf];
            } else {
                out += static_cast<char>(c);
            }
        }
    }
    return out;
}

// Extração pontual de valores de um JSON plano (mesma técnica minimalista do
// jni_bridge; suficiente para as configurações dos complementos oficiais).
int jsonIntField(const std::string& json, const std::string& key, int fallback) {
    const std::string needle = "\"" + key + "\":";
    size_t pos = json.find(needle);
    if (pos == std::string::npos) return fallback;
    pos += needle.size();
    while (pos < json.size() && (json[pos] == ' ' || json[pos] == '\t')) ++pos;
    bool neg = false;
    if (pos < json.size() && json[pos] == '-') { neg = true; ++pos; }
    long value = 0;
    bool any = false;
    while (pos < json.size() && json[pos] >= '0' && json[pos] <= '9') {
        value = value * 10 + (json[pos] - '0');
        any = true;
        ++pos;
    }
    if (!any) return fallback;
    return static_cast<int>(neg ? -value : value);
}

std::string jsonStringField(const std::string& json, const std::string& key,
                            const std::string& fallback) {
    const std::string needle = "\"" + key + "\":\"";
    size_t pos = json.find(needle);
    if (pos == std::string::npos) return fallback;
    pos += needle.size();
    std::string out;
    while (pos < json.size() && json[pos] != '"') {
        if (json[pos] == '\\' && pos + 1 < json.size()) ++pos;
        out += json[pos++];
    }
    return out;
}

bool validPluginDataId(const std::string& id) {
    if (id.size() < 3 || id.size() > 64) return false;
    for (char c : id) {
        const bool ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                     || c == '.' || c == '-' || c == '_';
        if (!ok) return false;
    }
    return true;
}

// ------------------------------------------------------------------ DSP ----
// O complemento oficial de voz por rádio executa no host nativo. Todo o DSP
// vive em RadioVoiceDsp.h (cópia fiel de src/plugins/RadioVoiceDsp.h do Halla
// Desktop — manter os dois arquivos sincronizados).

// Roda o DSP de rádio de um fluxo, semeando o ruído na primeira passagem.
void applyRadioDsp(RadioVoiceDsp& dsp, uint32_t seedBase, float intensity,
                   float noiseLevel, float gain, int16_t* samples, uint32_t frames) {
    if (!dsp.seeded()) dsp.seed(0xA341316Cu ^ seedBase);
    dsp.configure(intensity, noiseLevel, gain);
    dsp.process(samples, frames);
}

struct NativePlugin;

} // namespace

// ---------------------------------------------------------------- Impl ----

struct PluginHost::Impl {
    mutable std::mutex mutex;
    PluginHostBridge bridge;

    std::map<std::string, NativePlugin*> plugins;
    std::map<int64_t, std::pair<HallaUiTaskFn, void*>> uiTasks;
    std::atomic<int64_t> nextTaskId{1};

    // Estado de áudio controlado por plugins (aplicado no estágio remoto).
    std::map<int32_t, PluginUserAudioState> userAudio;
    std::map<uint64_t, RadioVoiceDsp> radioStates;

    // Complemento oficial de rádio (executa no host, sem .so).
    bool officialRadioEnabled = false;
    std::string officialRadioSendMode = "whisper";
    std::string officialRadioReceiveMode = "whisper";
    float officialRadioIntensity = 0.9f;
    float officialRadioNoise = 0.1f;
    float officialRadioGain = 1.05f;

    std::string clientState = "{}";
    uint64_t startMs = 0;

    uint64_t nowMs() const {
        return static_cast<uint64_t>(
            std::chrono::duration_cast<std::chrono::milliseconds>(
                std::chrono::steady_clock::now().time_since_epoch()).count());
    }
};

namespace {

// Registro de um plugin nativo carregado + tabelas por-plugin da ABI.
struct NativePlugin {
    std::string id;
    void* handle = nullptr;
    const HallaPluginApi* api = nullptr;
    std::string settingsJson = "{}";

    PluginHost::Impl* host = nullptr;

    // Processador de áudio registrado por este plugin.
    HallaAudioProcessorFn audioProcessor = nullptr;
    void* audioContext = nullptr;
    uint32_t audioStageMask = 0;

    // Handler de plugin_data registrado por este plugin.
    HallaPluginDataFn dataHandler = nullptr;
    void* dataContext = nullptr;

    HallaHostApi hostApi{};
    HallaCoreApiV1 coreApi{};
    HallaConnectionApiV1 connectionApi{};
    HallaAudioApiV1 audioApi{};
    HallaDataApiV1 dataApi{};
    HallaUiApiV1 uiApi{};
};

// ------------------------------------------------------------ host fns ----

void hostLog(void* context, HallaPluginLogLevel level, const char* message) {
    auto* plugin = static_cast<NativePlugin*>(context);
    const char* text = message ? message : "";
    switch (level) {
    case HALLA_PLUGIN_LOG_ERROR:
        PH_LOGE("[%s] %s", plugin->id.c_str(), text);
        break;
    default:
        PH_LOGI("[%s] %s", plugin->id.c_str(), text);
        break;
    }
}

size_t copyToBuffer(const std::string& value, char* buffer, size_t bufferSize) {
    const size_t needed = value.size() + 1;
    if (buffer && bufferSize >= needed) {
        memcpy(buffer, value.c_str(), needed);
    } else if (buffer && bufferSize > 0) {
        const size_t copy = bufferSize - 1;
        memcpy(buffer, value.c_str(), copy);
        buffer[copy] = '\0';
    }
    return needed;
}

size_t hostGetSettingsJson(void* context, char* buffer, size_t bufferSize) {
    auto* plugin = static_cast<NativePlugin*>(context);
    std::lock_guard<std::mutex> lock(plugin->host->mutex);
    return copyToBuffer(plugin->settingsJson, buffer, bufferSize);
}

void hostRequestClientState(void* context) {
    auto* plugin = static_cast<NativePlugin*>(context);
    std::string state;
    {
        std::lock_guard<std::mutex> lock(plugin->host->mutex);
        state = plugin->host->clientState;
    }
    if (plugin->api && plugin->api->on_event) {
        const std::string event =
            "{\"event\":\"client_state\",\"payload\":" + state + "}";
        plugin->api->on_event(event.c_str(), event.size());
    }
}

// core.v1
uint64_t coreMonotonic(void* context) {
    auto* plugin = static_cast<NativePlugin*>(context);
    return plugin->host->nowMs() - plugin->host->startMs;
}

size_t coreAppInfo(void* context, char* buffer, size_t bufferSize) {
    auto* plugin = static_cast<NativePlugin*>(context);
    std::string version = "0";
    if (plugin->host->bridge.appVersion) version = plugin->host->bridge.appVersion();
    const std::string info =
        "{\"application\":\"Halla Mobile\",\"platform\":\"Android\",\"version\":\""
        + jsonEscapeLocal(version) + "\",\"interfaces\":[\"halla.core.v1\","
        "\"halla.connection.v1\",\"halla.audio.v1\",\"halla.data.v1\",\"halla.ui.v1\"]}";
    return copyToBuffer(info, buffer, bufferSize);
}

int corePostToUi(void* context, HallaUiTaskFn task, void* taskContext) {
    auto* plugin = static_cast<NativePlugin*>(context);
    if (!task) return HALLA_RESULT_INVALID_ARGUMENT;
    if (!plugin->host->bridge.postUiTask) return HALLA_RESULT_UNAVAILABLE;
    const int64_t id = plugin->host->nextTaskId.fetch_add(1);
    {
        std::lock_guard<std::mutex> lock(plugin->host->mutex);
        plugin->host->uiTasks[id] = {task, taskContext};
    }
    plugin->host->bridge.postUiTask(id);
    return HALLA_RESULT_OK;
}

// connection.v1 — subconjunto móvel: uma única conexão (id 0 = ativa).
size_t connGetConnections(void* context, char* buffer, size_t bufferSize) {
    auto* plugin = static_cast<NativePlugin*>(context);
    std::string state;
    {
        std::lock_guard<std::mutex> lock(plugin->host->mutex);
        state = plugin->host->clientState;
    }
    const std::string list = "[{\"connectionId\":1,\"state\":" + state + "}]";
    return copyToBuffer(list, buffer, bufferSize);
}

size_t connGetConnection(void* context, uint64_t, char* buffer, size_t bufferSize) {
    auto* plugin = static_cast<NativePlugin*>(context);
    std::lock_guard<std::mutex> lock(plugin->host->mutex);
    return copyToBuffer(plugin->host->clientState, buffer, bufferSize);
}

int connSendRaw(NativePlugin* plugin, const std::string& json) {
    if (!plugin->host->bridge.sendRawJson) return HALLA_RESULT_NOT_CONNECTED;
    plugin->host->bridge.sendRawJson(json);
    return HALLA_RESULT_OK;
}

int connMoveSelf(void* context, uint64_t, int32_t channelId, const char* password) {
    auto* plugin = static_cast<NativePlugin*>(context);
    std::ostringstream os;
    os << "{\"t\":\"move\",\"channel\":" << channelId;
    if (password && *password)
        os << ",\"pass\":\"" << jsonEscapeLocal(password) << "\"";
    os << "}";
    return connSendRaw(plugin, os.str());
}

int connSetSelfFlags(void*, uint64_t, uint32_t, uint32_t) {
    // Estados do próprio usuário pertencem ao serviço Android (notificação,
    // áudio); um plugin não deve contorná-lo silenciosamente.
    return HALLA_RESULT_UNAVAILABLE;
}

int connSetNickname(void* context, uint64_t, const char* nickname) {
    auto* plugin = static_cast<NativePlugin*>(context);
    if (!nickname || !*nickname) return HALLA_RESULT_INVALID_ARGUMENT;
    return connSendRaw(plugin,
        "{\"t\":\"nick\",\"name\":\"" + jsonEscapeLocal(nickname) + "\"}");
}

int connSendChat(void* context, uint64_t, HallaChatScope scope,
                 int32_t targetUserId, const char* text) {
    auto* plugin = static_cast<NativePlugin*>(context);
    if (!text || !*text) return HALLA_RESULT_INVALID_ARGUMENT;
    const char* scopeName = scope == HALLA_CHAT_SERVER ? "server"
                          : scope == HALLA_CHAT_PRIVATE ? "private" : "channel";
    std::ostringstream os;
    os << "{\"t\":\"chat\",\"scope\":\"" << scopeName << "\"";
    if (scope == HALLA_CHAT_PRIVATE) os << ",\"to\":" << targetUserId;
    os << ",\"text\":\"" << jsonEscapeLocal(text) << "\"}";
    return connSendRaw(plugin, os.str());
}

int connSetWhisperTargets(void* context, uint64_t, const int32_t* userIds,
                          size_t userCount) {
    auto* plugin = static_cast<NativePlugin*>(context);
    std::ostringstream os;
    os << "{\"t\":\"whisper\",\"ids\":[";
    for (size_t i = 0; i < userCount && userIds; ++i) {
        if (i) os << ',';
        os << userIds[i];
    }
    os << "]}";
    return connSendRaw(plugin, os.str());
}

int connSetLocalMute(void*, uint64_t, int32_t, int) { return HALLA_RESULT_UNAVAILABLE; }

int connSetLocalVolumeDb(void* context, uint64_t connectionId, int32_t userId,
                         float volumeDb) {
    auto* plugin = static_cast<NativePlugin*>(context);
    std::lock_guard<std::mutex> lock(plugin->host->mutex);
    float gain = std::pow(10.0f, volumeDb / 20.0f);
    if (gain < 0.0f) gain = 0.0f;
    if (gain > 8.0f) gain = 8.0f;
    plugin->host->userAudio[userId].gain = gain;
    (void)connectionId;
    return HALLA_RESULT_OK;
}

int connMoveUser(void* context, uint64_t, int32_t userId, int32_t channelId) {
    auto* plugin = static_cast<NativePlugin*>(context);
    std::ostringstream os;
    os << "{\"t\":\"move_other\",\"id\":" << userId << ",\"channel\":" << channelId << "}";
    return connSendRaw(plugin, os.str());
}

int connPokeUser(void* context, uint64_t, int32_t userId, const char* message) {
    auto* plugin = static_cast<NativePlugin*>(context);
    std::ostringstream os;
    os << "{\"t\":\"poke\",\"to\":" << userId << ",\"msg\":\""
       << jsonEscapeLocal(message ? message : "") << "\"}";
    return connSendRaw(plugin, os.str());
}

int connSetCommander(void* context, uint64_t, int32_t userId, int enabled) {
    auto* plugin = static_cast<NativePlugin*>(context);
    std::ostringstream os;
    os << "{\"t\":\"commander\",\"id\":" << userId << ",\"on\":"
       << (enabled ? "true" : "false") << "}";
    return connSendRaw(plugin, os.str());
}

int connKickUser(void* context, uint64_t, int32_t userId, int fromServer,
                 const char* reason) {
    auto* plugin = static_cast<NativePlugin*>(context);
    std::ostringstream os;
    os << "{\"t\":\"kick\",\"id\":" << userId << ",\"from\":\""
       << (fromServer ? "server" : "channel") << "\",\"reason\":\""
       << jsonEscapeLocal(reason ? reason : "") << "\"}";
    return connSendRaw(plugin, os.str());
}

int connBanUser(void* context, uint64_t, int32_t userId, uint32_t minutes,
                const char* reason) {
    auto* plugin = static_cast<NativePlugin*>(context);
    std::ostringstream os;
    os << "{\"t\":\"ban\",\"id\":" << userId << ",\"minutes\":" << minutes
       << ",\"reason\":\"" << jsonEscapeLocal(reason ? reason : "") << "\"}";
    return connSendRaw(plugin, os.str());
}

int connCreateChannelJson(void* context, uint64_t, const char* channelJson) {
    auto* plugin = static_cast<NativePlugin*>(context);
    if (!channelJson || !*channelJson || channelJson[0] != '{')
        return HALLA_RESULT_INVALID_ARGUMENT;
    std::string body(channelJson);
    body.insert(1, "\"t\":\"chan_create\",");
    return connSendRaw(plugin, body);
}

int connEditChannelJson(void* context, uint64_t, const char* channelJson) {
    auto* plugin = static_cast<NativePlugin*>(context);
    if (!channelJson || !*channelJson || channelJson[0] != '{')
        return HALLA_RESULT_INVALID_ARGUMENT;
    std::string body(channelJson);
    body.insert(1, "\"t\":\"chan_edit\",");
    return connSendRaw(plugin, body);
}

int connDeleteChannel(void* context, uint64_t, int32_t channelId) {
    auto* plugin = static_cast<NativePlugin*>(context);
    std::ostringstream os;
    os << "{\"t\":\"chan_delete\",\"id\":" << channelId << "}";
    return connSendRaw(plugin, os.str());
}

// audio.v1
int audioRegisterProcessor(void* context, void* pluginContext,
                           HallaAudioProcessorFn processor, uint32_t stageMask) {
    auto* plugin = static_cast<NativePlugin*>(context);
    if (!processor || stageMask == 0) return HALLA_RESULT_INVALID_ARGUMENT;
    std::lock_guard<std::mutex> lock(plugin->host->mutex);
    plugin->audioProcessor = processor;
    plugin->audioContext = pluginContext;
    plugin->audioStageMask = stageMask;
    return HALLA_RESULT_OK;
}

void audioUnregisterProcessor(void* context) {
    auto* plugin = static_cast<NativePlugin*>(context);
    std::lock_guard<std::mutex> lock(plugin->host->mutex);
    plugin->audioProcessor = nullptr;
    plugin->audioContext = nullptr;
    plugin->audioStageMask = 0;
}

int audioSetListenerTransform(void*, uint64_t, const HallaTransform*) {
    return HALLA_RESULT_UNAVAILABLE; // reprodução móvel é mono-downmix
}

int audioSetUserTransform(void* context, uint64_t, int32_t userId,
                          const HallaVec3* position, float minDistance,
                          float maxDistance, float rolloff) {
    auto* plugin = static_cast<NativePlugin*>(context);
    if (!position) return HALLA_RESULT_INVALID_ARGUMENT;
    // Sem cena 3D no mobile: aproxima por atenuação de distância.
    const float distance = std::sqrt(position->x * position->x
        + position->y * position->y + position->z * position->z);
    float gain = 1.0f;
    if (maxDistance > minDistance && distance > minDistance) {
        if (distance >= maxDistance) gain = 0.0f;
        else {
            const float t = (distance - minDistance) / (maxDistance - minDistance);
            gain = std::pow(1.0f - t, rolloff <= 0.0f ? 1.0f : rolloff);
        }
    }
    std::lock_guard<std::mutex> lock(plugin->host->mutex);
    auto& state = plugin->host->userAudio[userId];
    state.hasSpatial = true;
    state.spatialGain = gain;
    return HALLA_RESULT_OK;
}

int audioSetUserGain(void* context, uint64_t, int32_t userId, float gain) {
    auto* plugin = static_cast<NativePlugin*>(context);
    if (!(gain >= 0.0f) || gain > 16.0f) return HALLA_RESULT_INVALID_ARGUMENT;
    std::lock_guard<std::mutex> lock(plugin->host->mutex);
    plugin->host->userAudio[userId].gain = gain;
    return HALLA_RESULT_OK;
}

int audioSetUserPan(void* context, uint64_t, int32_t userId, float pan) {
    auto* plugin = static_cast<NativePlugin*>(context);
    if (pan < -1.0f || pan > 1.0f) return HALLA_RESULT_INVALID_ARGUMENT;
    std::lock_guard<std::mutex> lock(plugin->host->mutex);
    plugin->host->userAudio[userId].pan = pan; // reservado: saída atual é mono
    return HALLA_RESULT_OK;
}

int audioSetUserRadioEffect(void* context, uint64_t, int32_t userId, int enabled,
                            float strength, float noiseLevel) {
    auto* plugin = static_cast<NativePlugin*>(context);
    std::lock_guard<std::mutex> lock(plugin->host->mutex);
    auto& state = plugin->host->userAudio[userId];
    state.radioEnabled = enabled != 0;
    state.radioStrength = strength < 0.0f ? 0.0f : (strength > 1.0f ? 1.0f : strength);
    state.radioNoise = noiseLevel < 0.0f ? 0.0f : (noiseLevel > 1.0f ? 1.0f : noiseLevel);
    return HALLA_RESULT_OK;
}

void audioResetUser(void* context, uint64_t, int32_t userId) {
    auto* plugin = static_cast<NativePlugin*>(context);
    std::lock_guard<std::mutex> lock(plugin->host->mutex);
    plugin->host->userAudio.erase(userId);
}

void audioResetConnection(void* context, uint64_t) {
    auto* plugin = static_cast<NativePlugin*>(context);
    std::lock_guard<std::mutex> lock(plugin->host->mutex);
    plugin->host->userAudio.clear();
    plugin->host->radioStates.clear();
}

int audioPlayPcm(void* context, uint64_t, const int16_t* samples,
                 uint32_t frameCount, uint32_t channels, float gain) {
    auto* plugin = static_cast<NativePlugin*>(context);
    if (!samples || frameCount == 0 || channels == 0 || channels > 2)
        return HALLA_RESULT_INVALID_ARGUMENT;
    if (frameCount > 48000u * 10u) return HALLA_RESULT_LIMIT_EXCEEDED;
    if (!plugin->host->bridge.playPcm) return HALLA_RESULT_UNAVAILABLE;
    if (gain < 0.0f) gain = 0.0f;
    if (gain > 4.0f) gain = 4.0f;
    // Downmix para mono (pipeline móvel) com ganho.
    std::vector<int16_t> mono(frameCount);
    for (uint32_t i = 0; i < frameCount; ++i) {
        int32_t acc = 0;
        for (uint32_t ch = 0; ch < channels; ++ch)
            acc += samples[i * channels + ch];
        float v = (float(acc) / float(channels)) * gain;
        if (v > 32767.0f) v = 32767.0f;
        if (v < -32768.0f) v = -32768.0f;
        mono[i] = static_cast<int16_t>(v);
    }
    plugin->host->bridge.playPcm(mono.data(), frameCount);
    return HALLA_RESULT_OK;
}

// data.v1
int dataSend(void* context, uint64_t, HallaPluginDataTarget target,
             const int32_t* targetUserIds, size_t targetCount,
             const char* topic, const uint8_t* data, size_t dataSize) {
    auto* plugin = static_cast<NativePlugin*>(context);
    if (!topic || !*topic || strlen(topic) > 64) return HALLA_RESULT_INVALID_ARGUMENT;
    if (!data && dataSize > 0) return HALLA_RESULT_INVALID_ARGUMENT;
    if (dataSize > 8192) return HALLA_RESULT_LIMIT_EXCEEDED;
    if (target == HALLA_PLUGIN_DATA_USERS && (targetCount == 0 || targetCount > 64))
        return HALLA_RESULT_INVALID_ARGUMENT;
    if (!validPluginDataId(plugin->id)) return HALLA_RESULT_INVALID_ARGUMENT;

    std::ostringstream os;
    os << "{\"t\":\"plugin_data\",\"plugin\":\"" << plugin->id << "\",\"target\":"
       << int(target);
    if (target == HALLA_PLUGIN_DATA_USERS) {
        os << ",\"ids\":[";
        for (size_t i = 0; i < targetCount; ++i) {
            if (i) os << ',';
            os << targetUserIds[i];
        }
        os << "]";
    }
    os << ",\"topic\":\"" << jsonEscapeLocal(topic) << "\",\"data\":\""
       << b64Encode(data, dataSize) << "\"}";
    return connSendRaw(plugin, os.str());
}

int dataSetReceiveHandler(void* context, void* pluginContext,
                          HallaPluginDataFn handler) {
    auto* plugin = static_cast<NativePlugin*>(context);
    std::lock_guard<std::mutex> lock(plugin->host->mutex);
    plugin->dataHandler = handler;
    plugin->dataContext = pluginContext;
    return HALLA_RESULT_OK;
}

// ui.v1
int uiShowNotification(void* context, const char* title, const char* message,
                       uint32_t) {
    auto* plugin = static_cast<NativePlugin*>(context);
    if (!plugin->host->bridge.notify) return HALLA_RESULT_UNAVAILABLE;
    plugin->host->bridge.notify(title ? title : plugin->id.c_str(),
                                message ? message : "");
    return HALLA_RESULT_OK;
}

int uiRegisterMenuAction(void* context, const char* actionId, const char* label,
                         void*, HallaUiActionFn callback) {
    auto* plugin = static_cast<NativePlugin*>(context);
    if (!actionId || !*actionId || !callback) return HALLA_RESULT_INVALID_ARGUMENT;
    if (!plugin->host->bridge.menuAction) return HALLA_RESULT_UNAVAILABLE;
    plugin->host->bridge.menuAction(plugin->id + ":" + actionId,
                                    label ? label : actionId, true);
    return HALLA_RESULT_OK;
}

int uiRegisterHotkey(void*, const char*, const char*, const char*, void*,
                     HallaUiActionFn) {
    return HALLA_RESULT_UNAVAILABLE; // sem hotkeys globais no Android
}

void uiUnregisterAction(void* context, const char* actionId) {
    auto* plugin = static_cast<NativePlugin*>(context);
    if (!actionId || !plugin->host->bridge.menuAction) return;
    plugin->host->bridge.menuAction(plugin->id + ":" + actionId, "", false);
}

const void* hostQueryInterface(void* context, const char* interfaceId,
                               uint32_t minimumVersion) {
    auto* plugin = static_cast<NativePlugin*>(context);
    if (!interfaceId || minimumVersion > 1) return nullptr;
    const std::string id(interfaceId);
    if (id == HALLA_INTERFACE_CORE_V1)       return &plugin->coreApi;
    if (id == HALLA_INTERFACE_CONNECTION_V1) return &plugin->connectionApi;
    if (id == HALLA_INTERFACE_AUDIO_V1)      return &plugin->audioApi;
    if (id == HALLA_INTERFACE_DATA_V1)       return &plugin->dataApi;
    if (id == HALLA_INTERFACE_UI_V1)         return &plugin->uiApi;
    return nullptr;
}

void wirePluginTables(NativePlugin* plugin) {
    plugin->hostApi.abi_version = HALLA_PLUGIN_ABI_VERSION;
    plugin->hostApi.struct_size = sizeof(HallaHostApi);
    plugin->hostApi.context = plugin;
    plugin->hostApi.log = &hostLog;
    plugin->hostApi.get_settings_json = &hostGetSettingsJson;
    plugin->hostApi.request_client_state = &hostRequestClientState;
    plugin->hostApi.query_interface = &hostQueryInterface;

    plugin->coreApi = {1, sizeof(HallaCoreApiV1), plugin,
                       &coreMonotonic, &coreAppInfo, &corePostToUi};

    plugin->connectionApi.version = 1;
    plugin->connectionApi.struct_size = sizeof(HallaConnectionApiV1);
    plugin->connectionApi.context = plugin;
    plugin->connectionApi.get_connections_json = &connGetConnections;
    plugin->connectionApi.get_connection_json = &connGetConnection;
    plugin->connectionApi.move_self = &connMoveSelf;
    plugin->connectionApi.set_self_flags = &connSetSelfFlags;
    plugin->connectionApi.set_nickname = &connSetNickname;
    plugin->connectionApi.send_chat = &connSendChat;
    plugin->connectionApi.set_whisper_targets = &connSetWhisperTargets;
    plugin->connectionApi.set_local_mute = &connSetLocalMute;
    plugin->connectionApi.set_local_volume_db = &connSetLocalVolumeDb;
    plugin->connectionApi.move_user = &connMoveUser;
    plugin->connectionApi.poke_user = &connPokeUser;
    plugin->connectionApi.set_commander = &connSetCommander;
    plugin->connectionApi.kick_user = &connKickUser;
    plugin->connectionApi.ban_user = &connBanUser;
    plugin->connectionApi.create_channel_json = &connCreateChannelJson;
    plugin->connectionApi.edit_channel_json = &connEditChannelJson;
    plugin->connectionApi.delete_channel = &connDeleteChannel;

    plugin->audioApi.version = 1;
    plugin->audioApi.struct_size = sizeof(HallaAudioApiV1);
    plugin->audioApi.context = plugin;
    plugin->audioApi.register_processor = &audioRegisterProcessor;
    plugin->audioApi.unregister_processor = &audioUnregisterProcessor;
    plugin->audioApi.set_listener_transform = &audioSetListenerTransform;
    plugin->audioApi.set_user_transform = &audioSetUserTransform;
    plugin->audioApi.set_user_gain = &audioSetUserGain;
    plugin->audioApi.set_user_pan = &audioSetUserPan;
    plugin->audioApi.set_user_radio_effect = &audioSetUserRadioEffect;
    plugin->audioApi.reset_user = &audioResetUser;
    plugin->audioApi.reset_connection = &audioResetConnection;
    plugin->audioApi.play_pcm = &audioPlayPcm;

    plugin->dataApi = {1, sizeof(HallaDataApiV1), plugin,
                       &dataSend, &dataSetReceiveHandler};

    plugin->uiApi = {1, sizeof(HallaUiApiV1), plugin,
                     &uiShowNotification, &uiRegisterMenuAction,
                     &uiRegisterHotkey, &uiUnregisterAction};
}

} // namespace

// ------------------------------------------------------------ PluginHost --

PluginHost& PluginHost::instance() {
    static PluginHost host;
    return host;
}

PluginHost::PluginHost() : d(new Impl) {
    d->startMs = d->nowMs();
}

void PluginHost::setBridge(PluginHostBridge bridge) {
    std::lock_guard<std::mutex> lock(d->mutex);
    d->bridge = std::move(bridge);
}

std::string PluginHost::loadNative(const std::string& id, const std::string& path) {
    if (!validPluginDataId(id)) return "ID de complemento inválido";
    {
        std::lock_guard<std::mutex> lock(d->mutex);
        if (d->plugins.count(id)) return ""; // já carregado
    }

    void* handle = dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (!handle) {
        const char* err = dlerror();
        return std::string("dlopen falhou: ") + (err ? err : "erro desconhecido");
    }

    auto entry = reinterpret_cast<HallaPluginEntryFn>(
        dlsym(handle, HALLA_PLUGIN_ENTRY_SYMBOL));
    if (!entry) {
        dlclose(handle);
        return "A biblioteca não exporta halla_plugin_entry";
    }

    const HallaPluginApi* api = entry();
    if (!api || api->abi_version != HALLA_PLUGIN_ABI_VERSION
            || api->struct_size < HALLA_PLUGIN_API_BASE_SIZE
            || !api->initialize || !api->shutdown
            || !api->id || std::string(api->id) != id) {
        dlclose(handle);
        return "A biblioteca usa uma ABI incompatível ou não corresponde ao manifesto";
    }

    auto* plugin = new NativePlugin;
    plugin->id = id;
    plugin->handle = handle;
    plugin->api = api;
    plugin->host = d;
    wirePluginTables(plugin);

    if (api->initialize(&plugin->hostApi) != 1) {
        dlclose(handle);
        delete plugin;
        return "initialize() do complemento retornou falha";
    }

    {
        std::lock_guard<std::mutex> lock(d->mutex);
        d->plugins[id] = plugin;
    }
    PH_LOGI("Complemento nativo carregado: %s", id.c_str());
    return "";
}

void PluginHost::unloadNative(const std::string& id) {
    NativePlugin* plugin = nullptr;
    {
        std::lock_guard<std::mutex> lock(d->mutex);
        auto it = d->plugins.find(id);
        if (it == d->plugins.end()) return;
        plugin = it->second;
        d->plugins.erase(it);
    }
    if (plugin->api && plugin->api->shutdown) plugin->api->shutdown();
    if (plugin->handle) dlclose(plugin->handle);
    delete plugin;
    PH_LOGI("Complemento nativo descarregado: %s", id.c_str());
}

bool PluginHost::isLoaded(const std::string& id) const {
    std::lock_guard<std::mutex> lock(d->mutex);
    return d->plugins.count(id) != 0;
}

void PluginHost::setSettings(const std::string& id, const std::string& settingsJson) {
    if (id == "com.halla.radio-voice") {
        std::lock_guard<std::mutex> lock(d->mutex);
        d->officialRadioEnabled = jsonIntField(settingsJson, "enabled", 0) != 0;
        d->officialRadioSendMode = jsonStringField(settingsJson, "sendMode", "whisper");
        d->officialRadioReceiveMode = jsonStringField(settingsJson, "receiveMode", "whisper");
        int intensity = jsonIntField(settingsJson, "intensity", 90);
        int noise = jsonIntField(settingsJson, "noise", 10);
        int gain = jsonIntField(settingsJson, "gain", 105);
        if (intensity < 0) intensity = 0; if (intensity > 100) intensity = 100;
        if (noise < 0) noise = 0; if (noise > 100) noise = 100;
        if (gain < 50) gain = 50; if (gain > 150) gain = 150;
        d->officialRadioIntensity = float(intensity) / 100.0f;
        d->officialRadioNoise = float(noise) / 100.0f;
        d->officialRadioGain = float(gain) / 100.0f;
        d->radioStates.clear();
        return;
    }

    NativePlugin* plugin = nullptr;
    {
        std::lock_guard<std::mutex> lock(d->mutex);
        auto it = d->plugins.find(id);
        if (it == d->plugins.end()) return;
        plugin = it->second;
        plugin->settingsJson = settingsJson.empty() ? "{}" : settingsJson;
    }
    if (plugin->api->struct_size >= HALLA_PLUGIN_API_BASE_SIZE
            + sizeof(void (*)(const char*, size_t))
            && plugin->api->on_settings_changed) {
        plugin->api->on_settings_changed(plugin->settingsJson.c_str(),
                                         plugin->settingsJson.size());
    }
}

void PluginHost::dispatchEvent(const std::string& eventJson) {
    std::vector<NativePlugin*> targets;
    {
        std::lock_guard<std::mutex> lock(d->mutex);
        for (auto& pair : d->plugins) targets.push_back(pair.second);
    }
    for (NativePlugin* plugin : targets) {
        if (plugin->api && plugin->api->on_event)
            plugin->api->on_event(eventJson.c_str(), eventJson.size());
    }
}

void PluginHost::setClientState(const std::string& stateJson) {
    {
        std::lock_guard<std::mutex> lock(d->mutex);
        d->clientState = stateJson.empty() ? "{}" : stateJson;
    }
    dispatchEvent("{\"event\":\"client_state\",\"payload\":"
                  + (stateJson.empty() ? std::string("{}") : stateJson) + "}");
}

void PluginHost::clearConnection() {
    {
        std::lock_guard<std::mutex> lock(d->mutex);
        d->clientState = "{}";
        d->userAudio.clear();
        d->radioStates.clear();
    }
    dispatchEvent("{\"event\":\"connection_closed\"}");
}

void PluginHost::processCapture(int selfUserId, int16_t* samples, uint32_t frames) {
    if (!samples || frames == 0) return;

    bool radioOn;
    std::string sendMode;
    float intensity, noise, gain;
    std::vector<NativePlugin*> processors;
    {
        std::lock_guard<std::mutex> lock(d->mutex);
        radioOn = d->officialRadioEnabled;
        sendMode = d->officialRadioSendMode;
        intensity = d->officialRadioIntensity;
        noise = d->officialRadioNoise;
        gain = d->officialRadioGain;
        for (auto& pair : d->plugins) {
            NativePlugin* p = pair.second;
            // A decisão de transmissão (VAD/PTT) já aconteceu na camada Kotlin,
            // então a captura é reportada como HALLA_AUDIO_CAPTURE e o estágio
            // HALLA_AUDIO_CAPTURE_AFTER_VAD é aceito como sinônimo — o pacote
            // oficial de rádio registra AFTER_VAD para usar o mesmo ponto do
            // pipeline nos dois aplicativos.
            if (p->audioProcessor
                    && (p->audioStageMask
                        & (HALLA_AUDIO_CAPTURE | HALLA_AUDIO_CAPTURE_AFTER_VAD)))
                processors.push_back(p);
        }
    }

    // Complemento oficial de rádio: no mobile o modo de envio "normal"/"both"
    // filtra a captura (o conceito de sussurro segue o estado do app).
    if (radioOn && (sendMode == "normal" || sendMode == "both")) {
        std::lock_guard<std::mutex> lock(d->mutex);
        const uint64_t key = (uint64_t(1) << 48) | uint32_t(selfUserId);
        applyRadioDsp(d->radioStates[key], uint32_t(selfUserId) * 2654435761u,
                      intensity, noise, gain, samples, frames);
    }

    for (NativePlugin* plugin : processors) {
        HallaAudioFrame frame{};
        frame.struct_size = sizeof(HallaAudioFrame);
        frame.stage = HALLA_AUDIO_CAPTURE;
        frame.connection_id = 1;
        frame.user_id = selfUserId;
        frame.samples = samples;
        frame.frame_count = frames;
        frame.channels = 1;
        frame.sample_rate = 48000;
        frame.flags = 0;
        plugin->audioProcessor(plugin->audioContext, &frame);
    }
}

void PluginHost::processRemote(int fromUserId, int16_t* samples, uint32_t frames) {
    if (!samples || frames == 0) return;

    bool radioOn;
    std::string receiveMode;
    float intensity, noise, gain;
    PluginUserAudioState userState;
    bool hasUserState = false;
    std::vector<NativePlugin*> processors;
    {
        std::lock_guard<std::mutex> lock(d->mutex);
        radioOn = d->officialRadioEnabled;
        receiveMode = d->officialRadioReceiveMode;
        intensity = d->officialRadioIntensity;
        noise = d->officialRadioNoise;
        gain = d->officialRadioGain;
        auto it = d->userAudio.find(fromUserId);
        if (it != d->userAudio.end()) {
            userState = it->second;
            hasUserState = true;
        }
        for (auto& pair : d->plugins) {
            NativePlugin* p = pair.second;
            if (p->audioProcessor
                    && (p->audioStageMask & HALLA_AUDIO_REMOTE_BEFORE_SPATIAL))
                processors.push_back(p);
        }
    }

    for (NativePlugin* plugin : processors) {
        HallaAudioFrame frame{};
        frame.struct_size = sizeof(HallaAudioFrame);
        frame.stage = HALLA_AUDIO_REMOTE_BEFORE_SPATIAL;
        frame.connection_id = 1;
        frame.user_id = fromUserId;
        frame.samples = samples;
        frame.frame_count = frames;
        frame.channels = 1;
        frame.sample_rate = 48000;
        frame.flags = 0;
        plugin->audioProcessor(plugin->audioContext, &frame);
    }

    // Efeito de rádio por usuário (halla.audio.v1) ou o complemento oficial.
    const bool userRadio = hasUserState && userState.radioEnabled;
    if (userRadio || (radioOn && (receiveMode == "normal" || receiveMode == "both"))) {
        std::lock_guard<std::mutex> lock(d->mutex);
        const uint64_t key = (uint64_t(2) << 48) | uint32_t(fromUserId);
        applyRadioDsp(d->radioStates[key], uint32_t(fromUserId) * 2654435761u,
                      userRadio ? userState.radioStrength : intensity,
                      userRadio ? userState.radioNoise : noise,
                      userRadio ? 1.0f : gain,
                      samples, frames);
    }

    // Ganho por usuário (volume local/espacialização aproximada).
    if (hasUserState) {
        float total = userState.gain * (userState.hasSpatial ? userState.spatialGain : 1.0f);
        if (total < 0.999f || total > 1.001f) {
            for (uint32_t i = 0; i < frames; ++i) {
                float v = float(samples[i]) * total;
                if (v > 32767.0f) v = 32767.0f;
                if (v < -32768.0f) v = -32768.0f;
                samples[i] = static_cast<int16_t>(v);
            }
        }
    }
}

void PluginHost::dispatchPluginData(const std::string& pluginId, int fromUserId,
                                    const std::string& topic,
                                    const std::string& dataB64) {
    NativePlugin* plugin = nullptr;
    HallaPluginDataFn handler = nullptr;
    void* handlerContext = nullptr;
    {
        std::lock_guard<std::mutex> lock(d->mutex);
        auto it = d->plugins.find(pluginId);
        if (it != d->plugins.end()) {
            plugin = it->second;
            handler = plugin->dataHandler;
            handlerContext = plugin->dataContext;
        }
    }

    const std::vector<uint8_t> payload = b64Decode(dataB64);
    if (plugin && handler) {
        handler(handlerContext, 1, fromUserId, topic.c_str(),
                payload.empty() ? nullptr : payload.data(), payload.size());
    }

    // Compatível com o desktop: também replica como evento para plugins
    // orientados a eventos (mantém o payload em base64).
    dispatchEvent("{\"event\":\"plugin_data\",\"plugin\":\"" + jsonEscapeLocal(pluginId)
                  + "\",\"from\":" + std::to_string(fromUserId)
                  + ",\"topic\":\"" + jsonEscapeLocal(topic)
                  + "\",\"data\":\"" + dataB64 + "\"}");
}

void PluginHost::runTask(int64_t taskId) {
    HallaUiTaskFn task = nullptr;
    void* context = nullptr;
    {
        std::lock_guard<std::mutex> lock(d->mutex);
        auto it = d->uiTasks.find(taskId);
        if (it == d->uiTasks.end()) return;
        task = it->second.first;
        context = it->second.second;
        d->uiTasks.erase(it);
    }
    if (task) task(context);
}
