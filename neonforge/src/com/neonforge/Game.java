package com.neonforge;

import android.content.Context;

import com.neonforge.audio.AudioEngine;
import com.neonforge.camera.CameraSource;
import com.neonforge.combat.CombatSystem;
import com.neonforge.core.Settings;
import com.neonforge.core.Time;
import com.neonforge.hand.Gesture;
import com.neonforge.hand.GestureSystem;
import com.neonforge.hand.HandTracker;
import com.neonforge.render.ParticleSystem;
import com.neonforge.render.Raycaster;
import com.neonforge.render.Renderer;
import com.neonforge.render.Sprites;
import com.neonforge.ui.Ui;
import com.neonforge.world.Enemy;
import com.neonforge.world.Interaction;
import com.neonforge.world.Map;
import com.neonforge.world.Physics;
import com.neonforge.world.Pickup;
import com.neonforge.world.Player;
import com.neonforge.world.Projectile;

/** NEON FORGE — top-level game orchestrator and state machine. */
public final class Game implements Renderer.GameCtx {

    public static final int ST_MENU = 0;
    public static final int ST_TUTORIAL = 1;
    public static final int ST_PLAYING = 2;
    public static final int ST_PAUSED = 3;
    public static final int ST_SETTINGS = 4;
    public static final int ST_TRAINING = 5;
    public static final int ST_GAMEOVER = 6;

    public final Settings settings;
    private final Time time = new Time();
    private final Map map = new Map();
    private final Player player = new Player();
    private final Enemy[] enemies;
    private final Pickup[] pickups;
    private final Projectile[] projectiles = new Projectile[64];

    public final Renderer renderer = new Renderer();
    private final Raycaster raycaster = new Raycaster();
    private final HandTracker tracker = new HandTracker();
    private final GestureSystem gestures;
    private final CameraSource camera;
    private final AudioEngine audio = new AudioEngine();
    private final Ui ui = new Ui();
    private CombatSystem combat;

    public int state = ST_MENU;
    private boolean settingsFromPause = false;

    private float animTime = 0f;
    private float lastCharge = 0f;
    private Gesture prevGesture = Gesture.NONE;

    // runtime performance monitoring / auto-downgrade
    private float fpsAvg = 60f;
    private float slowTime = 0f;

    // camera / tracking
    private volatile boolean cameraActive = false;
    private boolean cameraGranted = false;
    private volatile String cameraStatusMessage = null;
    private long lastProcessMs = 0;

    // touch fallback
    private float touchAimX = 0f, touchAimY = 0f;
    private float aimScreenX = 0.5f, aimScreenY = 0.5f;
    private boolean touchCharge = false, touchShield = false, touchTap = false;
    private float tapTimer = 0f;
    private float tapPinchTimer = 0f;
    private float lastTouchY = 0f;
    private float touchDownX = 0.5f, touchDownY = 0.5f;

    // interaction
    private Pickup grabbed = null;
    private Pickup selected = null;
    private float aoeCooldown = 0f;

    // objectives
    private int wave = 0;
    private int coresDelivered = 0;
    private static final int CORES_GOAL = 5;
    public float reactorX = 0f, reactorY = 0f;

    // feedback
    private String toastText = null;
    private float toastTimer = 0f;

    public Game(Context ctx) {
        settings = new Settings(ctx);
        gestures = new GestureSystem(settings);

        int nEnemies = map.enemyCount;
        enemies = new Enemy[Math.max(1, nEnemies)];
        for (int i = 0; i < enemies.length; i++) enemies[i] = new Enemy();

        int nPickups = map.coreCount + map.batteryCount;
        pickups = new Pickup[Math.max(1, nPickups)];
        for (int i = 0; i < pickups.length; i++) pickups[i] = new Pickup();

        for (int i = 0; i < projectiles.length; i++) projectiles[i] = new Projectile();

        renderer.particles = new ParticleSystem(particleCapacity());
        combat = new CombatSystem(enemies, projectiles, pickups, player, map, renderer.particles, audio);

        camera = new CameraSource(new CameraSource.FrameListener() {
            @Override public void onFrame(byte[] nv21, int w, int h, long ts) {
                Game.this.onCameraFrame(nv21, w, h, ts);
            }
        }, new CameraSource.StatusListener() {
            @Override public void onStatus(int status, String msg) {
                Game.this.onCameraStatus(status, msg);
            }
        });

        reactorX = map.playerX; reactorY = map.playerY;
        cameraStatusMessage = "INICIANDO HAND TRACKING...";
        player.reset(map.playerX, map.playerY, map.playerAngle);
        spawnPickups();
        spawnWave();
    }

