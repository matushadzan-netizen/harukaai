cmake_minimum_required(VERSION 3.22.1)
project(localai)

# --- llama.cpp is vendored as a git submodule at app/src/main/cpp/llama.cpp ---
# You must fetch it once (see README.md at the project root) because this
# build environment has no network access to clone it for you:
#   git submodule add https://github.com/ggerganov/llama.cpp app/src/main/cpp/llama.cpp
#   git submodule update --init --recursive
set(LLAMA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp)

if(NOT EXISTS ${LLAMA_DIR}/CMakeLists.txt)
    message(FATAL_ERROR
        "llama.cpp not found at ${LLAMA_DIR}. Run:\n"
        "  git submodule add https://github.com/ggerganov/llama.cpp app/src/main/cpp/llama.cpp\n"
        "  git submodule update --init --recursive\n"
        "from the project root, then re-sync Gradle.")
endif()

# Build llama.cpp's own CMake project (produces the `llama`, `ggml`, and `llava` static libs).
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
add_subdirectory(${LLAMA_DIR} llama_build)

add_library(localai SHARED native-lib.cpp)

target_include_directories(localai PRIVATE
    ${LLAMA_DIR}
    ${LLAMA_DIR}/include
    ${LLAMA_DIR}/common
    ${LLAMA_DIR}/examples/llava
)

target_link_libraries(localai
    llama
    common
    android
    log
)

# If the llava (vision) target exists in your llama.cpp checkout, link it too.
if(TARGET llava)
    target_link_libraries(localai llava)
    target_compile_definitions(localai PRIVATE LOCALAI_HAS_VISION=1)
endif()
