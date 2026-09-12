package com.neonforge.ui;

import android.graphics.Canvas;
import android.graphics.Color;

import com.neonforge.Game;
import com.neonforge.core.Settings;
import com.neonforge.render.NeonFont;
import com.neonforge.render.Renderer;
import com.neonforge.render.Sprites;

import java.util.ArrayList;

/** UISystem: start menu, how-to/tutorial, settings, pause and game-over screens. */
public final class Ui {

    public static final int A_NONE = 0;
    public static final int A_START = 1;
    public static final int A_SETTINGS = 2;
    public static final int A_HOWTO = 3;
    public static final int A_CONTINUE = 4;
    public static final int A_RESTART = 5;
    public static final int A_QUIT = 6;
    public static final int A_BACK = 7;
    public static final int A_TUT_PREV = 8;
    public static final int A_TUT_NEXT = 9;
    public static final int A_AUTODETECT = 10;

    private final UiKit kit = new UiKit();
    private final UiKit.Btn startBtn = new UiKit.Btn();
    private final UiKit.Btn settingsBtn = new UiKit.Btn();
    private final UiKit.Btn howtoBtn = new UiKit.Btn();
    private final UiKit.Btn continueBtn = new UiKit.Btn();
    private final UiKit.Btn restartBtn = new UiKit.Btn();
    private final UiKit.Btn quitBtn = new UiKit.Btn();
    private final UiKit.Btn backBtn = new UiKit.Btn();
    private final UiKit.Btn autoBtn = new UiKit.Btn();
    private final UiKit.Btn prevBtn = new UiKit.Btn();
    private final UiKit.Btn nextBtn = new UiKit.Btn();
    private final UiKit.Btn tutStartBtn = new UiKit.Btn();

    private int tutPage = 0;
    private float scroll = 0f;
    private int dragRow = -1;

    private static final String[][] TUT = {
            {"APONTAR", "Use o dedo indicador para mirar e selecionar objetos."},
            {"PINCH", "Junte o polegar e o indicador para selecionar ou agarrar."},
            {"AGARRAR", "Feche a mão para segurar objetos próximos."},
            {"ESCUDO", "Abra a palma da mão para criar um escudo de energia."},
            {"CARREGAR ENERGIA", "Feche a mão e mantenha o gesto para carregar energia."},
            {"DISPARAR", "Aponte para lançar a energia carregada."},
            {"DUAS MÃOS", "Use as duas mãos para manipular objetos maiores."},
    };
    private static final int[] TUT_GLYPH = {
            UiKit.G_POINT, UiKit.G_PINCH, UiKit.G_GRAB, UiKit.G_SHIELD,
            UiKit.G_ENERGY, UiKit.G_FIRE, UiKit.G_TWO
    };

    private int[] rowIds;          // logical id per settings row
    private int[] rowTypes;        // 0 cycle, 1 slider, 2 toggle
    private String[] rowLabels;

    public Ui() {
        // settings row table (fixed order)
        ArrayList<Integer> ids = new ArrayList<>();
        ArrayList<Integer> types = new ArrayList<>();
        ArrayList<String> labels = new ArrayList<>();
        ids.add(SettingsRow.QUALITY); types.add(0); labels.add("QUALIDADE GRÁFICA");
        ids.add(SettingsRow.FPS); types.add(0); labels.add("FPS");
        ids.add(SettingsRow.TRACK_SENS); types.add(1); labels.add("SENSIBILIDADE DO TRACKING");
        ids.add(SettingsRow.SMOOTH); types.add(1); labels.add("SUAVIZAÇÃO (SMOOTHING)");
        ids.add(SettingsRow.HAND_SIZE); types.add(1); labels.add("TAMANHO DAS MÃOS");
        ids.add(SettingsRow.HAND_X); types.add(1); labels.add("POSIÇÃO DAS MÃOS (X)");
        ids.add(SettingsRow.HAND_Y); types.add(1); labels.add("POSIÇÃO DAS MÃOS (Y)");
        ids.add(SettingsRow.FOV); types.add(1); labels.add("CAMPO DE VISÃO (FOV)");
        ids.add(SettingsRow.CAM_SENS); types.add(1); labels.add("SENSIBILIDADE DA CÂMERA");
        ids.add(SettingsRow.VOLUME); types.add(1); labels.add("VOLUME GERAL");
        ids.add(SettingsRow.SFX); types.add(1); labels.add("EFEITOS SONOROS");
        ids.add(SettingsRow.MUSIC); types.add(1); labels.add("MÚSICA");
        ids.add(SettingsRow.ECO); types.add(2); labels.add("MODO DE ECONOMIA DE DESEMPENHO");
        rowIds = toInt(ids); rowTypes = toInt(types); rowLabels = labels.toArray(new String[0]);
    }