    // ------------------------------------------------------------------
    // lifecycle
    // ------------------------------------------------------------------
    public void initAudio(Context ctx) {
        audio.init(ctx);
        audio.setVolumes(settings.masterVolume, settings.sfxVolume, settings.musicVolume);
        audio.startMusic();
    }

    public void release() {
        camera.stop();
        audio.release();
    }

    public void setCameraStub(android.view.SurfaceHolder holder) {
        camera.setPreviewDisplay(holder);
    }

    public void onCameraStubReady() {
        if (cameraGranted && !cameraActive) camera.start();
    }

    public void onCameraPermissionGranted() {
        cameraGranted = true;
        if (cameraActive) return;
        camera.start();
    }

    public void onCameraPermissionDenied() {
        cameraGranted = false;
        cameraStatusMessage = "CÂMERA NEGADA — USANDO MODO TOQUE";
    }

    private void onCameraFrame(byte[] nv21, int w, int h, long ts) {
        if (state != ST_PLAYING && state != ST_TRAINING) return;
        long now = System.currentTimeMillis();
        if (now - lastProcessMs < 33) return;   // ~30 Hz processing
        lastProcessMs = now;
        tracker.processFrame(nv21, w, h, ts / 1_000_000L);
    }

    private void onCameraStatus(int status, String msg) {
        if (status == CameraSource.ST_READY) {
            cameraActive = true;
            cameraStatusMessage = null;
        } else if (status == CameraSource.ST_FAILED || status == CameraSource.ST_UNAVAILABLE
                || status == CameraSource.ST_DISCONNECTED) {
            cameraActive = false;
            cameraStatusMessage = "CÂMERA INDISPONÍVEL — USANDO MODO TOQUE";
        }
    }

    // ------------------------------------------------------------------
    // input
    // ------------------------------------------------------------------
    public void setViewSize(int w, int h) {
        renderer.setSize(w, h, settings.renderScale);
    }

    public void touchDown(float nx, float ny) {
        touchDownX = nx; touchDownY = ny;
        touchAimX = (nx - 0.5f) * 2f;
        touchAimY = (ny - 0.5f) * 2f;
        aimScreenX = nx; aimScreenY = ny;
        float bx = nx * renderer.bufferWidth(), by = ny * renderer.bufferHeight();
        lastTouchY = by;
        int action = dispatchTouch(bx, by, true, false);
        handleAction(action);

        if (state == ST_PLAYING || state == ST_TRAINING) {
            // gesture buttons (touch fallback only)
            if (nx > 0.86f && ny > 0.86f) { touchCharge = true; touchTap = false; }
            else if (nx < 0.14f && ny > 0.86f) { touchShield = true; touchTap = false; }
            else { touchTap = true; tapTimer = 0f; }
        }
    }

    public void touchMove(float nx, float ny) {
        touchAimX = (nx - 0.5f) * 2f;
        touchAimY = (ny - 0.5f) * 2f;
        aimScreenX = nx; aimScreenY = ny;
        float bx = nx * renderer.bufferWidth(), by = ny * renderer.bufferHeight();
        if (state == ST_SETTINGS) {
            dispatchTouch(bx, by, false, true);
        }
        lastTouchY = by;
        // cancel tap if the finger actually moved
        if (touchTap) {
            float dx = nx - touchDownX, dy = ny - touchDownY;
            if (dx * dx + dy * dy > 0.003f) touchTap = false;
        }
    }

    public void touchUp(float nx, float ny) {
        float bx = nx * renderer.bufferWidth(), by = ny * renderer.bufferHeight();
        lastTouchY = by;
        int action = dispatchTouch(bx, by, false, false);
        handleAction(action);
        boolean wasTap = touchTap;
        touchCharge = false;
        touchShield = false;
        touchTap = false;
        ui.endTouch();

        // touch fallback: tap = interact (pinch)
        if (wasTap && (state == ST_PLAYING || state == ST_TRAINING)) {
            if (!cameraActive) {
                // fire if we were charging
                if (prevSynthetic == Gesture.FIST && lastCharge > 0.15f) {
                    firePlayer(lastCharge);
                } else {
                    setSyntheticGesture(Gesture.PINCH);
                    tapPinchTimer = 0.3f;
                }
            }
        }
    }

