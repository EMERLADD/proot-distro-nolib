# Pinned ARM64 rootfs catalogue (v0.4.0)

Verified on 2026-10-06. The executable contains these sizes and SHA256 values;
mirrors never choose the accepted digest at runtime. This project downloads
upstream rootfs archives without depending on Termux builds or runtime files.
Archives are not bundled into the executable or committed to this repository.

| Name | Artifact | Bytes | SHA256 |
| --- | --- | ---: | --- |
| alpine | alpine-minirootfs-3.24.2-aarch64.tar.gz | 4028030 | `9bf70a7f18ea44094cbb5f70c58f9af129c8214745743db0e68e5502cc2ce773` |
| ubuntu | ubuntu-base-24.04.5-base-arm64.tar.gz | 29936675 | `a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2` |
| debian | trixie/slim/oci/blobs/rootfs.tar.gz | 30200282 | `bbeda6b4abb749f743f4547ef5a28070a14de8caef211daa5afb78d5b5fa21ba` |
| arch | ArchLinuxARM-2026.08-aarch64-rootfs.tar.gz | 829367415 | `42a4eeaa038994ffd31fa173256ef2f0ef511358eeb41b9ea1f8626391b9b319` |

## Ubuntu

[Canonical Ubuntu Base release directory](https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/)
provides the ARM64 archive and [SHA256SUMS](https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/SHA256SUMS).
The downloaded archive matched that SHA256. TUNA and USTC mirror the same
`ubuntu-cdimage/ubuntu-base/releases/24.04/release/` path; package repositories
use `ubuntu-ports`, not the amd64 Ubuntu archive.

## Debian

[debuerreotype/docker-debian-artifacts](https://github.com/debuerreotype/docker-debian-artifacts)
provides the rootfs used for official Debian container images. This catalogue
pins the arm64v8 build at commit `cf1f4a45447842b45e9952e0f018ae734a7341c7`
(20261005, debuerreotype 0.17), with the `trixie/slim/oci/blobs/rootfs.tar.gz`
artifact. Its size and SHA256 matched the layer descriptor in the
[pinned OCI manifest](https://raw.githubusercontent.com/debuerreotype/docker-debian-artifacts/cf1f4a45447842b45e9952e0f018ae734a7341c7/trixie/slim/oci/blobs/image-manifest.json).
Only the rootfs gzip layer is needed; this is not a general OCI client.
The current downloader uses one official route to the immutable upstream
commit. No verified independent domestic mirror of this artifact is configured.

## Arch Linux ARM

The [official download documentation](https://archlinuxarm.org/about/downloads)
identifies the AArch64 platform and release signing key. The catalogue uses
`os/multi/ArchLinuxARM-2026.08-aarch64-rootfs.tar.gz`, not a mutable `latest`
filename. The fetched archive's detached signature was verified with the
[official build-system key](https://archlinuxarm.org/about/package-signing),
fingerprint `68B3537F39A313B3E574D06777193F152BDBE6A6`; its SHA256 is pinned above.

TUNA, USTC, NJU and the Florida US mirror returned the matching dated artifact
size over verified HTTPS. The automatic GeoIP host was excluded after TLS
hostname verification failed; TLS checks were not disabled. The full archive
includes kernel/firmware packages and expands to roughly 2 GiB.

## Alpine and updates

Alpine retains the [official minirootfs](https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/aarch64/)
and five-source configuration from v0.3.1. Source order is fixed, not a speed test.

When updating a pinned artifact, verify its upstream provenance and architecture,
record a new exact size and SHA256, inspect archive layout, and test installation,
login and package operations before changing the catalogue. Retired upstream
archives fail closed and require a catalogue update. Software/package licensing
inside downloaded distributions remains governed by those upstream projects.
