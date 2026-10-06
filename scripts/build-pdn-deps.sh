#!/bin/sh
set -eu
: "${PROJECT_ROOT:?}" "${CC_FLAGS:?}" "${SYSROOT:?}"
DEPS=$PROJECT_ROOT/build/proot-distro-nolib/deps
PREFIX=$DEPS/install
mkdir -p "$DEPS/downloads" "$PREFIX"
fetch() {
    file=$DEPS/downloads/$1
    if [ ! -f "$file" ]; then
        curl -fL --retry 2 --connect-timeout 20 --max-time 300 "$2" -o "$file.part"
        mv "$file.part" "$file"
    fi
    printf '%s  %s\n' "$3" "$file" | sha256sum -c -
}
fetch mbedtls.tar.bz2 https://github.com/Mbed-TLS/mbedtls/releases/download/mbedtls-3.6.7/mbedtls-3.6.7.tar.bz2 a7e8bcbec0e6f761b4af24f25677626b35f762f68eef79c08677a363212d11f6
fetch curl.tar.xz https://curl.se/download/curl-8.22.0.tar.xz f7ef3ae8a22e521f289803fe93543eb64c329b58aa73a9e224dfd915a2a5f4f7
fetch libarchive.tar.xz https://www.libarchive.org/downloads/libarchive-3.8.9.tar.xz 888c934f9d95648ecb9163dc8e23ab80a476ecb81a8f1154704a227b5b676dde
fetch zlib.tar.gz https://zlib.net/zlib-1.3.2.tar.gz bb329a0a2cd0274d05519d61c667c062e06990d72e125ee2dfa8de64f0119d16
prepare() {
    name=$1
    mkdir -p "$DEPS/$name-src"
    tar -xf "$DEPS/downloads/$2" --strip-components=1 -C "$DEPS/$name-src"
}
COMPILER_RESOURCE=$("$CC" -print-resource-dir)
export CFLAGS="$CC_FLAGS -resource-dir=$COMPILER_RESOURCE -O2 -fPIC -ffile-prefix-map=$DEPS=deps"
export AR=${AR:-llvm-ar}
export RANLIB=${RANLIB:-llvm-ranlib}
export LDFLAGS="--unwindlib=none -L$PREFIX/lib -Wl,-z,max-page-size=16384"
export CPPFLAGS="-I$PREFIX/include"
export PKG_CONFIG_LIBDIR="$PREFIX/lib/pkgconfig"
mkdir -p "$PREFIX/lib" "$PREFIX/include"
if [ ! -f "$DEPS/mbedtls.done" ]; then
    prepare mbedtls mbedtls.tar.bz2
    (cd "$DEPS/mbedtls-src" && make -j "${JOBS:-2}" CC="$CC" AR="${AR:-llvm-ar}" lib)
    cp "$DEPS/mbedtls-src/library/"*.a "$PREFIX/lib/"
    cp -R "$DEPS/mbedtls-src/include/mbedtls" "$DEPS/mbedtls-src/include/psa" "$PREFIX/include/"
    printf 'done\n' > "$DEPS/mbedtls.done"
fi
if [ ! -f "$DEPS/zlib.done" ]; then
    prepare zlib zlib.tar.gz
    (cd "$DEPS/zlib-src" && CHOST=aarch64-linux-android ./configure --static --prefix="$PREFIX" && make -j "${JOBS:-2}" && make install)
    printf 'done\n' > "$DEPS/zlib.done"
fi
if [ ! -f "$DEPS/curl.done" ]; then
    prepare curl curl.tar.xz
    (cd "$DEPS/curl-src" && ./configure --host=aarch64-linux-android --prefix="$PREFIX" \
        --disable-shared --enable-static --with-mbedtls="$PREFIX" \
        --without-libpsl --without-libidn2 --without-libssh2 --without-libssh \
        --without-zlib --without-brotli --without-zstd --without-nghttp2 --without-nghttp3 \
        --disable-threaded-resolver --disable-ares --disable-ldap --disable-ldaps \
        --disable-ftp --disable-file --disable-rtsp --disable-dict --disable-telnet \
        --disable-tftp --disable-pop3 --disable-imap --disable-smb --disable-smtp \
        --disable-gopher --disable-mqtt --disable-docs --disable-libcurl-option \
        --without-ca-bundle --with-ca-path=/system/etc/security/cacerts \
        && make -C lib -j "${JOBS:-2}" && make -C lib install && make -C include install)
    printf 'done\n' > "$DEPS/curl.done"
fi
if [ ! -f "$DEPS/archive.done" ]; then
    prepare archive libarchive.tar.xz
    (cd "$DEPS/archive-src" && CPPFLAGS="$CPPFLAGS -I$DEPS/archive-src/contrib/android/include" ./configure --host=aarch64-linux-android --prefix="$PREFIX" \
        --disable-shared --enable-static --disable-bsdtar --disable-bsdcpio --disable-bsdcat --disable-bsdunzip \
        --without-openssl --without-mbedtls --without-nettle --without-bz2lib --without-lzma \
        --without-zstd --without-lz4 --without-libb2 --without-xml2 --without-expat \
        --without-iconv --disable-acl --disable-xattr && make -j "${JOBS:-2}" && make install)
    printf 'done\n' > "$DEPS/archive.done"
fi
mkdir -p "$PREFIX/licenses"
cp "$DEPS/mbedtls-src/LICENSE" "$PREFIX/licenses/mbedtls.txt"
cp "$DEPS/curl-src/COPYING" "$PREFIX/licenses/curl.txt"
cp "$DEPS/archive-src/COPYING" "$PREFIX/licenses/libarchive.txt"
cp "$DEPS/zlib-src/LICENSE" "$PREFIX/licenses/zlib.txt"
