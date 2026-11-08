"""Usage: python make.py OUT_DIR [dusk arcade neon lofi night]  (needs numpy and ffmpeg)
Original lobby music candidates for OnnShell, synthesized from scratch (no samples, no third-party audio).
Each track is a seamless loop: every note, filter and reverb wraps around the loop end."""
import sys, os, wave, subprocess
import numpy as np

SR = 44100
OUT = sys.argv[1]
rng = np.random.default_rng(7)


def mf(m): return 440.0 * 2 ** ((m - 69) / 12)


NAMES = {'C': 0, 'D': 2, 'E': 4, 'F': 5, 'G': 7, 'A': 9, 'B': 11}
def nm(s):  # 'C#5' / 'Eb4' -> midi
    n = NAMES[s[0]]; i = 1
    if s[1] == '#': n += 1; i = 2
    elif s[1] == 'b': n -= 1; i = 2
    return n + 12 * (int(s[i:]) + 1)


class Track:
    def __init__(s, bpm, bars):
        s.beat = 60.0 / bpm
        s.N = int(round(bars * 4 * s.beat * SR))
        s.bus = {}

    def put(s, bus, beat, sig, pan=0.0):
        b = s.bus.setdefault(bus, np.zeros((2, s.N)))
        st = int(round(beat * s.beat * SR)) % s.N
        l, r = np.cos((pan + 1) * np.pi / 4), np.sin((pan + 1) * np.pi / 4)
        pos = 0
        while pos < len(sig):  # wrap past the loop end
            n = min(len(sig) - pos, s.N - st)
            b[0, st:st + n] += sig[pos:pos + n] * l; b[1, st:st + n] += sig[pos:pos + n] * r
            pos += n; st = 0

    def secs(s, beats): return beats * s.beat


def t_(sec): return np.arange(int(sec * SR)) / SR


def env(sec, a=0.01, r=0.1):
    n = int(sec * SR); e = np.ones(n)
    na, nr = min(n, int(a * SR)), min(n, int(r * SR))
    if na: e[:na] = np.linspace(0, 1, na)
    if nr: e[n - nr:] *= np.linspace(1, 0, nr)
    return e


def saw(f, t, det=0.0): return 2 * ((f * (1 + det) * t + rng.random()) % 1) - 1
def pulse(f, t, w=0.5): return np.where(((f * t) % 1) < w, 1.0, -1.0)
def tri(f, t): return 2 * np.abs(saw(f, t)) - 1


def filt(x, lo=None, hi=None, q=2):
    """Zero-phase spectral filter over the whole (circular) buffer."""
    X = np.fft.rfft(x, axis=-1); f = np.fft.rfftfreq(x.shape[-1], 1 / SR)
    H = np.ones_like(f)
    if hi: H /= np.sqrt(1 + (f / hi) ** (2 * q))
    if lo: H *= 1 / np.sqrt(1 + (lo / np.maximum(f, 1e-3)) ** (2 * q))
    return np.fft.irfft(X * H, n=x.shape[-1], axis=-1)


def reverb(x, sec=2.5, wet=0.3, hi=6000):
    """Circular convolution with decaying noise (a different tail per channel), so the loop stays seamless."""
    N = x.shape[-1]; out = np.empty_like(x)
    tt = t_(sec)
    for c in range(2):
        ir = rng.standard_normal(len(tt)) * np.exp(-tt * 6.9 / sec)
        ir = filt(ir, hi=hi); ir /= np.sqrt(np.sum(ir ** 2))
        irp = np.zeros(N); irp[:len(ir)] = ir
        out[c] = np.fft.irfft(np.fft.rfft(x[c]) * np.fft.rfft(irp), n=N)
    return x * (1 - wet) + out * wet


def delay(x, sec, fb=0.35, wet=0.3):
    out = x.copy(); d = int(sec * SR); g = wet
    for i in range(1, 6):
        out += np.roll(x[::-1] if False else x, d * i, axis=-1) * g; g *= fb
    return out


