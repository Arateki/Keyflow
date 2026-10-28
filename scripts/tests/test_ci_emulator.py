"""Exercita o preparo do emulador com adb falso, sem aparelho nem credenciais."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class EmulatorProfileTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.work = Path(self.temp.name)
        tools = self.work / "sdk/platform-tools"
        tools.mkdir(parents=True)
        adb = tools / "adb"
        adb.write_text('''#!/usr/bin/env bash
printf '%s\\n' "$*" >> commands
case "$*" in
  'shell cmd overlay list --user 0')
    if [ -f enabled ] && [ "${MISSING_OVERLAY:-}" != 1 ]; then
      echo '[x] com.android.internal.emulation.pixel_6'
      echo '[x] com.android.systemui.emulation.pixel_6'
    else
      echo '[ ] com.android.internal.emulation.pixel_6'
      echo '[ ] com.android.systemui.emulation.pixel_6'
    fi ;;
  'shell cmd overlay enable --user 0 '*)
    [ "${ADB_FAIL:-}" != 1 ] || exit 17
    touch enabled ;;
  'shell cmd overlay lookup '*) echo '128.0px' ;;
  'shell dumpsys window displays') echo 'Display: Pixel 6' ;;
  *) exit 99 ;;
esac
''')
        adb.chmod(0o755)
        self.env = {**os.environ, "ANDROID_HOME": str(self.work / "sdk")}

    def run_profile(self, **env):
        return subprocess.run(
            [str(ROOT / "scripts/ci-emulator-profile.sh")], cwd=self.work,
            env={**self.env, **env}, text=True, capture_output=True,
        )

    def test_generic_profile_is_configured_and_evidence_keeps_both_states(self):
        result = self.run_profile()
        self.assertEqual(0, result.returncode, result.stderr)
        evidence = self.work / "app/build/ci-evidence"
        self.assertIn('[ ] com.android.internal.emulation.pixel_6', (evidence / 'overlays-before.txt').read_text())
        self.assertIn('[x] com.android.internal.emulation.pixel_6', (evidence / 'overlays-after.txt').read_text())
        self.assertIn('128.0px', (evidence / 'status-bar-height.txt').read_text())
        self.assertTrue((evidence / 'display-before-gate.txt').exists())
        self.assertFalse((evidence / 'gate-started').exists())

    def test_overlay_that_stays_disabled_blocks_the_gate(self):
        result = self.run_profile(MISSING_OVERLAY='1')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('Overlay do Pixel 6 não foi ativado', result.stdout)
        self.assertNotIn('lookup', (self.work / 'commands').read_text())

    def test_adb_failure_is_not_swallowed(self):
        self.assertEqual(17, self.run_profile(ADB_FAIL='1').returncode)

    def test_cleanup_refuses_to_retry_after_tests_started(self):
        marker = self.work / 'app/build/ci-evidence/gate-started'
        marker.parent.mkdir(parents=True)
        marker.touch()
        result = subprocess.run(
            [str(ROOT / 'scripts/ci-reset-emulator.sh')], cwd=self.work,
            env={**self.env, 'GITHUB_ACTIONS': 'true'}, text=True, capture_output=True,
        )
        self.assertEqual(2, result.returncode)
        self.assertIn('gate já iniciado', result.stderr)
        self.assertFalse((self.work / 'commands').exists())

    def test_cleanup_refuses_to_run_outside_actions(self):
        result = subprocess.run(
            [str(ROOT / 'scripts/ci-reset-emulator.sh')], cwd=self.work,
            env={**self.env, 'GITHUB_ACTIONS': ''}, text=True, capture_output=True,
        )
        self.assertEqual(2, result.returncode)
        self.assertFalse((self.work / 'commands').exists())


if __name__ == '__main__':
    unittest.main()
