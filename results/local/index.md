# Index lookup benchmark

Windows 11 amd64, 8 hw threads, Java 21.0.12.1. 1,000,000 records in 100 files of 10,000. Batches are 50% existing / 50% absent keys. Median of 7 reps after 2 warm-up reps.

## ORDERED keys

Build: 3.2 s for 100 commits. Data 5.7 MB; bloom filters 1.20 MB total (fpp 0.01); hash index 1,000,000 entries, 33.6 MB in memory; cold open (checkpoint + tail replay) 115 ms.

| batch | method | median ms | µs/key | footers read | files key-scanned | keys scanned | false positives |
|---:|---|---:|---:|---:|---:|---:|---:|
| 1 | hash index | 0.002 | 1.70 | 0 | 0 | 0 | 0 |
| 1 | bloom (footers cached) | 3.650 | 3650.20 | 0 | 1 | 10,000 | 0 |
| 1 | bloom (footers read per lookup) | 12.002 | 12001.80 | 1 | 1 | 10,000 | 0 |
| 1 | full scan | 156.055 | 156054.60 | 0 | 100 | 1,000,000 | 0 |
| 100 | hash index | 0.005 | 0.05 | 0 | 0 | 0 | 0 |
| 100 | bloom (footers cached) | 66.796 | 667.96 | 0 | 39 | 390,000 | 2 |
| 100 | bloom (footers read per lookup) | 79.452 | 794.52 | 63 | 39 | 390,000 | 2 |
| 100 | full scan | 123.098 | 1230.98 | 0 | 100 | 1,000,000 | 0 |
| 10,000 | hash index | 2.550 | 0.26 | 0 | 0 | 0 | 0 |
| 10,000 | bloom (footers cached) | 121.367 | 12.14 | 0 | 100 | 1,000,000 | 42 |
| 10,000 | bloom (footers read per lookup) | 130.295 | 13.03 | 100 | 100 | 1,000,000 | 42 |
| 10,000 | full scan | 108.553 | 10.86 | 0 | 100 | 1,000,000 | 0 |

## RANDOM keys

Build: 3.0 s for 100 commits. Data 9.7 MB; bloom filters 1.20 MB total (fpp 0.01); hash index 1,000,000 entries, 33.6 MB in memory; cold open (checkpoint + tail replay) 100 ms.

| batch | method | median ms | µs/key | footers read | files key-scanned | keys scanned | false positives |
|---:|---|---:|---:|---:|---:|---:|---:|
| 1 | hash index | 0.000 | 0.10 | 0 | 0 | 0 | 0 |
| 1 | bloom (footers cached) | 0.875 | 875.00 | 0 | 1 | 10,000 | 0 |
| 1 | bloom (footers read per lookup) | 24.430 | 24430.10 | 100 | 1 | 10,000 | 0 |
| 1 | full scan | 97.878 | 97878.10 | 0 | 100 | 1,000,000 | 0 |
| 100 | hash index | 0.003 | 0.03 | 0 | 0 | 0 | 0 |
| 100 | bloom (footers cached) | 64.874 | 648.74 | 0 | 82 | 820,000 | 124 |
| 100 | bloom (footers read per lookup) | 124.568 | 1245.68 | 100 | 82 | 820,000 | 124 |
| 100 | full scan | 130.940 | 1309.40 | 0 | 100 | 1,000,000 | 0 |
| 10,000 | hash index | 0.277 | 0.03 | 0 | 0 | 0 | 0 |
| 10,000 | bloom (footers cached) | 116.380 | 11.64 | 0 | 100 | 1,000,000 | 9978 |
| 10,000 | bloom (footers read per lookup) | 128.395 | 12.84 | 100 | 100 | 1,000,000 | 9978 |
| 10,000 | full scan | 76.814 | 7.68 | 0 | 100 | 1,000,000 | 0 |

"files key-scanned" counts files whose key column had to be read: the expensive step on object storage. Every method returned the identical key -> file group map (checked per batch).
