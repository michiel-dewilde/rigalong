#!/usr/bin/env python3
"""lineout.py FILE [rate] : level, envelope and dips of a raw S32_LE 4-channel capture."""
import sys, numpy as np
f = sys.argv[1]; rate = int(sys.argv[2]) if len(sys.argv) > 2 else 48000
x = np.fromfile(f, dtype='<i4').reshape(-1, 4)[:, :2].astype(np.float64) / 2**31
db = lambda v: -200.0 if v <= 0 else 20 * np.log10(v)
print(f"{len(x)/rate:.2f} s  peak L {db(np.abs(x[:,0]).max()):.1f} R {db(np.abs(x[:,1]).max()):.1f} dBFS")
m = x.mean(axis=1)
w = rate // 200                                   # 5 ms windows
n = len(m) // w
rms = np.sqrt((m[:n*w].reshape(n, w) ** 2).mean(axis=1))
rdb = np.array([db(v) for v in rms])
# one line per half second: the window's median level
for i in range(0, n, 100):
    seg = rdb[i:i+100]
    print(f"  {i*w/rate:5.1f}s  med {np.median(seg):6.1f}  min {seg.min():6.1f}  max {seg.max():6.1f}")
# dips: inside sounding stretches (running median over 250 ms above -60), a window 15 dB under it
k = 50
dips = []
for i in range(k, n - k):
    ref = np.median(rdb[i-k:i+k])
    if ref > -60 and rdb[i] < ref - 15: dips.append((i*w/rate, rdb[i], ref))
print(f"dips (5 ms window 15 dB under its 500 ms median, while sounding): {len(dips)}")
runs = []
for t, v, r in dips:
    if runs and t - runs[-1][1] < 0.011: runs[-1][1] = t; runs[-1][2] = min(runs[-1][2], v)
    else: runs.append([t, t, v, r])
for a, b, v, r in runs[:40]:
    print(f"  dip at {a:.3f}s for {1000*(b-a)+5:.0f} ms: {v:.1f} dB against {r:.1f}")
