/*
 * Pina XR - ponte JNI Java <-> nativo.
 */

#include <jni.h>

#include <memory>

#include "pina_app.h"

#define JNI_METHOD(return_type, method_name)                          \
  JNIEXPORT return_type JNICALL                                       \
      Java_com_pina_xr_PinaActivity_##method_name

namespace {

inline jlong jptr(pina_xr::PinaApp* app) {
  return reinterpret_cast<intptr_t>(app);
}

inline pina_xr::PinaApp* native(jlong ptr) {
  return reinterpret_cast<pina_xr::PinaApp*>(ptr);
}

JavaVM* g_java_vm = nullptr;

}  // namespace

extern "C" {

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
  g_java_vm = vm;
  return JNI_VERSION_1_6;
}

JNI_METHOD(jlong, nativeOnCreate)(JNIEnv* env, jobject activity,
                                  jobject asset_mgr) {
  return jptr(new pina_xr::PinaApp(g_java_vm, activity, asset_mgr));
}

JNI_METHOD(void, nativeOnDestroy)(JNIEnv* /*env*/, jobject /*activity*/,
                                  jlong app) {
  delete native(app);
}

JNI_METHOD(void, nativeOnSurfaceCreated)(JNIEnv* env, jobject /*activity*/,
                                         jlong app) {
  native(app)->OnSurfaceCreated(env);
}

JNI_METHOD(void, nativeOnSurfaceChanged)(JNIEnv* /*env*/, jobject /*activity*/,
                                         jlong app, jint width, jint height) {
  native(app)->SetScreenParams(width, height);
}

JNI_METHOD(void, nativeOnDrawFrame)(JNIEnv* /*env*/, jobject /*activity*/,
                                    jlong app) {
  native(app)->OnDrawFrame();
}

JNI_METHOD(void, nativeOnTriggerEvent)(JNIEnv* /*env*/, jobject /*activity*/,
                                       jlong app) {
  native(app)->OnTriggerEvent();
}

JNI_METHOD(void, nativeOnPause)(JNIEnv* /*env*/, jobject /*activity*/,
                                jlong app) {
  native(app)->OnPause();
}

JNI_METHOD(void, nativeOnResume)(JNIEnv* /*env*/, jobject /*activity*/,
                                 jlong app) {
  native(app)->OnResume();
}

JNI_METHOD(void, nativeSwitchViewer)(JNIEnv* /*env*/, jobject /*activity*/,
                                     jlong app) {
  native(app)->SwitchViewer();
}

JNI_METHOD(void, nativeUpdateHands)
(JNIEnv* env, jobject /*activity*/, jlong app, jfloatArray left,
 jfloatArray right, jboolean pinch_left, jboolean pinch_right) {
  native(app)->UpdateHands(env, left, right, pinch_left, pinch_right);
}

JNI_METHOD(void, nativeUpdateCameraFrame)
(JNIEnv* env, jobject /*activity*/, jlong app, jobject byte_buffer, jint width,
 jint height) {
  native(app)->UpdateCameraFrame(env, byte_buffer, width, height);
}

JNI_METHOD(void, nativeUpdatePage)
(JNIEnv* env, jobject /*activity*/, jlong app, jobject byte_buffer, jint width,
 jint height, jfloatArray rects, jobjectArray urls) {
  native(app)->UpdatePage(env, byte_buffer, width, height, rects, urls);
}

}  // extern "C"
