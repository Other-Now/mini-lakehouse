# mini-lakehouse

A small transactional table format in Java, built the way Apache Hudi's copy-on-write tables work. It has
Parquet base files organised into **file groups**, a **timeline** of commits made atomic by a single
put-if-absent, **optimistic concurrency control** for competing writers with file-group conflict detection and
snapshot-isolation reads, and two **upsert indexes** (bloom filter in the Parquet footer, and a record-level
hash index). Maintenance is small-file compaction plus vacuum. A **multi-process crash test** kill -9s writers
at random points and checks that the table is always readable and consistent.

~2,600 lines of Java 21 plus ~550 lines of tests. Its dependencies are parquet-java, plus the AWS SDK for the S3 backend.

```
timeline/00000000000000000042.commit      one per version; created with putIfAbsent = the commit point
timeline/00000000000000000050.checkpoint  full state + record index every N versions
data/<fileGroup>_<txn>.parquet            file slices (footer: bloom filter, min/max key)
index/<txn>.idx                           record-level index delta, visible only via its commit
```

## How it works (short)

* **Atomic commit.** A writer creates `timeline/<v+1>.commit` **only if it does not exist**. Locally that is
  *write temp → hard-link to target* (`link` fails if the target exists). On S3 it is `PUT` with
  `If-None-Match: *` (412 if it exists). It is deliberately **not** *rename*: `rename(2)` and Java's
  `ATOMIC_MOVE` on Windows silently replace an existing file. Commits carry a CRC32, so a torn file is detected.
* **OCC.** If version v is taken, read that commit. If it rewrote any of our file groups, or inserted any key we
  are inserting, abort. Otherwise our commit is still valid on top of it, so try v+1 ("rebase"). Readers pin an
  immutable snapshot (version → slices) and never block.
* **Upsert.** Locate each key's file group via the index → rewrite each touched group's slice with the merged
  rows (copy-on-write) → put new keys into new file groups → write an index delta → commit.
* **Indexes.** `HASH`: key → file group, an open-addressing `long→long` map rebuilt from deltas + checkpoint.
  `BLOOM`: min/max range prune → footer bloom prune → read candidate files' key columns to confirm. `SCAN`:
  read every key column.
* **Maintenance.** Compaction (Hudi calls this *clustering*) rewrites small groups into big ones in one OCC
  commit. Vacuum deletes orphans (never named by a commit, older than a grace period) and slices that expired
  beyond the retention window.

The details, the Hudi mapping and the interview Q&A are in [docs/NOTES.md](docs/NOTES.md).

## Results

All numbers below are from a 4-core / 8-thread Windows 11 laptop, JDK 21, local NTFS
([results/local/](results/local/)). CI re-runs everything on Linux and posts the tables to the job summary.

### Concurrency control under contention ([contention.md](results/local/contention.md))

Writer threads with independent table handles run read-modify-write transactions, each incrementing 2 random
keys from the first *H* of 64 file groups (1,000 rows each). Aborted transactions retry from a fresh snapshot.
fsync is on.

| hot groups | writers | commits/s | abort rate | txn p50 | txn p99 | lost updates |
|---:|---:|---:|---:|---:|---:|---:|
| 64 | 1 | 76.7 | 0.0% | 11.8 ms | 25 ms | 0 |
| 64 | 4 | 106.0 | 14.6% | 22.0 ms | 275 ms | 0 |
| 64 | 16 | 47.8 | 29.5% | 21.1 ms | 2,525 ms | 0 |
| 16 | 4 | 108.2 | 38.0% | 19.7 ms | 207 ms | 0 |
| 4 | 4 | 84.0 | 63.3% | 15.1 ms | 369 ms | 0 |
| 1 | 4 | 121.6 | 68.8% | 8.0 ms | 438 ms | 0 |
| 1 | 16 | 55.2 | 83.2% | 12.9 ms | 2,531 ms | 0 |

* The abort rate rises with writers and with fewer hot groups, roughly as a birthday-collision model predicts
  (≈17% expected at 64 groups / 4 writers; 14.6% measured).
