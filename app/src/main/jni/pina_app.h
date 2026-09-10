/*
 * Pina XR - beta 0.2
 * App 100% VR estereo (Cardboard) com passthrough MR (Camera2), esqueleto das
 * maos (MediaPipe + filtros Kalman/One Euro no Java), apontador 3D com clique
 * por pinch e launcher estilo Meta Quest com varios apps:
 * Navegador 3D, Videos do celular, Videos 360, Alvos 3D (jogo) e Simon 3D
 * (jogo). Nada de UI 2D.
 */

#ifndef PINA_XR_JNI_PINA_APP_H_
#define PINA_XR_JNI_PINA_APP_H_

#include <android/asset_manager.h>
#include <jni.h>

#include <array>
#include <string>
#include <vector>

#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>  // GL_TEXTURE_EXTERNAL_OES (video via SurfaceTexture)
#include "cardboard.h"
#include "util.h"

namespace pina_xr {

// ---------------------------------------------------------------------------
// Constantes de tuning (metros, radianos, pixels)
// ---------------------------------------------------------------------------

constexpr float kHandDistance = 1.1f;
constexpr float kCameraFovX = 1.00f;  // ~57 graus (ajustar por aparelho)
constexpr float kCameraFovY = 0.78f;  // ~45 graus
constexpr float kHandZScale = 0.9f;
constexpr bool kPassthroughMirror = false;

// Paineis.
constexpr float kPanelDistance = 2.6f;
constexpr float kPanelHeight = 1.45f;
constexpr float kLauncherW = 2.10f;
constexpr float kLauncherH = 1.58f;
constexpr float kHomePanelW = 1.70f;
constexpr float kHomePanelH = 1.06f;
constexpr float kBrowserPanelW = 2.55f;
constexpr float kBrowserPanelH = 1.60f;
constexpr float kVideosPanelW = 2.30f;
constexpr float kVideosPanelH = 1.55f;
constexpr float kPlayerPanelW = 2.90f;
constexpr float kPlayerPanelH = 1.70f;
constexpr float kCtrlPanelW = 1.30f;
constexpr float kCtrlPanelH = 0.30f;
constexpr float kHudPanelW = 1.30f;
constexpr float kHudPanelH = 0.42f;
constexpr float kStatusPanelW = 1.10f;
constexpr float kStatusPanelH = 0.26f;
constexpr float kBrowserPageInset = 0.006f;

// Resolucoes das texturas de UI.
constexpr int kLauncherTexW = 640;
constexpr int kLauncherTexH = 480;
constexpr int kHomeTexW = 512;
constexpr int kHomeTexH = 320;
constexpr int kBrowserTexW = 768;
constexpr int kBrowserTexH = 480;
constexpr int kVideosTexW = 512;
constexpr int kVideosTexH = 640;
constexpr int kCtrlTexW = 512;
constexpr int kCtrlTexH = 128;
constexpr int kHudTexW = 512;
constexpr int kHudTexH = 160;
constexpr int kStatusTexW = 448;
constexpr int kStatusTexH = 96;

// Jogos.
constexpr int kTargetCount = 5;
constexpr float kTargetRadius = 0.24f;
constexpr float kTargetGameDuration = 60.0f;
constexpr int kSimonPads = 4;

constexpr float kZNear = 0.1f;
constexpr float kZFar = 100.f;

// ---------------------------------------------------------------------------
// IDs / telas
// ---------------------------------------------------------------------------

// Apps do launcher.
enum AppId {
  kAppBrowser = 0,
  kAppVideos = 1,
  kAppVideo360 = 2,
  kAppTargets = 3,
  kAppSimon = 4,
};

enum AppScreen {
  kScreenLauncher = 0,
  kScreenBrowser,
  kScreenVideos,
  kScreenPlayer,
  kScreenVideo360,
  kScreenGameTargets,
  kScreenGameSimon,
};

// IDs de acoes clicaveis.
enum ButtonId {
  kButtonNone = -1,
  // launcher
  kHomeBrowser = 0,   // legado (tela antiga de inicio)
  kHomeViewer = 1,
  kHomeExit = 2,
  kLauncherTile0 = 10,  // + kAppX => tile do app
  // videos list
  kVideosUpdate = 20,
  kVideosClose = 21,
  kVideosRow0 = 100,    // + index
  // player controls
  kPlayerClose = 30,
  kPlayerBack10 = 31,
  kPlayerPlayPause = 32,
  kPlayerFwd10 = 33,
  // 360 controls
  kVrClose = 40,
  kVrPlayPause = 41,
  // games HUD
  kGameRestart = 50,
  kGameExit = 51,
  // browser
  kBrowserClose = 60,
  kBrowserHome = 61,
};

enum HoverType { kHoverNone = 0, kHoverButton = 1, kHoverLink = 2 };

// Estado das maos recebido do Java (MediaPipe + filtros), normalizado [0..1].
struct HandFrame {
  bool has_left = false;
  bool has_right = false;
  bool pinch_left = false;
  bool pinch_right = false;
  std::array<float, 63> left{};
  std::array<float, 63> right{};
};

// Quad texturizado no mundo (travado no yaw).
struct Panel {
  bool visible = false;
  std::array<float, 3> position{0.0f, 0.0f, 0.0f};
  Matrix4x4 world_from_panel;
  float width = 1.0f;
  float height = 1.0f;
  GLuint texture = 0;
  bool external_oes = false;  // renderiza com shader de video (OES)

