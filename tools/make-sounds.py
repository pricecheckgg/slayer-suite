#!/usr/bin/env python3
"""Generate the bundled alert sounds (plain synthesised tones, 16-bit mono 22.05 kHz WAV).

Players can replace any of them by dropping a file of the same name into
~/.runelite/slayer-suite/sounds/.
"""
import math
import os
import struct
import wave

RATE = 22050
OUT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "src", "main", "resources", "com", "slayersuite", "sounds")


def tone(freq, ms, vol=0.5, attack=0.01, decay=0.6, harmonics=(1.0, 0.35, 0.15)):
    n = int(RATE * ms / 1000)
    out = []
    for i in range(n):
        t = i / RATE
        env = min(1.0, t / attack) * math.exp(-t / (ms / 1000 * decay))
        s = 0.0
        for k, h in enumerate(harmonics, start=1):
            s += h * math.sin(2 * math.pi * freq * k * t)
        out.append(vol * env * s / sum(harmonics))
    return out


def pulse(freq, ms, vol=0.35, duty=0.5, attack=0.004, decay=1.0, vibrato=0.0, glide_to=None):
    """A chiptune pulse wave. glide_to slides the pitch to that frequency over the note; vibrato is in Hz of depth."""
    n = int(RATE * ms / 1000)
    out = []
    phase = 0.0
    for i in range(n):
        t = i / RATE
        k = i / max(1, n - 1)
        f = freq if glide_to is None else freq * (glide_to / freq) ** k
        if vibrato:
            f += vibrato * math.sin(2 * math.pi * 6.0 * t)
        phase = (phase + f / RATE) % 1.0
        env = min(1.0, t / attack) * math.exp(-t / (ms / 1000 * decay))
        out.append(vol * env * (1.0 if phase < duty else -1.0))
    return out


def noise(ms, vol=0.3, decay=0.25, seed=7):
    """A short noise burst: the drum hit."""
    import random
    r = random.Random(seed)
    n = int(RATE * ms / 1000)
    return [vol * math.exp(-i / RATE / (ms / 1000 * decay)) * (r.random() * 2 - 1) for i in range(n)]


def mix(*tracks):
    n = max(len(t) for t in tracks)
    out = [0.0] * n
    for t in tracks:
        for i, v in enumerate(t):
            out[i] += v
    return out


def tremolo(samples, hz=11.0, depth=0.5):
    return [v * (1 - depth / 2 + depth / 2 * math.sin(2 * math.pi * hz * i / RATE)) for i, v in enumerate(samples)]


def silence(ms):
    return [0.0] * int(RATE * ms / 1000)


def write(name, samples):
    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, name)
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        frames = b"".join(struct.pack("<h", int(max(-1.0, min(1.0, s)) * 32767)) for s in samples)
        w.writeframes(frames)
    print(path, len(samples) / RATE, "s")


# Task done: a rising three-note chime.
write("task-done.wav", tone(523.25, 180) + tone(659.25, 180) + tone(783.99, 420, vol=0.55, decay=0.9))
# Superior spawned: an original 8-bit "wild encounter" jingle. Two fast falling zaps, then a driving
# E-minor arpeggio over a pulse bass with a drum hit, then a tremolo power chord. Nothing borrowed.
E2, E3, E4, G4, B4, D5, E5, FS5, G5 = 82.41, 164.81, 329.63, 392.0, 493.88, 587.33, 659.25, 739.99, 783.99
zap = pulse(1568.0, 130, vol=0.32, glide_to=392.0, decay=1.6) + pulse(1568.0, 130, vol=0.32, glide_to=392.0, decay=1.6)
arp_notes = [E4, B4, E5, G5, FS5, D5, B4, G4]
lead = []
for f in arp_notes:
    lead += pulse(f, 95, vol=0.30, decay=1.1, vibrato=4.0)
bass = []
for i in range(len(arp_notes)):
    bass += pulse(E2 if i % 2 == 0 else E3, 95, vol=0.22, duty=0.25, decay=1.3)
arp = mix(lead, bass, noise(60, vol=0.35, seed=3))
chord = tremolo(mix(pulse(E4, 520, vol=0.16, decay=0.8), pulse(G4, 520, vol=0.14, decay=0.8), pulse(B4, 520, vol=0.14, decay=0.8),
                    pulse(E5, 520, vol=0.12, decay=0.8), pulse(E2, 520, vol=0.18, duty=0.25, decay=0.8)))
chord = mix(chord, noise(80, vol=0.4, seed=11))
write("superior.wav", zap + silence(20) + arp + chord)
# Countdown tick for the last kills: one short bright blip.
write("tick.wav", tone(1320.0, 70, vol=0.4, attack=0.004, decay=1.2, harmonics=(1.0, 0.15)))
# Critical alert: a double beep.
write("alert.wav", tone(880.0, 140, vol=0.5, decay=1.5, harmonics=(1.0, 0.2)) + silence(90) + tone(880.0, 140, vol=0.5, decay=1.5, harmonics=(1.0, 0.2)))