# instruments ----------------------------------------------------------------
def pad(fs, sec, bright=1800):
    t = t_(sec); s = np.zeros(len(t))
    for f in fs:
        for d in (-0.004, 0.0, 0.0045): s += saw(f, t, d)
    return filt(s, hi=bright) * env(sec, a=min(1.2, sec / 3), r=min(1.5, sec / 3)) / (len(fs) * 3)


def fm(f, sec, ratio=1.0, index=2.0, idec=0.4, dec=1.2):
    t = t_(sec)
    return np.sin(2 * np.pi * f * t + index * np.exp(-t / idec) * np.sin(2 * np.pi * f * ratio * t)) * np.exp(-t / dec) * env(sec, 0.004, 0.05)


def flute(f, sec):
    t = t_(sec); vib = 1 + 0.004 * np.sin(2 * np.pi * 5 * t) * np.minimum(1, t / 0.6)
    ph = 2 * np.pi * np.cumsum(f * vib) / SR
    return (np.sin(ph) + 0.15 * np.sin(2 * ph) + 0.004 * rng.standard_normal(len(t))) * env(sec, 0.12, 0.25)


def kick(amp=1.0, dec=0.28):
    t = t_(0.5); f = 45 + 95 * np.exp(-t / 0.035)
    return amp * np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t / dec)


def snare(amp=0.5, dec=0.13, tone=190):
    t = t_(0.4); n = filt(rng.standard_normal(len(t)), lo=1200, hi=7000)
    return amp * (0.8 * n * np.exp(-t / dec) + 0.5 * np.sin(2 * np.pi * tone * t) * np.exp(-t / 0.06))


def hat(amp=0.15, dec=0.03):
    t = t_(0.15); return amp * filt(rng.standard_normal(len(t)), lo=7000) * np.exp(-t / dec)


def master(tr, buses, lp=None, name='x', title='', lufs=-20):
    mix = sum(buses)
    if lp: mix = filt(mix, hi=lp)
    mix = filt(mix, lo=30)
    mix = np.tanh(mix / np.max(np.abs(mix)) * 1.2) ; mix *= 0.89 / np.max(np.abs(mix))
    wav = os.path.join(OUT, name + '.wav')
    with wave.open(wav, 'wb') as w:
        w.setnchannels(2); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes((mix.T * 32767).astype('<i2').tobytes())
    meta = ['-metadata', f'title={title}', '-metadata', 'artist=OnnShell']
    subprocess.run(['ffmpeg', '-v', 'error', '-y', '-i', wav, '-af', f'loudnorm=I={lufs}:TP=-1.5:LRA=11', '-ar', '44100',
                    '-c:a', 'libvorbis', '-q:a', '5', *meta, os.path.join(OUT, name + '.ogg')], check=True)
    # preview: the loop twice, so the seam can be heard
    subprocess.run(['ffmpeg', '-v', 'error', '-y', '-stream_loop', '1', '-i', wav, '-af', f'loudnorm=I={lufs}:TP=-1.5:LRA=11',
                    '-ar', '44100', '-c:a', 'libmp3lame', '-q:a', '2', *meta, os.path.join(OUT, name + '-preview.mp3')], check=True)
    os.remove(wav)
    print(name, f'{tr.N / SR:.1f} s loop')


