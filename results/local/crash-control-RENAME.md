# Crash test: control-RENAME

Windows 11 amd64, 8 hw threads, Java 21.0.12.1. commit mode RENAME, conflict check FULL, 4 writer JVMs per round, 3000 ms per round, insert batches drawn from 64 shared bases.

| | |
|---|---:|
| rounds run | 5 |
| rounds failing verification | **5** |
| table versions committed | 264 |
| writers halted at an injected crash point | 0 |
| writers kill -9'd externally | 6 |
| writers exiting cleanly | 2 |
| writers failing with an error | 12 |
| acknowledged commits checked | 148 |
| acknowledged commits lost | **24** |
| orphan files + commit temp files removed by vacuum | 46 + 0 |

First failures:

```
round  1: v133   files=30  records=3802   FAIL [sum 400045 != 400000, version 19 does not sum to the total, 24 acknowledged commits missing from the timeline, after vacuum: Report[version=-1, files=0, records=0, sum=0, duplicateKeys=0, badSlices=0, indexMismatches=0, partialBatches=0, error=NoSuchFileException: C:\Users\chiku\AppData\Local\Temp\mini-lakehouse\crash-control-RENAME\table\data\38726ce67004048d_4066c92108334c4e.parquet]]
round 2 writer 0 exit 3: WRITER-ERROR java.nio.file.NoSuchFileException: C:\Users\chiku\AppData\Local\Temp\mini-lakehouse\crash-control-RENAME\table\data\38726ce67004048d_4066c92108334c4e.parquet
round 2 writer 1 exit 3: WRITER-ERROR java.nio.file.NoSuchFileException: C:\Users\chiku\AppData\Local\Temp\mini-lakehouse\crash-control-RENAME\table\data\38726ce67004048d_4066c92108334c4e.parquet
round 2 writer 3 exit 3: WRITER-ERROR java.nio.file.NoSuchFileException: C:\Users\chiku\AppData\Local\Temp\mini-lakehouse\crash-control-RENAME\table\data\38726ce67004048d_4066c92108334c4e.parquet
round  2: v-1    files=0   records=0      FAIL [unreadable: NoSuchFileException: C:\Users\chiku\AppData\Local\Temp\mini-lakehouse\crash-control-RENAME\table\data\38726ce67004048d_4066c92108334c4e.parquet]
round 3 writer 2 exit 3: WRITER-ERROR java.nio.file.NoSuchFileException: C:\Users\chiku\AppData\Local\Temp\mini-lakehouse\crash-control-RENAME\table\data\38726ce67004048d_4066c92108334c4e.parquet
round 3 writer 3 exit 3: WRITER-ERROR java.nio.file.NoSuchFileException: C:\Users\chiku\AppData\Local\Temp\mini-lakehouse\crash-control-RENAME\table\data\38726ce67004048d_4066c92108334c4e.parquet
round  3: v-1    files=0   records=0      FAIL [unreadable: NoSuchFileException: C:\Users\chiku\AppData\Local\Temp\mini-lakehouse\crash-control-RENAME\table\data\38726ce67004048d_4066c92108334c4e.parquet]
```
