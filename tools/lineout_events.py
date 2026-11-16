#!/usr/bin/env python3
"""events.py FILE [threshold_db] : every stretch above the threshold in a raw S32_LE 4-channel capture."""
import sys, numpy as np
f = sys.argv[1]; th = float(sys.argv[2]) if len(sys.argv) > 2 else -70.0
x = np.fromfile(f, dtype='<i4').reshape(-1, 4)[:, :2].astype(np.float32) / 2**31
w = 48
n = len(x) // w
pk = np.abs(x[:n*w]).reshape(n, w, 2).max(axis=1)
db = 20 * np.log10(pk + 1e-9)
print("%.0f s, floor (median 1 ms peak) L %.1f R %.1f dBFS" % (len(x) / 48000, np.median(db[:, 0]), np.median(db[:, 1])))
hot = np.where(db.max(axis=1) > th)[0]
ev = []
for i in hot:
    if ev and i - ev[-1][1] < 300: ev[-1][1] = i
    else: ev.append([i, i])
print(len(ev), "events above %.0f dBFS" % th)
for a, b in ev[:60]:
    seg = db[a:b+1]
    print("  at %8.3f s for %4d ms  peak L %6.1f R %6.1f" % (a * w / 48000, b - a + 1, seg[:, 0].max(), seg[:, 1].max()))
