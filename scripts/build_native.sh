#!/usr/bin/env bash
# ============================================================================
# Builds libllamaforge.so (llama.cpp + JNI bridge) for arm64-v8a and drops it
# into app/src/main/jniLibs/arm64-v8a/.
#
# Dois modos:
#   Termux (celular)  : usa o clang do Termux  (sem FORGE_NDK)
#   NDK (CI / x86_64) : usa o Android NDK      (FORGE_NDK=/caminho/do/ndk)
#
# Usage:
#   ./scripts/build_native.sh
#   FORGE_NDK=$HOME/android-ndk-r28c ./scripts/build_native.sh
#   FORGE_TUNE=v9 ./scripts/build_native.sh        # ARMv9 (i8mm/bf16/SVE2)
#   FORGE_TUNE=portable ./scripts/build_native.sh  # APK para qualquer ARMv8
#   FORGE_ALL_VARIANTS=1 ./scripts/build_native.sh # dispatch de CPU em runtime
#   FORGE_VULKAN=1 ./scripts/build_native.sh       # backend Vulkan (Adreno)
#
# O .so sempre é linkado com page-size de 16 KB (exigido por Android 15+/Play e
# inofensivo em kernels de 4 KB, como o do Z Fold 8).
# ============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ABI="${ABI:-arm64-v8a}"
MIN_API="${MIN_API:-26}"
LLAMA_REPO="${LLAMA_REPO:-https://github.com/PrismML-Eng/llama.cpp.git}"
LLAMA_TAG="${LLAMA_TAG:-prism-b10777-e3de715}"
LLAMA_CPP_DIR="${LLAMA_CPP_DIR:-$HOME/llama.cpp}"
JOBS="${JOBS:-$(nproc 2>/dev/null || echo 2)}"
BUILD_DIR="${BUILD_DIR:-$ROOT/build/native}"
JNILIBS="$ROOT/app/src/main/jniLibs/$ABI"
FORGE_NDK="${FORGE_NDK:-}"

# Android 15+ exige libs alinhadas em 16 KB (NDK r27+ já faz por padrão; o
# clang do Termux não, então passamos a flag explicitamente nos dois modos).
PAGE_SIZE_FLAGS="-Wl,-z,max-page-size=16384"

# ----------------------------------------------------------------------------
# CPU tuning
# ----------------------------------------------------------------------------
# FORGE_TUNE=auto (default): detecta a ISA do aparelho pelo /proc/cpuinfo.
#   ARMv9 + SVE2 + i8mm + bf16  -> armv9-a+sve2+i8mm+bf16  (Z Fold 8 / 8 Elite)
#   ARMv8.2 + dotprod           -> armv8.2-a+dotprod       (Snapdragon/Exynos antigos)
#   sem dotprod                 -> baseline portátil
# Valores explícitos: v9 | dotprod | portable | qualquer march string do clang.
# NÃO adicione +fp16: acelera o prompt e derruba o decode ~2,5x em Q4_K_M.
detect_arch() {
    local f
    f="$(grep -m1 '^Features' /proc/cpuinfo 2>/dev/null || true)"
    case "$f" in
        *" sve2 "*" i8mm "*|*" i8mm "*" sve2 "*) echo "armv9-a+sve2+i8mm+bf16" ;;
        *" asimddp "*)                           echo "armv8.2-a+dotprod" ;;
        *)                                       echo "" ;;
    esac
}

FORGE_TUNE="${FORGE_TUNE:-auto}"
case "$FORGE_TUNE" in
    auto)     CPU_ARCH="$(detect_arch)" ;;
    v9|armv9) CPU_ARCH="armv9-a+sve2+i8mm+bf16" ;;
    dotprod)  CPU_ARCH="armv8.2-a+dotprod" ;;
    portable) CPU_ARCH="" ;;
    *)        CPU_ARCH="$FORGE_TUNE" ;;
esac

# FORGE_ALL_VARIANTS=1: CPU backend com dispatch em runtime (GGML_CPU_ALL_VARIANTS
# + GGML_BACKEND_DL): porta libllama.so, libggml*.so e libggml-cpu-*.so (baseline,
# dotprod, fp16, i8mm, sve...) e o app escolhe a melhor no aparelho. Portátil E
# rápido — recomendado para APK distribuído. Requer BUILD_SHARED_LIBS=ON.
FORGE_ALL_VARIANTS="${FORGE_ALL_VARIANTS:-0}"
# FORGE_VULKAN=1: backend Vulkan (GPU Adreno/Mali). Requer `vulkan-headers` e
# `shaderc` no Termux (no CI vêm do NDK).
FORGE_VULKAN="${FORGE_VULKAN:-0}"

