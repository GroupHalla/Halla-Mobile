cmake_minimum_required(VERSION 3.21)
file(STRINGS "${CMAKE_CURRENT_SOURCE_DIR}/VERSION" HALLA_MOBILE_VERSION LIMIT_COUNT 1)
string(STRIP "${HALLA_MOBILE_VERSION}" HALLA_MOBILE_VERSION)
project(HallaMobile VERSION ${HALLA_MOBILE_VERSION} LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_AUTOMOC ON)
set(CMAKE_AUTORCC ON)

find_package(Qt6 REQUIRED COMPONENTS Gui Quick Network Multimedia)

# Opcional: Busca Opus para codificação de voz móvel
set(HAS_OPUS OFF)
find_path(OPUS_INCLUDE_DIR opus.h PATH_SUFFIXES opus)
find_library(OPUS_LIBRARY opus)

if (OPUS_INCLUDE_DIR AND OPUS_LIBRARY)
    set(HAS_OPUS ON)
    add_library(opus SHARED IMPORTED GLOBAL)
    set_target_properties(opus PROPERTIES IMPORTED_LOCATION ${OPUS_LIBRARY})
    target_include_directories(opus INTERFACE ${OPUS_INCLUDE_DIR})
    message(STATUS "HallaMobile: Opus encontrado e ativado!")
else()
    message(WARNING "HallaMobile: Opus não encontrado. Compilando com simulação/bypass de codec (gravação local e DSP de voz ativos).")
endif()

qt_add_executable(HallaMobileApp
    src/main.cpp
    src/net/MobileNetSession.h
    src/net/MobileNetSession.cpp
    src/net/MobileVoiceEngine.h
    src/net/MobileVoiceEngine.cpp
    src/core/Models.h
    shared/HallaProtocol.h
)

if (HAS_OPUS)
    target_compile_definitions(HallaMobileApp PRIVATE HAS_OPUS)
endif()

qt_add_qml_module(HallaMobileApp
    URI HallaMobile
    VERSION 1.0
    QML_FILES
        src/Main.qml
)

target_include_directories(HallaMobileApp PRIVATE
    src
    src/net
    src/core
)

target_link_libraries(HallaMobileApp PRIVATE
    Qt6::Gui
    Qt6::Quick
    Qt6::Network
    Qt6::Multimedia
)

if (HAS_OPUS)
    target_link_libraries(HallaMobileApp PRIVATE opus)
endif()
