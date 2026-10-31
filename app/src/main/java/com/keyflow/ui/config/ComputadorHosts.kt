package com.keyflow.ui.config

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keyflow.computador.AutenticacaoHost
import com.keyflow.computador.ChaveDoServidor
import com.keyflow.computador.Host
import com.keyflow.computador.ErroHost
import com.keyflow.computador.chaves.ChaveSsh
import com.keyflow.computador.sessao.EstadoSessao
import com.keyflow.computador.sessao.SessaoResumo
import com.keyflow.computador.ssh.ResultadoTeste
import com.keyflow.i18n.tr
import com.keyflow.ui.icons.KfIcons
import com.keyflow.ui.theme.LocalKeyFlowPalette
import com.keyflow.ui.theme.SpaceGrotesk
import com.keyflow.ui.theme.SpaceMono

const val COMPUTADOR_NOVO_HOST_TAG = "computador-novo-host"
const val COMPUTADOR_HOSTS_VAZIO_TAG = "computador-hosts-vazio"
fun computadorHostCardTag(id: String) = "computador-host-$id"

/** Tudo que o painel de hosts faz fora da tela (gravar, testar, excluir…). O app liga ao repositório; os testes trocam por fakes. */
interface AcoesDeHost {
    /** Valida e grava [host] (novo ou editado), com a [senha] digitada se houver. Devolve os erros por campo ou a lista vazia. */
    suspend fun salvar(host: Host, senha: String?): List<ErroHost>

    /** Consulta só a existência do segredo, sem entregá-lo à interface. */
    suspend fun temSenhaGuardada(host: Host): Boolean = false

    /** CMP-07: fecha as sessões do host, apaga `known_hosts`, a senha e o host. A chave fica. */
    suspend fun excluir(host: Host)

    /** CMP-08: o teste real (sem abrir o shell). [senha] é a digitada na tela. */
    suspend fun testar(host: Host, senha: String?): ResultadoTeste

    /** O que o `known_hosts` já sabe deste `endereço:porta`. */
    fun impressoesConhecidas(host: Host): List<ChaveDoServidor>
    fun esquecerServidor(host: Host)

    /** CMP-09: o agente deixou de poder usar o host (fecha as sessões do agente nele e desfaz compartilhamento). */
    suspend fun desligarAgente(host: Host)

    /** O id de um host novo. */
    fun novoId(existentes: Collection<String>): String
}

private fun Host.textoDeAutenticacao(chaves: List<ChaveSsh>): String = when (val a = autenticacao) {
    is AutenticacaoHost.Chave -> tr("Chave") + (chaves.firstOrNull { it.id == a.keyId }?.let { " · ${it.nome}" } ?: "")
    AutenticacaoHost.Senha -> tr("Senha")
    AutenticacaoHost.Perguntar -> tr("Pergunta sempre")
}

private fun EstadoSessao?.texto(): String = when (this) {
    null -> "○ " + tr("Desconectado")
    EstadoSessao.Aberta -> "● " + tr("Conectado")
    is EstadoSessao.Reconectando -> "⟳ " + tr("Reconectando…")
    EstadoSessao.Conectando, EstadoSessao.AguardandoServidor, EstadoSessao.Autenticando -> "⟳ " + tr("Conectando…")
    EstadoSessao.PrecisaDoUsuario -> "⚠ " + tr("Toque para reconectar")
    is EstadoSessao.Encerrada, is EstadoSessao.Falhou -> "○ " + tr("Desconectado")
}

/** A lista de servidores (CMP-59, design §11.1): cartões, "+ Novo host" e o modal. */
@Composable
internal fun ComputadorHosts(hosts: List<Host>, chaves: List<ChaveSsh>, sessoes: List<SessaoResumo>, acoes: AcoesDeHost) {
    val palette = LocalKeyFlowPalette.current
    var editando by remember { mutableStateOf<Host?>(null) }
    var criando by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (hosts.isEmpty()) InfoNote(tr("Nenhum servidor ainda. Cadastre o primeiro para abrir um terminal."), COMPUTADOR_HOSTS_VAZIO_TAG)
        hosts.forEach { h ->
            val estado = sessoes.firstOrNull { it.hostId == h.id && !it.estado.terminal }?.estado
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(palette.card).border(1.dp, palette.line2, RoundedCornerShape(16.dp))
                    .clickable(role = Role.Button) { editando = h }.heightIn(min = 48.dp).padding(14.dp).testTag(computadorHostCardTag(h.id)),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(h.nome, color = palette.fg, fontFamily = SpaceGrotesk, fontWeight = FontWeight.Medium, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text(estado.texto(), color = if (estado == EstadoSessao.Aberta) palette.teal else palette.muted, fontFamily = SpaceGrotesk, fontSize = 11.sp)
                }
                Text("${h.usuario}@${h.endereco}:${h.porta}", color = palette.muted, fontFamily = SpaceMono, fontSize = 11.sp)
                Text(
                    h.textoDeAutenticacao(chaves) + " · " + (if (h.agentePodeUsar) tr("Agente: permitido") else tr("Agente: não")),
                    color = if (h.agentePodeUsar) palette.terracotta else palette.faint, fontFamily = SpaceGrotesk, fontSize = 11.sp,
                )
            }
        }
        SecondaryButton(tr("+ Novo host"), COMPUTADOR_NOVO_HOST_TAG, { criando = true }, icon = KfIcons.Plus)
    }
    if (criando) ComputadorHostModal(host = null, hosts = hosts, chaves = chaves, acoes = acoes, onDismiss = { criando = false })
    editando?.let { atual ->
        // O host pode ter mudado por baixo (outro lugar): o modal sempre abre com o que está na lista.
        val corrente = hosts.firstOrNull { it.id == atual.id }
        if (corrente == null) editando = null
        else ComputadorHostModal(host = corrente, hosts = hosts, chaves = chaves, acoes = acoes, onDismiss = { editando = null })
    }
}