  struct HitRegion {
    float u0, v0, u1, v1;
    int id;
  };
  std::vector<HitRegion> regions;
};

struct PageLink {
  float u0, v0, u1, v1;
  std::string url;
};

struct VideoEntry {
  std::string title;
  long long id = -1;
};

struct Target {
  std::array<float, 3> pos{0.0f, 0.0f, -2.5f};
  bool alive = false;
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

  void UpdateHands(JNIEnv* env, jfloatArray left, jfloatArray right,
                   jboolean pinch_left, jboolean pinch_right);
  void UpdateCameraFrame(JNIEnv* env, jobject byte_buffer, jint width,
                         jint height);
  void UpdatePage(JNIEnv* env, jobject byte_buffer, jint width, jint height,
                  jfloatArray rects, jobjectArray urls);

  // Apps / video / jogos (chamados via JNI pelo Java).
  void OpenApp(int app_id);
  void SetVideoList(JNIEnv* env, jobjectArray titles, jlongArray ids,
                    jboolean have_permission);
  void SetVideoTexture(jint tex_id);
  void UpdateVideoTransform(JNIEnv* env, jfloatArray matrix);
  void SetVideoInfo(jint width, jint height, jlong duration_ms,
                    jlong position_ms, jboolean playing);

 private:
  bool UpdateDeviceParams();
  void GlSetup();
  void GlTeardown();
  Matrix4x4 GetPose();

  void BuildGeometry();
  void BuildUiTextures(JNIEnv* env);
  void ResetPage();
  void RebuildVideosTexture();
  void RebuildPlayerControls();
  void RebuildVrControls();
  void RebuildHud();
  void RebuildStatus();

  void DrawWorld(Matrix4x4 eye_view, Matrix4x4 projection);
  void DrawPassthrough(Matrix4x4 eye_view, Matrix4x4 projection);
  void Draw360Video(Matrix4x4 eye_view, Matrix4x4 projection);
  void DrawQuad(const Panel& panel, Matrix4x4 eye_view, Matrix4x4 projection,
                bool blend, bool depth_write);
  void DrawColorQuad(const Matrix4x4& model, const std::array<float, 4>& color,
                     Matrix4x4 eye_view, Matrix4x4 projection);
  void DrawHandsAndPointer(Matrix4x4 eye_view, Matrix4x4 projection);
  void DrawGameTargets(Matrix4x4 eye_view, Matrix4x4 projection);
  void DrawGameSimon(Matrix4x4 eye_view, Matrix4x4 projection);

  void SwitchScreen(AppScreen screen);
  void PlaceAllPanels();
  void PlacePanelInFront(Panel& panel, float w, float h, float dist,
                         float height, float up_offset);
  void ShowHome();  // launcher
  void StartTargetsGame();
  void RespawnTarget(Target& t);
  void UpdateTargetsGame(float now);
  void StartSimonGame();
  void ExtendSimonSequence();
  void UpdateSimonGame(float now);
  void HandleInteraction();
  void ClickCurrentHover();

  void NotifyOpenUrl(const std::string& url);
  void NotifyCloseBrowser();
  void NotifyExit();
  void NotifyRequestVideos();
  void NotifyPlayVideo(long long id, bool is360);
  void NotifyVideoControl(int action);

  JavaVM* java_vm_ = nullptr;
  jobject java_activity_ = nullptr;
  jobject java_asset_mgr_ = nullptr;
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
  GLuint texture_ = 0;

  // Programas.
  GLuint passthrough_program_ = 0;
  GLuint quad_program_ = 0;
  GLuint oes_quad_program_ = 0;
  GLuint oes_360_program_ = 0;
  GLuint color_program_ = 0;

