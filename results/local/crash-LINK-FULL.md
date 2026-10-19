# Crash test: LINK-FULL

Windows 11 amd64, 8 hw threads, Java 21.0.12.1. commit mode LINK, conflict check FULL, 4 writer JVMs per round, 4000 ms per round, insert batches drawn from 64 shared bases.

| | |
|---|---:|
| rounds run | 40 |
| rounds failing verification | **0** |
| table versions committed | 7316 |
| writers halted at an injected crash point | 76 |
| &nbsp;&nbsp;after-checkpoint | 15 |
| &nbsp;&nbsp;after-commit | 12 |
| &nbsp;&nbsp;after-data-write | 22 |
| &nbsp;&nbsp;after-index-write | 8 |
| &nbsp;&nbsp;compact-after-write | 1 |
| &nbsp;&nbsp;torn-commit | 18 |
| writers kill -9'd externally | 28 |
| writers exiting cleanly | 56 |
| writers failing with an error | 0 |
| acknowledged commits checked | 7267 |
| acknowledged commits lost | **0** |
| orphan files + commit temp files removed by vacuum | 59 + 25 |
