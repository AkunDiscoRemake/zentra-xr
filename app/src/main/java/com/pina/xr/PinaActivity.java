/*
 * Pina XR - beta 0.2 ("Quest caseira")
 *
 * Activity principal: 100% VR estéreo (Cardboard). Nenhuma UI 2D além do
 * GLSurfaceView estéreo. Integra:
 *  - Cardboard SDK (distorção, QR do visor, 3DOF)
 *  - Passthrough da câmera frontal (Camera2) para Mixed Reality
 *  - Hand tracking MediaPipe (esqueleto + pinch, filtros One Euro + Kalman)
 *  - Launcher estilo Meta Quest: Navegador 3D, Vídeos do celular
 *    (MediaStore + MediaPlayer -> textura OES), Vídeos 360°, Alvos 3D e
 *    Simon 3D (jogos)
 */
package com.pina.xr;

import android.Manifest;
import android.content.pm.PackageManager;
import android.util.Log;
import android.content.res.AssetManager;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.pina.xr.browser.BrowserContentManager;
import com.pina.xr.camera.CameraReader;
import com.pina.xr.hands.HandTrackingManager;
import com.pina.xr.video.VideoPlayerController;
import com.pina.xr.vr.PinaRenderer;

import java.io.File;
import java.io.FileWriter;
import java.util.List;

public class PinaActivity extends AppCompatActivity {

  private static final String TAG = "PinaActivity";
  private static final int PERMISSIONS_REQUEST_CODE = 2;

  static {
    System.loadLibrary("pina_jni");
  }

  private long nativeApp = 0;

  private GLSurfaceView glView;
  private PinaRenderer renderer;
  private CameraReader cameraReader;
  private HandTrackingManager handTracking;
  private BrowserContentManager browser;
  private VideoPlayerController videoPlayer;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    installCrashCatcher();

    nativeApp = nativeOnCreate((AssetManager) getAssets());