* **0 lost updates in all 20 cells.** The final sum always equals the initial sum plus the committed increments.
* Throughput peaks at about 4 writers on this machine. Beyond that, p50 stays flat while **p99 grows ~100×**:
  OCC has no fairness, and an unlucky transaction loses race after race (p99 needed 49 attempts at 1 hot group,
  16 writers).

### Index: record lookup cost vs full scan ([index.md](results/local/index.md))

1,000,000 records in 100 Parquet files. Each lookup batch is half existing keys and half absent keys (the
insert case). Every method returned the identical key → file-group map.

| keys | batch | hash index | bloom (footers cached) | bloom (footers read) | full scan |
|---|---:|---:|---:|---:|---:|
| ordered | 1 | **0.002 ms** | 3.7 ms (1 file read) | 12.0 ms | 156 ms (100 files) |
| random | 1 | **<0.001 ms** | 0.9 ms (1 file read) | 24.4 ms (100 footers) | 98 ms |
| random | 100 | **0.003 ms** | 65 ms (82 files) | 125 ms | 131 ms |
| random | 10,000 | **0.28 ms** | 116 ms (100 files) | 128 ms | **77 ms** |

* The record-level hash index costs 0.03–2 µs per key and touches **zero files**. Its price is 33.6 MB of memory
  for 1M keys, and cold open (checkpoint + tail replay) takes ~100 ms.
* For a single key, bloom beats a full scan by 42–109× with cached footers (4–13× when it must read footers),
  but it **degrades to worse than a scan** once the batch
  is big enough that every file has a candidate (10,000 random keys: 116 ms vs 77 ms). Each probe also costs a
  footer read when footers aren't cached. With random keys, min/max pruning does nothing (24 ms: 100 footers);
  with ordered keys it prunes to 1 footer (12 ms).
* The false-positive count matches theory: 9,978 at fpp = 0.01 for ~10⁶ (key, file) probes.

### Small-file compaction ([compaction.md](results/local/compaction.md))

500 insert commits × 200 random keys produce 500 files. One compaction commit turns them into 5.

| | files | full scan | 100-key bloom lookup |
|---|---:|---:|---:|
| before | 500 | 316 ms | 307 ms (500 footers) |
| after | 5 | 35 ms | 15 ms (5 footers) |
| | | **8.9×** | **20×** |

### Crash test ([crash-LINK-FULL.md](results/local/crash-LINK-FULL.md))

Each round runs 4 writer **JVMs** for 4 s against one table, with a mix of 80% transfers between keys
(sum-preserving, two file groups per commit), 15% insert batches and 5% compactions. Writers are halted at an
injected crash point (`Runtime.halt` after data write, after index write, *mid-commit-file write*, after commit,
after checkpoint, mid-compaction) or `kill -9`'d externally. After every round a cold reader checks the table
opens, files match metadata, there are no duplicate keys, the index is exact, there are no partial batches,
**every version committed in the round sums to the initial total**, and every acknowledged commit is present.
Then vacuum runs and the table is checked again.

| | |
|---|---:|
| rounds / table versions committed | 40 / 7,316 |
| writers halted at injected crash points | 76 |
| writers kill -9'd at random times | 28 |
| rounds failing any check | **0** |
| acknowledged commits lost | **0 of 7,267** |
| crash debris removed by vacuum (orphans + commit temp files) | 59 + 25 |

**Controls.** Each one removes a single safeguard, and each one fails:

| control | result |
|---|---|
| commit via check-then-**rename** | 24 acknowledged commits silently lost in round 1; sum drifted; the phantom state then referenced deleted files and the table became unreadable ([report](results/local/crash-control-RENAME.md)) |
| commit via **CREATE_NEW** (no temp file) | one torn commit at v184 → table unreadable, every writer stuck ([report](results/local/crash-control-DIRECT-torn.md)) |
| **no** conflict detection | sum off by ~+200,000 and 200 duplicate keys: updates raced compaction and resurrected dropped file groups ([report](results/local/crash-control-NONE.md)) |
| **file-group-only** conflict detection | 187 → 412 duplicate keys from concurrent inserts of the same new key; compaction then throws on every attempt ([report](results/local/crash-control-FILE_GROUP.md)). The full check on the same workload: 0 failures ([report](results/local/crash-LINK-FULL-shared-inserts.md)) |

