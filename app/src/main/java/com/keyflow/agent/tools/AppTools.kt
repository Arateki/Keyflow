package com.keyflow.agent.tools

import com.keyflow.agent.AgentTool
import com.keyflow.agent.PolicyGated
import com.keyflow.agent.ToolContext
import com.keyflow.agent.ToolDescription
import com.keyflow.agent.ToolFailure
import com.keyflow.agent.ToolOutput
import com.keyflow.agent.ToolRisk
import com.keyflow.agent.TurnScoped
import com.keyflow.agent.UndoCall
import com.keyflow.i18n.tr
import org.json.JSONArray
import org.json.JSONObject

/**
 * As ferramentas de app (APP-01..03): `app_status` (o estado, sem segredos), `settings_get` e `settings_set` (SÓ a lista de permitidos,
 * [AppSettingsAllowlist]) e `open_tab` (navegar). Tudo passa pela [AppControl]; o `settings_set` é `WRITE` — o painel mostra o antes → depois
 * que o APP escreveu, o registro guarda a ação e o "Desfazer" volta ao valor anterior, pelo mesmo caminho.
 *
 * **Nenhum segredo atravessa estas ferramentas**: não há campo secreto na lista, o esquema de `settings_get`/`settings_set` só aceita as chaves
 * dela (uma chave de fora é recusada na validação, antes de qualquer `run`), o valor é lido pelo tipo da linha (um nome de modelo só aceita
 * caracteres de nome de modelo) e o resultado ainda passa pelo filtro de segredos do executor.
 */
class AppTools(private val control: AppControl) {

    val tools: List<AgentTool> = listOf(StatusTool(), GetTool(), SetTool(), OpenTabTool())

    // ── estado ───────────────────────────────────────────────────────────────

    private inner class StatusTool : AgentTool {
        override val name = "app_status"
        override val label: String get() = tr("Ver o estado do app")
        override val risk = ToolRisk.READ
        override val description = "Devolve o estado do KeyFlow: a versão, quais modos (Sobrepor/orbe e Teclado) estão ligados, quais permissões o app tem (só leitura — você não concede nada), as skins do teclado e da orbe em uso e a origem de raciocínio com o modelo escolhido. Nunca traz chaves, tokens ou senhas."
        override val inputSchema = obj()

        @PolicyGated
        override suspend fun run(args: JSONObject, ctx: ToolContext): ToolOutput {
            val s = control.status()
            val json = JSONObject()
                .put("app", JSONObject().put("version", s.versionName).put("versionCode", s.versionCode))
                .put("modes", JSONObject().put("overlay", s.overlayModeOn).put("keyboard", s.keyboardModeOn))
                .put(
                    "permissions",
                    JSONObject()
                        .put("microphone", s.microphoneGranted)
                        .put("overlayDraw", s.overlayGranted)
                        .put("keyboardEnabled", s.keyboardEnabled)
                        .put("keyboardIsActive", s.keyboardActive)
                        .put("notifications", s.notificationsGranted),
                )
                .put("skins", JSONObject().put("keyboard", s.keyboardSkin).put("orb", s.orbSkin))
                .put("reasoning", JSONObject().put("source", s.reasoningSource).put("model", s.reasoningModel ?: JSONObject.NULL))
            return ToolOutput.ok(json.toString(2))
        }
    }

    // ── ler ajustes ──────────────────────────────────────────────────────────

    private inner class GetTool : AgentTool {
        override val name = "settings_get"
        override val label: String get() = tr("Ler ajustes")
        override val risk = ToolRisk.READ
        override val description = "Lê os ajustes do app que você pode ver (uma lista fixa e não secreta: tema, idioma e tamanho do teclado, tamanho da orbe, vibração, janela de rajada, skins em uso e os modelos escolhidos). Cada item traz a chave, o valor atual, a faixa aceita e se dá para mudar com settings_set. `key` (opcional) devolve só um deles; sem `key`, todos."
        override val inputSchema = JSONObject(
            """{"type":"object","properties":{"key":{"type":"string","enum":${keys(AppSettingsAllowlist.all)},"description":"a chave de um ajuste; omita para ler todos"}},"additionalProperties":false}""",
        )

        @PolicyGated
        override suspend fun run(args: JSONObject, ctx: ToolContext): ToolOutput {
            val wanted = if (args.has("key")) listOf(AppSettingsAllowlist[args.getString("key")] ?: return ToolOutput.error("Ajuste desconhecido.")) else AppSettingsAllowlist.all
            val values = control.readSettings(wanted.map { it.key })
            val items = JSONArray()
            for (setting in wanted) {
                val value = values[setting.key]
                items.put(
                    JSONObject()
                        .put("key", setting.key)
                        .put("name", setting.labelPt)
                        .put("value", value?.let(setting::json) ?: JSONObject.NULL)
                        .put("accepts", setting.rangeText())
                        .put("writable", setting.writable)
                        .put("about", setting.description + (setting.readOnlyHint?.let { " $it" } ?: "")),
                )
            }
            return ToolOutput.ok(JSONObject().put("settings", items).toString(2))
        }
    }

    // ── mudar um ajuste ──────────────────────────────────────────────────────

