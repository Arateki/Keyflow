package com.keyflow.agent.tools

/** Uma aba do app que o agente pode abrir: [id] é o que o modelo escreve; [label] é o nome na interface (no idioma dela). */
class AppTab(val id: String, val label: String)

/**
 * O que `app_status` diz do app (APP-01): só leitura, nada secreto — a versão, os modos ligados, as permissões concedidas, as skins em
 * uso e a origem de raciocínio. Credencial nenhuma, nem a informação de qual está guardada.
 */
class AppStatus(
    val versionName: String,
    val versionCode: Int,
    val overlayModeOn: Boolean,
    val keyboardModeOn: Boolean,
    val microphoneGranted: Boolean,
    val overlayGranted: Boolean,
    val keyboardEnabled: Boolean,
    val keyboardActive: Boolean,
    val notificationsGranted: Boolean,
    val keyboardSkin: String,
    val orbSkin: String,
    /** O nome da origem (`LOCAL`, `ANTHROPIC`, `OPENAI`, `GOOGLE`, `CUSTOM`). */
    val reasoningSource: String,
    /** O modelo da origem ativa, ou `null` se nenhum foi escolhido. */
    val reasoningModel: String?,
)

/**
 * A porta das ferramentas de app com o resto do KeyFlow (APP-01..03): o estado, os ajustes da lista de permitidos e a navegação. O
 * núcleo do agente continua sem `android.*` (o `AgentIsolationTest`); o adaptador do aparelho é o `AndroidAppControl`
 * (`com.keyflow.agenthost`) e os testes de unidade usam um falso.
 *
 * Só chaves da [AppSettingsAllowlist] atravessam esta porta, e o adaptador recusa qualquer outra (defesa em profundidade, TLS-03).
 */
interface AppControl {
    /** As abas que `open_tab` aceita. */
    val tabs: List<AppTab>

    suspend fun status(): AppStatus

    /** O valor atual de cada uma das [keys] (da lista de permitidos). Uma chave que o app não souber ler fica de fora. */
    suspend fun readSettings(keys: Collection<String>): Map<String, SettingValue>

    /** Grava [changes] (chave → valor, já conferido com o tipo e a faixa), de forma atômica e SÓ nessas chaves. */
    suspend fun writeSettings(changes: Map<String, SettingValue>)

    /** Pede à tela que abra a aba [id] (na thread principal). `false` = não havia tela para atender (o app está em segundo plano). */
    fun openTab(id: String): Boolean
}
