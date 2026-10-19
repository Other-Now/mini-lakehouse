# Small-file compaction

Linux amd64, 4 hw threads, Java 21.0.12.1. 500 insert commits x 200 random keys = 100,000 records.

| | files | full scan ms | 100-key bloom lookup ms (footers read) |
|---|---:|---:|---:|
| before | 500 | 91.1 | 69.7 (500) |
| after  | 5 | 21.5 | 8.1 (5) |
| speed-up | | 4.2x | 8.6x |

Compaction: one commit replaced 500 file groups in 0.27 s. Vacuum then deleted 500 expired files (1.6 MB). Verifier after vacuum: 100,000 records, 0 duplicates, index exact.
