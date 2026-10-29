"""Regressões do guard do agente: ele tem de estar no DEX (nomes ofuscados), mapping obrigatório."""
import os
import subprocess
import runpy
import tempfile
import unittest
from pathlib import Path

CHECK = runpy.run_path(str(Path(__file__).resolve().parents[1] / "check-release-agent.py"))["check_agent"]


class ReleaseAgentTest(unittest.TestCase):
    MAPPING = "com.keyflow.agent.AgentController -> y8:\ncom.keyflow.agenthost.AgentHost -> a9:\n"

    def test_agente_ofuscado_presente_no_dex_passa(self):
        with tempfile.TemporaryDirectory() as tmp:
            mapping = Path(tmp) / "mapping.txt"
            mapping.write_text(self.MAPPING)
            self.assertEqual([], CHECK(mapping, {"Ly8;", "La9;"}))

    def test_classe_do_agente_removida_do_dex_falha(self):
        with tempfile.TemporaryDirectory() as tmp:
            mapping = Path(tmp) / "mapping.txt"
            mapping.write_text(self.MAPPING)
            problems = CHECK(mapping, {"Ly8;"})
            self.assertEqual(1, len(problems))
            self.assertIn("AgentHost -> a9", problems[0])

    def test_agente_ausente_do_mapping_falha(self):
        with tempfile.TemporaryDirectory() as tmp:
            mapping = Path(tmp) / "mapping.txt"
            mapping.write_text("com.keyflow.ui.SetupActivity -> a:\n")
            self.assertEqual(2, len(CHECK(mapping, {"La;"})))

    def test_mapping_ausente_vazio_ou_invalido_falha(self):
        with tempfile.TemporaryDirectory() as tmp:
            mapping = Path(tmp) / "mapping.txt"
            self.assertTrue(CHECK(mapping, {"La;"}))
            for content in ("", "# sem classes\n"):
                mapping.write_text(content)
                self.assertTrue(CHECK(mapping, {"La;"}))

    def test_apk_sem_classes_falha(self):
        with tempfile.TemporaryDirectory() as tmp:
            mapping = Path(tmp) / "mapping.txt"
            mapping.write_text(self.MAPPING)
            self.assertIn("APK sem definições de classes DEX", CHECK(mapping, set()))


class SmokeMappingTest(unittest.TestCase):
    def test_smoke_falha_sem_mapping_antes_de_jni_ou_emulador(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            tools = root / "sdk/build-tools/36.0.0"
            tools.mkdir(parents=True)
            aapt = tools / "aapt2"
            aapt.write_text("#!/bin/sh\nexit 0\n")
            aapt.chmod(0o755)
            apk = root / "release.apk"
            apk.touch()
            env = dict(os.environ, ANDROID_HOME=str(root / "sdk"), ANDROID_SERIAL="emulator-5554",
                       KEYFLOW_SMOKE_APK=str(apk), KEYFLOW_SMOKE_MAPPING=str(root / "ausente.txt"),
                       KEYFLOW_SMOKE_PHASE="1", SMOKE_EVIDENCE_DIR=str(root / "evidence"))
            result = subprocess.run(["bash", str(Path(__file__).resolve().parents[1] / "smoke-release.sh")],
                                    env=env, capture_output=True, text=True)
            self.assertEqual(1, result.returncode, result.stderr)
            self.assertIn("mapping R8 obrigatório", result.stderr)
            self.assertNotIn("ignorada", result.stdout)

    def test_smoke_nao_tenta_assinar_automaticamente(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            scripts = root / "scripts"
            scripts.mkdir()
            source = Path(__file__).resolve().parents[1] / "smoke-release.sh"
            smoke = scripts / source.name
            smoke.write_text(source.read_text())
            tools = root / "sdk/build-tools/36.0.0"
            tools.mkdir(parents=True)
            binaries = root / "bin"
            binaries.mkdir()
            for file, content in (
                (tools / "aapt2", "#!/bin/sh\nexit 0\n"),
                (binaries / "gradle", '#!/bin/sh\nprintf "%s\\n" "$*" >> "$TEST_GRADLE_LOG"\n'),
                # Só substitui a trava neste teste com Gradle falso; não há build nem emulador.
                (binaries / "flock", '#!/bin/sh\nshift\nexec "$@"\n'),
            ):
                file.write_text(content)
                file.chmod(0o755)
            calls = root / "gradle.log"
            env = dict(os.environ, ANDROID_HOME=str(root / "sdk"), ANDROID_SERIAL="emulator-5554",
                       GRADLE_BIN=str(binaries / "gradle"), TEST_GRADLE_LOG=str(calls),
                       KEYFLOW_SMOKE_PHASE="1", SMOKE_EVIDENCE_DIR=str(root / "evidence"),
                       PATH=str(binaries) + os.pathsep + os.environ["PATH"])
            env.pop("KEYFLOW_SMOKE_APK", None)
            result = subprocess.run(["bash", str(smoke)], env=env, capture_output=True, text=True)
            self.assertEqual(2, result.returncode, result.stderr)
            self.assertEqual(["--no-daemon -p " + str(root) + " :app:assembleRelease"], calls.read_text().splitlines())
            self.assertNotIn("keyflowSmokeSigning", calls.read_text())


class SmokeKeyboardTest(unittest.TestCase):
    def run_keyboard(self, missing, absent_dumps=0):
        source = (Path(__file__).resolve().parents[1] / "smoke-release.sh").read_text()
        snippet = source[source.index("# Só toca teclas"):source.index("# Ditado com o modelo local")]
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            xml = root / "window.xml"
            xml.write_text("<displays/>")
            calls = root / "calls"
            env = dict(os.environ, TEST_XML=str(xml), TEST_EVIDENCE=str(root),
                       TEST_CALLS=str(calls), TEST_MISSING="1" if missing else "0",
                       TEST_COUNT=str(root / "count"), TEST_ABSENT=str(absent_dumps))
            preamble = '''
window_xml=$TEST_XML
evidence=$TEST_EVIDENCE
dump_window() { echo dump >> "$TEST_CALLS"; }
find_bounds() {
    if [[ "$1" == h && "$TEST_MISSING" == 1 ]]; then return; fi
    if [[ "$1" == h ]]; then
        local seen=0
        [[ -f "$TEST_COUNT" ]] && seen=$(<"$TEST_COUNT")
        echo $((seen + 1)) > "$TEST_COUNT"
        if (( seen < TEST_ABSENT )); then return; fi
    fi
    echo "[600,1833][720,1980]"
}
tap_bounds() { echo tap >> "$TEST_CALLS"; }
shot() { :; }
adbx() { :; }
sleep() { :; }
'''
            result = subprocess.run(["bash", "-c", preamble + snippet], env=env, capture_output=True, text=True)
            return result, calls.read_text().splitlines()

    def test_tecla_ausente_falha_sem_tocar_coordenadas(self):
        result, calls = self.run_keyboard(True)
        self.assertEqual(1, result.returncode)
        self.assertIn("tecla h ausente", result.stderr)
        self.assertEqual(["dump"] * 10, calls)  # repete a captura e nunca toca coordenadas às cegas

    def test_janela_do_ime_ausente_em_uma_captura_isolada(self):
        result, calls = self.run_keyboard(False, absent_dumps=2)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(["dump", "dump", "dump", "tap", "dump", "tap", "dump"], calls)

    def test_consulta_arvore_antes_de_cada_tecla(self):
        result, calls = self.run_keyboard(False)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(["dump", "tap", "dump", "tap", "dump"], calls)


if __name__ == "__main__":
    unittest.main()