    private static int[] toInt(ArrayList<Integer> l) {
        int[] a = new int[l.size()];
        for (int i = 0; i < a.length; i++) a[i] = l.get(i);
        return a;
    }

    public static final class SettingsRow {
        public static final int QUALITY = 0;
        public static final int FPS = 1;
        public static final int TRACK_SENS = 2;
        public static final int SMOOTH = 3;
        public static final int HAND_SIZE = 4;
        public static final int HAND_X = 5;
        public static final int HAND_Y = 6;
        public static final int FOV = 7;
        public static final int CAM_SENS = 8;
        public static final int VOLUME = 9;
        public static final int SFX = 10;
        public static final int MUSIC = 11;
        public static final int ECO = 12;
    }

    // ------------------------------------------------------------------
    // START MENU
    // ------------------------------------------------------------------
    public void drawMenu(Canvas c, Renderer r, Game g) {
        float w = r.bufferWidth(), h = r.bufferHeight();
        float t = g.timeSeconds();
        int cy = Sprites.C_CYAN;

        // drifting ambient glow
        r.particlePaint().setAlpha(80);
        c.drawBitmap(r.sprites().glowCyan, w * 0.12f - 60, h * 0.2f - 60 + (float) Math.sin(t * 0.5f) * 20, r.particlePaint());
        c.drawBitmap(r.sprites().glowMagenta, w * 0.82f - 60, h * 0.35f - 60 + (float) Math.cos(t * 0.4f) * 24, r.particlePaint());
        r.particlePaint().setAlpha(255);

        // two stylized hands behind the title
        kit.handGlyph(c, w * 0.3f, h * 0.34f, h * 0.16f, UiKit.G_POINT, t, Sprites.C_CYAN);
        kit.handGlyph(c, w * 0.7f, h * 0.34f, h * 0.16f, UiKit.G_SHIELD, t, Sprites.C_MAGENTA);

        // title
        r.font().draw(c, "NEON FORGE", w / 2f, h * 0.30f, h * 0.11f, cy, NeonFont.ALIGN_CENTER, 1f);
        r.font().draw(c, "CONTROLE SUAS MÃOS. CONTROLE A ENERGIA.", w / 2f, h * 0.40f,
                h * 0.026f, Color.WHITE, NeonFont.ALIGN_CENTER, 0.8f);

        // status line
        String status = g.cameraActive() ? "HAND TRACKING: ONLINE"
                : (g.cameraStatusMessage() != null ? g.cameraStatusMessage() : "HAND TRACKING: MODO TOQUE");
        r.font().draw(c, status, w / 2f, h * 0.455f, h * 0.02f,
                g.cameraActive() ? Sprites.C_GREEN : Sprites.C_AMBER, NeonFont.ALIGN_CENTER, 0.7f);

        // buttons
        float bw = w * 0.5f, bx = w / 2f - bw / 2f;
        float bh = Math.max(34f, h * 0.062f);
        startBtn.x = bx; startBtn.y = h * 0.55f; startBtn.w = bw; startBtn.h = bh;
        startBtn.label = "START"; startBtn.id = A_START;
        kit.button(c, r, startBtn, true, cy);

        settingsBtn.x = bx; settingsBtn.y = h * 0.55f + bh * 1.25f; settingsBtn.w = bw * 0.48f; settingsBtn.h = bh * 0.85f;
        settingsBtn.label = "CONFIGURAÇÕES"; settingsBtn.id = A_SETTINGS;
        kit.button(c, r, settingsBtn, false, Sprites.C_VIOLET);

        howtoBtn.x = bx + bw * 0.52f; howtoBtn.y = settingsBtn.y; howtoBtn.w = bw * 0.48f; howtoBtn.h = bh * 0.85f;
        howtoBtn.label = "COMO JOGAR"; howtoBtn.id = A_HOWTO;
        kit.button(c, r, howtoBtn, false, Sprites.C_AMBER);

        r.font().draw(c, "NEON FORGE • v1.0", w / 2f, h * 0.96f, h * 0.018f, cy, NeonFont.ALIGN_CENTER, 0.5f);
    }

