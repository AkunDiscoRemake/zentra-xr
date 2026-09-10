/*
 * Pina XR - beta 0.1
 *
 * Renderizador nativo: estereoscopia Cardboard (distortion + 3DOF),
 * passthrough da camera (esfera), esqueleto das maos (MediaPipe via Java),
 * apontador 3D com linha + bolinha e clique por pinch, e paineis 3D
 * (inicio + navegador). Nada de UI 2D.
 */

#include "pina_app.h"

#include <android/log.h>

#include <algorithm>
#include <cmath>
#include <cstring>

#include "cardboard.h"
#include "font.h"

namespace pina_xr {

namespace {

// ---------------------------------------------------------------------------
// Shaders
// ---------------------------------------------------------------------------

// Esfera de passthrough: mapeia a imagem da camera ao redor do usuario,
// limitada ao FOV da camera (fora disso, preto - e a "janela" MR).
constexpr const char* kPassthroughVertexShader =
    R"glsl(
    uniform mat4 u_MVP;
    attribute vec3 a_Position;
    varying vec3 v_Dir;
    void main() {
      v_Dir = a_Position;
      gl_Position = u_MVP * vec4(a_Position, 1.0);
    })glsl";

constexpr const char* kPassthroughFragmentShader =
    R"glsl(
    precision mediump float;
    uniform sampler2D u_Texture;
    uniform vec2 u_Fov;      // fovX, fovY em radianos
    uniform float u_Mirror;  // 1.0 = espelhar horizontalmente
    varying vec3 v_Dir;
    void main() {
      float angle = atan(v_Dir.x, -v_Dir.z);
      float pitch = asin(clamp(v_Dir.y / length(v_Dir), -1.0, 1.0));
      float u = 0.5 + angle / u_Fov.x;
      float v = 0.5 - pitch / u_Fov.y;
      if (u_Mirror > 0.5) { u = 1.0 - u; }
      if (u < 0.0 || u > 1.0 || v < 0.0 || v > 1.0) {
        gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
      } else {
        gl_FragColor = texture2D(u_Texture, vec2(u, v));
      }
    })glsl";

// Quads texturizados (paineis).
constexpr const char* kQuadVertexShader =
    R"glsl(
    uniform mat4 u_MVP;
    attribute vec3 a_Position;
    attribute vec2 a_UV;
    varying vec2 v_UV;
    void main() {
      v_UV = a_UV;
      gl_Position = u_MVP * vec4(a_Position, 1.0);
    })glsl";

constexpr const char* kQuadFragmentShader =
    R"glsl(
    precision mediump float;
    uniform sampler2D u_Texture;
    uniform float u_Alpha;
    varying vec2 v_UV;
    void main() {
      vec4 c = texture2D(u_Texture, v_UV);
      gl_FragColor = vec4(c.rgb, c.a * u_Alpha);
    })glsl";

// Linhas e pontos (esqueleto das maos + apontador).
constexpr const char* kColorVertexShader =
    R"glsl(
    uniform mat4 u_MVP;
    uniform float u_PointSize;
    attribute vec3 a_Position;
    void main() {
      gl_Position = u_MVP * vec4(a_Position, 1.0);
      gl_PointSize = u_PointSize;
    })glsl";

constexpr const char* kColorFragmentShader =
    R"glsl(
    precision mediump float;
    uniform vec4 u_Color;
    uniform float u_IsPoint;
    void main() {
      if (u_IsPoint > 0.5) {
        vec2 d = gl_PointCoord - vec2(0.5);
        float r = length(d);
        if (r > 0.5) discard;
        float alpha = smoothstep(0.5, 0.32, r);
        gl_FragColor = vec4(u_Color.rgb, u_Color.a * alpha);
      } else {
        gl_FragColor = u_Color;
      }
    })glsl";

// Topologia do esqueleto da mao (indices MediaPipe: 21 pontos).
const int kBones[][2] = {
    {0, 1}, {1, 2}, {2, 3}, {3, 4},         // polegar
    {0, 5}, {5, 6}, {6, 7}, {7, 8},         // indicador
    {5, 9}, {9, 10}, {10, 11}, {11, 12},    // medio
    {9, 13}, {13, 14}, {14, 15}, {15, 16},  // anelar
    {13, 17}, {17, 18}, {18, 19}, {19, 20}, // minimo
    {0, 17},                                // base da palma
};
constexpr int kNumBones = sizeof(kBones) / sizeof(kBones[0]);

// --- helpers de vetor ---
float Dot3(const std::array<float, 3>& a, const std::array<float, 3>& b) {
  return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
}

std::array<float, 3> Normalize3(const std::array<float, 3>& v) {
  const float n = std::sqrt(Dot3(v, v));
  if (n < 1e-8f) return {0.0f, 0.0f, -1.0f};
  return {v[0] / n, v[1] / n, v[2] / n};
}

std::array<float, 3> Sub3(const std::array<float, 3>& a,
                          const std::array<float, 3>& b) {
  return {a[0] - b[0], a[1] - b[1], a[2] - b[2]};
}

std::array<float, 3> Add3(const std::array<float, 3>& a,
                          const std::array<float, 3>& b) {
  return {a[0] + b[0], a[1] + b[1], a[2] + b[2]};
}

std::array<float, 3> Scale3(const std::array<float, 3>& a, float s) {
  return {a[0] * s, a[1] * s, a[2] * s};
}

std::array<float, 4> ToVec4(const std::array<float, 3>& v, float w) {
  return {v[0], v[1], v[2], w};
}

std::array<float, 3> FromVec4(const std::array<float, 4>& v) {
  return {v[0], v[1], v[2]};
}

// Converte coordenadas normalizadas da imagem (MediaPipe) para o espaco da
// cabeca, usando o FOV estimado da camera frontal.
std::array<float, 3> HeadSpaceFromImage(float x, float y, float z) {
  const float yaw = (x - 0.5f) * kCameraFovX;
  const float pitch = (0.5f - y) * kCameraFovY;
  const float dist = kHandDistance + z * kHandZScale;
  const float cp = std::cos(pitch);
  return {std::sin(yaw) * cp * dist, std::sin(pitch) * dist,
          -std::cos(yaw) * cp * dist};
}

// --- canvas 2D minimalista para gerar as texturas de UI ---
struct UiCanvas {
  int w;
  int h;
  std::vector<uint8_t> px;  // RGBA, linha 0 = topo

  UiCanvas(int width, int height) : w(width), h(height) {
    px.assign(static_cast<size_t>(w) * h * 4, 0);
  }

