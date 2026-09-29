package com.keyflow.ui.config

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** As abas da configuração e os grupos do menu lateral (Yan, 2026-09-29). */
class ConfigTabTest {

    @Test
    fun `interfaces come first, then capabilities, then the docs`() {
        assertThat(TabGroup.entries).containsExactly(TabGroup.INTERFACES, TabGroup.CAPACIDADES, TabGroup.OUTROS).inOrder()
        assertThat(ConfigTab.entries.map { it.group }.distinct())
            .containsExactly(TabGroup.INTERFACES, TabGroup.CAPACIDADES, TabGroup.OUTROS).inOrder()
    }

    @Test
    fun `keyboard, overlay and notification are the interfaces`() {
        assertThat(ConfigTab.entries.filter { it.group == TabGroup.INTERFACES })
            .containsExactly(ConfigTab.TECLADO, ConfigTab.OVERLAY, ConfigTab.NOTIFICACAO).inOrder()
    }

    @Test
    fun `transcription, vision, reasoning and voice are the capabilities`() {
        // VOZ é a Transcrição; a aba "Voz" (a boca) é a FALA.
        assertThat(ConfigTab.entries.filter { it.group == TabGroup.CAPACIDADES })
            .containsExactly(ConfigTab.VOZ, ConfigTab.VISAO, ConfigTab.RACIOCINIO, ConfigTab.FALA).inOrder()
    }

    @Test
    fun `the docs are in no group of their own and sit last`() {
        assertThat(ConfigTab.DOCS.group).isEqualTo(TabGroup.OUTROS)
        assertThat(ConfigTab.entries.last()).isEqualTo(ConfigTab.DOCS)
    }

    @Test
    fun `every group has tabs and every group but the last has a title`() {
        assertThat(ConfigTab.entries.map { it.name }).containsNoDuplicates()
        assertThat(ConfigTab.entries.groupBy { it.group }.keys).containsExactlyElementsIn(TabGroup.entries)
        // Interfaces e Capacidades têm título (que só aparece com o menu aberto); o grupo da Docs só tem a linha.
        assertThat(TabGroup.INTERFACES.title).isEqualTo("Interfaces")
        assertThat(TabGroup.CAPACIDADES.title).isEqualTo("Capacidades")
        assertThat(TabGroup.OUTROS.title).isNull()
    }
}