    private Gesture prevSynthetic = Gesture.NONE;

    private int dispatchTouch(float bx, float by, boolean down, boolean moved) {
        switch (state) {
            case ST_MENU: return ui.touchMenu(bx, by, down, this);
            case ST_TUTORIAL: return ui.touchTutorial(bx, by, down, this);
            case ST_SETTINGS: return ui.touchSettings(bx, by, down, moved, this);
            case ST_PAUSED: return ui.touchPause(bx, by, down, this);
            case ST_GAMEOVER: return ui.touchGameOver(bx, by, down, this);
            default: return Ui.A_NONE;
        }
    }

    private void handleAction(int a) {
        switch (a) {
            case Ui.A_START:
                if (state == ST_MENU) { state = ST_TUTORIAL; audio.play(AudioEngine.S_SELECT); }
                else if (state == ST_TUTORIAL) { startPlay(); }
                break;
            case Ui.A_SETTINGS:
                settingsFromPause = (state == ST_PAUSED);
                state = ST_SETTINGS;
                audio.play(AudioEngine.S_CLICK);
                break;
            case Ui.A_HOWTO:
                settingsFromPause = (state == ST_PAUSED);
                state = ST_TUTORIAL;
                audio.play(AudioEngine.S_CLICK);
                break;
            case Ui.A_CONTINUE:
                state = ST_PLAYING;
                audio.play(AudioEngine.S_CLICK);
                break;
            case Ui.A_RESTART:
                if (state == ST_PAUSED || state == ST_GAMEOVER) startPlay();
                break;
            case Ui.A_QUIT:
                quitToMenu();
                break;
            case Ui.A_BACK:
                if (state == ST_TUTORIAL && settingsFromPause) {
                    state = ST_PAUSED;
                } else if (state == ST_SETTINGS && settingsFromPause) {
                    state = ST_PAUSED;
                } else {
                    state = ST_MENU;
                }
                audio.play(AudioEngine.S_CLICK);
                break;
            case Ui.A_AUTODETECT:
                runAutoDetect();
                break;
            default:
                break;
        }
    }

    public void onBack() {
        if (state == ST_PLAYING) {
            state = ST_PAUSED;
            audio.play(AudioEngine.S_CLICK);
        } else if (state == ST_TRAINING) {
            state = ST_PAUSED;
            audio.play(AudioEngine.S_CLICK);
        } else if (state == ST_PAUSED) {
            state = ST_PLAYING;
            audio.play(AudioEngine.S_CLICK);
        } else if (state == ST_SETTINGS) {
            state = settingsFromPause ? ST_PAUSED : ST_MENU;
            audio.play(AudioEngine.S_CLICK);
        } else if (state == ST_TUTORIAL) {
            state = settingsFromPause ? ST_PAUSED : ST_MENU;
            audio.play(AudioEngine.S_CLICK);
        }
    }

    private void startPlay() {
        state = ST_PLAYING;
        player.reset(map.playerX, map.playerY, map.playerAngle);
        wave = 0;
        coresDelivered = 0;
        grabbed = null;
        selected = null;
        spawnPickups();
        spawnWave();
        renderer.particles.clear();
        audio.play(AudioEngine.S_SELECT);
    }

    public void startTraining() {
        state = ST_TRAINING;
        player.reset(map.playerX, map.playerY, map.playerAngle);
        grabbed = null;
        selected = null;
        deactivateEnemies();
        renderer.particles.clear();
    }

    private void quitToMenu() {
        state = ST_MENU;
        grabbed = null;
        selected = null;
        audio.play(AudioEngine.S_CLICK);
    }

    private void deactivateEnemies() {
        for (Enemy e : enemies) e.alive = false;
    }

    private void spawnPickups() {
        int k = 0;
        for (int i = 0; i < map.coreCount && k < pickups.length; i++) {
            pickups[k].spawn(map.coreX[i], map.coreY[i], Pickup.TYPE_CORE);
            k++;
        }
        for (int i = 0; i < map.batteryCount && k < pickups.length; i++) {
            pickups[k].spawn(map.batteryX[i], map.batteryY[i], Pickup.TYPE_BATTERY);
            k++;
        }
        for (; k < pickups.length; k++) pickups[k].active = false;
    }

