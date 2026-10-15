#!/usr/bin/env bash
# ============================================================================
# Builds libllamaforge.so (llama.cpp + JNI bridge) for arm64-v8a and drops it
# into app/src/main/jniLibs/arm64-v8a/ together with libc++_shared.so.
#
# Works both in Termux (native toolchain) and on a Linux PC (requires:
# clang, lld, ninja, cmake >= 3.22 and a local llama.cpp checkout or network
# access to clone it).
#
# Usage:
#   ./scripts/build_native.sh
#   LLAMA_CPP_DIR=/path/to/llama.cpp ./scripts/build_native.sh
# ============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ABI="${ABI:-arm64-v8a}"
LLAMA_TAG="${LLAMA_TAG:-b10333}"
LLAMA_CPP_DIR="${LLAMA_CPP_DIR:-$HOME/llama.cpp}"
JOBS="${JOBS:-$(nproc 2>/dev/null || echo 2)}"
BUILD_DIR="${BUILD_DIR:-$ROOT/build/native}"
JNILIBS="$ROOT/app/src/main/jniLibs/$ABI"

# ----------------------------------------------------------------------------
# Performance knobs
# ----------------------------------------------------------------------------
# FORGE_ARM_DOTPROD=1 (default): compiles the CPU kernels for ARMv8.2-A with
# dotprod (SDOT/UDOT). Measured on-device: prompt processing +66%, generation
# roughly neutral. Do NOT add +fp16 to the march — it speeds up prompt a bit
# more but slows single-token generation ~2.5x on Q4_K_M models.
# The resulting .so will NOT run on older CPUs without dotprod (A73 and
# below) — set FORGE_ARM_DOTPROD=0 for a portable baseline build.
FORGE_ARM_DOTPROD="${FORGE_ARM_DOTPROD:-1}"
# FORGE_ALL_VARIANTS=1: build the CPU backend with runtime ISA dispatch
# (GGML_CPU_ALL_VARIANTS + GGML_BACKEND_DL). Ships libllama.so, libggml*.so and
# libggml-cpu-*.so (one per ISA: baseline, dotprod, fp16, i8mm, sve...) — the
# app picks the best for each device at runtime. Portable AND fast; this is the
# recommended config for distribution. Requires shipping all libs (done below)
# and the app passing its native lib dir to nativeInit (already implemented).
FORGE_ALL_VARIANTS="${FORGE_ALL_VARIANTS:-0}"
# FORGE_VULKAN=1: builds llama.cpp with the Vulkan backend (GPU). Requires
#   pkg install vulkan-headers shaderc
# then enable "Camadas na GPU" in Ajustes after loading a model. Biggest win
# for 1B+ models on the Mali-G76. With FORGE_ALL_VARIANTS=1 the Vulkan backend
# is also a dl backend (libggml-vulkan.so) and gets shipped automatically.
FORGE_VULKAN="${FORGE_VULKAN:-0}"

echo "==> ForgeAI native build"
echo "    ABI:       $ABI"
echo "    llama.cpp: $LLAMA_CPP_DIR (tag $LLAMA_TAG)"
echo "    jobs:      $JOBS"
if [ "$FORGE_ALL_VARIANTS" = "1" ]; then
    echo "    CPU:       ALL_VARIANTS (dispatch por CPU em runtime — portátil)"
else
    echo "    CPU tune:  $([ "$FORGE_ARM_DOTPROD" = "1" ] && echo 'armv8.2-a+dotprod (rápido, específico deste aparelho)' || echo 'baseline (portátil)')"
fi
echo "    Vulkan:    $([ "$FORGE_VULKAN" = "1" ] && echo 'ON (GPU Mali-G76)' || echo 'OFF (só CPU)')"

if [ "$FORGE_VULKAN" = "1" ]; then
    if ! command -v glslc >/dev/null 2>&1; then
        echo "!! Vulkan build requer o compilador de shaders glslc." >&2
        echo "   Instale:  pkg install vulkan-headers shaderc" >&2
        exit 1
    fi
