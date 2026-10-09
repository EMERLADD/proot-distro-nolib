#!/system/bin/sh
set -eu
if [ "$#" -ne 4 ]; then
    printf 'Usage: sh test-pdn-paths.sh PDN LOADER ALPINE_ARCHIVE NEW_TEST_DIRECTORY\n' >&2
    exit 2
fi
pdn_path=$1
loader_path=$2
archive_path=$3
case_dir=$4
if [ -e "$case_dir" ]; then
    printf 'Test directory must not already exist: %s\n' "$case_dir" >&2
    exit 2
fi
mkdir -p "$case_dir/linux" "$case_dir/tmp" "$case_dir/host/usr" "$case_dir/parent/inner" "$case_dir/parent/innerish" "$case_dir/child" "$case_dir/linux/other/usr"
export PDN_ROOTFS_DIR="$case_dir/linux" PROOT_TMP_DIR="$case_dir/tmp" PROOT_LOADER="$loader_path" PROOT_NO_SECCOMP=1
"$pdn_path" install alpine --archive "$archive_path" > "$case_dir/install.log" 2>&1
root_path=$case_dir/linux/alpine
printf 'host\n' > "$case_dir/host/usr/pdn-path-marker"
printf 'guest\n' > "$root_path/usr/pdn-path-marker"
printf 'parent\n' > "$case_dir/parent/inner/marker"
printf 'adjacent\n' > "$case_dir/parent/innerish/marker"
printf 'child\n' > "$case_dir/child/marker"
printf 'other\n' > "$case_dir/linux/other/target"
ln -s /usr/pdn-path-marker "$root_path/usr/pdn-absolute"
ln -s pdn-path-marker "$root_path/usr/pdn-relative"
ln -s /pdn-other/target "$root_path/usr/pdn-cross"
ln -s "$case_dir/linux/other/target" "$root_path/usr/pdn-host-cross"
ln -s /usr/pdn-created-target "$root_path/usr/pdn-dangling"
ln -s pdn-loop-b "$root_path/usr/pdn-loop-a"
ln -s pdn-loop-a "$root_path/usr/pdn-loop-b"
case_count=0
failed_count=0
run_guest() {
    guest_script="set -e; /bin/busybox cat /usr/pdn-path-marker >/dev/null; $1"
    shift
    "$pdn_path" exec --rootfs "$root_path" "$@" -- /bin/sh -c "$guest_script"
}
check() {
    case_count=$((case_count + 1))
    case_name=$1
    shift
    if "$@" > "$case_dir/$case_name.log" 2>&1; then
        printf 'ok %s - %s\n' "$case_count" "$case_name"
    else
        printf 'not ok %s - %s\n' "$case_count" "$case_name"
        cat "$case_dir/$case_name.log"
        failed_count=$((failed_count + 1))
    fi
}
usr_mapping() {
    run_guest 'set -e; test "$(cat /usr/pdn-path-marker)" = guest; test "$(cat /pdn-host/usr/pdn-path-marker)" = host; echo guest-write > /usr/pdn-path-marker' --bind "$case_dir/host:/pdn-host" &&
    test "$(cat "$root_path/usr/pdn-path-marker")" = guest-write &&
    test "$(cat "$case_dir/host/usr/pdn-path-marker")" = host
}
nested() {
    order=$1
    if [ "$order" = parent ]; then
        run_guest 'set -e; test "$(cat /pdn-path/inner/marker)" = child; echo child-write > /pdn-path/inner/result' --bind "$case_dir/parent:/pdn-path" --bind "$case_dir/child:/pdn-path/inner"
    else
        run_guest 'set -e; test "$(cat /pdn-path/inner/marker)" = child; echo child-write > /pdn-path/inner/result' --bind "$case_dir/child:/pdn-path/inner" --bind "$case_dir/parent:/pdn-path"
    fi && test "$(cat "$case_dir/child/result")" = child-write && test ! -e "$case_dir/parent/inner/result"
}
boundary() {
    run_guest 'set -e; test "$(cat /pdn-path/innerish/marker)" = adjacent; echo adjacent-write > /pdn-path/innerish/result' --bind "$case_dir/parent:/pdn-path" --bind "$case_dir/child:/pdn-path/inner" &&
    test "$(cat "$case_dir/parent/innerish/result")" = adjacent-write && test ! -e "$case_dir/child/ish/result"
}
cross_bound() {
    run_guest 'set -e; test "$(cat /usr/pdn-cross)" = other; echo other-write > /usr/pdn-cross' --bind "$case_dir/linux/other:/pdn-other" &&
    test "$(cat "$case_dir/linux/other/target")" = other-write && test ! -e "$root_path/pdn-other/target"
}
missing_leaf() {
    run_guest 'set -e; test ! -e /usr/pdn-new-leaf; echo created > /usr/pdn-new-leaf' &&
    test "$(cat "$root_path/usr/pdn-new-leaf")" = created && test ! -e "$case_dir/host/usr/pdn-new-leaf"
}
missing_parent() {
    run_guest 'if echo bad > /usr/pdn-no-parent/leaf; then exit 1; fi; test ! -e /usr/pdn-no-parent/leaf' &&
    test ! -e "$root_path/usr/pdn-no-parent" && test ! -e "$case_dir/host/usr/pdn-no-parent"
}
dangling_creation() {
    run_guest 'set -e; if cat /usr/pdn-dangling; then exit 1; fi; echo linked-write > /usr/pdn-dangling; test "$(readlink /usr/pdn-dangling)" = /usr/pdn-created-target' &&
    test "$(cat "$root_path/usr/pdn-created-target")" = linked-write && test ! -e "$case_dir/host/usr/pdn-created-target"
}
printf 'TAP version 13\n'
check guest_usr_mapping usr_mapping
check nested_bind_parent_first nested parent
check nested_bind_child_first nested child
check bind_component_boundary boundary
check symlink_absolute run_guest 'set -e; test "$(cat /usr/pdn-absolute)" = guest-write; test "$(readlink /usr/pdn-absolute)" = /usr/pdn-path-marker'
check symlink_relative run_guest 'set -e; test "$(cat /usr/pdn-relative)" = guest-write; test "$(readlink /usr/pdn-relative)" = pdn-path-marker'
check cross_rootfs_bound_symlink cross_bound
check cross_rootfs_unbound_symlink run_guest 'if cat /usr/pdn-cross; then exit 1; fi; test ! -e /pdn-other/target'
check cross_rootfs_host_absolute_symlink run_guest 'if cat /usr/pdn-host-cross; then exit 1; fi; test -L /usr/pdn-host-cross'
check dangling_symlink_creation dangling_creation
check symlink_loop run_guest 'if cat /usr/pdn-loop-a; then exit 1; fi; test -L /usr/pdn-loop-a'
check missing_read run_guest 'if cat /usr/pdn-missing; then exit 1; fi; test ! -e /usr/pdn-missing'
check missing_leaf_creation missing_leaf
check missing_parent_creation missing_parent
printf '1..%s\n' "$case_count"
printf 'PATH_CHECKS_RESULT total=%s failed=%s\n' "$case_count" "$failed_count"
test "$failed_count" -eq 0
