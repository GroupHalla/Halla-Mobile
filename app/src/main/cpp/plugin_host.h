#pragma once

// Host de complementos do Halla Mobile.
//
// Porta o sistema de plugins do Halla Desktop para Android usando a MESMA
// ABI C pública (halla_plugin_api.h). No desktop cada plugin é uma DLL
// carregada com QLibrary; aqui é uma biblioteca compartilhada (.so) do
// pacote .halla-addon carregada com dlopen(), com as interfaces modulares
// obtidas por query_interface():
//
//   halla.core.v1        relógio, informações do app, post_to_ui
//   halla.connection.v1  subconjunto documentado (ações via núcleo móvel)
//   halla.audio.v1       processadores PCM, ganho/pan/rádio por usuário, play_pcm
//   halla.data.v1        transporte plugin_data (protocolo v5) pelo TLS
//   halla.ui.v1          notificações e ações de menu (hotkeys indisponíveis)
//
// Funções não suportadas no Android retornam HALLA_RESULT_UNAVAILABLE, o que
// é permitido pela especificação da ABI (plugins devem tolerar ausências).

#include <cstdint>
#include <functional>
#include <string>
#include <vector>

struct PluginHostBridge {
    // Envia uma linha JSON pelo canal de controle TLS (termina com '\n' no núcleo).
    std::function<void(const std::string&)> sendRawJson;
    // Encaminha PCM S16 mono 48 kHz do plugin para o caminho de reprodução.
    std::function<void(const int16_t*, uint32_t)> playPcm;
    // Notificação visível ao usuário (Kotlin decide Toast/Notification).
    std::function<void(const std::string&, const std::string&)> notify;
    // Ação de menu adicionada/removida pela UI de complementos.
    std::function<void(const std::string&, const std::string&, bool)> menuAction;
    // Agenda runTask(taskId) na thread principal do Android.
    std::function<void(int64_t)> postUiTask;
    // Estado efêmero da sessão para get_connection_json / eventos.
    std::function<std::string()> clientStateJson;
    // Versão do aplicativo para get_application_info_json.
    std::function<std::string()> appVersion;
};

// Controle por usuário aplicado no estágio remoto (definido via halla.audio.v1).
struct PluginUserAudioState {
    float gain = 1.0f;
    float pan = 0.0f;
    bool radioEnabled = false;
    float radioStrength = 0.9f;
    float radioNoise = 0.1f;
    bool hasSpatial = false;
    float spatialGain = 1.0f;
};

class PluginHost {
public:
    static PluginHost& instance();

    void setBridge(PluginHostBridge bridge);

    // Carregamento de plugins nativos (.so de um pacote .halla-addon).
    // Retorna string vazia em caso de sucesso ou a mensagem de erro.
    std::string loadNative(const std::string& id, const std::string& path);
    void unloadNative(const std::string& id);
    bool isLoaded(const std::string& id) const;

    // Configurações (JSON) por complemento; também controla o complemento
    // oficial embutido "com.halla.radio-voice" (id compartilhado com o desktop).
    void setSettings(const std::string& id, const std::string& settingsJson);

    // Eventos JSON para on_event de todos os plugins ativos.
    void dispatchEvent(const std::string& eventJson);

    // Estado da sessão vindo do welcome/atualizações (JSON bruto do protocolo).
    void setClientState(const std::string& stateJson);
    void clearConnection();

    // Pipeline de áudio (chamado pelo núcleo; PCM S16 mono 48 kHz mutável).
    void processCapture(int selfUserId, int16_t* samples, uint32_t frames);
    void processRemote(int fromUserId, int16_t* samples, uint32_t frames);

    // plugin_data recebido do servidor (payload ainda em base64).
    void dispatchPluginData(const std::string& pluginId, int fromUserId,
                            const std::string& topic, const std::string& dataB64);

    // Executa uma tarefa agendada por post_to_ui (chamado pela thread principal).
    void runTask(int64_t taskId);

    struct Impl;

private:
    PluginHost();
    Impl* d;
};
