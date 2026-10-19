# Small-file compaction

Windows 11 amd64, 8 hw threads, Java 21.0.12.1. 500 insert commits x 200 random keys = 100,000 records.

| | files | full scan ms | 100-key bloom lookup ms (footers read) |
|---|---:|---:|---:|
| before | 500 | 316.1 | 307.4 (500) |
| after  | 5 | 35.4 | 15.3 (5) |
| speed-up | | 8.9x | 20.0x |

Compaction: one commit replaced 500 file groups in 0.77 s. Vacuum then deleted 500 expired files (1.6 MB). Verifier after vacuum: 100,000 records, 0 duplicates, index exact.