    public int touchMenu(float x, float y, boolean down, Game g) {
        if (!down) return A_NONE;
        if (kit.hit(startBtn, x, y)) return A_START;
        if (kit.hit(settingsBtn, x, y)) return A_SETTINGS;
        if (kit.hit(howtoBtn, x, y)) return A_HOWTO;
        return A_NONE;
    }

    // ------------------------------------------------------------------
    // TUTORIAL / HOW-TO
    // ------------------------------------------------------------------
    public void drawTutorial(Canvas c, Renderer r, Game g) {
        float w = r.bufferWidth(), h = r.bufferHeight();
        float t = g.timeSeconds();

        r.font().draw(c, "COMO JOGAR", w / 2f, h * 0.07f, h * 0.05f, Sprites.C_CYAN, NeonFont.ALIGN_CENTER);

        float cw = w * 0.72f, cx = w / 2f - cw / 2f;
        float chh = h * 0.62f, cyy = h * 0.14f;
        kit.panel(c, cx, cyy, cw, chh, Sprites.C_CYAN, 0.9f);

        kit.handGlyph(c, w / 2f, cyy + chh * 0.34f, chh * 0.32f, TUT_GLYPH[tutPage], t, Sprites.C_CYAN);

        r.font().draw(c, TUT[tutPage][0], w / 2f, cyy + chh * 0.72f, h * 0.034f, Color.WHITE, NeonFont.ALIGN_CENTER);
        r.font().draw(c, TUT[tutPage][1], w / 2f, cyy + chh * 0.86f, h * 0.02f, Color.WHITE, NeonFont.ALIGN_CENTER, 0.8f);

        // pager arrows
        prevBtn.x = cx - h * 0.06f; prevBtn.y = cyy + chh * 0.3f; prevBtn.w = h * 0.05f; prevBtn.h = h * 0.1f;
        prevBtn.label = "<"; prevBtn.id = A_TUT_PREV;
        nextBtn.x = cx + cw + h * 0.01f; nextBtn.y = prevBtn.y; nextBtn.w = h * 0.05f; nextBtn.h = h * 0.1f;
        nextBtn.label = ">"; nextBtn.id = A_TUT_NEXT;
        kit.button(c, r, prevBtn, false, Sprites.C_VIOLET);
        kit.button(c, r, nextBtn, false, Sprites.C_VIOLET);

        // dots
        for (int i = 0; i < TUT.length; i++) {
            float dx = w / 2f + (i - (TUT.length - 1) / 2f) * h * 0.035f;
            r.particlePaint().setAlpha(i == tutPage ? 220 : 70);
            c.drawCircle(dx, cyy + chh + h * 0.035f, h * 0.009f, r.particlePaint());
        }
        r.particlePaint().setAlpha(255);

        r.font().draw(c, "Quando estiver pronto, pressione START.", w / 2f, h * 0.82f,
                h * 0.024f, Sprites.C_AMBER, NeonFont.ALIGN_CENTER);

        float bw = w * 0.4f, bx = w / 2f - bw / 2f;
        float bh = Math.max(34f, h * 0.062f);
        tutStartBtn.x = bx; tutStartBtn.y = h * 0.85f; tutStartBtn.w = bw; tutStartBtn.h = bh;
        tutStartBtn.label = "START"; tutStartBtn.id = A_START;
        kit.button(c, r, tutStartBtn, true, Sprites.C_CYAN);

        backBtn.x = w * 0.03f; backBtn.y = h * 0.03f; backBtn.w = w * 0.14f; backBtn.h = h * 0.05f;
        backBtn.label = "< VOLTAR"; backBtn.id = A_BACK;
        kit.button(c, r, backBtn, false, Sprites.C_AMBER);
    }

    public int touchTutorial(float x, float y, boolean down, Game g) {
        if (!down) return A_NONE;
        if (kit.hit(prevBtn, x, y)) { tutPage = (tutPage + TUT.length - 1) % TUT.length; return A_TUT_PREV; }
        if (kit.hit(nextBtn, x, y)) { tutPage = (tutPage + 1) % TUT.length; return A_TUT_NEXT; }
        if (kit.hit(tutStartBtn, x, y)) return A_START;
        if (kit.hit(backBtn, x, y)) return A_BACK;
        return A_NONE;
    }

