/*
 * Pina XR - beta 0.1
 * App 100% VR estereo (Cardboard) com passthrough MR via Camera2 + esqueleto
 * das maos (MediaPipe), apontador 3D com clique por gesto pinch e navegador 3D.
 *
 * Toda a UI vive dentro do mundo 3D (paineis). Nao existe UI 2D.
 */

#ifndef PINA_XR_JNI_PINA_APP_H_
#define PINA_XR_JNI_PINA_APP_H_

#include <android/asset_manager.h>
#include <jni.h>

#include <array>
#include <string>
#include <vector>

#include <GLES2/gl2.h>
#include "cardboard.h"
#include "util.h"

namespace pina_xr {

// ---------------------------------------------------------------------------
// Constantes de tuning (metros, radianos, pixels)
// ---------------------------------------------------------------------------

// Distancia em que as maos aparecem (plano da camera frontal).
constexpr float kHandDistance = 1.1f;
// FOV (horizontal/vertical) aproximado da camera frontal do passthrough.
// Usado para mapear as coordenadas normalizadas do MediaPipe para o mundo.
constexpr float kCameraFovX = 1.00f;  // ~57 graus
constexpr float kCameraFovY = 0.78f;  // ~45 graus
// Quanto o z do MediaPipe desloca a mao em profundidade.
constexpr float kHandZScale = 0.9f;
// Espelhar horizontalmente a imagem da camera (ajuste fino do passthrough).
constexpr bool kPassthroughMirror = false;

// Geometria dos paineis.
constexpr float kPanelDistance = 2.6f;   // distancia dos paineis do usuario
constexpr float kPanelHeight = 1.45f;    // altura (centro) dos paineis
constexpr float kHomePanelW = 1.70f;
constexpr float kHomePanelH = 1.06f;
constexpr float kBrowserPanelW = 2.55f;
constexpr float kBrowserPanelH = 1.60f;
constexpr float kBrowserPageInset = 0.006f;  // pagina "na frente" da moldura

// Resolucao das texturas de UI geradas no nativo.
constexpr int kHomeTexW = 512;
constexpr int kHomeTexH = 320;
constexpr int kBrowserTexW = 768;
constexpr int kBrowserTexH = 480;

constexpr float kZNear = 0.1f;
constexpr float kZFar = 100.f;

// IDs de acoes dos botoes.
enum ButtonId {
  kButtonNone = -1,
  kHomeBrowser = 0,
  kHomeViewer = 1,
  kHomeExit = 2,
  kBrowserClose = 10,
  kBrowserHome = 11,
};

// O que o apontador esta "segurando" em um dado momento.
enum HoverType { kHoverNone = 0, kHoverButton = 1, kHoverLink = 2 };

// Estado das maos recebido do Java (MediaPipe), coordenadas normalizadas
// [0..1] (x: esq->dir, y: topo->baixo, z: profundidade relativa).
struct HandFrame {
  bool has_left = false;
  bool has_right = false;
  bool pinch_left = false;
  bool pinch_right = false;
  std::array<float, 63> left{};   // 21 pontos * (x,y,z)
  std::array<float, 63> right{};
};

// Um quad texturizado posicionado no mundo (travado no yaw da cabeca).
struct Panel {
  bool visible = false;
  std::array<float, 3> position{0.0f, 0.0f, 0.0f};  // centro no mundo
  Matrix4x4 world_from_panel;
  float width = 1.0f;
  float height = 1.0f;
  GLuint texture = 0;

  struct HitRegion {
    float u0, v0, u1, v1;  // uv da textura (v=0 no topo)
    int id;
  };
  std::vector<HitRegion> regions;
};

// Link clicavel da pagina aberta no navegador 3D.
struct PageLink {
  float u0, v0, u1, v1;  // uv dentro da textura da pagina
  std::string url;
};

class PinaApp {
 public:
  PinaApp(JavaVM* vm, jobject activity_obj, jobject asset_mgr_obj);
  ~PinaApp();

  void OnSurfaceCreated(JNIEnv* env);
  void SetScreenParams(int width, int height);
  void OnDrawFrame();
  void OnTriggerEvent();
  void OnPause();
  void OnResume();
  void SwitchViewer();

  // Recebe o frame mais recente das maos (chamado pelo Java a cada draw).
  void UpdateHands(JNIEnv* env, jfloatArray left, jfloatArray right,
                   jboolean pinch_left, jboolean pinch_right);

  // Upload do frame da camera (RGBA) para a esfera de passthrough.
  void UpdateCameraFrame(JNIEnv* env, jobject byte_buffer, jint width,
                         jint height);

  // Nova pagina do navegador (RGBA + areas clicaveis). Buffer nulo restaura a
  // pagina inicial.
  void UpdatePage(JNIEnv* env, jobject byte_buffer, jint width, jint height,
                  jfloatArray rects, jobjectArray urls);

