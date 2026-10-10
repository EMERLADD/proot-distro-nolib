#!/system/bin/sh
set -eu
if [ "$#" -ne 5 ]; then
    echo 'Usage: sh test-pdn-seccomp.sh PDN LOADER ALPINE_ARCHIVE PROBE NEW_DIRECTORY' >&2
    exit 2
fi
pdn=$1
loader=$2
archive=$3
probe=$4
base=$5
[ ! -e "$base" ] || exit 2
mkdir -p "$base/linux" "$base/tmp"
export PDN_ROOTFS_DIR="$base/linux" PROOT_TMP_DIR="$base/tmp" PROOT_LOADER="$loader"
unset PROOT_NO_SECCOMP
export PATH=/system/bin
check() {
    name=$1
    shift
    if "$@" > "$base/$name.log" 2>&1; then
        echo "ok - $name"
    else
        echo "not ok - $name"
        cat "$base/$name.log"
        exit 1
    fi
}
auto_groups() {
    "$pdn" --verbose=1 -0 "$probe" groups > "$base/auto.log" 2>&1 &&
    grep -q 'ptrace acceleration.*enabled' "$base/auto.log" &&
    grep -q 'guest groups isolated' "$base/auto.log"
}
fallback() {
    kind=$1
    "$probe" "$kind" "$pdn" --verbose=1 -0 "$probe" groups > "$base/$kind.log" 2>&1 &&
    ! grep -q 'ptrace acceleration.*enabled' "$base/$kind.log" &&
    grep -q 'guest groups isolated' "$base/$kind.log"
}
disabled() {
    value=$1
    PROOT_NO_SECCOMP="$value" "$pdn" --verbose=1 -0 "$probe" groups > "$base/disabled-$value.log" 2>&1 &&
    ! grep -q 'ptrace acceleration.*enabled' "$base/disabled-$value.log" &&
    grep -q 'guest groups isolated' "$base/disabled-$value.log"
}
check auto_groups auto_groups
check inherited_filter fallback exec-inherited-filter
check query_denied fallback exec-denied-seccomp-query
check disabled_1 disabled 1
check disabled_0 disabled 0
check disabled_empty disabled ''
check sigsys "$pdn" -0 "$probe" sigsys
check install "$pdn" install alpine --archive "$archive"
root=$base/linux/alpine
check identity "$pdn" exec --rootfs "$root" -- /bin/sh -c 'set -e; test "$(id -u)" = 0; test "$(uname -r)" = 6.17.0-pr'
check files "$pdn" exec --rootfs "$root" -- /bin/sh -c 'set -e; echo seccomp > /tmp/seccomp-test; test "$(cat /tmp/seccomp-test)" = seccomp; rm /tmp/seccomp-test; test ! -e /tmp/seccomp-test'
printf 'mode,task,repetition,milliseconds\n' > "$base/timings.csv"
measure() {
    mode=$1
    task=$2
    repeat=$3
    command=$4
    if [ "$mode" = auto ]; then unset PROOT_NO_SECCOMP; else export PROOT_NO_SECCOMP=1; fi
    start=$(date +%s%N)
    "$pdn" exec --rootfs "$root" -- /bin/sh -c "$command" > "$base/last-task.log" 2>&1
    finish=$(date +%s%N)
    elapsed=$(/system/bin/expr "$finish" - "$start")
    millis=$(/system/bin/expr "$elapsed" / 1000000)
    printf '%s,%s,%s,%s\n' "$mode" "$task" "$repeat" "$millis" >> "$base/timings.csv"
}
unset PROOT_NO_SECCOMP
check prepare "$pdn" exec --rootfs "$root" -- /bin/sh -c 'set -e; mkdir -p /tmp/bench; dd if=/dev/zero of=/tmp/bench/payload bs=1M count=64; sha256sum /tmp/bench/payload > /tmp/bench/sha'
for repetition in 1 2 3; do
    for mode in auto disabled; do
        measure "$mode" startup "$repetition" 'true'
        measure "$mode" tar_gzip "$repetition" 'set -e; cd /tmp/bench; tar czf archive.tgz payload; rm payload; tar xzf archive.tgz; sha256sum -c sha; rm archive.tgz'
        measure "$mode" files_1000 "$repetition" 'set -e; mkdir /tmp/bench/files; i=0; while [ "$i" -lt 1000 ]; do echo "$i" > /tmp/bench/files/file-$i; i=$((i+1)); done; test "$(cat /tmp/bench/files/file-999)" = 999; test "$(ls /tmp/bench/files | wc -l)" = 1000; rm -r /tmp/bench/files'
    done
    echo "ok - workload repetition $repetition"
done
cat "$base/timings.csv"
