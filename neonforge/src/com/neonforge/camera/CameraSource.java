package com.neonforge.camera;

import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.view.SurfaceHolder;

import java.io.IOException;
import java.util.List;

/**
 * Front-camera capture using the legacy Camera API + preview callbacks.
 * Delivers NV21 frames to a listener on a dedicated handler thread.
 *
 * Deliberately avoids camera2/ImageReader complexity: the deprecated API still
 * works across Android 8..16 and gives us raw NV21 frames directly, which is
 * exactly what the CV hand tracker consumes.
 */
public final class CameraSource {

    public interface FrameListener {
        void onFrame(byte[] nv21, int width, int height, long timestamp);
    }

    public interface StatusListener {
        void onStatus(int status, String message);
    }

    public static final int ST_UNAVAILABLE = 0;
    public static final int ST_OPENING = 1;
    public static final int ST_READY = 2;
    public static final int ST_FAILED = 3;
    public static final int ST_DISCONNECTED = 4;

    private Camera camera;
    private SurfaceHolder stubHolder;
    private SurfaceTexture surfaceTexture;
    private FrameListener frameListener;
    private StatusListener statusListener;

    private int previewW = 320, previewH = 240;
    private volatile boolean running = false;

    private byte[] bufferA, bufferB;
    private boolean useA = true;

    public CameraSource(FrameListener l, StatusListener s) {
        this.frameListener = l;
        this.statusListener = s;
    }

    /** Optional 1x1 preview surface target (avoid GL surface texture). */
    public void setPreviewDisplay(SurfaceHolder holder) {
        this.stubHolder = holder;
    }

    public int previewWidth() { return previewW; }
    public int previewHeight() { return previewH; }

    public boolean isRunning() { return running; }

    public void start() {
        if (running) return;
        running = true;
        notify(ST_OPENING, "Abrindo câmera...");
        new Thread(new Runnable() {
            @Override public void run() {
                openCamera();
            }
        }, "camera-open").start();
    }

    private void openCamera() {
        try {
            int camId = findFrontCamera();
            if (camId < 0) camId = 0;
            camera = Camera.open(camId);
            Camera.Parameters p = camera.getParameters();
            choosePreviewSize(p);
            p.setPreviewFormat(android.graphics.ImageFormat.NV21);
            List<String> focus = p.getSupportedFocusModes();
            if (focus != null && focus.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
                p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
            } else if (focus != null && focus.contains(Camera.Parameters.FOCUS_MODE_FIXED)) {
                p.setFocusMode(Camera.Parameters.FOCUS_MODE_FIXED);
            }
            camera.setParameters(p);

            boolean previewSet = false;
            if (stubHolder != null) {
                try {
                    camera.setPreviewDisplay(stubHolder);
                    previewSet = true;
                } catch (Throwable ignored) {}
            }
            if (!previewSet) {
                try {
                    surfaceTexture = new SurfaceTexture(10);
                    camera.setPreviewTexture(surfaceTexture);
                    previewSet = true;
                } catch (Throwable ignored) {}
            }
            if (!previewSet) {
                notify(ST_FAILED, "Falha ao iniciar o preview da câmera.");
                running = false;
                releaseCamera();
                return;
            }

            int size = previewW * previewH;
            bufferA = new byte[size * 3 / 2];
            bufferB = new byte[size * 3 / 2];
            camera.addCallbackBuffer(bufferA);
            camera.addCallbackBuffer(bufferB);
            camera.setPreviewCallbackWithBuffer(new Camera.PreviewCallback() {
                @Override public void onPreviewFrame(byte[] data, Camera c) {
                    if (!running) return;
                    // copy immediately; hand buffer back to the camera
                    byte[] copy = useA ? bufferA : bufferB;
                    useA = !useA;
                    System.arraycopy(data, 0, copy, 0, data.length);
                    if (frameListener != null) {
                        frameListener.onFrame(copy, previewW, previewH, System.nanoTime());
                    }
                    c.addCallbackBuffer(data);
                }
            });
            camera.startPreview();
            notify(ST_READY, "Câmera pronta.");
        } catch (Throwable t) {
            running = false;
            notify(ST_FAILED, "Não foi possível abrir a câmera: " + t.getMessage());
            releaseCamera();
        }
    }

    private int findFrontCamera() {
        try {
            int n = Camera.getNumberOfCameras();
            Camera.CameraInfo info = new Camera.CameraInfo();
            for (int i = 0; i < n; i++) {
                Camera.getCameraInfo(i, info);
                if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) return i;
            }
        } catch (Throwable ignored) {}
        return -1;
    }

    private void choosePreviewSize(Camera.Parameters p) {
        try {
            List<Camera.Size> sizes = p.getSupportedPreviewSizes();
            if (sizes == null || sizes.isEmpty()) return;
            Camera.Size best = null;
            int bestArea = Integer.MAX_VALUE;
            for (Camera.Size s : sizes) {
                // smallest size with at least ~160px width (enough for CV, cheap to process)
                if (s.width < 160) continue;
                int area = s.width * s.height;
                if (area < bestArea) { bestArea = area; best = s; }
            }
            if (best == null) best = sizes.get(0);
            previewW = best.width;
            previewH = best.height;
            p.setPreviewSize(previewW, previewH);
        } catch (Throwable ignored) {}
    }

    public void stop() {
        running = false;
        releaseCamera();
    }

    private void releaseCamera() {
        try {
            if (camera != null) {
                camera.setPreviewCallbackWithBuffer(null);
                camera.stopPreview();
                camera.release();
            }
        } catch (Throwable ignored) {}
        camera = null;
    }

    private void notify(final int status, final String msg) {
        if (statusListener != null) statusListener.onStatus(status, msg);
    }
}
