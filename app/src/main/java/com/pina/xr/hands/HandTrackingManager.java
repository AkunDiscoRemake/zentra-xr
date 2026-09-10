/*
 * Pina XR - hand tracking via MediaPipe Tasks Vision (Hand Landmarker).
 *
 * Recebe os frames da camera (RGBA), roda o modelo hand_landmarker.task em
 * modo LIVE_STREAM e publica o esqueleto (21 pontos por mao, normalizados)
 * + o estado do gesto pinch para o renderer.
 */
package com.pina.xr.hands;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.core.Delegate;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class HandTrackingManager {

  private static final String TAG = "HandTracking";
  private static final String MODEL_ASSET = "hand_landmarker.task";

  // Umbrais do gesto pinch (distancia polegar-indicador normalizada pelo
  // tamanho da mao), com histerese.
  private static final float PINCH_ON = 0.45f;
  private static final float PINCH_OFF = 0.65f;

  /** Estado imutavel publicado para o renderer. */
  public static class HandState {
    public final float[] left;        // 63 floats (21 pontos x,y,z) ou null
    public final float[] right;
    public final boolean pinchLeft;
    public final boolean pinchRight;

    HandState(float[] left, float[] right, boolean pinchLeft, boolean pinchRight) {
      this.left = left;
      this.right = right;
      this.pinchLeft = pinchLeft;
      this.pinchRight = pinchRight;
    }
  }

  private static final AtomicReference<HandState> latest = new AtomicReference<>();

  /** Renderer consome o estado mais recente (qualquer thread). */
  @Nullable
  public static HandState consumeLatest() {
    return latest.get();
  }

  private final Context context;
  private HandlerThread handThread;
  private Handler handHandler;
  private HandLandmarker landmarker;

  private float[] leftPoints;
  private float[] rightPoints;
  private boolean leftPinch = false;
  private boolean rightPinch = false;

  private boolean busy = false; // um frame por vez no landmarker
  private long lastTimestamp = 0;

  public HandTrackingManager(Context context) {
    this.context = context.getApplicationContext();
  }

  public void start() {
    if (handThread != null) return;
    handThread = new HandlerThread("PinaHands");
    handThread.start();
    handHandler = new Handler(handThread.getLooper());
    handHandler.post(this::setupLandmarker);
  }

  public void stop() {
    if (handHandler != null) {
      handHandler.post(
          () -> {
            if (landmarker != null) {
              landmarker.close();
              landmarker = null;
            }
          });
    }
    if (handThread != null) {
      handThread.quitSafely();
      handThread = null;
    }
    busy = false;
  }

  /** Chamado pela thread da camera para cada frame novo. */
  public void detect(Bitmap bitmap) {
    if (handHandler == null || landmarker == null || busy) return;
    // Processa no maximo ~15 fps para nao pesar a bateria/perf.
    final long now = SystemClock.uptimeMillis();
    if (now - lastTimestamp < 66) return;
    busy = true;
    lastTimestamp = now;
    final long timestamp = now;
    handHandler.post(
        () -> {
          try {
            MPImage image = new BitmapImageBuilder(bitmap).build();
            landmarker.detectAsync(image, timestamp);
          } catch (Exception e) {
            Log.w(TAG, "detectAsync falhou", e);
            busy = false;
          }
        });
  }

  // -------------------------------------------------------------------------

  private void setupLandmarker() {
    try {
      BaseOptions baseOptions =
          BaseOptions.builder().setDelegate(Delegate.CPU).setModelAssetPath(MODEL_ASSET).build();

      HandLandmarker.HandLandmarkerOptions options =
          HandLandmarker.HandLandmarkerOptions.builder()
              .setBaseOptions(baseOptions)
              .setMinHandDetectionConfidence(0.5f)
              .setMinHandPresenceConfidence(0.5f)
              .setMinTrackingConfidence(0.5f)
              .setNumHands(2)
              .setRunningMode(RunningMode.LIVE_STREAM)
              .setResultListener(this::onResults)
              .setErrorListener(
                  error -> Log.e(TAG, "landmarker: " + error.getMessage()))
              .build();

      landmarker = HandLandmarker.createFromOptions(context, options);
      Log.i(TAG, "HandLandmarker pronto (CPU, LIVE_STREAM)");
    } catch (Exception e) {
      Log.e(TAG, "falha ao iniciar HandLandmarker", e);
    }
  }

  private void onResults(@NonNull HandLandmarkerResult result, @NonNull MPImage input) {
    busy = false;
    if (result.landmarks().isEmpty()) {
      // Sem maos: publica estado vazio para esconder o esqueleto.
      latest.set(new HandState(null, null, false, false));
      return;
    }

    leftPoints = null;
    rightPoints = null;
    leftPinch = false;
    rightPinch = false;

    for (int h = 0; h < result.landmarks().size(); h++) {
      List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark> lm =
          result.landmarks().get(h);
      if (lm.size() < 21) continue;

      float[] pts = new float[63];
      for (int i = 0; i < 21; i++) {
        com.google.mediapipe.tasks.components.containers.NormalizedLandmark p = lm.get(i);
        pts[i * 3] = p.x();
        pts[i * 3 + 1] = p.y();
        pts[i * 3 + 2] = p.z();
      }

      boolean pinch = computePinch(pts);

      // A camera frontal SEM espelho inverte a lateralidade reportada pelo
      // MediaPipe (que assume entrada "selfie" espelhada): "Left" = mao direita.
      String label = null;
      if (result.handedness() != null
          && result.handedness().size() > h
          && !result.handedness().get(h).isEmpty()) {
        label = result.handedness().get(h).get(0).categoryName();
      }      boolean isRightHand = "Left".equals(label);

      if (isRightHand) {
        rightPoints = pts;
        rightPinch = pinch;
      } else {
        leftPoints = pts;
        leftPinch = pinch;
      }
    }

    latest.set(new HandState(leftPoints, rightPoints, leftPinch, rightPinch));
  }

  private boolean computePinch(float[] pts) {
    final float thumbX = pts[4 * 3];
    final float thumbY = pts[4 * 3 + 1];
    final float indexX = pts[8 * 3];
    final float indexY = pts[8 * 3 + 1];
    final float dx = indexX - thumbX;
    final float dy = indexY - thumbY;
    final float pinchDist = (float) Math.sqrt(dx * dx + dy * dy);

    // Escala da mao: distancia punho -> base do medio (0..9).
    final float sx = pts[9 * 3] - pts[0];
    final float sy = pts[9 * 3 + 1] - pts[1];
    final float handScale = (float) Math.max(1e-4, Math.sqrt(sx * sx + sy * sy));

    final float ratio = pinchDist / handScale;
    if (rightPinch || leftPinch) {
      return ratio < PINCH_OFF; // histerese na saida
    }
    return ratio < PINCH_ON; // histerese na entrada
  }
}
