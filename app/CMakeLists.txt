# Pina XR - lib nativa (renderizador Cardboard + cena 3D).
cmake_minimum_required(VERSION 3.22.1)
project(pina_xr VERSION 0.1.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED True)
add_compile_options(-Wall -Wextra)

# Dependências padrão Android
find_library(ANDROID_LIB android)
find_library(GLESv2_LIB GLESv2)
find_library(GLESv3_LIB GLESv3)
find_library(LOG_LIB log)

set(libs_dir ${CMAKE_CURRENT_SOURCE_DIR}/libraries)

set(CARDBOARD_LIB ${libs_dir}/jni/${ANDROID_ABI}/libGfxPluginCardboard.so)
set(CARDBOARD_INCLUDE_DIR ${libs_dir})

# Fontes nativos do Pina XR
file(GLOB PINA_SRC "src/main/jni/*.cc")

add_library(pina_jni SHARED ${PINA_SRC})

target_include_directories(pina_jni PRIVATE ${CARDBOARD_INCLUDE_DIR})
target_link_libraries(pina_jni
    ${ANDROID_LIB}
    ${GLESv2_LIB}
    ${GLESv3_LIB}
    ${LOG_LIB}
    ${CARDBOARD_LIB})

target_link_options(pina_jni PRIVATE "-Wl,-z,max-page-size=16384")
