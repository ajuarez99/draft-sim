# Raw rows appended by scripts/memory/measure.sh (spec 041 baseline, bk041:main). Source for baseline.md.
| profile | container MB (docker stats) | heap used after GC MB | heap committed MB | NMT committed MB | note |
|---|---|---|---|---|---|
| rest | 244.9 | n/a | n/a | n/a | rep=1 image=bk041:main |
| rest | 246.6 | 25.9 | 80.0 | 241.1 | rep=1 image=bk041:main-jdk |
| warm | 638.7 | n/a | n/a | n/a | rep=1 image=bk041:main |
| warm | 494.5 | 54.6 | 321.0 | 515.4 | rep=1 image=bk041:main-jdk |
| realistic-end | 455.5 | n/a | n/a | n/a | rep=1 image=bk041:main peak=704.2MB calls=20 2xx=20 429=0 other=0 results=20 p50_ms=921 p95_ms=1129 max_ms=2755 |
| realistic-end | 742.5 | 176.2 (before GC) | 535.0 | 736.3 | rep=1 image=bk041:main-jdk peak=767.3MB; heap committed peak 535 MB, used peak 312 MB (used includes garbage); calls=20 2xx=20 429=0 other=0 results=20 p50_ms=840 p95_ms=1468 max_ms=3442 |
| stress-end | 756.5 | n/a | n/a | n/a | rep=1 image=bk041:main peak=869.4MB calls=60 2xx=21 429=39 other=0 results=21 p50_ms=3128 p95_ms=4878 max_ms=4943 |
| stress-end | 827.0 | 138.6 (before GC) | 611.0 | 815.4 | rep=1 image=bk041:main-jdk peak=855.8MB; heap committed peak 611 MB, used peak 340 MB (used includes garbage); calls=60 2xx=20 429=40 other=0 results=20 p50_ms=3428 p95_ms=3941 max_ms=3943 |
| recovery+1min | 756.5 | n/a | n/a | n/a | rep=1 image=bk041:main |
| recovery+1min | 826.7 | 138.7 (before GC) | 611.0 | 815.1 | rep=1 image=bk041:main-jdk |
| recovery+5min | 756.9 | n/a | n/a | n/a | rep=1 image=bk041:main |
| recovery+5min | 827.3 | 138.8 (before GC) | 611.0 | 815.2 | rep=1 image=bk041:main-jdk |
| recovery+10min | 762.1 | n/a | n/a | n/a | rep=1 image=bk041:main |
| recovery+10min | 827.3 | 139.0 (before GC) | 611.0 | 815.8 | rep=1 image=bk041:main-jdk |
| recovery+10min-afterGC | 829.2 | 56.1 | 611.0 | 815.2 | rep=1 image=bk041:main-jdk (GC.run forced, live set) |
| worst-end | 1045.5 | n/a | n/a | n/a | rep=1 peak=1047.6MB; OOMKilled=false restarts=0; calls=2 2xx=2 429=0 other=0 results=2 p50_ms=16271 p95_ms=16275 max_ms=16275 |
| worst-end | 1181.7 | 439.7 (before GC) | 958.0 | 1171.6 | rep=1 peak=1181.7MB; heap committed peak 958 MB, used peak 573 MB (used includes garbage); OOMKilled=false restarts=0; calls=2 2xx=2 429=0 other=0 results=2 p50_ms=15485 p95_ms=15528 max_ms=15528 |
| rest | 261.9 | n/a | n/a | n/a | rep=2 image=bk041:main |
| rest | 294.9 | 26.0 | 93.0 | 254.4 | rep=2 image=bk041:main-jdk |
| warm | 527.2 | n/a | n/a | n/a | rep=2 image=bk041:main |
| warm | 576.9 | 54.7 | 414.0 | 612.5 | rep=2 image=bk041:main-jdk |
| realistic-end | 469.5 | n/a | n/a | n/a | rep=2 image=bk041:main peak=766.1MB calls=20 2xx=20 429=0 other=0 results=20 p50_ms=913 p95_ms=1617 max_ms=2951 |
| realistic-end | 456.5 | 73.6 (before GC) | 210.0 | 404.5 | rep=2 image=bk041:main-jdk peak=697.7MB; heap committed peak 468 MB, used peak 218 MB (used includes garbage); calls=20 2xx=20 429=0 other=0 results=20 p50_ms=1119 p95_ms=1601 max_ms=3586 |
| stress-end | 711.8 | n/a | n/a | n/a | rep=2 image=bk041:main peak=721.5MB calls=60 2xx=20 429=40 other=0 results=20 p50_ms=3718 p95_ms=4261 max_ms=4390 |
| stress-end | 840.6 | 138.7 (before GC) | 612.0 | 816.2 | rep=2 image=bk041:main-jdk peak=842.2MB; heap committed peak 662 MB, used peak 220 MB (used includes garbage); calls=60 2xx=20 429=40 other=0 results=20 p50_ms=4264 p95_ms=5677 max_ms=5833 |
| recovery+1min | 711.7 | n/a | n/a | n/a | rep=2 image=bk041:main |
| recovery+1min | 840.7 | 138.7 (before GC) | 612.0 | 815.8 | rep=2 image=bk041:main-jdk |
| recovery+5min | 711.6 | n/a | n/a | n/a | rep=2 image=bk041:main |
| recovery+5min | 840.2 | 138.9 (before GC) | 612.0 | 815.9 | rep=2 image=bk041:main-jdk |
| recovery+10min | 711.9 | n/a | n/a | n/a | rep=2 image=bk041:main |
| recovery+10min | 840.5 | 140.0 (before GC) | 612.0 | 846.5 | rep=2 image=bk041:main-jdk |
| recovery+10min-afterGC | 836.5 | 56.1 | 612.0 | 816.0 | rep=2 image=bk041:main-jdk (GC.run forced, live set) |
| worst-end | 969.0 | n/a | n/a | n/a | rep=2 peak=965.9MB; OOMKilled=false restarts=0; calls=2 2xx=2 429=0 other=0 results=2 p50_ms=15461 p95_ms=15469 max_ms=15469 |
| worst-end | 1095.7 | 352.3 (before GC) | 834.0 | 1064.1 | rep=2 peak=1145.9MB; heap committed peak 834 MB, used peak 579 MB (used includes garbage); OOMKilled=false restarts=0; calls=2 2xx=2 429=0 other=0 results=2 p50_ms=17804 p95_ms=17902 max_ms=17902 |
| rest | 241.4 | n/a | n/a | n/a | rep=3 image=bk041:main |
| rest | 260.6 | 26.0 | 93.0 | 254.1 | rep=3 image=bk041:main-jdk |
| warm | 583.9 | n/a | n/a | n/a | rep=3 image=bk041:main |
| warm | 640.4 | 54.6 | 672.0 | 875.0 | rep=3 image=bk041:main-jdk |
| realistic-end | 420.2 | n/a | n/a | n/a | rep=3 image=bk041:main peak=738.6MB calls=20 2xx=20 429=0 other=0 results=20 p50_ms=956 p95_ms=1482 max_ms=2978 |
| realistic-end | 692.8 | 93.9 (before GC) | 480.0 | 681.3 | rep=3 image=bk041:main-jdk peak=718.6MB; heap committed peak 480 MB, used peak 321 MB (used includes garbage); calls=20 2xx=20 429=0 other=0 results=20 p50_ms=951 p95_ms=1966 max_ms=3066 |
| stress-end | 763.2 | n/a | n/a | n/a | rep=3 image=bk041:main peak=760.7MB calls=60 2xx=18 429=42 other=0 results=18 p50_ms=3181 p95_ms=5741 max_ms=5829 |
| stress-end | 951.0 | 208.0 (before GC) | 738.0 | 947.3 | rep=3 image=bk041:main-jdk peak=971.1MB; heap committed peak 738 MB, used peak 275 MB (used includes garbage); calls=60 2xx=20 429=40 other=0 results=20 p50_ms=3861 p95_ms=5488 max_ms=5586 |
| recovery+1min | 762.9 | n/a | n/a | n/a | rep=3 image=bk041:main |
| recovery+1min | 951.0 | 208.1 (before GC) | 738.0 | 947.1 | rep=3 image=bk041:main-jdk |
| recovery+5min | 762.8 | n/a | n/a | n/a | rep=3 image=bk041:main |
| recovery+5min | 951.0 | 208.2 (before GC) | 738.0 | 947.0 | rep=3 image=bk041:main-jdk |
| recovery+10min | 763.2 | n/a | n/a | n/a | rep=3 image=bk041:main |
| recovery+10min | 951.1 | 208.4 (before GC) | 738.0 | 947.1 | rep=3 image=bk041:main-jdk |
| recovery+10min-afterGC | 951.8 | 56.0 | 738.0 | 947.1 | rep=3 image=bk041:main-jdk (GC.run forced, live set) |
| worst-end | 1040.4 | n/a | n/a | n/a | rep=3 peak=1044.5MB; OOMKilled=false restarts=0; calls=2 2xx=2 429=0 other=0 results=2 p50_ms=16580 p95_ms=16582 max_ms=16582 |
| worst-end | 1086.5 | 544.0 (before GC) | 864.0 | 1078.4 | rep=3 peak=1101.8MB; heap committed peak 696 MB, used peak 518 MB (used includes garbage); OOMKilled=false restarts=0; calls=2 2xx=2 429=0 other=0 results=2 p50_ms=12997 p95_ms=13017 max_ms=13017 |
