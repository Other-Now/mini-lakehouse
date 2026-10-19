#!/usr/bin/env bash
# Build, test, and run every benchmark + the crash test and its controls. Results land in results/<label>/.
# Usage: scripts/run-all.sh [label]      (Git Bash on Windows: source env.sh first)
set -euo pipefail
cd "$(dirname "$0")/.."
OUT="results/${1:-local}"
mkdir -p "$OUT"
mvn -q -B test
mvn -q -B dependency:build-classpath -Dmdep.outputFile=target/cp.txt
SEP=":"; case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=";";; esac
CP="target/classes${SEP}$(cat target/cp.txt)"
run() { echo "== $*"; java -Xmx3g -cp "$CP" "$@" --out "$OUT"; }

run lakehouse.bench.ContentionBench --writers 1,2,4,8,16 --hot 64,16,4,1 --seconds 5
run lakehouse.bench.IndexBench --records 1000000 --per-file 10000
run lakehouse.bench.CompactionBench --commits 500 --per-commit 200
run lakehouse.bench.CrashTest --rounds 40 --writers 4 --millis 4000 --label LINK-FULL
# controls: each one removes a single safeguard and must fail
run lakehouse.bench.CrashTest --rounds 5 --writers 4 --millis 3000 --mode DIRECT --point torn-commit --stop-on-failure true --label control-DIRECT-torn
run lakehouse.bench.CrashTest --rounds 5 --writers 4 --millis 3000 --mode RENAME --arm-probability 0 --label control-RENAME
run lakehouse.bench.CrashTest --rounds 5 --writers 4 --millis 3000 --conflict NONE --arm-probability 0 --label control-NONE
run lakehouse.bench.CrashTest --rounds 5 --writers 4 --millis 3000 --conflict FILE_GROUP --arm-probability 0 --insert-clock-ms 50 --label control-FILE_GROUP
run lakehouse.bench.CrashTest --rounds 5 --writers 4 --millis 3000 --conflict FULL --insert-clock-ms 50 --label LINK-FULL-shared-inserts
