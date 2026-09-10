/*
 * Copyright 2019 Google LLC (adaptado para o Pina XR)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Utilidades de matemática/GL, derivadas do sample oficial do Cardboard SDK
 * (hellocardboard-android) e estendidas para o Pina XR.
 */

#ifndef PINA_XR_JNI_UTIL_H_
#define PINA_XR_JNI_UTIL_H_

#include <android/asset_manager.h>
#include <jni.h>

#include <array>
#include <vector>

#include <GLES2/gl2.h>

#define LOG_TAG "PinaXR"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define PINA_CHECK(condition)                                              \
  if (!(condition)) {                                                      \
    LOGE("*** CHECK FAILED at %s:%d: %s", __FILE__, __LINE__, #condition); \
    abort();                                                               \
  }

namespace pina_xr {

class Matrix4x4 {
 public:
  float m[4][4];

  // Encadeia transformacoes (mesma semantica do sample do Cardboard):
  // `a * b` le-se "a a partir de b" na cadeia de transformacoes.
  Matrix4x4 operator*(const Matrix4x4& right);

  // Transforma um ponto/vetor (x,y,z,w).
  std::array<float, 4> operator*(const std::array<float, 4>& vec);

  // Converte para array de floats para o OpenGL.
  std::array<float, 16> ToGlArray();
};

struct Quatf {
  float x;
  float y;
  float z;
  float w;

  Quatf(float x_, float y_, float z_, float w_) : x(x_), y(y_), z(z_), w(w_) {}
  Quatf() : x(0), y(0), z(0), w(1) {}

  static Quatf FromXYZW(float q[4]) { return Quatf(q[0], q[1], q[2], q[3]); }

  Matrix4x4 ToMatrix();
};

Matrix4x4 GetMatrixFromGlArray(float* vec);

Matrix4x4 GetTranslationMatrix(const std::array<float, 3>& translation);

float AngleBetweenVectors(const std::array<float, 4>& vec1,
                          const std::array<float, 4>& vec2);

int64_t GetBootTimeNano();

float RandomUniformFloat(float min, float max);
int RandomUniformInt(int max_val);

void CheckGlError(const char* file, int line, const char* label);
#define CHECKGLERROR(label) CheckGlError(__FILE__, __LINE__, label)

GLuint LoadGLShader(GLenum type, const char* shader_source);

// Mesh texturizada carregada de um .obj dos assets.
class TexturedMesh {
 public:
  TexturedMesh() = default;

  bool Initialize(GLuint position_attrib, GLuint uv_attrib,
                  const std::string& obj_file_path, AAssetManager* asset_mgr);

  void Draw() const;

 private:
  std::vector<GLfloat> vertices_;
  std::vector<GLfloat> uv_;
  std::vector<GLushort> indices_;
  GLuint position_attrib_{0};
  GLuint uv_attrib_{0};
};

// Textura carregada de um PNG dos assets.
class Texture {
 public:
  Texture() = default;
  ~Texture();

  bool Initialize(JNIEnv* env, jobject java_asset_mgr,
                  const std::string& texture_path);

  void Bind() const;
  GLuint id() const { return texture_id_; }

 private:
  GLuint texture_id_{0};
};

}  // namespace pina_xr

#endif  // PINA_XR_JNI_UTIL_H_
