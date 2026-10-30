#!/usr/bin/env bash
# Sobe o servidor SSH de teste (CMP-T4) fora do processo do Gradle e liga a porta ao aparelho com adb reverse.
# Uso: scripts/ssh-fixture.sh subir [--sem-adb] | parar | estado
# O JAR sai de `gradle :app:prepararFixtureSsh` (app/build/reports/computador/ssh-fixture.jar).
# Só dados falsos: host key e usuários de teste fixos, nada de ~/.ssh nem de cofre. Escuta só em 127.0.0.1.
set -euo pipefail
raiz="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
jar="${KEYFLOW_FIXTURE_JAR:-$raiz/app/build/reports/computador/ssh-fixture.jar}"
dir="${KEYFLOW_FIXTURE_DIR:-${TMPDIR:-/tmp}/keyflow-ssh-fixture-$(id -u)}"
pronto="$dir/pronto.json"; pidf="$dir/pid"
java_bin="${JAVA:-$(command -v java || echo /srv/dev/ferramentas/jdk-17/bin/java)}"

porta_pronta() { sed -n 's/.*"porta":\([0-9]*\).*/\1/p' "$pronto"; }
vivo() { [ -f "$pidf" ] && kill -0 "$(cat "$pidf")" 2>/dev/null; }

parar() {
  if [ -f "$pronto" ] && command -v adb >/dev/null 2>&1; then
    adb ${ANDROID_SERIAL:+-s "$ANDROID_SERIAL"} reverse --remove "tcp:$(porta_pronta)" >/dev/null 2>&1 || true
  fi
  if vivo; then
    p="$(cat "$pidf")"; kill "$p" 2>/dev/null || true
    for _ in $(seq 1 50); do kill -0 "$p" 2>/dev/null || break; sleep 0.1; done
    kill -0 "$p" 2>/dev/null && kill -9 "$p" 2>/dev/null || true
  fi
  rm -rf "$dir"
}

case "${1:-}" in
  subir)
    [ -f "$jar" ] || { echo "JAR ausente: $jar (rode :app:prepararFixtureSsh)" >&2; exit 2; }
    vivo && { echo "já está de pé: porta $(porta_pronta)"; exit 0; }
    rm -rf "$dir"; mkdir -p "$dir"; chmod 700 "$dir"
    nohup "$java_bin" -jar "$jar" --pronto "$pronto" >"$dir/log" 2>&1 &
    echo $! >"$pidf"
    for _ in $(seq 1 100); do [ -f "$pronto" ] && break; sleep 0.1; done
    [ -f "$pronto" ] || { echo "servidor não ficou pronto" >&2; cat "$dir/log" >&2; parar; exit 1; }
    pid_real="$(sed -n 's/.*"pid":\([0-9]*\).*/\1/p' "$pronto")"; echo "$pid_real" >"$pidf"
    porta="$(porta_pronta)"
    if [ "${2:-}" != "--sem-adb" ]; then
      adb ${ANDROID_SERIAL:+-s "$ANDROID_SERIAL"} reverse "tcp:$porta" "tcp:$porta" >/dev/null
    fi
    cat "$pronto" ;;
  parar) parar; echo "parado" ;;
  estado) if vivo; then cat "$pronto"; else echo "parado"; exit 1; fi ;;
  *) echo "uso: $0 subir [--sem-adb] | parar | estado" >&2; exit 2 ;;
esac
