/*
 * Pina XR - beta 0.2
 *
 * Renderizador nativo: estereoscopia Cardboard (distortion + 3DOF),
 * passthrough da camera (esfera), esqueleto das maos (MediaPipe via Java, com
 * filtros Kalman + One Euro no lado Java), apontador 3D (linha + bolinha,
 * clique por pinch) e launcher estilo Meta Quest com apps:
 * Navegador 3D, Videos do celular (MediaPlayer -> OES), Videos 360,
 * Alvos 3D (jogo) e Simon 3D (jogo). Nada de UI 2D.
 */

#include "pina_app.h"

#include <android/asset_manager_jni.h>
#include <android/log.h>

#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstdio>
#include <cstring>

#include "cardboard.h"
#include "font.h"

namespace pina_xr {

namespace {

// ---------------------------------------------------------------------------
// Shaders
// ---------------------------------------------------------------------------

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
    uniform vec2 u_Fov;
    uniform float u_Mirror;
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

// Video em quad (SurfaceTexture -> OES).
constexpr const char* kOesQuadVertexShader =
    R"glsl(
    uniform mat4 u_MVP;
    attribute vec3 a_Position;
    attribute vec2 a_UV;
    varying vec2 v_UV;
    void main() {
      v_UV = a_UV;
      gl_Position = u_MVP * vec4(a_Position, 1.0);
    })glsl";

constexpr const char* kOesQuadFragmentShader =
    R"glsl(
    #extension GL_OES_EGL_image_external : require
    precision mediump float;
    uniform samplerExternalOES u_Texture;
    uniform mat4 u_TexMatrix;
    varying vec2 v_UV;
    void main() {
      vec2 uv = (u_TexMatrix * vec4(v_UV, 0.0, 1.0)).xy;
      gl_FragColor = texture2D(u_Texture, uv);
    })glsl";

// Video 360 equiretangular dentro da esfera.
constexpr const char* kOes360VertexShader =
    R"glsl(
    uniform mat4 u_MVP;
    attribute vec3 a_Position;
    varying vec2 v_UV;
    void main() {
      vec3 d = normalize(a_Position);
      float u = 0.5 + atan(d.x, -d.z) / 6.28318530718;
      float v = 0.5 - asin(clamp(d.y, -1.0, 1.0)) / 3.14159265359;
      v_UV = vec2(u, v);
      gl_Position = u_MVP * vec4(a_Position, 1.0);
    })glsl";

constexpr const char* kOes360FragmentShader =
    R"glsl(
    #extension GL_OES_EGL_image_external : require
    precision mediump float;
    uniform samplerExternalOES u_Texture;
    uniform mat4 u_TexMatrix;
    varying vec2 v_UV;
    void main() {
      vec2 uv = (u_TexMatrix * vec4(v_UV, 0.0, 1.0)).xy;
      gl_FragColor = texture2D(u_Texture, uv);
    })glsl";

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

