#!/bin/bash
set -u
BASE=$(mktemp -d /tmp/pdn-stress.XXXXXX) || exit 1
SRC="$BASE/source"
DST="$BASE/extracted"
ERRORS=0
echo "Test directory: $BASE"
mkdir -p "$SRC" "$DST"
fail() {
    echo "FAIL: $1"
    ERRORS=$((ERRORS + 1))
}
echo "[1/6] Creating 200 directories and 2000 files..."
for i in $(seq 1 200); do
    dir="$SRC/group_$i/subdir"
    mkdir -p "$dir" || fail "mkdir group_$i"
    for j in $(seq 1 10); do
        file="$dir/file_$j.txt"
        printf 'group=%s file=%s\n' "$i" "$j" > "$file" || fail "write $file"
    done
done
echo "[2/6] Creating executables and symlinks..."
mkdir -p "$SRC/bin" "$SRC/links"
for i in $(seq 1 50); do
    file="$SRC/bin/program_$i"
    printf '#!/bin/sh\necho program_%s\n' "$i" > "$file" || fail "write executable $i"
    chmod +x "$file" || fail "chmod $i"
    ln -s "../bin/program_$i" "$SRC/links/program_$i" || fail "symlink $i"
done
echo "[3/6] Creating tar.gz archive..."
tar -czf "$BASE/archive.tar.gz" -C "$SRC" . || fail "tar create"
echo "[4/6] Extracting archive..."
tar -xzf "$BASE/archive.tar.gz" -C "$DST" || fail "tar extract"
echo "[5/6] Comparing file contents and types..."
diff -qr "$SRC" "$DST" || fail "directory comparison"
for i in $(seq 1 50); do
    [ -x "$DST/bin/program_$i" ] || fail "executable permission $i"
    [ -L "$DST/links/program_$i" ] || fail "symlink $i"
done
echo "[6/6] Checking SHA-256 manifests..."
(
    cd "$SRC" || exit 1
    find . -type f -print0 | sort -z | xargs -0 -r sha256sum
) > "$BASE/source.sha256"
(
    cd "$DST" || exit 1
    find . -type f -print0 | sort -z | xargs -0 -r sha256sum
) > "$BASE/extracted.sha256"
diff -u "$BASE/source.sha256" "$BASE/extracted.sha256" || fail "SHA-256 mismatch"
echo
echo "=============================="
echo "PDN FILESYSTEM STRESS RESULT"
echo "=============================="
echo "Errors: $ERRORS"
echo "Source files: $(find "$SRC" -type f | wc -l)"
echo "Extracted files: $(find "$DST" -type f | wc -l)"
echo "Source symlinks: $(find "$SRC" -type l | wc -l)"
echo "Extracted symlinks: $(find "$DST" -type l | wc -l)"
echo "Test directory: $BASE"
if [ "$ERRORS" -eq 0 ]; then
    echo "RESULT: PASS"
else
    echo "RESULT: FAIL"
fi
exit "$((ERRORS > 0))"