echo "==> ForgeAI native build"
echo "    ABI:       $ABI (minSdk $MIN_API)"
echo "    llama.cpp: $LLAMA_CPP_DIR (repo $LLAMA_REPO, tag $LLAMA_TAG)"
echo "    jobs:      $JOBS"
echo "    toolchain: $([ -n "$FORGE_NDK" ] && echo "Android NDK ($FORGE_NDK)" || echo "Termux clang")"
if [ "$FORGE_ALL_VARIANTS" = "1" ]; then
    echo "    CPU:       ALL_VARIANTS (dispatch por CPU em runtime — portátil)"
else
    echo "    CPU march: ${CPU_ARCH:-<baseline portátil>}"
fi
echo "    Vulkan:    $([ "$FORGE_VULKAN" = "1" ] && echo 'ON' || echo 'OFF (só CPU)')"
echo "    page size: 16 KB"

if [ "$FORGE_VULKAN" = "1" ] && [ -z "$FORGE_NDK" ] && ! command -v glslc >/dev/null 2>&1; then
    echo "!! Build Vulkan no Termux requer glslc:  pkg install vulkan-headers shaderc" >&2
    exit 1
fi

# --- 1) llama.cpp ---------------------------------------------------------
if [ ! -f "$LLAMA_CPP_DIR/CMakeLists.txt" ]; then
    echo "==> cloning llama.cpp ($LLAMA_TAG)..."
    mkdir -p "$(dirname "$LLAMA_CPP_DIR")"
    git clone --depth 1 --branch "$LLAMA_TAG" \
        "$LLAMA_REPO" "$LLAMA_CPP_DIR"
fi

# --- 2) configure ---------------------------------------------------------
EXTRA_ARGS=()
TOOLCHAIN_ARGS=()

if [ -n "$FORGE_NDK" ]; then
    NDK_TOOLCHAIN="$FORGE_NDK/build/cmake/android.toolchain.cmake"
    if [ ! -f "$NDK_TOOLCHAIN" ]; then
        echo "!! NDK inválido (não achei $NDK_TOOLCHAIN). Aponte FORGE_NDK para a raiz do NDK." >&2
        exit 1
    fi
    TOOLCHAIN_ARGS+=(
        -DCMAKE_TOOLCHAIN_FILE="$NDK_TOOLCHAIN"
        -DANDROID_ABI="$ABI"
        -DANDROID_PLATFORM="android-$MIN_API"
        -DANDROID_STL=c++_shared
    )
else
    TOOLCHAIN_ARGS+=(-DCMAKE_TOOLCHAIN_FILE="$ROOT/scripts/termux-toolchain.cmake")
fi

if [ "$FORGE_ALL_VARIANTS" = "1" ]; then
    EXTRA_ARGS+=(-DFORGE_ALL_VARIANTS=ON)
else
    EXTRA_ARGS+=(-DFORGE_ALL_VARIANTS=OFF -DGGML_CPU_ARM_ARCH="$CPU_ARCH")
fi
if [ "$FORGE_VULKAN" = "1" ]; then
    EXTRA_ARGS+=(-DFORGE_VULKAN=ON)
fi

cmake -S "$ROOT/app/src/main/cpp" -B "$BUILD_DIR" -G Ninja \
    -DCMAKE_BUILD_TYPE=Release \
    "${TOOLCHAIN_ARGS[@]}" \
    -DCMAKE_MAKE_PROGRAM="$(command -v ninja)" \
    -DCMAKE_SHARED_LINKER_FLAGS="$PAGE_SIZE_FLAGS" \
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

if [ "$FORGE_ALL_VARIANTS" = "1" ]; then
    # llama/ggml viram libs compartilhadas e o backend CPU é um .so por variante.
    cp "$BUILD_DIR/libllama.so" "$JNILIBS/" 2>/dev/null || true
    cp "$BUILD_DIR"/libggml*.so "$JNILIBS/" 2>/dev/null || true
fi

# libc++_shared.so: do NDK no modo NDK; do clang do Termux no modo Termux.
CXX_SHARED=""
if [ -n "$FORGE_NDK" ]; then
    CXX_SHARED="$(find "$FORGE_NDK/toolchains/llvm/prebuilt" -name 'libc++_shared.so' \
        -path "*aarch64-linux-android*" 2>/dev/null | head -1)"
else
    CXX_SHARED="$(clang++ -print-file-name=libc++_shared.so 2>/dev/null || true)"
fi
if [ -n "$CXX_SHARED" ] && [ -f "$CXX_SHARED" ]; then
    cp "$CXX_SHARED" "$JNILIBS/libc++_shared.so"
    echo "    libc++_shared: $CXX_SHARED"
else
    echo "!! libc++_shared.so não encontrado — o build com STL compartilhada vai falhar em runtime" >&2
fi

echo "==> done:"
ls -la "$JNILIBS"