### Linux CI ([results/ci/](results/ci/), GitHub Actions run 39211481978, 4 vCPU)

The same suite on a Linux runner. File creation is far cheaper on Linux than on NTFS, so throughput is 3–6×
higher. The shape of every result is the same.

| | Linux CI |
|---|---|
| OCC, 64 hot groups, 1 / 4 / 16 writers | 228 / 595 / 303 commits/s; abort 0 / 15.2 / 46.7%; **0 lost updates** in all 20 cells |
| OCC, 1 hot group, 16 writers | 89.1% abort; p99 needs 53 attempts |
| Lookup, 1 key (random keys) | hash 0.14 µs · bloom 0.6 ms (cached) / 10.5 ms (100 footers) · scan 64 ms |
| Lookup, 10,000 random keys | hash 0.09 ms · bloom 84 ms · scan **68 ms** (bloom still loses at large batches) |
| Compaction 500 → 5 files | scan 4.2×, bloom lookup 8.6× |
| Crash test | 40 rounds, **19,493 versions**, 75 injected crashes + 28 kill -9, 0 failing rounds, **0 of 19,447** acked commits lost |
| Controls | RENAME: 80 acked commits lost · DIRECT: unreadable after a torn commit · NONE: 987 → 1,290 duplicate keys · FILE_GROUP: 604 duplicate keys · FULL on the same workload: 0 failures |
| S3 (MinIO built from source) | conditional-PUT race exclusive (0 double winners); transfers on S3: 254 commits, 135 aborts, 62 concurrent snapshot scans, 0 inconsistent, 0 drift |

## Things found along the way

1. **Rename is not put-if-absent.** On Windows, `Files.move(…, ATOMIC_MOVE)` replaced an existing commit file
   without error, while `Files.createLink` threw `FileAlreadyExistsException`. In a race test, 299 of 300
   check-then-rename slots had more than one writer told "you won".
2. **A shared `ParquetReadOptions` corrupts concurrent reads.** It owns parquet-java's `CodecFactory`, which
   caches decompressors and is not thread-safe. Four reader threads made valid files fail with
   *"Corrupt file: Zero bytes read during decompression"*. The fix is one instance per thread.
3. **parquet-java builds a new Hadoop `Configuration` per file** and parses Hadoop's XML defaults each time.
   Passing one empty `Configuration` cut a 1,000-row write from 10.1 to 3.3 ms and a read from 7.3 to 1.6 ms.
4. **File-group conflict detection alone is not enough.** Two writers inserting the same new key each create a
   *new* file group, so their write sets never overlap. The check also has to intersect inserted keys. The
   resulting duplicates are not a cosmetic problem: they make every later compaction fail.

## Run it

```bash
. ./env.sh               # this box: portable JDK 21 + Maven (edit paths for yours)
mvn test                 # 20 unit tests (+3 S3 tests when LAKEHOUSE_S3_ENDPOINT is set)
scripts/run-all.sh local # all benchmarks, the crash test and the controls -> results/local/
```

## What is not here

* **S3 is tested only in CI.** `S3Storage` uses conditional `PUT` and is exercised against MinIO (built from
  source; Docker Hub no longer serves minio/minio) in GitHub Actions. There is no AWS account or Docker on the dev
  machine. It has never run against real AWS S3. All benchmark numbers are local disk.
* Copy-on-write only: no merge-on-read log files, no deletes, no partitioning, no schema evolution, no metadata
  table. The record-level index lives entirely in memory (Hudi's is HFile-backed and partitioned).
* Writers in the contention benchmark are threads with independent handles. The crash test uses separate
  processes.