# 1. Dusk: slow ambient, D major ---------------------------------------------
def dusk():
    tr = Track(70, 16)
    chords = [[50, 57, 61, 64, 66], [47, 54, 57, 61, 62], [43, 50, 54, 59, 61], [45, 52, 57, 59, 64]]
    for i in range(8):
        c = chords[i % 4]; b0 = i * 8
        tr.put('pad', b0, pad([mf(m) for m in c], tr.secs(8) + 1.5, bright=1400))
        tr.put('bass', b0, 0.5 * np.sin(2 * np.pi * mf(c[0] - 12) * t_(tr.secs(8))) * env(tr.secs(8), 0.6, 0.8))
        tones = sorted(c[1:]) + [c[1] + 12]
        pat = [0, 2, 4, 1, 3, 2, 4, 1]
        for k in range(16):
            if rng.random() < 0.3: continue
            m = tones[pat[k % 8]] + 12
            tr.put('bell', b0 + k * 0.5, fm(mf(m), 2.5, ratio=3.5, index=1.6, idec=0.25, dec=0.9) * rng.uniform(0.25, 0.5),
                   pan=rng.uniform(-0.6, 0.6))
    mel = [(32, 74, 3), (35, 76, 1), (36, 78, 4), (40, 76, 2), (42, 74, 2), (44, 71, 4), (48, 69, 3), (51, 71, 1),
           (52, 74, 4), (56, 73, 2), (58, 76, 2), (60, 74, 4)]
    for b, m, l in mel: tr.put('lead', b, flute(mf(m), tr.secs(l) + 0.2) * 0.35, pan=0.15)
    B = tr.bus
    master(tr, [reverb(B['pad'], 3.5, 0.5), B['bass'], reverb(B['bell'], 3.0, 0.55), delay(reverb(B['lead'], 3, 0.4), tr.secs(0.75))],
           lp=7000, name='1-dusk', title='Dusk')


# 2. Arcade Lounge: chill chiptune, A minor ------------------------------------
def arcade():
    tr = Track(96, 32)
    roots = [57, 53, 48, 55]; triads = [[57, 60, 64], [53, 57, 60], [48, 52, 55], [55, 59, 62]]
    A = [[('E5', 0, 1), ('A5', 1, 1), ('B5', 2, .5), ('C6', 2.5, 1.5)], [('A5', 0, 1), ('G5', 1, .5), ('F5', 1.5, .5), ('A5', 2, 2)],
         [('G5', 0, 1), ('E5', 1, 1), ('C5', 2, 1), ('D5', 3, 1)], [('E5', 0, 1.5), ('D5', 1.5, .5), ('B4', 2, 2)],
         [('E5', 0, 1), ('A5', 1, 1), ('B5', 2, .5), ('C6', 2.5, 1.5)], [('D6', 0, 1), ('C6', 1, .5), ('A5', 1.5, .5), ('C6', 2, 2)],
         [('G5', 0, 1), ('E5', 1, .5), ('G5', 1.5, .5), ('C6', 2, 1), ('B5', 3, 1)], [('A5', 0, 3)]]
    A2 = A[:7] + [[('B5', 0, 1), ('D6', 1, 1), ('E6', 2, 2)]]
    Bs = [[('C6', 0, 4)], [('A5', 0, 4)], [('G5', 0, 4)], [('B5', 0, 4)]] * 2
    song = A + A2 + Bs + A
    for bar in range(32):
        ch = bar % 4; b0 = bar * 4
        for e in range(8):
            m = roots[ch] - 24 + (12 if e % 2 else 0)
            tr.put('bass', b0 + e * 0.5, tri(mf(m), t_(tr.secs(0.45))) * env(tr.secs(0.45), 0.003, 0.03) * 0.5)
        arp = triads[ch] + [triads[ch][0] + 12, triads[ch][2], triads[ch][1]]
        for s in range(16):
            m = arp[s % 6] + 12
            tr.put('arp', b0 + s * 0.25, pulse(mf(m), t_(0.12), 0.25) * np.exp(-t_(0.12) / 0.05) * (0.09 if 16 <= bar < 24 else 0.06),
                   pan=0.35)
        for n, b, l in song[bar]:
            sec = tr.secs(l) * 0.92
            tt = t_(sec); vib = 1 + 0.003 * np.sin(2 * np.pi * 6 * tt) * np.minimum(1, tt / 0.3)
            ph = np.cumsum(mf(nm(n)) * vib) / SR
            tr.put('lead', b0 + b, np.where((ph % 1) < 0.5, 1.0, -1.0) * env(sec, 0.005, 0.06) * 0.16, pan=-0.1)
        tr.put('drums', b0, kick(0.6, 0.18)); tr.put('drums', b0 + 2, kick(0.5, 0.18))
        for e in range(4): tr.put('drums', b0 + e + 0.5, hat(0.06))
        if bar >= 16: tr.put('drums', b0 + 1, snare(0.15, 0.08, 230)); tr.put('drums', b0 + 3, snare(0.15, 0.08, 230))
    B = tr.bus
    master(tr, [filt(B['bass'], hi=2500), filt(B['arp'], hi=5000), delay(filt(B['lead'], hi=4500), tr.secs(0.75), 0.3, 0.2),
                reverb(B['drums'], 1.2, 0.15)], name='2-arcade-lounge', title='Arcade Lounge')


