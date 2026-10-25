#!/usr/bin/env bash
# Confere um AAR do sherpa-onnx recompilado sem TTS (workflow .github/workflows/sherpa-sem-tts.yml).
#
# O AAR oficial é compilado com SHERPA_ONNX_ENABLE_TTS=ON, que embute o eSpeak NG (GPL-3.0) nas
# libsherpa-onnx-jni.so e libsherpa-onnx-c-api.so. O KeyFlow não usa TTS e nada GPL pode ir junto com o app.
#
# Uso:
#   scripts/verificar-aar-sherpa.sh <aar> [<aar-oficial>]
#
# Sem o segundo argumento só vale o que é absoluto (nada de eSpeak, 16 KB, ABIs, classes que o app usa).
# Com o AAR oficial, confere também que a única diferença é o TTS: mesmo layout do zip, mesmas classes
# (menos as de TTS), mesma libonnxruntime.so (byte a byte) e os mesmos símbolos exportados (menos os de TTS).
# Rode com o próprio AAR oficial como primeiro argumento para ver o controle negativo: tem de falhar.
#
# Sai com 0 se tudo passou e 1 se alguma conferência falhou (todas rodam; o relatório sai completo).
# Precisa de unzip, strings, readelf, awk, sort e comm (binutils e coreutils).
set -uo pipefail

AAR=${1:?uso: verificar-aar-sherpa.sh <aar> [<aar-oficial>]}
OFICIAL=${2:-}

# O mesmo padrão do `strings` da regra do dono do produto: texto que só existe no eSpeak NG.
PADRAO_ESPEAK='has not been built into espeak-ng|Empty _dict file|espeak_ng_'
ABIS="arm64-v8a armeabi-v7a x86 x86_64"
LIBS_SHERPA="libsherpa-onnx-jni.so libsherpa-onnx-c-api.so libsherpa-onnx-cxx-api.so"
# Classes do sherpa-onnx que o app importa (OfflineRecognizer e as configs de Whisper e transducer).
CLASSES_DO_APP="OfflineRecognizer OfflineRecognizerConfig OfflineRecognizerResult OfflineModelConfig \
OfflineWhisperModelConfig OfflineTransducerModelConfig OfflineStream"
# O que a remoção do TTS pode levar embora (classes Kotlin, símbolos JNI e C).
FILTRO_TTS='Tts|GeneratedAudio|GenerationConfig'

falhas=0
falha() { echo "FALHOU: $*"; falhas=$((falhas + 1)); }
ok() { echo "ok: $*"; }

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/novo"
unzip -q -o "$AAR" -d "$TMP/novo" || { echo "não consegui abrir $AAR" >&2; exit 1; }
if [[ -n $OFICIAL ]]; then
  mkdir -p "$TMP/oficial"
  unzip -q -o "$OFICIAL" -d "$TMP/oficial" || { echo "não consegui abrir $OFICIAL" >&2; exit 1; }
fi

# Quantas linhas do `strings -a` casam o padrão do eSpeak NG neste .so.
contar_espeak() { strings -a "$1" | grep -c -E "$PADRAO_ESPEAK" || true; }

