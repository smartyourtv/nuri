# Ilona — sherpa-onnx JNI classes must survive R8 untouched (v0.4.0,
# the phone Scout rule): the native library calls these by name.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclassmembers class com.k2fsa.sherpa.onnx.** { *; }
