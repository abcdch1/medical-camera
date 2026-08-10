
cmake_minimum_required(VERSION 3.22.1)

project("nativelib")

# 16K page size support for Android devices
# Required for devices with 16KB memory page size (e.g., some Android 15+ devices)
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")

# lame
include_directories(${CMAKE_SOURCE_DIR}/module/mp3/lame)
aux_source_directory(${CMAKE_SOURCE_DIR}/module/mp3/lame SRC_LAME)

add_library(
        nativelib
        SHARED

        ${SRC_LAME}
        utils/logger.cpp
        module/yuv/yuv.cpp
        module/mp3/mp3.cpp
        proxy/proxy_yuv.cpp
        proxy/proxy_mp3.cpp
        nativelib.cpp)

find_library(
        log-lib
        log)

target_link_libraries(
        nativelib
        ${log-lib})