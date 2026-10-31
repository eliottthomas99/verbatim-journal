# CMakeLists.txt for Cactus Whistle (Needle) JNI bridge
# Bundles the Needle native engine; no Python wrapper.
cmake_minimum_required(VERSION 3.22.1)
project("verbatim-jni")

if(NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "The pinned Whistle engine supports arm64-v8a only")
endif()
get_filename_component(WHISTLE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../.local-models/whistle" ABSOLUTE)
if(NOT EXISTS "${WHISTLE_DIR}/native/arm64-v8a/libneedle.a")
    message(FATAL_ERROR "Run python -I tools/prepare_whistle.py from the repository root first")
endif()

# Add the Needle (Whistle engine) static library, bundled during APK build.
# .local-models/whistle/native/arm64-v8a/libneedle.a
add_library(needle STATIC
    IMPORTED
)
set_target_properties(needle PROPERTIES
    IMPORTED_LOCATION
    "${WHISTLE_DIR}/native/arm64-v8a/libneedle.a"
)

# Our JNI bridge — minimal C code linking the native API.
add_library(verbatim-jni SHARED
    needle_jni.c
)
target_include_directories(verbatim-jni PRIVATE
    "${WHISTLE_DIR}/native"
)
set_target_properties(verbatim-jni PROPERTIES LINKER_LANGUAGE CXX)
target_link_options(verbatim-jni PRIVATE "-Wl,-z,max-page-size=16384")
target_link_libraries(verbatim-jni
    needle
    log
)
