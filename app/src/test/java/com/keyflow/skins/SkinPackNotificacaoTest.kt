package com.keyflow.skins

import com.google.common.truth.Truth.assertThat
import com.keyflow.skins.SkinPackTestFixtures.MANIFEST
import com.keyflow.skins.SkinPackTestFixtures.fullOrb
import com.keyflow.skins.SkinPackTestFixtures.keyboardOnly
import com.keyflow.skins.SkinPackTestFixtures.notificationOnly
import com.keyflow.skins.SkinPackTestFixtures.read
import com.keyflow.skins.SkinPackTestFixtures.svg
import com.keyflow.skins.SkinPackTestFixtures.zip
import org.junit.Test

/** NTF-T8: a seção `notification` do pacote de skin (NTF-31..35) — vocabulário, manifest, leitor e compatibilidade com a versão antiga. */
class SkinPackNotificacaoTest {

    private fun ok(bytes: ByteArray): SkinPackReadResult.Ok {
        val r = read(bytes)
        assertThat(r.report.errors).isEmpty()
        return r as SkinPackReadResult.Ok
    }

    private fun failed(bytes: ByteArray): SkinPackReadResult.Failed = read(bytes) as SkinPackReadResult.Failed

    @Test
    fun `os nomes notification_estado viram NotificationFace`() {
        for (st in OrbPackState.entries) {
            assertThat(SkinPackNames.classify("notification_${st.key}.svg")).isEqualTo(PackFileKind.NotificationFace(st))
            assertThat(SkinPackNames.notificationFileName(st)).isEqualTo("notification_${st.key}.svg")
        }
        assertThat(SkinPackNames.classify("notification_error.svg")).isInstanceOf(PackFileKind.Unknown::class.java)
        assertThat(SkinPackNames.classify("Notification_idle.svg")).isEqualTo(PackFileKind.Unknown(lowercaseWouldMatch = true))
        assertThat(SkinPackNames.classify("notification_idle_01.svg")).isInstanceOf(PackFileKind.Unknown::class.java)
    }

    @Test
    fun `um pacote so de notificacao instala e nao tem orbe nem teclado`() {
        val r = ok(zip(notificationOnly()))
        assertThat(r.content.hasNotification).isTrue()
        assertThat(r.content.hasOrb).isFalse()
        assertThat(r.content.hasKeyboard).isFalse()
        assertThat(r.report.installed).containsExactly("notificação: 1 estado")
        assertThat(r.report.warnings).isEmpty()
    }

    @Test
    fun `as combinacoes orbe mais teclado mais notificacao`() {
        val orbKb = ok(zip(fullOrb() + keyboardOnly().drop(1)))
        assertThat(orbKb.content.hasNotification).isFalse()
        val orbNot = ok(zip(fullOrb() + notificationOnly().drop(1)))
        assertThat(orbNot.content.hasOrb && orbNot.content.hasNotification && !orbNot.content.hasKeyboard).isTrue()
        val todas = ok(zip(fullOrb() + keyboardOnly().drop(1) + notificationOnly(estados = OrbPackState.entries.map { it.key }).drop(1)))
        assertThat(todas.content.hasOrb && todas.content.hasKeyboard && todas.content.hasNotification).isTrue()
        assertThat(todas.report.installed).contains("notificação: 4 estados")
    }

    @Test
    fun `o estado ausente usa idle e o idle e obrigatorio`() {
        val so = ok(zip(notificationOnly(estados = listOf("idle", "paused"))))
        assertThat(so.content.notificationFaces.keys).containsExactly(OrbPackState.IDLE, OrbPackState.PAUSED)
        val semIdle = failed(zip(notificationOnly(estados = listOf("listening"))))
        assertThat(semIdle.report.errors.single()).contains("notification_idle.svg")
    }

    @Test
    fun `sem orbe, teclado nem notificacao e erro`() {
        val r = failed(zip(listOf("manifest.json" to MANIFEST.toByteArray(), "x.txt" to byteArrayOf(1))))
        assertThat(r.report.errors.single()).contains("não tem orbe, teclado nem notificação")
    }

    @Test
    fun `background translucido e ink mal escrito sao erro com o caminho do campo`() {
        val translucido = """{"schemaVersion":1,"name":"T","notification":{"background":"#802B2720"}}"""
        assertThat(failed(zip(notificationOnly(translucido))).report.errors.single()).contains("notification.background")
        val ink = """{"schemaVersion":1,"name":"T","notification":{"ink":"branco"}}"""
        assertThat(failed(zip(notificationOnly(ink))).report.errors.single()).contains("notification.ink")
        val tipo = """{"schemaVersion":1,"name":"T","notification":"x"}"""
        assertThat(failed(zip(notificationOnly(tipo))).report.errors).isNotEmpty()
    }

    @Test
    fun `o manifest valido da notificacao vira o dado`() {
        val m = """{"schemaVersion":1,"name":"T","notification":{"ink":"#112233","background":"#2B2720"}}"""
        val r = ok(zip(notificationOnly(m)))
        assertThat(r.content.manifest.notification.ink).isEqualTo(0xFF112233.toInt())
        assertThat(r.content.manifest.notification.background).isEqualTo(0xFF2B2720.toInt())
        assertThat(ok(zip(notificationOnly())).content.manifest.notification).isEqualTo(NotificationManifest())
    }

    @Test
    fun `a secao sem arquivos e aviso`() {
        val m = """{"schemaVersion":1,"name":"T","notification":{"ink":"#FFFFFF"}}"""
        val r = ok(zip(fullOrb(m)))
        assertThat(r.report.warnings.single()).contains("a seção notification foi ignorada")
    }

    /** Simula o parser de antes da seção: o mesmo pacote só perde a notificação e importa com os avisos de desconhecido. */
    @Test
    fun `a versao antiga importa o mesmo pacote so com avisos de desconhecido`() {
        val pacote = fullOrb("""{"schemaVersion":1,"name":"T","notification":{"ink":"#FFFFFF"}}""") + notificationOnly().drop(1)
        // o que a versão antiga faria: arquivo fora do vocabulário ignorado com aviso, campo desconhecido ignorado com aviso
        val antigo = pacote.filterNot { it.first.startsWith("notification_") }
        val r = ok(zip(antigo))
        assertThat(r.content.hasOrb).isTrue()
        assertThat(r.report.warnings.single()).contains("notification")
        // e o parser novo, no mesmo manifest, não duplica nem perde nada
        assertThat(SkinPackManifest.parse("""{"schemaVersion":1,"name":"T","futuro":1}""").warnings).hasSize(1)
    }

    @Test
    fun `um arquivo notification de tamanho estourado e recusado como os outros SVGs`() {
        val grande = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 1 1\">" + " ".repeat(SkinPackReader.MAX_SVG_BYTES + 10) + "</svg>"
        val r = failed(zip(listOf("manifest.json" to MANIFEST.toByteArray(), "notification_idle.svg" to grande.toByteArray(), "orb_idle_01.svg" to svg().toByteArray())))
        assertThat(r.report.errors.joinToString()).contains("notification_idle.svg")
    }
}
