# Index lookup benchmark

Linux amd64, 4 hw threads, Java 21.0.12.1. 1,000,000 records in 100 files of 10,000. Batches are 50% existing / 50% absent keys. Median of 7 reps after 2 warm-up reps.

## ORDERED keys

Build: 1.8 s for 100 commits. Data 5.7 MB; bloom filters 1.20 MB total (fpp 0.01); hash index 1,000,000 entries, 33.6 MB in memory; cold open (checkpoint + tail replay) 77 ms.

| batch | method | median ms | µs/key | footers read | files key-scanned | keys scanned | false positives |
|---:|---|---:|---:|---:|---:|---:|---:|
| 1 | hash index | 0.001 | 0.67 | 0 | 0 | 0 | 0 |
| 1 | bloom (footers cached) | 1.775 | 1774.70 | 0 | 1 | 10,000 | 0 |
| 1 | bloom (footers read per lookup) | 2.055 | 2054.73 | 1 | 1 | 10,000 | 0 |
| 1 | full scan | 96.162 | 96162.07 | 0 | 100 | 1,000,000 | 0 |
| 100 | hash index | 0.009 | 0.09 | 0 | 0 | 0 | 0 |
| 100 | bloom (footers cached) | 33.224 | 332.24 | 0 | 39 | 390,000 | 2 |
| 100 | bloom (footers read per lookup) | 37.975 | 379.75 | 63 | 39 | 390,000 | 2 |
| 100 | full scan | 88.594 | 885.94 | 0 | 100 | 1,000,000 | 0 |
| 10,000 | hash index | 0.310 | 0.03 | 0 | 0 | 0 | 0 |
| 10,000 | bloom (footers cached) | 77.242 | 7.72 | 0 | 100 | 1,000,000 | 42 |
| 10,000 | bloom (footers read per lookup) | 86.036 | 8.60 | 100 | 100 | 1,000,000 | 42 |
| 10,000 | full scan | 84.231 | 8.42 | 0 | 100 | 1,000,000 | 0 |

## RANDOM keys

Build: 1.4 s for 100 commits. Data 9.7 MB; bloom filters 1.20 MB total (fpp 0.01); hash index 1,000,000 entries, 33.6 MB in memory; cold open (checkpoint + tail replay) 61 ms.

| batch | method | median ms | µs/key | footers read | files key-scanned | keys scanned | false positives |
|---:|---|---:|---:|---:|---:|---:|---:|
| 1 | hash index | 0.000 | 0.14 | 0 | 0 | 0 | 0 |
| 1 | bloom (footers cached) | 0.621 | 620.57 | 0 | 1 | 10,000 | 0 |
| 1 | bloom (footers read per lookup) | 10.498 | 10498.33 | 100 | 1 | 10,000 | 0 |
| 1 | full scan | 63.680 | 63680.26 | 0 | 100 | 1,000,000 | 0 |
| 100 | hash index | 0.001 | 0.01 | 0 | 0 | 0 | 0 |
| 100 | bloom (footers cached) | 47.938 | 479.38 | 0 | 82 | 820,000 | 124 |
| 100 | bloom (footers read per lookup) | 57.489 | 574.89 | 100 | 82 | 820,000 | 124 |
| 100 | full scan | 68.514 | 685.14 | 0 | 100 | 1,000,000 | 0 |
| 10,000 | hash index | 0.089 | 0.01 | 0 | 0 | 0 | 0 |
| 10,000 | bloom (footers cached) | 83.698 | 8.37 | 0 | 100 | 1,000,000 | 9978 |
| 10,000 | bloom (footers read per lookup) | 92.827 | 9.28 | 100 | 100 | 1,000,000 | 9978 |
| 10,000 | full scan | 68.064 | 6.81 | 0 | 100 | 1,000,000 | 0 |

"files key-scanned" counts files whose key column had to be read: the expensive step on object storage. Every method returned the identical key -> file group map (checked per batch).
