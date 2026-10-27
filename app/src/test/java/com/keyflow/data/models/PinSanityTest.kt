package com.keyflow.data.models

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.util.Random
import org.junit.Test

/**
 * Impede a volta de pin inventado no catálogo (2026-10-24: 15 entradas com repositório, commit e SHA-256 digitados à
 * mão — nenhuma era baixável). Aqui, sem rede: a FORMA dos pins. Quem confere se eles existem na API do Hugging Face
 * é o `scripts/verify-model-catalog.py` (à mão e no workflow `catalogo-modelos.yml`).
 */
class PinSanityTest {

    private val entries = ModelCatalog.MODELS.flatMap { model -> model.files.map { model to it } }

    // ── o catálogo de verdade ────────────────────────────────────────────────────────────────────────

    @Test
    fun `the catalog has files, so the checks below are not vacuous`() {
        assertThat(entries.size).isAtLeast(12)
    }

    @Test
    fun `every url pins a huggingface commit of 40 lowercase hex and ends with the file name`() {
        entries.forEach { (model, file) ->
            assertWithMessage("${model.id}/${file.fileName}: URL sem commit fixo de 40 hex")
                .that(PinSanity.commitOf(file.url)).isNotNull()
            assertWithMessage("${model.id}: a URL deve terminar no nome do arquivo").that(file.url).endsWith("/${file.fileName}")
            assertWithMessage("${model.id}/${file.fileName}: sha256 deve ter 64 hex minúsculos")
                .that(file.sha256).matches("[0-9a-f]{64}")
            assertThat(file.bytes).isGreaterThan(0L)
        }
    }

    @Test
    fun `no commit or sha256 in the catalog looks typed by hand`() {
        entries.forEach { (model, file) ->
            val commit = checkNotNull(PinSanity.commitOf(file.url)) { "${model.id}/${file.fileName}: URL sem commit" }
            assertWithMessage("${model.id}/${file.fileName}: commit $commit parece inventado")
                .that(PinSanity.suspicion(commit)).isNull()
            assertWithMessage("${model.id}/${file.fileName}: sha256 ${file.sha256} parece inventado")
                .that(PinSanity.suspicion(file.sha256)).isNull()
        }
    }

    /** O SHA-256 inventado de texto copiava o fim do real do Qwen3 trocando o começo: dois hashes de verdade não se parecem. */
    @Test
    fun `different sha256 never share a long prefix or suffix`() {
        val hashes = entries.map { it.second.sha256 }.distinct()
        for (i in hashes.indices) for (j in i + 1 until hashes.size) {
            assertWithMessage("${hashes[i]} e ${hashes[j]} começam iguais")
                .that(PinSanity.sharedPrefix(hashes[i], hashes[j])).isAtMost(MAX_SHARED)
            assertWithMessage("${hashes[i]} e ${hashes[j]} terminam iguais")
                .that(PinSanity.sharedSuffix(hashes[i], hashes[j])).isAtMost(MAX_SHARED)
        }
    }

    /** Mesmo SHA-256 em dois arquivos é o mesmo conteúdo (o `tokens.txt` dos dois Whisper): então o tamanho também é o mesmo. */
    @Test
    fun `the same sha256 always comes with the same size`() {
        entries.groupBy({ it.second.sha256 }, { it.second.bytes }).forEach { (sha, sizes) ->
            assertWithMessage("sha256 $sha com tamanhos diferentes").that(sizes.distinct()).hasSize(1)
        }
    }

    // ── o detector: tem de pegar o que foi inventado e deixar passar o que é de verdade ───────────────

    /** Os commits que o catálogo antigo trazia, ao pé da letra (nenhum existe no Hugging Face). */
    private val fabricatedCommits = listOf(
        "c1b2a3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0",
        "d2c3b4a5f6e7d8c9b0a1f2e3d4c5b6a7f8e9d0c1",
        "e3d4c5b6a7f8e9d0c1b2a3f4e5d6c7b8a9f0e1d2",
        "f4e5d6c7b8a9f0e1d2c3b4a5f6e7d8c9b0a1f2e3",
        "a5f6e7d8c9b0a1f2e3d4c5b6a7f8e9d0c1b2a3f4",
        "b6a7f8e9d0c1b2a3f4e5d6c7b8a9f0e1d2c3b4a5",
        "6a2b8e3d0c4f1e5a8b7c9d0e2f4a6b8c0d2e4f6a",
        "7b3c9d1e5a8b2f4c6e0d8a4f2b6c9e1a3d5f7b9c",
        "8c4e0f2b6a9d3e1f5b7c8a0d2e4f6a8b1c3d5e7f",
        "1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b",
        "2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c",
        "9d1e5a8b2f4c6e0d8a4f2b6c9e1a3d5f7b9c1e3a",
        "0e2f4a6b8c0d2e4f6a8b1c3d5e7f9a1b3c5d7e9f",
        "1f3a5c7e9b0d2f4a6c8e0b2d4f6a8c0e2b4d6f8a",
        "5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b3c4d",
    )

