# sherpa-onnx faz lookup JNI de classes e campos por nome em runtime.
-keep class com.k2fsa.sherpa.onnx.** { *; }

# LiteRT-LM acessa estas classes/fields pelo JNI.
-keep class com.google.ai.edge.litertlm.LiteRtLmJni { *; }
-keep class com.google.ai.edge.litertlm.InputData$Text { *; }
-keep class com.google.ai.edge.litertlm.BenchmarkInfo { *; }
-keep class com.google.ai.edge.litertlm.LiteRtLmJniException { *; }

# Settings.toString() identifica segredos pelo nome do campo, e não pelo valor.
-keepclassmembernames class com.keyflow.data.Settings { <fields>; }

# WorkManager (transitivo do play-services-ads) instancia WorkDatabase_Impl por reflexão via Room.
-keep class androidx.work.impl.WorkDatabase_Impl {
    public <init>();
}
-keep class * extends androidx.room.RoomDatabase {
    public <init>();
}

# AIDL e Parcelable para IPC entre o processo principal e os serviços :inference e :reasoning.
-keep class com.keyflow.transcription.local.**$Stub { *; }
-keep class com.keyflow.reasoning.local.**$Stub { *; }
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# AndroidSVG usa reflexão para parsing e estilização de nós vetoriais.
-keep public class com.caverock.androidsvg.** { public *; }
