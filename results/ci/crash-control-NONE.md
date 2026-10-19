# Crash test: control-NONE

Linux amd64, 4 hw threads, Java 21.0.12.1. commit mode LINK, conflict check NONE, 4 writer JVMs per round, 3000 ms per round, insert batches drawn from 64 shared bases.

| | |
|---|---:|
| rounds run | 5 |
| rounds failing verification | **5** |
| table versions committed | 2950 |
| writers halted at an injected crash point | 0 |
| writers kill -9'd externally | 9 |
| writers exiting cleanly | 11 |
| writers failing with an error | 0 |
| acknowledged commits checked | 2915 |
| acknowledged commits lost | **0** |
| orphan files + commit temp files removed by vacuum | 9 + 2 |

First failures:

```
round  1: v577   files=15  records=7864   FAIL [verify: Report[version=577, files=15, records=7864, sum=500205, duplicateKeys=987, badSlices=0, indexMismatches=433, partialBatches=6, error=null], sum 500205 != 400000, version 13 does not sum to the total, after vacuum: Report[version=577, files=15, records=7864, sum=500205, duplicateKeys=987, badSlices=0, indexMismatches=433, partialBatches=6, error=null]]
round  2: v1200  files=25  records=10909  FAIL [verify: Report[version=1200, files=25, records=10909, sum=501881, duplicateKeys=1290, badSlices=0, indexMismatches=736, partialBatches=8, error=null], sum 501881 != 400000, version 577 does not sum to the total, after vacuum: Report[version=1200, files=25, records=10909, sum=501881, duplicateKeys=1290, badSlices=0, indexMismatches=736, partialBatches=8, error=null]]
round  3: v1769  files=28  records=11706  FAIL [verify: Report[version=1769, files=28, records=11706, sum=501716, duplicateKeys=1290, badSlices=0, indexMismatches=736, partialBatches=8, error=null], sum 501716 != 400000, version 1200 does not sum to the total, after vacuum: Report[version=1769, files=28, records=11706, sum=501716, duplicateKeys=1290, badSlices=0, indexMismatches=736, partialBatches=8, error=null]]
round  4: v2362  files=27  records=11706  FAIL [verify: Report[version=2362, files=27, records=11706, sum=501937, duplicateKeys=1290, badSlices=0, indexMismatches=736, partialBatches=8, error=null], sum 501937 != 400000, version 1769 does not sum to the total, after vacuum: Report[version=2362, files=27, records=11706, sum=501937, duplicateKeys=1290, badSlices=0, indexMismatches=736, partialBatches=8, error=null]]
round  5: v2950  files=27  records=11706  FAIL [verify: Report[version=2950, files=27, records=11706, sum=501817, duplicateKeys=1290, badSlices=0, indexMismatches=736, partialBatches=8, error=null], sum 501817 != 400000, version 2362 does not sum to the total, after vacuum: Report[version=2950, files=27, records=11706, sum=501817, duplicateKeys=1290, badSlices=0, indexMismatches=736, partialBatches=8, error=null]]
```
