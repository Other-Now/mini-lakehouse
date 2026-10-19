# Crash test: LINK-FULL-shared-inserts

Windows 11 amd64, 8 hw threads, Java 21.0.12.1. commit mode LINK, conflict check FULL, 4 writer JVMs per round, 3000 ms per round, every writer inserts the same new batch per 50 ms time slot.

| | |
|---|---:|
| rounds run | 5 |
| rounds failing verification | **0** |
| table versions committed | 435 |
| writers halted at an injected crash point | 6 |
| &nbsp;&nbsp;after-checkpoint | 1 |
| &nbsp;&nbsp;after-commit | 1 |
| &nbsp;&nbsp;after-data-write | 1 |
| &nbsp;&nbsp;torn-commit | 3 |
| writers kill -9'd externally | 5 |
| writers exiting cleanly | 9 |
| writers failing with an error | 0 |
| acknowledged commits checked | 421 |
| acknowledged commits lost | **0** |
| orphan files + commit temp files removed by vacuum | 10 + 4 |
