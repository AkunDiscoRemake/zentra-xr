package com.neonforge;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

/** Entry activity: fullscreen immersive, camera permission flow, game host. */
public final class MainActivity extends Activity {

    private static final int REQ_CAMERA = 1001;

    private Game game;
    private GameView gameView;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        game = new Game(this);
        game.initAudio(this);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF04060E);

        // 1x1 camera preview stub (hidden behind the game surface)
        SurfaceView stub = new SurfaceView(this);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(1, 1);
        root.addView(stub, lp);
        game.setCameraStub(stub.getHolder());
        stub.getHolder().addCallback(new android.view.SurfaceHolder.Callback() {
            @Override public void surfaceCreated(android.view.SurfaceHolder h) {
                game.onCameraStubReady();
            }
            @Override public void surfaceChanged(android.view.SurfaceHolder h, int f, int w, int hh) {}
            @Override public void surfaceDestroyed(android.view.SurfaceHolder h) {}
        });

        gameView = new GameView(this, game);
        root.addView(gameView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        setContentView(root);
        hideSystemUi();

        // first boot: ask for camera permission before anything else
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            game.onCameraPermissionGranted();
        } else {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        }
    }

    private void hideSystemUi() {
        View v = getWindow().getDecorView();
        v.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code == REQ_CAMERA) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                game.onCameraPermissionGranted();
            } else {
                game.onCameraPermissionDenied();
            }
        }
    }

    @Override protected void onResume() {
        super.onResume();
        hideSystemUi();
        if (gameView != null) gameView.startLoop();
    }

    @Override protected void onPause() {
        super.onPause();
        if (gameView != null) gameView.stopLoop();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (game != null) game.release();
    }

    @Override public void onBackPressed() {
        game.onBack();
    }
}