  void SetPixel(int x, int y, uint8_t r, uint8_t g, uint8_t b, uint8_t a) {
    if (x < 0 || y < 0 || x >= w || y >= h) return;
    uint8_t* p = px.data() + (static_cast<size_t>(y) * w + x) * 4;
    if (a >= 255) {
      p[0] = r; p[1] = g; p[2] = b; p[3] = 255;
    } else {
      const float alpha = a / 255.0f;
      p[0] = static_cast<uint8_t>(p[0] * (1 - alpha) + r * alpha);
      p[1] = static_cast<uint8_t>(p[1] * (1 - alpha) + g * alpha);
      p[2] = static_cast<uint8_t>(p[2] * (1 - alpha) + b * alpha);
      p[3] = static_cast<uint8_t>(std::min(255, p[3] + a));
    }
  }

  void FillRect(int x0, int y0, int x1, int y1, uint8_t r, uint8_t g,
                uint8_t b, uint8_t a = 255) {
    for (int y = y0; y <= y1; ++y)
      for (int x = x0; x <= x1; ++x) SetPixel(x, y, r, g, b, a);
  }

  void FillRoundRect(int x0, int y0, int x1, int y1, int radius, uint8_t r,
                     uint8_t g, uint8_t b, uint8_t a = 255) {
    for (int y = y0; y <= y1; ++y) {
      for (int x = x0; x <= x1; ++x) {
        int dx = 0, dy = 0;
        if (x < x0 + radius) dx = x0 + radius - x;
        if (x > x1 - radius) dx = x - (x1 - radius);
        if (y < y0 + radius) dy = y0 + radius - y;
        if (y > y1 - radius) dy = y - (y1 - radius);
        if (dx * dx + dy * dy <= radius * radius) {
          SetPixel(x, y, r, g, b, a);
        }
      }
    }
  }

  void Ring(int cx, int cy, int radius, int thickness, uint8_t r, uint8_t g,
            uint8_t b, uint8_t a = 255) {
    const float outer = radius + thickness * 0.5f;
    const float inner = radius - thickness * 0.5f;
    const int lim = static_cast<int>(outer) + 2;
    for (int y = cy - lim; y <= cy + lim; ++y) {
      for (int x = cx - lim; x <= cx + lim; ++x) {
        const float d =
            std::sqrt(static_cast<float>((x - cx) * (x - cx) + (y - cy) * (y - cy)));
        if (d <= outer && d >= inner) SetPixel(x, y, r, g, b, a);
      }
    }
  }

  void Line(int x0, int y0, int x1, int y1, int thickness, uint8_t r, uint8_t g,
            uint8_t b, uint8_t a = 255) {
    const int steps = std::max(std::abs(x1 - x0), std::abs(y1 - y0)) + 1;
    const int half = std::max(1, thickness / 2);
    for (int i = 0; i <= steps; ++i) {
      const float t = static_cast<float>(i) / steps;
      const int cx = static_cast<int>(x0 + (x1 - x0) * t);
      const int cy = static_cast<int>(y0 + (y1 - y0) * t);
      for (int dy = -half; dy <= half; ++dy)
        for (int dx = -half; dx <= half; ++dx)
          SetPixel(cx + dx, cy + dy, r, g, b, a);
    }
  }

  void Text(int x, int y, const char* s, int scale, uint8_t r, uint8_t g,
            uint8_t b, uint8_t a = 255) {
    DrawText5x7(px.data(), w, h, x, y, s, scale, r, g, b, a);
  }

  void TextCentered(int cx, int y, const char* s, int scale, uint8_t r,
                    uint8_t g, uint8_t b, uint8_t a = 255) {
    Text(cx - TextWidth5x7(s, scale) / 2, y, s, scale, r, g, b, a);
  }
};

GLuint CreateRgbaTexture(int w, int h, const uint8_t* data) {
  GLuint tex = 0;
  glGenTextures(1, &tex);
  glBindTexture(GL_TEXTURE_2D, tex);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
  glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE,
               data);
  CHECKGLERROR("CreateRgbaTexture");
  return tex;
}

}  // namespace

// ===========================================================================
// Ciclo de vida
// ===========================================================================

PinaApp::PinaApp(JavaVM* vm, jobject activity_obj, jobject asset_mgr_obj) {
  JNIEnv* env = nullptr;
  vm->GetEnv((void**)&env, JNI_VERSION_1_6);
  java_vm_ = vm;
  java_activity_ = env->NewGlobalRef(activity_obj);
  java_asset_mgr_ = env->NewGlobalRef(asset_mgr_obj);
  asset_mgr_ = AAssetManager_fromJava(env, asset_mgr_obj);

  jclass activity_class = env->GetObjectClass(activity_obj);
  method_open_url_ =
      env->GetMethodID(activity_class, "onNativeOpenUrl", "(Ljava/lang/String;)V");
  method_close_browser_ =
      env->GetMethodID(activity_class, "onNativeCloseBrowser", "()V");
  method_exit_ = env->GetMethodID(activity_class, "onNativeExit", "()V");

  Cardboard_initializeAndroid(vm, activity_obj);
  head_tracker_ = CardboardHeadTracker_create();
  // Filtro passa-baixa do head tracker (igual ao sample oficial).
  CardboardHeadTracker_setLowPassFilter(head_tracker_, 6);
}

PinaApp::~PinaApp() {
  CardboardHeadTracker_destroy(head_tracker_);
  CardboardLensDistortion_destroy(lens_distortion_);
  CardboardDistortionRenderer_destroy(distortion_renderer_);
}

void PinaApp::OnPause() { CardboardHeadTracker_pause(head_tracker_); }

void PinaApp::OnResume() {
  CardboardHeadTracker_resume(head_tracker_);
  device_params_changed_ = true;

  // Sem perfil de visor salvo, abre o leitor de QR Code do Cardboard.
  uint8_t* buffer;
  int size;
  CardboardQrCode_getSavedDeviceParams(&buffer, &size);
  if (size == 0) {
    SwitchViewer();
  }
  CardboardQrCode_destroy(buffer);
}

void PinaApp::SwitchViewer() {
  CardboardQrCode_scanQrCodeAndSaveDeviceParams();
}

void PinaApp::SetScreenParams(int width, int height) {
  screen_width_ = width;
  screen_height_ = height;
  screen_params_changed_ = true;
}

// ===========================================================================
// Surface / GL setup
// ===========================================================================