# 3. Neon Drive: soft synthwave, F minor -------------------------------------
def neon():
    tr = Track(100, 32)
    chords = [[53, 56, 60, 65], [49, 53, 56, 61], [56, 60, 63, 68], [51, 55, 58, 63]]
    cyc = [[('C5', 0, 2), ('Ab4', 2, 1), ('C5', 3, 1)], [('Eb5', 0, 3), ('F5', 3, 1)], [('F5', 0, 2), ('Eb5', 2, 1), ('Db5', 3, 1)],
           [('C5', 0, 4)], [('Eb5', 0, 2), ('C5', 2, 1), ('Eb5', 3, 1)], [('Ab5', 0, 3), ('G5', 3, 1)],
           [('G5', 0, 2), ('F5', 2, 1), ('Eb5', 2 + 1, 1)], [('Bb4', 0, 4)]]
    cyc2 = cyc[:7] + [[('C5', 0, 4)]]
    for i in range(16):
        c = chords[i % 4]; b0 = i * 8
        tr.put('pad', b0, pad([mf(m) for m in c], tr.secs(8) + 1.0, bright=1600))
        for e in range(16):
            m = c[0] - 24
            tr.put('bass', b0 + e * 0.5, saw(mf(m), t_(tr.secs(0.45))) * np.exp(-t_(tr.secs(0.45)) / 0.12) * 0.45)
    for bar in range(32):
        b0 = bar * 4
        tr.put('drums', b0, kick(0.9)); tr.put('drums', b0 + 2, kick(0.8))
        for e in range(8): tr.put('drums', b0 + e * 0.5, hat(0.05 if e % 2 else 0.03))
        if bar >= 8:
            tr.put('snare', b0 + 1, snare(0.45)); tr.put('snare', b0 + 3, snare(0.45))
        if bar >= 16:
            for n, b, l in (cyc if bar < 24 else cyc2)[bar % 8]:
                sec = tr.secs(l) * 0.95; tt = t_(sec)
                s = (saw(mf(nm(n)), tt, -0.003) + saw(mf(nm(n)), tt, 0.003)) * env(sec, 0.02, 0.2) * 0.13
                tr.put('lead', b0 + b, s)
    B = tr.bus
    master(tr, [reverb(B['pad'], 3, 0.45), filt(B['bass'], hi=700), B['drums'], reverb(B['snare'], 2.2, 0.5),
                delay(reverb(filt(B['lead'], hi=3000), 2.5, 0.35), tr.secs(0.75), 0.4, 0.3)],
           lp=9000, name='3-neon-drive', title='Neon Drive')


