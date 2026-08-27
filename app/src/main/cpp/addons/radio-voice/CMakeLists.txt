cmake_minimum_required(VERSION 3.22.1)
project(halla-addon-radio-voice LANGUAGES CXX)

# Biblioteca do complemento oficial "Voz de rádio policial" para o pacote
# .halla-addon do catálogo (https://grouphalla.github.io/Halla-Addons/).
#
# Este CMake é INDEPENDENTE do build do aplicativo: o alvo não entra no APK
# (o complemento embutido é executado pelo plugin_host nativo). O job de CI
# do Halla Mobile compila esta biblioteca para as quatro ABIs Android e a
# publica como artefato, alimentando o pacote multiplataforma do catálogo.
#
# Uso (toolchain do NDK):
#   cmake -S app/src/main/cpp/addons/radio-voice -B build-addon-arm64-v8a \
#     -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
#     -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 \
#     -DCMAKE_BUILD_TYPE=Release
#   cmake --build build-addon-arm64-v8a   # -> libradio_voice.so

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(radio_voice SHARED radio_voice_plugin.cpp)

# halla_plugin_api.h e RadioVoiceDsp.h vivem no diretório cpp/ do app
# (cópia fiel de sdk/ e src/plugins/ do Halla Desktop — manter sincronizado).
target_include_directories(radio_voice PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/../..)

# Símbolos exportados: apenas halla_plugin_entry (a ABI C pública).
if (NOT WIN32)
    target_link_options(radio_voice PRIVATE
        "-Wl,--exclude-libs,ALL" "-Wl,-exclude-libs,ALL")
endif()
