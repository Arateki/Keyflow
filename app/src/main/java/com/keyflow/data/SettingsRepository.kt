package com.keyflow.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.security.crypto.MasterKey
import com.keyflow.auth.Credential
import com.keyflow.auth.CredentialStore
import com.keyflow.auth.CredentialVaultLock
import com.keyflow.auth.ProviderIds
import com.keyflow.auth.SecureCredentialStore
import com.keyflow.core.ComputeBackend
import com.keyflow.core.FalaSource
import com.keyflow.core.OrbShortcutAction
import com.keyflow.core.OverlaySide
import com.keyflow.core.ReasoningSource
import com.keyflow.core.RecordingMode
import com.keyflow.core.ShortcutOverflow
import com.keyflow.core.TranscriptionSource
import com.keyflow.core.VisionSource
import com.keyflow.core.shortcuts.BarsConfig
import com.keyflow.core.shortcuts.ShortcutCodec
import com.keyflow.core.shortcuts.ShortcutGrid
import com.keyflow.data.models.ModelCatalog
import com.keyflow.data.models.ModelKind
import com.keyflow.data.models.ModelProvisioning
import com.keyflow.fluxos.CodecFluxos
import com.keyflow.fluxos.BotoesDeFluxo
import com.keyflow.fluxos.ConfigFluxos
import com.keyflow.fluxos.PadroesFluxo
import com.keyflow.fluxos.PoliticaGatilhos
import com.keyflow.orbe.CodecOrbeTeclado
import com.keyflow.orbe.ConfigOrbeTeclado
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class SettingsRepository(
    context: Context,
    dataStoreFile: File = defaultDataStoreFile(context),
    private val securePrefsName: String = SECURE_PREFS_NAME,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val securePrefsFactory: (Context, String) -> SharedPreferences = { app, name -> SecurePrefs.create(app, name) },
    private val credentialStoreFactory: (Context, String) -> CredentialStore = { app, name -> SecureCredentialStore(app, name) },
) {
    private val appContext = context.applicationContext
    private val dataStore: DataStore<Preferences> = dataStoreFor(dataStoreFile, scope)
    private val legacyPrefs: SharedPreferences by lazy {
        securePrefsFactory(appContext, securePrefsName)
    }
    private val authPrefsName = if (securePrefsName == SECURE_PREFS_NAME) {
        SecureCredentialStore.PREFS_NAME
    } else {
        "${SecureCredentialStore.PREFS_NAME}_$securePrefsName"
    }
    private val credentials: CredentialStore by lazy { credentialStoreFactory(appContext, authPrefsName) }

    /**
     * O cofre cifrado das Settings (`keyflow_secure`), para quem precisa LER todos os valores secretos — o filtro de segredos do agente, que
     * os troca por valor em tudo que vai ao modelo. Só leitura: ninguém grava por aqui (as gravações são as do [saveSettings]). É a mesma
     * instância preguiçosa que o repositório usa, então o nome do cofre fica num lugar só.
     */
    internal fun secretVault(): SharedPreferences = legacyPrefs

    /** Grava uma chave sem colocar o valor no estado de [Settings] nem na thread da UI. */
    suspend fun saveSecret(key: String, value: String) = withContext(Dispatchers.IO) {
        SettingsSecrets.SUSPEND_LOCK.withLock {
            // Mesmo a gravação pontual precisa concluir a migração antes de tocar na origem antiga.
            migrateLegacySecretsLocked()
            val provider = apiKeyProvider(key)
            if (provider != null) {
                if (value.isBlank()) credentials.clearApiKey(provider) else credentials.put(provider, Credential.ApiKey(value))
            } else if (value.isBlank()) {
                credentials.clearSecret(key)
            } else {
                credentials.putSecret(key, value)
            }
        }
    }

    /**
     * Config efetivo consumido pelo transcritor. Resolve pela **origem selecionada** ([Settings.source])
     * via [Settings.effectiveConfigFor] — antes ignorava o source e usava só os campos legados do CUSTOM,
     * o que dava `CONFIG_MISSING` ao escolher OpenAI/Groq. CREDITS legado é normalizado para o padrão antes da leitura.
     */
    /**
     * Leitura leve só do tema, lendo **apenas o DataStore** — não inicializa o EncryptedSharedPreferences
     * (cripto cara). Usada no caminho crítico do IME (`onCreateInputView`) para não travar a thread
     * principal ao criar o teclado, o que atrasava a exibição na troca de IME.
     */
    suspend fun themeDarkOnce(): Boolean = dataStore.data.first()[THEME_DARK] ?: false

    /** Mesma leitura leve de [themeDarkOnce], para o tamanho do teclado (também lido em `onCreateInputView`). */
    suspend fun keyboardScalePercentOnce(): Int = dataStore.data.first()[KEYBOARD_SCALE_PERCENT] ?: 100

    /**
     * Observação ao vivo de tema/tamanho/idioma (pedido do Yan, 2026-07-03: aplicar sem precisar reabrir
     * o teclado) — mesmo padrão de [barsConfigFlow]/[shortcutGridsFlow] (BAR-07).
     */
    fun themeDarkFlow(): Flow<Boolean> = dataStore.data.map { it[THEME_DARK] ?: false }
    fun keyboardScalePercentFlow(): Flow<Int> = dataStore.data.map { it[KEYBOARD_SCALE_PERCENT] ?: 100 }
    fun keyboardLanguageFlow(): Flow<String> = dataStore.data.map { it[KEYBOARD_LANGUAGE] ?: "pt" }
    fun keyboardNumberRowEnabledFlow(): Flow<Boolean> =
        dataStore.data.map { it[KEYBOARD_NUMBER_ROW_ENABLED] ?: false }

    /** Mesma leitura leve de [themeDarkOnce], para a fileira de números (também lida em `onCreateInputView`). */
    suspend fun keyboardNumberRowEnabledOnce(): Boolean =
        dataStore.data.first()[KEYBOARD_NUMBER_ROW_ENABLED] ?: false

    fun keyboardShowWithHardKeyboardFlow(): Flow<Boolean> =
        dataStore.data.map { it[KEYBOARD_SHOW_WITH_HARD_KEYBOARD] ?: true }

    fun keyboardAutoCorrectFlow(): Flow<Boolean> = dataStore.data.map { it[KEYBOARD_AUTO_CORRECT] ?: true }

    fun keyboardSkinFlow(): Flow<String> = dataStore.data.map { it[KEYBOARD_SKIN] ?: "system" }
    fun skinsUnlockedFlow(): Flow<Boolean> = dataStore.data.map { it[SKINS_UNLOCKED] ?: false }

    /** Idioma da interface, leve (só DataStore): o [com.keyflow.App] aplica no processo inteiro. */
    fun appLanguageFlow(): Flow<String> = dataStore.data.map { it[APP_LANGUAGE] ?: "" }

    /** Mesma leitura leve de [themeDarkOnce], para as cores do teclado em `onCreateInputView`. */
    suspend fun keyboardSkinOnce(): String = dataStore.data.first()[KEYBOARD_SKIN] ?: "system"

    suspend fun skinsUnlockedOnce(): Boolean = dataStore.data.first()[SKINS_UNLOCKED] ?: false

    /** Estado técnico compartilhado com o IME e o overlay; não altera a escolha de skin salva. */
    suspend fun saveSkinsUnlocked(unlocked: Boolean) {
        dataStore.edit { it[SKINS_UNLOCKED] = unlocked }
    }

    /**
     * Idioma do teclado ("pt"/"en"/"es"), gerenciado pelo app (não pelos subtypes do sistema — ver
     * [Settings.keyboardLanguage]). Leitura leve mesmo padrão de [themeDarkOnce]; escrita separada
     * (não passa pelo round-trip completo de [saveSettings]) porque o globo troca isto sozinho, sem
     * tocar no resto da config.
     */
    suspend fun keyboardLanguageOnce(): String = dataStore.data.first()[KEYBOARD_LANGUAGE] ?: "pt"

    suspend fun saveKeyboardLanguageOnce(language: String) {
        dataStore.edit { it[KEYBOARD_LANGUAGE] = language }
    }

    suspend fun saveOnboardingDone(done: Boolean) {
        dataStore.edit { it[ONBOARDING_DONE] = done }
    }

    suspend fun saveDisclosureConsent(version: Int, declined: Boolean) {
        dataStore.edit {
            it[DISCLOSURE_ACCEPTED_VERSION] = version
            it[DISCLOSURE_DECLINED] = declined
        }
    }

    suspend fun saveOverlayModeEnabled(enabled: Boolean) {
        dataStore.edit { it[OVERLAY_MODE_ENABLED] = enabled }
    }

    suspend fun saveKeyboardModeEnabled(enabled: Boolean) {
        dataStore.edit { it[KEYBOARD_MODE_ENABLED] = enabled }
    }

    /** Leitura leve da intensidade de vibração (háptica da grade efêmera/alavanca — GRD-03/JOY-02). */
    suspend fun vibrationIntensityOnce(): Int = dataStore.data.first().let { prefs ->
        prefs[VIBRATION_INTENSITY] ?: (if (prefs[HAPTIC_FEEDBACK] == false) 0 else 60)
    }

    // ── Barras personalizáveis + teclados de atalhos (Trabalho 3 / SB-T5) ────
    // Strings JSON no DataStore (nada aqui é segredo — fora do EncryptedSharedPreferences); o codec cai
    // no DEFAULT/vazio em config malformada, então o IME nunca quebra por dado ruim.

    /** Leitura leve para o `onCreateInputView` do IME (só DataStore, como [themeDarkOnce]). */
    suspend fun barsConfigOnce(): BarsConfig =
        ShortcutCodec.decodeBars(dataStore.data.first()[BARS_CONFIG_JSON])

    suspend fun shortcutGridsOnce(): List<ShortcutGrid> =
        ShortcutCodec.decodeGrids(dataStore.data.first()[SHORTCUT_GRIDS_JSON])

    /**
     * Tudo o que o IME lê para montar o teclado, de UMA leitura do DataStore. O `onCreateInputView` roda na thread principal
     * bem quando o Android vai mostrar o teclado: eram oito leituras avulsas (oito idas e voltas entre threads), até 400 ms no
     * emulador lento — e o 1º quadro do teclado passava do prazo de 1 s que o Android dá para a janela aparecer (esteira de
     * instabilidade, 2026-10-10). Mesmos padrões das leituras avulsas ([themeDarkOnce] e companhia).
     */
    suspend fun keyboardSnapshotOnce(): KeyboardSnapshot = dataStore.data.first().let { prefs ->
        val barras = ShortcutCodec.decodeBars(prefs[BARS_CONFIG_JSON])
        KeyboardSnapshot(
            dark = prefs[THEME_DARK] ?: false,
            scalePercent = prefs[KEYBOARD_SCALE_PERCENT] ?: 100,
            language = prefs[KEYBOARD_LANGUAGE] ?: "pt",
            numberRowEnabled = prefs[KEYBOARD_NUMBER_ROW_ENABLED] ?: false,
            skin = prefs[KEYBOARD_SKIN] ?: "system",
            skinsUnlocked = prefs[SKINS_UNLOCKED] ?: false,
            bars = barras,
            grids = ShortcutCodec.decodeGrids(prefs[SHORTCUT_GRIDS_JSON]),
            vibration = prefs[VIBRATION_INTENSITY] ?: (if (prefs[HAPTIC_FEEDBACK] == false) 0 else 60),
            botoesDeFluxo = prefs.botoesDeFluxoDe(barras),
        )
    }

    /** Os botões `RunFlow` da barra com gatilho válido (`botaoId → fluxoId`). Barra sem botão de fluxo: nem decodifica os fluxos. */
    private fun Preferences.botoesDeFluxoDe(barras: BarsConfig): Map<String, String> =
        if (barras.botoesDeFluxo().isEmpty()) emptyMap() else BotoesDeFluxo.validos(configFluxos())

    /** Ao vivo: o IME recria a barra quando um botão de fluxo ganha, perde ou muda o gatilho, ou o fluxo fica (in)válido. */
    fun botoesDeFluxoFlow(): Flow<Map<String, String>> =
        dataStore.data.map { it.botoesDeFluxoDe(ShortcutCodec.decodeBars(it[BARS_CONFIG_JSON])) }.distinctUntilChanged()

    /** Observação ao vivo (BAR-07): o service recria a view do teclado quando a config muda. */
    fun barsConfigFlow(): Flow<BarsConfig> =
        dataStore.data.map { ShortcutCodec.decodeBars(it[BARS_CONFIG_JSON]) }

    fun shortcutGridsFlow(): Flow<List<ShortcutGrid>> =
        dataStore.data.map { ShortcutCodec.decodeGrids(it[SHORTCUT_GRIDS_JSON]) }

    /**
     * MGR-03: um modelo acabou de instalar e nenhum está selecionado → seleciona. Escrita atômica só da
     * chave do modelo: não disputa com o auto-save da tela (que regrava as Settings inteiras).
     * @return true se selecionou.
     */
    suspend fun selectLocalModelIfNone(modelId: String): Boolean = selectInstalledModelIfNone(modelId, ModelKind.SPEECH)

    /**
     * O mesmo para os tipos de modelo: a chave é a do tipo — fala escolhe o modelo da Transcrição, texto escolhe o
     * do Raciocínio, visão escolhe o da Visão.
     */
    suspend fun selectInstalledModelIfNone(modelId: String, kind: ModelKind): Boolean {
        val key = when (kind) {
            ModelKind.TEXT -> REASONING_LOCAL_MODEL_ID
            ModelKind.VISION -> VISION_LOCAL_MODEL_ID
            ModelKind.VOICE -> FALA_LOCAL_MODEL_ID
            ModelKind.SPEECH -> LOCAL_MODEL_ID
        }
        var selected = false
        dataStore.edit { prefs ->
            if (prefs[key].isNullOrBlank()) {
                prefs[key] = modelId
                selected = true
            }
        }
        return selected
    }

    /**
     * As skins escolhidas (teclado, orbe), lidas SÓ destas duas chaves — sem o round-trip das Settings inteiras, nem o cofre. O agente
     * (`skin_select`/`skin_remove`) usa isto e [updateSkinSelection]/[resetSkinsUsing]: a mudança é atômica e não disputa com o auto-save da tela.
     */
    suspend fun skinSelectionOnce(): Pair<String, String> =
        dataStore.data.first().let { (it[KEYBOARD_SKIN] ?: "system") to (it[ORB_SKIN] ?: "atelie") }

    /** Grava só as escolhas de skin que vieram (`null` = não mexe). A UI recebe pelo [settingsFlow] como qualquer mudança. */
    suspend fun updateSkinSelection(keyboard: String? = null, orb: String? = null) {
        dataStore.edit { prefs ->
            if (keyboard != null) prefs[KEYBOARD_SKIN] = keyboard
            if (orb != null) prefs[ORB_SKIN] = orb
        }
    }

    /**
     * Devolve ao padrão (teclado → Sistema, orbe → Ateliê) quem usa [skinId] (`pack:<id>`) — o que a tela faz ao remover um pacote
     * (`settingsAfterRemoval`), aqui atômico. Devolve se o teclado e a orbe usavam, para o resumo.
     */
    suspend fun resetSkinsUsing(skinId: String): Pair<Boolean, Boolean> {
        var keyboard = false
        var orb = false
        dataStore.edit { prefs ->
            if (prefs[KEYBOARD_SKIN] == skinId) {
                prefs[KEYBOARD_SKIN] = "system"
                keyboard = true
            }
            if (prefs[ORB_SKIN] == skinId) {
                prefs[ORB_SKIN] = "atelie"
                orb = true
            }
        }
        return keyboard to orb
    }

    /**
     * Os valores atuais dos ajustes que o agente pode ler (`settings_get`): SÓ as [keys] que existem em [AgentSettingKeys], lidas direto do
     * DataStore — sem o round-trip das Settings inteiras e SEM abrir os cofres (nenhum segredo passa por aqui). Chave desconhecida fica de fora.
     */
    internal suspend fun agentSettingsOnce(keys: Collection<String>): Map<String, Any> {
        val prefs = dataStore.data.first()
        val defaults = Settings()
        return keys.mapNotNull { name -> AgentSettingKeys.all[name]?.let { name to it.read(prefs, defaults) } }.toMap()
    }

    /**
     * Grava os ajustes que o agente mudou (`settings_set`): atômico (uma edição do DataStore) e SÓ nas chaves de [AgentSettingKeys.writable].
     * Qualquer outra chave — um campo secreto, um endereço, as skins (que o `skin_select` confere) — lança sem gravar NADA. A UI recebe pelo
     * [settingsFlow] como qualquer mudança.
     */
    internal suspend fun updateAgentSettings(changes: Map<String, Any>) {
        val entries = changes.map { (name, value) ->
            val entry = AgentSettingKeys.all[name]?.takeIf { name in AgentSettingKeys.writable } ?: throw IllegalArgumentException("o agente não pode gravar o ajuste \"$name\"")
            entry to value
        }
        dataStore.edit { prefs -> entries.forEach { (entry, value) -> entry.write(prefs, value) } }
    }

    /**
     * O que `app_status` precisa das Settings: os modos, as skins e a origem de raciocínio com o modelo dela — SÓ do DataStore, sem cofre e
     * sem credencial (o app_status nunca diz o que está guardado).
     */
    internal suspend fun agentStatusOnce(): AgentStatusSettings {
        val prefs = dataStore.data.first()
        val source = prefs[REASONING_SOURCE].toReasoningSource().let { if (it == ReasoningSource.CREDITS) ReasoningSource.LOCAL else it }
        val model = when (source) {
            ReasoningSource.ANTHROPIC -> prefs[REASONING_ANTHROPIC_MODEL] ?: Settings().reasoningAnthropicModel
            ReasoningSource.OPENAI -> prefs[REASONING_OPENAI_MODEL]
            ReasoningSource.GOOGLE -> prefs[REASONING_GOOGLE_MODEL] ?: Settings().reasoningGoogleModel
            ReasoningSource.LOCAL -> prefs[REASONING_LOCAL_MODEL_ID]
            ReasoningSource.CUSTOM -> prefs[REASONING_CUSTOM_MODEL]
            ReasoningSource.CREDITS -> null
        }?.takeIf { it.isNotBlank() }
        return AgentStatusSettings(
            overlayModeEnabled = prefs[OVERLAY_MODE_ENABLED] ?: false,
            keyboardModeEnabled = prefs[KEYBOARD_MODE_ENABLED] ?: false,
            keyboardSkin = prefs[KEYBOARD_SKIN] ?: "system",
            orbSkin = prefs[ORB_SKIN] ?: "atelie",
            reasoningSource = source,
            reasoningModel = model,
        )
    }

    /** Gancho do downloader ao instalar: o tipo vem do catálogo; id que o catálogo não conhece não seleciona nada. */
    internal suspend fun selectOnInstall(modelId: String) {
        val spec = ModelCatalog.byId(modelId) ?: return
        selectInstalledModelIfNone(spec.id, spec.kind)
    }

    /**
     * O DataStore inteiro, chave por chave, como está gravado. Existe para o gate de instrumentados
     * guardar a config de quem é dono do aparelho antes de rodar a suíte no estado de fábrica
     * (`FactorySettingsListener`, androidTest) — nenhuma tela do app usa isto.
     *
     * Migra antes os segredos que um build antigo deixou em claro ([migrateLegacySecrets]): o gate copia o que
     * lê para um arquivo em `files/`, que seria mais uma cópia em claro (e no backup) do segredo.
     */
    internal suspend fun rawPreferencesOnce(): Preferences {
        migrateLegacySecrets()
        return dataStore.data.first()
    }

    /**
     * Cada estado do DataStore como é emitido, sem tocar no cofre. Existe para o teste de instrumentado conferir que duas
     * chaves gravadas "numa só transação" nunca aparecem separadas (`FluxosRepositoryTest`) — nenhuma tela usa isto.
     */
    internal fun rawPreferencesFlow(): Flow<Preferences> = dataStore.data

    /** Troca o DataStore inteiro por [prefs]; vazio = estado de fábrica. Par de [rawPreferencesOnce]. */
    internal suspend fun replaceRawPreferences(prefs: Preferences) {
        dataStore.edit { current ->
            current.clear()
            current += prefs
        }
    }

    suspend fun saveBarsConfig(config: BarsConfig) {
        dataStore.edit { prefs ->
            prefs[BARS_CONFIG_JSON] = ShortcutCodec.encodeBars(config)
            // Remover o botão de um fluxo no editor de barras desliga o gatilho dele (FLX-36, FLX-50). Só regrava os fluxos se algum
            // gatilho ficou órfão: salvar as barras não "adota" a semente dos fluxos.
            val atual = prefs.configFluxos()
            if (PoliticaGatilhos.limpar(atual, config.botoesDeFluxo()).gatilhos != atual.gatilhos) prefs.gravarFluxos(atual, config)
        }
    }

    suspend fun saveShortcutGrids(grids: List<ShortcutGrid>) {
        dataStore.edit { it[SHORTCUT_GRIDS_JSON] = ShortcutCodec.encodeGrids(grids) }
    }

    // ── Fluxos (aba Fluxos): `flows_json` e `flow_triggers_json` ─────────────
    // Duas chaves próprias, fora de [Settings]: o auto-save da tela regrava as Settings inteiras e nunca encosta nestas, e estas
    // nunca encostam nas Settings (o gatilho sombreia a ação do gesto, não a sobrescreve — D12). Nada aqui é segredo e nada
    // abre o cofre (FLX-46, FLX-49). Chave ausente ou JSON malformado = os fluxos padrão, só em memória.

    private fun Preferences.configFluxos(): ConfigFluxos =
        CodecFluxos.decodificarConfig(this[FLOWS_JSON], this[FLOW_TRIGGERS_JSON], PadroesFluxo::semear)

    /** Leitura leve para o serviço da orbe e o IME (só DataStore, como [barsConfigOnce]). Não grava a semente. */
    suspend fun configFluxosOnce(): ConfigFluxos = dataStore.data.first().configFluxos()

    /** Observação ao vivo: trocar um gatilho vale no gesto seguinte, sem reiniciar o serviço (FLX-49). */
    fun configFluxosFlow(): Flow<ConfigFluxos> = dataStore.data.map { it.configFluxos() }.distinctUntilChanged()

    /**
     * Altera os fluxos e os gatilhos **sobre o valor gravado agora** (FLX-48): lê, aplica [alterar] (pura) e grava os dois JSON
     * dentro do mesmo `dataStore.edit`, então duas telas (o modal e o editor de barras) não se atropelam, mesmo com estado velho
     * na mão. A primeira alteração grava a lista inteira (a semente passa a ser da pessoa). Tira os gatilhos órfãos (FLX-50);
     * sem mudança nenhuma, não regrava. Devolve o que ficou gravado.
     */
    suspend fun atualizarFluxos(alterar: (ConfigFluxos) -> ConfigFluxos): ConfigFluxos =
        dataStore.edit { prefs -> prefs.gravarFluxos(alterar(prefs.configFluxos())) }.configFluxos()

    /**
     * Como [atualizarFluxos], mais as barras do teclado **na mesma transação** (criar ou remover o botão do fluxo e o gatilho
     * dele juntos): quem lê nunca vê um sem o outro. Devolve a config de fluxos que ficou gravada.
     */
    suspend fun atualizarFluxosEBarras(alterar: (ConfigFluxos, BarsConfig) -> Pair<ConfigFluxos, BarsConfig>): ConfigFluxos =
        dataStore.edit { prefs ->
            val (fluxos, barras) = alterar(prefs.configFluxos(), ShortcutCodec.decodeBars(prefs[BARS_CONFIG_JSON]))
            prefs.gravarFluxos(fluxos, barras)
            prefs[BARS_CONFIG_JSON] = ShortcutCodec.encodeBars(barras)
        }.configFluxos()

    /**
     * Grava os dois JSON de [nova] depois de tirar os gatilhos órfãos; se o que está gravado já é exatamente isso, não regrava
     * (um JSON malformado, que a leitura trata como a semente, é consertado na primeira gravação).
     *
     * Os gatilhos de botão da barra também são conferidos contra os botões `RunFlow` que a barra tem (FLX-50): [barras] são as que
     * valem depois desta gravação (as novas, quando a mesma transação as grava; senão as já gravadas).
     */
    private fun MutablePreferences.gravarFluxos(nova: ConfigFluxos, barras: BarsConfig = ShortcutCodec.decodeBars(this[BARS_CONFIG_JSON])) {
        val limpa = PoliticaGatilhos.limpar(nova, botoesDaBarra = barras.botoesDeFluxo())
        val fluxosJson = CodecFluxos.codificarFluxos(limpa.fluxos)
        val gatilhosJson = CodecFluxos.codificarGatilhos(limpa.gatilhos)
        if (this[FLOWS_JSON] == fluxosJson && this[FLOW_TRIGGERS_JSON] == gatilhosJson) return
        this[FLOWS_JSON] = fluxosJson
        this[FLOW_TRIGGERS_JSON] = gatilhosJson
    }

    // ── Orbe do teclado (`keyboard_orb_config_json`, KBO-27..29) ─────────────
    // Chave própria no DataStore, fora do auto-save de [Settings] e sem tocar nos cofres cifrados.

    private fun Preferences.configOrbeTeclado(): ConfigOrbeTeclado =
        CodecOrbeTeclado.decodificar(this[KEYBOARD_ORB_CONFIG_JSON])

    /** Observação ao vivo da configuração própria da orbe do teclado (KBO-29). */
    fun configOrbeTecladoFlow(): Flow<ConfigOrbeTeclado> =
        dataStore.data.map { it.configOrbeTeclado() }.distinctUntilChanged()

    /** Leitura leve da configuração própria da orbe do teclado (só DataStore, sem o cofre — KBO-29). */
    suspend fun configOrbeTecladoOnce(): ConfigOrbeTeclado =
        dataStore.data.first().configOrbeTeclado()

    /**
     * Escrita atômica sobre o valor atual de `keyboard_orb_config_json` (`dataStore.edit`), para o auto-save das
     * [Settings] não desfazer a seção da orbe e esta não regravar as [Settings] (KBO-27).
     */
    suspend fun atualizarOrbeTeclado(f: (ConfigOrbeTeclado) -> ConfigOrbeTeclado): ConfigOrbeTeclado =
        dataStore.edit { prefs ->
            val nova = f(prefs.configOrbeTeclado())
            val json = CodecOrbeTeclado.codificar(nova)
            if (prefs[KEYBOARD_ORB_CONFIG_JSON] != json) {
                prefs[KEYBOARD_ORB_CONFIG_JSON] = json
            }
        }.configOrbeTeclado()

    /** Onde cada modelo de texto local roda, ao vivo — o seletor "Roda em" do card (chave própria, fora de [Settings]). */
    fun localBackendPrefsFlow(): Flow<LocalBackendPrefs> =
        dataStore.data.map { LocalBackendPrefs.decode(it[REASONING_LOCAL_BACKENDS]) }.distinctUntilChanged()

    /**
     * O backend do [modelId] agora: leitura leve só do DataStore (sem cofre), chamada a cada geração local para a troca no card valer
     * já na próxima. Sem escolha, CPU.
     */
    suspend fun localBackendFor(modelId: String): ComputeBackend =
        LocalBackendPrefs.decode(dataStore.data.first()[REASONING_LOCAL_BACKENDS]).backendFor(modelId)

    /** O backend e a ficha da escolha do [modelId] numa só leitura: quem pede uma carga leva os dois juntos (ver [LocalBackendPrefs.ticketFor]). */
    suspend fun localBackendChoiceFor(modelId: String): LocalBackendChoice {
        val prefs = LocalBackendPrefs.decode(dataStore.data.first()[REASONING_LOCAL_BACKENDS])
        return LocalBackendChoice(prefs.backendFor(modelId), prefs.ticketFor(modelId))
    }

    /**
     * Muda a tabela de backends numa escrita atômica só dessa chave: não disputa com o auto-save da tela, que regrava as Settings
     * inteiras (mesma razão de [selectInstalledModelIfNone]). Devolve o que ficou gravado.
     */
    suspend fun updateLocalBackendPrefs(change: (LocalBackendPrefs) -> LocalBackendPrefs): LocalBackendPrefs {
        var result = LocalBackendPrefs.EMPTY
        dataStore.edit { prefs ->
            result = change(LocalBackendPrefs.decode(prefs[REASONING_LOCAL_BACKENDS]))
            val text = result.encode()
            if (text.isEmpty()) prefs.remove(REASONING_LOCAL_BACKENDS) else prefs[REASONING_LOCAL_BACKENDS] = text
        }
        return result
    }

    fun flow(): Flow<Config> = settingsFlow().map { settings ->
        val values = SettingsCredentialValues(
            openaiToken = credentials.getApiKey(ProviderIds.OPENAI)?.value.orEmpty(),
            groqToken = credentials.getApiKey(ProviderIds.GROQ)?.value.orEmpty(),
        )
        configDe(settings, values)
    }.flowOn(Dispatchers.IO)

    /**
     * Estado completo de configuração (T5). Os campos `custom*` compartilham as chaves legadas;
     * os segredos (tokens, chaves de API, senha e chave SSH, valores de cabeçalho — [SettingsSecrets.BY_PROPERTY])
     * vêm do armazenamento criptografado e são preservados entre trocas de origem.
     */
    // O map lê o cofre cifrado (abre o EncryptedSharedPreferences: disco + Keystore) e roda na thread de quem coleta — o
    // teclado e a orbe coletam na principal. flowOn leva a leitura (e a migração do onStart) para o IO (StrictModeKeyboardTest).
    fun settingsFlow(): Flow<Settings> = flow {
        migrateLegacySecrets()
        emitAll(dataStore.data.map { prefs ->
        val mirrorUrl = prefs[CUSTOM_MODEL_MIRROR_URL]?.ifBlank { null }
        ModelProvisioning.customMirrorUrl = mirrorUrl
        Settings(
            source = prefs[SOURCE].toSource(),
            customEndpoint = prefs[ENDPOINT_URL].orEmpty(),
            customHeaderName = prefs[AUTH_HEADER_NAME] ?: "Authorization",
            customHeaderValue = credentials.getSecret(SettingsCredentialKeys.CUSTOM_HEADER).orEmpty(),
            customModel = prefs[MODEL] ?: "whisper-1",
            customLanguage = prefs[LANGUAGE]?.takeIf { it.isNotBlank() },
            openaiModel = prefs[OPENAI_MODEL] ?: Settings.OPENAI_MODEL,
            openaiLanguage = prefs[OPENAI_LANGUAGE]?.takeIf { it.isNotBlank() },
            groqModel = prefs[GROQ_MODEL] ?: Settings.GROQ_MODEL,
            groqLanguage = prefs[GROQ_LANGUAGE]?.takeIf { it.isNotBlank() },
            localModelId = prefs[LOCAL_MODEL_ID]?.takeIf { it.isNotBlank() },
            localLanguage = prefs[LOCAL_LANGUAGE]?.takeIf { it.isNotBlank() },
            reasoningSource = prefs[REASONING_SOURCE].toReasoningSource(),
            reasoningLocalModelId = prefs[REASONING_LOCAL_MODEL_ID]?.takeIf { it.isNotBlank() },
            reasoningOpenAiModel = prefs[REASONING_OPENAI_MODEL].orEmpty(),
            reasoningAnthropicModel = prefs[REASONING_ANTHROPIC_MODEL] ?: "claude-3-5-sonnet-latest",
            reasoningGoogleModel = prefs[REASONING_GOOGLE_MODEL] ?: "gemini-2.0-flash",
            reasoningCustomEndpoint = prefs[REASONING_CUSTOM_ENDPOINT].orEmpty(),
            reasoningCustomHeaderName = prefs[REASONING_CUSTOM_HEADER_NAME] ?: "Authorization",
            reasoningCustomHeaderValue = secret(SettingsCredentialKeys.REASONING_CUSTOM_HEADER),
            reasoningCustomModel = prefs[REASONING_CUSTOM_MODEL].orEmpty(),
            visionSource = prefs[VISION_SOURCE].toVisionSource(),
            visionLocalModelId = prefs[VISION_LOCAL_MODEL_ID]?.takeIf { it.isNotBlank() },
            visionOpenAiModel = prefs[VISION_OPENAI_MODEL].orEmpty(),
            visionGoogleModel = prefs[VISION_GOOGLE_MODEL].orEmpty(),
            visionAnthropicModel = prefs[VISION_ANTHROPIC_MODEL].orEmpty(),
            visionCustomEndpoint = prefs[VISION_CUSTOM_ENDPOINT].orEmpty(),
            visionCustomHeaderName = prefs[VISION_CUSTOM_HEADER_NAME] ?: "Authorization",
            visionCustomHeaderValue = secret(SettingsCredentialKeys.VISION_CUSTOM_HEADER),
            visionCustomModel = prefs[VISION_CUSTOM_MODEL].orEmpty(),
            falaSource = prefs[FALA_SOURCE].toFalaSource(),
            falaLocalModelId = prefs[FALA_LOCAL_MODEL_ID]?.takeIf { it.isNotBlank() },
            falaSpeed = prefs[FALA_SPEED] ?: 1.0f,
            falaCloudVoice = prefs[FALA_CLOUD_VOICE] ?: "alloy",
            falaElevenLabsVoice = prefs[FALA_ELEVENLABS_VOICE]?.takeIf { it != "Rachel" }.orEmpty(),
            falaElevenLabsModel = prefs[FALA_ELEVENLABS_MODEL] ?: "eleven_multilingual_v2",
            falaGoogleVoice = prefs[FALA_GOOGLE_VOICE] ?: "pt-BR-Neural2-A",
            falaCustomEndpoint = prefs[FALA_CUSTOM_ENDPOINT].orEmpty(),
            falaCustomHeaderName = prefs[FALA_CUSTOM_HEADER_NAME] ?: "Authorization",
            falaCustomHeaderValue = secret(SettingsCredentialKeys.FALA_CUSTOM_HEADER),
            falaCustomModel = prefs[FALA_CUSTOM_MODEL].orEmpty(),
            falaCustomVoice = prefs[FALA_CUSTOM_VOICE] ?: "alloy",
            falaCustomFormat = prefs[FALA_CUSTOM_FORMAT] ?: "wav",
            computerMode = prefs[COMPUTER_MODE] ?: "ssh",
            computerSshHost = prefs[COMPUTER_SSH_HOST].orEmpty(),
            computerSshPort = prefs[COMPUTER_SSH_PORT] ?: 22,
            computerSshUser = prefs[COMPUTER_SSH_USER].orEmpty(),
            computerSshAuthType = prefs[COMPUTER_SSH_AUTH_TYPE] ?: "password",
            computerSshPassword = secret(SettingsCredentialKeys.COMPUTER_SSH_PASSWORD),
            computerSshKey = secret(SettingsCredentialKeys.COMPUTER_SSH_KEY),
            customModelMirrorUrl = prefs[CUSTOM_MODEL_MIRROR_URL].orEmpty(),
            providerAnthropicModel = prefs[PROVIDER_ANTHROPIC_MODEL] ?: "claude-3-5-sonnet-latest",
            providerAnthropicAuthMode = prefs[PROVIDER_ANTHROPIC_AUTH_MODE] ?: "subscription",
            providerOpenAiModel = prefs[PROVIDER_OPENAI_MODEL] ?: "gpt-4o",
            providerOpenAiAuthMode = prefs[PROVIDER_OPENAI_AUTH_MODE] ?: "subscription",
            providerGoogleModel = prefs[PROVIDER_GOOGLE_MODEL] ?: "gemini-2.0-flash",
            providerGoogleAuthMode = prefs[PROVIDER_GOOGLE_AUTH_MODE] ?: "subscription",
            providerElevenLabsAuthMode = prefs[PROVIDER_ELEVENLABS_AUTH_MODE] ?: "subscription",
            providerGroqModel = prefs[PROVIDER_GROQ_MODEL] ?: "llama-3.3-70b-versatile",
            providerGrokModel = prefs[PROVIDER_GROK_MODEL] ?: "grok-2",
            overlayModeEnabled = prefs[OVERLAY_MODE_ENABLED] ?: false,
            keyboardModeEnabled = prefs[KEYBOARD_MODE_ENABLED] ?: false,
            overlayRecordingMode = prefs[OVERLAY_RECORDING_MODE].toRecordingMode(),
            // Migra o antigo KEYBOARD_AUTO_PASTE único para os dois flags (mesmo valor) quando os novos
            // ainda não existem.
            autoPasteInField = prefs[AUTO_PASTE_IN_FIELD] ?: prefs[KEYBOARD_AUTO_PASTE] ?: true,
            autoPasteOnFocus = prefs[AUTO_PASTE_ON_FOCUS] ?: prefs[KEYBOARD_AUTO_PASTE] ?: true,
            overlaySide = prefs[OVERLAY_SIDE].toOverlaySide(),
            orbSizePx = prefs[ORB_SIZE_PX] ?: 195,
            tapBurstWindowMs = prefs[TAP_BURST_WINDOW_MS] ?: 250,
            shortcut1Tap = prefs[SHORTCUT_1_TAP].toShortcutAction(OrbShortcutAction.NONE),
            shortcut2Tap = prefs[SHORTCUT_2_TAP].toShortcutAction(OrbShortcutAction.TOGGLE_KEYBOARD),
            shortcut3Tap = prefs[SHORTCUT_3_TAP].toShortcutAction(OrbShortcutAction.SWITCH_IME),
            shortcutOverflow = prefs[SHORTCUT_OVERFLOW].toShortcutOverflow(),
            // Migração do antigo liga/desliga: haptic=false → 0; ausente/true → 60.
            vibrationIntensity = prefs[VIBRATION_INTENSITY]
                ?: (if (prefs[HAPTIC_FEEDBACK] == false) 0 else 60),
            themeDark = prefs[THEME_DARK] ?: false,
            onboardingDone = prefs[ONBOARDING_DONE] ?: false,
            disclosureAcceptedVersion = prefs[DISCLOSURE_ACCEPTED_VERSION] ?: 0,
            disclosureDeclined = prefs[DISCLOSURE_DECLINED] ?: false,
            transcriptionTimeoutMs = prefs[TRANSCRIPTION_TIMEOUT_MS] ?: Config.DEFAULT_TIMEOUT_MS.toInt(),
            maxRecordingSec = prefs[MAX_RECORDING_SEC] ?: 300,
            keyboardScalePercent = prefs[KEYBOARD_SCALE_PERCENT] ?: 100,
            keyboardLanguage = prefs[KEYBOARD_LANGUAGE] ?: "pt",
            keyboardNumberRowEnabled = prefs[KEYBOARD_NUMBER_ROW_ENABLED] ?: false,
            partialOnPause = prefs[PARTIAL_ON_PAUSE] ?: false,
            partialViewEdit = prefs[PARTIAL_VIEW_EDIT] ?: false,
            keyboardShowWithHardKeyboard = prefs[KEYBOARD_SHOW_WITH_HARD_KEYBOARD] ?: true,
            keyboardAutoCorrect = prefs[KEYBOARD_AUTO_CORRECT] ?: true,
            keyboardSkin = prefs[KEYBOARD_SKIN] ?: "system",
            orbSkin = prefs[ORB_SKIN] ?: "atelie",
            appLanguage = prefs[APP_LANGUAGE] ?: "",
        ).withoutCredits()
        })
    }.flowOn(Dispatchers.IO)

    /**
     * Persiste o estado completo (T5). Segredos criptografados; `null` em texto remove a chave do DataStore.
     * Não apaga credenciais de outros provedores — todos os segredos ([SettingsSecrets.BY_PROPERTY]) são
     * reescritos a partir de [settings]. Só depois de o cofre confirmar o `commit()` o DataStore é tocado, e então
     * qualquer cópia em claro que um build antigo tenha deixado lá é removida (ver [migrateLegacySecrets]).
     */
    suspend fun saveSettings(settings: Settings) {
        val persisted = settings.withoutCredits()
        withContext(Dispatchers.IO) {
            SettingsSecrets.SUSPEND_LOCK.withLock {
                // Nunca descarte uma cópia legada antes de a mover para o auth vault.
                migrateLegacySecretsLocked()
                val values = mapOf(
                    SettingsCredentialKeys.CUSTOM_HEADER to persisted.customHeaderValue,
                    SettingsCredentialKeys.REASONING_CUSTOM_HEADER to persisted.reasoningCustomHeaderValue,
                    SettingsCredentialKeys.VISION_CUSTOM_HEADER to persisted.visionCustomHeaderValue,
                    SettingsCredentialKeys.FALA_CUSTOM_HEADER to persisted.falaCustomHeaderValue,
                    SettingsCredentialKeys.COMPUTER_SSH_PASSWORD to persisted.computerSshPassword,
                    SettingsCredentialKeys.COMPUTER_SSH_KEY to persisted.computerSshKey,
                )
                values.forEach { (key, value) ->
                    if (value.isBlank()) credentials.clearSecret(key) else credentials.putSecret(key, value)
                }
                dataStore.edit { prefs ->
            SettingsSecrets.LEGACY_IN_DATASTORE.forEach { prefs.remove(it) }
            prefs[SOURCE] = persisted.source.name
            prefs[ENDPOINT_URL] = persisted.customEndpoint
            prefs[AUTH_HEADER_NAME] = persisted.customHeaderName
            prefs[MODEL] = persisted.customModel
            persisted.customLanguage.putOrRemove(prefs, LANGUAGE)
            prefs[OPENAI_MODEL] = persisted.openaiModel
            persisted.openaiLanguage.putOrRemove(prefs, OPENAI_LANGUAGE)
            prefs[GROQ_MODEL] = persisted.groqModel
            persisted.groqLanguage.putOrRemove(prefs, GROQ_LANGUAGE)
            persisted.localModelId.putOrRemove(prefs, LOCAL_MODEL_ID)
            persisted.localLanguage.putOrRemove(prefs, LOCAL_LANGUAGE)
            prefs[REASONING_SOURCE] = persisted.reasoningSource.name
            persisted.reasoningLocalModelId.putOrRemove(prefs, REASONING_LOCAL_MODEL_ID)
            persisted.reasoningOpenAiModel.putOrRemove(prefs, REASONING_OPENAI_MODEL)
            prefs[REASONING_ANTHROPIC_MODEL] = persisted.reasoningAnthropicModel
            prefs[REASONING_GOOGLE_MODEL] = persisted.reasoningGoogleModel
            prefs[REASONING_CUSTOM_ENDPOINT] = persisted.reasoningCustomEndpoint
            prefs[REASONING_CUSTOM_HEADER_NAME] = persisted.reasoningCustomHeaderName
            prefs[REASONING_CUSTOM_MODEL] = persisted.reasoningCustomModel
            prefs[VISION_SOURCE] = persisted.visionSource.name
            persisted.visionLocalModelId.putOrRemove(prefs, VISION_LOCAL_MODEL_ID)
            prefs[VISION_OPENAI_MODEL] = persisted.visionOpenAiModel
            prefs[VISION_GOOGLE_MODEL] = persisted.visionGoogleModel
            prefs[VISION_ANTHROPIC_MODEL] = persisted.visionAnthropicModel
            prefs[VISION_CUSTOM_ENDPOINT] = persisted.visionCustomEndpoint
            prefs[VISION_CUSTOM_HEADER_NAME] = persisted.visionCustomHeaderName
            prefs[VISION_CUSTOM_MODEL] = persisted.visionCustomModel
            prefs[FALA_SOURCE] = persisted.falaSource.name
            persisted.falaLocalModelId.putOrRemove(prefs, FALA_LOCAL_MODEL_ID)
            prefs[FALA_SPEED] = persisted.falaSpeed
            prefs[FALA_CLOUD_VOICE] = persisted.falaCloudVoice
            prefs[FALA_ELEVENLABS_VOICE] = persisted.falaElevenLabsVoice
            prefs[FALA_ELEVENLABS_MODEL] = persisted.falaElevenLabsModel
            prefs[FALA_GOOGLE_VOICE] = persisted.falaGoogleVoice
            prefs[FALA_CUSTOM_ENDPOINT] = persisted.falaCustomEndpoint
            prefs[FALA_CUSTOM_HEADER_NAME] = persisted.falaCustomHeaderName
            prefs[FALA_CUSTOM_MODEL] = persisted.falaCustomModel
            prefs[FALA_CUSTOM_VOICE] = persisted.falaCustomVoice
            prefs[FALA_CUSTOM_FORMAT] = persisted.falaCustomFormat
            prefs[COMPUTER_MODE] = persisted.computerMode
            prefs[COMPUTER_SSH_HOST] = persisted.computerSshHost
            prefs[COMPUTER_SSH_PORT] = persisted.computerSshPort
            prefs[COMPUTER_SSH_USER] = persisted.computerSshUser
            prefs[COMPUTER_SSH_AUTH_TYPE] = persisted.computerSshAuthType
            prefs[CUSTOM_MODEL_MIRROR_URL] = persisted.customModelMirrorUrl
            ModelProvisioning.customMirrorUrl = persisted.customModelMirrorUrl.ifBlank { null }
            prefs[PROVIDER_ANTHROPIC_MODEL] = persisted.providerAnthropicModel
            prefs[PROVIDER_ANTHROPIC_AUTH_MODE] = persisted.providerAnthropicAuthMode
            prefs[PROVIDER_OPENAI_MODEL] = persisted.providerOpenAiModel
            prefs[PROVIDER_OPENAI_AUTH_MODE] = persisted.providerOpenAiAuthMode
            prefs[PROVIDER_GOOGLE_MODEL] = persisted.providerGoogleModel
            prefs[PROVIDER_GOOGLE_AUTH_MODE] = persisted.providerGoogleAuthMode
            prefs[PROVIDER_ELEVENLABS_AUTH_MODE] = persisted.providerElevenLabsAuthMode
            prefs[PROVIDER_GROQ_MODEL] = persisted.providerGroqModel
            prefs[PROVIDER_GROK_MODEL] = persisted.providerGrokModel
            prefs[OVERLAY_MODE_ENABLED] = persisted.overlayModeEnabled
            prefs[KEYBOARD_MODE_ENABLED] = persisted.keyboardModeEnabled
            prefs[OVERLAY_RECORDING_MODE] = persisted.overlayRecordingMode.name
            prefs[AUTO_PASTE_IN_FIELD] = persisted.autoPasteInField
            prefs[AUTO_PASTE_ON_FOCUS] = persisted.autoPasteOnFocus
            prefs[OVERLAY_SIDE] = persisted.overlaySide.name
            prefs[ORB_SIZE_PX] = persisted.orbSizePx
            prefs[TAP_BURST_WINDOW_MS] = persisted.tapBurstWindowMs
            prefs[SHORTCUT_1_TAP] = persisted.shortcut1Tap.name
            prefs[SHORTCUT_2_TAP] = persisted.shortcut2Tap.name
            prefs[SHORTCUT_3_TAP] = persisted.shortcut3Tap.name
            prefs[SHORTCUT_OVERFLOW] = persisted.shortcutOverflow.name
            prefs[VIBRATION_INTENSITY] = persisted.vibrationIntensity
            prefs[THEME_DARK] = persisted.themeDark
            prefs[ONBOARDING_DONE] = persisted.onboardingDone
            prefs[DISCLOSURE_ACCEPTED_VERSION] = persisted.disclosureAcceptedVersion
            prefs[DISCLOSURE_DECLINED] = persisted.disclosureDeclined
            prefs[TRANSCRIPTION_TIMEOUT_MS] = persisted.transcriptionTimeoutMs
            prefs[MAX_RECORDING_SEC] = persisted.maxRecordingSec
            prefs[KEYBOARD_SCALE_PERCENT] = persisted.keyboardScalePercent
            prefs[KEYBOARD_LANGUAGE] = persisted.keyboardLanguage
            prefs[KEYBOARD_NUMBER_ROW_ENABLED] = persisted.keyboardNumberRowEnabled
            prefs[PARTIAL_ON_PAUSE] = persisted.partialOnPause
            prefs[PARTIAL_VIEW_EDIT] = persisted.partialViewEdit
            prefs[KEYBOARD_SHOW_WITH_HARD_KEYBOARD] = persisted.keyboardShowWithHardKeyboard
            prefs[KEYBOARD_AUTO_CORRECT] = persisted.keyboardAutoCorrect
            prefs[KEYBOARD_SKIN] = persisted.keyboardSkin
            prefs[ORB_SKIN] = persisted.orbSkin
            prefs[APP_LANGUAGE] = persisted.appLanguage
                }
                credentials.setAuthMode(ProviderIds.OPENAI, persisted.providerOpenAiAuthMode.toCredentialAuthMethod())
                credentials.setAuthMode(ProviderIds.ANTHROPIC, persisted.providerAnthropicAuthMode.toCredentialAuthMethod())
                credentials.setAuthMode(ProviderIds.GOOGLE, persisted.providerGoogleAuthMode.toCredentialAuthMethod())
            }
        }
    }

    private fun String?.putOrRemove(prefs: MutablePreferences, key: Preferences.Key<String>) {
        if (this.isNullOrBlank()) prefs.remove(key) else prefs[key] = this
    }

    private fun String.toCredentialAuthMethod(): com.keyflow.auth.AuthMethod =
        if (this == "subscription") com.keyflow.auth.AuthMethod.OAUTH else com.keyflow.auth.AuthMethod.API_KEY

    /** O segredo não entra no DataStore nem em `Settings` para chaves de API; os campos legados restantes leem o auth vault. */
    private fun secret(name: String): String = credentials.getSecret(name).orEmpty()

    private fun apiKeyProvider(key: String) = when (key) {
        SettingsCredentialKeys.OPENAI_TOKEN -> ProviderIds.OPENAI
        SettingsCredentialKeys.GROQ_TOKEN -> ProviderIds.GROQ
        SettingsCredentialKeys.VISION_GOOGLE_API_KEY, SettingsCredentialKeys.FALA_GOOGLE_API_KEY -> ProviderIds.GOOGLE
        SettingsCredentialKeys.VISION_ANTHROPIC_API_KEY -> ProviderIds.ANTHROPIC
        SettingsCredentialKeys.FALA_ELEVENLABS_API_KEY -> ProviderIds.ELEVENLABS
        SettingsCredentialKeys.PROVIDER_GROQ_API_KEY -> ProviderIds.GROQ
        SettingsCredentialKeys.PROVIDER_GROK_API_KEY -> ProviderIds.GROK
        else -> null
    }

    /**
     * Segredos que builds de desenvolvimento gravaram em claro no DataStore ([SettingsSecrets.LEGACY_IN_DATASTORE]):
     * se o cofre ainda não tem o valor, copia para ele e **só depois** de o `commit()` confirmar remove a chave do
     * DataStore. Roda ao começar cada coleta de [settingsFlow] e em [rawPreferencesOnce]; o caminho comum, sem nada
     * em claro, só olha o DataStore e nem abre o cofre. Idempotente. Se o cofre falhar, o segredo fica onde está
     * (a próxima leitura tenta de novo) e a leitura não cai.
     */
    private suspend fun migrateLegacySecrets() {
        try {
            SettingsSecrets.SUSPEND_LOCK.withLock { migrateLegacySecretsLocked() }
        } catch (e: Exception) {
            Log.w(TAG, "Cofre indisponível; segredos antigos ficam no local de origem por enquanto", e)
        }
    }

    /** Pré-condição: [SettingsSecrets.SUSPEND_LOCK] está adquirido. Falha propaga para não apagar a origem. */
    private suspend fun migrateLegacySecretsLocked() {
        // O DataStore é lido antes do lock de arquivo: não há suspensão enquanto outro processo espera pela cópia cifrada.
        val current = dataStore.data.first()
        val (movable, inferredModes) = CredentialVaultLock.withLock(appContext) {
            credentials.migrateLegacyActiveCredentials()
            val migrated = SettingsSecrets.migrateLegacySources(current, legacyPrefs, credentials)
            val modes = listOf(
                PROVIDER_OPENAI_AUTH_MODE to ProviderIds.OPENAI,
                PROVIDER_ANTHROPIC_AUTH_MODE to ProviderIds.ANTHROPIC,
                PROVIDER_GOOGLE_AUTH_MODE to ProviderIds.GOOGLE,
            ).mapNotNull { (key, provider) ->
                if (current[key] != null) return@mapNotNull null
                val oauth = credentials.getOAuth(provider)
                val apiKey = credentials.getApiKey(provider)
                if (oauth == null && apiKey == null) return@mapNotNull null
                val mode = if (oauth != null) "subscription" else "api_key"
                Triple(key, provider, mode)
            }
            migrated to modes
        }
        if (movable.isNotEmpty() || inferredModes.isNotEmpty()) {
            // A remoção das origens e a gravação do modo inferido só ocorrem após o commit cifrado.
            dataStore.edit { prefs ->
                movable.forEach { prefs.remove(it) }
                inferredModes.forEach { (key, _, mode) -> if (prefs[key] == null) prefs[key] = mode }
            }
            inferredModes.forEach { (_, provider, mode) ->
                credentials.setAuthMode(provider, mode.toCredentialAuthMethod())
            }
        }
    }

    fun overlayPositionFlow(): Flow<OverlayPosition> = dataStore.data.map { prefs ->
        OverlayPosition(
            x = prefs[OVERLAY_X] ?: OverlayPosition().x,
            y = prefs[OVERLAY_Y] ?: OverlayPosition().y,
        )
    }

    suspend fun save(cfg: Config) {
        withContext(Dispatchers.IO) {
            SettingsSecrets.SUSPEND_LOCK.withLock {
                migrateLegacySecretsLocked()
                if (cfg.authHeaderValue.isBlank()) credentials.clearSecret(SettingsCredentialKeys.CUSTOM_HEADER)
                else credentials.putSecret(SettingsCredentialKeys.CUSTOM_HEADER, cfg.authHeaderValue)
                dataStore.edit { prefs ->
            prefs[ENDPOINT_URL] = cfg.endpointUrl
            prefs[AUTH_HEADER_NAME] = cfg.authHeaderName
            prefs[MODEL] = cfg.model
            prefs[RECORDING_MODE] = cfg.recordingMode.name
            if (cfg.language.isNullOrBlank()) {
                prefs.remove(LANGUAGE)
            } else {
                prefs[LANGUAGE] = cfg.language
            }
                }
            }
        }
    }

    suspend fun saveOverlayPosition(position: OverlayPosition) {
        dataStore.edit { prefs ->
            prefs[OVERLAY_X] = position.x
            prefs[OVERLAY_Y] = position.y
        }
    }

    private fun String?.toRecordingMode(): RecordingMode = this
        ?.let { runCatching { RecordingMode.valueOf(it) }.getOrNull() }
        ?: RecordingMode.TOGGLE

    private fun String?.toSource(): TranscriptionSource = this
        ?.let { runCatching { TranscriptionSource.valueOf(it) }.getOrNull() }
        ?: TranscriptionSource.CUSTOM

    /** Valor ausente ou desconhecido (versão futura, dado corrompido) cai no Local — nunca derruba a leitura. */
    private fun String?.toReasoningSource(): ReasoningSource = this
        ?.let { runCatching { ReasoningSource.valueOf(it) }.getOrNull() }
        ?: ReasoningSource.LOCAL

    /** Valor ausente ou desconhecido cai no Local — nunca derruba a leitura. */
    private fun String?.toVisionSource(): VisionSource = this
        ?.let { runCatching { VisionSource.valueOf(it) }.getOrNull() }
        ?: VisionSource.LOCAL

    /** Valor ausente ou desconhecido cai no Local — nunca derruba a leitura. */
    private fun String?.toFalaSource(): FalaSource = this
        ?.let { runCatching { FalaSource.valueOf(it) }.getOrNull() }
        ?: FalaSource.LOCAL

    private fun String?.toOverlaySide(): OverlaySide = this
        ?.let { runCatching { OverlaySide.valueOf(it) }.getOrNull() }
        ?: OverlaySide.RIGHT

    private fun String?.toShortcutAction(default: OrbShortcutAction): OrbShortcutAction = this
        ?.let { runCatching { OrbShortcutAction.valueOf(it) }.getOrNull() }
        ?: default

    private fun String?.toShortcutOverflow(): ShortcutOverflow = this
        ?.let { runCatching { ShortcutOverflow.valueOf(it) }.getOrNull() }
        ?: ShortcutOverflow.NEAREST_DOWN

    private companion object {
        const val DATASTORE_NAME = "keyflow_settings"
        const val SECURE_PREFS_NAME = "keyflow_secure"
        const val TAG = "SettingsRepository"
        val DATASTORES = mutableMapOf<String, DataStore<Preferences>>()

        val ENDPOINT_URL = stringPreferencesKey("endpoint_url")
        val AUTH_HEADER_NAME = stringPreferencesKey("auth_header_name")
        val MODEL = stringPreferencesKey("model")
        val LANGUAGE = stringPreferencesKey("language")
        val RECORDING_MODE = stringPreferencesKey("recording_mode")
        val OVERLAY_X = intPreferencesKey("overlay_x")
        val OVERLAY_Y = intPreferencesKey("overlay_y")

        // T5 — novas chaves
        val SOURCE = stringPreferencesKey("transcription_source")
        val OPENAI_MODEL = stringPreferencesKey("openai_model")
        val OPENAI_LANGUAGE = stringPreferencesKey("openai_language")
        val GROQ_MODEL = stringPreferencesKey("groq_model")
        val GROQ_LANGUAGE = stringPreferencesKey("groq_language")
        val LOCAL_MODEL_ID = stringPreferencesKey("local_model_id")
        val LOCAL_LANGUAGE = stringPreferencesKey("local_language")
        val REASONING_SOURCE = stringPreferencesKey("reasoning_source")
        val REASONING_LOCAL_MODEL_ID = stringPreferencesKey("reasoning_local_model_id")
        val REASONING_OPENAI_MODEL = stringPreferencesKey("reasoning_openai_model")
        val REASONING_ANTHROPIC_MODEL = stringPreferencesKey("reasoning_anthropic_model")
        val REASONING_GOOGLE_MODEL = stringPreferencesKey("reasoning_google_model")
        val VISION_SOURCE = stringPreferencesKey("vision_source")
        val VISION_LOCAL_MODEL_ID = stringPreferencesKey("vision_local_model_id")
        val VISION_OPENAI_MODEL = stringPreferencesKey("vision_openai_model")
        val VISION_GOOGLE_MODEL = stringPreferencesKey("vision_google_model")
        val VISION_ANTHROPIC_MODEL = stringPreferencesKey("vision_anthropic_model")
        val FALA_SOURCE = stringPreferencesKey("fala_source")
        val FALA_LOCAL_MODEL_ID = stringPreferencesKey("fala_local_model_id")
        val FALA_SPEED = floatPreferencesKey("fala_speed")
        val FALA_CLOUD_VOICE = stringPreferencesKey("fala_cloud_voice")
        val FALA_ELEVENLABS_VOICE = stringPreferencesKey("fala_elevenlabs_voice")
        val FALA_ELEVENLABS_MODEL = stringPreferencesKey("fala_elevenlabs_model")
        val FALA_GOOGLE_VOICE = stringPreferencesKey("fala_google_voice")
        val REASONING_CUSTOM_ENDPOINT = stringPreferencesKey("reasoning_custom_endpoint")
        val REASONING_CUSTOM_HEADER_NAME = stringPreferencesKey("reasoning_custom_header_name")
        val REASONING_CUSTOM_MODEL = stringPreferencesKey("reasoning_custom_model")
        val VISION_CUSTOM_ENDPOINT = stringPreferencesKey("vision_custom_endpoint")
        val VISION_CUSTOM_HEADER_NAME = stringPreferencesKey("vision_custom_header_name")
        val VISION_CUSTOM_MODEL = stringPreferencesKey("vision_custom_model")
        val FALA_CUSTOM_ENDPOINT = stringPreferencesKey("fala_custom_endpoint")
        val FALA_CUSTOM_HEADER_NAME = stringPreferencesKey("fala_custom_header_name")
        val FALA_CUSTOM_MODEL = stringPreferencesKey("fala_custom_model")
        val FALA_CUSTOM_VOICE = stringPreferencesKey("fala_custom_voice")
        val FALA_CUSTOM_FORMAT = stringPreferencesKey("fala_custom_format")
        val COMPUTER_MODE = stringPreferencesKey("computer_mode")
        val COMPUTER_SSH_HOST = stringPreferencesKey("computer_ssh_host")
        val COMPUTER_SSH_PORT = intPreferencesKey("computer_ssh_port")
        val COMPUTER_SSH_USER = stringPreferencesKey("computer_ssh_user")
        val COMPUTER_SSH_AUTH_TYPE = stringPreferencesKey("computer_ssh_auth_type")
        val CUSTOM_MODEL_MIRROR_URL = stringPreferencesKey("custom_model_mirror_url")
        val PROVIDER_ANTHROPIC_MODEL = stringPreferencesKey("provider_anthropic_model")
        val PROVIDER_ANTHROPIC_AUTH_MODE = stringPreferencesKey("provider_anthropic_auth_mode")
        val PROVIDER_OPENAI_MODEL = stringPreferencesKey("provider_openai_model")
        val PROVIDER_OPENAI_AUTH_MODE = stringPreferencesKey("provider_openai_auth_mode")
        val PROVIDER_GOOGLE_MODEL = stringPreferencesKey("provider_google_model")
        val PROVIDER_GOOGLE_AUTH_MODE = stringPreferencesKey("provider_google_auth_mode")
        val PROVIDER_ELEVENLABS_AUTH_MODE = stringPreferencesKey("provider_elevenlabs_auth_mode")
        val PROVIDER_GROQ_MODEL = stringPreferencesKey("provider_groq_model")
        val PROVIDER_GROK_MODEL = stringPreferencesKey("provider_grok_model")
        val OVERLAY_MODE_ENABLED = booleanPreferencesKey("overlay_mode_enabled")
        val KEYBOARD_MODE_ENABLED = booleanPreferencesKey("keyboard_mode_enabled")
        val OVERLAY_RECORDING_MODE = stringPreferencesKey("overlay_recording_mode")
        val KEYBOARD_AUTO_PASTE = booleanPreferencesKey("keyboard_auto_paste") // legado (migração)
        val AUTO_PASTE_IN_FIELD = booleanPreferencesKey("auto_paste_in_field")
        val AUTO_PASTE_ON_FOCUS = booleanPreferencesKey("auto_paste_on_focus")
        val OVERLAY_SIDE = stringPreferencesKey("overlay_side")
        val ORB_SIZE_PX = intPreferencesKey("orb_size_px")
        val TAP_BURST_WINDOW_MS = intPreferencesKey("tap_burst_window_ms")
        val SHORTCUT_1_TAP = stringPreferencesKey("shortcut_1_tap")
        val SHORTCUT_2_TAP = stringPreferencesKey("shortcut_2_tap")
        val SHORTCUT_3_TAP = stringPreferencesKey("shortcut_3_tap")
        val SHORTCUT_OVERFLOW = stringPreferencesKey("shortcut_overflow")
        val HAPTIC_FEEDBACK = booleanPreferencesKey("haptic_feedback") // legado (migração p/ vibration_intensity)
        val VIBRATION_INTENSITY = intPreferencesKey("vibration_intensity")
        val THEME_DARK = booleanPreferencesKey("theme_dark")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val DISCLOSURE_ACCEPTED_VERSION = intPreferencesKey("disclosure_accepted_version")
        val DISCLOSURE_DECLINED = booleanPreferencesKey("disclosure_declined")
        val TRANSCRIPTION_TIMEOUT_MS = intPreferencesKey("transcription_timeout_ms")
        val MAX_RECORDING_SEC = intPreferencesKey("max_recording_sec")
        val KEYBOARD_SCALE_PERCENT = intPreferencesKey("keyboard_scale_percent")
        val KEYBOARD_LANGUAGE = stringPreferencesKey("keyboard_language")
        val KEYBOARD_NUMBER_ROW_ENABLED = booleanPreferencesKey("keyboard_number_row_enabled")
        val PARTIAL_ON_PAUSE = booleanPreferencesKey("partial_on_pause")
        val PARTIAL_VIEW_EDIT = booleanPreferencesKey("partial_view_edit")
        val KEYBOARD_SHOW_WITH_HARD_KEYBOARD = booleanPreferencesKey("keyboard_show_with_hard_keyboard")
        val KEYBOARD_AUTO_CORRECT = booleanPreferencesKey("keyboard_auto_correct")
        val KEYBOARD_SKIN = stringPreferencesKey("keyboard_skin")
        val SKINS_UNLOCKED = booleanPreferencesKey("skins_unlocked")
        val ORB_SKIN = stringPreferencesKey("orb_skin")
        val APP_LANGUAGE = stringPreferencesKey("app_language")
        // Trabalho 3 (shortcut-bars): barras personalizáveis + teclados de atalhos, como JSON.
        val BARS_CONFIG_JSON = stringPreferencesKey("bars_config_json")
        val SHORTCUT_GRIDS_JSON = stringPreferencesKey("shortcut_grids_json")
        // Fluxos (feature flows): a lista de fluxos e os gatilhos que os disparam, como JSON (CodecFluxos).
        val FLOWS_JSON = stringPreferencesKey("flows_json")
        val FLOW_TRIGGERS_JSON = stringPreferencesKey("flow_triggers_json")
        // Orbe do teclado (feature keyboard-orb): configuração própria como JSON (CodecOrbeTeclado).
        val KEYBOARD_ORB_CONFIG_JSON = stringPreferencesKey("keyboard_orb_config_json")
        // Raciocínio local: onde cada modelo roda (CPU/GPU), o aviso de volta para a CPU e a GPU já pronta — ver [LocalBackendPrefs].
        val REASONING_LOCAL_BACKENDS = stringPreferencesKey("reasoning_local_backends")

        fun createSecurePrefs(
            context: Context,
            name: String,
            keyAlias: String = MasterKey.DEFAULT_MASTER_KEY_ALIAS,
        ): SharedPreferences = SecurePrefs.create(context, name, keyAlias)

        /**
         * O mesmo arquivo de `preferencesDataStoreFile(DATASTORE_NAME)`, montado sem tocar no disco: o `filesDir` confere e
         * cria a pasta a cada chamada (StrictMode: leitura de disco na thread principal a cada repositório criado, inclusive
         * ao abrir o teclado), e `filesDir` é sempre `<dataDir>/files`. Quem cria a pasta é o `produceFile` do DataStore, na
         * thread dele.
         */
        fun defaultDataStoreFile(context: Context): File =
            File(context.applicationInfo.dataDir, "files/datastore/$DATASTORE_NAME.preferences_pb")

        fun dataStoreFor(
            file: File,
            scope: CoroutineScope,
        ): DataStore<Preferences> = synchronized(DATASTORES) {
            DATASTORES.getOrPut(file.absolutePath) {
                PreferenceDataStoreFactory.create(
                    scope = scope,
                    produceFile = {
                        file.parentFile?.mkdirs()
                        file
                    },
                )
            }
        }
    }
}