    private inner class SetTool : AgentTool {
        override val name = "settings_set"
        override val label: String get() = tr("Mudar ajuste")
        override val risk = ToolRisk.WRITE
        override val description = "Muda UM ajuste da lista de settings_get, depois da confirmação do usuário (o painel mostra o antes → depois que o app escreveu). `key`: a chave; `value`: o valor novo, SEMPRE como texto (\"true\"/\"false\", \"220\", \"pt\", \"gpt-4o\") dentro da faixa que settings_get informa. " +
            "Só os ajustes marcados como graváveis: as skins mudam por skin_select. Não existe ajuste de chave, token, senha ou endereço — não tente. O desfazer volta ao valor anterior."
        override val inputSchema = JSONObject(
            """{"type":"object","properties":{"key":{"type":"string","enum":${keys(AppSettingsAllowlist.writable)},"description":"a chave do ajuste"},"value":{"type":"string","minLength":1,"maxLength":100,"description":"o valor novo, como texto"}},"required":["key","value"],"additionalProperties":false}""",
        )

        /** O ajuste e o valor já conferidos com o tipo e a faixa; lança [ToolFailure] (o modelo se corrige, o usuário nem é incomodado). */
        private fun checked(args: JSONObject): Pair<AllowedSetting, SettingValue> {
            val setting = AppSettingsAllowlist[args.getString("key")] ?: throw ToolFailure("Ajuste desconhecido. Use settings_get para ver os ajustes.")
            if (!setting.writable) throw ToolFailure("«${setting.key}» só pode ser lido aqui. ${setting.readOnlyHint.orEmpty()}".trim())
            return setting to setting.parse(args.getString("value"))
        }

        private suspend fun current(setting: AllowedSetting): SettingValue =
            control.readSettings(listOf(setting.key))[setting.key] ?: throw ToolFailure("Não consegui ler o valor atual de «${setting.key}»; nada foi mudado.")

        override suspend fun describe(args: JSONObject, ctx: ToolContext): ToolDescription {
            val (setting, value) = checked(args)
            val before = current(setting)
            if (before == value) throw ToolFailure("«${setting.key}» já está em ${setting.argText(value)}; nada a mudar.")
            return ToolDescription(tr("Mudar «%s»: %s → %s", setting.label, setting.show(before), setting.show(value)))
        }

        @PolicyGated
        override suspend fun run(args: JSONObject, ctx: ToolContext): ToolOutput {
            val (setting, value) = checked(args)
            val before = current(setting)
            if (before == value) return ToolOutput.error("«${setting.key}» já está em ${setting.argText(value)}; nada foi mudado.")
            control.writeSettings(mapOf(setting.key to value))
            val after = control.readSettings(listOf(setting.key))[setting.key]
            if (after != value) return ToolOutput.error("Gravei «${setting.key}», mas o valor lido depois não confere (${after?.let(setting::argText) ?: "ilegível"}). Confira com settings_get.")
            val undo = UndoCall("settings_set", JSONObject().put("key", setting.key).put("value", setting.argText(before)).toString())
            return ToolOutput.ok("«${setting.labelPt}» (${setting.key}) mudou de ${setting.argText(before)} para ${setting.argText(value)}.", undo = undo)
        }
    }

    // ── abrir uma aba ────────────────────────────────────────────────────────

    /**
     * A aba só abre quando a RESPOSTA do agente termina: navegar no meio esconderia o chat de quem ainda precisa responder ao painel de
     * confirmação da próxima ferramenta ("abra a aba Teclado e deixe o teclado maior" pediria a permissão numa tela que o usuário não vê). Cancelar
     * ou limpar a conversa descarta o pedido.
     */
    private inner class OpenTabTool : AgentTool, TurnScoped {
        override val name = "open_tab"
        override val label: String get() = tr("Abrir uma aba")
        override val risk = ToolRisk.READ
        override val description: String
            get() = "Abre uma aba do app para o usuário olhar — não muda nenhum dado. A aba abre quando você TERMINAR esta resposta (não no meio), então avise o usuário e feche a resposta. Abas: " +
                control.tabs.joinToString("; ") { "${it.id} (${it.label})" } + "."
        override val inputSchema = JSONObject(
            """{"type":"object","properties":{"tab":{"type":"string","enum":${JSONArray(control.tabs.map { it.id })},"description":"o id da aba"}},"required":["tab"],"additionalProperties":false}""",
        )

        @Volatile
        private var pending: String? = null

        override suspend fun describe(args: JSONObject, ctx: ToolContext): ToolDescription {
            val tab = tabOf(args)
            return ToolDescription(tr("Abrir a aba «%s» quando o agente terminar de responder.", tab.label))
        }

        private fun tabOf(args: JSONObject): AppTab {
            val id = args.getString("tab")
            return control.tabs.firstOrNull { it.id == id } ?: throw ToolFailure("Aba desconhecida. Abas: ${control.tabs.joinToString(", ") { it.id }}.")
        }

        @PolicyGated
        override suspend fun run(args: JSONObject, ctx: ToolContext): ToolOutput {
            val tab = tabOf(args)
            pending = tab.id
            return ToolOutput.ok("A aba «${tab.label}» (${tab.id}) vai abrir quando você terminar esta resposta. Diga isso ao usuário e encerre; não chame mais ferramentas só para esperar.")
        }

        override fun turnEnded() {
            val id = pending ?: return
            pending = null
            control.openTab(id)
        }

        override fun turnCancelled() {
            pending = null
        }
    }

    private companion object {
        fun obj(): JSONObject = JSONObject("""{"type":"object","properties":{},"additionalProperties":false}""")

        fun keys(settings: List<AllowedSetting>): JSONArray = JSONArray(settings.map { it.key })
    }
}