    private void spawnWave() {
        wave++;
        int toSpawn = Math.min(2 + wave, enemies.length);
        int sources = Math.max(1, map.enemyCount);
        int spawned = 0;
        for (int i = 0; i < enemies.length && spawned < toSpawn; i++) {
            enemies[i].spawn(map.enemyX[i % sources], map.enemyY[i % sources],
                    (i % 3 == 0) ? Enemy.TYPE_GUARD : Enemy.TYPE_DRONE);
            spawned++;
        }
        showToast("ONDA " + wave + " — ROBÔS DETECTADOS", 2.2f);
        audio.play(AudioEngine.S_DOOR);
    }

    private void runAutoDetect() {
        settings.quality = Settings.Q_AUTO;
        settings.applyQuality(Settings.Q_AUTO);
        settings.save();
        onSettingsChanged();
        showToast("QUALIDADE AUTOMÁTICA: " + settings.qualityName(), 2f);
        audio.play(AudioEngine.S_SELECT);
    }

    private int particleCapacity() {
        switch (settings.particleDensity) {
            case 0: return 128;
            case 2: return 768;
            default: return 384;
        }
    }

    // ------------------------------------------------------------------
    // update
    // ------------------------------------------------------------------
    public void update(float dt) {
        time.tick();
        animTime += dt;
        if (toastTimer > 0f) toastTimer -= dt;
        if (aoeCooldown > 0f) aoeCooldown -= dt;
        map.update(dt);
        if (tapTimer < 10f) tapTimer += dt;
        if (tapPinchTimer > 0f) tapPinchTimer -= dt;

        // gesture result
        GestureSystem.Result g;
        if (cameraActive) {
            g = gestures.update(tracker, System.currentTimeMillis());
        } else {
            g = updateSyntheticGesture();
        }

        switch (state) {
            case ST_MENU:
            case ST_TUTORIAL:
                ambientParticles(dt);
                break;
            case ST_PLAYING:
                updatePlaying(dt, g);
                break;
            case ST_TRAINING:
                updateTraining(dt, g);
                break;
            case ST_GAMEOVER:
                renderer.particles.update(dt, map);
                break;
            default:
                break;
        }

        // track charge for fire-on-release
        if (g.gesture == Gesture.FIST) lastCharge = g.charge;
        prevGesture = g.gesture;

        if (state == ST_PLAYING || state == ST_TRAINING) monitorPerf(dt);
    }

    private void monitorPerf(float dt) {
        if (dt <= 0f) return;
        float inst = 1f / dt;
        fpsAvg += (inst - fpsAvg) * 0.05f;
        if (fpsAvg < 20f) {
            slowTime += dt;
        } else {
            slowTime = 0f;
        }
        if (slowTime > 3f && settings.quality > Settings.Q_LOW && !settings.performanceMode) {
            settings.quality = Math.max(Settings.Q_LOW, settings.quality - 1);
            settings.applyQuality(settings.quality);
            settings.save();
            onSettingsChanged();
            showToast("DESEMPENHO BAIXO — QUALIDADE REDUZIDA PARA " + settings.qualityName(), 2.5f);
            slowTime = 0f;
            fpsAvg = 60f;
        }
    }

    private GestureSystem.Result currentResult;

    private GestureSystem.Result updateSyntheticGesture() {
        Gesture ge = Gesture.NONE;
        if (touchCharge) {
            ge = Gesture.FIST;
        } else if (touchShield) {
            ge = Gesture.OPEN_PALM;
        } else if (tapPinchTimer > 0f) {
            ge = Gesture.PINCH;
        } else {
            ge = Gesture.POINT;
        }
        prevSynthetic = ge;
        return gestures.synthesize(ge, touchAimX * 1.4f, touchAimY * 1.4f, ge == Gesture.PINCH ? 1f : 0f,
                false, System.currentTimeMillis());
    }

    private void setSyntheticGesture(Gesture g) {
        prevSynthetic = g;
    }

