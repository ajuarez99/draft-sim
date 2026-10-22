| rest | 240.5 | n/a | n/a | n/a | rep=1 image=bk041:041 |
| rest | 241.8 | 26.1 | 76.0 | 236.6 | rep=1 image=bk041:041-jdk |
| warm | 533.9 | n/a | n/a | n/a | rep=1 image=bk041:041 |
| warm | 522.6 | 54.8 | 290.0 | 484.2 | rep=1 image=bk041:041-jdk |
| realistic-end | 422.9 | n/a | n/a | n/a | rep=1 image=bk041:041 peak=568.3MB calls=20 2xx=20 429=0 other=0 results=20 p50_ms=684 p95_ms=1012 max_ms=1677 |
| realistic-end | 456.7 | 72.4 (before GC) | 224.0 | 416.0 | rep=1 image=bk041:041-jdk peak=695.9MB; heap committed peak 457 MB, used peak 198 MB (used includes garbage); calls=20 2xx=20 429=0 other=0 results=20 p50_ms=846 p95_ms=1117 max_ms=2074 |
| stress-end | 521.8 | n/a | n/a | n/a | rep=1 image=bk041:041 peak=834.2MB calls=60 2xx=30 429=30 other=0 results=30 p50_ms=2575 p95_ms=4086 max_ms=4090 |
| stress-end | 532.8 | 95.8 (before GC) | 297.0 | 492.0 | rep=1 image=bk041:041-jdk peak=766.9MB; heap committed peak 549 MB, used peak 215 MB (used includes garbage); calls=60 2xx=30 429=30 other=0 results=30 p50_ms=2891 p95_ms=4738 max_ms=4751 |
| recovery+1min | 522.3 | n/a | n/a | n/a | rep=1 image=bk041:041 |
| recovery+1min | 533.1 | 95.9 (before GC) | 297.0 | 492.0 | rep=1 image=bk041:041-jdk |
| recovery+5min | 436.5 | n/a | n/a | n/a | rep=1 image=bk041:041 |
| recovery+5min | 512.4 | 74.0 (before GC) | 277.0 | 470.6 | rep=1 image=bk041:041-jdk |
| recovery+10min | 413.7 | n/a | n/a | n/a | rep=1 image=bk041:041 |
| recovery+10min | 442.0 | 62.2 (before GC) | 214.0 | 406.3 | rep=1 image=bk041:041-jdk |
| recovery+10min-afterGC | 443.9 | 46.2 | 214.0 | 406.3 | rep=1 image=bk041:041-jdk (GC.run forced, live set) |
| worst-end | 950.4 | n/a | n/a | n/a | rep=1 peak=955.6MB; OOMKilled=false restarts=0; calls=2 2xx=2 429=0 other=0 results=2 p50_ms=12471 p95_ms=12474 max_ms=12474 |
| worst-end | 946.0 | 488.4 (before GC) | 684.0 | 909.0 | rep=1 peak=966.0MB; heap committed peak 684 MB, used peak 544 MB (used includes garbage); OOMKilled=false restarts=0; calls=2 2xx=2 429=0 other=0 results=2 p50_ms=15198 p95_ms=15198 max_ms=15198 |
| rest | 281.0 | n/a | n/a | n/a | rep=2 image=bk041:041 |
| rest | 277.7 | 26.1 | 77.0 | 237.8 | rep=2 image=bk041:041-jdk |
| warm | 607.0 | n/a | n/a | n/a | rep=2 image=bk041:041 |
| warm | 604.7 | 54.9 | 364.0 | 561.6 | rep=2 image=bk041:041-jdk |
| realistic-end | 457.3 | n/a | n/a | n/a | rep=2 image=bk041:041 peak=706.9MB calls=20 2xx=20 429=0 other=0 results=20 p50_ms=636 p95_ms=931 max_ms=1405 |
| realistic-end | 681.6 | 255.0 (before GC) | 534.0 | 733.8 | rep=2 image=bk041:041-jdk peak=705.6MB; heap committed peak 534 MB, used peak 338 MB (used includes garbage); calls=20 2xx=20 429=0 other=0 results=20 p50_ms=794 p95_ms=1107 max_ms=2027 |
| stress-end | 562.4 | n/a | n/a | n/a | rep=2 image=bk041:041 peak=652.1MB calls=60 2xx=30 429=30 other=0 results=30 p50_ms=2513 p95_ms=4343 max_ms=4358 |
| stress-end | 790.9 | 113.6 (before GC) | 632.0 | 834.9 | rep=2 image=bk041:041-jdk peak=829.0MB; heap committed peak 632 MB, used peak 407 MB (used includes garbage); calls=60 2xx=34 429=26 other=0 results=34 p50_ms=2385 p95_ms=3808 max_ms=4374 |
| recovery+1min | 562.2 | n/a | n/a | n/a | rep=2 image=bk041:041 |
| recovery+1min | 789.8 | 113.6 (before GC) | 632.0 | 834.6 | rep=2 image=bk041:041-jdk |
| recovery+5min | 464.5 | n/a | n/a | n/a | rep=2 image=bk041:041 |
| recovery+5min | 360.4 | 58.2 (before GC) | 204.0 | 397.6 | rep=2 image=bk041:041-jdk |
| recovery+10min | 404.7 | n/a | n/a | n/a | rep=2 image=bk041:041 |
| recovery+10min | 324.4 | 55.7 (before GC) | 200.0 | 393.2 | rep=2 image=bk041:041-jdk |
| recovery+10min-afterGC | 326.5 | 46.4 | 200.0 | 393.2 | rep=2 image=bk041:041-jdk (GC.run forced, live set) |
| worst-end | 930.5 | n/a | n/a | n/a | rep=2 peak=936.1MB; OOMKilled=false restarts=0; calls=2 2xx=2 429=0 other=0 results=2 p50_ms=12682 p95_ms=12709 max_ms=12709 |
| worst-end | 987.7 | 642.7 (before GC) | 746.0 | 971.6 | rep=2 peak=992.9MB; heap committed peak 746 MB, used peak 570 MB (used includes garbage); OOMKilled=false restarts=0; calls=2 2xx=2 429=0 other=0 results=2 p50_ms=16100 p95_ms=16103 max_ms=16103 |
| rest | 267.2 | n/a | n/a | n/a | rep=3 image=bk041:041 |
| rest | 276.3 | 26.2 | 94.0 | 255.4 | rep=3 image=bk041:041-jdk |
| warm | 656.9 | n/a | n/a | n/a | rep=3 image=bk041:041 |
| warm | 568.5 | 54.7 | 390.0 | 586.3 | rep=3 image=bk041:041-jdk |
| realistic-end | 445.0 | n/a | n/a | n/a | rep=3 image=bk041:041 peak=704.1MB calls=20 2xx=20 429=0 other=0 results=20 p50_ms=780 p95_ms=1552 max_ms=2791 |
| realistic-end | 466.7 | 104.6 (before GC) | 227.0 | 420.1 | rep=3 image=bk041:041-jdk peak=618.8MB; heap committed peak 363 MB, used peak 188 MB (used includes garbage); calls=20 2xx=20 429=0 other=0 results=20 p50_ms=965 p95_ms=1479 max_ms=3266 |
| stress-end | 520.0 | n/a | n/a | n/a | rep=3 image=bk041:041 peak=836.6MB calls=60 2xx=26 429=34 other=0 results=26 p50_ms=2850 p95_ms=5401 max_ms=5401 |
| stress-end | 531.6 | 84.6 (before GC) | 280.0 | 475.4 | rep=3 image=bk041:041-jdk peak=764.7MB; heap committed peak 453 MB, used peak 220 MB (used includes garbage); calls=60 2xx=24 429=36 other=0 results=24 p50_ms=3084 p95_ms=4606 max_ms=4633 |
| recovery+1min | 519.9 | n/a | n/a | n/a | rep=3 image=bk041:041 |
| recovery+1min | 531.8 | 84.7 (before GC) | 280.0 | 475.1 | rep=3 image=bk041:041-jdk |
| recovery+5min | 520.6 | n/a | n/a | n/a | rep=3 image=bk041:041 |
| recovery+5min | 505.5 | 70.5 (before GC) | 254.0 | 447.9 | rep=3 image=bk041:041-jdk |
| recovery+10min | 416.3 | n/a | n/a | n/a | rep=3 image=bk041:041 |
| recovery+10min | 425.5 | 59.8 (before GC) | 207.0 | 399.2 | rep=3 image=bk041:041-jdk |
| recovery+10min-afterGC | 427.7 | 46.3 | 207.0 | 399.2 | rep=3 image=bk041:041-jdk (GC.run forced, live set) |
| worst-end | 1003.0 | n/a | n/a | n/a | rep=3 peak=1009.0MB; OOMKilled=false restarts=0; calls=2 2xx=2 429=0 other=0 results=2 p50_ms=14465 p95_ms=14467 max_ms=14467 |
| worst-end | 854.6 | 455.9 (before GC) | 621.0 | 838.6 | rep=3 peak=742.9MB; heap committed peak 621 MB, used peak 456 MB (used includes garbage); OOMKilled=false restarts=0; calls=2 2xx=2 429=0 other=0 results=2 p50_ms=10458 p95_ms=10539 max_ms=10539 |
