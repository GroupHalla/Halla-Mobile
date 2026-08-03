cmake_minimum_required(VERSION 3.21)

project(HallaMobile VERSION 1.0.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_AUTOMOC ON)
set(CMAKE_AUTORCC ON)

find_package(Qt6 REQUIRED COMPONENTS Gui Quick Network)

qt_add_executable(HallaMobileApp
    src/main.cpp
    src/net/MobileNetSession.h
    src/net/MobileNetSession.cpp
    src/core/Models.h
    shared/HallaProtocol.h
)

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
)