  // Atributos/uniformes.
  GLuint pt_position_param_ = 0, pt_mvp_param_ = 0, pt_texture_param_ = 0,
         pt_fov_param_ = 0, pt_mirror_param_ = 0;
  GLuint quad_position_param_ = 0, quad_uv_param_ = 0, quad_mvp_param_ = 0,
         quad_texture_param_ = 0, quad_alpha_param_ = 0;
  GLuint oes_q_position_param_ = 0, oes_q_uv_param_ = 0, oes_q_mvp_param_ = 0,
         oes_q_texture_param_ = 0, oes_q_texmatrix_param_ = 0;
  GLuint oes_360_position_param_ = 0, oes_360_mvp_param_ = 0,
         oes_360_texture_param_ = 0, oes_360_texmatrix_param_ = 0;
  GLuint color_position_param_ = 0, color_mvp_param_ = 0, color_param_ = 0,
         color_point_size_param_ = 0, color_is_point_param_ = 0;

  // Geometria.
  std::vector<GLfloat> sphere_vertices_;
  std::vector<GLushort> sphere_indices_;
  std::vector<GLfloat> quad_vertices_;
  std::vector<GLfloat> quad_uv_;
  std::vector<GLushort> quad_indices_;
  std::vector<GLfloat> pad_vertices_;   // quad unitario sem uv (color prog)

  // Texturas.
  GLuint camera_texture_ = 0;
  int camera_tex_width_ = 640;
  int camera_tex_height_ = 480;
  bool camera_texture_ready_ = false;

  GLuint launcher_texture_ = 0;
  GLuint status_texture_ = 0;
  GLuint home_texture_ = 0;
  GLuint browser_texture_ = 0;
  GLuint page_texture_ = 0;
  GLuint start_page_texture_ = 0;
  GLuint videos_texture_ = 0;
  GLuint player_ctrl_texture_ = 0;
  GLuint vr_ctrl_texture_ = 0;
  GLuint hud_texture_ = 0;

  // Estado.
  AppScreen screen_ = kScreenLauncher;
  Matrix4x4 head_view_;
  HandFrame hands_;
  bool has_hands_ = false;
  float placement_yaw_ = 1e9f;  // yaw usado na ultima colocacao de paineis

  Panel launcher_panel_;
  Panel status_panel_;
  Panel home_panel_;  // legado (nao usado, mantido p/ browser regions)
  Panel browser_frame_panel_;
  Panel page_panel_;
  Panel videos_panel_;
  Panel player_panel_;
  Panel player_ctrl_panel_;
  Panel vr_ctrl_panel_;
  Panel hud_panel_;
  std::vector<PageLink> page_links_;

  // Videos.
  std::vector<VideoEntry> videos_;
  bool videos_have_permission_ = false;
  bool videos_mode_360_ = false;
  int video_tex_width_ = 16, video_tex_height_ = 9;
  long long video_duration_ms_ = 0;
  long long video_position_ms_ = 0;
  bool video_playing_ = false;
  bool player_ctrl_playing_shown_ = false;

  // Jogos.
  std::vector<Target> targets_;
  int targets_score_ = 0;
  float targets_end_time_ = 0.0f;
  bool targets_running_ = false;
  float base_yaw_ = 0.0f;
  std::array<std::array<float, 3>, kSimonPads> simon_pads_{};  // mundo
  std::array<Matrix4x4, kSimonPads> simon_models_{};
  int simon_lit_ = -1;
  float simon_lit_until_ = 0.0f;
  float simon_next_step_ = 0.0f;
  size_t simon_playback_index_ = 0;
  std::vector<int> simon_sequence_;
  int simon_level_ = 0;
  bool simon_playing_back_ = false;
  bool simon_failed_ = false;

  // Apontador.
  bool pointer_valid_ = false;
  std::array<float, 3> pointer_origin_w_{0.0f, 0.0f, 0.0f};
  std::array<float, 3> pointer_end_w_{0.0f, 0.0f, -1.0f};
  std::array<float, 4> pointer_color_{1.0f, 1.0f, 1.0f, 1.0f};
  HoverType hover_type_ = kHoverNone;
  int hover_id_ = kButtonNone;
  std::string hover_url_;
  bool pinch_was_down_ = false;

  // Video (OES).
  GLuint video_oes_texture_ = 0;
  std::array<float, 16> video_tex_matrix_{};
  bool video_transform_valid_ = false;

  // FPS / diagnostico.
  float cam_fps_ = 0.0f;
  float hands_fps_ = 0.0f;
  int cam_frame_count_ = 0;
  int hands_frame_count_ = 0;
  float last_fps_time_ = 0.0f;
  float last_status_time_ = 0.0f;

  // Metodos Java (callbacks).
  jmethodID method_open_url_ = nullptr;
  jmethodID method_close_browser_ = nullptr;
  jmethodID method_exit_ = nullptr;
  jmethodID method_request_videos_ = nullptr;
  jmethodID method_play_video_ = nullptr;
  jmethodID method_video_control_ = nullptr;
};

}  // namespace pina_xr

#endif  // PINA_XR_JNI_PINA_APP_H_
