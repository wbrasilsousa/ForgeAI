# CMake toolchain for building Android shared libraries inside Termux.
#
# Termux's clang (aarch64, bionic) is used as the compiler — it natively
# targets aarch64-unknown-linux-android. The llama.cpp codebase is CPU-only
# here, so we don't need any of the NDK toolchain machinery.

set(CMAKE_SYSTEM_NAME Linux)
set(CMAKE_SYSTEM_PROCESSOR aarch64)

set(CMAKE_C_COMPILER   clang)
set(CMAKE_CXX_COMPILER clang++)
set(CMAKE_ASM_COMPILER clang)

set(CMAKE_C_FLAGS_INIT   "-fuse-ld=lld")
set(CMAKE_CXX_FLAGS_INIT "-fuse-ld=lld")

set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_PACKAGE ONLY)