void PinaApp::OnSurfaceCreated(JNIEnv* env) {
  // O contexto GL pode ser novo: invalida texturas dependentes de contexto.
  camera_texture_ = 0;
  camera_texture_ready_ = false;

  passthrough_program_ = glCreateProgram();
  glAttachShader(passthrough_program_,
                 LoadGLShader(GL_VERTEX_SHADER, kPassthroughVertexShader));
  glAttachShader(passthrough_program_,
                 LoadGLShader(GL_FRAGMENT_SHADER, kPassthroughFragmentShader));
  glLinkProgram(passthrough_program_);
  pt_position_param_ = glGetAttribLocation(passthrough_program_, "a_Position");
  pt_mvp_param_ = glGetUniformLocation(passthrough_program_, "u_MVP");
  pt_texture_param_ = glGetUniformLocation(passthrough_program_, "u_Texture");
  pt_fov_param_ = glGetUniformLocation(passthrough_program_, "u_Fov");
  pt_mirror_param_ = glGetUniformLocation(passthrough_program_, "u_Mirror");

  quad_program_ = glCreateProgram();
  glAttachShader(quad_program_,
                 LoadGLShader(GL_VERTEX_SHADER, kQuadVertexShader));
  glAttachShader(quad_program_,
                 LoadGLShader(GL_FRAGMENT_SHADER, kQuadFragmentShader));
  glLinkProgram(quad_program_);
  quad_position_param_ = glGetAttribLocation(quad_program_, "a_Position");
  quad_uv_param_ = glGetAttribLocation(quad_program_, "a_UV");
  quad_mvp_param_ = glGetUniformLocation(quad_program_, "u_MVP");
  quad_texture_param_ = glGetUniformLocation(quad_program_, "u_Texture");
  quad_alpha_param_ = glGetUniformLocation(quad_program_, "u_Alpha");

  color_program_ = glCreateProgram();
  glAttachShader(color_program_,
                 LoadGLShader(GL_VERTEX_SHADER, kColorVertexShader));
  glAttachShader(color_program_,
                 LoadGLShader(GL_FRAGMENT_SHADER, kColorFragmentShader));
  glLinkProgram(color_program_);
  color_position_param_ = glGetAttribLocation(color_program_, "a_Position");
  color_mvp_param_ = glGetUniformLocation(color_program_, "u_MVP");
  color_param_ = glGetUniformLocation(color_program_, "u_Color");
  color_point_size_param_ = glGetUniformLocation(color_program_, "u_PointSize");
  color_is_point_param_ = glGetUniformLocation(color_program_, "u_IsPoint");

  CHECKGLERROR("Programas");

  BuildGeometry();
  BuildUiTextures(env);
  CHECKGLERROR("OnSurfaceCreated");
}

void PinaApp::BuildGeometry() {
  // Esfera de passthrough (raio 40m).
  constexpr int kSlices = 48;
  constexpr int kStacks = 32;
  constexpr float kRadius = 40.0f;
  sphere_vertices_.clear();
  sphere_indices_.clear();
  sphere_vertices_.reserve((kSlices + 1) * (kStacks + 1) * 3);
  for (int stack = 0; stack <= kStacks; ++stack) {
    const float phi = static_cast<float>(M_PI) * stack / kStacks;
    for (int slice = 0; slice <= kSlices; ++slice) {
      const float theta = 2.0f * static_cast<float>(M_PI) * slice / kSlices;
      const float x = std::sin(phi) * std::cos(theta);
      const float y = std::cos(phi);
      const float z = std::sin(phi) * std::sin(theta);
      sphere_vertices_.push_back(kRadius * x);
      sphere_vertices_.push_back(kRadius * y);
      sphere_vertices_.push_back(kRadius * z);
    }
  }
  sphere_indices_.reserve(kSlices * kStacks * 6);
  for (int stack = 0; stack < kStacks; ++stack) {
    for (int slice = 0; slice < kSlices; ++slice) {
      const GLushort a = static_cast<GLushort>(stack * (kSlices + 1) + slice);
      const GLushort b = static_cast<GLushort>(a + kSlices + 1);
      sphere_indices_.insert(sphere_indices_.end(),
                             {a, b, static_cast<GLushort>(a + 1), b,
                              static_cast<GLushort>(b + 1),
                              static_cast<GLushort>(a + 1)});
    }
  }

  // Quad unitario (escala via matriz do painel). uv (0,0) = topo-esquerda.
  quad_vertices_ = {
      -0.5f, 0.5f,  0.0f,  //
      0.5f,  0.5f,  0.0f,  //
      -0.5f, -0.5f, 0.0f,  //
      0.5f,  -0.5f, 0.0f,  //
  };
  quad_uv_ = {0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f};
  quad_indices_ = {0, 2, 1, 1, 2, 3};
}

