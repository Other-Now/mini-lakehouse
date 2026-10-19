# Crash test: control-DIRECT-torn

Linux amd64, 4 hw threads, Java 21.0.12.1. commit mode DIRECT, conflict check FULL, 4 writer JVMs per round, 3000 ms per round, insert batches drawn from 64 shared bases.

| | |
|---|---:|
| rounds run | 2 |
| rounds failing verification | **1** |
| table versions committed | table unreadable |
| writers halted at an injected crash point | 1 |
| &nbsp;&nbsp;torn-commit | 1 |
| writers kill -9'd externally | 2 |
| writers exiting cleanly | 0 |
| writers failing with an error | 5 |
| acknowledged commits checked | 353 |
| acknowledged commits lost | **0** |
| orphan files + commit temp files removed by vacuum | 1 + 0 |

First failures:

```
round 1 writer 0 exit 3: WRITER-ERROR lakehouse.CorruptTableException: torn commit timeline/00000000000000000177.commit
round 1 writer 2 exit 3: WRITER-ERROR lakehouse.CorruptTableException: torn commit timeline/00000000000000000177.commit
round 2 writer 0 exit 3: WRITER-ERROR lakehouse.CorruptTableException: torn commit timeline/00000000000000000371.commit
round 2 writer 2 exit 3: WRITER-ERROR lakehouse.CorruptTableException: torn commit timeline/00000000000000000371.commit
round 2 writer 3 exit 3: WRITER-ERROR lakehouse.CorruptTableException: torn commit timeline/00000000000000000371.commit
round  2: v-1    files=0   records=0      FAIL [unreadable: CorruptTableException: torn commit timeline/00000000000000000371.commit]
```
