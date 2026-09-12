package com.neonforge.audio;

/**
 * Procedural audio synthesis: generates every SFX and the ambient music
 * waveform at runtime (zero asset files, tiny footprint, fully offline).
 */
public final class Synth {

    public static final int RATE = 22050;

    private Synth() {}

    /** Build a 16-bit mono WAV (RIFF header + PCM) from float samples in [-1,1]. */
    public static byte[] toWav(float[] samples) {
        int n = samples.length;
        int dataLen = n * 2;
        byte[] out = new byte[44 + dataLen];
        writeStr(out, 0, "RIFF");
        writeInt(out, 4, 36 + dataLen);
        writeStr(out, 8, "WAVE");
        writeStr(out, 12, "fmt ");
        writeInt(out, 16, 16);
        writeShort(out, 20, 1);              // PCM
        writeShort(out, 22, 1);              // mono
        writeInt(out, 24, RATE);
        writeInt(out, 28, RATE * 2);
        writeShort(out, 32, 2);
        writeShort(out, 34, 16);
        writeStr(out, 36, "data");
        writeInt(out, 40, dataLen);
        for (int i = 0; i < n; i++) {
            int v = (int) (clamp(samples[i], -1f, 1f) * 32767f);
            out[44 + i * 2] = (byte) (v & 0xFF);
            out[44 + i * 2 + 1] = (byte) ((v >> 8) & 0xFF);
        }
        return out;
    }

    private static void writeStr(byte[] b, int off, String s) {
        for (int i = 0; i < s.length(); i++) b[off + i] = (byte) s.charAt(i);
    }