void PinaApp::BuildUiTextures(JNIEnv* env) {
  (void)env;

  // ------------------------------------------------------------------
  // Painel inicial (512x320)
  // ------------------------------------------------------------------
  {
    UiCanvas c(kHomeTexW, kHomeTexH);
    c.FillRoundRect(2, 2, kHomeTexW - 3, kHomeTexH - 3, 18, 90, 200, 255, 255);
    c.FillRoundRect(6, 6, kHomeTexW - 7, kHomeTexH - 7, 15, 13, 18, 32, 242);
    c.TextCentered(kHomeTexW / 2, 22, "PINA XR", 4, 80, 220, 255);
    c.TextCentered(kHomeTexW / 2, 60, "REALIDADE MISTADA - BETA 0.1", 1, 150,
                   160, 180);

    struct Btn {
      int cx;
      const char* label;
    };
    const Btn btns[3] = {{kHomeTexW / 4, "NAVEGADOR"},
                         {kHomeTexW / 2, "VISOR"},
                         {kHomeTexW * 3 / 4, "SAIR"}};
    const int cy = 150;
    for (int i = 0; i < 3; ++i) {
      const int cx = btns[i].cx;
      c.Ring(cx, cy, 40, 4, 80, 220, 255);
      if (i == 0) {
        // globo
        c.Line(cx - 38, cy, cx + 38, cy, 2, 80, 220, 255);
        for (int y = -38; y <= 38; ++y) {
          const float t = static_cast<float>(y) / 38.0f;
          const int dx =
              static_cast<int>(16.0f * std::sqrt(std::max(0.0f, 1.0f - t * t)));
          c.SetPixel(cx + dx, cy + y, 80, 220, 255, 255);
          c.SetPixel(cx - dx, cy + y, 80, 220, 255, 255);
        }
      } else if (i == 1) {
        // QR code estilizado
        c.FillRect(cx - 26, cy - 26, cx - 8, cy - 8, 80, 220, 255);
        c.FillRect(cx + 8, cy - 26, cx + 26, cy - 8, 80, 220, 255);
        c.FillRect(cx - 26, cy + 8, cx - 8, cy + 26, 80, 220, 255);
        c.FillRect(cx - 21, cy - 21, cx - 13, cy - 13, 13, 18, 32);
        c.FillRect(cx + 13, cy - 21, cx + 21, cy - 13, 13, 18, 32);
        c.FillRect(cx - 21, cy + 13, cx - 13, cy + 21, 13, 18, 32);
        c.FillRect(cx + 6, cy + 6, cx + 14, cy + 14, 80, 220, 255);
        c.FillRect(cx + 18, cy + 18, cx + 26, cy + 26, 80, 220, 255);
      } else {
        // X vermelho
        c.Line(cx - 22, cy - 22, cx + 22, cy + 22, 7, 255, 90, 90);
        c.Line(cx - 22, cy + 22, cx + 22, cy - 22, 7, 255, 90, 90);
      }
      c.TextCentered(cx, cy + 52, btns[i].label, 1, 200, 210, 230);
    }
    c.TextCentered(kHomeTexW / 2, kHomeTexH - 26,
                   "APONTE A MAO E PINCE PARA CLICAR", 1, 130, 140, 160);

    if (home_texture_ != 0) glDeleteTextures(1, &home_texture_);
    home_texture_ = CreateRgbaTexture(kHomeTexW, kHomeTexH, c.px.data());

    // Regioes clicaveis (uv, v=0 no topo).
    const float quarter = 0.25f;
    home_panel_.regions = {
        {quarter - 0.11f, 0.33f, quarter + 0.11f, 0.72f, kHomeBrowser},
        {0.5f - 0.11f, 0.33f, 0.5f + 0.11f, 0.72f, kHomeViewer},
        {1.0f - quarter - 0.11f, 0.33f, 1.0f - quarter + 0.11f, 0.72f,
         kHomeExit},
    };
  }

  // ------------------------------------------------------------------
  // Moldura do navegador (768x480), area central transparente
  // ------------------------------------------------------------------
  {
    UiCanvas c(kBrowserTexW, kBrowserTexH);
    c.FillRoundRect(0, 0, kBrowserTexW - 1, kBrowserTexH - 1, 14, 90, 200, 255,
                    255);
    c.FillRoundRect(4, 4, kBrowserTexW - 5, kBrowserTexH - 5, 11, 13, 18, 32,
                    235);
    // barra de endereco
    c.FillRoundRect(88, 10, 690, 60, 8, 30, 36, 52, 255);
    c.FillRoundRect(704, 8, 762, 62, 8, 200, 60, 60, 255);  // FECHAR
    c.FillRoundRect(6, 8, 74, 62, 8, 40, 170, 90, 255);     // INICIO
    c.Line(718, 20, 748, 50, 5, 255, 255, 255);
    c.Line(718, 50, 748, 20, 5, 255, 255, 255);
    // casinha do inicio
    for (int t = 0; t <= 26; ++t) {
      c.SetPixel(40 - t / 2, 18 + t / 2, 255, 255, 255, 255);
      c.SetPixel(40 + t / 2, 18 + t / 2, 255, 255, 255, 255);
    }
    c.FillRect(26, 32, 54, 52, 255, 255, 255);
    c.FillRect(36, 42, 44, 52, 40, 170, 90);
    c.Text(100, 28, "PINA XR NAVEGADOR", 2, 200, 210, 230);
    c.TextCentered(kBrowserTexW / 2, kBrowserTexH - 22,
                   "PINCE NOS LINKS DA PAGINA", 1, 120, 130, 150);

    if (browser_texture_ != 0) glDeleteTextures(1, &browser_texture_);
    browser_texture_ =
        CreateRgbaTexture(kBrowserTexW, kBrowserTexH, c.px.data());

    browser_frame_panel_.regions = {
        {704.0f / kBrowserTexW, 8.0f / kBrowserTexH, 762.0f / kBrowserTexW,
         62.0f / kBrowserTexH, kBrowserClose},
        {6.0f / kBrowserTexW, 8.0f / kBrowserTexH, 74.0f / kBrowserTexW,
         62.0f / kBrowserTexH, kBrowserHome},
    };
  }

  // ------------------------------------------------------------------
  // Pagina inicial + textura da pagina
  // ------------------------------------------------------------------
  ResetPage();

  home_panel_.width = kHomePanelW;
  home_panel_.height = kHomePanelH;
  home_panel_.texture = home_texture_;

  browser_frame_panel_.width = kBrowserPanelW;
  browser_frame_panel_.height = kBrowserPanelH;
  browser_frame_panel_.texture = browser_texture_;

  const float content_w = kBrowserPanelW * 745.0f / 768.0f;
  const float content_h = content_w * 376.0f / 745.0f;
  page_panel_.width = content_w;
  page_panel_.height = content_h;
  page_panel_.texture = page_texture_;

  ShowHome();
}

void PinaApp::ResetPage() {
  UiCanvas c(768, 384);
  c.FillRect(0, 0, 767, 383, 24, 26, 34, 255);
  c.TextCentered(384, 52, "PINA XR", 6, 80, 220, 255);
  c.TextCentered(384, 110, "NAVEGADOR 3D - BETA", 2, 150, 160, 180);
  c.FillRoundRect(140, 180, 620, 244, 10, 40, 46, 66, 255);
  c.TextCentered(384, 202, "GOOGLE.COM", 2, 120, 230, 255);
  c.FillRoundRect(140, 268, 620, 332, 10, 40, 46, 66, 255);
  c.TextCentered(384, 290, "EXAMPLE.COM", 2, 120, 230, 255);
  c.TextCentered(384, 356, "PINCE PARA ABRIR", 1, 120, 130, 150);

  if (start_page_texture_ != 0) {
    glDeleteTextures(1, &start_page_texture_);
  }
  start_page_texture_ = CreateRgbaTexture(768, 384, c.px.data());

  page_links_ = {
      {140.0f / 768, 180.0f / 384, 620.0f / 768, 244.0f / 384,
       "https://www.google.com"},
      {140.0f / 768, 268.0f / 384, 620.0f / 768, 332.0f / 384,
       "https://example.com"},
  };

  if (page_texture_ != 0 && page_texture_ != start_page_texture_) {
    glDeleteTextures(1, &page_texture_);
  }
  page_texture_ = start_page_texture_;
  page_tex_width_ = 768;
  page_tex_height_ = 384;
  page_panel_.texture = page_texture_;
}

// ===========================================================================
// Parametros do dispositivo (QR do visor) - fluxo oficial do Cardboard
// ===========================================================================

bool PinaApp::UpdateDeviceParams() {
  if (!screen_params_changed_ && !device_params_changed_) {
    return true;
  }

  uint8_t* buffer;
  int size;
  CardboardQrCode_getSavedDeviceParams(&buffer, &size);

  if (size == 0) {
    return false;
  }

  CardboardLensDistortion_destroy(lens_distortion_);
  lens_distortion_ = CardboardLensDistortion_create(buffer, size, screen_width_,
                                                    screen_height_);

  CardboardQrCode_destroy(buffer);

  GlSetup();

  CardboardDistortionRenderer_destroy(distortion_renderer_);
  const CardboardOpenGlEsDistortionRendererConfig config{kGlTexture2D};
  distortion_renderer_ = CardboardOpenGlEs2DistortionRenderer_create(&config);

  CardboardMesh left_mesh;
  CardboardMesh right_mesh;
  CardboardLensDistortion_getDistortionMesh(lens_distortion_, kLeft,
                                            &left_mesh);
  CardboardLensDistortion_getDistortionMesh(lens_distortion_, kRight,
                                            &right_mesh);

  CardboardDistortionRenderer_setMesh(distortion_renderer_, &left_mesh, kLeft);
  CardboardDistortionRenderer_setMesh(distortion_renderer_, &right_mesh,
                                      kRight);

  CardboardLensDistortion_getEyeFromHeadMatrix(lens_distortion_, kLeft,
                                               eye_matrices_[0]);
  CardboardLensDistortion_getEyeFromHeadMatrix(lens_distortion_, kRight,
                                               eye_matrices_[1]);
  CardboardLensDistortion_getProjectionMatrix(lens_distortion_, kLeft, kZNear,
                                              kZFar, projection_matrices_[0]);
  CardboardLensDistortion_getProjectionMatrix(lens_distortion_, kRight, kZNear,
                                              kZFar, projection_matrices_[1]);

  screen_params_changed_ = false;
  device_params_changed_ = false;

  CHECKGLERROR("UpdateDeviceParams");
  return true;
}

