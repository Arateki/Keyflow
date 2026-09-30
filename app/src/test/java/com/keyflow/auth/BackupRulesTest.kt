package com.keyflow.auth

import com.google.common.truth.Truth.assertThat
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Test
import org.w3c.dom.Element

/**
 * Review Focus 5 / AUTH-02: as regras de backup excluem por NOME DE ARQUIVO. Um cofre cifrado que ficasse de fora delas
 * seria restaurado sem a chave do Keystore (que não é exportável) e reabriria o crash em loop do A32 (Q8, 2026-09-07).
 * Todo arquivo de prefs cifradas do app tem de estar nesta lista E nas três listas de exclusão.
 */
class BackupRulesTest {

    /** Cada cofre cifrado do app. Um novo (ex.: outro provedor) entra aqui — e o teste falha até as regras o excluírem. */
    private val encryptedPrefs = listOf("keyflow_secure", SecureCredentialStore.PREFS_NAME)

    private fun excludes(file: String, parent: String): Set<String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/xml/$file"))
        val section = doc.getElementsByTagName(parent).item(0) as Element
        val nodes = section.getElementsByTagName("exclude")
        return (0 until nodes.length).map { nodes.item(it) as Element }
            .filter { it.getAttribute("domain") == "sharedpref" }
            .map { it.getAttribute("path") }
            .toSet()
    }

    @Test
    fun `every encrypted prefs file is excluded from the full backup`() {
        assertThat(excludes("backup_rules.xml", "full-backup-content"))
            .containsAtLeastElementsIn(encryptedPrefs.map { "$it.xml" })
    }

    @Test
    fun `every encrypted prefs file is excluded from the cloud backup`() {
        assertThat(excludes("data_extraction_rules.xml", "cloud-backup"))
            .containsAtLeastElementsIn(encryptedPrefs.map { "$it.xml" })
    }

    @Test
    fun `every encrypted prefs file is excluded from the device to device transfer`() {
        assertThat(excludes("data_extraction_rules.xml", "device-transfer"))
            .containsAtLeastElementsIn(encryptedPrefs.map { "$it.xml" })
    }

    @Test
    fun `the auth store uses its own prefs file and its own master key alias`() {
        // Alias próprio: a recuperação do SecurePrefs apaga o keyset E a chave — dividir a chave com o `keyflow_secure`
        // faria a recuperação de um invalidar o outro.
        assertThat(SecureCredentialStore.PREFS_NAME).isEqualTo("keyflow_auth")
        assertThat(SecureCredentialStore.KEY_ALIAS).isEqualTo("keyflow_auth_master")
        assertThat(SecureCredentialStore.PREFS_NAME).isNotEqualTo("keyflow_secure")
    }
}