    private static void writeInt(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >> 8) & 0xFF);
        b[off + 2] = (byte) ((v >> 16) & 0xFF);
        b[off + 3] = (byte) ((v >> 24) & 0xFF);
    }

    private static void writeShort(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >> 8) & 0xFF);
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static float env(int i, int n, float attack, float release) {
        int a = Math.max(1, (int) (n * attack));
        int r = Math.max(1, (int) (n * release));
        float e = 1f;
        if (i < a) e = (float) i / a;
        else if (i > n - r) e = (float) (n - i) / r;
        return e;
    }

    /** sine */
    public static float sin(double phase) {
        return (float) Math.sin(phase * 2.0 * Math.PI);
    }

    // ---------------- SFX generators ----------------

    public static float[] blip(float f0, float f1, float dur, float vol) {
        int n = (int) (RATE * dur);
        float[] s = new float[n];
        for (int i = 0; i < n; i++) {
            double t = (double) i / RATE;
            double f = f0 + (f1 - f0) * ((double) i / n);
            double ph = f * t;
            s[i] = sin(ph) * env(i, n, 0.05f, 0.35f) * vol * 0.6f;
        }
        return s;
    }

    public static float[] zap(float dur, float vol) {
        int n = (int) (RATE * dur);
        float[] s = new float[n];
        for (int i = 0; i < n; i++) {
            double t = (double) i / RATE;
            double f = 1400 - 900 * t;
            double ph = f * t;
            float saw = (float) (2.0 * (ph - Math.floor(ph)) - 1.0);
            s[i] = (saw * 0.5f + sin(ph * 1.001f) * 0.5f) * env(i, n, 0.02f, 0.6f) * vol;
        }
        return s;
    }

    public static float[] chargeSweep(float vol) {
        float dur = 1.1f;
        int n = (int) (RATE * dur);
        float[] s = new float[n];
        for (int i = 0; i < n; i++) {
            double t = (double) i / RATE;
            double f = 180 + 900 * t;
            double ph = f * t;
            s[i] = (sin(ph) * 0.6f + sin(ph * 0.5f) * 0.4f) * (float) Math.min(1.0, t * 4.0) * vol * 0.5f;
        }
        return s;
    }

    public static float[] shieldHum(float vol) {
        float dur = 0.5f;
        int n = (int) (RATE * dur);
        float[] s = new float[n];
        for (int i = 0; i < n; i++) {
            double t = (double) i / RATE;
            s[i] = (sin(110.0 * t) * 0.4f + sin(220.0 * t) * 0.3f + sin(440.0 * t) * 0.2f)
                    * env(i, n, 0.1f, 0.5f) * vol * 0.6f;
        }
        return s;
    }

    public static float[] impact(float vol) {
        float dur = 0.22f;
        int n = (int) (RATE * dur);
        float[] s = new float[n];
        for (int i = 0; i < n; i++) {
            double t = (double) i / RATE;
            double f = 90 - 40 * t;
            s[i] = (sin(f * t) * 0.5f + noise() * 0.5f) * env(i, n, 0.01f, 0.8f) * vol;
        }
        return s;
    }

    public static float[] explosion(float vol) {
        float dur = 0.6f;
        int n = (int) (RATE * dur);
        float[] s = new float[n];
        float lp = 0f;
        for (int i = 0; i < n; i++) {
            double t = (double) i / RATE;
            float white = noise() * 0.7f + sin((60 - 30 * t) * t) * 0.3f;
            lp += (white - lp) * 0.2f;
            s[i] = lp * env(i, n, 0.01f, 0.9f) * vol;
        }
        return s;
    }

    public static float[] pickup(float vol) {
        float dur = 0.35f;
        int n = (int) (RATE * dur);
        float[] s = new float[n];
        for (int i = 0; i < n; i++) {
            double t = (double) i / RATE;
            double f = 660 + 440 * t;
            s[i] = (sin(f * t) + sin(f * 2 * t) * 0.4f) * env(i, n, 0.05f, 0.6f) * vol * 0.5f;
        }
        return s;
    }

    public static float[] door(float vol) {
        float dur = 0.45f;
        int n = (int) (RATE * dur);
        float[] s = new float[n];
        float lp = 0f;
        for (int i = 0; i < n; i++) {
            double t = (double) i / RATE;
            float white = noise();
            lp += (white - lp) * 0.05f;
            s[i] = (lp * 0.8f + sin(70 * t) * 0.2f) * env(i, n, 0.05f, 0.7f) * vol * 0.7f;
        }
        return s;
    }

    public static float[] uiTick(float vol) {
        return blip(900, 1200, 0.06f, vol);
    }

    public static float noise() {
        return (float) (Math.random() * 2.0 - 1.0);
    }

    // ---------------- ambient music (streaming) ----------------

    private static final double[] CHORDS = {
            220.00, 261.63, 329.63, 440.00,   // Am
            174.61, 220.00, 261.63, 349.23,   // F
            196.00, 246.94, 293.66, 392.00,   // G
            130.81, 164.81, 196.00, 261.63    // C
    };
    private static final double[] ARP = {
            220.00, 261.63, 329.63, 440.00, 523.25, 440.00, 329.63, 261.63,
            174.61, 220.00, 261.63, 349.23, 440.00, 349.23, 261.63, 220.00,
            196.00, 246.94, 293.66, 392.00, 493.88, 392.00, 293.66, 246.94,
            130.81, 164.81, 196.00, 261.63, 329.63, 261.63, 196.00, 164.81
    };

    /** Fill `buf` with the next chunk of ambient music. */
    public static void musicChunk(float[] buf, double time) {
        double chordDur = 2.0;
        int chordIdx = (int) ((time / chordDur) % 4);
        int chordBase = chordIdx * 4;
        double chordT = time % chordDur;

        for (int i = 0; i < buf.length; i++) {
            double t = time + (double) i / RATE;
            double ct = t % chordDur;
            int ci = (int) ((t / chordDur) % 4) * 4;

            // pad: two detuned sines per chord note + slow lfo
            float pad = 0f;
            for (int k = 0; k < 4; k++) {
                double f = CHORDS[ci + k];
                pad += (float) (Math.sin(2 * Math.PI * f * t) * 0.25f);
                pad += (float) (Math.sin(2 * Math.PI * f * 1.003 * t) * 0.15f);
            }
            double amp = 0.5 + 0.5 * Math.sin(2 * Math.PI * 0.05 * t);
            pad *= (float) (amp * 0.14f);

            // arpeggio: 16th-note plucks with decay
            int step = (int) (t * 8.0) % 32;
            double stepT = t * 8.0 - Math.floor(t * 8.0);
            double f = ARP[step];
            float pluck = (float) (Math.sin(2 * Math.PI * f * t) * Math.exp(-stepT * 6.0) * 0.10f);

            // airy filtered noise
            double noise = Math.random() * 2.0 - 1.0;
            float air = (float) (noise * 0.05f * (0.4 + 0.6 * amp));

            buf[i] = pad + pluck + air;
        }
    }
}