/** O que o agente lê das Settings para o `app_status` — ver [SettingsRepository.agentStatusOnce]. Nada secreto. */
internal class AgentStatusSettings(
    val overlayModeEnabled: Boolean,
    val keyboardModeEnabled: Boolean,
    val keyboardSkin: String,
    val orbSkin: String,
    val reasoningSource: ReasoningSource,
    val reasoningModel: String?,
)

/** O que o teclado precisa para ser montado — ver [SettingsRepository.keyboardSnapshotOnce]. */
data class KeyboardSnapshot(
    val dark: Boolean,
    val scalePercent: Int,
    val language: String,
    val numberRowEnabled: Boolean,
    val skin: String,
    val bars: BarsConfig,
    val grids: List<ShortcutGrid>,
    val vibration: Int,
    val skinsUnlocked: Boolean = false,
    /** `botaoId → fluxoId` dos botões `RunFlow` da barra com gatilho válido; os demais renderizam desabilitados (FLX-36). */
    val botoesDeFluxo: Map<String, String> = emptyMap(),
)

/**
 * A [Config] do transcritor para estas [settings] e [credenciais]: a que o `SettingsRepository.flow()` publica, extraída para o
 * coordinator da orbe e a porta de Ditar dos fluxos usarem a mesma função sobre o instantâneo da etapa (FLX-26) — mesma `Settings`,
 * mesma `Config`. Pura: as credenciais vêm de quem chama (o cofre, na hora da chamada).
 */
internal fun configDe(settings: Settings, credenciais: SettingsCredentialValues = SettingsCredentialValues()): Config =
    when (val eff = settings.effectiveConfigFor(credentials = credenciais)) {
        is EffectiveConfig.Ready -> eff.config
        // Sem endpoint nem token: o RoutingTranscriber manda para o :inference (NM-T9, PRV-01).
        is EffectiveConfig.Local -> Config(
            timeoutMs = Config.localTimeoutMs(settings.transcriptionTimeoutMs.toLong()),
            local = eff,
        )
        EffectiveConfig.Unavailable -> Config()
    }