void PinaApp::GlSetup() {
  LOGD("GL SETUP");

  if (framebuffer_ != 0) {
    GlTeardown();
  }

  glGenTextures(1, &texture_);
  glBindTexture(GL_TEXTURE_2D, texture_);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
  glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB, screen_width_, screen_height_, 0,
               GL_RGB, GL_UNSIGNED_BYTE, 0);

  left_eye_texture_description_.texture = texture_;
  left_eye_texture_description_.left_u = 0;
  left_eye_texture_description_.right_u = 0.5;
  left_eye_texture_description_.top_v = 1;
  left_eye_texture_description_.bottom_v = 0;

  right_eye_texture_description_.texture = texture_;
  right_eye_texture_description_.left_u = 0.5;
  right_eye_texture_description_.right_u = 1;
  right_eye_texture_description_.top_v = 1;
  right_eye_texture_description_.bottom_v = 0;

  glGenRenderbuffers(1, &depth_render_buffer_);
  glBindRenderbuffer(GL_RENDERBUFFER, depth_render_buffer_);
  glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT16, screen_width_,
                        screen_height_);
  CHECKGLERROR("Create Render buffer");

  glGenFramebuffers(1, &framebuffer_);
  glBindFramebuffer(GL_FRAMEBUFFER, framebuffer_);
  glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
                         texture_, 0);
  glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT,
                            GL_RENDERBUFFER, depth_render_buffer_);

  CHECKGLERROR("GlSetup");
}

void PinaApp::GlTeardown() {
  if (framebuffer_ == 0) {
    return;
  }
  glDeleteRenderbuffers(1, &depth_render_buffer_);
  depth_render_buffer_ = 0;
  glDeleteFramebuffers(1, &framebuffer_);
  framebuffer_ = 0;
  glDeleteTextures(1, &texture_);
  texture_ = 0;

  CHECKGLERROR("GlTeardown");
}

Matrix4x4 PinaApp::GetPose() {
  std::array<float, 4> out_orientation;
  std::array<float, 3> out_position;
  CardboardHeadTracker_getPose(head_tracker_,
                               GetBootTimeNano() + 50000000, kLandscapeLeft,
                               &out_position[0], &out_orientation[0]);
  return GetTranslationMatrix(out_position) *
         Quatf::FromXYZW(&out_orientation[0]).ToMatrix();
}

// ===========================================================================
// Paineis
// ===========================================================================

void PinaApp::PlacePanelsInFront() {
  const std::array<float, 4> forward4 = head_view_ * ToVec4({0, 0, -1}, 0.0f);
  std::array<float, 3> forward = Normalize3(FromVec4(forward4));
  forward[1] = 0.0f;
  const std::array<float, 3> fwd = Normalize3(forward);

  const std::array<float, 4> head_pos4 = head_view_ * ToVec4({0, 0, 0}, 1.0f);
  const std::array<float, 3> head_pos = FromVec4(head_pos4);

  const float yaw = std::atan2(-fwd[0], -fwd[2]);
  Quatf yaw_quat(0.0f, std::sin(yaw / 2.0f), 0.0f, std::cos(yaw / 2.0f));

  auto make_model = [&](const std::array<float, 3>& pos, float w, float h) {
    Matrix4x4 scale;
    scale.m[0][0] = w;
    scale.m[1][1] = h;
    scale.m[2][2] = 1.0f;
    scale.m[3][3] = 1.0f;
    Matrix4x4 t = GetTranslationMatrix(pos);
    Matrix4x4 rot = yaw_quat.ToMatrix();
    return t * rot * scale;
  };

  const std::array<float, 3> base = Add3(head_pos, Scale3(fwd, kPanelDistance));
  home_panel_.position = {base[0], kPanelHeight, base[2]};
  Matrix4x4 home_model =
      make_model(home_panel_.position, kHomePanelW, kHomePanelH);
  home_panel_.world_from_panel = home_model;

  browser_frame_panel_.position = {base[0], kPanelHeight, base[2]};
  Matrix4x4 frame_model = make_model(
      browser_frame_panel_.position, kBrowserPanelW, kBrowserPanelH);
  browser_frame_panel_.world_from_panel = frame_model;

  // Pagina um pouco na frente da moldura, alinhada com a area de conteudo.
  const float up_offset =
      (240.0f - (70.0f + 446.0f) / 2.0f) / 480.0f * kBrowserPanelH;
  const std::array<float, 3> normal = Scale3(fwd, -1.0f);  // aponta pro usuario
  page_panel_.position =
      Add3(Add3(browser_frame_panel_.position, Scale3(normal, kBrowserPageInset)),
           {0.0f, up_offset, 0.0f});
  Matrix4x4 page_model =
      make_model(page_panel_.position, page_panel_.width, page_panel_.height);
  page_panel_.world_from_panel = page_model;
}

void PinaApp::ShowHome() {
  home_panel_.visible = true;
  browser_frame_panel_.visible = false;
  page_panel_.visible = false;
  PlacePanelsInFront();
}

void PinaApp::OpenBrowser() {
  home_panel_.visible = false;
  browser_frame_panel_.visible = true;
  page_panel_.visible = true;
  PlacePanelsInFront();
}

void PinaApp::CloseBrowser() {
  ShowHome();
}

// ===========================================================================
// Frame
// ===========================================================================

void PinaApp::OnDrawFrame() {
  if (!UpdateDeviceParams()) {
    return;
  }

  head_view_ = GetPose();

  HandleInteraction();

  glBindFramebuffer(GL_FRAMEBUFFER, framebuffer_);

  glEnable(GL_DEPTH_TEST);
  glEnable(GL_CULL_FACE);
  glDisable(GL_SCISSOR_TEST);
  glEnable(GL_BLEND);
  glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
  glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
  glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

  for (int eye = 0; eye < 2; ++eye) {
    glViewport(eye == kLeft ? 0 : screen_width_ / 2, 0, screen_width_ / 2,
               screen_height_);

    Matrix4x4 eye_matrix = GetMatrixFromGlArray(eye_matrices_[eye]);
    Matrix4x4 eye_view = eye_matrix * head_view_;
    Matrix4x4 projection = GetMatrixFromGlArray(projection_matrices_[eye]);

    DrawWorld(eye_view, projection);
  }

  CardboardDistortionRenderer_renderEyeToDisplay(
      distortion_renderer_, /* target_display = */ 0, /* x = */ 0, /* y = */ 0,
      screen_width_, screen_height_, &left_eye_texture_description_,
      &right_eye_texture_description_);

  CHECKGLERROR("onDrawFrame");
}

