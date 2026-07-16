cmake_minimum_required(VERSION 3.22.1)

project(inkleaf_enhancement LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

set(NCNN_ROOT "${CMAKE_CURRENT_LIST_DIR}/third_party/ncnn/20260526/${ANDROID_ABI}")
set(ncnn_DIR "${NCNN_ROOT}/lib/cmake/ncnn")
find_package(ncnn 20260526 EXACT REQUIRED CONFIG)

function(inkleaf_add_shader SHADER_PATH OUTPUT_LIST)
    get_filename_component(SHADER_NAME "${SHADER_PATH}" NAME_WE)
    get_filename_component(SHADER_DIRECTORY "${SHADER_PATH}" DIRECTORY)
    set(SHADER_HEADER "${CMAKE_CURRENT_BINARY_DIR}/${SHADER_NAME}.comp.hex.h")
    add_custom_command(
        OUTPUT "${SHADER_HEADER}"
        COMMAND
            "${CMAKE_COMMAND}"
            "-DSHADER_SRC=${SHADER_PATH}"
            "-DSHADER_COMP_HEADER=${SHADER_HEADER}"
            -P "${SHADER_DIRECTORY}/generate_shader_comp_header.cmake"
        DEPENDS
            "${SHADER_PATH}"
            "${SHADER_DIRECTORY}/generate_shader_comp_header.cmake"
        VERBATIM
    )
    set(${OUTPUT_LIST} ${${OUTPUT_LIST}} "${SHADER_HEADER}" PARENT_SCOPE)
endfunction()

set(REALCUGAN_DIRECTORY "${CMAKE_CURRENT_LIST_DIR}/third_party/realcugan")
set(WAIFU2X_DIRECTORY "${CMAKE_CURRENT_LIST_DIR}/third_party/waifu2x")
set(REALESRGAN_DIRECTORY "${CMAKE_CURRENT_LIST_DIR}/third_party/realesrgan")

set(GENERATED_SHADER_HEADERS)
foreach(SHADER_NAME IN ITEMS
    realcugan_preproc
    realcugan_postproc
    realcugan_4x_postproc
    realcugan_preproc_tta
    realcugan_postproc_tta
    realcugan_4x_postproc_tta
)
    inkleaf_add_shader(
        "${REALCUGAN_DIRECTORY}/${SHADER_NAME}.comp"
        GENERATED_SHADER_HEADERS
    )
endforeach ()
foreach (SHADER_NAME IN ITEMS
        realesrgan_preproc
        realesrgan_postproc
        realesrgan_preproc_tta
        realesrgan_postproc_tta
)
    inkleaf_add_shader(
            "${REALESRGAN_DIRECTORY}/${SHADER_NAME}.comp"
            GENERATED_SHADER_HEADERS
    )
endforeach()
foreach(SHADER_NAME IN ITEMS
    waifu2x_preproc
    waifu2x_postproc
    waifu2x_preproc_tta
    waifu2x_postproc_tta
)
    inkleaf_add_shader(
        "${WAIFU2X_DIRECTORY}/${SHADER_NAME}.comp"
        GENERATED_SHADER_HEADERS
    )
endforeach()

add_library(
    inkleaf_enhancement
    SHARED
    enhancement_jni.cpp
    "${REALCUGAN_DIRECTORY}/realcugan.cpp"
    "${WAIFU2X_DIRECTORY}/waifu2x.cpp"
        "${REALESRGAN_DIRECTORY}/realesrgan.cpp"
    ${GENERATED_SHADER_HEADERS}
)

target_include_directories(
    inkleaf_enhancement
    PRIVATE
    "${CMAKE_CURRENT_BINARY_DIR}"
    "${REALCUGAN_DIRECTORY}"
    "${WAIFU2X_DIRECTORY}"
        "${REALESRGAN_DIRECTORY}"
)

target_compile_options(
    inkleaf_enhancement
    PRIVATE
    -Wall
    -Wextra
    -Wno-unused-parameter
)

target_link_options(inkleaf_enhancement PRIVATE "-Wl,--exclude-libs,ALL")
target_link_libraries(inkleaf_enhancement PRIVATE ncnn)
