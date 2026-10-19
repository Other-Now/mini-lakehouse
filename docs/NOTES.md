# mini-lakehouse: interview study sheet

Read this in two sittings: §1–4 on day one (the model and the protocol), §5–8 on day two (the numbers,
the stories, the questions). Every number here comes from `results/`. Re-read those files before quoting.

---

## 1. The model in one paragraph

A table is **whatever its timeline says**. Data lives in immutable Parquet files. A *file group* is a stable
id for a set of keys. Each copy-on-write rewrite of a group produces a new *file slice* (a new file), and a
commit records "group G's current slice is now file F". Commits are files `timeline/<N>.commit` with dense
version numbers. A writer commits by **creating file N+1 if and only if it does not exist** (put-if-absent).
That single atomic, exclusive operation is the linearisation point for everything: atomic multi-file commits,
snapshot isolation, and optimistic concurrency. Data files written by a writer that never manages to commit
are invisible orphans, and vacuum deletes them.

```
timeline/00000000000000000041.commit   txn a1  UPSERT  read=40  add fg7 -> data/7_a1.parquet
timeline/00000000000000000042.commit   txn b2  UPSERT  read=40  add fg3 -> data/3_b2.parquet   (rebased once)
timeline/00000000000000000050.checkpoint   full state + record index at v50
_last_checkpoint                           "50"  (a hint; may be stale, never wrong)
data/<fg>_<txn>.parquet                    slices; footer = bloom + min/max key
index/<txn>.idx                            record-level index delta for the keys that commit inserted/moved
```

## 2. Hudi vocabulary mapping (say these words in the interview)

| here | Hudi | note |
|---|---|---|
| file group / file slice / base file | same | Copy-on-write only. No MOR log files. |
| timeline of `N.commit` | timeline of instants (`requested → inflight → completed`) | Here, dense version numbers à la Delta. Hudi uses instant timestamps and, for OCC, a **lock provider** (ZK / DynamoDB / HMS / FS lock) around conflict check + commit. |
| conflict check = write-set (file group) overlap | `SimpleConcurrentFileWritesConflictResolutionStrategy` | Same rule, plus the inserted-key check (§4). |
| BLOOM locator | `HoodieBloomIndex` | Range prune + bloom in footer + read candidate keys. "Footers cached" ≈ the bloom partition of Hudi's metadata table. |
| HASH locator | Record-level index (RLI), 0.14+ | Hudi stores RLI in the metadata table as HFiles hash-partitioned by key. Here it is an in-memory map rebuilt from deltas + checkpoint. |
| small-file **compaction** here | **clustering** (`replacecommit`) | Careful: in Hudi, "compaction" means merging MOR log files into base files. |
| vacuum | cleaner (retain N commits / versions) + orphan removal | Hudi tracks in-flight files with **markers** so it can roll back immediately. Here, a grace period does that job. |
| — | bucket index; NBCC (Hudi 1.0) | Not built. Worth knowing as the alternatives (§7). |

## 3. Commit protocol (`Table.publish`)

```
v = readVersion + 1
while !putIfAbsent("timeline/v.commit", ours):     # someone else got v
    other = read(v)
    if conflicts(ours, other): delete our files; abort      # OCC lost
    apply(other); v++                                        # "rebase": still valid on top of theirs
apply(ours); maybe checkpoint
```

* **Why put-if-absent and not rename.** POSIX `rename(2)` and Java `ATOMIC_MOVE` on Windows both
  **replace** an existing target. Check-then-rename has a window where two writers both "win" and the later
  rename silently overwrites the earlier commit. Measured: 299/300 racing slots had more than one winner (the
  exact count varies run to run). Local put-if-absent is therefore *write temp → `link(temp, target)`*:
  `link` fails with EEXIST. On S3 it is `PUT` with `If-None-Match: *` (412 if the key exists). Before Aug 2024
  S3 had no such primitive, which is why Delta needed a DynamoDB log store and Hudi a lock provider on S3.
