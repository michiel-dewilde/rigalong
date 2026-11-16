#!/usr/bin/env python3
"""lineout_note.py FILE... : level and pitch of one held note in each raw S32_LE 4-channel capture."""
import sys, numpy as np
rate = 48000
for f in sys.argv[1:]:
    x = np.fromfile(f, dtype='<i4').reshape(-1, 4)[:, :2].astype(np.float64) / 2**31
    m = x.mean(axis=1)
    env = np.sqrt((m[:len(m)//480*480].reshape(-1, 480) ** 2).mean(axis=1))      # 10 ms
    # The note's start: the first 10 ms within 20 dB of its loudest. (Not "10x the first 200 ms":
    # a tail of the song before, still decaying there, put the start at 0 and the window astray.)
    on = np.argmax(env > env.max() / 10)
    seg = m[(on + 20) * 480:(on + 170) * 480]                                      # 0,2 s to 1,7 s in
    if len(seg) < 48000:
        print(f"{f}: no note found"); continue
    lvl = 20 * np.log10(np.sqrt((seg ** 2).mean()) + 1e-12)
    sp = np.abs(np.fft.rfft(seg * np.hanning(len(seg))))
    fr = np.fft.rfftfreq(len(seg), 1 / rate)
    band = (fr > 60) & (fr < 4000)
    k = np.argmax(sp * band)
    a, b, c = sp[k-1:k+2]; k = k + 0.5 * (a - c) / (a - 2*b + c)                   # parabolic peak
    print(f"{f}: level {lvl:6.1f} dBFS  strongest partial {k * rate / len(seg):8.2f} Hz")
