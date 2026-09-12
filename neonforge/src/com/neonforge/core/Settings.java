package com.neonforge.core;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Persisted game settings with quality presets (LOW / MEDIUM / HIGH / ULTRA / AUTO),
 * camera + hand-tracking tuning, audio volumes and performance mode.
 */
public final class Settings {

    public static final int Q_AUTO = -1;
    public static final int Q_LOW = 0;
    public static final int Q_MEDIUM = 1;
    public static final int Q_HIGH = 2;
    public static final int Q_ULTRA = 3;

    public static final String[] QUALITY_NAMES = {"LOW", "MEDIUM", "HIGH", "ULTRA"};

    // ---- persisted ----
    public int quality = Q_AUTO;          // chosen preset (Q_AUTO = automatic)
    public int fpsCap = 60;
    public float trackingSensitivity = 1.0f;   // hand -> aim multiplier
    public float smoothing = 0.55f;            // 0..1 hand smoothing strength
    public float handScale = 1.0f;
    public float handOffsetX = 0f;
    public float handOffsetY = 0f;
    public float masterVolume = 1.0f;
    public float sfxVolume = 1.0f;
    public float musicVolume = 0.7f;
    public boolean performanceMode = false;
    public float fov = 78f;
    public float cameraSensitivity = 1.0f;
    public float cameraSmoothing = 0.5f;

    // ---- derived from preset (cached) ----
    public float renderScale = 0.66f;
    public boolean bloom = true;
    public boolean wallShadows = true;
    public int particleDensity = 1;        // 0,1,2
    public float drawDistance = 18f;
    public int objectDensity = 1;

    private final SharedPreferences prefs;

    public Settings(Context ctx) {
        prefs = ctx.getSharedPreferences("neonforge_settings", Context.MODE_PRIVATE);
        load();
    }

    public void load() {
        quality = prefs.getInt("quality", Q_AUTO);
        fpsCap = prefs.getInt("fpsCap", 60);
        trackingSensitivity = prefs.getFloat("trackingSensitivity", 1.0f);
        smoothing = prefs.getFloat("smoothing", 0.55f);
        handScale = prefs.getFloat("handScale", 1.0f);
        handOffsetX = prefs.getFloat("handOffsetX", 0f);
        handOffsetY = prefs.getFloat("handOffsetY", 0f);
        masterVolume = prefs.getFloat("masterVolume", 1.0f);
        sfxVolume = prefs.getFloat("sfxVolume", 1.0f);
        musicVolume = prefs.getFloat("musicVolume", 0.7f);
        performanceMode = prefs.getBoolean("performanceMode", false);
        fov = prefs.getFloat("fov", 78f);
        cameraSensitivity = prefs.getFloat("cameraSensitivity", 1.0f);
        cameraSmoothing = prefs.getFloat("cameraSmoothing", 0.5f);
        applyQuality(quality);
    }

    public void save() {
        SharedPreferences.Editor e = prefs.edit();
        e.putInt("quality", quality);
        e.putInt("fpsCap", fpsCap);
        e.putFloat("trackingSensitivity", trackingSensitivity);
        e.putFloat("smoothing", smoothing);
        e.putFloat("handScale", handScale);
        e.putFloat("handOffsetX", handOffsetX);
        e.putFloat("handOffsetY", handOffsetY);
        e.putFloat("masterVolume", masterVolume);
        e.putFloat("sfxVolume", sfxVolume);
        e.putFloat("musicVolume", musicVolume);
        e.putBoolean("performanceMode", performanceMode);
        e.putFloat("fov", fov);
        e.putFloat("cameraSensitivity", cameraSensitivity);
        e.putFloat("cameraSmoothing", cameraSmoothing);
        e.apply();
    }

    /** Applies a preset (or Q_AUTO for the auto-detected one) and caches derived values. */
    public void applyQuality(int preset) {
        if (preset == Q_AUTO) preset = autoPreset();
        quality = preset;
        switch (preset) {
            case Q_LOW:
                renderScale = 0.42f; bloom = false; wallShadows = false;
                particleDensity = 0; drawDistance = 12f; objectDensity = 0;
                break;
            case Q_MEDIUM:
                renderScale = 0.6f; bloom = true; wallShadows = true;
                particleDensity = 1; drawDistance = 16f; objectDensity = 1;
                break;
            case Q_HIGH:
                renderScale = 0.8f; bloom = true; wallShadows = true;
                particleDensity = 2; drawDistance = 22f; objectDensity = 2;
                break;
            case Q_ULTRA:
            default:
                renderScale = 1.0f; bloom = true; wallShadows = true;
                particleDensity = 2; drawDistance = 30f; objectDensity = 3;
                break;
        }
        if (performanceMode) {
            // economy mode: cap effects regardless of preset
            if (preset == Q_ULTRA) renderScale = 0.8f;
            if (particleDensity > 1) particleDensity = 1;
            if (fpsCap > 30) fpsCap = 30;
        }
    }

    /** Cheap heuristic auto-detect; refined at runtime by the measured framerate. */
    public int autoPreset() {
        Runtime rt = Runtime.getRuntime();
        long max = rt.maxMemory() / (1024 * 1024);
        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        if (cores >= 8 && max >= 384) return Q_ULTRA;
        if (cores >= 6 && max >= 256) return Q_HIGH;
        if (cores >= 4) return Q_MEDIUM;
        return Q_LOW;
    }

    public String qualityName() {
        if (quality == Q_AUTO) return "AUTO";
        return QUALITY_NAMES[quality];
    }
}
