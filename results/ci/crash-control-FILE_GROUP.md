# Crash test: control-FILE_GROUP

Linux amd64, 4 hw threads, Java 21.0.12.1. commit mode LINK, conflict check FILE_GROUP, 4 writer JVMs per round, 3000 ms per round, every writer inserts the same new batch per 50 ms time slot.

| | |
|---|---:|
| rounds run | 5 |
| rounds failing verification | **5** |
| table versions committed | 375 |
| writers halted at an injected crash point | 0 |
| writers kill -9'd externally | 2 |
| writers exiting cleanly | 0 |
| writers failing with an error | 18 |
| acknowledged commits checked | 367 |
| acknowledged commits lost | **0** |
| orphan files + commit temp files removed by vacuum | 0 + 0 |

First failures:

```
round 1 writer 0 exit 1: Exception in thread "main" java.lang.IllegalArgumentException: keys not strictly ascending at 38907223152640
	at lakehouse.io.DataFiles.write(DataFiles.java:71)
	at lakehouse.Txn.compact(Txn.java:165)
	at lakehouse.Maintenance.compact(Maintenance.java:25)
	at lakehouse.bench.WriterMain.main(WriterMain.java:65)
round 1 writer 1 exit 1: Exception in thread "main" java.lang.IllegalArgumentException: keys not strictly ascending at 38907223152640
	at lakehouse.io.DataFiles.write(DataFiles.java:71)
	at lakehouse.Txn.compact(Txn.java:165)
	at lakehouse.Maintenance.compact(Maintenance.java:25)
	at lakehouse.bench.WriterMain.main(WriterMain.java:65)
round 1 writer 2 exit 1: Exception in thread "main" java.lang.IllegalArgumentException: keys not strictly ascending at 38907223152640
	at lakehouse.io.DataFiles.write(DataFiles.java:71)
	at lakehouse.Txn.compact(Txn.java:165)
	at lakehouse.Maintenance.compact(Maintenance.java:25)
	at lakehouse.bench.WriterMain.main(WriterMain.java:65)
round 1 writer 3 exit 1: Exception in thread "main" java.lang.IllegalArgumentException: keys not strictly ascending at 38907223152640
	at lakehouse.io.DataFiles.write(DataFiles.java:71)
	at lakehouse.Txn.compact(Txn.java:165)
	at lakehouse.Maintenance.compact(Maintenance.java:25)
	at lakehouse.bench.WriterMain.main(WriterMain.java:65)
round  1: v182   files=26  records=2843   FAIL [verify: Report[version=182, files=26, records=2843, sum=400000, duplicateKeys=604, badSlices=0, indexMismatches=304, partialBatches=3, error=null], after vacuum: Report[version=182, files=26, records=2843, sum=400000, duplicateKeys=604, badSlices=0, indexMismatches=304, partialBatches=3, error=null]]
round 2 writer 0 exit 1: Exception in thread "main" java.lang.IllegalArgumentException: keys not strictly ascending at 38907223152640
	at lakehouse.io.DataFiles.write(DataFiles.java:71)
	at lakehouse.Txn.compact(Txn.java:165)
	at lakehouse.Maintenance.compact(Maintenance.java:25)
	at lakehouse.bench.WriterMain.main(WriterMain.java:65)
round 2 writer 1 exit 1: Exception in thread "main" java.lang.IllegalArgumentException: keys not strictly ascending at 38907223152640
	at lakehouse.io.DataFiles.write(DataFiles.java:71)
	at lakehouse.Txn.compact(Txn.java:165)
	at lakehouse.Maintenance.compact(Maintenance.java:25)
	at lakehouse.bench.WriterMain.main(WriterMain.java:65)
round 2 writer 2 exit 1: Exception in thread "main" java.lang.IllegalArgumentException: keys not strictly ascending at 38907223152640
	at lakehouse.io.DataFiles.write(DataFiles.java:71)
	at lakehouse.Txn.compact(Txn.java:165)
	at lakehouse.Maintenance.compact(Maintenance.java:25)
	at lakehouse.bench.WriterMain.main(WriterMain.java:65)
```
