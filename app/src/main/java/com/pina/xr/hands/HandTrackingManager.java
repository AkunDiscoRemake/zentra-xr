/*
 * Pina XR - hand tracking via MediaPipe Tasks Vision (Hand Landmarker).
 *
 * Recebe os frames da camera (RGBA), roda o modelo hand_landmarker.task em
 * modo LIVE_STREAM e publica o esqueleto (21 pontos por mao, normalizados)
 * + o estado do gesto pinch para o renderer.
 *
 * Suavizacao dupla (pedido da 0.2), por landmark/eixo:
 *  1. Filtro One Euro (adaptativo: poco ruido quando parado, resposta rapida
 *     em movimentos) em x/y e um mais "mole" em z (profundidade ruidosa).
 *  2. Kalman de velocidade constante (2 estados por eixo) como suavizante
 *     final, que tambem prediz durante micro-gap de deteccao.
 *
 * Correcoes 0.2 (hand tracking nao funcionava na 0.1):
 *  - Copia o bitmap ANTES de enfileirar no MediaPipe (o bitmap do bus era
 *    sobrescrito pela camera enquanto o modelo ainda lia -> resultado lixo).
 *  - Watchdog: se o callback nunca chega, o flag busy e liberado (travava
 *    apos o primeiro erro).
 *  - Histerese do pinch por mao (era global: uma mao "sujava" a outra).
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

  // Parametros do One Euro Filter.
  private static final float ONE_EURO_MIN_CUTOFF_XY = 1.2f;
  private static final float ONE_EURO_BETA_XY = 0.05f;
  private static final float ONE_EURO_MIN_CUTOFF_Z = 0.6f;
  private static final float ONE_EURO_BETA_Z = 0.02f;
  private static final float ONE_EURO_D_CUTOFF = 1.0f;

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

  // -------------------------------------------------------------------------
  // Filtros
  // -------------------------------------------------------------------------

  /** One Euro Filter 1D (Low pass adaptativo). */
  private static final class OneEuroFilter {
    private float hatX = Float.NaN;       // valor filtrado
    private float hatDx = 0;              // derivada filtrada
    private long lastTime = Long.MIN_VALUE;
    private final float minCutoff;
    private final float beta;
    private final float dCutoff;

    OneEuroFilter(float minCutoff, float beta, float dCutoff) {
      this.minCutoff = minCutoff;
      this.beta = beta;
      this.dCutoff = dCutoff;
    }

    private static float alpha(float cutoff, float dt) {
      final float tau = 1.0f / (2.0f * (float) Math.PI * cutoff);
      return 1.0f / (1.0f + tau / dt);
    }

    float filter(float value, long timestampMs) {
      if (Float.isNaN(hatX)) {
        hatX = value;
        hatDx = 0;
        lastTime = timestampMs;
        return value;
      }
      float dt = (timestampMs - lastTime) / 1000.0f;
      lastTime = timestampMs;
      if (dt <= 0 || dt > 0.5f) {  // frames atrasados ou salto de pausa
        hatX = value;
        hatDx = 0;
        return value;
      }
      final float dx = (value - hatX) / dt;
      final float aD = alpha(dCutoff, dt);
      hatDx = hatDx + aD * (dx - hatDx);
      final float cutoff = minCutoff + beta * Math.abs(hatDx);
      final float a = alpha(cutoff, dt);
      hatX = hatX + a * (value - hatX);
      return hatX;
    }

    void reset() {
      hatX = Float.NaN;
      hatDx = 0;
      lastTime = Long.MIN_VALUE;
    }
  }

  /**
   * Kalman 1D com modelo de velocidade constante:
   * estado [p, v], medida z = p. Robusto como suavizante final.
   */
  private static final class VelocityKalman {
    private float p = Float.NaN;  // posicao estimada
    private float v = 0;          // velocidade estimada
    private float cov = 1;        // covariancia [2x2 compactada: pp, pv, vv]
    private float covPv = 0;
    private float covVv = 1;
    private final float processNoise;
    private final float measurementNoise;

    VelocityKalman(float processNoise, float measurementNoise) {
      this.processNoise = processNoise;
      this.measurementNoise = measurementNoise;
    }

    float update(float z, float dt) {
      if (Float.isNaN(p)) {
        p = z;
        v = 0;
        cov = 1;
        covPv = 0;
        covVv = 1;
        return z;
      }
      dt = Math.min(Math.max(dt, 1e-3f), 0.2f);
      // Predicao: p += v*dt; v igual. Propagacao da covariancia (F P F' + Q).
      final float np = p + v * dt;
      final float nPp = cov + dt * (covPv + covPv) + dt * dt * covVv + processNoise * dt;
      final float nPv = covPv + dt * covVv;
      final float nVv = covVv + processNoise * dt * 0.1f;
      p = np;
      cov = nPp;
      covPv = nPv;
      covVv = nVv;
      // Correcao (medicao da posicao).
      final float y = z - p;
      final float s = cov + measurementNoise;
      final float kP = cov / s;
      final float kV = covPv / s;
      p += kP * y;
      v += kV * y;
      final float ncov = (1 - kP) * cov;
      covPv = (1 - kP) * covPv;
      covVv -= kV * covPv;
      cov = ncov;
      return p;
    }

    void reset() {
      p = Float.NaN;
      v = 0;
      cov = 1;
      covPv = 0;
      covVv = 1;
    }
  }

  /** Conjunto de filtros de uma mao (21 landmarks x 3 eixos). */
  private static final class HandFilters {
    final OneEuroFilter[] euro = new OneEuroFilter[63];
    final VelocityKalman[] kalman = new VelocityKalman[63];
    long lastTs = Long.MIN_VALUE;

    HandFilters() {
      for (int i = 0; i < 21; i++) {
        // x, y: resposta cheia; z: mais suave (ruido de profundidade grande).
        euro[i * 3] = new OneEuroFilter(
            ONE_EURO_MIN_CUTOFF_XY, ONE_EURO_BETA_XY, ONE_EURO_D_CUTOFF);
        euro[i * 3 + 1] = new OneEuroFilter(
            ONE_EURO_MIN_CUTOFF_XY, ONE_EURO_BETA_XY, ONE_EURO_D_CUTOFF);
        euro[i * 3 + 2] = new OneEuroFilter(
            ONE_EURO_MIN_CUTOFF_Z, ONE_EURO_BETA_Z, ONE_EURO_D_CUTOFF);
        for (int a = 0; a < 3; a++) {
          kalman[i * 3 + a] = new VelocityKalman(
              a == 2 ? 40f : 90f, a == 2 ? 0.0025f : 0.0015f);
        }
      }
    }

    float[] apply(float[] raw, long ts) {
      float dt = 1.0f / 15.0f;
      if (lastTs != Long.MIN_VALUE) {
        dt = Math.min(Math.max((ts - lastTs) / 1000.0f, 1e-3f), 0.2f);
      }
      lastTs = ts;
      final float[] out = new float[63];
      for (int i = 0; i < 63; i++) {
        final float smoothed = euro[i].filter(raw[i], ts);
        out[i] = kalman[i].update(smoothed, dt);
      }
      return out;
    }

    void reset() {
      for (int i = 0; i < 63; i++) {
        euro[i].reset();
        kalman[i].reset();
      }
      lastTs = Long.MIN_VALUE;
    }
  }

  // -------------------------------------------------------------------------

  private final Context context;
  private HandlerThread handThread;
  private Handler handHandler;
  private HandLandmarker landmarker;

  private final HandFilters leftFilters = new HandFilters();
  private final HandFilters rightFilters = new HandFilters();

  // Histerese do pinch POR MAO.
  private boolean leftPinchSticky = false;
  private boolean rightPinchSticky = false;

  private boolean busy = false;        // um frame por vez no landmarker
  private long busySince = 0;          // watchdog do busy
  private long lastTimestamp = 0;
  private long lostFrames = 0;         // frames sem mao (para reter suavidade)

  // Copia do frame em processamento. E reciclada quando o PROXIMO frame e
  // aceito (o busy garante que o anterior ja foi consumido pelo modelo).
  private Bitmap pendingCopy;

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
              try {
                landmarker.close();
              } catch (Exception e) {
                Log.w(TAG, "close falhou", e);
              }
              landmarker = null;
            }
          });
    }
    if (handThread != null) {
      handThread.quitSafely();
      handThread = null;
    }
    handHandler = null;
    busy = false;
    if (pendingCopy != null) {
      pendingCopy.recycle();
      pendingCopy = null;
    }
  }

  /**
   * Chamado pela thread da camera para cada frame novo. Faz uma copia do
   * bitmap (o original e reusado pelo bus) e enfileira a deteccao.
   */
  public void detect(Bitmap sharedBitmap) {
    if (handHandler == null || landmarker == null || sharedBitmap == null) return;

    final long now = SystemClock.uptimeMillis();

    // Watchdog: se o landmarker nunca respondeu (frame dropado internamente
    // ou erro silencioso), libera o busy para o tracking nao morrer.
    if (busy && now - busySince > 1500) {
      Log.w(TAG, "watchdog: liberando busy preso");
      busy = false;
    }
    if (busy) return;
    // Processa no maximo ~15 fps para nao pesar a bateria/perf.
    if (now - lastTimestamp < 66) return;

    // Copia AGORA, na thread da camera: o bitmap do bus e sobrescrito no
    // proximo frame e o MediaPipe le de forma assincrona.
    Bitmap copy;
    try {
      copy = sharedBitmap.copy(sharedBitmap.getConfig(), false);
    } catch (Exception e) {
      Log.w(TAG, "copia do frame falhou", e);
      return;
    }

    busy = true;
    busySince = now;
    lastTimestamp = now;
    final long timestamp = now;
    // Libera a copia anterior (consumida: busy so reabre apos onResults/erro).
    if (pendingCopy != null && pendingCopy != copy) {
      pendingCopy.recycle();
    }
    pendingCopy = copy;
    handHandler.post(
        () -> {
          final HandLandmarker lm = landmarker;
          if (lm == null) {
            busy = false;
            return;
          }
          try {
            MPImage image = new BitmapImageBuilder(copy).build();
            lm.detectAsync(image, timestamp);
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
                  error -> {
                    Log.e(TAG, "landmarker: " + error.getMessage());
                    busy = false;  // erro nunca pode travar o pipeline
                  })
              .build();

      landmarker = HandLandmarker.createFromOptions(context, options);
      Log.i(TAG, "HandLandmarker pronto (CPU, LIVE_STREAM)");
    } catch (Exception e) {
      Log.e(TAG, "falha ao iniciar HandLandmarker", e);
      busy = false;
    }
  }

  private void onResults(@NonNull HandLandmarkerResult result, @NonNull MPImage input) {
    busy = false;
    final long ts = SystemClock.uptimeMillis();

    if (result.landmarks().isEmpty()) {
      lostFrames++;
      // Apos ~10 frames sem mao, esconde de verdade (retenta por um tempo
      // para nao piscar o esqueleto em micro-perdas).
      if (lostFrames > 10) {
        latest.set(new HandState(null, null, false, false));
        leftFilters.reset();
        rightFilters.reset();
      }
      return;
    }
    lostFrames = 0;

    float[] leftPoints = null;
    float[] rightPoints = null;
    boolean leftPinch = false;
    boolean rightPinch = false;

    for (int h = 0; h < result.landmarks().size(); h++) {
      List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark> lm =
          result.landmarks().get(h);
      if (lm.size() < 21) continue;

      float[] raw = new float[63];
      for (int i = 0; i < 21; i++) {
        com.google.mediapipe.tasks.components.containers.NormalizedLandmark p = lm.get(i);
        raw[i * 3] = p.x();
        raw[i * 3 + 1] = p.y();
        raw[i * 3 + 2] = p.z();
      }

      // A camera frontal SEM espelho inverte a lateralidade reportada pelo
      // MediaPipe (que assume entrada "selfie" espelhada): "Left" = mao direita.
      String label = null;
      if (result.handedness() != null
          && result.handedness().size() > h
          && !result.handedness().get(h).isEmpty()) {
        label = result.handedness().get(h).get(0).categoryName();
      }
      final boolean isRightHand = "Left".equals(label);

      // One Euro + Kalman, por landmark/eixo (só a mao atual).
      final float[] pts =
          (isRightHand ? rightFilters : leftFilters).apply(raw, ts);

      final boolean pinch =
          computePinch(pts, isRightHand ? rightPinchSticky : leftPinchSticky);
      if (isRightHand) {
        rightPoints = pts;
        rightPinchSticky = pinch;
        rightPinch = pinch;
      } else {
        leftPoints = pts;
        leftPinchSticky = pinch;
        leftPinch = pinch;
      }
    }

    latest.set(new HandState(leftPoints, rightPoints, leftPinch, rightPinch));
  }

  /** Distancia polegar-indicador normalizada pelo tamanho da mao. */
  private boolean computePinch(float[] pts, boolean wasPinching) {
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
    return wasPinching ? ratio < PINCH_OFF : ratio < PINCH_ON;
  }
}
