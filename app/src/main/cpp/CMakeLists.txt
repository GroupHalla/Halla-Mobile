cmake_minimum_required(VERSION 3.22.1)

project("halla-core" LANGUAGES CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Baixa e compila a biblioteca Opus oficial em tempo de execução para qualquer arquitetura Android (arm64-v8a, etc)
include(FetchContent)

FetchContent_Declare(
  opus
  GIT_REPOSITORY https://github.com/xiph/opus.git
  GIT_TAG        v1.4
)

set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "Build programs" FORCE)
set(OPUS_BUILD_TESTS OFF CACHE BOOL "Build tests" FORCE)
set(OPUS_BUILD_SHARED_LIBRARY OFF CACHE BOOL "Build shared library" FORCE)

FetchContent_MakeAvailable(opus)

add_library(halla-core SHARED
    jni_bridge.cpp
)

find_library(log-lib log)

target_compile_definitions(halla-core PRIVATE HAS_OPUS)

target_link_libraries(halla-core PRIVATE
    ${log-lib}
    opus
)
