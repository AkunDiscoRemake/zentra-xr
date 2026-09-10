/*
 * Pina XR - player de video do celular (beta 0.2).
 *
 * Pipeline: MediaStore (lista) -> MediaPlayer -> SurfaceTexture (OES) criada
 * na GL thread -> textura externa consumida pelo renderer nativo (painel
 * flat ou esfera 360). A matriz de transformacao da SurfaceTexture segue
 * para o shader nativo (u_TexMatrix) a cada frame novo.
 *
 * Tudo em threads certas: MediaPlayer na UI thread, textura OES na GL
 * thread, polling de posicao com Handler na main looper.
 */
package com.pina.xr.video;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Surface;
import android.graphics.SurfaceTexture;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class VideoPlayerController {

  private static final String TAG = "PinaVideo";
  private static final long POLL_INTERVAL_MS = 500;

  /** Acoes vindas do nativo (painel de controles). */
  public static final int ACTION_PLAY_PAUSE = 0;
  public static final int ACTION_BACK_10 = 1;
  public static final int ACTION_FWD_10 = 2;
  public static final int ACTION_STOP = 3;

  /** Ponte para o nativo via renderer (as chamadas chegam na GL thread). */
  public interface NativeBridge {
    void setVideoTexture(int oesTextureId);

    void updateVideoTransform(float[] matrix);

    void updateVideoInfo(
        int width, int height, long durationMs, long positionMs, boolean playing);
  }

  /** Entrada da lista de videos. */
  public static class VideoItem {
    public final long id;
    public final String title;
    public final long durationMs;

    public VideoItem(long id, String title, long durationMs) {
      this.id = id;
      this.title = title;
      this.durationMs = durationMs;
    }
  }

  private final Context context;
  private final NativeBridge bridge;
  private final Handler mainHandler = new Handler(Looper.getMainLooper());
  private final Object glLock = new Object();

  // GLSurfaceView atual (para queueEvent: updateTexImage EXIGE a GL thread).
  @Nullable
  private android.opengl.GLSurfaceView glView;

  // Criados na GL thread.
  private int oesTextureId = 0;
  private SurfaceTexture surfaceTexture;
  private final float[] texMatrix = new float[16];

  // Criados na UI thread.
  @Nullable
  private MediaPlayer player;
  private int videoWidth = 0;
  private int videoHeight = 0;
  private boolean prepared = false;
  private boolean playing = false;
  private boolean pendingPlayWhenPrepared = true;
  private boolean wasPlayingBeforePause = false;

  private final Runnable pollRunnable = new Runnable() {
    @Override
    public void run() {
      publishInfo();
      mainHandler.postDelayed(this, POLL_INTERVAL_MS);
    }
  };

  public VideoPlayerController(Context context, NativeBridge bridge) {
    this.context = context.getApplicationContext();
    this.bridge = bridge;
  }

  // -------------------------------------------------------------------------
  // Lista (MediaStore)
  // -------------------------------------------------------------------------

  /** Consulta os videos do celular. Thread-agnostic (rapido o bastante). */
  public static List<VideoItem> queryVideos(Context ctx) {
    final List<VideoItem> out = new ArrayList<>();
    Cursor cursor = null;
    try {
      final String[] projection = {
          MediaStore.Video.Media._ID,
          MediaStore.Video.Media.DISPLAY_NAME,
          MediaStore.Video.Media.DURATION,
      };
      cursor =
          ctx.getContentResolver()
              .query(
                  MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                  projection,
                  null,
                  null,
                  MediaStore.Video.Media.DATE_ADDED + " DESC");
      if (cursor == null) return out;
      final int idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID);
      final int nameCol =
          cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME);
      final int durCol = cursor.getColumnIndex(MediaStore.Video.Media.DURATION);
      while (cursor.moveToNext() && out.size() < 200) {
        final long id = cursor.getLong(idCol);
        String name = cursor.getString(nameCol);
        if (name == null || name.isEmpty()) name = "video_" + id;
        final long dur = durCol >= 0 ? cursor.getLong(durCol) : 0;
        out.add(new VideoItem(id, name, dur));
      }
    } catch (Exception e) {
      Log.w(TAG, "query MediaStore falhou", e);
    } finally {
      if (cursor != null) cursor.close();
    }
    return out;
  }

  // -------------------------------------------------------------------------
  // Play / controles
  // -------------------------------------------------------------------------

  /**
   * Toca um video por _ID do MediaStore. A textura OES e criada na GL thread
   * e o MediaPlayer na main thread.
   */
  public void play(long videoId, android.opengl.GLSurfaceView glView) {
    this.glView = glView;
    stopInternal(false);
    currentVideoId = videoId;
    glView.queueEvent(this::createTextureOnGlThread);
  }

  private void createTextureOnGlThread() {
    synchronized (glLock) {
      deleteTextureOnGlThread();
      final int[] tex = new int[1];
      GLES20.glGenTextures(1, tex, 0);
      oesTextureId = tex[0];
      GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId);
      GLES20.glTexParameteri(
          GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S,
          GLES20.GL_CLAMP_TO_EDGE);
      GLES20.glTexParameteri(
          GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T,
          GLES20.GL_CLAMP_TO_EDGE);
      GLES20.glTexParameteri(
          GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER,
          GLES20.GL_LINEAR);
      GLES20.glTexParameteri(
          GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER,
          GLES20.GL_LINEAR);
      GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);

      surfaceTexture = new SurfaceTexture(oesTextureId);
      surfaceTexture.setOnFrameAvailableListener(this::onFrameAvailable, mainHandler);
    }
    bridge.setVideoTexture(oesTextureId);
    final long videoId = currentVideoId;
    mainHandler.post(() -> startPlayer(videoId));
  }

  private long currentVideoId = -1;

  private void startPlayer(long videoId) {
    releasePlayer();
    currentVideoId = videoId;
    final SurfaceTexture st;
    synchronized (glLock) {
      st = surfaceTexture;
    }
    if (st == null) return;

    try {
      // Default buffer size evita frames descartados no comeco.
      st.setDefaultBufferSize(640, 360);  // ajustado no OnVideoSizeChanged
      final MediaPlayer p = new MediaPlayer();
      player = p;
      prepared = false;
      pendingPlayWhenPrepared = true;
      wasPlayingBeforePause = false;
      p.setAudioStreamType(AudioManager.STREAM_MUSIC);
      p.setSurface(new Surface(st));
      p.setDataSource(
          context,
          ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, videoId));
      p.setOnPreparedListener(
          mp -> {
            prepared = true;
            if (pendingPlayWhenPrepared) {
              mp.start();
              playing = true;
            }
            publishInfo();
          });
      p.setOnVideoSizeChangedListener(
          (mp, w, h) -> {
            videoWidth = w;
            videoHeight = h;
            if (w > 0 && h > 0) {
              synchronized (glLock) {
                if (surfaceTexture != null) surfaceTexture.setDefaultBufferSize(w, h);
              }
            }
            publishInfo();
          });
      p.setOnCompletionListener(
          mp -> {
            playing = false;
            publishInfo();
          });
      p.setOnErrorListener(
          (mp, what, extra) -> {
            Log.e(TAG, "MediaPlayer erro what=" + what + " extra=" + extra);
            playing = false;
            publishInfo();
            return true;
          });
      p.setOnSeekCompleteListener(mp -> publishInfo());
      p.prepareAsync();
      mainHandler.removeCallbacks(pollRunnable);
      mainHandler.postDelayed(pollRunnable, POLL_INTERVAL_MS);
      videoWidth = 0;
      videoHeight = 0;
      publishInfo();
    } catch (IOException | IllegalStateException | SecurityException | IllegalArgumentException e) {
      Log.e(TAG, "falha ao tocar video " + videoId, e);
      releasePlayer();
    }
  }

  /** Acoes dos paineis de controle nativos. */
  public void control(int action) {
    final MediaPlayer p = player;
    if (action == ACTION_STOP) {
      stopInternal(true);
      return;
    }
    if (p == null || !prepared) return;
    try {
      switch (action) {
        case ACTION_PLAY_PAUSE:
          if (p.isPlaying()) {
            p.pause();
            playing = false;
          } else {
            p.start();
            playing = true;
          }
          break;
        case ACTION_BACK_10:
          p.seekTo(Math.max(0, p.getCurrentPosition() - 10_000));
          break;
        case ACTION_FWD_10:
          p.seekTo(
              Math.min(Math.max(p.getDuration(), 0), p.getCurrentPosition() + 10_000));
          break;
        default:
          break;
      }
    } catch (IllegalStateException e) {
      Log.w(TAG, "controle em estado invalido", e);
    }
    publishInfo();
  }

  /** Activity pausou: para de tocar (audio) sem derrubar a textura. */
  public void pause() {
    final MediaPlayer p = player;
    if (p != null && prepared && p.isPlaying()) {
      try {
        p.pause();
        wasPlayingBeforePause = true;
      } catch (IllegalStateException ignored) {
        // player em estado transitorio
      }
    }
    publishInfo();
  }

  /** Activity voltou: retoma se estava tocando. */
  public void resume() {
    final MediaPlayer p = player;
    if (p != null && prepared && wasPlayingBeforePause) {
      wasPlayingBeforePause = false;
      try {
        p.start();
      } catch (IllegalStateException ignored) {
        // player em estado transitorio
      }
    }
    publishInfo();
  }

  /** Solta tudo (activity destruida ou outro video escolhido). */
  public void release(android.opengl.GLSurfaceView glView) {
    stopInternal(true);
    glView.queueEvent(this::deleteTextureOnGlThread);
  }

  /**
   * O contexto EGL pode ser destruido ao pausar (padrao do GLSurfaceView).
   * Recria a textura OES + SurfaceTexture e recomeca o video atual.
   */
  public void onSurfaceRecreated(android.opengl.GLSurfaceView glView) {
    this.glView = glView;
    synchronized (glLock) {
      surfaceTexture = null;
      oesTextureId = 0;
    }
    if (currentVideoId == -1) return;
    final long videoId = currentVideoId;
    glView.queueEvent(
        () -> {
          createTextureOnGlThread();
          final long id = videoId;
          mainHandler.post(() -> {
            if (currentVideoId == id && player == null) startPlayer(id);
          });
        });
  }

  // -------------------------------------------------------------------------

  private void stopInternal(boolean clearId) {
    mainHandler.removeCallbacks(pollRunnable);
    releasePlayer();
    if (clearId) {
      currentVideoId = -1;
      bridge.setVideoTexture(0);
    }
  }

  private void releasePlayer() {
    final MediaPlayer p = player;
    player = null;
    prepared = false;
    playing = false;
    videoWidth = 0;
    videoHeight = 0;
    if (p != null) {
      try {
        p.setSurface(null);
      } catch (Exception ignored) {
        // surface ja solta
      }
      try {
        p.release();
      } catch (Exception e) {
        Log.w(TAG, "release do player falhou", e);
      }
    }
  }

  /** GL thread: destroi textura OES + SurfaceTexture. */
  private void deleteTextureOnGlThread() {
    synchronized (glLock) {
      if (surfaceTexture != null) {
        surfaceTexture.setOnFrameAvailableListener(null);
        surfaceTexture.release();
        surfaceTexture = null;
      }
      if (oesTextureId != 0) {
        GLES20.glDeleteTextures(1, new int[] {oesTextureId}, 0);
        oesTextureId = 0;
      }
    }
  }

  /**
   * Main thread (via Handler): SurfaceTexture tem frame novo. O
   * updateTexImage roda na GL thread (precisa do contexto EGL corrente).
   */
  private void onFrameAvailable(SurfaceTexture st) {
    final android.opengl.GLSurfaceView view = glView;
    if (view == null) return;
    view.queueEvent(
        () -> {
          synchronized (glLock) {
            if (surfaceTexture != st) return;
            try {
              st.updateTexImage();
              st.getTransformMatrix(texMatrix);
            } catch (Exception e) {
              Log.w(TAG, "updateTexImage falhou", e);
              return;
            }
          }
          bridge.updateVideoTransform(texMatrix);
        });
  }

  /** Publica posicao/duracao/estado para o nativo (HUD). */
  private void publishInfo() {
    final MediaPlayer p = player;
    long duration = 0;
    long position = 0;
    boolean nowPlaying = false;
    if (p != null && prepared) {
      try {
        duration = Math.max(p.getDuration(), 0);
        position = Math.max(p.getCurrentPosition(), 0);
        nowPlaying = p.isPlaying();
      } catch (IllegalStateException ignored) {
        // player soltando
      }
    }
    bridge.updateVideoInfo(videoWidth, videoHeight, duration, position, nowPlaying);
  }
}