    private void updatePlaying(float dt, GestureSystem.Result g) {
        currentResult = g;
        if (!player.alive) {
            state = ST_GAMEOVER;
            audio.play(AudioEngine.S_EXPLODE);
            return;
        }

        // steering toward aim
        float turn = clamp(g.aimX, -1f, 1f) * 2.6f * settings.cameraSensitivity;
        player.angle += turn * dt;
        player.pitch = clamp(player.pitch + clamp(g.aimY, -1f, 1f) * 1.1f * dt, -0.9f, 0.9f);

        float dirX = (float) Math.cos(player.angle);
        float dirY = (float) Math.sin(player.angle);

        // fire on fist release
        if (prevGesture == Gesture.FIST && g.gesture != Gesture.FIST && lastCharge > 0.15f) {
            firePlayer(lastCharge);
            lastCharge = 0f;
        }

        // two-hands AoE
        if (g.twoHands && aoeCooldown <= 0f) {
            aoeCooldown = 3f;
            aoeBlast(dirX, dirY);
        }

        updateInteraction(dt, g, dirX, dirY);

        // movement (pinch on open space = walk forward)
        boolean doorAhead = Interaction.findDoor(map, player.x, player.y, dirX, dirY, 1.6f);
        boolean moving = false;
        if (g.gesture == Gesture.PINCH && selected == null && grabbed == null && !doorAhead) {
            moving = true;
        }
        if (g.twoHands) moving = false;

        if (moving) {
            float[] out = new float[2];
            map.collideMove(player.x, player.y, 0.26f, dirX * 2.1f * dt, dirY * 2.1f * dt, out);
            player.x = out[0]; player.y = out[1];
        }

        // open door ahead on pinch
        if (g.gesture == Gesture.PINCH && doorAhead) {
            int cx = (int) Math.floor(player.x + dirX * 1.1f);
            int cy = (int) Math.floor(player.y + dirY * 1.1f);
            if (map.typeAt(cx, cy) == Map.T_DOOR) {
                map.openDoor(cx, cy);
                audio.play(AudioEngine.S_DOOR);
            }
        }

        combat.update(dt, g, player.x, player.y, dirX, dirY);
        updatePickups(dt, g, dirX, dirY);
        renderer.particles.update(dt, map);
        player.update(dt);

        // reactor delivery
        checkReactor();

        // wave cleared
        if (combat.aliveEnemies() == 0) {
            spawnWave();
        }
    }

    private void updateTraining(float dt, GestureSystem.Result g) {
        currentResult = g;
        float dirX = (float) Math.cos(player.angle);
        float dirY = (float) Math.sin(player.angle);
        player.angle += clamp(g.aimX, -1f, 1f) * 2.6f * settings.cameraSensitivity * dt;
        player.pitch = clamp(player.pitch + clamp(g.aimY, -1f, 1f) * 1.1f * dt, -0.9f, 0.9f);

        // movement like playing, minus combat
        boolean doorAhead = Interaction.findDoor(map, player.x, player.y, dirX, dirY, 1.6f);
        if (g.gesture == Gesture.PINCH && selected == null && grabbed == null && !doorAhead) {
            float[] out = new float[2];
            map.collideMove(player.x, player.y, 0.26f, dirX * 2.1f * dt, dirY * 2.1f * dt, out);
            player.x = out[0]; player.y = out[1];
        }
        if (g.gesture == Gesture.PINCH && doorAhead) {
            int cx = (int) Math.floor(player.x + dirX * 1.1f);
            int cy = (int) Math.floor(player.y + dirY * 1.1f);
            if (map.typeAt(cx, cy) == Map.T_DOOR) {
                map.openDoor(cx, cy);
                audio.play(AudioEngine.S_DOOR);
            }
        }
        if (prevGesture == Gesture.FIST && g.gesture != Gesture.FIST && lastCharge > 0.15f) {
            firePlayer(lastCharge);
            lastCharge = 0f;
        }
        updateInteraction(dt, g, dirX, dirY);
        updatePickups(dt, g, dirX, dirY);
        renderer.particles.update(dt, map);
        player.update(dt);

        trainingStep(dt, g);
    }

