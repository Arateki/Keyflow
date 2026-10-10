package com.keyflow.ui.config

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** As abas da configuração e os grupos do menu lateral (Yan, 2026-09-29). */
class ConfigTabTest {

    @Test
    fun `keyflow comes first, then interfaces, then capabilities, then the docs`() {
        assertThat(TabGroup.entries).containsExactly(TabGroup.PRINCIPAL, TabGroup.INTERFACES, TabGroup.CAPACIDADES, TabGroup.OUTROS).inOrder()
        assertThat(ConfigTab.entries.map { it.group }.distinct())
            .containsExactly(TabGroup.PRINCIPAL, TabGroup.INTERFACES, TabGroup.CAPACIDADES, TabGroup.OUTROS).inOrder()
    }

    @Test
    fun `keyflow is the principal agent tab`() {
        assertThat(ConfigTab.entries.filter { it.group == TabGroup.PRINCIPAL })
            .containsExactly(ConfigTab.KEYFLOW)
    }

    @Test
    fun `keyboard, overlay and notification are the interfaces`() {
        assertThat(ConfigTab.entries.filter { it.group == TabGroup.INTERFACES })
            .containsExactly(ConfigTab.TECLADO, ConfigTab.OVERLAY, ConfigTab.NOTIFICACAO).inOrder()
    }

    @Test
    fun `fluxos, transcription, vision, reasoning, voice, and computador are the capabilities`() {
        // FLUXOS vem em primeiro nas capacidades (central de orquestração); VOZ é a Transcrição; a aba "Voz" (a boca) é a FALA.
        assertThat(ConfigTab.entries.filter { it.group == TabGroup.CAPACIDADES })
            .containsExactly(
                ConfigTab.FLUXOS,
                ConfigTab.VOZ,
                ConfigTab.VISAO,
                ConfigTab.RACIOCINIO,
                ConfigTab.FALA,
                ConfigTab.COMPUTADOR,
            ).inOrder()
    }

    @Test
    fun `the docs are in no group of their own and sit last`() {
        assertThat(ConfigTab.DOCS.group).isEqualTo(TabGroup.OUTROS)
        assertThat(ConfigTab.entries.last()).isEqualTo(ConfigTab.DOCS)
    }

    @Test
    fun `every group has tabs and interfaces and capabilities have titles`() {
        assertThat(ConfigTab.entries.map { it.name }).containsNoDuplicates()
        assertThat(ConfigTab.entries.groupBy { it.group }.keys).containsExactlyElementsIn(TabGroup.entries)
        // Keyflow (topo) e Outros (Docs/Provedores) só têm a linha/espaço; Interfaces e Capacidades têm título.
        assertThat(TabGroup.PRINCIPAL.title).isNull()
        assertThat(TabGroup.INTERFACES.title).isEqualTo("Interfaces")
        assertThat(TabGroup.CAPACIDADES.title).isEqualTo("Capacidades")
        assertThat(TabGroup.OUTROS.title).isNull()
    }
}
