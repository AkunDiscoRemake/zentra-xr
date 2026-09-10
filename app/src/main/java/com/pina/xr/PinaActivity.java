/*
 * Pina XR - beta 0.1
 *
 * Activity principal: 100% VR estéreo (Cardboard). Nenhuma UI 2D além do
 * GLSurfaceView estéreo. Integra:
 *  - Cardboard SDK (distorção, QR do visor, 3DOF)
 *  - Passthrough da câmera frontal (Camera2) para Mixed Reality
 *  - Hand tracking MediaPipe (esqueleto + pinch)
 *  - Navegador 3D (painéis dentro do mundo)
 */
package com.pina.xr;

import android.Manifest;
import android.content.pm.PackageManager;
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
import com.pina.xr.vr.PinaRenderer;

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

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    nativeApp = nativeOnCreate((AssetManager) getAssets());

    setContentView(R.layout.activity_pina);
    glView = findViewById(R.id.surface_view);
    glView.setEGLContextClientVersion(2);
    renderer = new PinaRenderer(this, glView);
    glView.setRenderer(renderer);
    glView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);

    handTracking = new HandTrackingManager(this);
    cameraReader = new CameraReader(this, frame -> handTracking.detect(frame));
    browser = new BrowserContentManager(renderer);

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

  @Override
  protected void onResume() {
    super.onResume();

    // Permissão de armazenamento (Android P ou anterior) para o perfil do
    // visor Cardboard - mesmo fluxo do sample oficial.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
        && !hasPermission(Manifest.permission.READ_EXTERNAL_STORAGE)) {
      requestPermissions(
          new String[] {Manifest.permission.READ_EXTERNAL_STORAGE});
      return;
    }

    // Câmera é obrigatória: passthrough MR + hand tracking.
    if (!hasPermission(Manifest.permission.CAMERA)) {
      requestPermissions(new String[] {Manifest.permission.CAMERA});
      return;
    }

    glView.onResume();
    nativeOnResume(nativeApp);
    cameraReader.setDisplayRotationDegrees(displayRotationDegrees());
    cameraReader.start();
    handTracking.start();
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
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    browser.cancel();
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

  // -------------------------------------------------------------------------

  void onPermissionsResult() {
    // Simplificação da beta: recomeça o fluxo do onResume.
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

  public long nativeAppHandle() {
    return nativeApp;
  }
}
