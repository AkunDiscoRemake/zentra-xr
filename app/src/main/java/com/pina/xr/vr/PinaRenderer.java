/*
 * Pina XR - renderer da GLSurfaceView estéreo. Ponte entre os frames da
 * câmera, o estado das mãos e o loop nativo do Cardboard.
 */
package com.pina.xr.vr;

import android.opengl.GLSurfaceView;

import com.pina.xr.PinaActivity;
import com.pina.xr.camera.CameraFrameBus;
import com.pina.xr.hands.HandTrackingManager;

import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class PinaRenderer implements GLSurfaceView.Renderer {

  private final WeakReference<PinaActivity> activityRef;
  private final GLSurfaceView glView;
  private final CameraFrameBus frameBus = new CameraFrameBus();

  private ByteBuffer frameBuffer;

  public PinaRenderer(PinaActivity activity, GLSurfaceView glView) {
    this.activityRef = new WeakReference<>(activity);
    this.glView = glView;
  }

  /** Barramento de frames da câmera (thread da câmera -> GL thread). */
  public CameraFrameBus frameBus() {
    return frameBus;
  }

  @Override
  public void onSurfaceCreated(GL10 gl, EGLConfig config) {
    PinaActivity activity = activityRef.get();
    if (activity == null) return;
    activity.nativeOnSurfaceCreated(activity.nativeAppHandle());
    // Contexto EGL novo: recria a textura OES do video se havia um video.
    activity.onGlSurfaceCreated();
  }

  @Override
  public void onSurfaceChanged(GL10 gl, int width, int height) {
    PinaActivity activity = activityRef.get();
    if (activity == null) return;
    activity.nativeOnSurfaceChanged(activity.nativeAppHandle(), width, height);
  }

  @Override
  public void onDrawFrame(GL10 gl) {
    PinaActivity activity = activityRef.get();
    if (activity == null) return;
    final long app = activity.nativeAppHandle();

    // 1. Frame mais recente da câmera -> textura do passthrough.
    CameraFrameBus.LatestFrame frame = frameBus.glConsume();
    if (frame != null && frame.bitmap != null) {
      int needed = frame.bitmap.getWidth() * frame.bitmap.getHeight() * 4;
      if (frameBuffer == null || frameBuffer.capacity() < needed) {
        frameBuffer =
            ByteBuffer.allocateDirect(needed).order(ByteOrder.nativeOrder());
      }
      frameBuffer.rewind();
      frame.bitmap.copyPixelsToBuffer(frameBuffer);
      frameBuffer.rewind();
      activity.nativeUpdateCameraFrame(
          app, frameBuffer, frame.bitmap.getWidth(), frame.bitmap.getHeight());
      frameBus.releaseGl();
    }

    // 2. Estado mais recente das mãos (MediaPipe).
    HandTrackingManager.HandState hands = HandTrackingManager.consumeLatest();
    activity.nativeUpdateHands(
        app,
        hands == null ? null : hands.left,
        hands == null ? null : hands.right,
        hands != null && hands.pinchLeft,
        hands != null && hands.pinchRight);

    // 3. Frame estéreo (Cardboard).
    activity.nativeOnDrawFrame(app);
  }

  /** Agenda a atualização da página do navegador na GL thread. */
  public void schedulePageUpload(
      ByteBuffer rgba, int width, int height, float[] linkRects, String[] linkUrls) {
    PinaActivity activity = activityRef.get();
    if (activity == null || activity.nativeAppHandle() == 0) return;
    final long app = activity.nativeAppHandle();
    glView.queueEvent(
        () -> activity.nativeUpdatePage(app, rgba, width, height, linkRects, linkUrls));
  }

  // -------------------------------------------------------------------------
  // Pontes 0.2: apps do launcher e pipeline de video (chamadas de qualquer
  // thread; executam na GL thread).
  // -------------------------------------------------------------------------

  /** Abre um app do launcher (ver AppId no nativo). */
  public void scheduleOpenApp(int appId) {
    PinaActivity activity = activityRef.get();
    if (activity == null || activity.nativeAppHandle() == 0) return;
    final long app = activity.nativeAppHandle();
    glView.queueEvent(() -> activity.nativeOpenApp(app, appId));
  }

  /** Publica a lista de videos do celular no painel nativo. */
  public void scheduleSetVideoList(String[] titles, long[] ids, boolean havePermission) {
    PinaActivity activity = activityRef.get();
    if (activity == null || activity.nativeAppHandle() == 0) return;
    final long app = activity.nativeAppHandle();
    glView.queueEvent(
        () -> activity.nativeSetVideoList(app, titles, ids, havePermission));
  }

  /** Define a textura OES do video (0 = nenhuma). */
  public void scheduleSetVideoTexture(int textureId) {
    PinaActivity activity = activityRef.get();
    if (activity == null || activity.nativeAppHandle() == 0) return;
    final long app = activity.nativeAppHandle();
    glView.queueEvent(() -> activity.nativeSetVideoTexture(app, textureId));
  }

  /** Matriz de transformacao da SurfaceTexture (u_TexMatrix no shader). */
  public void scheduleVideoTransform(float[] matrix) {
    PinaActivity activity = activityRef.get();
    if (activity == null || activity.nativeAppHandle() == 0) return;
    final long app = activity.nativeAppHandle();
    final float[] copy = matrix.clone();
    glView.queueEvent(() -> activity.nativeUpdateVideoTransform(app, copy));
  }

  /** Info do player para o HUD (tamanho, duracao, posicao, tocando). */
  public void scheduleVideoInfo(
      int width, int height, long durationMs, long positionMs, boolean playing) {
    PinaActivity activity = activityRef.get();
    if (activity == null || activity.nativeAppHandle() == 0) return;
    final long app = activity.nativeAppHandle();
    glView.queueEvent(
        () ->
            activity.nativeSetVideoInfo(
                app, width, height, durationMs, positionMs, playing));
  }
}