fi
if [ "$FORGE_ARM_DOTPROD" = "1" ]; then
    echo "!! ATENÇÃO: libllamaforge.so será otimizada para ARMv8.2-A+dotprod."
    echo "   Não funcionará em aparelhos sem dotprod (ex.: Cortex-A73 e anteriores)."
fi

# --- 1) obtain llama.cpp ------------------------------------------------
if [ ! -f "$LLAMA_CPP_DIR/CMakeLists.txt" ]; then
    echo "==> cloning llama.cpp ($LLAMA_TAG)..."
    mkdir -p "$(dirname "$LLAMA_CPP_DIR")"
    git clone --depth 1 --branch "$LLAMA_TAG" \
        https://github.com/ggml-org/llama.cpp.git "$LLAMA_CPP_DIR"
fi

# --- 2) configure --------------------------------------------------------
EXTRA_ARGS=()
if [ "$FORGE_ALL_VARIANTS" = "1" ]; then
    EXTRA_ARGS+=(-DFORGE_ALL_VARIANTS=ON)
else
    EXTRA_ARGS+=(-DFORGE_ALL_VARIANTS=OFF)
    if [ "$FORGE_ARM_DOTPROD" = "1" ]; then
        EXTRA_ARGS+=(-DFORGE_ARM_DOTPROD=ON -DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod)
    else
        EXTRA_ARGS+=(-DFORGE_ARM_DOTPROD=OFF -DGGML_CPU_ARM_ARCH="")
    fi
fi
if [ "$FORGE_VULKAN" = "1" ]; then
    EXTRA_ARGS+=(-DFORGE_VULKAN=ON)
fi

cmake -S "$ROOT/app/src/main/cpp" -B "$BUILD_DIR" -G Ninja \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_TOOLCHAIN_FILE="$ROOT/scripts/termux-toolchain.cmake" \
    -DCMAKE_MAKE_PROGRAM="$(command -v ninja)" \
    -DFORGE_LLAMA_SRC="$LLAMA_CPP_DIR" \
    "${EXTRA_ARGS[@]}" \
    -DLLAMA_BUILD_COMMON=OFF -DLLAMA_BUILD_TESTS=OFF \
    -DLLAMA_BUILD_TOOLS=OFF -DLLAMA_BUILD_EXAMPLES=OFF \
    -DLLAMA_BUILD_SERVER=OFF -DLLAMA_BUILD_APP=OFF -DLLAMA_BUILD_UI=OFF \
    -DLLAMA_OPENSSL=OFF -DLLAMA_CURL=OFF \
    -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF -DGGML_CCACHE=OFF \
    -DGGML_CPU_ALL_VARIANTS=OFF -DGGML_CPU_REPACK=OFF

# --- 3) build -------------------------------------------------------------
cmake --build "$BUILD_DIR" --target llamaforge -j "$JOBS"

# --- 4) install -----------------------------------------------------------
mkdir -p "$JNILIBS"
cp "$BUILD_DIR/libllamaforge.so" "$JNILIBS/libllamaforge.so"

# ALL_VARIANTS: llama/ggml são libs compartilhadas e o backend CPU vira
# libggml-cpu.so + libggml-cpu-<isa>.so (uma por variante). Todas precisam
# ir junto no APK, senão o app não encontra o backend em runtime.
if [ "$FORGE_ALL_VARIANTS" = "1" ]; then
    cp "$BUILD_DIR/libllama.so" "$JNILIBS/" 2>/dev/null || true
    cp "$BUILD_DIR"/libggml*.so "$JNILIBS/" 2>/dev/null || true
fi

# libc++_shared.so — bundled from the Termux toolchain (bionic aarch64)
CXX_SHARED="$(clang++ -print-file-name=libc++_shared.so)"
if [ -f "$CXX_SHARED" ]; then
    cp "$CXX_SHARED" "$JNILIBS/libc++_shared.so"
else
    echo "!! libc++_shared.so not found via clang; you must bundle it manually"
fi

echo "==> done:"
ls -la "$JNILIBS"
