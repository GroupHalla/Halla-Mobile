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
  <b>com.halla.mobile</b> · Android 8.0+ (API 26) · versão 1.0.24
</p>

---

## Índice

- [Visão geral](#visão-geral)
- [Principais recursos](#principais-recursos)
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
- Roteamento de áudio para fone de ouvido/alto-falante/Bluetooth.
- Gravação local e diagnóstico de áudio (painel com status do pipeline de
  voz, útil para depurar problemas de microfone/alto-falante).
- Reconexão silenciosa ao trocar de rede (Wi-Fi ↔ dados móveis).
- Notificação persistente com ações rápidas (mudo do microfone, dos
  alto-falantes, desconectar) enquanto conectado.
- Verificação e download de atualizações dentro do próprio app.
- Interface com tema claro/escuro (`Theme.AppCompat.DayNight`) e suporte a
  RTL.
- Localizado em **português, inglês e espanhol** (troca de idioma pelo app,
  não só pelo sistema).

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
autocontida, sem depender de Qt nem de bibliotecas de terceiros além do
Opus:

- Conexão de controle via **soquete TCP** cru (BSD sockets/POSIX), com um
  parser de JSON minimalista feito à mão (`jsonExtractString`,
  `jsonExtractArray`) — o bastante para extrair os campos que o protocolo
  usa, sem trazer uma biblioteca JSON completa para o binário nativo.
- Voz via **soquete UDP**, com um `std::thread` dedicado ao laço de
  recepção (`udpLoop`) e outro para o keepalive/hole-punching de NAT
  (`udpPingLoop`).
- Um `std::thread` próprio para o laço de controle TCP (`tcpLoop`) e outro
  só para medir latência (`pingLoop`).
- Codificação/decodificação de voz com **libopus**, baixada e compilada na
  hora do build via `FetchContent` (tag `v1.4` do repositório oficial
  `xiph/opus`) — assim não é preciso empacotar binários pré-compilados do
  Opus para cada ABI do Android.
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
    │   └── jni_bridge.cpp         núcleo de rede/voz nativo (JNI)
    ├── kotlin/com/halla/mobile/
    │   ├── HallaCore.kt           fachada JNI (funções externas + callbacks)
    │   ├── HallaAudioManager.kt   captura/reprodução PCM, AEC/NS, gravação local
    │   ├── HallaService.kt        serviço em 1º plano, notificação, overlay de PTT
    │   ├── LocaleManager.kt       troca de idioma em runtime
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
./gradlew assembleDebug
# APK gerado em: app/build/outputs/apk/debug/app-debug.apk
```

O Gradle, via `externalNativeBuild`, aciona o CMake de
`app/src/main/cpp/CMakeLists.txt` automaticamente — não é preciso rodar o
CMake manualmente. Na primeira build, o CMake baixa e compila o Opus a partir
da fonte (requer acesso à internet nesse passo).

## CI/CD

`.github/workflows/android.yml` builda o APK a cada push na `main` e a cada
tag `v*` (JDK 17 + Android SDK + NDK 25.2.9519653 via GitHub Actions), e, se
for uma tag, publica automaticamente uma **Release** no GitHub com o
`HallaMobile.apk` anexado.

## Idiomas

`values/` (padrão, português), `values-en/` e `values-es/` — cerca de 385
strings traduzidas em cada um. A troca pode ser feita dentro do próprio app
(`LocaleManager`), sem depender do idioma do sistema Android.

## Projetos relacionados

- **[Halla](https://github.com/GroupHalla/Halla)** — cliente desktop
  (Windows/Linux/, Qt 6) que fala o mesmo protocolo.
- **[Halla Server](https://github.com/GroupHalla/HallaServer)** — servidor
  auto-hospedável.
  
