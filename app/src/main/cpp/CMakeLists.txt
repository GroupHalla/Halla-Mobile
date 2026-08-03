cmake_minimum_required(VERSION 3.22.1)

project("halla-core" LANGUAGES CXX)

add_library(halla-core SHARED
    jni_bridge.cpp
)

find_library(log-lib log)

target_link_libraries(halla-core PRIVATE
    ${log-lib}
)