void PinaApp::DrawWorld(Matrix4x4 eye_view, Matrix4x4 projection) {
  DrawPassthrough(eye_view, projection);

  glDisable(GL_CULL_FACE);  // quads de UI visiveis dos dois lados

  if (page_panel_.visible) {
    DrawQuad(page_panel_, eye_view, projection, /*blend=*/false,
             /*depth_write=*/true);
  }
  if (browser_frame_panel_.visible) {
    DrawQuad(browser_frame_panel_, eye_view, projection, /*blend=*/true,
             /*depth_write=*/false);
  }
  if (home_panel_.visible) {
    DrawQuad(home_panel_, eye_view, projection, /*blend=*/true,
             /*depth_write=*/false);
  }

  DrawHandsAndPointer(eye_view, projection);

  glEnable(GL_CULL_FACE);
}

void PinaApp::DrawPassthrough(Matrix4x4 eye_view, Matrix4x4 projection) {
  if (!camera_texture_ready_) {
    return;
  }
  glDisable(GL_DEPTH_TEST);
  glDepthMask(GL_FALSE);

  glUseProgram(passthrough_program_);
  Matrix4x4 mvp = projection * eye_view;
  std::array<float, 16> mvp_array = mvp.ToGlArray();
  glUniformMatrix4fv(pt_mvp_param_, 1, GL_FALSE, mvp_array.data());
  glUniform2f(pt_fov_param_, kCameraFovX, kCameraFovY);
  glUniform1f(pt_mirror_param_, kPassthroughMirror ? 1.0f : 0.0f);

  glActiveTexture(GL_TEXTURE0);
  glBindTexture(GL_TEXTURE_2D, camera_texture_);
  glUniform1i(pt_texture_param_, 0);

  glEnableVertexAttribArray(pt_position_param_);
  glVertexAttribPointer(pt_position_param_, 3, GL_FLOAT, GL_FALSE, 0,
                        sphere_vertices_.data());
  glDrawElements(GL_TRIANGLES, sphere_indices_.size(), GL_UNSIGNED_SHORT,
                 sphere_indices_.data());
  glDisableVertexAttribArray(pt_position_param_);

  glEnable(GL_DEPTH_TEST);
  glDepthMask(GL_TRUE);
  CHECKGLERROR("DrawPassthrough");
}

void PinaApp::DrawQuad(const Panel& panel, Matrix4x4 eye_view,
                       Matrix4x4 projection, bool blend, bool depth_write) {
  glUseProgram(quad_program_);
  Matrix4x4 mvp = projection * eye_view * panel.world_from_panel;
  std::array<float, 16> mvp_array = mvp.ToGlArray();
  glUniformMatrix4fv(quad_mvp_param_, 1, GL_FALSE, mvp_array.data());

  glActiveTexture(GL_TEXTURE0);
  glBindTexture(GL_TEXTURE_2D, panel.texture);
  glUniform1i(quad_texture_param_, 0);
  glUniform1f(quad_alpha_param_, 1.0f);

  if (blend) {
    glEnable(GL_BLEND);
  } else {
    glDisable(GL_BLEND);
  }
  glDepthMask(depth_write ? GL_TRUE : GL_FALSE);

  glEnableVertexAttribArray(quad_position_param_);
  glEnableVertexAttribArray(quad_uv_param_);
  glVertexAttribPointer(quad_position_param_, 3, GL_FLOAT, GL_FALSE, 0,
                        quad_vertices_.data());
  glVertexAttribPointer(quad_uv_param_, 2, GL_FLOAT, GL_FALSE, 0,
                        quad_uv_.data());
  glDrawElements(GL_TRIANGLES, quad_indices_.size(), GL_UNSIGNED_SHORT,
                 quad_indices_.data());
  glDisableVertexAttribArray(quad_position_param_);
  glDisableVertexAttribArray(quad_uv_param_);
  glEnable(GL_BLEND);
  glDepthMask(GL_TRUE);
  CHECKGLERROR("DrawQuad");
}