    /** Os SHA-256 sequenciais do catálogo antigo (visão e voz), ao pé da letra. */
    private val fabricatedSha256 = listOf(
        "4a8f9c1e2b3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f",
        "b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2",
        "c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2c3d4",
        "3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0d1e2f3a4b",
        "4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c",
        "d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5",
        "6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e",
        "e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6",
        "f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6a7",
    )

    /** O SHA-256 de verdade do Qwen3 0.6B e os de texto inventados em cima dele (só trocaram o começo). */
    private val realQwenSha = "555579ff2f4fd13379abe69c1c3ab5200f7338bc92471557f1d6614a6e5ab0b4"
    private val fabricatedFromQwen = listOf(
        "666679ff2f4fd13379abe69c1c3ab5200f7338bc92471557f1d6614a6e5ab0b4",
        "777779ff2f4fd13379abe69c1c3ab5200f7338bc92471557f1d6614a6e5ab0b4",
        "888879ff2f4fd13379abe69c1c3ab5200f7338bc92471557f1d6614a6e5ab0b4",
        "999979ff2f4fd13379abe69c1c3ab5200f7338bc92471557f1d6614a6e5ab0b4",
        "aaaa79ff2f4fd13379abe69c1c3ab5200f7338bc92471557f1d6614a6e5ab0b4",
        "bbbb79ff2f4fd13379abe69c1c3ab5200f7338bc92471557f1d6614a6e5ab0b4",
    )

    @Test
    fun `the detector flags every commit the old catalog made up`() {
        fabricatedCommits.forEach { assertWithMessage("commit $it passou").that(PinSanity.suspicion(it)).isNotNull() }
    }

    @Test
    fun `the detector flags the sequential sha256 the old catalog made up`() {
        fabricatedSha256.forEach { assertWithMessage("sha256 $it passou").that(PinSanity.suspicion(it)).isNotNull() }
    }

    @Test
    fun `hashes copied from a real one are caught by the shared suffix rule`() {
        fabricatedFromQwen.forEach {
            assertThat(PinSanity.sharedSuffix(it, realQwenSha)).isAtLeast(56)
            assertThat(PinSanity.sharedSuffix(it, realQwenSha)).isGreaterThan(MAX_SHARED)
        }
    }

    @Test
    fun `the detector flags the shapes it was built for`() {
        assertThat(PinSanity.suspicion("0".repeat(40))).contains("mesmo caractere")
        assertThat(PinSanity.suspicion("0123456789abcdef".repeat(2) + "01234567")).isNotNull()
        assertThat(PinSanity.suspicion("fedcba9876543210".repeat(2) + "fedcba98")).isNotNull()
        assertThat(PinSanity.suspicion("abcabcabcabcabcabcabcabcabcabcabcabcabca")).isNotNull()
        assertThat(PinSanity.suspicion("1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b")).isNotNull()
    }

    @Test
    fun `the detector accepts the real pins the catalog uses today`() {
        // Valores lidos da API do HF e conferidos por `scripts/verify-model-catalog.py`.
        listOf(
            "bb53ee204431c90d314c1cc08d28d23e5b7927cc",
            "8f3c18b358db4d1f2fc1eae49d75cd20989e4309",
            "2bda32ec70b097a55adaa07d9a7173915b43cc78",
            "b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1",
            "a3c5d805ae362dff7f580bc25f2dfb9a5a7eaa76",
            realQwenSha,
            "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
            "acfc2b4456377e15d04f0243af540b7fe7c992f8d898d751cf134c3a55fd2247",
            "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d",
        ).forEach { assertWithMessage("pin real $it foi marcado").that(PinSanity.suspicion(it)).isNull() }
    }

    /** Falso alarme custa um dev olhando um hash legítimo: tem de ser raro. Semente fixa — o resultado não varia. */
    @Test
    fun `the detector almost never flags random hex`() {
        val random = Random(20_261_024L)
        fun hex(length: Int) = buildString { repeat(length) { append("0123456789abcdef"[random.nextInt(16)]) } }
        val samples = List(10_000) { hex(40) } + List(10_000) { hex(64) }
        val flagged = samples.filter { PinSanity.suspicion(it) != null }
        assertWithMessage("falsos alarmes em ${samples.size} hashes aleatórios: $flagged")
            .that(flagged.size).isAtMost(samples.size / 1_000)
    }

    private companion object {
        /** Dois hashes de verdade com 10 hex iguais no começo ou no fim: ~1 em 4 bilhões por par. */
        const val MAX_SHARED = 10
    }
}