    // ------------------------------------------------------------------
    // SETTINGS
    // ------------------------------------------------------------------
    public void drawSettings(Canvas c, Renderer r, Game g) {
        float w = r.bufferWidth(), h = r.bufferHeight();
        Settings s = g.settings();

        r.font().draw(c, "CONFIGURAÇÕES", w / 2f, h * 0.05f, h * 0.045f, Sprites.C_VIOLET, NeonFont.ALIGN_CENTER);

        backBtn.x = w * 0.03f; backBtn.y = h * 0.025f; backBtn.w = w * 0.14f; backBtn.h = h * 0.05f;
        backBtn.label = "< VOLTAR"; backBtn.id = A_BACK;
        kit.button(c, r, backBtn, false, Sprites.C_AMBER);

        float rowH = Math.max(22f, h * 0.044f);
        float top = h * 0.12f;
        float x0 = w * 0.06f, x1 = w * 0.94f;

        for (int i = 0; i < rowIds.length; i++) {
            float ry = top + i * (rowH + 6) - scroll;
            if (ry < h * 0.08f || ry > h * 0.94f) continue;
            int color = (i % 2 == 0) ? Sprites.C_CYAN : Sprites.C_VIOLET;
            int type = rowTypes[i];
            String label = rowLabels[i];
            float val = getValue(s, rowIds[i]);
            float min = minFor(rowIds[i]), max = maxFor(rowIds[i]);
            if (type == 0) {
                String v = cycleLabel(s, rowIds[i]);
                r.font().draw(c, label, x0, ry + rowH * 0.7f, h * 0.02f, Color.WHITE, NeonFont.ALIGN_LEFT);
                r.font().draw(c, v, x1, ry + rowH * 0.7f, h * 0.02f, color, NeonFont.ALIGN_RIGHT);
            } else if (type == 2) {
                r.font().draw(c, label, x0, ry + rowH * 0.7f, h * 0.02f, Color.WHITE, NeonFont.ALIGN_LEFT);
                boolean on = s.performanceMode;
                r.font().draw(c, on ? "LIGADO" : "DESLIGADO", x1, ry + rowH * 0.7f, h * 0.02f,
                        on ? Sprites.C_GREEN : Sprites.C_RED, NeonFont.ALIGN_RIGHT);
            } else {
                kit.slider(c, r, x0, ry, x1 - x0, label, val, min, max, color);
            }
        }

        autoBtn.x = w * 0.06f; autoBtn.y = h * 0.90f; autoBtn.w = w * 0.52f; autoBtn.h = h * 0.055f;
        autoBtn.label = "DETECTAR QUALIDADE AUTOMATICAMENTE"; autoBtn.id = A_AUTODETECT;
        kit.button(c, r, autoBtn, false, Sprites.C_GREEN);
    }

    public int touchSettings(float x, float y, boolean down, boolean moved, Game g) {
        Settings s = g.settings();
        if (kit.hit(backBtn, x, y)) return down ? A_NONE : A_BACK;
        if (down && kit.hit(autoBtn, x, y)) return A_AUTODETECT;

        float h = g.renderer.bufferHeight();
        float rowH = Math.max(22f, h * 0.044f);
        float top = h * 0.12f;
        int idx = (int) ((y + scroll - top) / (rowH + 6));
        if (idx >= 0 && idx < rowIds.length) {
            int id = rowIds[idx];
            int type = rowTypes[idx];
            if (type == 0) {
                if (down) { cycleValue(s, id); s.save(); applySettings(g); }
                return A_NONE;
            } else if (type == 2) {
                if (down) { s.performanceMode = !s.performanceMode; s.save(); applySettings(g); }
                return A_NONE;
            } else if (type == 1) {
                if (down) dragRow = idx;
                if (!down && !moved) dragRow = -1;   // finger up
                if (dragRow == idx) {
                    float w0 = g.renderer.bufferWidth() * 0.06f;
                    float w1 = g.renderer.bufferWidth() * 0.94f;
                    float frac = clamp((x - w0) / (w1 - w0), 0f, 1f);
                    float val = minFor(id) + frac * (maxFor(id) - minFor(id));
                    setValue(s, id, val);
                    s.save();
                    applySettings(g);
                }
                return A_NONE;
            }
        }
        if (moved) {
            float delta = y - g.lastTouchY();
            float total = rowIds.length * (rowH + 6);
            float view = h * 0.82f;
            float maxScroll = Math.max(0f, total - view);
            scroll = clamp(scroll - delta, 0f, maxScroll);
        }
        return A_NONE;
    }