void PinaApp::DrawHandsAndPointer(Matrix4x4 eye_view, Matrix4x4 projection) {
  if (!has_hands_ && !pointer_valid_) return;

  glUseProgram(color_program_);
  Matrix4x4 mvp = projection * eye_view;
  std::array<float, 16> mvp_array = mvp.ToGlArray();
  glUniformMatrix4fv(color_mvp_param_, 1, GL_FALSE, mvp_array.data());
  glDepthMask(GL_FALSE);
  glEnable(GL_BLEND);

  // --- esqueleto das maos ---
  std::array<std::array<float, 3>, 42> joints;
  const bool present[2] = {hands_.has_left, hands_.has_right};
  for (int h = 0; h < 2; ++h) {
    if (!present[h]) continue;
    const std::array<float, 63>& lm = (h == 0) ? hands_.left : hands_.right;
    for (int i = 0; i < 21; ++i) {
      const std::array<float, 3> p_head = HeadSpaceFromImage(
          lm[i * 3], lm[i * 3 + 1], lm[i * 3 + 2]);
      joints[h * 21 + i] = FromVec4(head_view_ * ToVec4(p_head, 1.0f));
    }
  }

  glEnableVertexAttribArray(color_position_param_);

  // ossos (linhas)
  {
    std::vector<GLfloat> bone_buf;
    bone_buf.reserve(2 * 2 * kNumBones * 6);
    for (int h = 0; h < 2; ++h) {
      if (!present[h]) continue;
      for (int b = 0; b < kNumBones; ++b) {
        const std::array<float, 3>& a = joints[h * 21 + kBones[b][0]];
        const std::array<float, 3>& c = joints[h * 21 + kBones[b][1]];
        bone_buf.insert(bone_buf.end(), {a[0], a[1], a[2], c[0], c[1], c[2]});
      }
    }
    if (!bone_buf.empty()) {
      glUniform4f(color_param_, 0.30f, 0.85f, 1.0f, 0.95f);
      glUniform1f(color_point_size_param_, 1.0f);
      glUniform1f(color_is_point_param_, 0.0f);
      glVertexAttribPointer(color_position_param_, 3, GL_FLOAT, GL_FALSE, 0,
                            bone_buf.data());
      glDrawArrays(GL_LINES, 0, static_cast<GLsizei>(bone_buf.size() / 3));
    }
  }

  // juntas (pontos brancos)
  {
    std::vector<GLfloat> joint_buf;
    for (int h = 0; h < 2; ++h) {
      if (!present[h]) continue;
      for (int i = 0; i < 21; ++i) {
        const std::array<float, 3>& p = joints[h * 21 + i];
        joint_buf.insert(joint_buf.end(), {p[0], p[1], p[2]});
      }
    }
    if (!joint_buf.empty()) {
      glUniform4f(color_param_, 1.0f, 1.0f, 1.0f, 0.95f);
      glUniform1f(color_point_size_param_, 14.0f);
      glUniform1f(color_is_point_param_, 1.0f);
      glVertexAttribPointer(color_position_param_, 3, GL_FLOAT, GL_FALSE, 0,
                            joint_buf.data());
      glDrawArrays(GL_POINTS, 0, static_cast<GLsizei>(joint_buf.size() / 3));
    }
  }

  // destaque do pinch (polegar + indicador em verde)
  for (int h = 0; h < 2; ++h) {
    const bool pinching = (h == 0) ? hands_.pinch_left : hands_.pinch_right;
    if (!present[h] || !pinching) continue;
    std::vector<GLfloat> pinch_pts;
    for (int i : {4, 8}) {
      const std::array<float, 3>& p = joints[h * 21 + i];
      pinch_pts.insert(pinch_pts.end(), {p[0], p[1], p[2]});
    }
    glUniform4f(color_param_, 0.2f, 1.0f, 0.4f, 1.0f);
    glUniform1f(color_point_size_param_, 20.0f);
    glUniform1f(color_is_point_param_, 1.0f);
    glVertexAttribPointer(color_position_param_, 3, GL_FLOAT, GL_FALSE, 0,
                          pinch_pts.data());
    glDrawArrays(GL_POINTS, 0, 2);
  }

  // --- apontador: linha + bolinha ---
  if (pointer_valid_) {
    glUniform1f(color_is_point_param_, 0.0f);
    glUniform1f(color_point_size_param_, 1.0f);
    glUniform4f(color_param_, pointer_color_[0], pointer_color_[1],
                pointer_color_[2], 0.95f);
    std::array<GLfloat, 6> line = {
        pointer_origin_w_[0], pointer_origin_w_[1], pointer_origin_w_[2],
        pointer_end_w_[0],    pointer_end_w_[1],    pointer_end_w_[2]};
    glVertexAttribPointer(color_position_param_, 3, GL_FLOAT, GL_FALSE, 0,
                          line.data());
    glDrawArrays(GL_LINES, 0, 2);

    glUniform1f(color_is_point_param_, 1.0f);
    glUniform1f(color_point_size_param_, 26.0f);
    glVertexAttribPointer(color_position_param_, 3, GL_FLOAT, GL_FALSE, 0,
                          line.data() + 3);
    glDrawArrays(GL_POINTS, 0, 1);
  }

  glDisableVertexAttribArray(color_position_param_);
  glDepthMask(GL_TRUE);
  CHECKGLERROR("DrawHandsAndPointer");
}

// ===========================================================================
// Interacao: raycast do apontador + clique por pinch
// ===========================================================================

void PinaApp::HandleInteraction() {
  pointer_valid_ = false;
  hover_type_ = kHoverNone;
  hover_id_ = kButtonNone;
  hover_url_.clear();

  const bool use_right = hands_.has_right;
  const bool use_left = !use_right && hands_.has_left;
  if (!use_right && !use_left) {
    pinch_was_down_ = false;
    return;
  }

  const int hand = use_right ? 1 : 0;
  const std::array<float, 63>& lm = (hand == 1) ? hands_.right : hands_.left;
  const bool pinching = (hand == 1) ? hands_.pinch_right : hands_.pinch_left;

  // Pontos-chave no espaco da cabeca.
  const std::array<float, 3> p_index =
      HeadSpaceFromImage(lm[8 * 3], lm[8 * 3 + 1], lm[8 * 3 + 2]);
  const std::array<float, 3> p_thumb =
      HeadSpaceFromImage(lm[4 * 3], lm[4 * 3 + 1], lm[4 * 3 + 2]);
  const std::array<float, 3> p_mcp =
      HeadSpaceFromImage(lm[5 * 3], lm[5 * 3 + 1], lm[5 * 3 + 2]);

  const std::array<float, 3> mid = Scale3(Add3(p_index, p_thumb), 0.5f);
  std::array<float, 3> dir = Sub3(mid, p_mcp);
  if (Dot3(dir, dir) < 1e-10f) {
    dir = {0.0f, 0.0f, -1.0f};
  }
  dir = Normalize3(dir);

  // Para o mundo (rotacao + posicao da cabeca).
  const std::array<float, 4> o4 = head_view_ * ToVec4(mid, 1.0f);
  const std::array<float, 4> d4 = head_view_ * ToVec4(dir, 0.0f);
  pointer_origin_w_ = FromVec4(o4);
  const std::array<float, 3> dir_w = Normalize3(FromVec4(d4));
  pointer_end_w_ = Add3(pointer_origin_w_, Scale3(dir_w, 2.8f));
  pointer_valid_ = true;

  // --- raycast ---
  auto raycast = [&](const Panel& panel, float* u, float* v) -> bool {
    if (!panel.visible) return false;
    const std::array<float, 4> c4 =
        panel.world_from_panel * ToVec4({0, 0, 0}, 1.0f);
    const std::array<float, 4> r4 =
        panel.world_from_panel * ToVec4({1, 0, 0}, 0.0f);
    const std::array<float, 4> u4 =
        panel.world_from_panel * ToVec4({0, 1, 0}, 0.0f);
    const std::array<float, 4> n4 =
        panel.world_from_panel * ToVec4({0, 0, 1}, 0.0f);
    const std::array<float, 3> center = FromVec4(c4);
    const std::array<float, 3> right = FromVec4(r4);
    const std::array<float, 3> up = FromVec4(u4);
    const std::array<float, 3> normal = FromVec4(n4);

    const float denom = Dot3(dir_w, normal);
    if (std::abs(denom) < 1e-6f) return false;
    const float t = Dot3(Sub3(center, pointer_origin_w_), normal) / denom;
    if (t <= 0.0f) return false;
    const std::array<float, 3> p = Add3(pointer_origin_w_, Scale3(dir_w, t));
    const std::array<float, 3> q = Sub3(p, center);
    if (std::abs(Dot3(q, right)) > panel.width / 2.0f) return false;
    if (std::abs(Dot3(q, up)) > panel.height / 2.0f) return false;
    *u = 0.5f + Dot3(q, right) / panel.width;
    *v = 0.5f - Dot3(q, up) / panel.height;  // v=0 no topo
    return true;
  };

  float u = 0.0f, v = 0.0f;
  float pu = 0.0f, pv = 0.0f;
  const bool on_page = page_panel_.visible && raycast(page_panel_, &pu, &pv);
  const bool on_frame =
      browser_frame_panel_.visible && raycast(browser_frame_panel_, &u, &v);
  const bool on_home = home_panel_.visible && raycast(home_panel_, &u, &v);

  if (on_page) {
    for (const PageLink& link : page_links_) {
      if (pu >= link.u0 && pu <= link.u1 && pv >= link.v0 && pv <= link.v1) {
        hover_type_ = kHoverLink;
        hover_url_ = link.url;
        break;
      }
    }
  }
  if (hover_type_ == kHoverNone && on_frame) {
    for (const Panel::HitRegion& region : browser_frame_panel_.regions) {
      if (u >= region.u0 && u <= region.u1 && v >= region.v0 && v <= region.v1) {
        hover_type_ = kHoverButton;
        hover_id_ = region.id;
        break;
      }
    }
  }
  if (hover_type_ == kHoverNone && on_home) {
    for (const Panel::HitRegion& region : home_panel_.regions) {
      if (u >= region.u0 && u <= region.u1 && v >= region.v0 && v <= region.v1) {
        hover_type_ = kHoverButton;
        hover_id_ = region.id;
        break;
      }
    }
  }

  // Cor do apontador: verde (pinch), amarelo (hover), branco (livre).
  if (pinching) {
    pointer_color_ = {0.2f, 1.0f, 0.4f, 1.0f};
  } else if (hover_type_ != kHoverNone) {
    pointer_color_ = {1.0f, 0.9f, 0.2f, 1.0f};
  } else {
    pointer_color_ = {1.0f, 1.0f, 1.0f, 1.0f};
  }

  // Clique na borda de subida do pinch.
  if (pinching && !pinch_was_down_) {
    ClickCurrentHover();
  }
  pinch_was_down_ = pinching;
}

