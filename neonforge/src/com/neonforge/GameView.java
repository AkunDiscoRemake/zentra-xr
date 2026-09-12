package com.neonforge;

import android.content.Context;
import android.graphics.Canvas;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

/** SurfaceView + dedicated render loop for the game. */
public final class GameView extends SurfaceView implements SurfaceHolder.Callback {

    private final Game game;
    private Thread thread;
    private volatile boolean running = false;
    private int viewW = 1, viewH = 1;

    public GameView(Context ctx, Game game) {
        super(ctx);
        this.game = game;
        getHolder().addCallback(this);
        setFocusable(true);
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {
        startLoop();
    }

    @Override public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        viewW = w; viewH = h;
        game.setViewSize(w, h);
    }

    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        stopLoop();
    }

    public void startLoop() {
        if (running) return;
        running = true;
        thread = new Thread(new Runnable() {
            @Override public void run() {
                loop();
            }
        }, "neonforge-loop");
        thread.start();
    }

    public void stopLoop() {
        running = false;
        try {
            if (thread != null) thread.join(500);
        } catch (InterruptedException ignored) {}
        thread = null;
    }

    private void loop() {
        long last = System.nanoTime();
        while (running) {
            long now = System.nanoTime();
            float dt = (now - last) / 1_000_000_000f;
            last = now;
            if (dt > 0.1f) dt = 0.1f;
            if (dt < 0f) dt = 0f;

            game.update(dt);

            Canvas c = null;
            try {
                c = getHolder().lockCanvas();
                if (c != null) {
                    game.render(c);
                }
            } finally {
                if (c != null) {
                    try { getHolder().unlockCanvasAndPost(c); } catch (Throwable ignored) {}
                }
            }

            // fps cap
            int cap = game.settings.fpsCap;
            long frameNanos = 1_000_000_000L / Math.max(1, cap);
            long elapsed = System.nanoTime() - now;
            long sleep = frameNanos - elapsed;
            if (sleep > 0) {
                try { Thread.sleep(sleep / 1_000_000L, (int) (sleep % 1_000_000L)); }
                catch (InterruptedException ignored) { break; }
            }
        }
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        float nx = e.getX() / Math.max(1, viewW);
        float ny = e.getY() / Math.max(1, viewH);
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                game.touchDown(nx, ny);
                return true;
            case MotionEvent.ACTION_MOVE:
                game.touchMove(nx, ny);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                game.touchUp(nx, ny);
                return true;
            default:
                return false;
        }
    }
}