    setContentView(R.layout.activity_pina);
    glView = findViewById(R.id.surface_view);
    glView.setEGLContextClientVersion(2);
    renderer = new PinaRenderer(this, glView);
    glView.setRenderer(renderer);
    glView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);

    handTracking = new HandTrackingManager(this);
    // O bus de frames é o MESMO do renderer: passthrough e mãos bebem da
    // mesma fonte (na 0.1 o CameraReader publicava num bus próprio e nada
    // funcionava).
    cameraReader =
        new CameraReader(this, renderer.frameBus(), frame -> handTracking.detect(frame));
    browser = new BrowserContentManager(renderer);
    videoPlayer =
        new VideoPlayerController(
            this,
            new VideoPlayerController.NativeBridge() {
              @Override
              public void setVideoTexture(int oesTextureId) {
                renderer.scheduleSetVideoTexture(oesTextureId);
              }

              @Override
              public void updateVideoTransform(float[] matrix) {
                renderer.scheduleVideoTransform(matrix);
              }

              @Override
              public void updateVideoInfo(
                  int width, int height, long durationMs, long positionMs, boolean playing) {
                renderer.scheduleVideoInfo(width, height, durationMs, positionMs, playing);
              }
            });

    setImmersiveSticky();
    View decorView = getWindow().getDecorView();
    decorView.setOnSystemUiVisibilityChangeListener(
        (visibility) -> {
          if ((visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
            setImmersiveSticky();
          }
        });

    // Tela no brilho máximo (uso dentro do visor).
    WindowManager.LayoutParams layout = getWindow().getAttributes();
    layout.screenBrightness = 1.0f;
    getWindow().setAttributes(layout);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
  }

  /** Permissão de leitura de vídeo conforme a versão do Android. */
  private static String mediaReadPermission() {
    if (Build.VERSION.SDK_INT >= 33) {
      return Manifest.permission.READ_MEDIA_VIDEO;
    }
    return Manifest.permission.READ_EXTERNAL_STORAGE;
  }

  @Override
  protected void onResume() {
    super.onResume();

    // Permissão de armazenamento para o perfil do visor Cardboard (mesmo
    // fluxo do sample oficial) e para os vídeos do celular.
    final java.util.LinkedHashSet<String> needed = new java.util.LinkedHashSet<>();
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
        && !hasPermission(Manifest.permission.READ_EXTERNAL_STORAGE)) {
      needed.add(Manifest.permission.READ_EXTERNAL_STORAGE);
    }
    if (!hasPermission(mediaReadPermission())) {
      needed.add(mediaReadPermission());
    }
    // Câmera é obrigatória: passthrough MR + hand tracking.
    if (!hasPermission(Manifest.permission.CAMERA)) {
      needed.add(Manifest.permission.CAMERA);
    }
    if (!needed.isEmpty()) {
      requestPermissions(needed.toArray(new String[0]));
      return;
    }

    glView.onResume();
    try {
      nativeOnResume(nativeApp);
    } catch (Throwable t) {
      // NUNCA deixe o SDK do Cardboard (scanner/perfil) derrubar o app.
      Log.e(TAG, "nativeOnResume falhou; seguindo sem perfil novo", t);
    }
    cameraReader.setDisplayRotationDegrees(displayRotationDegrees());
    cameraReader.start();
    handTracking.start();
    videoPlayer.resume();
  }

  /** Contexto EGL recriado: o pipeline de video precisa de textura nova. */
  public void onGlSurfaceCreated() {
    if (videoPlayer != null && glView != null) {
      videoPlayer.onSurfaceRecreated(glView);
    }
  }

  /** Rotacao de exibicao em graus (aparelho travado em paisagem). */
  private int displayRotationDegrees() {
    switch (getWindowManager().getDefaultDisplay().getRotation()) {
      case android.view.Surface.ROTATION_0:
        return 0;
      case android.view.Surface.ROTATION_90:
        return 90;
      case android.view.Surface.ROTATION_180:
        return 180;
      case android.view.Surface.ROTATION_270:
        return 270;
      default:
        return 90;
    }
  }

  @Override
  protected void onPause() {
    super.onPause();
    nativeOnPause(nativeApp);
    glView.onPause();
    cameraReader.stop();
    handTracking.stop();
    browser.cancel();
    videoPlayer.pause();
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    browser.cancel();
    if (videoPlayer != null && glView != null) {
      videoPlayer.release(glView);
    }
    if (nativeApp != 0) {
      nativeOnDestroy(nativeApp);
      nativeApp = 0;
    }
  }

  @Override
  public void onWindowFocusChanged(boolean hasFocus) {
    super.onWindowFocusChanged(hasFocus);
    if (hasFocus) {
      setImmersiveSticky();
    }
  }

  @Override
  public boolean dispatchTouchEvent(@NonNull MotionEvent event) {
    if (event.getAction() == MotionEvent.ACTION_DOWN && nativeApp != 0) {
      // Botão do visor Cardboard (toque na tela) = clique.
      glView.queueEvent(() -> nativeOnTriggerEvent(nativeApp));
      return true;
    }
    return super.dispatchTouchEvent(event);
  }

  // -------------------------------------------------------------------------
  // Callbacks vindos do nativo (chamados na GL thread)
  // -------------------------------------------------------------------------

  /** Um link da página do navegador 3D foi "pinchado". */
  public void onNativeOpenUrl(String url) {
    runOnUiThread(() -> browser.load(url));
  }

  /** O usuário fechou o navegador (ou voltou ao início): cancela downloads. */
  public void onNativeCloseBrowser() {
    runOnUiThread(browser::cancel);
  }

  /** Botão SAIR do painel inicial. */
  public void onNativeExit() {
    runOnUiThread(this::finish);
  }

  /** Launcher pediu a lista de vídeos do celular. */
  public void onNativeRequestVideos() {
    runOnUiThread(
        () -> {
          final boolean granted =
              ActivityCompat.checkSelfPermission(this, mediaReadPermission())
                  == PackageManager.PERMISSION_GRANTED;
          final List<VideoPlayerController.VideoItem> videos =
              granted
                  ? VideoPlayerController.queryVideos(this)
                  : java.util.Collections.emptyList();
          final int n = videos.size();
          final String[] titles = new String[n];
          final long[] ids = new long[n];
          for (int i = 0; i < n; i++) {
            titles[i] = videos.get(i).title;
            ids[i] = videos.get(i).id;
          }
          renderer.scheduleSetVideoList(titles, ids, granted);
        });
  }

  /** Usuário pinchou um vídeo da lista (ou controle pediu replay). */
  public void onNativePlayVideo(long videoId, boolean is360) {
    runOnUiThread(() -> videoPlayer.play(videoId, glView));
  }

  /** Controles do player: 0=play/pause 1=-10s 2=+10s 3=stop. */
  public void onNativeVideoControl(int action) {
    runOnUiThread(() -> videoPlayer.control(action));
  }

  // -------------------------------------------------------------------------
  // Capturador de crash: escreve o stack em filesDir/pina-crash.txt. Na
  // proxima abertura o conteudo vai para o logcat (TAG PinaCrash) e o
  // arquivo fica guardado para o usuario enviar.
  // -------------------------------------------------------------------------

  private void installCrashCatcher() {
    final File crashFile = new File(getFilesDir(), "pina-crash.txt");
    final Thread.UncaughtExceptionHandler previous =
        Thread.getDefaultUncaughtExceptionHandler();
    Thread.setDefaultUncaughtExceptionHandler(
        (thread, throwable) -> {
          try {
            final String text =
                "thread=" + thread.getName()
                + "\n" + Log.getStackTraceString(throwable);
            try (FileWriter w = new FileWriter(crashFile, false)) {
              w.write(text);
            }
            Log.e("PinaCrash", "CRASH CAPTURADO\n" + text);
          } catch (Throwable ignored) {
            // nada mais a fazer
          }
          if (previous != null) {
            previous.uncaughtException(thread, throwable);
          }
        });

    if (crashFile.exists()) {
      try {
        final String text =
            new String(java.nio.file.Files.readAllBytes(crashFile.toPath()));
        Log.e("PinaCrash", "CRASH ANTERIOR\n" + text);
        final File kept = new File(getFilesDir(), "pina-crash-last.txt");
        if (!crashFile.renameTo(kept)) {
          crashFile.delete();
        }
      } catch (Throwable ignored) {
        // sem problema
      }
    }
  }

  // -------------------------------------------------------------------------

  void onPermissionsResult() {
    // Recomeça o fluxo do onResume.
    if (hasPermission(Manifest.permission.CAMERA)) {
      onResume();
    } else {
      Toast.makeText(this, R.string.camera_permission_rationale, Toast.LENGTH_LONG)
          .show();
      finish();
    }
  }

  private boolean hasPermission(String permission) {
    return ActivityCompat.checkSelfPermission(this, permission)
        == PackageManager.PERMISSION_GRANTED;
  }

  private void requestPermissions(String[] permissions) {
    ActivityCompat.requestPermissions(this, permissions, PERMISSIONS_REQUEST_CODE);
  }

  @Override
  public void onRequestPermissionsResult(
      int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    if (requestCode != PERMISSIONS_REQUEST_CODE) return;
    for (int i = 0; i < permissions.length; i++) {
      if (Manifest.permission.CAMERA.equals(permissions[i])
          && grantResults[i] != PackageManager.PERMISSION_GRANTED) {
        Toast.makeText(this, R.string.camera_permission_rationale, Toast.LENGTH_LONG)
            .show();
        finish();
        return;
      }
    }
    // Permissão de mídia negada não é fatal: o app abre e o painel de vídeos
    // mostra "sem permissão".
    onPermissionsResult();
  }

  private void setImmersiveSticky() {
    getWindow()
        .getDecorView()
        .setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
  }

  // -------------------------------------------------------------------------
  // Métodos nativos (públicos: também usados por PinaRenderer e uploads)
  // -------------------------------------------------------------------------

  public native long nativeOnCreate(AssetManager assetManager);

  public native void nativeOnDestroy(long app);

  public native void nativeOnSurfaceCreated(long app);

  public native void nativeOnSurfaceChanged(long app, int width, int height);

  public native void nativeOnDrawFrame(long app);

  public native void nativeOnTriggerEvent(long app);

  public native void nativeOnPause(long app);

  public native void nativeOnResume(long app);

  public native void nativeSwitchViewer(long app);

  public native void nativeUpdateHands(
      long app, float[] left, float[] right, boolean pinchLeft, boolean pinchRight);

  public native void nativeUpdateCameraFrame(
      long app, java.nio.ByteBuffer rgba, int width, int height);

  public native void nativeUpdatePage(
      long app, java.nio.ByteBuffer rgba, int width, int height,
      float[] linkRects, String[] linkUrls);

  // Beta 0.2: launcher e vídeo.
  public native void nativeOpenApp(long app, int appId);

  public native void nativeSetVideoList(
      long app, String[] titles, long[] ids, boolean havePermission);

  public native void nativeSetVideoTexture(long app, int oesTextureId);

  public native void nativeUpdateVideoTransform(long app, float[] matrix);

  public native void nativeSetVideoInfo(
      long app, int width, int height, long durationMs, long positionMs, boolean playing);

  public long nativeAppHandle() {
    return nativeApp;
  }
}
