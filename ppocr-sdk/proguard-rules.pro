# ONNX Runtime creates result wrappers and metadata from JNI by class and member signature.
# Keep its Java bridge intact so R8 cannot remove constructors that native inference calls.
-keep class ai.onnxruntime.** { *; }
