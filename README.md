<p align="center">
  <img src="https://i.imgur.com/eJFWf5w.png" width="110" alt="Halla Mobile" />
</p>

<h1 align="center">Halla Mobile</h1>

<p align="center">
  Cliente Android nativo do <a href="https://github.com/GroupHalla/Halla">Halla</a> —
  o cliente de voz desktop estilo TeamSpeak 3. Kotlin na interface,
  C++/JNI no núcleo de rede e voz.
</p>

<p align="center">
  <b>com.halla.mobile</b> · Android 8.0+ (API 26) · versão 1.0.58
</p>

---

## Índice

- [Visão geral](#visão-geral)
- [Principais recursos](#principais-recursos)
- [Complementos (plugins)](#complementos-plugins)
- [Visualizador WebRTC (WebView)](#visualizador-webrtc-webview)
- [Arquitetura](#arquitetura)
- [Núcleo nativo (C++/JNI)](#núcleo-nativo-cjni)
- [Serviço em segundo plano](#serviço-em-segundo-plano)
- [Permissões usadas](#permissões-usadas)
- [Estrutura do repositório](#estrutura-do-repositório)
- [Compilando](#compilando)
- [CI/CD](#cicd)
- [Idiomas](#idiomas)
- [Projetos relacionados](#projetos-relacionados)
- [Observação sobre o `CMakeLists.txt` da raiz](#observação-sobre-o-cmakeliststxt-da-raiz)

---

## Visão geral

O **Halla Mobile** é o cliente Android do Halla: permite entrar nos mesmos
servidores **Halla Server** usados pelo cliente desktop, com voz, chat de
texto, canais, grupos/permissões e sussurro — de dentro do bolso, inclusive
com o app em segundo plano (serviço em primeiro plano + botão de PTT
flutuante sobre outros aplicativos).

Diferente de um app "wrapper", o Halla Mobile **não roda Qt** — é um app
Android nativo (Kotlin + Android Views) com um núcleo de rede/áudio próprio
escrito em C++, compilado como biblioteca nativa (`libhalla-core.so`) e
acessado via JNI.

## Principais recursos

- Conexão a servidores Halla Server (mesmo protocolo do cliente desktop).
- Voz em tempo real: captura/reprodução via `AudioRecord`/`AudioTrack`,
  codec **Opus** (compilado a partir da fonte oficial), com cancelamento de
  eco (`AcousticEchoCanceler`) e supressão de ruído (`NoiseSuppressor`)
  quando disponíveis no aparelho.
- Push-to-talk, transmissão contínua ou detecção de voz — inclusive com um
  **botão de PTT flutuante** (overlay sobre outros apps) para falar sem
  precisar abrir o Halla Mobile.
- **Sussurro**: canais específicos ou listas de usuários, com o mesmo
  conceito do cliente desktop.
- Canais, chat de texto (com histórico/rolagem), grupos de servidor/canal,
  permissões granulares, talk power, "cutucar" (poke).
- Emblemas globais oficiais vinculados à UID, obtidos de um registro Ed25519
  assinado e mantidos em cache para funcionamento offline.
- Roteamento de áudio para fone de ouvido/alto-falante/Bluetooth.
- Gravação local e diagnóstico de áudio (painel com status do pipeline de
  voz, útil para depurar problemas de microfone/alto-falante).
- Reconexão silenciosa ao trocar de rede (Wi-Fi ↔ dados móveis).
- Notificação persistente com ações rápidas (mudo do microfone, dos
  alto-falantes, desconectar) enquanto conectado.
- Verificação e download de atualizações dentro do próprio app (checksum
  SHA-256 conferido antes de instalar).
- Interface com tema claro/escuro (`Theme.AppCompat.DayNight`) e suporte a
  RTL.
- Localizado em **português, inglês e espanhol** (troca de idioma pelo app,
  não só pelo sistema).
- **Transmitir a própria tela em até 30 FPS e o áudio interno capturável** usando
  a autorização oficial `MediaProjection`/`AudioPlaybackCapture` do Android. O PCM interno alimenta
  uma `AudioTrack` WebRTC real usada exclusivamente por viewers Mobile e Desktop.
  O AAR do libwebrtc é fixado e corrigido para validar
  corretamente o buffer direto do ADM; o vídeo usa teto adaptativo de 1,2 Mbps. O UID do Halla é
  excluído para que as vozes da chamada não retornem na live. Também é possível
  assistir transmissões do Desktop ou de outro celular.
- **Complementos (plugins)**: pacotes `.halla-addon` com a mesma ABI C do
  Desktop, hooks de áudio, transporte `plugin_data` v5 e o complemento oficial
  de voz de rádio embutido (veja [Complementos](#complementos-plugins)).

**Segurança**
- Canal de controle em TLS com pinagem TOFU (mesmo esquema do cliente
  desktop).
- Identidade Ed25519: usa a API nativa `java.security` (Android 12/API 31+)
  quando disponível, com Bouncy Castle atualizado como implementação compatível
  de fallback em aparelhos mais antigos (API 26+). A chave privada é
  guardada cifrada com uma chave AES do **Android Keystore**, não em texto
  puro.
- Voz cifrada com ChaCha20-Poly1305 via **mbedTLS** (a mesma técnica AEAD do
  cliente desktop, implementação diferente por ser mais leve para Android).
- Pipeline de release **assinado**: builds de tag exigem a keystore de
  produção (GitHub Secrets), e o APK final passa por `apksigner verify`
  antes de publicar.

## Complementos (plugins)

O sistema de complementos do Halla Desktop agora existe também no Mobile, em
**Configurações → Complementos**:

- **Mesmo formato de pacote**: arquivos `.halla-addon` (ZIP com
  `manifest.json`), idênticos aos do Desktop — mesmo manifesto, mesmas
  capacidades declaradas, mesmo empacotador (`tools/package_plugin.py` do
  repositório Halla).
- **Mesma ABI C pública**: o host nativo (`plugin_host.cpp`) implementa a ABI
  de `halla_plugin_api.h` (ABI-base 1) com as interfaces modulares
  `halla.core.v1`, `halla.connection.v1`, `halla.audio.v1`, `halla.data.v1` e
  `halla.ui.v1` via `query_interface()`. Um plugin escrito para o Desktop
  recompilado para Android (NDK) funciona sem mudanças de código — bibliotecas
  são declaradas no manifesto sob `android-arm64`, `android-arm`,
  `android-x86_64` ou `android-x86` e carregadas com `dlopen()`.
- **Pipeline de áudio**: processadores PCM nos estágios de captura (antes do
  Opus) e de voz remota (por remetente, após decodificação), além de ganho,
  atenuação por distância e efeito de rádio por usuário.
- **Transporte `plugin_data` (protocolo v5)**: complementos trocam payloads
  binários (≤ 8 KiB) com instâncias do mesmo complemento em outros clientes,
  pelo canal TLS — o Mobile agora negocia protocolo v5 no `hello`.
- **Complemento oficial embutido**: **Voz de rádio policial**, o mesmo DSP do
  Desktop (banda estreita, compressão, chiado configurável), aplicável ao
  enviar e/ou ao ouvir, sem precisar de `.so` externo.
- Funções sem equivalente no Android (hotkeys globais, mute local por usuário
  do lado do host) retornam `HALLA_RESULT_UNAVAILABLE`, conforme previsto pela
  especificação da ABI — plugins devem tolerar ausências.

> **Segurança:** como no Desktop, uma biblioteca nativa executa no mesmo
> processo e com os mesmos privilégios do app. As capacidades do manifesto são
> informativas; instale apenas complementos de fontes confiáveis. A instalação
> valida o manifesto, limita tamanhos e bloqueia caminhos fora do pacote
> (zip-slip).

## Visualizador WebRTC (WebView)

Para assistir a transmissão de tela de alguém (modo WebRTC do cliente
desktop), o Halla Mobile abre uma `WebView` interna e usa o
**`RTCPeerConnection` nativo do próprio Android System WebView**, em vez de
empacotar um AAR nativo do libwebrtc. Essa escolha foi deliberada — algumas
versões do SDK Android nativo do WebRTC derrubavam o processo principal do
app em determinados aparelhos; rodar a sinalização/mídia dentro do processo
isolado da WebView evita esse problema, e ainda usa uma implementação real de
WebRTC (a do Chromium/WebView), não uma simulação.

## Arquitetura

```
┌──────────────────────────────┐        JNI        ┌───────────────────────────┐
│     Kotlin (app Android)     │ ─────────────────► │   C++ nativo (JNI bridge) │
│                               │ ◄───────────────── │                           │
│  MainActivity   — telas/UI   │    callbacks        │  HallaClientCore          │
│  HallaService    — 1º plano, │                     │   • soquetes TCP/UDP crus │
│    notificação, overlay PTT, │                     │   • parsing manual do     │
│    reconexão                 │                     │     protocolo (JSON)      │
│  HallaAudioManager — captura/│                     │   • codec Opus (encode/   │
│    reprodução PCM, AEC/NS    │                     │     decode)               │
│  HallaCore       — fachada   │                     │   • threads: TCP, UDP,    │
│    das funções externas JNI  │                     │     ping, keepalive NAT   │
│  LocaleManager   — idioma    │                     │                           │
└──────────────────────────────┘                     └───────────────────────────┘
```

- **`HallaCore`** (Kotlin `object`) é a fachada JNI: carrega
  `libhalla-core.so` e declara as funções nativas (`connectToServer`,
  `joinChannel`, `sendChatMessage`, `sendVoiceFrame`, `sendStatus`, ações de
  administração como `sendKick`/`sendBan`, etc.) e a interface `Callbacks`
  que o C++ chama de volta (`onConnected`, `onWelcomeReceived`,
  `onChannelListReceived`, `onUserListReceived`, `onChatMessageReceived`,
  `onAudioFrameReceived`, `onPingUpdated`, `onPokeReceived`...).
- **`HallaAudioManager`** cuida só do áudio do lado Android: abre o
  `AudioRecord`/`AudioTrack`, liga os efeitos nativos de eco/ruído quando
  disponíveis, e entrega/recebe blocos de PCM cru — a codificação/decodificação
  Opus acontece do lado C++.
- **`HallaService`** é um `Service` em primeiro plano (tipo `microphone`)
  que mantém a sessão viva com a tela apagada ou o app em segundo plano,
  desenha o botão de PTT flutuante (`WindowManager` + `SYSTEM_ALERT_WINDOW`),
  publica a notificação com ações rápidas e cuida da troca de rede.
- **`MainActivity`** é a tela principal (lista de servidores, árvore de
  canais, chat, opções) — feita com Android Views tradicionais (não Compose).
- **`LocaleManager`** aplica o idioma escolhido no app, independente do
  idioma do sistema.

## Núcleo nativo (C++/JNI)

`app/src/main/cpp/jni_bridge.cpp` implementa a classe `HallaClientCore`,
autocontida, sem depender de Qt:

- Conexão de controle via **soquete TCP** cru (BSD sockets/POSIX), com um
  parser de JSON minimalista feito à mão (`jsonExtractString`,
  `jsonExtractArray`) — o bastante para extrair os campos que o protocolo
  usa, sem trazer uma biblioteca JSON completa para o binário nativo.
- Voz via **soquete UDP**, com um `std::thread` dedicado ao laço de
  recepção (`udpLoop`) e outro para o keepalive/hole-punching de NAT
  (`udpPingLoop`).
- Um `std::thread` próprio para o laço de controle TCP (`tcpLoop`) e outro
  só para medir latência (`pingLoop`).
- Codificação/decodificação de voz com **libopus**, e cifragem AEAD
  (ChaCha20-Poly1305) com **mbedTLS** — ambos baixados e compilados na hora
  do build via `FetchContent` (veja `app/src/main/cpp/CMakeLists.txt`), sem
  precisar empacotar binários pré-compilados por ABI do Android.
- A geração/assinatura da identidade Ed25519 fica do lado Kotlin
  (`HallaCore.identityPublicKeyBase64`/`signIdentityNonceBase64`, chamadas de
  volta via JNI) — mantém o material de chave sob a Keystore do Android em
  vez de trafegar pelo núcleo C++.
- Todo evento relevante (conectado, desconectado, boas-vindas, lista de
  canais/usuários, mensagem de chat, quadro de áudio recebido, erro, ping,
  poke...) vira uma chamada JNI de volta para o `HallaCore.Callbacks` do
  Kotlin.

## Serviço em segundo plano

Boa parte do valor do app está em continuar funcionando com a tela apagada
ou o usuário em outro aplicativo — igual um cliente de voz "de verdade"
deve se comportar:

- `HallaService` roda como **serviço em primeiro plano** (`foregroundServiceType="microphone"`),
  com notificação obrigatória (exigência do Android para manter o microfone
  ativo em segundo plano).
- Um **botão de PTT flutuante** pode ser desenhado por cima de outros apps
  (permissão `SYSTEM_ALERT_WINDOW`), para segurar e falar sem precisar abrir
  o Halla Mobile.
- Detecta trocas de rede (`ConnectivityManager`/`NetworkCapabilities`) para
  reconectar sem o usuário perceber ao sair do Wi-Fi para os dados móveis
  (ou vice-versa).

## Permissões usadas

| Permissão | Para quê |
|---|---|
| `INTERNET` | conexão com o Halla Server |
| `RECORD_AUDIO` | captura do microfone |
| `MODIFY_AUDIO_SETTINGS` | modo de áudio de comunicação, roteamento fone/alto-falante |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_MICROPHONE` | manter a chamada ativa em segundo plano |
| `POST_NOTIFICATIONS` | notificação de chamada ativa com ações rápidas |
| `SYSTEM_ALERT_WINDOW` | botão de PTT flutuante sobre outros apps |
| `REQUEST_INSTALL_PACKAGES` | instalar a atualização baixada dentro do app |
| `BLUETOOTH`, `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN` | roteamento de áudio para fones Bluetooth |

## Estrutura do repositório

```
app/
├── build.gradle.kts              módulo Android (SDK, NDK, dependências)
└── src/main/
    ├── AndroidManifest.xml
    ├── cpp/
    │   ├── CMakeLists.txt         builda libhalla-core.so (busca o Opus via FetchContent)
    │   ├── jni_bridge.cpp         núcleo de rede/voz nativo (JNI)
    │   ├── halla_plugin_api.h     ABI C pública de complementos (a mesma do Desktop)
    │   ├── plugin_host.h/.cpp     host de complementos: dlopen, interfaces
    │   │                          halla.core/connection/audio/data/ui v1,
    │   │                          hooks de PCM e transporte plugin_data (v5)
    ├── kotlin/com/halla/mobile/
    │   ├── HallaCore.kt           fachada JNI (funções externas + callbacks,
    │   │                          + geração/assinatura da identidade Ed25519)
    │   ├── HallaAudioManager.kt   captura/reprodução PCM, AEC/NS, gravação local
    │   ├── HallaService.kt        serviço em 1º plano, notificação, overlay de PTT
    │   ├── HallaWebRtcViewer.kt   visualizador de transmissão de tela (WebView)
    │   ├── LocaleManager.kt       troca de idioma em runtime
    │   ├── PluginManager.kt       complementos: pacotes .halla-addon, manifesto,
    │   │                          ativar/desativar, configurações por schema
    │   └── MainActivity.kt        telas: conexão, canais, chat, opções
    └── res/
        ├── drawable/              ícones vetoriais e logo
        ├── layout/activity_main.xml
        └── values(-en|-es)/strings.xml   pt-BR (padrão), inglês, espanhol
build.gradle.kts, settings.gradle.kts, gradle.properties, gradlew
```

## Compilando

**Requisitos**: Android Studio (ou o `gradlew` da linha de comando) com
JDK 17, Android SDK 34 e NDK **25.2.9519653** instalados.

```bash
./gradlew assembleDebug   # desenvolvimento, applicationId com sufixo .debug
```

Releases oficiais usam `assembleRelease`, keystore estável configurada pelos
GitHub Secrets documentados em `SECURITY.md`, `apksigner verify`, SHA-256,
testes unitários e Android lint.

O Gradle, via `externalNativeBuild`, aciona o CMake de
`app/src/main/cpp/CMakeLists.txt` automaticamente — não é preciso rodar o
CMake manualmente. Na primeira build, o CMake baixa e compila o Opus a partir
da fonte (requer acesso à internet nesse passo).

## CI/CD

`.github/workflows/android.yml` executa testes e lint em pushes/PRs, gera um
APK debug separado apenas para CI e, em tags `v*`, exige a keystore de produção,
gera `HallaMobile.apk` assinado, valida a assinatura e publica APK + SHA-256.

## Idiomas

`values/` (padrão, português), `values-en/` e `values-es/` — cerca de 385
strings traduzidas em cada um. A troca pode ser feita dentro do próprio app
(`LocaleManager`), sem depender do idioma do sistema Android.

## Projetos relacionados

- **[Halla](https://github.com/GroupHalla/Halla)** — cliente desktop
  (Windows/Linux, Qt 6) que fala o mesmo protocolo. Desktop e Mobile podem
  transmitir tela via WebRTC; o Mobile usa MediaProjection para publicar e
  WebView/Chromium para assistir.
- **[Halla Server](https://github.com/GroupHalla/HallaServer)** — servidor
  auto-hospedável; veja
  [`PROTOCOL.md`](https://github.com/GroupHalla/HallaServer/blob/main/PROTOCOL.md)
  para a especificação completa do protocolo (atualmente v4).

## Observação sobre o `CMakeLists.txt` da raiz

O repositório ainda tem um `CMakeLists.txt` na raiz que referencia um app Qt
Quick/QML (`HallaMobileApp`, `src/main.cpp`, `src/Main.qml`,
`src/net/MobileNetSession.*`). Esses arquivos **não existem** na árvore do
projeto — foi uma abordagem inicial (Qt for Android), substituída pelo app
Android nativo descrito acima. O build real, local e no CI, usa
exclusivamente o Gradle (`./gradlew assembleDebug`/`assembleRelease`); esse
`CMakeLists.txt` da raiz continua sendo código morto e pode ser removido com
segurança.
