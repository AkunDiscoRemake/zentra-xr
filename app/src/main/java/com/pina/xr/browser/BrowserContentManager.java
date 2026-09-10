/*
 * Pina XR - navegador 3D (beta).
 *
 * Baixa uma pagina web (HTTP/HTTPS), converte o HTML em texto estilizado
 * (sem JavaScript - beta), desenha num bitmap RGBA e envia para o nativo,
 * junto com as areas clicaveis dos links (raycast + pinch no mundo 3D).
 */
package com.pina.xr.browser;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.text.Editable;
import android.text.Html;
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.style.ForegroundColorSpan;
import android.text.style.URLSpan;
import android.util.Log;

import androidx.annotation.Nullable;

import com.pina.xr.vr.PinaRenderer;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.xml.sax.XMLReader;

public class BrowserContentManager {

  private static final String TAG = "PinaBrowser";

  private static final int PAGE_W = 768;
  private static final int PAGE_MAX_H = 2048;
  private static final int MARGIN = 24;
  private static final int MAX_BYTES = 3 * 1024 * 1024;
  private static final int MAX_REDIRECTS = 5;
  private static final String USER_AGENT =
      "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) "
          + "Chrome/120.0 Mobile Safari/537.36";

  private final PinaRenderer renderer;
  private final ExecutorService executor = Executors.newSingleThreadExecutor();
  private final AtomicInteger generation = new AtomicInteger(0);

  private volatile Future<?> pending;

  public BrowserContentManager(PinaRenderer renderer) {
    this.renderer = renderer;
  }

  /** Carrega uma URL (completa com https:// se faltar o esquema). */
  public void load(String rawUrl) {
    final String url = normalizeUrl(rawUrl);
    if (url == null) return;
    cancel();
    final int gen = generation.incrementAndGet();
    publish(makeTextPage("CARREGANDO...", truncateForDisplay(url)));

    pending =
        executor.submit(
            () -> {
              try {
                Fetched fetched = fetch(url);
                if (generation.get() != gen) return;
                final String ct = fetched.contentType == null ? "" : fetched.contentType;
                Page page =
                    ct.startsWith("image/")
                        ? renderImagePage(fetched.bytes)
                        : renderHtmlPage(fetched.bytes);
                if (generation.get() == gen) publish(page);
              } catch (Exception e) {
                Log.w(TAG, "falha ao carregar " + url, e);
                if (generation.get() == gen) {
                  publish(makeTextPage("ERRO", "NAO FOI POSSIVEL CARREGAR A PAGINA"));
                }
              }
            });
  }

  /** Cancela o carregamento em andamento. */
  public void cancel() {
    generation.incrementAndGet();
    Future<?> p = pending;
    if (p != null) {
      p.cancel(true);
      pending = null;
    }
  }

  // -------------------------------------------------------------------------