# 4. Lo-fi Lobby: mellow keys, E flat ----------------------------------------
def lofi(bpm=80, night=False):
    tr = Track(bpm, 16)
    chords = [[51, 55, 58, 62, 65], [48, 55, 58, 62, 63], [56, 60, 63, 67, 70], [56, 60, 63, 67]]
    roots = [39, 36, 44, 46]
    sw = 0.66  # swing: the off-beat 8th lands at 2/3 of the beat
    for bar in range(16):
        ch = bar % 4; b0 = bar * 4
        for b, l, v in ((0, 1.4, 0.5), (1 + sw, 0.8, 0.3), (2.5 + 0.16, 1.2, 0.38)):
            for k, m in enumerate(chords[ch]):
                tr.put('keys', b0 + b + k * 0.012, fm(mf(m), tr.secs(l) + 0.6, 1.0, 1.4, 0.3, 0.9) * v / 4,
                       pan=-0.3 + 0.15 * k)
        for b, m, l in ((0, roots[ch], 1.5), (1.5 + 0.16, roots[ch] + 7, 0.5), (2.5 + 0.16, roots[ch], 1.2)):
            tt = t_(tr.secs(l)); tr.put('bass', b0 + b, np.sin(2 * np.pi * mf(m) * tt) * np.exp(-tt / 0.6) * env(tr.secs(l), 0.01, 0.08) * 0.6)
        if night:  # late night: a soft kick, brushed snare, no hats on every beat
            tr.put('drums', b0, kick(0.45, 0.25))
            tr.put('drums', b0 + 1, snare(0.08, 0.18, 0)); tr.put('drums', b0 + 3, snare(0.08, 0.18, 0))
            for e in (1, 3): tr.put('drums', b0 + e + sw, hat(0.015, 0.04))
            continue
        tr.put('drums', b0, kick(0.7, 0.2)); tr.put('drums', b0 + 2.5 + 0.16, kick(0.45, 0.2))
        tr.put('drums', b0 + 1, snare(0.25, 0.07, 330)); tr.put('drums', b0 + 3, snare(0.25, 0.07, 330))
        for e in range(4):
            tr.put('drums', b0 + e, hat(0.05, 0.025)); tr.put('drums', b0 + e + sw, hat(0.03, 0.02))
    mel = {8: [(0.66, 70, 1), (1.66, 67, 0.33), (2, 65, 1), (3, 63, 1)], 9: [(0, 62, 2), (2.66, 63, 0.33), (3, 67, 1)],
           10: [(0, 68, 1.66), (1.66, 67, 0.33), (2, 63, 2)], 11: [(0, 65, 3)],
           12: [(0.66, 70, 1), (1.66, 72, 0.33), (2, 70, 1), (3, 67, 1)], 13: [(0, 63, 1.66), (1.66, 65, 0.33), (2, 67, 2)],
           14: [(0, 72, 1), (1, 70, 1), (2, 68, 1), (3, 67, 1)], 15: [(0, 65, 2), (2, 63, 2)]}
    for bar, ns in mel.items():
        for b, m, l in ns: tr.put('lead', bar * 4 + b, fm(mf(m), tr.secs(l) + 0.8, 4.0, 0.8, 0.15, 0.7) * 0.22, pan=0.25)
    # vinyl: soft hiss plus sparse crackle, over the whole loop
    crackle = filt(rng.standard_normal(tr.N), lo=800, hi=5000) * 0.004
    pops = np.zeros(tr.N); idx = rng.integers(0, tr.N, int(tr.N / SR * 6)); pops[idx] = rng.uniform(-0.15, 0.15, len(idx))
    tr.bus['vinyl'] = np.vstack([crackle + filt(pops, lo=1500), crackle + filt(np.roll(pops, 777), lo=1500)])
    B = tr.bus
    if night:
        master(tr, [reverb(B['keys'], 3.2, 0.45), filt(B['bass'], hi=450), filt(B['drums'], hi=4000), reverb(B['lead'], 3.2, 0.5), B['vinyl'] * 0.8],
               lp=3200, name='5-lofi-lobby-night', title='Lo-fi Lobby (Late Night)', lufs=-23)
        return
    master(tr, [reverb(B['keys'], 2.0, 0.3), filt(B['bass'], hi=600), filt(B['drums'], hi=6000), reverb(B['lead'], 2.0, 0.35), B['vinyl']],
           lp=5000, name='4-lofi-lobby', title='Lo-fi Lobby')


os.makedirs(OUT, exist_ok=True)
for fn in (sys.argv[2:] or ['dusk', 'arcade', 'neon', 'lofi']): {'night': lambda: lofi(64, True)}.get(fn, globals().get(fn))()
