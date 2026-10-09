#!/system/bin/sh
set -eu
if [ "$#" -lt 4 ]; then
    printf 'Usage: sh benchmark-pdn-startup.sh OUTPUT_CSV REPETITIONS COMMAND [ARGS...]\n' >&2
    exit 2
fi
benchmark_output=$1
benchmark_runs=$2
shift 2
case "$benchmark_runs" in ''|*[!0-9]*|0) exit 2 ;; esac
benchmark_clock=/system/bin/date
printf 'iteration,elapsed_ns\n' > "$benchmark_output"
benchmark_iteration=0
while [ "$benchmark_iteration" -lt "$benchmark_runs" ]; do
    benchmark_start=$("$benchmark_clock" +%s%N)
    benchmark_result=$("$@")
    benchmark_end=$("$benchmark_clock" +%s%N)
    if [ "$benchmark_result" != PDN_BENCH_READY ]; then
        printf 'Unexpected guest output: %s\n' "$benchmark_result" >&2
        exit 1
    fi
    benchmark_iteration=$((benchmark_iteration + 1))
    benchmark_elapsed=$(/system/bin/expr "$benchmark_end" - "$benchmark_start")
    test "$benchmark_elapsed" -gt 0
    printf '%s,%s\n' "$benchmark_iteration" "$benchmark_elapsed" >> "$benchmark_output"
done
