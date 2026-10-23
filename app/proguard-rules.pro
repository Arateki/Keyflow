# sherpa-onnx faz lookup JNI de classes e campos por nome em runtime.
-keep class com.k2fsa.sherpa.onnx.** { *; }

# LiteRT-LM acessa estas classes/fields pelo JNI.
-keep class com.google.ai.edge.litertlm.LiteRtLmJni { *; }
-keep class com.google.ai.edge.litertlm.InputData$Text { *; }
-keep class com.google.ai.edge.litertlm.BenchmarkInfo { *; }
-keep class com.google.ai.edge.litertlm.LiteRtLmJniException { *; }

# Settings.toString() identifica segredos pelo nome do campo, e não pelo valor.
-keepclassmembernames class com.keyflow.data.Settings { <fields>; }
