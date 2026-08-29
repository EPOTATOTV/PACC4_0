cmake_minimum_required(VERSION 3.6)
project(pacc_native_probe C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(pacc_native_probe SHARED
        native_probe.cpp)

target_compile_options(pacc_native_probe PRIVATE
        -fvisibility=hidden
        -Wall
        -Wextra)

# 安全加固编译选项
target_compile_options(pacc_native_probe PRIVATE
        -fstack-protector-strong)
target_link_options(pacc_native_probe PRIVATE
        -z relro
        -z noexecstack)

find_library(log-lib log)
target_link_libraries(pacc_native_probe
        ${log-lib})