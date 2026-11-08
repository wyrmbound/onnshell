"""Usage: python shop.py OUT_DIR [window coin]
Original store music for OnnShell in the bright, jazzy "console shop channel" style (bossa nova, vibraphone, marimba).
All melodies and progressions here are written for OnnShell; nothing is quoted from any existing shop theme."""
import sys, os
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import make as m
from make import Track, mf, nm, t_, env, fm, flute, kick, snare, hat, filt, reverb, delay, master, rng


def vibes(f, sec, amp=0.3):
    """Vibraphone: a bell-like FM tone with a slow tremolo."""
    t = t_(sec)
    s = np.sin(2 * np.pi * f * t + 0.8 * np.exp(-t / 0.08) * np.sin(2 * np.pi * f * 4 * t))
    s += 0.25 * np.sin(2 * np.pi * f * 4 * t) * np.exp(-t / 0.15)
    return amp * s * np.exp(-t / 1.1) * (1 + 0.25 * np.sin(2 * np.pi * 5.5 * t)) * env(sec, 0.003, 0.08)


def marimba(f, sec=0.6, amp=0.3):
    t = t_(sec)
    s = np.sin(2 * np.pi * f * t) * np.exp(-t / 0.25) + 0.35 * np.sin(2 * np.pi * f * 3.93 * t) * np.exp(-t / 0.05)
    return amp * s * env(sec, 0.002, 0.05)


def pizz(f, sec, amp=0.55):
    t = t_(sec)
    return amp * (np.sin(2 * np.pi * f * t) + 0.3 * np.sin(4 * np.pi * f * t) * np.exp(-t / 0.08)) * np.exp(-t / 0.35) * env(sec, 0.004, 0.05)


def shaker(amp=0.05):
    t = t_(0.09); return amp * filt(rng.standard_normal(len(t)), lo=5000, hi=11000) * np.sin(np.pi * t / 0.09)


def rim(amp=0.2):
    t = t_(0.06)
    return amp * (np.sin(2 * np.pi * 1700 * t) * np.exp(-t / 0.012) + 0.4 * filt(rng.standard_normal(len(t)), lo=2000) * np.exp(-t / 0.01))


def snap(amp=0.25):
    t = t_(0.08); return amp * filt(rng.standard_normal(len(t)), lo=1800, hi=4500) * np.exp(-t / 0.018)


# A. Window Shopping: bossa nova, F major, 120 bpm -------------------------------
def window():
    tr = Track(120, 32)
    # (start beat in bar, root midi, voicing) per bar; bar 2 changes chord halfway
    prog = [[(0, 41, [53, 57, 60, 64])], [(0, 40, [52, 55, 58, 62]), (2, 45, [55, 57, 61, 64])], [(0, 38, [50, 53, 57, 60, 64])],
            [(0, 43, [53, 55, 59, 64])], [(0, 43, [53, 57, 58, 62])], [(0, 36, [52, 57, 58, 62])], [(0, 41, [53, 57, 60, 64, 67])],
            [(0, 36, [55, 58, 62, 65])]]
    A = [[('A5', .5, .5), ('C6', 1, .5), ('E6', 1.5, 1), ('D6', 2.5, .5), ('C6', 3, 1)],
         [('Bb5', 0, 1), ('G5', 1, .5), ('E5', 1.5, .5), ('C#6', 2, 1), ('A5', 3, 1)],
         [('F5', 0, .5), ('A5', .5, .5), ('C6', 1, .5), ('E6', 1.5, 1.5), ('D6', 3, .5), ('C6', 3.5, .5)],
         [('B5', 0, 1.5), ('A5', 1.5, .5), ('G5', 2, 2)],
         [('Bb5', .5, .5), ('D6', 1, .5), ('F6', 1.5, 1), ('E6', 2.5, .5), ('D6', 3, 1)],
         [('C6', 0, 1), ('A5', 1, .5), ('Bb5', 1.5, .5), ('G5', 2, 2)],
         [('A5', 0, .5), ('C6', .5, .5), ('G6', 1, 1), ('E6', 2, .5), ('C6', 2.5, .5), ('D6', 3, 1)],
         [('C6', 0, 3)]]
    A2 = A[:7] + [[('F5', 0, .5), ('G5', .5, .5), ('A5', 1, .5), ('C6', 1.5, 2.5)]]
    B = [[('E6', 0, 2), ('D6', 2, 1), ('C6', 3, 1)], [('D6', 0, 2), ('C#6', 2, 2)], [('F6', 0, 1.5), ('E6', 1.5, .5), ('D6', 2, 2)],
         [('D6', 0, 4)], [('D6', 0, 1.5), ('F6', 1.5, .5), ('E6', 2, 2)], [('Bb5', 0, 2), ('A5', 2, 1), ('G5', 3, 1)],
         [('A5', 0, 4)], [('G5', 0, 2), ('Bb5', 2, 2)]]
    song = A + A2 + B + A2
    for bar in range(32):
        b0 = bar * 4; chords = prog[bar % 8]
        for k, (cb, root, voi) in enumerate(chords):
            end = chords[k + 1][0] if k + 1 < len(chords) else 4
            for hb, l, v in ((0, .6, .45), (1.5, .4, .3), (2.5, .4, .3), (3, .8, .38)):
                if cb <= hb < end:
                    for j, n in enumerate(voi):
                        tr.put('keys', b0 + hb + j * 0.01, fm(mf(n), tr.secs(l) + 0.4, 1.0, 1.2, 0.25, 0.8) * v / 4, pan=-0.35 + 0.15 * j)
            fifth = root + 7
            pat = ((0, root, 1.4), (1.5, fifth, .45), (2, fifth, 1.4), (3.5, root, .45)) if end - cb == 4 else \
                  ((0, root, 1.4), (1.5, fifth, .45)) if cb == 0 else ((0, root, 1.4), (1.5, root + 7, .45))
            for pb, n, l in pat:
                tr.put('bass', b0 + cb + pb, pizz(mf(n), tr.secs(l), 0.5))
        for e in range(8): tr.put('drums', b0 + e * 0.5, shaker(0.05 if e % 2 else 0.035))
        for rb in ((0, 1.5, 3) if bar % 2 == 0 else (1, 2.5)): tr.put('drums', b0 + rb, rim(0.13))
        tr.put('drums', b0, kick(0.4, 0.15)); tr.put('drums', b0 + 2, kick(0.3, 0.15))
        for n, b, l in song[bar]:
            tr.put('lead', b0 + b, vibes(mf(nm(n)), tr.secs(l) + 0.6, 0.3), pan=0.2)
    Bs = tr.bus
    master(tr, [reverb(Bs['keys'], 1.8, 0.3), filt(Bs['bass'], hi=900), reverb(Bs['drums'], 1.0, 0.12),
                delay(reverb(Bs['lead'], 2.0, 0.3), tr.secs(0.75), 0.3, 0.18)], lp=10000,
           name='6-window-shopping', title='Window Shopping')