 private:
  bool UpdateDeviceParams();
  void GlSetup();
  void GlTeardown();
  Matrix4x4 GetPose();

  void BuildGeometry();
  void BuildUiTextures(JNIEnv* env);
  void ResetPage();

  void DrawWorld(Matrix4x4 eye_view, Matrix4x4 projection);
  void DrawPassthrough(Matrix4x4 eye_view, Matrix4x4 projection);
  void DrawQuad(const Panel& panel, Matrix4x4 eye_view, Matrix4x4 projection,
                bool blend, bool depth_write);
  void DrawHandsAndPointer(Matrix4x4 eye_view, Matrix4x4 projection);

  void PlacePanelsInFront();
  void ShowHome();
  void OpenBrowser();
  void CloseBrowser();
  void HandleInteraction();
  void ClickCurrentHover();

  void NotifyOpenUrl(const std::string& url);
  void NotifyCloseBrowser();
  void NotifyExit();

  JavaVM* java_vm_ = nullptr;
  jobject java_activity_ = nullptr;   // global ref
  jobject java_asset_mgr_ = nullptr;  // global ref
  AAssetManager* asset_mgr_ = nullptr;

  CardboardHeadTracker* head_tracker_ = nullptr;
  CardboardLensDistortion* lens_distortion_ = nullptr;
  CardboardDistortionRenderer* distortion_renderer_ = nullptr;

  CardboardEyeTextureDescription left_eye_texture_description_{};
  CardboardEyeTextureDescription right_eye_texture_description_{};

  bool screen_params_changed_ = false;
  bool device_params_changed_ = false;
  int screen_width_ = 0;
  int screen_height_ = 0;

  float projection_matrices_[2][16]{};
  float eye_matrices_[2][16]{};

  GLuint depth_render_buffer_ = 0;
  GLuint framebuffer_ = 0;
  GLuint texture_ = 0;  // textura de renderizacao (olhos)

  // Programas de shader.
  GLuint passthrough_program_ = 0;
  GLuint quad_program_ = 0;
  GLuint color_program_ = 0;

  // Atributos/uniformes.
  GLuint pt_position_param_ = 0;
  GLuint pt_mvp_param_ = 0;
  GLuint pt_texture_param_ = 0;
  GLuint pt_fov_param_ = 0;
  GLuint pt_mirror_param_ = 0;

  GLuint quad_position_param_ = 0;
  GLuint quad_uv_param_ = 0;
  GLuint quad_mvp_param_ = 0;
  GLuint quad_texture_param_ = 0;
  GLuint quad_alpha_param_ = 0;

  GLuint color_position_param_ = 0;
  GLuint color_mvp_param_ = 0;
  GLuint color_param_ = 0;
  GLuint color_point_size_param_ = 0;
  GLuint color_is_point_param_ = 0;

  // Geometria.
  std::vector<GLfloat> sphere_vertices_;
  std::vector<GLushort> sphere_indices_;
  std::vector<GLfloat> quad_vertices_;
  std::vector<GLfloat> quad_uv_;
  std::vector<GLushort> quad_indices_;

  // Texturas.
  GLuint camera_texture_ = 0;  // passthrough (GL_TEXTURE_2D, RGBA)
  int camera_tex_width_ = 640;
  int camera_tex_height_ = 480;
  bool camera_texture_ready_ = false;

  GLuint home_texture_ = 0;
  GLuint browser_texture_ = 0;  // moldura do navegador
  GLuint page_texture_ = 0;     // pagina atual do navegador
  GLuint start_page_texture_ = 0;
  int page_tex_width_ = kBrowserTexW;
  int page_tex_height_ = kBrowserTexH;

  // Estado da cena.
  Matrix4x4 head_view_;
  HandFrame hands_;
  bool has_hands_ = false;

  Panel home_panel_;
  Panel browser_frame_panel_;
  Panel page_panel_;
  std::vector<PageLink> page_links_;

  // Apontador (calculado por frame).
  bool pointer_valid_ = false;
  std::array<float, 3> pointer_origin_w_{0.0f, 0.0f, 0.0f};
  std::array<float, 3> pointer_end_w_{0.0f, 0.0f, -1.0f};
  std::array<float, 4> pointer_color_{1.0f, 1.0f, 1.0f, 1.0f};
  HoverType hover_type_ = kHoverNone;
  int hover_id_ = kButtonNone;
  std::string hover_url_;
  bool pinch_was_down_ = false;

  // Metodos Java para callbacks.
  jmethodID method_open_url_ = nullptr;
  jmethodID method_close_browser_ = nullptr;
  jmethodID method_exit_ = nullptr;
};

}  // namespace pina_xr

#endif  // PINA_XR_JNI_PINA_APP_H_
