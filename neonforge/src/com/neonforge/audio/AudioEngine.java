package com.neonforge.audio;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioTrack;
import android.media.SoundPool;

import java.io.File;
import java.io.FileOutputStream;
import java.util.HashMap;

/**
 * AudioSystem: SFX via SoundPool (procedurally generated WAVs) + a streaming
 * generative ambient music loop via AudioTrack. All sounds are synthesized.
 */
public final class AudioEngine {

    public static final int S_CLICK = 0;
    public static final int S_PINCH = 1;
    public static final int S_SELECT = 2;
    public static final int S_CHARGE = 3;
    public static final int S_SHOOT = 4;
    public static final int S_SHIELD = 5;
    public static final int S_IMPACT = 6;
    public static final int S_EXPLODE = 7;
    public static final int S_PICKUP = 8;
    public static final int S_DOOR = 9;
    public static final int S_HURT = 10;

    private SoundPool pool;
    private final int[] soundIds = new int[11];
    private final HashMap<String, Integer> streamIds = new HashMap<>();

    private float master = 1f, sfx = 1f, music = 0.7f;
    private AudioTrack musicTrack;
    private Thread musicThread;
    private volatile boolean musicRunning = false;
    private boolean enabled = true;

    public void init(Context ctx) {
        try {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            pool = new SoundPool.Builder().setMaxStreams(10).setAudioAttributes(attrs).build();

            File dir = ctx.getCacheDir();
            soundIds[S_CLICK] = loadWav(pool, dir, "click", Synth.uiTick(1f));
            soundIds[S_PINCH] = loadWav(pool, dir, "pinch", Synth.blip(500, 900, 0.12f, 1f));
            soundIds[S_SELECT] = loadWav(pool, dir, "select", Synth.pickup(1f));
            soundIds[S_CHARGE] = loadWav(pool, dir, "charge", Synth.chargeSweep(1f));
            soundIds[S_SHOOT] = loadWav(pool, dir, "shoot", Synth.zap(0.3f, 1f));
            soundIds[S_SHIELD] = loadWav(pool, dir, "shield", Synth.shieldHum(1f));
            soundIds[S_IMPACT] = loadWav(pool, dir, "impact", Synth.impact(1f));
            soundIds[S_EXPLODE] = loadWav(pool, dir, "explode", Synth.explosion(1f));
            soundIds[S_PICKUP] = loadWav(pool, dir, "pickup", Synth.pickup(1f));
            soundIds[S_DOOR] = loadWav(pool, dir, "door", Synth.door(1f));
            soundIds[S_HURT] = loadWav(pool, dir, "hurt", Synth.impact(0.8f));
        } catch (Throwable t) {
            enabled = false;
        }
    }

    private int loadWav(SoundPool p, File dir, String name, float[] samples) {
        try {
            File f = new File(dir, "nf_" + name + ".wav");
            if (!f.exists()) {
                FileOutputStream fo = new FileOutputStream(f);
                fo.write(Synth.toWav(samples));
                fo.close();
            }
            return p.load(f.getAbsolutePath(), 1);
        } catch (Throwable t) {
            return 0;
        }
    }

    public void setVolumes(float master, float sfx, float music) {
        this.master = master;
        this.sfx = sfx;
        this.music = music;
    }

    public void play(int sound) {
        if (!enabled || pool == null) return;
        try {
            float v = master * sfx;
            pool.play(soundIds[sound], v, v, 1, 0, 1f);
        } catch (Throwable ignored) {}
    }

    public void startMusic() {
        if (!enabled || musicRunning) return;
        musicRunning = true;
        musicThread = new Thread(new Runnable() {
            @Override public void run() {
                streamMusic();
            }
        }, "neonforge-music");
        musicThread.start();
    }

    private void streamMusic() {
        int rate = Synth.RATE;
        int chunk = 2048;
        int minBuf = AudioTrack.getMinBufferSize(rate,
                android.media.AudioFormat.CHANNEL_OUT_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT);
        int bufSize = Math.max(minBuf, chunk * 4);
        try {
            musicTrack = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new android.media.AudioFormat.Builder()
                            .setSampleRate(rate)
                            .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(bufSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
        } catch (Throwable t) {
            musicRunning = false;
            return;
        }
        try {
            musicTrack.play();
        } catch (Throwable t) {
            musicRunning = false;
            return;
        }
        float[] samples = new float[chunk];
        short[] pcm = new short[chunk];
        double time = 0;
        while (musicRunning) {
            Synth.musicChunk(samples, time);
            float v = master * music;
            for (int i = 0; i < chunk; i++) {
                float s = samples[i] * v;
                if (s > 1f) s = 1f;
                if (s < -1f) s = -1f;
                pcm[i] = (short) (s * 32767f);
            }
            try {
                musicTrack.write(pcm, 0, chunk);
            } catch (Throwable t) {
                break;
            }
            time += (double) chunk / rate;
        }
        try { musicTrack.stop(); musicTrack.release(); } catch (Throwable ignored) {}
        musicTrack = null;
    }

    public void stopMusic() {
        musicRunning = false;
    }

    public void release() {
        stopMusic();
        try { if (pool != null) pool.release(); } catch (Throwable ignored) {}
        pool = null;
    }
}