echo "== 1. eSpeak NG (GPL) fora dos .so (strings -a | grep -c -E '$PADRAO_ESPEAK')"
printf '%-40s %8s %8s\n' "arquivo" "oficial" "este"
for abi in $ABIS; do
  for so in "$TMP"/novo/jni/$abi/*.so; do
    [[ -f $so ]] || continue
    nome=jni/$abi/$(basename "$so")
    n=$(contar_espeak "$so")
    antes="-"
    [[ -n $OFICIAL && -f $TMP/oficial/$nome ]] && antes=$(contar_espeak "$TMP/oficial/$nome")
    printf '%-40s %8s %8s\n' "$nome" "$antes" "$n"
    [[ $n -eq 0 ]] || falha "$nome ainda tem $n linha(s) do eSpeak NG"
  done
done

echo "== 2. ABIs e bibliotecas"
antes=$falhas
for abi in $ABIS; do
  for lib in libonnxruntime.so $LIBS_SHERPA; do
    [[ -f $TMP/novo/jni/$abi/$lib ]] || falha "falta jni/$abi/$lib"
  done
done
[[ $falhas -eq $antes ]] && ok "4 ABIs com onnxruntime, jni, c-api e cxx-api"

echo "== 3. Alinhamento de 16 KB (requisito da Play) em todo segmento PT_LOAD"
antes=$falhas
for so in "$TMP"/novo/jni/*/*.so; do
  [[ -f $so ]] || continue
  nome=${so#"$TMP/novo/"}
  # A última coluna das linhas LOAD do readelf é o alinhamento em hexadecimal (0x4000 = 16384).
  menor=""
  for a in $(readelf -lW "$so" | awk '$1 == "LOAD" {print $NF}'); do
    v=$((a))
    if [[ -z $menor || $v -lt $menor ]]; then menor=$v; fi
  done
  if [[ -z $menor ]]; then
    falha "$nome: não achei segmentos LOAD"
  elif [[ $menor -lt 16384 ]]; then
    falha "$nome: PT_LOAD com alinhamento $menor < 16384"
  fi
done
[[ $falhas -eq $antes ]] && ok "todos os .so com PT_LOAD >= 16384"

echo "== 4. Classes Kotlin/Java"
mkdir -p "$TMP/novo-classes"
unzip -q -o "$TMP/novo/classes.jar" -d "$TMP/novo-classes"
(cd "$TMP/novo-classes" && find . -name '*.class' | sort) > "$TMP/classes-novo.txt"
for c in $CLASSES_DO_APP; do
  grep -q "/$c\.class$" "$TMP/classes-novo.txt" || falha "falta a classe $c (o app usa)"
done
if grep -E "$FILTRO_TTS" "$TMP/classes-novo.txt"; then
  falha "ainda há classes de TTS no classes.jar"
fi
echo "classes: $(wc -l < "$TMP/classes-novo.txt")"

if [[ -n $OFICIAL ]]; then
  echo "== 5. Contra o AAR oficial: a única diferença é o TTS"
  # Layout do zip.
  diff <(unzip -Z1 "$OFICIAL" | sort) <(unzip -Z1 "$AAR" | sort) > /dev/null \
    && ok "mesma lista de arquivos no zip" || falha "a lista de arquivos do zip difere do oficial"
  for f in AndroidManifest.xml proguard.txt META-INF/com/android/build/gradle/aar-metadata.properties; do
    cmp -s "$TMP/oficial/$f" "$TMP/novo/$f" && ok "$f idêntico" || falha "$f difere do oficial"
  done
  # Classes: oficial menos TTS == novo.
  mkdir -p "$TMP/oficial-classes"
  unzip -q -o "$TMP/oficial/classes.jar" -d "$TMP/oficial-classes"
  (cd "$TMP/oficial-classes" && find . -name '*.class' | sort) > "$TMP/classes-oficial.txt"
  grep -v -E "$FILTRO_TTS" "$TMP/classes-oficial.txt" > "$TMP/classes-oficial-sem-tts.txt"
  if diff "$TMP/classes-oficial-sem-tts.txt" "$TMP/classes-novo.txt"; then
    ok "classes = oficial menos as $(($(wc -l < "$TMP/classes-oficial.txt") - $(wc -l < "$TMP/classes-oficial-sem-tts.txt"))) de TTS"
  else
    falha "a lista de classes difere do oficial além do TTS (diff acima)"
  fi
  # ONNX Runtime: tem de ser byte a byte a mesma.
  for abi in $ABIS; do
    cmp -s "$TMP/oficial/jni/$abi/libonnxruntime.so" "$TMP/novo/jni/$abi/libonnxruntime.so" \
      && ok "jni/$abi/libonnxruntime.so idêntica à oficial" \
      || falha "jni/$abi/libonnxruntime.so difere da oficial (outra versão do ONNX Runtime?)"
  done
  # Símbolos exportados: só podem sumir os de TTS e nada novo pode aparecer.
  simbolos() { readelf --dyn-syms -W "$1" | awk '$7 != "UND" && NF >= 8 {print $8}' | sort -u; }
  for abi in $ABIS; do
    for lib in $LIBS_SHERPA; do
      simbolos "$TMP/oficial/jni/$abi/$lib" > "$TMP/sim-oficial.txt"
      simbolos "$TMP/novo/jni/$abi/$lib" > "$TMP/sim-novo.txt"
      sumiram=$(comm -23 "$TMP/sim-oficial.txt" "$TMP/sim-novo.txt")
      apareceram=$(comm -13 "$TMP/sim-oficial.txt" "$TMP/sim-novo.txt")
      naotts=$(grep -v -E "$FILTRO_TTS" <<< "$sumiram" || true)
      if [[ -n $apareceram ]]; then
        falha "jni/$abi/$lib: símbolos novos que o oficial não tem: $(tr '\n' ' ' <<< "$apareceram")"
      elif [[ -n $naotts ]]; then
        falha "jni/$abi/$lib: sumiram símbolos que não são de TTS: $(tr '\n' ' ' <<< "$naotts")"
      else
        echo "ok: jni/$abi/$lib: $(grep -c . <<< "$sumiram" || true) símbolo(s) de TTS a menos, nada mais mudou"
      fi
    done
  done
fi

echo
if [[ $falhas -eq 0 ]]; then
  echo "RESULTADO: AAR sem eSpeak NG e compatível com o que o app usa ($AAR)"
  exit 0
fi
echo "RESULTADO: $falhas conferência(s) falharam ($AAR)"
exit 1