    // ------------------------------------------------------------------
    // interaction
    // ------------------------------------------------------------------
    private void updateInteraction(float dt, GestureSystem.Result g, float dirX, float dirY) {
        if (grabbed != null && !grabbed.active) grabbed = null;

        if (grabbed == null) {
            Pickup target = Interaction.findTarget(pickups, player.x, player.y, dirX, dirY, 6f, 0.72f);
            if (g.gesture == Gesture.PINCH && target != null) {
                if (target.state == Pickup.S_IDLE || target.state == Pickup.S_SELECTED) {
                    target.select();
                    selected = target;
                    audio.play(AudioEngine.S_PINCH);
                }
            } else if (g.gesture != Gesture.PINCH && selected != null) {
                selected.deselect();
                selected = null;
            }
        }

        // pull selected toward hand
        if (selected != null) {
            if (selected.state == Pickup.S_SELECTED) {
                float dx = player.x - selected.x;
                float dy = player.y - selected.y;
                float dist = (float) Math.sqrt(dx * dx + dy * dy);
                if (dist > 0.7f) {
                    selected.state = Pickup.S_PULLED;
                    selected.vx = dx / Math.max(0.001f, dist) * 5.2f;
                    selected.vy = dy / Math.max(0.001f, dist) * 5.2f;
                } else {
                    doGrab(selected);
                }
            } else if (selected.state == Pickup.S_PULLED) {
                float dx = player.x - selected.x;
                float dy = player.y - selected.y;
                float dist = (float) Math.sqrt(dx * dx + dy * dy);
                if (dist < 0.6f) {
                    doGrab(selected);
                }
            }
        }

        // release grabbed: throw
        if (grabbed != null) {
            grabbed.x = player.x + dirX * 0.5f;
            grabbed.y = player.y + dirY * 0.5f;
            if (g.gesture != Gesture.PINCH) {
                float power = 6.5f + lastCharge * 7f;
                grabbed.throwFrom(player.x, player.y, dirX, dirY, power);
                audio.play(AudioEngine.S_SHOOT);
                grabbed = null;
                showToast("OBJETO LANÇADO", 1f);
            }
        }
    }

    private void doGrab(Pickup k) {
        if (k.type == Pickup.TYPE_BATTERY) {
            player.energy = Math.min(100f, player.energy + 40f);
            player.health = Math.min(100f, player.health + 30f);
            k.active = false;
            selected = null;
            audio.play(AudioEngine.S_PICKUP);
            renderer.particles.burst(k.x, k.y, 0.5f, 14, Sprites.C_GREEN, 2f, 0.5f, 0.09f);
            showToast("BATERIA COLETADA", 1.4f);
            return;
        }
        k.grab(player.x, player.y);
        grabbed = k;
        selected = null;
        player.energy = Math.min(100f, player.energy + 8f);
        audio.play(AudioEngine.S_SELECT);
    }

    private void updatePickups(float dt, GestureSystem.Result g, float dirX, float dirY) {
        for (Pickup k : pickups) {
            if (!k.active) continue;
            k.update(dt, map);
            // stop pulling if no longer pinching
            if (k.state == Pickup.S_PULLED && g.gesture != Gesture.PINCH) {
                k.state = Pickup.S_IDLE;
            }
        }
    }

    private void firePlayer(float charge) {
        float dirX = (float) Math.cos(player.angle);
        float dirY = (float) Math.sin(player.angle);
        combat.playerFire(charge, dirX, dirY, player.x + dirX * 0.4f, player.y + dirY * 0.4f);
        showToast("ENERGIA DISPARADA", 1f);
    }

    private void aoeBlast(float dirX, float dirY) {
        for (Enemy e : enemies) {
            if (!e.alive) continue;
            float dx = e.x - player.x, dy = e.y - player.y;
            float d = (float) Math.sqrt(dx * dx + dy * dy);
            if (d < 4.5f) {
                e.hp -= 55f;
                e.hitFlash = 1f;
                float inv = 1f / Math.max(0.01f, d);
                e.x += dx * inv * 1.2f;
                e.y += dy * inv * 1.2f;
                if (e.hp <= 0f) {
                    e.alive = false;
                    player.score += (e.type == Enemy.TYPE_GUARD) ? 250 : 120;
                }
            }
        }
        Interaction.openDoorsNear(map, player.x, player.y, 4f);
        renderer.particles.burst(player.x, player.y, 0.6f, 40, Sprites.C_CYAN, 4f, 0.7f, 0.14f);
        renderer.particles.burst(player.x, player.y, 0.6f, 30, Sprites.C_VIOLET, 3f, 0.6f, 0.1f);
        audio.play(AudioEngine.S_EXPLODE);
        showToast("PULSO DE ENERGIA", 1.4f);
    }

