#!/bin/sh
set -eu
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$project_dir"
version=$(sed -n 's/^#define PDN_VERSION "\([^"]*\)"/\1/p' src/proot/src/cli/proot.h)
case "$version" in ''|*[!0-9.]*) echo 'Invalid project version' >&2; exit 1;; esac
tag=v$version
case "${RELEASE_REF:-refs/heads/main}" in
    refs/heads/main) ;;
    "refs/tags/$tag") ;;
    *) echo 'Release ref does not match project version' >&2; exit 1;;
esac
repository=${GITHUB_REPOSITORY:-EMERLADD/proot-distro-nolib}
if gh release view "$tag" --repo "$repository" >/dev/null 2>&1; then
    echo "$tag already published; increment the patch version for the next release."
    exit 0
fi
package=proot-distro-nolib-$tag-android-arm64.tar.gz
aar=pdn-engine-$version.aar
lite=pdn-engine-lite-$version.aar
for asset in "$package" "$aar" "$lite" pdn proot-loader libpdn.so libproot-loader.so SHA256SUMS; do
    test -s "build/packages/$asset"
done
(cd build/packages && sha256sum -c SHA256SUMS)
notes=build/release-notes.md
awk -v heading="## $tag —" '
    index($0, heading) == 1 { active = 1; next }
    active && /^## / { exit }
    active { print }
' CHANGELOG.md > "$notes"
test -s "$notes"
cat >> "$notes" <<EOF

### 下载文件

- \`$aar\`：PDN Android 引擎 AAR，仅含 PDN、loader 与 PTY JNI（非插桩 Debug 构建）；Kotlin 标准库由宿主提供。
- \`$lite\`：兼容旧 lite 下载名称的别名，与上述 AAR 字节相同，保留全部 PDN 终端功能；Kotlin 标准库由宿主提供。
- \`libpdn.so\`、\`libproot-loader.so\`：放入 \`jniLibs/arm64-v8a/\` 的 ELF 可执行程序。
- \`pdn\`、\`proot-loader\`：同版本的原始 ARM64 ELF。
- \`$package\`：完整原生程序、对应源码、依赖源码与许可证。
- \`SHA256SUMS\`：附件的 SHA256 校验值。

Linux CI 负责交叉编译和打包检查；实机验证范围见上方记录。
EOF
commit=${RELEASE_COMMIT:-$(git rev-parse HEAD)}
gh release create "$tag" --repo "$repository" --target "$commit" \
    --title "$tag — proot-distro-nolib ARM64" --prerelease --draft \
    --notes-file "$notes" \
    "build/packages/$package" "build/packages/$aar" "build/packages/$lite" \
    build/packages/pdn build/packages/proot-loader \
    build/packages/libpdn.so build/packages/libproot-loader.so \
    build/packages/SHA256SUMS
