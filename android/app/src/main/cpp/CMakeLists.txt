# Compendium's native library for Android: the core (CPK writer, mod detection, library) plus
# the JSON engine in source/android. libarchive (with xz for 7z) is built from source for each ABI.
cmake_minimum_required(VERSION 3.22)
project(compendium_native LANGUAGES C CXX)

get_filename_component(REPO "${CMAKE_CURRENT_SOURCE_DIR}/../../../../.." REALPATH)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(ExternalProject)
set(DEPS_PREFIX ${CMAKE_BINARY_DIR}/deps)
file(MAKE_DIRECTORY ${DEPS_PREFIX}/include)

# Settings every dependency build shares with this one (the NDK toolchain, ABI, API level).
set(DEP_ARGS
    -DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}
    -DANDROID_ABI=${ANDROID_ABI}
    -DANDROID_PLATFORM=${ANDROID_PLATFORM}
    -DCMAKE_MAKE_PROGRAM=${CMAKE_MAKE_PROGRAM}
    -DCMAKE_BUILD_TYPE=Release
    -DCMAKE_INSTALL_PREFIX=${DEPS_PREFIX}
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON
    -DBUILD_SHARED_LIBS=OFF)

ExternalProject_Add(xz_ep
    URL https://github.com/tukaani-project/xz/releases/download/v5.4.7/xz-5.4.7.tar.gz
    URL_HASH SHA256=8db6664c48ca07908b92baedcfe7f3ba23f49ef2476864518ab5db6723836e71
    DOWNLOAD_EXTRACT_TIMESTAMP ON
    CMAKE_ARGS ${DEP_ARGS}
        -DXZ_TOOL_XZ=OFF -DXZ_TOOL_XZDEC=OFF -DXZ_TOOL_LZMADEC=OFF -DXZ_TOOL_LZMAINFO=OFF
        -DXZ_TOOL_SYMLINKS=OFF -DXZ_TOOL_SCRIPTS=OFF -DXZ_NLS=OFF -DXZ_DOXYGEN=OFF -DBUILD_TESTING=OFF
    BUILD_BYPRODUCTS ${DEPS_PREFIX}/lib/liblzma.a)

ExternalProject_Add(libarchive_ep
    DEPENDS xz_ep
    URL https://github.com/libarchive/libarchive/releases/download/v3.7.7/libarchive-3.7.7.tar.xz
    URL_HASH SHA256=879acd83c3399c7caaee73fe5f7418e06087ab2aaf40af3e99b9e29beb29faee
    DOWNLOAD_EXTRACT_TIMESTAMP ON
    CMAKE_ARGS ${DEP_ARGS}
        -DENABLE_TEST=OFF -DENABLE_TAR=OFF -DENABLE_CPIO=OFF -DENABLE_CAT=OFF -DENABLE_UNZIP=OFF
        -DENABLE_OPENSSL=OFF -DENABLE_NETTLE=OFF -DENABLE_MBEDTLS=OFF -DENABLE_LIBB2=OFF -DENABLE_LZ4=OFF
        -DENABLE_LZO=OFF -DENABLE_ZSTD=OFF -DENABLE_BZip2=OFF -DENABLE_LIBXML2=OFF -DENABLE_EXPAT=OFF
        -DENABLE_PCREPOSIX=OFF -DENABLE_PCRE2POSIX=OFF -DENABLE_LibGCC=OFF -DENABLE_CNG=OFF
        -DENABLE_ICONV=OFF -DENABLE_ACL=OFF -DENABLE_XATTR=OFF -DENABLE_LZMA=ON -DENABLE_ZLIB=ON
        -DCMAKE_FIND_ROOT_PATH=${DEPS_PREFIX}
        -DLIBLZMA_INCLUDE_DIR=${DEPS_PREFIX}/include
        -DLIBLZMA_LIBRARY=${DEPS_PREFIX}/lib/liblzma.a
    BUILD_BYPRODUCTS ${DEPS_PREFIX}/lib/libarchive.a)

add_library(archive_dep STATIC IMPORTED)
set_target_properties(archive_dep PROPERTIES IMPORTED_LOCATION ${DEPS_PREFIX}/lib/libarchive.a)
add_library(lzma_dep STATIC IMPORTED)
set_target_properties(lzma_dep PROPERTIES IMPORTED_LOCATION ${DEPS_PREFIX}/lib/liblzma.a)

# The core, without the libcurl client (downloads go through Kotlin).
file(GLOB CORE_SOURCES ${REPO}/source/core/*.cpp)
list(FILTER CORE_SOURCES EXCLUDE REGEX "http_curl\\.cpp$")
add_library(mmcore STATIC ${CORE_SOURCES})
target_include_directories(mmcore PUBLIC ${REPO}/source/core ${REPO}/source/third_party ${DEPS_PREFIX}/include)
add_dependencies(mmcore libarchive_ep)

add_library(compendium SHARED ${REPO}/source/android/engine.cpp ${REPO}/source/android/jni_glue.cpp)
target_include_directories(compendium PRIVATE ${REPO}/source/android)
target_link_libraries(compendium PRIVATE mmcore archive_dep lzma_dep z log)
# 16 KB page size support (Android 15+ devices).
target_link_options(compendium PRIVATE -Wl,-z,max-page-size=16384)