void PinaApp::ClickCurrentHover() {
  if (hover_type_ == kHoverLink) {
    LOGD("Abrindo link: %s", hover_url_.c_str());
    NotifyOpenUrl(hover_url_);
    return;
  }
  if (hover_type_ != kHoverButton) return;
  switch (hover_id_) {
    case kHomeBrowser:
      OpenBrowser();
      break;
    case kHomeViewer:
      SwitchViewer();
      break;
    case kHomeExit:
      NotifyExit();
      break;
    case kBrowserClose:
      CloseBrowser();
      NotifyCloseBrowser();
      break;
    case kBrowserHome:
      ResetPage();
      NotifyCloseBrowser();
      break;
    default:
      break;
  }
}

void PinaApp::OnTriggerEvent() {
  // Botao fisico do visor: clique no que estiver sob o apontador.
  ClickCurrentHover();
}

// ===========================================================================
// Texturas enviadas do Java
// ===========================================================================

void PinaApp::UpdateCameraFrame(JNIEnv* env, jobject byte_buffer, jint width,
                                jint height) {
  if (byte_buffer == nullptr || width <= 0 || height <= 0) return;
  auto* data = static_cast<uint8_t*>(env->GetDirectBufferAddress(byte_buffer));
  if (data == nullptr) return;

  if (camera_texture_ == 0) {
    glGenTextures(1, &camera_texture_);
    glBindTexture(GL_TEXTURE_2D, camera_texture_);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
  }
  glBindTexture(GL_TEXTURE_2D, camera_texture_);
  if (width != camera_tex_width_ || height != camera_tex_height_) {
    camera_tex_width_ = width;
    camera_tex_height_ = height;
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA,
                 GL_UNSIGNED_BYTE, data);
  } else {
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, GL_RGBA,
                    GL_UNSIGNED_BYTE, data);
  }
  camera_texture_ready_ = true;
}

void PinaApp::UpdatePage(JNIEnv* env, jobject byte_buffer, jint width,
                         jint height, jfloatArray rects, jobjectArray urls) {
  if (byte_buffer != nullptr && width > 0 && height > 0) {
    auto* data =
        static_cast<uint8_t*>(env->GetDirectBufferAddress(byte_buffer));
    if (data == nullptr) return;

    // Cria textura propria da pagina se estiver usando a pagina inicial.
    if (page_texture_ == 0 || page_texture_ == start_page_texture_) {
      page_texture_ = 0;
      glGenTextures(1, &page_texture_);
      glBindTexture(GL_TEXTURE_2D, page_texture_);
      glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
      glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
      glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
      glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    } else {
      glBindTexture(GL_TEXTURE_2D, page_texture_);
    }
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA,
                 GL_UNSIGNED_BYTE, data);
    page_tex_width_ = width;
    page_tex_height_ = height;
    page_panel_.texture = page_texture_;

    // Areas clicaveis da pagina.
    page_links_.clear();
    if (rects != nullptr && urls != nullptr) {
      const jsize n_rects = env->GetArrayLength(rects) / 4;
      const jsize n_urls = env->GetArrayLength(urls);
      const jsize n = std::min(n_rects, n_urls);
      if (n > 0) {
        jfloat* r = env->GetFloatArrayElements(rects, nullptr);
        for (jsize i = 0; i < n; ++i) {
          jstring jurl = (jstring)env->GetObjectArrayElement(urls, i);
          if (jurl == nullptr) continue;
          const char* url = env->GetStringUTFChars(jurl, nullptr);
          PageLink link;
          link.u0 = r[i * 4 + 0];
          link.v0 = r[i * 4 + 1];
          link.u1 = r[i * 4 + 2];
          link.v1 = r[i * 4 + 3];
          link.url = (url != nullptr) ? url : "";
          page_links_.push_back(link);
          env->ReleaseStringUTFChars(jurl, url);
          env->DeleteLocalRef(jurl);
        }
        env->ReleaseFloatArrayElements(rects, r, JNI_ABORT);
      }
    }
  } else {
    // Restaura a pagina inicial.
    if (page_texture_ != 0 && page_texture_ != start_page_texture_) {
      glDeleteTextures(1, &page_texture_);
    }
    page_texture_ = start_page_texture_;
    page_panel_.texture = page_texture_;
    ResetPage();
  }
}

// ===========================================================================
// Callbacks para o Java
// ===========================================================================

void PinaApp::NotifyOpenUrl(const std::string& url) {
  JNIEnv* env = nullptr;
  if (java_vm_->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK || env == nullptr)
    return;
  jstring jurl = env->NewStringUTF(url.c_str());
  env->CallVoidMethod(java_activity_, method_open_url_, jurl);
  env->DeleteLocalRef(jurl);
}

void PinaApp::NotifyCloseBrowser() {
  JNIEnv* env = nullptr;
  if (java_vm_->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK || env == nullptr)
    return;
  env->CallVoidMethod(java_activity_, method_close_browser_);
}

void PinaApp::NotifyExit() {
  JNIEnv* env = nullptr;
  if (java_vm_->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK || env == nullptr)
    return;
  env->CallVoidMethod(java_activity_, method_exit_);
}

}  // namespace pina_xr