* **Why temp + link and not just `CREATE_NEW`.** `CREATE_NEW` is exclusive, but the content is not atomic. A
  crash mid-write leaves a *torn* commit at version N. Every later writer then gets "exists" on N, reads
  garbage, and the table is bricked. The CRC32 on the commit makes that detectable, not recoverable. Shown by
  the DIRECT control.
* **Rebase is cheap** because a commit's content does not depend on its version number.
* **Checkpoint every N versions.** Cold open = read the hint → checkpoint → replay the tail. A corrupt or
  missing checkpoint falls back to replay from 0 (tested).

## 4. Concurrency control: what conflicts and why

Write set = file groups a commit rewrote or dropped. Two commits conflict if:

1. their **write sets intersect** (both rewrote group G: the second was built from a stale base and would erase
   the first's rows, a lost update); or
2. **both inserted the same new key.** File-group overlap misses this case. Both writers see key k as absent,
   and each puts it into its *own brand-new* file group. Write sets are disjoint, so both would commit, and k
   would now exist twice. The fix: each commit's index delta lists its inserted keys, and the conflict check
   merge-intersects them (both are sorted).

Isolation level: **snapshot isolation**. Reads are pinned to a snapshot. Write-write conflicts at file-group
granularity abort. Write skew is possible: two txns that each read A and B but write disjoint groups both
commit. That is SI's known anomaly, same as Hudi and Delta.

File-group granularity means **false conflicts**. Two txns touching *different keys* in the same group still
conflict. Coarser groups give fewer files but more false conflicts. That is the knob the contention benchmark
turns.

Back-of-envelope abort model: each txn touches g = 2 of H hot groups. With k other commits landing during my
txn, P(abort) ≈ 1 − (1 − g²/H)^k. For H = 64 and k ≈ 3 (4 writers), that gives ≈ 17%; measured 14.6%.

## 5. Indexing

Upsert asks for every incoming key: which file group holds it, if any?

* **SCAN.** Read every file's key column (Parquet projection). Cost ∝ table size, whatever the batch size.
* **BLOOM.** For each file: range prune with min/max (free, from slice metadata) → bloom prune (footer) →
  read the key column only of surviving files to confirm, because blooms have false positives. Great when keys
  are ordered (disjoint ranges), weak with random keys (every range spans everything, so you pay one footer per
  file). Absent keys (inserts) are where bloom shines: they're rejected without opening the data.
* **HASH (record-level).** key → file group in an open-addressing `long→long` map. One probe per key, zero
  files touched. Cost: memory (≈16 B × 2 slots per key here) and keeping it consistent. It is versioned *with*
  the commits: the delta is written before the commit and only visible through it, and checkpoints snapshot it.

## 6. Failure model and the crash test

What can kill a writer: anywhere. The injected points are after data write, after index write, mid-commit
write (torn), after commit (before ack), after checkpoint (before hint), and compaction after write. Plus
external `kill -9` (TerminateProcess) at random times.

Invariants checked after **every** round, with all writers dead, by a cold reader:
1. the table opens, and every referenced file reads and matches its slice metadata;
2. no duplicate keys; the record index exactly equals the data; no insert batch is partially visible;
3. **every** version committed that round sums to the initial total (transfers are sum-preserving, so atomic
   multi-group commits and no lost updates are both visible in one number);
4. every commit a writer *acknowledged* is in the timeline;
5. vacuum removes the debris (orphans, temp files) and the table still verifies.

Controls, each removing exactly one safeguard (they must fail, and do):

| control | what breaks |
|---|---|
| RENAME commit | acked commits silently vanish; sum drifts |
| DIRECT (CREATE_NEW, no temp) | one torn commit bricks the table |
| conflict NONE | lost updates; compaction-vs-update resurrects dropped groups → duplicate keys |
| conflict FILE_GROUP | concurrent inserts duplicate keys; then compaction throws forever |

## 7. Likely questions

* **"Why OCC and not locks?"** Lakehouse writers are long (seconds to minutes) and mostly non-overlapping
  (different partitions). OCC costs nothing when there is no overlap. Under heavy overlap it wastes work and has
  no fairness: see the p99 tail. Mitigations: retry backoff, finer file groups, partition-aware routing, a
  lock/queue for hot keys, or Hudi 1.0's **non-blocking concurrency control** (MOR: both writers append log files
  to the same group, and ordering by completion time resolves at read/compaction time, so neither aborts).
