# OpenCV Android build configuration for Inkleaf.
#
# Goal: produce a minimal libopencv_java4.so for arm64-v8a only, containing
# just the modules actually used by ppocr-sdk + app:
#   core, imgproc, imgcodecs, java (JNI binding), geometry (OpenCV 5.x).
#
# Consumed by .github/workflows/build-opencv-aar.yml via:
#   python3 build_sdk.py --config inkleaf-config.py --modules_list "..." ...
#
# This file is copied next to build_sdk.py (opencv/platforms/android/) before
# execution. build_sdk.py loads it via exec(), sharing its globals, so the ABI
# class (defined in build_sdk.py itself in OpenCV 5.x; build_sdk_helper.py was
# removed) is directly available — no import needed.

import os

# NDK min API level. App minSdk=29, but NDK API level may be lower (it is the
# floor of libc symbols the .so may link). 24 keeps broad device coverage and
# is well supported by every recent NDK.
ANDROID_NATIVE_API_LEVEL = int(os.environ.get('ANDROID_NATIVE_API_LEVEL', 24))

cmake_common_vars = {
    'ANDROID_COMPILE_SDK_VERSION': os.environ.get('ANDROID_COMPILE_SDK_VERSION', 36),
    'ANDROID_TARGET_SDK_VERSION': os.environ.get('ANDROID_TARGET_SDK_VERSION', 36),
    'ANDROID_MIN_SDK_VERSION': os.environ.get('ANDROID_MIN_SDK_VERSION', ANDROID_NATIVE_API_LEVEL),
    # OpenCV SDK gradle build versions (independent of the app's AGP/Gradle).
    'ANDROID_GRADLE_PLUGIN_VERSION': '8.12.0',
    'GRADLE_VERSION': '8.13',
    'KOTLIN_PLUGIN_VERSION': '2.0.21',
    # Match app's STL choice; libc++_shared is already shipped via pickFirsts.
    'ANDROID_STL': 'c++_shared',
    # 16 KB page size support (Android 15+).
    'ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES': 'ON',
}

ABIs = [
    ABI("3", "arm64-v8a", None, ndk_api_level=ANDROID_NATIVE_API_LEVEL, cmake_vars=cmake_common_vars),
]
