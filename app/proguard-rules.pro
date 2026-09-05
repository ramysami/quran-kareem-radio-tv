-dontwarn org.slf4j.**
-dontwarn okhttp3.**
-keep class androidx.media3.** { *; }

# ONNX Runtime's native library reaches back into these classes by name, so
# nothing under here may be renamed or dropped however unused it looks.
-keep class ai.onnxruntime.** { *; }
-keepclassmembers class ai.onnxruntime.** { *; }
