cmake_minimum_required(VERSION 3.22.1)

project("halla-core" LANGUAGES CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Baixa e compila a biblioteca Opus oficial em tempo de execução para qualquer arquitetura Android (arm64-v8a, etc)
include(FetchContent)

FetchContent_Declare(
  opus
  GIT_REPOSITORY https://github.com/xiph/opus.git
  GIT_TAG        82ac57d9f1aaf575800cf17373348e45b7ce6c0d
)

FetchContent_Declare(
  mbedtls
  GIT_REPOSITORY https://github.com/Mbed-TLS/mbedtls.git
  GIT_TAG        0bebf8b8c7f07abe3571ded48a11aa907a1ffb20 # Mbed TLS 3.6.6
)

set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "Build programs" FORCE)
set(OPUS_BUILD_TESTS OFF CACHE BOOL "Build tests" FORCE)
set(OPUS_BUILD_SHARED_LIBRARY OFF CACHE BOOL "Build shared library" FORCE)
set(ENABLE_TESTING OFF CACHE BOOL "Disable mbedTLS tests" FORCE)
set(ENABLE_PROGRAMS OFF CACHE BOOL "Disable mbedTLS programs" FORCE)
set(MBEDTLS_FATAL_WARNINGS OFF CACHE BOOL "No fatal warnings" FORCE)

FetchContent_MakeAvailable(opus mbedtls)

add_library(halla-core SHARED
    jni_bridge.cpp
    plugin_host.cpp
)

find_library(log-lib log)

target_compile_definitions(halla-core PRIVATE HAS_OPUS HAS_MBEDTLS)

target_link_libraries(halla-core PRIVATE
    ${log-lib}
    opus
    mbedtls
    mbedx509
    mbedcrypto
)
