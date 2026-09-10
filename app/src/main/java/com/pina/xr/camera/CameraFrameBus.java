/*
 * Pina XR - barramento de frames da camera (double buffer).
 *
 * A thread da camera escreve em um slot livre; a GL thread consome o slot
 * pronto para upload; a thread do MediaPipe le o mesmo bitmap pronto para
 * deteccao (leitores concorrentes, escritor exclusivo em outro slot).
 */
package com.pina.xr.camera;

import android.graphics.Bitmap;

public class CameraFrameBus {

  /** Frame pronto para consumo. */
  public static class LatestFrame {
    public final Bitmap bitmap;
    public final long seq;

    LatestFrame(Bitmap bitmap, long seq) {
      this.bitmap = bitmap;
      this.seq = seq;
    }
  }

  private final Object lock = new Object();
  private Bitmap[] slots = new Bitmap[2];
  private int readyIndex = -1;   // indice do slot pronto (-1 = nenhum)
  private long seq = 0;          // contador de frames publicados
  private long consumedSeq = -1; // ultimo frame consumido pela GL
  private boolean glReading = false;

  /** Garante dois bitmaps do tamanho pedido (chamado pela thread da camera). */
  public void ensureSize(int width, int height) {
    synchronized (lock) {
      for (int i = 0; i < 2; i++) {
        if (slots[i] == null
            || slots[i].getWidth() != width
            || slots[i].getHeight() != height) {
          Bitmap old = slots[i];
          slots[i] =
              Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
          if (old != null && !old.isRecycled()) old.recycle();
        }
      }
    }
  }

  /**
   * Chamado pela thread da camera quando terminou de desenhar um frame no
   * slot livre; publica o slot como "pronto" e devolve o bitmap usado.
   */
  public Bitmap publish(int slotIndex) {
    synchronized (lock) {
      readyIndex = slotIndex;
      seq++;
      return slots[slotIndex];
    }
  }

  /** Slot livre para a thread da camera desenhar (-1 se nenhum disponivel). */
  public int freeSlot() {
    synchronized (lock) {
      if (readyIndex < 0) return 0;
      return 1 - readyIndex;
    }
  }

  /** GL thread: pega o frame mais recente ainda nao consumido. */
  public LatestFrame glConsume() {
    synchronized (lock) {
      if (readyIndex < 0 || slots[readyIndex] == null) return null;
      if (seq == consumedSeq || glReading) return null;
      glReading = true;
      consumedSeq = seq;
      return new LatestFrame(slots[readyIndex], seq);
    }
  }

  /** GL thread: devolve o slot apos o upload. */
  public void releaseGl() {
    synchronized (lock) {
      glReading = false;
    }
  }

  /** Thread da camera: bitmap do slot indicado (ou null se invalido). */
  public Bitmap slotBitmap(int index) {
    synchronized (lock) {
      if (index < 0 || index >= slots.length) return null;
      return slots[index];
    }
  }
}