const int kBones[][2] = {
    {0, 1}, {1, 2}, {2, 3}, {3, 4},
    {0, 5}, {5, 6}, {6, 7}, {7, 8},
    {5, 9}, {9, 10}, {10, 11}, {11, 12},
    {9, 13}, {13, 14}, {14, 15}, {15, 16},
    {13, 17}, {17, 18}, {18, 19}, {19, 20},
    {0, 17},
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

double NowSeconds() {
  struct timespec res;
  clock_gettime(CLOCK_MONOTONIC, &res);
  return static_cast<double>(res.tv_sec) +
         static_cast<double>(res.tv_nsec) / 1e9;
}

std::array<float, 3> HeadSpaceFromImage(float x, float y, float z) {
  const float yaw = (x - 0.5f) * kCameraFovX;
  const float pitch = (0.5f - y) * kCameraFovY;
  const float dist = kHandDistance + z * kHandZScale;
  const float cp = std::cos(pitch);
  return {std::sin(yaw) * cp * dist, std::sin(pitch) * dist,
          -std::cos(yaw) * cp * dist};
}

// --- canvas 2D para as texturas de UI ---
struct UiCanvas {
  int w;
  int h;
  std::vector<uint8_t> px;

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
        const float d = std::sqrt(
            static_cast<float>((x - cx) * (x - cx) + (y - cy) * (y - cy)));
        if (d <= outer && d >= inner) SetPixel(x, y, r, g, b, a);
      }
    }
  }

  void FillCircle(int cx, int cy, int radius, uint8_t r, uint8_t g, uint8_t b,
                  uint8_t a = 255) {
    for (int y = cy - radius; y <= cy + radius; ++y)
      for (int x = cx - radius; x <= cx + radius; ++x)
        if ((x - cx) * (x - cx) + (y - cy) * (y - cy) <= radius * radius)
          SetPixel(x, y, r, g, b, a);
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

// Base64 (alfabeto web-safe, sem padding) - formato aceito pelo parser de URI
// do Cardboard (https://google.com/cardboard/cfd?p=<params>).
std::string Base64UrlEncode(const uint8_t* data, size_t len) {
  static const char kAlphabet[] =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
  std::string out;
  out.reserve(((len + 2) / 3) * 4);
  size_t i = 0;
  while (i + 3 <= len) {
    const uint32_t v = (data[i] << 16) | (data[i + 1] << 8) | data[i + 2];
    out += kAlphabet[(v >> 18) & 63];
    out += kAlphabet[(v >> 12) & 63];
    out += kAlphabet[(v >> 6) & 63];
    out += kAlphabet[v & 63];
    i += 3;
  }
  const size_t rest = len - i;
  if (rest == 1) {
    const uint32_t v = data[i] << 16;
    out += kAlphabet[(v >> 18) & 63];
    out += kAlphabet[(v >> 12) & 63];
  } else if (rest == 2) {
    const uint32_t v = (data[i] << 16) | (data[i + 1] << 8);
    out += kAlphabet[(v >> 18) & 63];
    out += kAlphabet[(v >> 12) & 63];
    out += kAlphabet[(v >> 6) & 63];
  }
  return out;
}

// Salva o perfil padrao do Cardboard V1 como device params, sem precisar
// escanear QR. O app ja abre com distorcao generica decente; o usuario ainda
// pode escanear o QR do visor pelo botao VISOR do painel de status.
void SaveDefaultDeviceParams() {
  uint8_t* v1 = nullptr;
  int v1_size = 0;
  CardboardQrCode_getCardboardV1DeviceParams(&v1, &v1_size);
  if (v1 == nullptr || v1_size <= 0) {
    LOGE("CardboardQrCode_getCardboardV1DeviceParams vazio");
    return;
  }
  const std::string uri =
      "https://google.com/cardboard/cfd?p=" + Base64UrlEncode(v1, v1_size);
  CardboardQrCode_saveDeviceParams(
      reinterpret_cast<const uint8_t*>(uri.data()),
      static_cast<int>(uri.size()));
  CardboardQrCode_destroy(v1);

  uint8_t* check = nullptr;
  int check_size = 0;
  CardboardQrCode_getSavedDeviceParams(&check, &check_size);
  LOGI("Perfil de visor padrao (Cardboard V1) salvo (%d bytes)", check_size);
  CardboardQrCode_destroy(check);
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
  method_request_videos_ =
      env->GetMethodID(activity_class, "onNativeRequestVideos", "()V");
  method_play_video_ =
      env->GetMethodID(activity_class, "onNativePlayVideo", "(JZ)V");
  method_video_control_ =
      env->GetMethodID(activity_class, "onNativeVideoControl", "(I)V");

  targets_.resize(kTargetCount);

  Cardboard_initializeAndroid(vm, activity_obj);
  head_tracker_ = CardboardHeadTracker_create();
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

  // Sem perfil salvo: salva o perfil padrao (Cardboard V1) e segue. NAO
  // abrimos mais o scanner automaticamente: em aparelhos sem o Google Play
  // Services atualizado o scanner quebrava e o app fechava segundos apos
  // abrir. O scanner fica no botao VISOR do painel de status.
  uint8_t* buffer = nullptr;
  int size = 0;
  CardboardQrCode_getSavedDeviceParams(&buffer, &size);
  CardboardQrCode_destroy(buffer);
  if (size == 0) {
    SaveDefaultDeviceParams();
    device_params_changed_ = true;
  }
}

void PinaApp::SwitchViewer() { CardboardQrCode_scanQrCodeAndSaveDeviceParams(); }

void PinaApp::SetScreenParams(int width, int height) {
  screen_width_ = width;
  screen_height_ = height;
  screen_params_changed_ = true;
}

// ===========================================================================
// Surface / GL
// ===========================================================================

void PinaApp::OnSurfaceCreated(JNIEnv* env) {
  camera_texture_ = 0;
  camera_texture_ready_ = false;
  // A textura OES do video pertence ao Java; com contexto novo ela morreu.
  video_oes_texture_ = 0;
  video_transform_valid_ = false;

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
  glAttachShader(quad_program_, LoadGLShader(GL_VERTEX_SHADER, kQuadVertexShader));
  glAttachShader(quad_program_,
                 LoadGLShader(GL_FRAGMENT_SHADER, kQuadFragmentShader));
  glLinkProgram(quad_program_);
  quad_position_param_ = glGetAttribLocation(quad_program_, "a_Position");
  quad_uv_param_ = glGetAttribLocation(quad_program_, "a_UV");
  quad_mvp_param_ = glGetUniformLocation(quad_program_, "u_MVP");
  quad_texture_param_ = glGetUniformLocation(quad_program_, "u_Texture");
  quad_alpha_param_ = glGetUniformLocation(quad_program_, "u_Alpha");

  oes_quad_program_ = glCreateProgram();
  glAttachShader(oes_quad_program_,
                 LoadGLShader(GL_VERTEX_SHADER, kOesQuadVertexShader));
  glAttachShader(oes_quad_program_,
                 LoadGLShader(GL_FRAGMENT_SHADER, kOesQuadFragmentShader));
  glLinkProgram(oes_quad_program_);
  oes_q_position_param_ = glGetAttribLocation(oes_quad_program_, "a_Position");
  oes_q_uv_param_ = glGetAttribLocation(oes_quad_program_, "a_UV");
  oes_q_mvp_param_ = glGetUniformLocation(oes_quad_program_, "u_MVP");
  oes_q_texture_param_ = glGetUniformLocation(oes_quad_program_, "u_Texture");
  oes_q_texmatrix_param_ =
      glGetUniformLocation(oes_quad_program_, "u_TexMatrix");

  oes_360_program_ = glCreateProgram();
  glAttachShader(oes_360_program_,
                 LoadGLShader(GL_VERTEX_SHADER, kOes360VertexShader));
  glAttachShader(oes_360_program_,
                 LoadGLShader(GL_FRAGMENT_SHADER, kOes360FragmentShader));
  glLinkProgram(oes_360_program_);
  oes_360_position_param_ =
      glGetAttribLocation(oes_360_program_, "a_Position");
  oes_360_mvp_param_ = glGetUniformLocation(oes_360_program_, "u_MVP");
  oes_360_texture_param_ =
      glGetUniformLocation(oes_360_program_, "u_Texture");
  oes_360_texmatrix_param_ =
      glGetUniformLocation(oes_360_program_, "u_TexMatrix");

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
  RebuildVideosTexture();
  RebuildPlayerControls();
  RebuildVrControls();
  RebuildHud();
  RebuildStatus();
  SwitchScreen(kScreenLauncher);
  CHECKGLERROR("OnSurfaceCreated");
}

void PinaApp::BuildGeometry() {
  // Esfera (raio 40m) usada pelo passthrough e pelo video 360.
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
      sphere_vertices_.push_back(kRadius * std::sin(phi) * std::cos(theta));
      sphere_vertices_.push_back(kRadius * std::cos(phi));
      sphere_vertices_.push_back(kRadius * std::sin(phi) * std::sin(theta));
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

  quad_vertices_ = {
      -0.5f, 0.5f,  0.0f,  //
      0.5f,  0.5f,  0.0f,  //
      -0.5f, -0.5f, 0.0f,  //
      0.5f,  -0.5f, 0.0f,  //
  };
  quad_uv_ = {0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f};
  quad_indices_ = {0, 2, 1, 1, 2, 3};

  pad_vertices_ = quad_vertices_;
}

void PinaApp::BuildUiTextures(JNIEnv* env) {
  (void)env;

  // ------------------------------------------------------------------
  // Launcher (estilo Quest): grade de apps + dock
  // ------------------------------------------------------------------
  {
    UiCanvas c(kLauncherTexW, kLauncherTexH);
    c.FillRect(0, 0, kLauncherTexW - 1, kLauncherTexH - 1, 12, 14, 22, 255);
    c.FillRect(0, 0, kLauncherTexW - 1, 56, 18, 20, 32, 255);
    c.TextCentered(kLauncherTexW / 2, 18, "PINA XR", 3, 80, 220, 255);
    c.Text(kLauncherTexW - 190, 26, "MR BETA 0.2", 1, 120, 130, 150);

    const int tile_cx[3] = {kLauncherTexW / 6, kLauncherTexW / 2,
                            kLauncherTexW * 5 / 6};
    const int tile_cy[2] = {170, 330};
    const char* labels[5] = {"NAVEGADOR", "VIDEOS", "360 GRAUS", "ALVOS 3D",
                             "SIMON 3D"};
    for (int i = 0; i < 5; ++i) {
      const int cx = tile_cx[i % 3];
      const int cy = tile_cy[i / 3];
      c.FillCircle(cx, cy, 56, 24, 27, 40, 255);
      c.Ring(cx, cy, 56, 3, 80, 220, 255);
      if (i == 0) {  // globo
        c.Line(cx - 52, cy, cx + 52, cy, 2, 120, 230, 255);
        for (int y = -52; y <= 52; ++y) {
          const float t = static_cast<float>(y) / 52.0f;
          const int dx = static_cast<int>(
              22.0f * std::sqrt(std::max(0.0f, 1.0f - t * t)));
          c.SetPixel(cx + dx, cy + y, 120, 230, 255, 255);
          c.SetPixel(cx - dx, cy + y, 120, 230, 255, 255);
        }
      } else if (i == 1) {  // play
        for (int t = -26; t <= 26; ++t) {
          const int span = std::abs(t) / 2 + 1;
          c.Line(cx + t, cy - span, cx + t, cy + span, 2, 120, 230, 255);
        }
      } else if (i == 2) {  // 360
        c.Ring(cx, cy, 30, 3, 120, 230, 255);
        c.FillCircle(cx, cy, 12, 120, 230, 255);
        c.TextCentered(cx, cy + 36, "360", 1, 120, 230, 255);
      } else if (i == 3) {  // alvo
        c.Ring(cx, cy, 40, 5, 255, 120, 120);
        c.Ring(cx, cy, 24, 5, 255, 200, 120);
        c.FillCircle(cx, cy, 9, 255, 90, 90);
      } else {  // simon: 4 quadrados
        c.FillRect(cx - 34, cy - 34, cx - 6, cy - 6, 60, 220, 110);
        c.FillRect(cx + 6, cy - 34, cx + 34, cy - 6, 235, 90, 90);
        c.FillRect(cx - 34, cy + 6, cx - 6, cy + 34, 90, 140, 235);
        c.FillRect(cx + 6, cy + 6, cx + 34, cy + 34, 235, 210, 90);
      }
      c.TextCentered(cx, cy + 68, labels[i], 1, 200, 210, 230);
    }
    c.TextCentered(kLauncherTexW / 2, kLauncherTexH - 26,
                   "PINCE PARA ABRIR", 1, 130, 140, 160);

    if (launcher_texture_ != 0) glDeleteTextures(1, &launcher_texture_);
    launcher_texture_ =
        CreateRgbaTexture(kLauncherTexW, kLauncherTexH, c.px.data());

    // Regioes dos tiles (uv, v=0 no topo). Textura 640x480.
    launcher_panel_.regions.clear();
    for (int i = 0; i < 5; ++i) {
      const int cx = tile_cx[i % 3];
      const int cy = tile_cy[i / 3];
      const float u0 = (cx - 62.0f) / kLauncherTexW;
      const float u1 = (cx + 62.0f) / kLauncherTexW;
      const float v0 = (cy - 62.0f) / kLauncherTexH;
      const float v1 = (cy + 78.0f) / kLauncherTexH;
      launcher_panel_.regions.push_back({u0, v0, u1, v1, kLauncherTile0 + i});
    }
  }

  // ------------------------------------------------------------------
  // Moldura do navegador
  // ------------------------------------------------------------------
  {
    UiCanvas c(kBrowserTexW, kBrowserTexH);
    c.FillRoundRect(0, 0, kBrowserTexW - 1, kBrowserTexH - 1, 14, 90, 200, 255,
                    255);
    c.FillRoundRect(4, 4, kBrowserTexW - 5, kBrowserTexH - 5, 11, 13, 18, 32,
                    235);
    c.FillRoundRect(88, 10, 690, 60, 8, 30, 36, 52, 255);
    c.FillRoundRect(704, 8, 762, 62, 8, 200, 60, 60, 255);
    c.FillRoundRect(6, 8, 74, 62, 8, 40, 170, 90, 255);
    c.Line(718, 20, 748, 50, 5, 255, 255, 255);
    c.Line(718, 50, 748, 20, 5, 255, 255, 255);
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
    browser_texture_ = CreateRgbaTexture(kBrowserTexW, kBrowserTexH, c.px.data());

    browser_frame_panel_.regions = {
        {704.0f / kBrowserTexW, 8.0f / kBrowserTexH, 762.0f / kBrowserTexW,
         62.0f / kBrowserTexH, kBrowserClose},
        {6.0f / kBrowserTexW, 8.0f / kBrowserTexH, 74.0f / kBrowserTexW,
         62.0f / kBrowserTexH, kBrowserHome},
    };
  }

  // ------------------------------------------------------------------
  // Pagina inicial do navegador
  // ------------------------------------------------------------------
  {
    UiCanvas c(768, 384);
    c.FillRect(0, 0, 767, 383, 24, 26, 34, 255);
    c.TextCentered(384, 52, "PINA XR", 6, 80, 220, 255);
    c.TextCentered(384, 110, "NAVEGADOR 3D - BETA", 2, 150, 160, 180);
    c.FillRoundRect(140, 180, 620, 244, 10, 40, 46, 66, 255);
    c.TextCentered(384, 202, "GOOGLE.COM", 2, 120, 230, 255);
    c.FillRoundRect(140, 268, 620, 332, 10, 40, 46, 66, 255);
    c.TextCentered(384, 290, "EXAMPLE.COM", 2, 120, 230, 255);
    c.TextCentered(384, 356, "PINCE PARA ABRIR", 1, 120, 130, 150);

    if (start_page_texture_ != 0) glDeleteTextures(1, &start_page_texture_);
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
    page_panel_.texture = page_texture_;
  }
}

void PinaApp::ResetPage() {
  if (start_page_texture_ == 0) return;  // BuildUiTextures cuida do resto
  if (page_texture_ != 0 && page_texture_ != start_page_texture_) {
    glDeleteTextures(1, &page_texture_);
  }
  page_texture_ = start_page_texture_;
  page_panel_.texture = page_texture_;
  page_links_ = {
      {140.0f / 768, 180.0f / 384, 620.0f / 768, 244.0f / 384,
       "https://www.google.com"},
      {140.0f / 768, 268.0f / 384, 620.0f / 768, 332.0f / 384,
       "https://example.com"},
  };
}

// ---------------------------------------------------------------------------

void PinaApp::RebuildVideosTexture() {
  UiCanvas c(kVideosTexW, kVideosTexH);
  c.FillRoundRect(0, 0, kVideosTexW - 1, kVideosTexH - 1, 14, 90, 200, 255,
                  255);
  c.FillRoundRect(4, 4, kVideosTexW - 5, kVideosTexH - 5, 11, 13, 18, 32, 240);
  c.TextCentered(kVideosTexW / 2, 20,
                 videos_mode_360_ ? "VIDEOS 360 GRAUS" : "VIDEOS DO CELULAR", 2,
                 80, 220, 255);
  // botoes
  c.FillRoundRect(24, 62, 170, 118, 8, 40, 170, 90, 255);
  c.TextCentered(97, 82, "ATUALIZAR", 1, 255, 255, 255);
  c.FillRoundRect(kVideosTexW - 170, 62, kVideosTexW - 24, 118, 8, 200, 60, 60,
                  255);
  c.TextCentered(kVideosTexW - 97, 82, "FECHAR", 1, 255, 255, 255);

  if (!videos_have_permission_) {
    c.TextCentered(kVideosTexW / 2, 300, "SEM PERMISSAO DE MIDIA", 1, 255, 160,
                   160);
    c.TextCentered(kVideosTexW / 2, 330, "PINCE ATUALIZAR P/ TENTAR", 1, 150,
                   160, 180);
  } else if (videos_.empty()) {
    c.TextCentered(kVideosTexW / 2, 300, "NENHUM VIDEO ENCONTRADO", 1, 150, 160,
                   180);
  } else {
    const int max_rows = 6;
    const int n = static_cast<int>(std::min(videos_.size(),
                                            static_cast<size_t>(max_rows)));
    for (int i = 0; i < n; ++i) {
      const int y = 140 + i * 80;
      c.FillRoundRect(24, y, kVideosTexW - 24, y + 70, 8, 30, 36, 52, 255);
      std::string title = videos_[i].title;
      if (title.empty()) title = "VIDEO";
      // caixa alta (fonte 5x7)
      for (auto& ch : title) ch = static_cast<char>(std::toupper(
                                       static_cast<unsigned char>(ch)));
      if (title.size() > 30) title = title.substr(0, 30);
      c.Text(36, y + 14, title.c_str(), 1, 210, 220, 240);
      c.Text(kVideosTexW - 60, y + 14, ">", 2, 120, 230, 255);
    }
  }

  if (videos_texture_ != 0) glDeleteTextures(1, &videos_texture_);
  videos_texture_ = CreateRgbaTexture(kVideosTexW, kVideosTexH, c.px.data());
  videos_panel_.texture = videos_texture_;

  videos_panel_.regions.clear();
  videos_panel_.regions.push_back(
      {24.0f / kVideosTexW, 62.0f / kVideosTexH, 170.0f / kVideosTexW,
       118.0f / kVideosTexH, kVideosUpdate});
  videos_panel_.regions.push_back(
      {(kVideosTexW - 170.0f) / kVideosTexW, 62.0f / kVideosTexH,
       (kVideosTexW - 24.0f) / kVideosTexW, 118.0f / kVideosTexH,
       kVideosClose});
  const int n = static_cast<int>(
      std::min(videos_.size(), static_cast<size_t>(6)));
  for (int i = 0; i < n; ++i) {
    const int y = 140 + i * 80;
    videos_panel_.regions.push_back(
        {24.0f / kVideosTexW, y / static_cast<float>(kVideosTexH),
         (kVideosTexW - 24.0f) / kVideosTexW,
         (y + 70.0f) / static_cast<float>(kVideosTexH), kVideosRow0 + i});
  }
}

void PinaApp::RebuildPlayerControls() {
  UiCanvas c(kCtrlTexW, kCtrlTexH);
  c.FillRoundRect(0, 0, kCtrlTexW - 1, kCtrlTexH - 1, 16, 90, 200, 255, 255);
  c.FillRoundRect(5, 5, kCtrlTexW - 6, kCtrlTexH - 6, 12, 13, 18, 32, 240);
  // X
  c.FillRoundRect(16, 22, 116, 106, 10, 200, 60, 60, 255);
  c.Line(46, 44, 86, 84, 6, 255, 255, 255);
  c.Line(46, 84, 86, 44, 6, 255, 255, 255);
  // -10
  c.FillRoundRect(136, 22, 236, 106, 10, 30, 36, 52, 255);
  c.TextCentered(186, 52, "-10S", 2, 210, 220, 240);
  // PLAY / PAUSE
  c.FillRoundRect(256, 22, 356, 106, 10, 40, 170, 90, 255);
  if (video_playing_) {
    c.FillRect(292, 42, 304, 86, 255, 255, 255);
    c.FillRect(320, 42, 332, 86, 255, 255, 255);
  } else {
    for (int t = 0; t <= 40; ++t) {
      c.Line(296 + t / 2, 40 + t / 4, 296 + t / 2, 88 - t / 4, 2, 255, 255,
             255);
    }
    c.Line(296, 40, 296, 88, 3, 255, 255, 255);
    c.Line(296, 40, 336, 64, 3, 255, 255, 255);
    c.Line(296, 88, 336, 64, 3, 255, 255, 255);
  }
  // +10
  c.FillRoundRect(376, 22, 476, 106, 10, 30, 36, 52, 255);
  c.TextCentered(426, 52, "+10S", 2, 210, 220, 240);

  if (player_ctrl_texture_ != 0) glDeleteTextures(1, &player_ctrl_texture_);
  player_ctrl_texture_ = CreateRgbaTexture(kCtrlTexW, kCtrlTexH, c.px.data());
  player_ctrl_panel_.texture = player_ctrl_texture_;
  player_ctrl_playing_shown_ = video_playing_;

  player_ctrl_panel_.regions = {
      {16.0f / kCtrlTexW, 22.0f / kCtrlTexH, 116.0f / kCtrlTexW,
       106.0f / kCtrlTexH, kPlayerClose},
      {136.0f / kCtrlTexW, 22.0f / kCtrlTexH, 236.0f / kCtrlTexW,
       106.0f / kCtrlTexH, kPlayerBack10},
      {256.0f / kCtrlTexW, 22.0f / kCtrlTexH, 356.0f / kCtrlTexW,
       106.0f / kCtrlTexH, kPlayerPlayPause},
      {376.0f / kCtrlTexW, 22.0f / kCtrlTexH, 476.0f / kCtrlTexW,
       106.0f / kCtrlTexH, kPlayerFwd10},
  };
}

void PinaApp::RebuildVrControls() {
  UiCanvas c(kCtrlTexW, kCtrlTexH);
  c.FillRoundRect(0, 0, kCtrlTexW - 1, kCtrlTexH - 1, 16, 90, 200, 255, 255);
  c.FillRoundRect(5, 5, kCtrlTexW - 6, kCtrlTexH - 6, 12, 13, 18, 32, 240);
  c.FillRoundRect(20, 22, 150, 106, 10, 200, 60, 60, 255);
  c.TextCentered(85, 52, "FECHAR", 2, 255, 255, 255);
  c.FillRoundRect(240, 22, 470, 106, 10, 40, 170, 90, 255);
  if (video_playing_) {
    c.FillRect(320, 42, 336, 86, 255, 255, 255);
    c.FillRect(354, 42, 370, 86, 255, 255, 255);
    c.TextCentered(285, 52, "II", 2, 255, 255, 255);
  } else {
    c.TextCentered(355, 52, "PLAY", 2, 255, 255, 255);
  }
  if (vr_ctrl_texture_ != 0) glDeleteTextures(1, &vr_ctrl_texture_);
  vr_ctrl_texture_ = CreateRgbaTexture(kCtrlTexW, kCtrlTexH, c.px.data());
  vr_ctrl_panel_.texture = vr_ctrl_texture_;

  vr_ctrl_panel_.regions = {
      {20.0f / kCtrlTexW, 22.0f / kCtrlTexH, 150.0f / kCtrlTexW,
       106.0f / kCtrlTexH, kVrClose},
      {240.0f / kCtrlTexW, 22.0f / kCtrlTexH, 470.0f / kCtrlTexW,
       106.0f / kCtrlTexH, kVrPlayPause},
  };
}

void PinaApp::RebuildHud() {
  UiCanvas c(kHudTexW, kHudTexH);
  c.FillRoundRect(0, 0, kHudTexW - 1, kHudTexH - 1, 14, 90, 200, 255, 255);
  c.FillRoundRect(5, 5, kHudTexW - 6, kHudTexH - 6, 11, 13, 18, 32, 240);

  char line1[64] = {0};
  char line2[64] = {0};
  if (screen_ == kScreenGameTargets) {
    if (targets_running_) {
      const int left = std::max(0, static_cast<int>(targets_end_time_ - NowSeconds()));
      snprintf(line1, sizeof(line1), "PONTOS: %d", targets_score_);
      snprintf(line2, sizeof(line2), "TEMPO: %dS", left);
    } else {
      snprintf(line1, sizeof(line1), "FIM! PONTOS: %d", targets_score_);
      snprintf(line2, sizeof(line2), "PINCE REINICIAR OU SAIR");
    }
  } else if (screen_ == kScreenGameSimon) {
    snprintf(line1, sizeof(line1), "NIVEL: %d", simon_level_);
    if (simon_failed_) {
      snprintf(line2, sizeof(line2), "ERRO! PINCE REINICIAR");
    } else if (simon_playing_back_) {
      snprintf(line2, sizeof(line2), "ASSISTA A SEQUENCIA");
    } else {
      snprintf(line2, sizeof(line2), "SUAS VEZ: REPITA");
    }
  } else if (screen_ == kScreenPlayer || screen_ == kScreenVideo360) {
    const int pos = static_cast<int>(video_position_ms_ / 1000);
    const int dur = static_cast<int>(video_duration_ms_ / 1000);
    snprintf(line1, sizeof(line1), "%02d:%02d / %02d:%02d", pos / 60, pos % 60,
             dur / 60, dur % 60);
    snprintf(line2, sizeof(line2), "%s",
             video_playing_ ? "TOCANDO" : "PAUSADO");
  }

  c.TextCentered(kHudTexW / 2, 16, line1, 2, 80, 220, 255);
  c.TextCentered(kHudTexW / 2, 48, line2, 1, 170, 180, 200);

  if (screen_ == kScreenGameTargets || screen_ == kScreenGameSimon) {
    c.FillRoundRect(20, 86, 210, 140, 8, 40, 170, 90, 255);
    c.TextCentered(115, 104, "REINICIAR", 1, 255, 255, 255);
    c.FillRoundRect(kHudTexW - 210, 86, kHudTexW - 20, 140, 8, 200, 60, 60,
                    255);
    c.TextCentered(kHudTexW - 115, 104, "SAIR", 1, 255, 255, 255);
    hud_panel_.regions = {
        {20.0f / kHudTexW, 86.0f / kHudTexH, 210.0f / kHudTexW,
         140.0f / kHudTexH, kGameRestart},
        {(kHudTexW - 210.0f) / kHudTexW, 86.0f / kHudTexH,
         (kHudTexW - 20.0f) / kHudTexW, 140.0f / kHudTexH, kGameExit},
    };
  } else {
    hud_panel_.regions.clear();
  }

  if (hud_texture_ != 0) glDeleteTextures(1, &hud_texture_);
  hud_texture_ = CreateRgbaTexture(kHudTexW, kHudTexH, c.px.data());
  hud_panel_.texture = hud_texture_;
}

void PinaApp::RebuildStatus() {
  UiCanvas c(kStatusTexW, kStatusTexH);
  c.FillRoundRect(0, 0, kStatusTexW - 1, kStatusTexH - 1, 12, 90, 200, 255,
                  255);
  c.FillRoundRect(4, 4, kStatusTexW - 5, kStatusTexH - 5, 9, 13, 18, 32, 235);
  char line[64];
  snprintf(line, sizeof(line), "MAOS: %0.0fFPS  CAM: %0.0fFPS", hands_fps_,
           cam_fps_);
  c.TextCentered(kStatusTexW / 2, 12, line, 1, 120, 230, 255);
  // Botao VISOR: escanear o QR do visor (opcional; o perfil padrao ja funciona).
  c.FillRoundRect(10, 34, 118, 84, 8, 40, 170, 90, 255);
  c.TextCentered(64, 51, "VISOR", 1, 255, 255, 255);
  c.TextCentered(283, 51, has_hands_ ? "MAO VISTA :)" : "MOSTRE A MAO", 1,
                 has_hands_ ? 120 : 200, has_hands_ ? 255 : 160,
                 has_hands_ ? 140 : 160);

  if (status_texture_ != 0) glDeleteTextures(1, &status_texture_);
  status_texture_ = CreateRgbaTexture(kStatusTexW, kStatusTexH, c.px.data());
  status_panel_.texture = status_texture_;
  status_panel_.regions = {
      {10.0f / kStatusTexW, 34.0f / kStatusTexH, 118.0f / kStatusTexW,
       84.0f / kStatusTexH, kHomeViewer},
  };
}

// ===========================================================================
// Parametros do dispositivo (QR do visor)
// ===========================================================================

bool PinaApp::UpdateDeviceParams() {
  if (!screen_params_changed_ && !device_params_changed_) {
    return true;
  }

  uint8_t* buffer = nullptr;
  int size = 0;
  CardboardQrCode_getSavedDeviceParams(&buffer, &size);
  if (size == 0) {
    CardboardQrCode_destroy(buffer);
    return false;
  }

  CardboardLensDistortion_destroy(lens_distortion_);
  lens_distortion_ = CardboardLensDistortion_create(buffer, size, screen_width_,
                                                    screen_height_);
  CardboardQrCode_destroy(buffer);

  // Libera os recursos GL do setup anterior (sem isso, cada mudanca de
  // perfil/rotacao vazava framebuffer/texture).
  GlTeardown();
  GlSetup();

  CardboardDistortionRenderer_destroy(distortion_renderer_);
  const CardboardOpenGlEsDistortionRendererConfig config{kGlTexture2D};
  distortion_renderer_ = CardboardOpenGlEs2DistortionRenderer_create(&config);

  CardboardMesh left_mesh;
  CardboardMesh right_mesh;
  CardboardLensDistortion_getDistortionMesh(lens_distortion_, kLeft, &left_mesh);
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
  CardboardHeadTracker_getPose(head_tracker_, GetBootTimeNano() + 50000000,
                               kLandscapeLeft, &out_position[0],
                               &out_orientation[0]);
  return GetTranslationMatrix(out_position) *
         Quatf::FromXYZW(&out_orientation[0]).ToMatrix();
}

// ===========================================================================
// Paineis / telas
// ===========================================================================

void PinaApp::PlacePanelInFront(Panel& panel, float w, float h, float dist,
                                float height, float up_offset) {
  const std::array<float, 4> forward4 = head_view_ * ToVec4({0, 0, -1}, 0.0f);
  std::array<float, 3> forward = Normalize3(FromVec4(forward4));
  forward[1] = 0.0f;
  const std::array<float, 3> fwd = Normalize3(forward);
  const std::array<float, 4> head_pos4 = head_view_ * ToVec4({0, 0, 0}, 1.0f);
  const std::array<float, 3> head_pos = FromVec4(head_pos4);

  const float yaw = std::atan2(-fwd[0], -fwd[2]);
  Quatf yaw_quat(0.0f, std::sin(yaw / 2.0f), 0.0f, std::cos(yaw / 2.0f));

  Matrix4x4 scale{};
  scale.m[0][0] = w;
  scale.m[1][1] = h;
  scale.m[2][2] = 1.0f;
  scale.m[3][3] = 1.0f;

  const std::array<float, 3> pos = Add3(head_pos, Scale3(fwd, dist));
  panel.position = {pos[0], height + up_offset, pos[2]};
  panel.width = w;
  panel.height = h;
  panel.world_from_panel = GetTranslationMatrix(panel.position) *
                           yaw_quat.ToMatrix() * scale;
}

void PinaApp::PlaceAllPanels() {
  const std::array<float, 4> forward4 = head_view_ * ToVec4({0, 0, -1}, 0.0f);
  std::array<float, 3> forward = Normalize3(FromVec4(forward4));
  forward[1] = 0.0f;
  const float yaw = std::atan2(-forward[0], -forward[2]);
  placement_yaw_ = yaw;

  switch (screen_) {
    case kScreenLauncher:
      PlacePanelInFront(launcher_panel_, kLauncherW, kLauncherH,
                        kPanelDistance, kPanelHeight, 0.0f);
      PlacePanelInFront(status_panel_, kStatusPanelW, kStatusPanelH,
                        kPanelDistance, kPanelHeight + kLauncherH / 2 + 0.18f,
                        0.0f);
      break;
    case kScreenBrowser: {
      PlacePanelInFront(browser_frame_panel_, kBrowserPanelW, kBrowserPanelH,
                        kPanelDistance, kPanelHeight, 0.0f);
      const float content_w = kBrowserPanelW * 745.0f / 768.0f;
      const float content_h = content_w * 376.0f / 745.0f;
      const float up_offset =
          (240.0f - (70.0f + 446.0f) / 2.0f) / 480.0f * kBrowserPanelH;
      std::array<float, 3> fwd = Normalize3(
          FromVec4(head_view_ * ToVec4({0, 0, -1}, 0.0f)));
      fwd[1] = 0.0f;
      fwd = Normalize3(fwd);
      page_panel_.width = content_w;
      page_panel_.height = content_h;
      const std::array<float, 3> base =
          Add3(FromVec4(head_view_ * ToVec4({0, 0, 0}, 1.0f)),
               Scale3(fwd, kPanelDistance));
      const std::array<float, 3> normal = Scale3(fwd, -1.0f);
      page_panel_.position = Add3(
          Add3(base, Scale3(normal, kBrowserPageInset)), {0.0f, up_offset, 0.0f});
      Matrix4x4 scale{};
      scale.m[0][0] = content_w;
      scale.m[1][1] = content_h;
      scale.m[3][3] = 1.0f;
      Quatf yaw_quat(0.0f, std::sin(yaw / 2.0f), 0.0f, std::cos(yaw / 2.0f));
      page_panel_.world_from_panel =
          GetTranslationMatrix(page_panel_.position) * yaw_quat.ToMatrix() *
          scale;
      break;
    }
    case kScreenVideos:
      PlacePanelInFront(videos_panel_, kVideosPanelW, kVideosPanelH,
                        kPanelDistance, kPanelHeight, 0.0f);
      break;
    case kScreenPlayer: {
      float aspect = (video_tex_height_ > 0)
                         ? static_cast<float>(video_tex_width_) /
                               static_cast<float>(video_tex_height_)
                         : 16.0f / 9.0f;
      float h = kPlayerPanelH;
      float w = h * aspect;
      if (w > kPlayerPanelW) {
        w = kPlayerPanelW;
        h = w / aspect;
      }
      if (w < 1.2f) {
        w = 1.2f;
        h = w / aspect;
      }
      PlacePanelInFront(player_panel_, w, h, kPanelDistance - 0.2f,
                        kPanelHeight + 0.25f, 0.0f);
      PlacePanelInFront(player_ctrl_panel_, kCtrlPanelW, kCtrlPanelH,
                        kPanelDistance - 0.2f,
                        kPanelHeight + 0.25f - h / 2 - kCtrlPanelH / 2 - 0.1f,
                        0.0f);
      PlacePanelInFront(hud_panel_, kHudPanelW, kHudPanelH, kPanelDistance,
                        kPanelHeight + h / 2 + kHudPanelH / 2 + 0.24f, 0.0f);
      break;
    }
    case kScreenVideo360:
      PlacePanelInFront(vr_ctrl_panel_, kCtrlPanelW, kCtrlPanelH, 1.8f, 0.85f,
                        0.0f);
      PlacePanelInFront(hud_panel_, kHudPanelW, kHudPanelH, 1.8f, 2.05f, 0.0f);
      break;
    case kScreenGameTargets:
      PlacePanelInFront(hud_panel_, kHudPanelW, kHudPanelH, 1.8f, 2.05f, 0.0f);
      break;
    case kScreenGameSimon: {
      PlacePanelInFront(hud_panel_, kHudPanelW, kHudPanelH, 1.8f, 2.05f, 0.0f);
      std::array<float, 3> fwd =
          Normalize3(FromVec4(head_view_ * ToVec4({0, 0, -1}, 0.0f)));
      fwd[1] = 0.0f;
      fwd = Normalize3(fwd);
      const std::array<float, 3> right = {-fwd[2], 0.0f, fwd[0]};
      const std::array<float, 3> center =
          Add3(FromVec4(head_view_ * ToVec4({0, 0, 0}, 1.0f)),
               Scale3(fwd, 1.9f));
      Quatf yaw_quat(0.0f, std::sin(yaw / 2.0f), 0.0f, std::cos(yaw / 2.0f));
      Matrix4x4 scale{};
      scale.m[0][0] = 0.44f;
      scale.m[1][1] = 0.44f;
      scale.m[3][3] = 1.0f;
      const float dx[4] = {-0.5f, 0.5f, -0.5f, 0.5f};
      const float dy[4] = {0.3f, 0.3f, -0.3f, -0.3f};
      for (int i = 0; i < kSimonPads; ++i) {
        simon_pads_[i] =
            Add3(Add3(center, Scale3(right, dx[i])), {0.0f, dy[i], 0.0f});
        simon_models_[i] =
            GetTranslationMatrix(simon_pads_[i]) * yaw_quat.ToMatrix() * scale;
      }
      break;
    }
  }
}

void PinaApp::SwitchScreen(AppScreen screen) {
  screen_ = screen;
  launcher_panel_.visible = (screen == kScreenLauncher);
  status_panel_.visible = (screen == kScreenLauncher);
  browser_frame_panel_.visible = (screen == kScreenBrowser);
  page_panel_.visible = (screen == kScreenBrowser);
  videos_panel_.visible = (screen == kScreenVideos);
  player_panel_.visible = (screen == kScreenPlayer);
  player_ctrl_panel_.visible = (screen == kScreenPlayer);
  vr_ctrl_panel_.visible = (screen == kScreenVideo360);
  hud_panel_.visible = (screen == kScreenPlayer || screen == kScreenVideo360 ||
                        screen == kScreenGameTargets ||
                        screen == kScreenGameSimon);
  placement_yaw_ = 1e9f;  // forca reancoragem
  RebuildHud();
}

void PinaApp::ShowHome() { SwitchScreen(kScreenLauncher); }

void PinaApp::OpenApp(int app_id) {
  switch (app_id) {
    case kAppBrowser:
      ResetPage();
      SwitchScreen(kScreenBrowser);
      break;
    case kAppVideos:
    case kAppVideo360:
      videos_mode_360_ = (app_id == kAppVideo360);
      SwitchScreen(kScreenVideos);
      NotifyRequestVideos();
      break;
    case kAppTargets:
      StartTargetsGame();
      SwitchScreen(kScreenGameTargets);
      break;
    case kAppSimon:
      StartSimonGame();
      SwitchScreen(kScreenGameSimon);
      break;
    default:
      break;
  }
}

// ===========================================================================
// Jogos
// ===========================================================================

void PinaApp::StartTargetsGame() {
  targets_score_ = 0;
  targets_end_time_ = NowSeconds() + kTargetGameDuration;
  targets_running_ = true;
  const std::array<float, 4> forward4 = head_view_ * ToVec4({0, 0, -1}, 0.0f);
  std::array<float, 3> fwd = Normalize3(FromVec4(forward4));
  fwd[1] = 0.0f;
  fwd = Normalize3(fwd);
  base_yaw_ = std::atan2(-fwd[0], -fwd[2]);
  for (auto& t : targets_) {
    RespawnTarget(t);
  }
}

void PinaApp::RespawnTarget(Target& t) {
  const float yaw = base_yaw_ + RandomUniformFloat(-0.7f, 0.7f);
  const float pitch = RandomUniformFloat(-0.25f, 0.35f);
  const float d = RandomUniformFloat(2.2f, 3.2f);
  const std::array<float, 4> head_pos4 = head_view_ * ToVec4({0, 0, 0}, 1.0f);
  const std::array<float, 3> head_pos = FromVec4(head_pos4);
  const float cp = std::cos(pitch);
  t.pos = {head_pos[0] - std::sin(yaw) * cp * d,
           head_pos[1] + std::sin(pitch) * d,
           head_pos[2] - std::cos(yaw) * cp * d};
  t.alive = true;
}

void PinaApp::UpdateTargetsGame(float now) {
  (void)now;
  if (targets_running_ && NowSeconds() > targets_end_time_) {
    targets_running_ = false;
    for (auto& t : targets_) t.alive = false;
    RebuildHud();
  }
}

void PinaApp::StartSimonGame() {
  simon_sequence_.clear();
  simon_level_ = 0;
  simon_failed_ = false;
  simon_playing_back_ = true;
  simon_playback_index_ = 0;
  simon_lit_ = -1;
  ExtendSimonSequence();
  simon_next_step_ = NowSeconds() + 0.8f;
}

void PinaApp::ExtendSimonSequence() {
  simon_sequence_.push_back(RandomUniformInt(kSimonPads));
  simon_level_ = static_cast<int>(simon_sequence_.size());
}

void PinaApp::UpdateSimonGame(float now) {
  if (simon_playing_back_ && !simon_failed_ && now >= simon_next_step_) {
    if (simon_lit_ >= 0 && simon_lit_until_ > now) return;
    if (simon_playback_index_ < simon_sequence_.size()) {
      simon_lit_ = simon_sequence_[simon_playback_index_++];
      simon_lit_until_ = now + 0.45f;
      simon_next_step_ = now + 0.75f;
      RebuildHud();
    } else {
      simon_playing_back_ = false;
      simon_lit_ = -1;
      RebuildHud();
    }
  }
}

// ===========================================================================
// Frame
// ===========================================================================

void PinaApp::OnDrawFrame() {
  if (!UpdateDeviceParams()) {
    return;
  }

  head_view_ = GetPose();

  const float now = static_cast<float>(NowSeconds());

  // FPS
  if (now - last_fps_time_ >= 1.0f) {
    if (last_fps_time_ > 0.0f) {
      cam_fps_ = cam_frame_count_ / (now - last_fps_time_);
      hands_fps_ = hands_frame_count_ / (now - last_fps_time_);
    }
    cam_frame_count_ = 0;
    hands_frame_count_ = 0;
    last_fps_time_ = now;
    if (screen_ == kScreenLauncher && now - last_status_time_ > 1.0f) {
      last_status_time_ = now;
      RebuildStatus();
    }
  }

  // Reancora paineis se o usuario girou bastante a cabeca.
  const std::array<float, 4> forward4 = head_view_ * ToVec4({0, 0, -1}, 0.0f);
  std::array<float, 3> fwd = Normalize3(FromVec4(forward4));
  fwd[1] = 0.0f;
  const float yaw = std::atan2(-fwd[0], -fwd[2]);
  if (placement_yaw_ > 1e8f || std::abs(yaw - placement_yaw_) > 0.55f) {
    PlaceAllPanels();
  }

  UpdateTargetsGame(now);
  UpdateSimonGame(now);
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
      distortion_renderer_, 0, 0, 0, screen_width_, screen_height_,
      &left_eye_texture_description_, &right_eye_texture_description_);
  CHECKGLERROR("onDrawFrame");
}

