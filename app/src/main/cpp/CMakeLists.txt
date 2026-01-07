cmake_minimum_required(VERSION 3.18.1)
project("glyphdiff")

if (ANDROID_ABI STREQUAL "armeabi-v7a")
    add_definitions(-DARM_NEON=1)
    list(APPEND SOURCES
            arm/neon_math.cpp
    )
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -mfpu=neon -mfloat-abi=softfp")

elseif (ANDROID_ABI STREQUAL "arm64-v8a")
    add_definitions(-DARM_NEON=1 -DARM64=1)
    list(APPEND SOURCES
            arm64/neon_math.cpp
    )
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -march=armv8-a+simd")

elseif (ANDROID_ABI MATCHES "x86")
    add_definitions(-DSSE3=1)
    list(APPEND SOURCES
            x86/sse_math.cpp
    )
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -mssse3")

elseif (ANDROID_ABI MATCHES "x86_64")
    add_definitions(-DAVX2=1)
    list(APPEND SOURCES
            x86_64/avx_math.cpp
    )
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -mavx2")
endif()

# 添加 OpenMP 查找
find_package(OpenMP REQUIRED)

if (OpenMP_CXX_FOUND)
    target_link_libraries(OpenMP::OpenMP_CXX)
endif ()

add_library(glyphdiff SHARED
        common.cpp
        ${SOURCES}
        native-lib.cpp
)

target_include_directories(glyphdiff PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/arm
        ${CMAKE_CURRENT_SOURCE_DIR}/arm64
        ${CMAKE_CURRENT_SOURCE_DIR}/x86
        ${CMAKE_CURRENT_SOURCE_DIR}/x86_64
)

#target_compile_options(glyphdiff PRIVATE -fopenmp)
target_link_libraries(glyphdiff PRIVATE -fopenmp
        android
        log
)