    public void endTouch() {
        dragRow = -1;
    }

    private static void applySettings(Game g) {
        Settings s = g.settings();
        s.applyQuality(s.quality == Settings.Q_AUTO ? Settings.Q_AUTO : s.quality);
        g.onSettingsChanged();
    }

    private static float minFor(int id) {
        switch (id) {
            case SettingsRow.TRACK_SENS: return 0.5f;
            case SettingsRow.SMOOTH: return 0.1f;
            case SettingsRow.HAND_SIZE: return 0.7f;
            case SettingsRow.HAND_X: return -0.3f;
            case SettingsRow.HAND_Y: return -0.3f;
            case SettingsRow.FOV: return 55f;
            case SettingsRow.CAM_SENS: return 0.4f;
            default: return 0f;
        }
    }

    private static float maxFor(int id) {
        switch (id) {
            case SettingsRow.TRACK_SENS: return 2f;
            case SettingsRow.SMOOTH: return 0.9f;
            case SettingsRow.HAND_SIZE: return 1.5f;
            case SettingsRow.HAND_X: return 0.3f;
            case SettingsRow.HAND_Y: return 0.3f;
            case SettingsRow.FOV: return 100f;
            case SettingsRow.CAM_SENS: return 2f;
            default: return 1f;
        }
    }

    private static float getValue(Settings s, int id) {
        switch (id) {
            case SettingsRow.TRACK_SENS: return s.trackingSensitivity;
            case SettingsRow.SMOOTH: return s.smoothing;
            case SettingsRow.HAND_SIZE: return s.handScale;
            case SettingsRow.HAND_X: return s.handOffsetX;
            case SettingsRow.HAND_Y: return s.handOffsetY;
            case SettingsRow.FOV: return s.fov;
            case SettingsRow.CAM_SENS: return s.cameraSensitivity;
            case SettingsRow.VOLUME: return s.masterVolume;
            case SettingsRow.SFX: return s.sfxVolume;
            case SettingsRow.MUSIC: return s.musicVolume;
            default: return 0f;
        }
    }

    private static void setValue(Settings s, int id, float v) {
        switch (id) {
            case SettingsRow.TRACK_SENS: s.trackingSensitivity = v; break;
            case SettingsRow.SMOOTH: s.smoothing = v; break;
            case SettingsRow.HAND_SIZE: s.handScale = v; break;
            case SettingsRow.HAND_X: s.handOffsetX = v; break;
            case SettingsRow.HAND_Y: s.handOffsetY = v; break;
            case SettingsRow.FOV: s.fov = v; break;
            case SettingsRow.CAM_SENS: s.cameraSensitivity = v; break;
            case SettingsRow.VOLUME: s.masterVolume = v; break;
            case SettingsRow.SFX: s.sfxVolume = v; break;
            case SettingsRow.MUSIC: s.musicVolume = v; break;
        }
    }

    private static void cycleValue(Settings s, int id) {
        if (id == SettingsRow.QUALITY) {
            // AUTO -> LOW -> MEDIUM -> HIGH -> ULTRA -> AUTO
            s.quality = (s.quality >= Settings.Q_ULTRA) ? Settings.Q_AUTO : s.quality + 1;
        } else if (id == SettingsRow.FPS) {
            s.fpsCap = (s.fpsCap >= 60) ? 30 : 60;
        }
    }

    private static String cycleLabel(Settings s, int id) {
        if (id == SettingsRow.QUALITY) return s.qualityName();
        if (id == SettingsRow.FPS) return s.fpsCap + " FPS";
        return "";
    }

