# Crash test: control-RENAME

Linux amd64, 4 hw threads, Java 21.0.12.1. commit mode RENAME, conflict check FULL, 4 writer JVMs per round, 3000 ms per round, insert batches drawn from 64 shared bases.

| | |
|---|---:|
| rounds run | 5 |
| rounds failing verification | **5** |
| table versions committed | 603 |
| writers halted at an injected crash point | 0 |
| writers kill -9'd externally | 2 |
| writers exiting cleanly | 1 |
| writers failing with an error | 17 |
| acknowledged commits checked | 480 |
| acknowledged commits lost | **80** |
| orphan files + commit temp files removed by vacuum | 144 + 0 |

First failures:

```
round 1 writer 0 exit 1: Exception in thread "main" java.lang.IllegalArgumentException: keys not strictly ascending at 1099511666688
	at lakehouse.io.DataFiles.write(DataFiles.java:71)
	at lakehouse.Txn.compact(Txn.java:165)
	at lakehouse.Maintenance.compact(Maintenance.java:25)
	at lakehouse.bench.WriterMain.main(WriterMain.java:65)
round 1 writer 3 exit 1: Exception in thread "main" java.lang.IllegalArgumentException: keys not strictly ascending at 1099511631872
	at lakehouse.io.DataFiles.write(DataFiles.java:71)
	at lakehouse.Txn.compact(Txn.java:165)
	at lakehouse.Maintenance.compact(Maintenance.java:25)
	at lakehouse.bench.WriterMain.main(WriterMain.java:65)
round  1: v409   files=9   records=8402   FAIL [verify: Report[version=409, files=9, records=8402, sum=399700, duplicateKeys=163, badSlices=0, indexMismatches=0, partialBatches=1, error=null], sum 399700 != 400000, version 38 does not sum to the total, 80 acknowledged commits missing from the timeline, after vacuum: Report[version=-1, files=0, records=0, sum=0, duplicateKeys=0, badSlices=0, indexMismatches=0, partialBatches=0, error=NoSuchFileException: /tmp/mini-lakehouse/crash-control-RENAME/table/data/168c679c7fad4e2d_abd76883ea7e4446.parquet]]
round 2 writer 0 exit 3: WRITER-ERROR java.nio.file.NoSuchFileException: /tmp/mini-lakehouse/crash-control-RENAME/table/data/168c679c7fad4e2d_abd76883ea7e4446.parquet
round 2 writer 1 exit 3: WRITER-ERROR java.nio.file.NoSuchFileException: /tmp/mini-lakehouse/crash-control-RENAME/table/data/168c679c7fad4e2d_abd76883ea7e4446.parquet
round 2 writer 2 exit 3: WRITER-ERROR java.nio.file.NoSuchFileException: /tmp/mini-lakehouse/crash-control-RENAME/table/data/168c679c7fad4e2d_abd76883ea7e4446.parquet
round 2 writer 3 exit 3: WRITER-ERROR java.nio.file.NoSuchFileException: /tmp/mini-lakehouse/crash-control-RENAME/table/data/168c679c7fad4e2d_abd76883ea7e4446.parquet
round  2: v-1    files=0   records=0      FAIL [unreadable: NoSuchFileException: /tmp/mini-lakehouse/crash-control-RENAME/table/data/168c679c7fad4e2d_abd76883ea7e4446.parquet]
```
