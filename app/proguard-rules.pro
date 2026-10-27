# Regras do R8 do release (V1-3). As bibliotecas que trazem regras nos próprios AARs (Billing, AdMob/UMP, Tink, DataStore,
# OkHttp, Compose, coroutines, WorkManager) não precisam de nada aqui; ficam as classes que o app e as bibliotecas SEM regra
# (sherpa-onnx, LiteRT-LM e AndroidSVG, que entram como AAR/arquivo cru) acessam por nome. Os componentes do manifesto
# (SetupActivity, KeyFlowKeyboardService, OverlayService, InferenceService, ReasoningService...) o AAPT2 já mantém sozinho
# (aapt_rules.txt); não repetir aqui. Conferência: scripts/smoke-release.sh e o mapping.txt do release.

# Stack traces do Play Console: a Play desofusca com o mapping.txt que vai embutido no AAB; sem estes atributos o rastro
# perderia arquivo e linha.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# sherpa-onnx (AAR local, sem regras): o libsherpa-onnx-jni.so faz FindClass/GetFieldID/GetMethodID por nome nas classes de
# configuração (OfflineRecognizerConfig, OfflineWhisperModelConfig...) e no resultado. Renomeado ou podado, o modelo não
# carrega (NoSuchFieldError/ClassNotFoundException no :inference).
-keep class com.k2fsa.sherpa.onnx.** { *; }

# LiteRT-LM (AAR sem regras): o liblitertlm_jni.so resolve por nome BenchmarkInfo, InputData$Text/Image/Audio e
# LiteRtLmJniException, lê os campos de SamplerConfig, ThinkingConfig, RepetitionPenaltyConfig etc. e CHAMA DE VOLTA
# JniMessageCallback.onMessage/onDone/onError e JniInferenceCallback — métodos que nenhum código Java chama, então o R8
# os apagava da interface (medido no mapping de 2026-10-24: a interface ficava sem métodos e a implementação era fundida
# noutra classe) e o Raciocínio local morreria com NoSuchMethodError. O pacote todo (~50 classes, custo desprezível).
-keep class com.google.ai.edge.litertlm.** { *; }

# ONNX Runtime Java (AAR sem regras; só o classes.jar entra, ver prepareOrtJava no app/build.gradle.kts): o libonnxruntime4j_jni.so
# faz FindClass/GetFieldID/GetMethodID por nome (OnnxTensor, OrtSession, OrtException, SessionOptions...) e devolve objetos
# construídos por JNI. Renomeado ou podado, o OCR local morre com NoSuchFieldError/NoSuchMethodError no :vision.
-keep class ai.onnxruntime.** { *; }

# Settings.toString() identifica segredos pelo nome do campo, e não pelo valor.
-keepclassmembernames class com.keyflow.data.Settings { <fields>; }

# WorkManager (transitivo do play-services-ads) instancia WorkDatabase_Impl por reflexão via Room.
-keep class androidx.work.impl.WorkDatabase_Impl {
    public <init>();
}
-keep class * extends androidx.room.RoomDatabase {
    public <init>();
}

# AIDL e Parcelable para IPC entre o processo principal e os serviços :inference, :reasoning e :vision.
-keep class com.keyflow.transcription.local.**$Stub { *; }
-keep class com.keyflow.reasoning.local.**$Stub { *; }
-keep class com.keyflow.vision.service.**$Stub { *; }
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# AndroidSVG (AAR sem regras) usa reflexão para parsing e estilização de nós vetoriais.
-keep public class com.caverock.androidsvg.** { public *; }