void PinaApp::DrawWorld(Matrix4x4 eye_view, Matrix4x4 projection) {
  if (screen_ == kScreenVideo360) {
    Draw360Video(eye_view, projection);
  } else {
    DrawPassthrough(eye_view, projection);
  }

  glDisable(GL_CULL_FACE);

  if (page_panel_.visible) {
    DrawQuad(page_panel_, eye_view, projection, false, true);
  }
  if (browser_frame_panel_.visible) {
    DrawQuad(browser_frame_panel_, eye_view, projection, true, false);
  }
  if (launcher_panel_.visible) {
    DrawQuad(launcher_panel_, eye_view, projection, true, false);
  }
  if (status_panel_.visible) {
    DrawQuad(status_panel_, eye_view, projection, true, false);
  }
  if (videos_panel_.visible) {
    DrawQuad(videos_panel_, eye_view, projection, true, false);
  }
  if (player_panel_.visible && video_oes_texture_ != 0) {
    DrawQuad(player_panel_, eye_view, projection, false, true);
  }
  if (player_ctrl_panel_.visible) {
    DrawQuad(player_ctrl_panel_, eye_view, projection, true, false);
  }
  if (vr_ctrl_panel_.visible) {
    DrawQuad(vr_ctrl_panel_, eye_view, projection, true, false);
  }
  if (screen_ == kScreenGameTargets) {
    DrawGameTargets(eye_view, projection);
  }
  if (screen_ == kScreenGameSimon) {
    DrawGameSimon(eye_view, projection);
  }
  if (hud_panel_.visible) {
    DrawQuad(hud_panel_, eye_view, projection, true, false);
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

void PinaApp::Draw360Video(Matrix4x4 eye_view, Matrix4x4 projection) {
  if (video_oes_texture_ == 0 || !video_transform_valid_) {
    return;
  }
  glDisable(GL_DEPTH_TEST);
  glDepthMask(GL_FALSE);
  glDisable(GL_CULL_FACE);

  glUseProgram(oes_360_program_);
  Matrix4x4 mvp = projection * eye_view;
  std::array<float, 16> mvp_array = mvp.ToGlArray();
  glUniformMatrix4fv(oes_360_mvp_param_, 1, GL_FALSE, mvp_array.data());
  glUniformMatrix4fv(oes_360_texmatrix_param_, 1, GL_FALSE,
                     video_tex_matrix_.data());

  glActiveTexture(GL_TEXTURE0);
  glBindTexture(GL_TEXTURE_EXTERNAL_OES, video_oes_texture_);
  glUniform1i(oes_360_texture_param_, 0);

  glEnableVertexAttribArray(oes_360_position_param_);
  glVertexAttribPointer(oes_360_position_param_, 3, GL_FLOAT, GL_FALSE, 0,
                        sphere_vertices_.data());
  glDrawElements(GL_TRIANGLES, sphere_indices_.size(), GL_UNSIGNED_SHORT,
                 sphere_indices_.data());
  glDisableVertexAttribArray(oes_360_position_param_);

  glEnable(GL_DEPTH_TEST);
  glDepthMask(GL_TRUE);
  CHECKGLERROR("Draw360Video");
}

void PinaApp::DrawQuad(const Panel& panel, Matrix4x4 eye_view,
                       Matrix4x4 projection, bool blend, bool depth_write) {
  if (panel.external_oes) {
    glUseProgram(oes_quad_program_);
  } else {
    glUseProgram(quad_program_);
  }
  Matrix4x4 mvp = projection * eye_view * panel.world_from_panel;
  std::array<float, 16> mvp_array = mvp.ToGlArray();
  glUniformMatrix4fv(panel.external_oes ? oes_q_mvp_param_ : quad_mvp_param_, 1,
                     GL_FALSE, mvp_array.data());

  glActiveTexture(GL_TEXTURE0);
  if (panel.external_oes) {
    if (video_oes_texture_ == 0) return;
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, video_oes_texture_);
    glUniform1i(oes_q_texture_param_, 0);
    glUniformMatrix4fv(oes_q_texmatrix_param_, 1, GL_FALSE,
                       video_tex_matrix_.data());
    glEnableVertexAttribArray(oes_q_position_param_);
    glEnableVertexAttribArray(oes_q_uv_param_);
    glVertexAttribPointer(oes_q_position_param_, 3, GL_FLOAT, GL_FALSE, 0,
                          quad_vertices_.data());
    glVertexAttribPointer(oes_q_uv_param_, 2, GL_FLOAT, GL_FALSE, 0,
                          quad_uv_.data());
    if (blend) {
      glEnable(GL_BLEND);
    } else {
      glDisable(GL_BLEND);
    }
    glDepthMask(depth_write ? GL_TRUE : GL_FALSE);
    glDrawElements(GL_TRIANGLES, quad_indices_.size(), GL_UNSIGNED_SHORT,
                   quad_indices_.data());
    glDisableVertexAttribArray(oes_q_position_param_);
    glDisableVertexAttribArray(oes_q_uv_param_);
    glEnable(GL_BLEND);
    glDepthMask(GL_TRUE);
    return;
  }

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

void PinaApp::DrawColorQuad(const Matrix4x4& model,
                            const std::array<float, 4>& color,
                            Matrix4x4 eye_view, Matrix4x4 projection) {
  glUseProgram(color_program_);
  Matrix4x4 mvp = projection * eye_view * model;
  std::array<float, 16> mvp_array = mvp.ToGlArray();
  glUniformMatrix4fv(color_mvp_param_, 1, GL_FALSE, mvp_array.data());
  glUniform4f(color_param_, color[0], color[1], color[2], color[3]);
  glUniform1f(color_point_size_param_, 1.0f);
  glUniform1f(color_is_point_param_, 0.0f);

  glDepthMask(GL_FALSE);
  glEnableVertexAttribArray(color_position_param_);
  glVertexAttribPointer(color_position_param_, 3, GL_FLOAT, GL_FALSE, 0,
                        pad_vertices_.data());
  glDrawElements(GL_TRIANGLES, quad_indices_.size(), GL_UNSIGNED_SHORT,
                 quad_indices_.data());
  glDisableVertexAttribArray(color_position_param_);
  glDepthMask(GL_TRUE);
}

void PinaApp::DrawGameTargets(Matrix4x4 eye_view, Matrix4x4 projection) {
  if (!targets_running_) return;
  glUseProgram(color_program_);
  Matrix4x4 mvp = projection * eye_view;
  std::array<float, 16> mvp_array = mvp.ToGlArray();
  glUniformMatrix4fv(color_mvp_param_, 1, GL_FALSE, mvp_array.data());

  glEnableVertexAttribArray(color_position_param_);
  glUniform1f(color_is_point_param_, 1.0f);
  for (const Target& t : targets_) {
    if (!t.alive) continue;
    const float dist = std::sqrt(
        Dot3(Sub3(t.pos, pointer_origin_w_), Sub3(t.pos, pointer_origin_w_)));
    const float size =
        std::min(300.0f, std::max(40.0f, 240.0f / std::max(0.6f, dist)));
    std::array<GLfloat, 3> p = {t.pos[0], t.pos[1], t.pos[2]};
    glUniform1f(color_point_size_param_, size);
    glUniform4f(color_param_, 1.0f, 0.35f, 0.3f, 0.95f);
    glVertexAttribPointer(color_position_param_, 3, GL_FLOAT, GL_FALSE, 0,
                          p.data());
    glDrawArrays(GL_POINTS, 0, 1);
    glUniform1f(color_point_size_param_, size * 0.45f);
    glUniform4f(color_param_, 1.0f, 0.9f, 0.8f, 0.95f);
    glVertexAttribPointer(color_position_param_, 3, GL_FLOAT, GL_FALSE, 0,
                          p.data());
    glDrawArrays(GL_POINTS, 0, 1);
  }
  glDisableVertexAttribArray(color_position_param_);
}

void PinaApp::DrawGameSimon(Matrix4x4 eye_view, Matrix4x4 projection) {
  static const std::array<float, 4> colors[kSimonPads] = {
      {0.2f, 0.9f, 0.35f, 0.9f}, {0.95f, 0.3f, 0.3f, 0.9f},
      {0.3f, 0.55f, 0.95f, 0.9f}, {0.95f, 0.85f, 0.25f, 0.9f}};
  for (int i = 0; i < kSimonPads; ++i) {
    std::array<float, 4> c = colors[i];
    if (simon_lit_ == i) {
      c[0] = std::min(1.0f, c[0] * 1.8f + 0.15f);
      c[1] = std::min(1.0f, c[1] * 1.8f + 0.15f);
      c[2] = std::min(1.0f, c[2] * 1.8f + 0.15f);
      c[3] = 1.0f;
    }
    if (hover_type_ == kHoverButton && hover_id_ == kLauncherTile0 + 100 + i) {
      c[3] = 1.0f;
    }
    DrawColorQuad(simon_models_[i], c, eye_view, projection);
  }
}

void PinaApp::DrawHandsAndPointer(Matrix4x4 eye_view, Matrix4x4 projection) {
  if (!has_hands_ && !pointer_valid_) return;

  glUseProgram(color_program_);
  Matrix4x4 mvp = projection * eye_view;
  std::array<float, 16> mvp_array = mvp.ToGlArray();
  glUniformMatrix4fv(color_mvp_param_, 1, GL_FALSE, mvp_array.data());
  glDepthMask(GL_FALSE);
  glEnable(GL_BLEND);

  std::array<std::array<float, 3>, 42> joints;
  const bool present[2] = {hands_.has_left, hands_.has_right};
  for (int h = 0; h < 2; ++h) {
    if (!present[h]) continue;
    const std::array<float, 63>& lm = (h == 0) ? hands_.left : hands_.right;
    for (int i = 0; i < 21; ++i) {
      const std::array<float, 3> p_head =
          HeadSpaceFromImage(lm[i * 3], lm[i * 3 + 1], lm[i * 3 + 2]);
      joints[h * 21 + i] = FromVec4(head_view_ * ToVec4(p_head, 1.0f));
    }
  }

  glEnableVertexAttribArray(color_position_param_);

  {
    std::vector<GLfloat> bone_buf;
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
// Interacao
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

  const std::array<float, 4> o4 = head_view_ * ToVec4(mid, 1.0f);
  const std::array<float, 4> d4 = head_view_ * ToVec4(dir, 0.0f);
  pointer_origin_w_ = FromVec4(o4);
  const std::array<float, 3> dir_w = Normalize3(FromVec4(d4));
  pointer_end_w_ = Add3(pointer_origin_w_, Scale3(dir_w, 2.8f));
  pointer_valid_ = true;

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
    *v = 0.5f - Dot3(q, up) / panel.height;
    return true;
  };

  // Painel clicavel da tela atual (+ alvos/pads).
  auto hover_panel = [&](const Panel& panel) {
    float u = 0.0f, v = 0.0f;
    if (raycast(panel, &u, &v)) {
      for (const Panel::HitRegion& region : panel.regions) {
        if (u >= region.u0 && u <= region.u1 && v >= region.v0 &&
            v <= region.v1) {
          hover_type_ = kHoverButton;
          hover_id_ = region.id;
          return;
        }
      }
    }
  };

  if (launcher_panel_.visible) hover_panel(launcher_panel_);
  if (hover_type_ == kHoverNone && status_panel_.visible) {
    hover_panel(status_panel_);
  }
  if (hover_type_ == kHoverNone && browser_frame_panel_.visible) {
    hover_panel(browser_frame_panel_);
    float pu = 0.0f, pv = 0.0f;
    if (hover_type_ == kHoverNone && page_panel_.visible &&
        raycast(page_panel_, &pu, &pv)) {
      for (const PageLink& link : page_links_) {
        if (pu >= link.u0 && pu <= link.u1 && pv >= link.v0 && pv <= link.v1) {
          hover_type_ = kHoverLink;
          hover_url_ = link.url;
          break;
        }
      }
    }
  }
  if (hover_type_ == kHoverNone && videos_panel_.visible) {
    hover_panel(videos_panel_);
  }
  if (hover_type_ == kHoverNone && player_ctrl_panel_.visible) {
    hover_panel(player_ctrl_panel_);
  }
  if (hover_type_ == kHoverNone && vr_ctrl_panel_.visible) {
    hover_panel(vr_ctrl_panel_);
  }
  if (hover_type_ == kHoverNone && hud_panel_.visible) {
    hover_panel(hud_panel_);
  }

  // Alvos do jogo (esferas).
  if (hover_type_ == kHoverNone && screen_ == kScreenGameTargets &&
      targets_running_) {
    for (size_t i = 0; i < targets_.size(); ++i) {
      if (!targets_[i].alive) continue;
      const std::array<float, 3> oc = Sub3(pointer_origin_w_, targets_[i].pos);
      const float b = Dot3(oc, dir_w);
      const float c = Dot3(oc, oc) - kTargetRadius * kTargetRadius;
      const float disc = b * b - c;
      if (disc > 0.0f) {
        const float t = -b - std::sqrt(disc);
        if (t > 0.0f && t < 2.8f) {
          hover_type_ = kHoverButton;
          hover_id_ = kLauncherTile0 + 200 + static_cast<int>(i);
          break;
        }
      }
    }
  }

  // Pads do Simon (quads).
  if (hover_type_ == kHoverNone && screen_ == kScreenGameSimon &&
      !simon_playing_back_ && !simon_failed_) {
    for (int i = 0; i < kSimonPads; ++i) {
      Panel pad;
      pad.visible = true;
      pad.world_from_panel = simon_models_[i];
      pad.width = 0.44f;
      pad.height = 0.44f;
      float u = 0.0f, v = 0.0f;
      if (raycast(pad, &u, &v)) {
        hover_type_ = kHoverButton;
        hover_id_ = kLauncherTile0 + 100 + i;
        break;
      }
    }
  }

  if (pinching) {
    pointer_color_ = {0.2f, 1.0f, 0.4f, 1.0f};
  } else if (hover_type_ != kHoverNone) {
    pointer_color_ = {1.0f, 0.9f, 0.2f, 1.0f};
  } else {
    pointer_color_ = {1.0f, 1.0f, 1.0f, 1.0f};
  }

  if (pinching && !pinch_was_down_) {
    ClickCurrentHover();
  }
  pinch_was_down_ = pinching;
}

void PinaApp::ClickCurrentHover() {
  if (hover_type_ == kHoverLink) {
    NotifyOpenUrl(hover_url_);
    return;
  }
  if (hover_type_ != kHoverButton) return;

  const int id = hover_id_;

  // Linhas da lista de videos.
  if (id >= kVideosRow0 && id < kVideosRow0 + 8) {
    const int index = id - kVideosRow0;
    if (index >= 0 && index < static_cast<int>(videos_.size())) {
      const long long video_id = videos_[index].id;
      const bool is360 = videos_mode_360_;
      SwitchScreen(is360 ? kScreenVideo360 : kScreenPlayer);
      NotifyPlayVideo(video_id, is360);
    }
    return;
  }

  // Tiles do launcher.
  if (id >= kLauncherTile0 && id < kLauncherTile0 + 5) {
    OpenApp(id - kLauncherTile0);
    return;
  }
  // Alvos do jogo.
  if (id >= kLauncherTile0 + 200 && id < kLauncherTile0 + 200 + kTargetCount) {
    if (targets_running_) {
      Target& t = targets_[id - (kLauncherTile0 + 200)];
      if (t.alive) {
        t.alive = false;
        targets_score_++;
        RespawnTarget(t);
        RebuildHud();
      }
    }
    return;
  }
  // Pads do Simon.
  if (id >= kLauncherTile0 + 100 && id < kLauncherTile0 + 100 + kSimonPads) {
    const int pad = id - (kLauncherTile0 + 100);
    if (screen_ == kScreenGameSimon && !simon_playing_back_ && !simon_failed_) {
      if (pad == simon_sequence_[simon_playback_index_]) {
        simon_lit_ = pad;
        simon_lit_until_ = NowSeconds() + 0.3f;
        simon_playback_index_++;
        if (simon_playback_index_ >= simon_sequence_.size()) {
          ExtendSimonSequence();
          simon_playback_index_ = 0;
          simon_playing_back_ = true;
          simon_next_step_ = NowSeconds() + 0.8f;
        }
        RebuildHud();
      } else {
        simon_failed_ = true;
        simon_lit_ = -1;
        RebuildHud();
      }
    }
    return;
  }

  switch (id) {
    case kHomeViewer:
      SwitchViewer();
      break;
    case kHomeExit:
      NotifyExit();
      break;
    case kBrowserClose:
      SwitchScreen(kScreenLauncher);
      NotifyCloseBrowser();
      break;
    case kBrowserHome:
      ResetPage();
      NotifyCloseBrowser();
      break;
    case kVideosUpdate:
      NotifyRequestVideos();
      break;
    case kVideosClose:
      SwitchScreen(kScreenLauncher);
      break;
    case kPlayerClose:
    case kVrClose:
      NotifyVideoControl(3);
      SwitchScreen(kScreenVideos);
      break;
    case kPlayerBack10:
      NotifyVideoControl(1);
      break;
    case kPlayerPlayPause:
    case kVrPlayPause:
      NotifyVideoControl(0);
      break;
    case kPlayerFwd10:
      NotifyVideoControl(2);
      break;
    case kGameRestart:
      if (screen_ == kScreenGameTargets) {
        StartTargetsGame();
      } else {
        StartSimonGame();
      }
      RebuildHud();
      break;
    case kGameExit:
      SwitchScreen(kScreenLauncher);
      break;
    default:
      break;
  }
}

void PinaApp::OnTriggerEvent() { ClickCurrentHover(); }

// ===========================================================================
// Entradas do Java
// ===========================================================================

void PinaApp::UpdateHands(JNIEnv* env, jfloatArray left, jfloatArray right,
                          jboolean pinch_left, jboolean pinch_right) {
  auto copy = [&](jfloatArray arr, std::array<float, 63>* out) -> bool {
    if (arr == nullptr) return false;
    const jsize len = env->GetArrayLength(arr);
    if (len < 63) return false;
    env->GetFloatArrayRegion(arr, 0, 63, out->data());
    return true;
  };
  hands_.has_left = copy(left, &hands_.left);
  hands_.has_right = copy(right, &hands_.right);
  hands_.pinch_left = pinch_left == JNI_TRUE;
  hands_.pinch_right = pinch_right == JNI_TRUE;
  has_hands_ = hands_.has_left || hands_.has_right;
  hands_frame_count_++;
}

void PinaApp::UpdateCameraFrame(JNIEnv* env, jobject byte_buffer, jint width,
                                jint height) {
  if (byte_buffer == nullptr || width <= 0 || height <= 0) return;
  auto* data = static_cast<uint8_t*>(env->GetDirectBufferAddress(byte_buffer));
  if (data == nullptr) return;
  cam_frame_count_++;

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
    page_panel_.texture = page_texture_;

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
    if (page_texture_ != 0 && page_texture_ != start_page_texture_) {
      glDeleteTextures(1, &page_texture_);
    }
    ResetPage();
  }
}

void PinaApp::SetVideoList(JNIEnv* env, jobjectArray titles, jlongArray ids,
                           jboolean have_permission) {
  videos_have_permission_ = (have_permission == JNI_TRUE);
  videos_.clear();
  if (titles != nullptr && ids != nullptr) {
    const jsize n = std::min(env->GetArrayLength(titles),
                             env->GetArrayLength(ids));
    if (n > 0) {
      jlong* id_ptr = env->GetLongArrayElements(ids, nullptr);
      for (jsize i = 0; i < n; ++i) {
        jstring jtitle = (jstring)env->GetObjectArrayElement(titles, i);
        VideoEntry e;
        e.id = static_cast<long long>(id_ptr[i]);
        if (jtitle != nullptr) {
          const char* t = env->GetStringUTFChars(jtitle, nullptr);
          e.title = (t != nullptr) ? t : "";
          env->ReleaseStringUTFChars(jtitle, t);
          env->DeleteLocalRef(jtitle);
        }
        videos_.push_back(e);
      }
      env->ReleaseLongArrayElements(ids, id_ptr, JNI_ABORT);
    }
  }
  RebuildVideosTexture();
}

void PinaApp::SetVideoTexture(jint tex_id) {
  video_oes_texture_ = static_cast<GLuint>(tex_id);
  video_transform_valid_ = false;
}

void PinaApp::UpdateVideoTransform(JNIEnv* env, jfloatArray matrix) {
  if (matrix == nullptr) return;
  if (env->GetArrayLength(matrix) < 16) return;
  jfloat* m = env->GetFloatArrayElements(matrix, nullptr);
  memcpy(video_tex_matrix_.data(), m, 16 * sizeof(float));
  env->ReleaseFloatArrayElements(matrix, m, JNI_ABORT);
  video_transform_valid_ = true;
}

void PinaApp::SetVideoInfo(jint width, jint height, jlong duration_ms,
                           jlong position_ms, jboolean playing) {
  if (width > 0 && height > 0) {
    video_tex_width_ = width;
    video_tex_height_ = height;
  }
  video_duration_ms_ = duration_ms;
  video_position_ms_ = position_ms;
  video_playing_ = (playing == JNI_TRUE);
  if (video_playing_ != player_ctrl_playing_shown_) {
    RebuildPlayerControls();
    RebuildVrControls();
  }
  RebuildHud();
}

// ===========================================================================
// Callbacks para o Java
// ===========================================================================

void PinaApp::NotifyOpenUrl(const std::string& url) {
  JNIEnv* env = nullptr;
  if (java_vm_->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK || !env) return;
  jstring jurl = env->NewStringUTF(url.c_str());
  env->CallVoidMethod(java_activity_, method_open_url_, jurl);
  env->DeleteLocalRef(jurl);
}

void PinaApp::NotifyCloseBrowser() {
  JNIEnv* env = nullptr;
  if (java_vm_->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK || !env) return;
  env->CallVoidMethod(java_activity_, method_close_browser_);
}

void PinaApp::NotifyExit() {
  JNIEnv* env = nullptr;
  if (java_vm_->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK || !env) return;
  env->CallVoidMethod(java_activity_, method_exit_);
}

void PinaApp::NotifyRequestVideos() {
  JNIEnv* env = nullptr;
  if (java_vm_->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK || !env) return;
  env->CallVoidMethod(java_activity_, method_request_videos_);
}

void PinaApp::NotifyPlayVideo(long long id, bool is360) {
  JNIEnv* env = nullptr;
  if (java_vm_->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK || !env) return;
  env->CallVoidMethod(java_activity_, method_play_video_,
                      static_cast<jlong>(id), is360 ? JNI_TRUE : JNI_FALSE);
}

void PinaApp::NotifyVideoControl(int action) {
  JNIEnv* env = nullptr;
  if (java_vm_->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK || !env) return;
  env->CallVoidMethod(java_activity_, method_video_control_,
                      static_cast<jint>(action));
}

}  // namespace pina_xr
