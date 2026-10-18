# ForgeAI native library.
#
# Builds a shared library "libllamaforge.so" that embeds llama.cpp (CPU backend).
# The library is compiled by scripts/build_native.sh using the local toolchain
# and the result is dropped into app/src/main/jniLibs/arm64-v8a/.
# On a normal PC you can also run the same script (see README) to produce the .so.

cmake_minimum_required(VERSION 3.22)
project(forgeai_native LANGUAGES C CXX)

# everything is linked into a shared library -> must be position independent
set(CMAKE_POSITION_INDEPENDENT_CODE ON CACHE BOOL "" FORCE)

# ---------------------------------------------------------------------------
# llama.cpp build options (keep the build small and self contained)
# ---------------------------------------------------------------------------
# FORGE_ALL_VARIANTS: runtime CPU dispatch (GGML_CPU_ALL_VARIANTS +
# GGML_BACKEND_DL). Builds the CPU backend as several shared libs (baseline,
# dotprod, fp16, i8mm, sve...) and picks the best for the device at runtime:
# portable AND fast on any ARMv8 device. Requires BUILD_SHARED_LIBS=ON, so
# llama/ggml also become .so files that must be shipped together with
# libllamaforge.so and the native lib dir must be passed to nativeInit().
# Default OFF -> classic single static .so (see FORGE_ARM_DOTPROD below).
option(FORGE_ALL_VARIANTS "Runtime CPU ISA dispatch (portable, all devices)" OFF)

if(FORGE_ALL_VARIANTS)
    set(BUILD_SHARED_LIBS      ON  CACHE BOOL "" FORCE)
    set(GGML_BACKEND_DL        ON  CACHE BOOL "" FORCE)
    set(GGML_CPU_ALL_VARIANTS  ON  CACHE BOOL "" FORCE)
    set(GGML_CPU_ARM_ARCH      ""  CACHE STRING "" FORCE)
else()
    set(BUILD_SHARED_LIBS      OFF CACHE BOOL "" FORCE) # static-link llama/ggml into libllamaforge.so
    set(GGML_BACKEND_DL        OFF CACHE BOOL "" FORCE)
    set(GGML_CPU_ALL_VARIANTS  OFF CACHE BOOL "" FORCE)
endif()

set(LLAMA_BUILD_COMMON   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP      OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_UI       OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL        OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL           OFF CACHE BOOL "" FORCE)
# subprocess só existe no Linux glibc — no Termux/bionic (e no NDK Android) ele
# quebra o build do tools/mtmd (subprocess.h). Só precisamos de imagens, não vídeo.
set(LLAMA_SUBPROCESS      OFF CACHE BOOL "" FORCE)

set(GGML_NATIVE           OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP           OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE           OFF CACHE BOOL "" FORCE)
set(GGML_CPU_REPACK       OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN           OFF CACHE BOOL "" FORCE)

# Option to build with the Vulkan backend (requires the Vulkan SDK/headers and
# a device with Vulkan drivers). Default OFF -> plain CPU build.
option(FORGE_VULKAN "Build llama.cpp with the Vulkan GPU backend" OFF)
if(FORGE_VULKAN)
    set(GGML_VULKAN ON CACHE BOOL "" FORCE)
endif()

# Tune the CPU kernels for the device's ISA (ARMv8.2-A + dotprod).
# Enables the SDOT/UDOT kernels used by Q4_K_M & co — measured on-device:
#   prompt (batch) +66%, decode roughly neutral. (Adding +fp16 to the march
# speeds prompt a bit more but HURTS single-token decode by ~2.5x on
# Q4_K_M — do NOT add it.) The resulting .so requires a CPU with dotprod;
# disable it for a portable baseline build.
# build_native.sh controls this via FORGE_ARM_DOTPROD and passes
# GGML_CPU_ARM_ARCH explicitly; this option only kicks in for direct cmake runs.
option(FORGE_ARM_DOTPROD "Tune CPU kernels for ARMv8.2-A + dotprod (device-specific)" ON)
if(FORGE_ARM_DOTPROD AND NOT FORGE_ALL_VARIANTS AND NOT GGML_CPU_ARM_ARCH)
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod" CACHE STRING "" FORCE)
endif()

# ---------------------------------------------------------------------------
# llama.cpp source
# ---------------------------------------------------------------------------
# FORGE_LLAMA_SRC can point to an already cloned llama.cpp checkout
# (avoids the network clone; used by the Termux build script).
# tools/mtmd: multimodal support (vision via mmproj, SigLIP2/LFM2 etc.).
# The root llama.cpp CMakeLists adds tools/mtmd when LLAMA_BUILD_MTMD=ON and
# TOOLS/COMMON are OFF (they are, see above). The library "mtmd" is linked
# into libllamaforge.so so the JNI bridge can encode images on-device.
set(LLAMA_BUILD_MTMD ON CACHE BOOL "" FORCE)

set(FORGE_LLAMA_SRC "" CACHE STRING "Path to a local llama.cpp source tree (optional)")
if(FORGE_LLAMA_SRC)
    message(STATUS "Using local llama.cpp at ${FORGE_LLAMA_SRC}")
    add_subdirectory(${FORGE_LLAMA_SRC} llama_cpp)
else()
    include(FetchContent)
    FetchContent_Declare(
        llama_cpp
        GIT_REPOSITORY https://github.com/PrismML-Eng/llama.cpp.git
        GIT_TAG        prism-b10777-e3de715
        GIT_SHALLOW    TRUE
    )
    FetchContent_MakeAvailable(llama_cpp)
endif()

# ---------------------------------------------------------------------------
# Our shared library
# ---------------------------------------------------------------------------
add_library(llamaforge SHARED llama_jni.cpp)

find_library(LOG_LIB NAMES log HINTS ${CMAKE_PREFIX_PATH}/lib $ENV{PREFIX}/lib)
if(NOT LOG_LIB)
    set(LOG_LIB log)
endif()

target_compile_options(llamaforge PRIVATE -O3 -fvisibility=hidden -Wall -Wno-unused-variable)
target_link_libraries(llamaforge PRIVATE llama mtmd ${LOG_LIB})

if(ANDROID OR CMAKE_SYSTEM_NAME STREQUAL "Android")
    target_link_libraries(llamaforge PRIVATE android log dl)
else()
    target_link_libraries(llamaforge PRIVATE dl pthread)
endif()

# Ensure the JNI code sees the llama.h / gguf.h headers
target_include_directories(llamaforge PRIVATE
    ${llama_cpp_SOURCE_DIR}/include
    ${llama_cpp_SOURCE_DIR}/ggml/include
    ${llama_cpp_SOURCE_DIR}/tools/mtmd
)