# B. Coin Jar: bouncy swing, C major, 132 bpm --------------------------------------
def coin():
    tr = Track(132, 32)
    sw = 0.66
    prog = [(36, [52, 55, 59, 60]), (33, [52, 55, 57, 61]), (38, [53, 57, 60, 62]), (43, [53, 55, 59, 62]),
            (40, [55, 59, 62, 64]), (33, [55, 57, 61, 64]), (38, [53, 57, 60, 62]), (36, [52, 55, 57, 60])]
    walks = [[36, 40, 43, 44], [45, 40, 43, 37], [38, 41, 45, 42], [43, 47, 38, 39], [40, 43, 47, 44], [45, 44, 43, 37],
             [38, 41, 43, 35], [36, 43, 40, 37]]
    A = [[('E5', 0, sw), ('G5', sw, 1 - sw), ('C6', 1, 1), ('B5', 2, sw), ('A5', 2 + sw, 1 - sw), ('G5', 3, 1)],
         [('C#6', 0, 1), ('E6', 1, sw), ('D6', 1 + sw, 1 - sw), ('C#6', 2, 1), ('A5', 3, 1)],
         [('F5', 0, sw), ('A5', sw, 1 - sw), ('D6', 1, 1), ('C6', 2, sw), ('A5', 2 + sw, 1 - sw), ('F5', 3, 1)],
         [('G5', 0, 1), ('B5', 1, 1), ('D6', 2, 2)],
         [('G5', 0, sw), ('B5', sw, 1 - sw), ('E6', 1, 1), ('D6', 2, sw), ('B5', 2 + sw, 1 - sw), ('G5', 3, 1)],
         [('A5', 0, sw), ('C#6', sw, 1 - sw), ('E6', 1, 1), ('G6', 2, 1), ('E6', 3, 1)],
         [('F6', 0, 1), ('D6', 1, 1), ('B5', 2, 1), ('G5', 3, 1)],
         [('C6', 0, 3)]]
    for bar in range(32):
        b0 = bar * 4; root, voi = prog[bar % 8]; sec = bar // 8
        for q, n in enumerate(walks[bar % 8]): tr.put('bass', b0 + q, pizz(mf(n), tr.secs(0.9), 0.55))
        for e in range(4):  # marimba chops on the swung off-beats
            for j, n in enumerate(voi): tr.put('keys', b0 + e + sw + j * 0.008, marimba(mf(n + 12), 0.4, 0.07), pan=-0.3 + 0.2 * j)
        tr.put('drums', b0 + 1, snap(0.22)); tr.put('drums', b0 + 3, snap(0.22))
        for e in range(4): tr.put('drums', b0 + e, shaker(0.05)); tr.put('drums', b0 + e + sw, shaker(0.035))
        tr.put('drums', b0, kick(0.35, 0.12))
        if sec == 2:  # middle: a marimba solo over the changes
            arp = sorted(voi) + [voi[0] + 12, voi[2] + 12]
            for s in range(8):
                n = arp[(s * 3 + bar) % len(arp)] + 12
                tr.put('lead2', b0 + s // 2 + (sw if s % 2 else 0), marimba(mf(n), 0.5, 0.22), pan=0.25)
            continue
        for n, b, l in A[bar % 8]:
            if sec == 1: tr.put('lead2', b0 + b, marimba(mf(nm(n)), 0.6, 0.3), pan=0.2)
            else: tr.put('lead', b0 + b, flute(mf(nm(n) + 0), tr.secs(l) * 0.95 + 0.05) * 0.22, pan=0.1)
    Bs = tr.bus
    master(tr, [filt(Bs['bass'], hi=1200), reverb(Bs['keys'], 1.2, 0.2), reverb(Bs['drums'], 0.8, 0.1),
                reverb(Bs['lead'], 1.5, 0.25), reverb(Bs['lead2'], 1.2, 0.2)], lp=11000,
           name='7-coin-jar', title='Coin Jar')


if __name__ == '__main__':
    os.makedirs(m.OUT, exist_ok=True)
    for fn in (sys.argv[2:] or ['window', 'coin']): globals()[fn]()