    // ------------------------------------------------------------------
    // PAUSE
    // ------------------------------------------------------------------
    public void drawPause(Canvas c, Renderer r, Game g) {
        float w = r.bufferWidth(), h = r.bufferHeight();
        // dim
        c.drawColor(Color.argb(150, 0, 0, 0));

        r.font().draw(c, "PAUSADO", w / 2f, h * 0.16f, h * 0.06f, Sprites.C_CYAN, NeonFont.ALIGN_CENTER);

        float bw = w * 0.44f, bx = w / 2f - bw / 2f;
        float bh = Math.max(36f, h * 0.07f);
        float y = h * 0.30f;

        continueBtn.x = bx; continueBtn.y = y; continueBtn.w = bw; continueBtn.h = bh;
        continueBtn.label = "CONTINUAR"; continueBtn.id = A_CONTINUE;
        kit.button(c, r, continueBtn, true, Sprites.C_CYAN);

        settingsBtn.x = bx; settingsBtn.y = y + bh * 1.25f; settingsBtn.w = bw; settingsBtn.h = bh;
        settingsBtn.label = "CONFIGURAÇÕES"; settingsBtn.id = A_SETTINGS;
        kit.button(c, r, settingsBtn, false, Sprites.C_VIOLET);

        howtoBtn.x = bx; howtoBtn.y = y + bh * 2.5f; howtoBtn.w = bw; howtoBtn.h = bh;
        howtoBtn.label = "COMO JOGAR"; howtoBtn.id = A_HOWTO;
        kit.button(c, r, howtoBtn, false, Sprites.C_AMBER);

        restartBtn.x = bx; restartBtn.y = y + bh * 3.75f; restartBtn.w = bw; restartBtn.h = bh;
        restartBtn.label = "REINICIAR"; restartBtn.id = A_RESTART;
        kit.button(c, r, restartBtn, false, Sprites.C_GREEN);

        quitBtn.x = bx; quitBtn.y = y + bh * 5f; quitBtn.w = bw; quitBtn.h = bh;
        quitBtn.label = "SAIR PARA O MENU"; quitBtn.id = A_QUIT;
        kit.button(c, r, quitBtn, false, Sprites.C_RED);
    }

    public int touchPause(float x, float y, boolean down, Game g) {
        if (!down) return A_NONE;
        if (kit.hit(continueBtn, x, y)) return A_CONTINUE;
        if (kit.hit(settingsBtn, x, y)) return A_SETTINGS;
        if (kit.hit(howtoBtn, x, y)) return A_HOWTO;
        if (kit.hit(restartBtn, x, y)) return A_RESTART;
        if (kit.hit(quitBtn, x, y)) return A_QUIT;
        return A_NONE;
    }

    // ------------------------------------------------------------------
    // GAME OVER
    // ------------------------------------------------------------------
    public void drawGameOver(Canvas c, Renderer r, Game g) {
        float w = r.bufferWidth(), h = r.bufferHeight();
        c.drawColor(Color.argb(150, 4, 2, 12));
        r.font().draw(c, "SISTEMA OFFLINE", w / 2f, h * 0.30f, h * 0.07f, Sprites.C_RED, NeonFont.ALIGN_CENTER);
        r.font().draw(c, "VOCÊ FOI DESATIVADO", w / 2f, h * 0.38f, h * 0.03f, Color.WHITE, NeonFont.ALIGN_CENTER);
        r.font().draw(c, "PONTOS: " + g.player().score, w / 2f, h * 0.44f, h * 0.028f, Sprites.C_AMBER, NeonFont.ALIGN_CENTER);

        float bw = w * 0.44f, bx = w / 2f - bw / 2f;
        float bh = Math.max(36f, h * 0.07f);
        restartBtn.x = bx; restartBtn.y = h * 0.52f; restartBtn.w = bw; restartBtn.h = bh;
        restartBtn.label = "REINICIAR"; restartBtn.id = A_RESTART;
        kit.button(c, r, restartBtn, true, Sprites.C_GREEN);
        quitBtn.x = bx; quitBtn.y = h * 0.52f + bh * 1.3f; quitBtn.w = bw; quitBtn.h = bh;
        quitBtn.label = "SAIR PARA O MENU"; quitBtn.id = A_QUIT;
        kit.button(c, r, quitBtn, false, Sprites.C_RED);
    }

    public int touchGameOver(float x, float y, boolean down, Game g) {
        if (!down) return A_NONE;
        if (kit.hit(restartBtn, x, y)) return A_RESTART;
        if (kit.hit(quitBtn, x, y)) return A_QUIT;
        return A_NONE;
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
