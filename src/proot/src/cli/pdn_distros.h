#ifndef PDN_DISTROS_H
#define PDN_DISTROS_H

#include <stddef.h>
#include <stdint.h>
#include <strings.h>

struct mirror {
    const char *name;
    const char *base;
    const char *url;
    const char *packages;
};

struct distro {
    const char *name;
    const char *version;
    const char *sha256;
    size_t size;
    int64_t extracted_limit;
    long timeout;
    struct mirror mirrors[5];
};

#define ALPINE_FILE "/v3.24/releases/aarch64/alpine-minirootfs-3.24.2-aarch64.tar.gz"
#define UBUNTU_FILE "/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz"
#define DEBIAN_FILE "/debuerreotype/docker-debian-artifacts/cf1f4a45447842b45e9952e0f018ae734a7341c7/trixie/slim/oci/blobs/rootfs.tar.gz"
#define ARCH_FILE "/os/multi/ArchLinuxARM-2026.08-aarch64-rootfs.tar.gz"

#ifndef ALPINE_MIRRORS
#define ALPINE_MIRRORS \
    {"tuna", "https://mirrors.tuna.tsinghua.edu.cn/alpine", "https://mirrors.tuna.tsinghua.edu.cn/alpine" ALPINE_FILE, NULL}, \
    {"ustc", "https://mirrors.ustc.edu.cn/alpine", "https://mirrors.ustc.edu.cn/alpine" ALPINE_FILE, NULL}, \
    {"nju", "https://mirrors.nju.edu.cn/alpine", "https://mirrors.nju.edu.cn/alpine" ALPINE_FILE, NULL}, \
    {"official", "https://dl-cdn.alpinelinux.org/alpine", "https://dl-cdn.alpinelinux.org/alpine" ALPINE_FILE, NULL}, \
    {"dotsrc", "https://mirrors.dotsrc.org/alpine", "https://mirrors.dotsrc.org/alpine" ALPINE_FILE, NULL}
#endif
#ifndef UBUNTU_MIRRORS
#define UBUNTU_MIRRORS \
    {"tuna", "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage", "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage" UBUNTU_FILE, "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports"}, \
    {"ustc", "https://mirrors.ustc.edu.cn/ubuntu-cdimage", "https://mirrors.ustc.edu.cn/ubuntu-cdimage" UBUNTU_FILE, "https://mirrors.ustc.edu.cn/ubuntu-ports"}, \
    {"official", "https://cdimage.ubuntu.com", "https://cdimage.ubuntu.com" UBUNTU_FILE, "https://ports.ubuntu.com/ubuntu-ports"}
#endif
#ifndef DEBIAN_MIRRORS
#define DEBIAN_MIRRORS \
    {"official", "https://raw.githubusercontent.com/debuerreotype/docker-debian-artifacts", "https://raw.githubusercontent.com" DEBIAN_FILE, "https://deb.debian.org"}
#endif
#ifndef ARCH_MIRRORS
#define ARCH_MIRRORS \
    {"tuna", "https://mirrors.tuna.tsinghua.edu.cn/archlinuxarm", "https://mirrors.tuna.tsinghua.edu.cn/archlinuxarm" ARCH_FILE, NULL}, \
    {"ustc", "https://mirrors.ustc.edu.cn/archlinuxarm", "https://mirrors.ustc.edu.cn/archlinuxarm" ARCH_FILE, NULL}, \
    {"nju", "https://mirrors.nju.edu.cn/archlinuxarm", "https://mirrors.nju.edu.cn/archlinuxarm" ARCH_FILE, NULL}, \
    {"official", "https://fl.us.mirror.archlinuxarm.org", "https://fl.us.mirror.archlinuxarm.org" ARCH_FILE, NULL}
#endif
#ifndef ALPINE_SHA256
#define ALPINE_SHA256 "9bf70a7f18ea44094cbb5f70c58f9af129c8214745743db0e68e5502cc2ce773"
#define ALPINE_SIZE 4028030
#endif
#ifndef UBUNTU_SHA256
#define UBUNTU_SHA256 "a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2"
#define UBUNTU_SIZE 29936675
#endif
#ifndef DEBIAN_SHA256
#define DEBIAN_SHA256 "bbeda6b4abb749f743f4547ef5a28070a14de8caef211daa5afb78d5b5fa21ba"
#define DEBIAN_SIZE 30200282
#endif
#ifndef ARCH_SHA256
#define ARCH_SHA256 "42a4eeaa038994ffd31fa173256ef2f0ef511358eeb41b9ea1f8626391b9b319"
#define ARCH_SIZE 829367415
#endif

static const char *const distro_names[] = {"alpine", "ubuntu", "debian", "arch"};

static int find_distro(const char *name, struct distro *result)
{
    const struct distro distros[] = {
        {"alpine", "3.24.2", ALPINE_SHA256, ALPINE_SIZE, INT64_C(512) * 1024 * 1024, 90, {ALPINE_MIRRORS}},
        {"ubuntu", "24.04.5 LTS", UBUNTU_SHA256, UBUNTU_SIZE, INT64_C(1024) * 1024 * 1024, 600, {UBUNTU_MIRRORS}},
        {"debian", "13 trixie (20261005 slim)", DEBIAN_SHA256, DEBIAN_SIZE, INT64_C(1024) * 1024 * 1024, 600, {DEBIAN_MIRRORS}},
        {"arch", "Arch Linux ARM 2026.08", ARCH_SHA256, ARCH_SIZE, INT64_C(4096) * 1024 * 1024, 1800, {ARCH_MIRRORS}}
    };
    size_t i;
    for (i = 0; i < sizeof(distros) / sizeof(distros[0]); i++) {
        if (!strcasecmp(name, distros[i].name)) { *result = distros[i]; return 0; }
    }
    return -1;
}

#endif
