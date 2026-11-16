#!/usr/bin/env python3
"""holds.py FILE : in a raw S32_LE 4-channel capture, every click (a 1 ms peak 12 dB over the
loudest of the 200 ms around it, or over -40 dBFS in silence) and every gap in sound."""
import sys, numpy as np
x = np.fromfile(sys.argv[1], dtype='<i4').reshape(-1, 4)[:, :2].astype(np.float32) / 2**31
w = 48
n = len(x) // w
pk = np.abs(x[:n*w]).reshape(n, w, 2).max(axis=1)           # 1 ms peaks, per channel
db = 20 * np.log10(pk + 1e-9)
both = db.max(axis=1)
# the level around each ms, the ms itself and its neighbours left out: the median of 200 ms
clicks = []
for i in range(120, n - 120):
    around = np.concatenate([both[i-100:i-3], both[i+4:i+101]])
    ref = max(np.percentile(around, 95), -52.0)
    if both[i] > ref + 12:
        if clicks and i - clicks[-1][0] < 30: continue
        clicks.append((i, db[i, 0], db[i, 1], ref))
print(len(clicks), "clicks")
for i, l, r, ref in clicks:
    # the gap after (or around) it: how long the sound stays 20 dB under what it was
    before = np.median(both[max(0, i-300):i-20])
    j = i
    while j < n and both[j] < before - 20 or j < i + 2: j += 1
    k = i - 1
    while k > 0 and both[k] < before - 20: k -= 1
    print("  at %7.3f s  L %6.1f R %6.1f dBFS  (around it %5.1f, sound before %5.1f; quiet from %+d to %+d ms)"
          % (i / 1000, l, r, ref, before, k - i + 1, j - i))
