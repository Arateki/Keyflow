package com.keyflow.data.models

import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test

/** Evidência externa congelada: a API do HF é a origem dos pins, não os literais do catálogo. */
class NewReasoningCandidatesTest {
    private val evidence = JSONObject(checkNotNull(javaClass.getResource("/modelos-novos.json")).readText())

    @Test
    fun `candidate files match the pinned upstream LFS evidence and are listed in every build though unvalidated`() {
        val entries = evidence.getJSONArray("models")
        val listed = ModelCatalog.TEXT.map { it.id }
        repeat(entries.length()) { i ->
            val upstream = entries.getJSONObject(i)
            val model = checkNotNull(ModelCatalog.byId(upstream.getString("id")))
            assertThat(model.validatedOnDevice).isFalse()
            assertThat(listed).contains(model.id)
            val file = model.files.single()
            assertThat(file.fileName).isEqualTo(upstream.getString("file"))
            assertThat(file.bytes).isEqualTo(upstream.getLong("bytes"))
            assertThat(file.sha256).isEqualTo(upstream.getString("sha256"))
            assertThat(file.url).isEqualTo(upstream.getString("url"))
            assertThat(file.url).contains("/resolve/${upstream.getString("commit")}/")
            assertThat(upstream.getInt("range_status")).isEqualTo(206)
        }
    }

    @Test
    fun `models whose declared minimum is above the app runtime are not offered`() {
        val rejected = evidence.getJSONArray("excluded")
        repeat(rejected.length()) { i ->
            val repo = rejected.getJSONObject(i).getString("repo")
            assertThat(ModelCatalog.TEXT_ALL.flatMap { it.files }.any { it.url.contains("/$repo/") }).isFalse()
        }
    }
}
