#!/usr/bin/env python3
"""Confere as cópias implantadas e executa a limpeza em logs descartáveis."""
import gzip
import os
import re
import shutil
import subprocess
import tempfile
import time
import unittest
from pathlib import Path

BASE = Path(__file__).resolve().parent
CONFIGS = ["journald.conf", "nginx.logrotate", "postgresql.logrotate",
           "keyflow-retencao-logs.service", "keyflow-retencao-logs.timer"]


class RetencaoTest(unittest.TestCase):
    def test_provisionamento_implanta_os_arquivos_versionados(self):
        script = (BASE / "provisionar.sh").read_text()
        for index, name in enumerate(CONFIGS):
            marker = f"KEYFLOW_RETENCAO_{index}"
            embedded = script.split(f"<<'{marker}'\n", 1)[1].split(f"\n{marker}", 1)[0] + "\n"
            self.assertEqual((BASE / "logs" / name).read_text(), embedded, name)
        subprocess.run(["bash", "-n", str(BASE / "provisionar.sh")], check=True)

    def test_margem_do_arquivo_ativo_e_do_timer_cabe_em_trinta_dias(self):
        journal = (BASE / "logs/journald.conf").read_text()
        self.assertIn("MaxRetentionSec=29day", journal)
        self.assertIn("MaxFileSec=1h", journal)
        self.assertIn("OnCalendar=hourly", (BASE / "logs/keyflow-retencao-logs.timer").read_text())
        self.assertLess(29 * 86400 + 2 * 3600 + 60, 30 * 86400)
        for name in CONFIGS[1:3]:
            config = (BASE / "logs" / name).read_text()
            self.assertRegex(config, r"\bhourly\b")
            self.assertRegex(config, r"\brotate 696\b")
            self.assertRegex(config, r"\bmaxage 29\b")
            self.assertRegex(config, r"\bifempty\b")

    def test_vhost_implantado_passa_no_nginx_sem_log_duplicado_do_debian(self):
        script = (BASE / "provisionar.sh").read_text()
        match = re.search(r'cat > "\$\{site_avail\}" <<EONGINX\n(.*?)\nEONGINX', script, re.S)
        self.assertIsNotNone(match)
        vhost = match.group(1)
        self.assertEqual(vhost.count("    access_log /var/log/keyflow/nginx-access.log keyflow_api;"), 3)
        self.assertNotIn("$request ", vhost)
        self.assertNotIn("$request_uri", vhost)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            cert, key = root / "cert.pem", root / "key.pem"
            subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes",
                            "-keyout", str(key), "-out", str(cert), "-days", "1",
                            "-subj", "/CN=teste.invalid"], check=True, capture_output=True)
            values = {"cert_pem": str(cert), "cert_key": str(key), "DOMAIN_CONF": "dominio.conf",
                      "KEYFLOW_DOMAIN": "api.teste.invalid", "KEYFLOW_STAGING_DOMAIN": "staging.teste.invalid"}
            for name, value in values.items():
                vhost = vhost.replace("${" + name + "}", value)
            vhost = vhost.replace("\\$", "$").replace("/var/log/keyflow/", str(root) + "/")
            config = root / "nginx.conf"
            config.write_text("error_log stderr;\nevents {}\nhttp {\n" + vhost + "\n}\n")
            subprocess.run(["nginx", "-t", "-p", directory + "/", "-c", str(config)], check=True, capture_output=True)

    def test_logrotate_remove_logs_antigos_mesmo_com_arquivo_ativo_vazio(self):
        self.assertIsNotNone(shutil.which("logrotate"), "instale logrotate para executar este teste")
        for name in CONFIGS[1:3]:
            with self.subTest(config=name), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                root.chmod(0o755)
                active = root / "api.log"
                active.touch()
                recent = root / "api.log.2.gz"
                old = root / "api.log.100.gz"
                unrelated = root / "outro.log.100.gz"
                for path, days in [(recent, 5), (old, 31), (unrelated, 31)]:
                    with gzip.open(path, "wb") as file:
                        file.write(b"registro descartavel\n")
                    timestamp = time.time() - days * 86400
                    os.utime(path, (timestamp, timestamp))
                config = (BASE / "logs" / name).read_text()
                config = re.sub(r"^/var/log/.*? \{", str(active) + " {", config, flags=re.M)
                config = re.sub(r"    postrotate\n.*?    endscript\n", "", config, flags=re.S)
                config = config.replace("create 0640 www-data adm", "create 0640 root root")
                rule = root / "rotate.conf"
                rule.write_text(config)
                subprocess.run(["logrotate", "--force", "--state", str(root / "state"), str(rule)], check=True)
                self.assertFalse(old.exists(), name)
                self.assertTrue(unrelated.exists(), name)
                self.assertTrue(any(root.glob("api.log.[2-9].gz")), "o arquivo recente deve permanecer")
                self.assertTrue(active.exists())


if __name__ == "__main__":
    unittest.main()
