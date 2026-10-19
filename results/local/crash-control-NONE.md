# Crash test: control-NONE

Windows 11 amd64, 8 hw threads, Java 21.0.12.1. commit mode LINK, conflict check NONE, 4 writer JVMs per round, 3000 ms per round, insert batches drawn from 64 shared bases.

| | |
|---|---:|
| rounds run | 5 |
| rounds failing verification | **5** |
| table versions committed | 783 |
| writers halted at an injected crash point | 0 |
| writers kill -9'd externally | 9 |
| writers exiting cleanly | 11 |
| writers failing with an error | 0 |
| acknowledged commits checked | 760 |
| acknowledged commits lost | **0** |
| orphan files + commit temp files removed by vacuum | 3 + 1 |

First failures:

```
round  1: v157   files=3   records=3288   FAIL [verify: Report[version=157, files=3, records=3288, sum=599821, duplicateKeys=200, badSlices=0, indexMismatches=0, partialBatches=0, error=null], sum 599821 != 400000, version 10 does not sum to the total, after vacuum: Report[version=157, files=3, records=3288, sum=599821, duplicateKeys=200, badSlices=0, indexMismatches=0, partialBatches=0, error=null]]
round  2: v324   files=10  records=5524   FAIL [verify: Report[version=324, files=10, records=5524, sum=599415, duplicateKeys=200, badSlices=0, indexMismatches=0, partialBatches=0, error=null], sum 599415 != 400000, version 157 does not sum to the total, after vacuum: Report[version=324, files=10, records=5524, sum=599415, duplicateKeys=200, badSlices=0, indexMismatches=0, partialBatches=0, error=null]]
round  3: v489   files=8   records=7208   FAIL [verify: Report[version=489, files=8, records=7208, sum=599150, duplicateKeys=200, badSlices=0, indexMismatches=0, partialBatches=0, error=null], sum 599150 != 400000, version 324 does not sum to the total, after vacuum: Report[version=489, files=8, records=7208, sum=599150, duplicateKeys=200, badSlices=0, indexMismatches=0, partialBatches=0, error=null]]
round  4: v621   files=10  records=7893   FAIL [verify: Report[version=621, files=10, records=7893, sum=598660, duplicateKeys=200, badSlices=0, indexMismatches=0, partialBatches=0, error=null], sum 598660 != 400000, version 489 does not sum to the total, after vacuum: Report[version=621, files=10, records=7893, sum=598660, duplicateKeys=200, badSlices=0, indexMismatches=0, partialBatches=0, error=null]]
round  5: v783   files=12  records=8676   FAIL [verify: Report[version=783, files=12, records=8676, sum=598383, duplicateKeys=200, badSlices=0, indexMismatches=0, partialBatches=0, error=null], sum 598383 != 400000, version 621 does not sum to the total, after vacuum: Report[version=783, files=12, records=8676, sum=598383, duplicateKeys=200, badSlices=0, indexMismatches=0, partialBatches=0, error=null]]
```