    private void checkReactor() {
        float dx = reactorX, dy = reactorY;
        for (Pickup k : pickups) {
            if (!k.active || k.type != Pickup.TYPE_CORE) continue;
            if (k == grabbed) continue;   // don't consume while being held
            float ddx = k.x - dx, ddy = k.y - dy;
            if (ddx * ddx + ddy * ddy < 0.8f * 0.8f) {
                k.active = false;
                coresDelivered++;
                player.score += 500;
                player.energy = Math.min(100f, player.energy + 25f);
                renderer.particles.burst(k.x, k.y, 0.7f, 26, Sprites.C_GREEN, 3f, 0.7f, 0.12f);
                audio.play(AudioEngine.S_PICKUP);
                showToast("NÚCLEO ENTREGUE NO REATOR", 1.6f);
                if (coresDelivered >= CORES_GOAL) {
                    coresDelivered = 0;
                    player.score += 1500;
                    showToast("INSTALAÇÃO ESTABILIZADA +1500", 2.2f);
                }
            }
        }
    }

    private void ambientParticles(float dt) {
        if (renderer.particles.count() < 60) {
            float a = (float) (Math.random() * Math.PI * 2);
            float r = 6f + (float) Math.random() * 6f;
            renderer.particles.spawn(
                    player.x + (float) Math.cos(a) * r,
                    player.y + (float) Math.sin(a) * r,
                    0.4f + (float) Math.random() * 1.4f,
                    0f, 0f, 0.2f + (float) Math.random() * 0.4f,
                    3f + (float) Math.random() * 3f,
                    0.06f,
                    (Math.random() < 0.5f) ? Sprites.C_CYAN : Sprites.C_MAGENTA, 0f);
        }
        renderer.particles.update(dt, map);
    }

    // ------------------------------------------------------------------
    // training
    // ------------------------------------------------------------------
    private int trainStep = 0;
    private float trainHold = 0f;

    private static final Gesture[] TRAIN_SEQ = {
            Gesture.POINT, Gesture.PINCH, Gesture.FIST, Gesture.OPEN_PALM, Gesture.FIST, Gesture.POINT, Gesture.TWO_HANDS
    };
    private static final String[] TRAIN_NAMES = {
            "APONTAR", "PINCH", "AGARRAR (FECHAR A MÃO)", "ESCUDO (PALMA ABERTA)",
            "CARREGAR ENERGIA", "DISPARAR", "DUAS MÃOS"
    };

    private void trainingStep(float dt, GestureSystem.Result g) {
        Gesture want = TRAIN_SEQ[trainStep % TRAIN_SEQ.length];
        boolean done = false;
        if (want == Gesture.TWO_HANDS) {
            done = g.twoHands;
        } else if (want == Gesture.FIST && trainStep % TRAIN_SEQ.length == 4) {
            done = (g.gesture == Gesture.FIST && g.charge > 0.6f);
        } else {
            done = (g.gesture == want);
        }
        if (done) {
            trainHold += dt;
            if (trainHold > 0.5f) {
                showToast("GESTO DETECTADO", 1.4f);
                audio.play(AudioEngine.S_SELECT);
                trainStep++;
                trainHold = 0f;
            }
        } else {
            trainHold = 0f;
        }
    }

    // ------------------------------------------------------------------
    // render
    // ------------------------------------------------------------------
    public void render(android.graphics.Canvas screen) {
        renderer.beginFrame();
        android.graphics.Canvas c = renderer.canvas();

        switch (state) {
            case ST_MENU:
                renderer.drawWorld(this);
                ui.drawMenu(c, renderer, this);
                break;
            case ST_TUTORIAL:
                renderer.drawWorld(this);
                ui.drawTutorial(c, renderer, this);
                break;
            case ST_PLAYING:
                renderer.drawWorld(this);
                renderer.drawHands(this);
                renderer.drawHud(this);
                break;
            case ST_TRAINING:
                renderer.drawWorld(this);
                renderer.drawHands(this);
                renderer.drawHud(this);
                drawTrainingPrompt(c);
                break;
            case ST_PAUSED:
                renderer.drawWorld(this);
                renderer.drawHands(this);
                renderer.drawHud(this);
                ui.drawPause(c, renderer, this);
                break;
            case ST_SETTINGS:
                renderer.drawWorld(this);
                ui.drawSettings(c, renderer, this);
                break;
            case ST_GAMEOVER:
                renderer.drawWorld(this);
                renderer.drawHands(this);
                ui.drawGameOver(c, renderer, this);
                break;
        }
        renderer.endFrame(screen);
    }