  private static String normalizeUrl(String raw) {
    if (raw == null || raw.isEmpty()) return null;
    raw = raw.trim();
    final String lower = raw.toLowerCase(Locale.US);
    if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
      raw = "https://" + raw;
    }
    return raw;
  }

  private static String truncateForDisplay(String url) {
    String u = url.replaceFirst("^https?://", "");
    if (u.length() > 46) u = u.substring(0, 46) + "...";
    return u.toUpperCase(Locale.US);
  }

  // -------------------------------------------------------------------------
  // Download
  // -------------------------------------------------------------------------

  private static class Fetched {
    final byte[] bytes;
    final String contentType;

    Fetched(byte[] bytes, String contentType) {
      this.bytes = bytes;
      this.contentType = contentType;
    }
  }

  private Fetched fetch(String urlString) throws Exception {
    String current = urlString;
    for (int i = 0; i < MAX_REDIRECTS; i++) {
      HttpURLConnection conn = (HttpURLConnection) new URL(current).openConnection();
      conn.setInstanceFollowRedirects(false);
      conn.setConnectTimeout(12000);
      conn.setReadTimeout(12000);
      conn.setRequestProperty("User-Agent", USER_AGENT);
      conn.setRequestProperty("Accept", "text/html,image/*;q=0.9,*/*;q=0.8");
      conn.setRequestProperty("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8");
      try {
        final int code = conn.getResponseCode();
        final String location = conn.getHeaderField("Location");
        if (code >= 300 && code < 400 && location != null) {
          current = new URL(new URL(current), location).toString();
          continue;
        }
        if (code < 200 || code >= 300) {
          throw new Exception("HTTP " + code);
        }
        InputStream in = conn.getInputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int total = 0;
        int n;
        while ((n = in.read(buf)) != -1 && total < MAX_BYTES) {
          out.write(buf, 0, n);
          total += n;
        }
        in.close();
        return new Fetched(out.toByteArray(), conn.getContentType());
      } finally {
        conn.disconnect();
      }
    }
    throw new Exception("muitos redirecionamentos");
  }

  // -------------------------------------------------------------------------
  // Pagina
  // -------------------------------------------------------------------------

  private static class Page {
    final Bitmap bitmap;
    final float[] linkRects; // u0,v0,u1,v1 por link (normalizado 0..1)
    final String[] linkUrls;

    Page(Bitmap bitmap, float[] linkRects, String[] linkUrls) {
      this.bitmap = bitmap;
      this.linkRects = linkRects;
      this.linkUrls = linkUrls;
    }
  }

  private void publish(Page page) {
    ByteBuffer buffer =
        ByteBuffer.allocateDirect(
                page.bitmap.getWidth() * page.bitmap.getHeight() * 4)
            .order(ByteOrder.nativeOrder());
    page.bitmap.copyPixelsToBuffer(buffer);
    buffer.rewind();
    renderer.schedulePageUpload(
        buffer, page.bitmap.getWidth(), page.bitmap.getHeight(),
        page.linkRects, page.linkUrls);
  }

  // -------------------------------------------------------------------------
  // Renderizacao HTML
  // -------------------------------------------------------------------------

  private Page renderHtmlPage(byte[] bytes) {
    String html = new String(bytes, Charset.forName("UTF-8"));
    Matcher m =
        Pattern.compile("charset\\s*=\\s*[\"']?([a-zA-Z0-9_\\-]+)",
                Pattern.CASE_INSENSITIVE)
            .matcher(html.length() > 2048 ? html.substring(0, 2048) : html);
    if (m.find()) {
      try {
        html = new String(bytes, Charset.forName(m.group(1)));
      } catch (Exception ignored) {
        // mantem UTF-8
      }
    }
    // limpa scripts/estilos (beta sem JS)
    html = html.replaceAll("(?is)<(script|style|noscript)[^>]*>.*?</\\1>", "");

    SpannableStringBuilder text =
        new SpannableStringBuilder(
            Html.fromHtml(
                html,
                Html.FROM_HTML_MODE_LEGACY,
                new ImageGetter(),
                new BlockTagHandler()));

    if (text.length() == 0) {
      text.append("(pagina vazia)");
    }

    // Links http(s) em ciano, mantendo pares span/url alinhados.
    URLSpan[] spans = text.getSpans(0, text.length(), URLSpan.class);
    List<URLSpan> keptSpans = new ArrayList<>();
    List<String> keptUrls = new ArrayList<>();
    for (URLSpan span : spans) {
      String u = span.getURL();
      if (u == null) continue;
      if (!u.startsWith("http://") && !u.startsWith("https://")) continue;
      text.setSpan(
          new ForegroundColorSpan(0xFF4FC3F7),
          text.getSpanStart(span),
          text.getSpanEnd(span),
          Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
      keptSpans.add(span);
      keptUrls.add(normalizeUrl(u));
    }

    TextPaint paint = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
    paint.setTextSize(30f);
    paint.setColor(0xFFE8EAF0);
    paint.setTypeface(Typeface.SANS_SERIF);

    final int contentWidth = PAGE_W - 2 * MARGIN;
    StaticLayout layout =
        StaticLayout.Builder.obtain(text, 0, text.length(), paint, contentWidth)
            .setLineSpacing(0f, 1.35f)
            .setIncludePad(true)
            .build();

    final int height = Math.min(layout.getHeight() + 2 * MARGIN, PAGE_MAX_H);
    Bitmap bitmap = Bitmap.createBitmap(PAGE_W, height, Bitmap.Config.ARGB_8888);
    bitmap.setHasAlpha(false);
    Canvas canvas = new Canvas(bitmap);
    canvas.drawColor(0xFF14161E);
    canvas.save();
    canvas.translate(MARGIN, MARGIN);
    canvas.clipRect(0, 0, contentWidth, height - 2 * MARGIN);
    layout.draw(canvas);
    canvas.restore();

    // Areas clicaveis: para cada link, um retangulo por linha afetada.
    List<Float> rects = new ArrayList<>();
    List<String> finalUrls = new ArrayList<>();
    for (int i = 0; i < keptSpans.size(); i++) {
      final URLSpan span = keptSpans.get(i);
      final String u = keptUrls.get(i);
      final int start = text.getSpanStart(span);
      final int end = text.getSpanEnd(span);
      if (start < 0 || end <= start) continue;
      final int firstLine = layout.getLineForOffset(start);
      final int lastLine =
          layout.getLineForOffset(Math.min(end - 1, text.length() - 1));
      for (int line = firstLine; line <= lastLine; line++) {
        final int ls = layout.getLineStart(line);
        final int le = layout.getLineEnd(line);
        final int a = Math.max(start, ls);
        final int b = Math.min(end, le);
        if (b <= a) continue;
        final float x0 = layout.getPrimaryHorizontal(a) + MARGIN;
        final float x1 = layout.getPrimaryHorizontal(b) + MARGIN;
        final float y0 = layout.getLineTop(line) + MARGIN;
        final float y1 = layout.getLineBottom(line) + MARGIN;
        if (y1 < 0 || y0 > height) continue;
        rects.add(x0 / PAGE_W);
        rects.add(y0 / (float) height);
        rects.add(x1 / PAGE_W);
        rects.add(y1 / (float) height);
        finalUrls.add(u);
      }
    }

    float[] rectArray = new float[rects.size()];
    for (int i = 0; i < rects.size(); i++) rectArray[i] = rects.get(i);
    return new Page(bitmap, rectArray, finalUrls.toArray(new String[0]));
  }

  private Page renderImagePage(byte[] bytes) {
    Bitmap img = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
    if (img == null) {
      return makeTextPage("ERRO", "IMAGEM INVALIDA");
    }
    Bitmap bitmap = Bitmap.createBitmap(PAGE_W, PAGE_MAX_H, Bitmap.Config.ARGB_8888);
    bitmap.setHasAlpha(false);
    Canvas canvas = new Canvas(bitmap);
    canvas.drawColor(0xFF14161E);
    float scale =
        Math.min(
            (float) (PAGE_W - 2 * MARGIN) / img.getWidth(),
            (float) (PAGE_MAX_H - 2 * MARGIN) / img.getHeight());
    scale = Math.min(scale, 1.0f);
    int w = (int) (img.getWidth() * scale);
    int h = (int) (img.getHeight() * scale);
    Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    canvas.drawBitmap(img, null, new RectF(MARGIN, MARGIN, MARGIN + w, MARGIN + h), paint);
    img.recycle();
    return new Page(bitmap, new float[0], new String[0]);
  }

  /** Pagina simples (carregando / erro) desenhada localmente. */
  private Page makeTextPage(String title, String subtitle) {
    Bitmap bitmap = Bitmap.createBitmap(PAGE_W, 384, Bitmap.Config.ARGB_8888);
    bitmap.setHasAlpha(false);
    Canvas canvas = new Canvas(bitmap);
    canvas.drawColor(0xFF14161E);

    TextPaint titlePaint = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
    titlePaint.setColor(0xFF50DCFF);
    titlePaint.setTextSize(52f);
    titlePaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
    titlePaint.setTextAlign(Paint.Align.CENTER);
    canvas.drawText(title, PAGE_W / 2f, 170f, titlePaint);

    TextPaint subPaint = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
    subPaint.setColor(0xFF9AA3B5);
    subPaint.setTextSize(26f);
    subPaint.setTextAlign(Paint.Align.CENTER);
    canvas.drawText(subtitle, PAGE_W / 2f, 230f, subPaint);

    return new Page(bitmap, new float[0], new String[0]);
  }

  // -------------------------------------------------------------------------
  // <img>: baixa e escala a imagem (na thread do navegador)
  // -------------------------------------------------------------------------

  private class ImageGetter implements Html.ImageGetter {
    @Override
    public Drawable getDrawable(String source) {
      try {
        String u = source.startsWith("http") ? source : new URL(new URL("https://x"), source).toString();
        HttpURLConnection conn = (HttpURLConnection) new URL(u).openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        conn.setRequestProperty("User-Agent", USER_AGENT);
        Bitmap bmp = android.graphics.BitmapFactory.decodeStream(conn.getInputStream());
        conn.disconnect();
        if (bmp == null) return emptyDrawable();
        float scale =
            Math.min(1.0f, (float) (PAGE_W - 2 * MARGIN) / bmp.getWidth());
        Bitmap scaled =
            Bitmap.createScaledBitmap(
                bmp,
                Math.max(1, (int) (bmp.getWidth() * scale)),
                Math.max(1, (int) (bmp.getHeight() * scale)),
                true);
        if (scaled != bmp) bmp.recycle();
        BitmapDrawable drawable = new BitmapDrawable(scaled);
        drawable.setBounds(0, 0, scaled.getWidth(), scaled.getHeight());
        return drawable;
      } catch (Exception e) {
        return emptyDrawable();
      }
    }

    private Drawable emptyDrawable() {
      Bitmap bmp = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
      BitmapDrawable d = new BitmapDrawable(bmp);
      d.setBounds(0, 0, 0, 0);
      return d;
    }
  }

  /**
   * Quebras de linha para tags de bloco que o fromHtml nao trata: div,
   * titulos h1-h6, linhas de tabela e listas de definicao.
   */
  private static class BlockTagHandler implements Html.TagHandler {
    @Override
    public void handleTag(
        boolean opening, String tag, Editable output, XMLReader xmlReader) {
      final String t = tag.toLowerCase(Locale.US);
      if (opening) {
        if (t.equals("div") || t.equals("hr")) {
          output.append("\n");
        } else if (t.startsWith("h") && t.length() == 2
            && t.charAt(1) >= '1' && t.charAt(1) <= '6') {
          output.append("\n");
        } else if (t.equals("tr") || t.equals("li") || t.equals("dd") || t.equals("dt")) {
          output.append("\n");
        }
      } else {
        if (t.equals("div") || t.equals("hr") || t.equals("p")
            || (t.startsWith("h") && t.length() == 2
                && t.charAt(1) >= '1' && t.charAt(1) <= '6')
            || t.equals("tr") || t.equals("li") || t.equals("dd") || t.equals("dt")) {
          output.append("\n");
        }
      }
    }
  }
}
