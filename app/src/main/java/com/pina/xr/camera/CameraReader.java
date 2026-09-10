/*
 * Pina XR - passthrough MR via Camera2 API.
 *
 * Abre a camera frontal, captura YUV_420_888 via ImageReader, converte para
 * RGBA (rotacionado para a orientacao de exibicao) e publica no frame bus
 * COMPARTILHADO (o mesmo do renderer: passthrough + hand tracking).
 *
 * Correcoes 0.2:
 *  - Usa o bus passado pelo renderer (na 0.1 publicava num bus proprio que
 *    ninguem consumia: passthrough e maos ficavam mortos).
 *  - Escolhe um tamanho suportado pela camera (640x480 era fixo e algumas
 *    cameras nao suportam: a sessao nem configurava).
 */
package com.pina.xr.camera;

import android.content.Context;
import android.graphics.Bitmap;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class CameraReader {

  private static final String TAG = "PinaCamera";
  private static final int PREFERRED_WIDTH = 640;
  private static final int PREFERRED_HEIGHT = 480;

  /** Chamado na thread da camera com o bitmap pronto (RGBA). */
  public interface FrameListener {
    void onFrame(Bitmap bitmap);
  }

  private final Context context;
  private final FrameListener listener;
  private final CameraFrameBus frameBus;  // compartilhado com o renderer

  private final Object publishLock = new Object();

  private HandlerThread cameraThread;
  private Handler cameraHandler;
  private CameraDevice camera;
  private CameraCaptureSession session;
  private android.media.ImageReader imageReader;

  private int sensorOrientation = 270;
  private int displayRotationDegrees = 90; // aparelho travado em landscape
  private int rotationDegrees = 0;
  private boolean frontCamera = true;
  private int captureWidth = PREFERRED_WIDTH;
  private int captureHeight = PREFERRED_HEIGHT;

  // Buffers de conversao (alocados no tamanho real da camera).
  private int[] sensorPixels = new int[0];
  private int[] rotatedPixels = new int[0];

  public CameraReader(
      Context context, CameraFrameBus sharedBus, FrameListener listener) {
    this.context = context;
    this.frameBus = sharedBus;
    this.listener = listener;
  }

  /** Rotacao de exibicao em graus (chamar antes de start()). */
  public void setDisplayRotationDegrees(int degrees) {
    displayRotationDegrees = degrees;
  }

  /** Barramento de frames para a GL thread. */
  public CameraFrameBus frameBus() {
    return frameBus;
  }

  public void start() {
    if (cameraThread != null) return;
    cameraThread = new HandlerThread("PinaCamera");
    cameraThread.start();
    cameraHandler = new Handler(cameraThread.getLooper());
    cameraHandler.post(this::openCamera);
  }

  public void stop() {
    try {
      if (session != null) {
        session.stopRepeating();
        session.close();
        session = null;
      }
      if (camera != null) {
        camera.close();
        camera = null;
      }
      if (imageReader != null) {
        imageReader.close();
        imageReader = null;
      }
    } catch (Exception e) {
      Log.w(TAG, "erro ao parar camera", e);
    }
    if (cameraThread != null) {
      cameraThread.quitSafely();
      cameraThread = null;
    }
  }

  // -------------------------------------------------------------------------

  private void openCamera() {
    try {
      CameraManager manager =
          (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
      String cameraId = findCamera(manager);
      if (cameraId == null) {
        Log.e(TAG, "nenhuma camera disponivel");
        return;
      }

      CameraCharacteristics characteristics =
          manager.getCameraCharacteristics(cameraId);
      Integer sensor = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
      sensorOrientation = sensor != null ? sensor : 270;
      Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
      frontCamera = facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT;
      rotationDegrees = computeRotationDegrees();

      Size size = pickCaptureSize(characteristics);
      captureWidth = size.getWidth();
      captureHeight = size.getHeight();
      Log.i(TAG, "camera " + cameraId + " capturando " + captureWidth + "x" + captureHeight);
      sensorPixels = new int[captureWidth * captureHeight];
      rotatedPixels = new int[captureWidth * captureHeight];

      imageReader =
          android.media.ImageReader.newInstance(
              captureWidth,
              captureHeight,
              android.graphics.ImageFormat.YUV_420_888,
              3);
      imageReader.setOnImageAvailableListener(this::onImageAvailable, cameraHandler);

      manager.openCamera(
          cameraId,
          new CameraDevice.StateCallback() {
            @Override
            public void onOpened(@NonNull CameraDevice device) {
              camera = device;
              try {
                List<Surface> outputs = new ArrayList<>();
                outputs.add(imageReader.getSurface());
                device.createCaptureSession(
                    outputs,
                    new CameraCaptureSession.StateCallback() {
                      @Override
                      public void onConfigured(@NonNull CameraCaptureSession s) {
                        session = s;
                        try {
                          CaptureRequest.Builder builder =
                              camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                          builder.addTarget(imageReader.getSurface());
                          session.setRepeatingRequest(builder.build(), null, cameraHandler);
                          Log.i(TAG, "passthrough MR ativo");
                        } catch (Exception e) {
                          Log.e(TAG, "setRepeatingRequest falhou", e);
                        }
                      }

                      @Override
                      public void onConfigureFailed(
                          @NonNull CameraCaptureSession s) {
                        Log.e(TAG, "falha ao configurar sessao da camera");
                      }
                    },
                    cameraHandler);
              } catch (Exception e) {
                Log.e(TAG, "createCaptureSession falhou", e);
              }
            }

            @Override
            public void onDisconnected(@NonNull CameraDevice device) {
              device.close();
            }

            @Override
            public void onError(@NonNull CameraDevice device, int error) {
              Log.e(TAG, "erro ao abrir camera: " + error);
              device.close();
            }
          },
          cameraHandler);
    } catch (SecurityException e) {
      Log.e(TAG, "permissao de camera negada", e);
    } catch (Exception e) {
      Log.e(TAG, "erro ao abrir camera", e);
    }
  }

  private String findCamera(CameraManager manager) {
    try {
      String fallback = null;
      for (String id : manager.getCameraIdList()) {
        CameraCharacteristics cc = manager.getCameraCharacteristics(id);
        Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
        if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
          return id;
        }
        if (fallback == null) fallback = id;
      }
      return fallback;
    } catch (Exception e) {
      Log.e(TAG, "erro listando cameras", e);
      return null;
    }
  }

  /**
   * Escolhe o menor tamanho suportado pela camera com area perto de 640x480
   * (bom para MediaPipe e para a CPU). Cai para o menor disponivel se a
   * preferida nao existir.
   */
  private Size pickCaptureSize(CameraCharacteristics characteristics) {
    try {
      android.hardware.camera2.params.StreamConfigurationMap map =
          characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
      Size[] sizes =
          map != null ? map.getOutputSizes(android.graphics.ImageFormat.YUV_420_888) : null;
      if (sizes == null || sizes.length == 0) {
        return new Size(PREFERRED_WIDTH, PREFERRED_HEIGHT);
      }
      Size best = null;
      long bestScore = Long.MAX_VALUE;
      for (Size s : sizes) {
        if (s.getWidth() * s.getHeight() > 1_200_000) continue; // nao precisa 4K
        final long score =
            (long) Math.abs(s.getWidth() * s.getHeight()
                - PREFERRED_WIDTH * PREFERRED_HEIGHT)
                * 10
                + Math.abs((long) s.getWidth() * PREFERRED_HEIGHT
                    - (long) s.getHeight() * PREFERRED_WIDTH)
                    * 100
                    / (PREFERRED_WIDTH * PREFERRED_HEIGHT + 1);
        if (score < bestScore) {
          bestScore = score;
          best = s;
        }
      }
      if (best == null) {
        best = sizes[0];
        for (Size s : sizes) {
          if (s.getWidth() * s.getHeight() < best.getWidth() * best.getHeight()) best = s;
        }
      }
      return best;
    } catch (Exception e) {
      Log.w(TAG, "nao consegui listar tamanhos; usando 640x480", e);
      return new Size(PREFERRED_WIDTH, PREFERRED_HEIGHT);
    }
  }

  /**
   * Rotacao necessaria para exibir o frame de pe, mesmo calculo do CameraX:
   * (sensorOrientation - displayRotation) e inversao para cameras frontais.
   */
  private int computeRotationDegrees() {
    int rotation = (sensorOrientation - displayRotationDegrees + 360) % 360;
    if (frontCamera) {
      rotation = (360 - rotation) % 360;
    }
    return rotation;
  }

  // -------------------------------------------------------------------------

  private void onImageAvailable(android.media.ImageReader reader) {
    android.media.Image image = null;
    try {
      image = reader.acquireLatestImage();
      if (image == null || listener == null) return;

      final int width = image.getWidth();
      final int height = image.getHeight();
      if (width * height != sensorPixels.length) return; // tamanho mudou: ignora
      android.media.Image.Plane[] planes = image.getPlanes();
      if (planes.length < 3) return;

      convertYuv420ToArgb(
          planes[0].getBuffer(),
          planes[1].getBuffer(),
          planes[2].getBuffer(),
          planes[0].getRowStride(),
          planes[1].getRowStride(),
          planes[1].getPixelStride(),
          width,
          height);

      final int rotation = rotationDegrees;
      final boolean swap = (rotation == 90 || rotation == 270);
      final int outW = swap ? height : width;
      final int outH = swap ? width : height;
      rotatePixels(width, height, rotation, outW, outH);

      synchronized (publishLock) {
        final int slot = frameBus.freeSlot();
        frameBus.ensureSize(outW, outH);
        Bitmap target = frameBus.slotBitmap(slot);
        if (target != null && !target.isRecycled()) {
          target.setPixels(rotatedPixels, 0, outW, 0, 0, outW, outH);
          frameBus.publish(slot);
          listener.onFrame(target);
        }
      }
    } catch (Exception e) {
      Log.w(TAG, "falha processando frame", e);
    } finally {
      if (image != null) image.close();
    }
  }

  /** Rotacao em passos de 90 graus (0/90/180/270), in-place nos buffers. */
  private void rotatePixels(int w, int h, int rotation, int outW, int outH) {
    if (rotation == 0) {
      System.arraycopy(sensorPixels, 0, rotatedPixels, 0, w * h);
      return;
    }
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        final int v = sensorPixels[y * w + x];
        int nx, ny;
        switch (rotation) {
          case 90:
            nx = h - 1 - y;
            ny = x;
            break;
          case 180:
            nx = w - 1 - x;
            ny = h - 1 - y;
            break;
          case 270:
            nx = y;
            ny = w - 1 - x;
            break;
          default:
            nx = x;
            ny = y;
        }
        rotatedPixels[ny * outW + nx] = v;
      }
    }
  }

  private void convertYuv420ToArgb(
      ByteBuffer yBuf,
      ByteBuffer uBuf,
      ByteBuffer vBuf,
      int yRowStride,
      int uvRowStride,
      int uvPixelStride,
      int width,
      int height) {
    int pos = 0;
    for (int row = 0; row < height; row++) {
      int yRowStart = row * yRowStride;
      int uvRowStart = (row >> 1) * uvRowStride;
      for (int col = 0; col < width; col++) {
        int y = (0xff & yBuf.get(yRowStart + col)) - 16;
        int uvIndex = uvRowStart + (col >> 1) * uvPixelStride;
        int u = (0xff & uBuf.get(uvIndex)) - 128;
        int v = (0xff & vBuf.get(uvIndex)) - 128;
        int r = (int) (1.164f * y + 1.596f * v);
        int g = (int) (1.164f * y - 0.392f * u - 0.813f * v);
        int b = (int) (1.164f * y + 2.017f * u);
        r = r < 0 ? 0 : Math.min(r, 255);
        g = g < 0 ? 0 : Math.min(g, 255);
        b = b < 0 ? 0 : Math.min(b, 255);
        sensorPixels[pos++] = 0xff000000 | (r << 16) | (g << 8) | b;
      }
    }
  }
}