* **"Bloom vs record-level index?"** Bloom costs no extra state and is great for ordered keys and inserts, but is
  O(files) per lookup with random keys. RLI is O(1) per key but is state that must be maintained transactionally
  and sized to the key count. Bucket index (hash(key) mod N → fixed group) needs no lookup at all, but fixes the
  group count up front.
* **"How does a reader get snapshot isolation?"** It holds an immutable `Snapshot` (version → slices). Files are
  never modified in place. Vacuum keeps every slice that was current within the retention window.
* **"What if the checkpoint hint is stale?"** It's a hint. You replay more commits. It can't be wrong in a
  dangerous way, because checkpoints are immutable and checksummed.
* **"Why can vacuum not just delete files no commit references?"** Because an in-flight writer's files look
  exactly like orphans until it commits. Hence the grace period (Hudi uses markers instead).
* **"S3 specifics?"** Conditional PUT (`If-None-Match: *` → 412). 409 means a concurrent conditional write is in
  flight, so retry. LIST is the expensive call, so walk forward by probing N+1 instead of listing. PUTs are
  strongly consistent since Dec 2020.

## 8. Numbers to remember (local laptop; see results/local)

* OCC, 64 groups: abort 0 → 14.6% → 29.5% at 1 → 4 → 16 writers. At 1 hot group: 83%. Lost updates **0**
  in all 20 cells. Throughput peaks around 4 writers (~106–122 commits/s, fsync on). p99 grows ~100× while
  p50 stays flat (starvation).
* Index, 1M rows / 100 files: hash 0.03–2 µs/key, 0 files. Bloom single key 0.9–3.7 ms (one file confirmed).
  Full scan ~100–156 ms. At a 10k random batch bloom (116 ms) is slower than a scan (77 ms).
* Compaction 500 → 5 files: scan 8.9×, bloom lookup 20×.
* Crash test: 40 rounds, 7,316 versions, 76 injected crashes + 28 kill -9, **0** failed checks, **0 / 7,267**
  acked commits lost.

## 9. Stories (each one measured, each one in the README)

1. **rename ≠ put-if-absent**: `ATOMIC_MOVE` replaced an existing file on Windows. The race test gave
   299/300 multi-winner slots, and the crash control lost 24 acked commits.
2. **Thread-unsafe CodecFactory**: shared `ParquetReadOptions` turned valid files into "Corrupt file: Zero
   bytes read during decompression" under 4 threads. Lesson: when a "corrupt file" error appears only under
   concurrency, suspect shared decoder state before suspecting the file.
3. **Hadoop Configuration per file**: 3× on writes, 4.5× on reads, found with JFR (72 samples in 6 s meant the
   thread was mostly *not* on-CPU in my code).
4. **Insert-insert conflicts**: file-group overlap misses them, and the duplicates then poison compaction
   permanently.
5. **A benchmark asserting its own correctness caught a bug in the benchmark**: the index bench checked that
   every method returned the same map and the expected hit count. It failed (4,992 ≠ 5,000) because I sampled
   lookup keys with replacement, not because an index was wrong.

## 10. What is deliberately not built

MOR / log files, deletes, schema evolution, partitioning, a metadata table, multi-table transactions. S3 is
tested only in CI against MinIO (no AWS account or Docker on the dev laptop). The hash index lives entirely in
memory.
