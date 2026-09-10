/*
 * Pina XR - passthrough MR via Camera2 API.
 *
 * Abre a camera frontal, captura YUV_420_888 via ImageReader, converte para
 * RGBA (rotacionado para a orientacao de exibicao) e publica no frame bus.
 * O mesmo bitmap alimenta o MediaPipe (hand tracking).
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
import android.view.Surface;

import androidx.annotation.NonNull;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class CameraReader {

  private static final String TAG = "CameraReader";
  private static final int CAPTURE_WIDTH = 640;
  private static final int CAPTURE_HEIGHT = 480;

  /** Chamado na thread da camera com o bitmap pronto (RGBA). */
  public interface FrameListener {
    void onFrame(Bitmap bitmap);
  }

  private final Context context;
  private final FrameListener listener;
  private final CameraFrameBus frameBus = new CameraFrameBus();

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

  // Buffers de conversao.
  private final int[] sensorPixels = new int[CAPTURE_WIDTH * CAPTURE_HEIGHT];
  private final int[] rotatedPixels = new int[CAPTURE_WIDTH * CAPTURE_HEIGHT];

  public CameraReader(Context context, FrameListener listener) {
    this.context = context;
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

      imageReader =
          android.media.ImageReader.newInstance(
              CAPTURE_WIDTH,
              CAPTURE_HEIGHT,
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