    private void drawTrainingPrompt(android.graphics.Canvas c) {
        int idx = trainStep % TRAIN_SEQ.length;
        float w = renderer.bufferWidth(), h = renderer.bufferHeight();
        renderer.font().draw(c, "TREINAMENTO — FAÇA O GESTO:", w / 2f, h * 0.10f,
                h * 0.03f, Sprites.C_AMBER, com.neonforge.render.NeonFont.ALIGN_CENTER);
        renderer.font().draw(c, TRAIN_NAMES[idx], w / 2f, h * 0.16f,
                h * 0.045f, Sprites.C_CYAN, com.neonforge.render.NeonFont.ALIGN_CENTER, 1f);
    }

    // ------------------------------------------------------------------
    // helpers / Renderer.GameCtx
    // ------------------------------------------------------------------
    private void showToast(String msg, float dur) {
        toastText = msg;
        toastTimer = dur;
    }

    public void onSettingsChanged() {
        renderer.setSize(renderer.screenWidth(), renderer.screenHeight(), settings.renderScale);
        renderer.particles = new ParticleSystem(particleCapacity());
        combat = new CombatSystem(enemies, projectiles, pickups, player, map, renderer.particles, audio);
        audio.setVolumes(settings.masterVolume, settings.sfxVolume, settings.musicVolume);
        settings.save();
    }

    public float timeSeconds() { return animTime; }

    public void setCameraMode(boolean active) {
        if (active && cameraGranted) camera.start();
    }

    // GameCtx impl
    @Override public Player player() { return player; }
    @Override public Map map() { return map; }
    @Override public Settings settings() { return settings; }
    @Override public Raycaster raycaster() { return raycaster; }
    @Override public Enemy[] enemies() { return enemies; }
    @Override public int enemyCount() { return enemies.length; }
    @Override public Pickup[] pickups() { return pickups; }
    @Override public int pickupCount() { return pickups.length; }
    @Override public Projectile[] projectiles() { return projectiles; }
    @Override public int projectileCount() { return projectiles.length; }
    @Override public GestureSystem.Result gesture() {
        if (currentResult == null) currentResult = gestures.update(tracker, System.currentTimeMillis());
        return currentResult;
    }
    @Override public HandTracker tracker() { return tracker; }
    @Override public boolean cameraActive() { return cameraActive; }
    @Override public boolean showSyntheticHand() { return !cameraActive && (state == ST_PLAYING || state == ST_TRAINING); }
    @Override public boolean showTouchHint() { return !cameraActive; }
    @Override public float aimScreenX() { return aimScreenX; }
    @Override public float aimScreenY() { return aimScreenY; }
    @Override public float reactorX() { return reactorX; }
    @Override public float reactorY() { return reactorY; }
    @Override public String objectiveText() {
        int alive = combat != null ? combat.aliveEnemies() : 0;
        return "ROBÔS: " + alive + "   NÚCLEOS: " + coresDelivered + "/" + CORES_GOAL;
    }
    @Override public String interactionHint() {
        if (state != ST_PLAYING && state != ST_TRAINING) return null;
        if (grabbed != null) return "SOLTE O PINCH PARA LANÇAR O OBJETO";
        if (selected != null) return "OBJETO SELECIONADO — PUXANDO...";
        if (currentResult != null && currentResult.gesture == Gesture.FIST) {
            if (currentResult.charge > 0.6f) return "ENERGIA PRONTA — APONTE E SOLTE";
            return "CARREGANDO ENERGIA...";
        }
        if (currentResult != null && currentResult.gesture == Gesture.OPEN_PALM) return "ESCUDO ATIVO";
        if (!cameraActive) return "TOQUE NO ALVO PARA INTERAGIR";
        return null;
    }
    @Override public String toast() {
        return toastTimer > 0f ? toastText : null;
    }
    public String cameraStatusMessage() { return cameraStatusMessage; }

    public float lastTouchY() { return lastTouchY; }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
