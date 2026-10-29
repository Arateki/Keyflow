package com.keyflow.bancada.pontuacao

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** BPT-01/11: a normalização e as distâncias com vetores escritos à mão. */
class NormalizacaoEDistanciasTest {

    @Test
    fun `normalization lowercases drops punctuation and keeps number separators and apostrophes`() {
        assertThat(Normalizacao.palavras("Oi, Pessoal!  Tudo   bem?")).containsExactly("oi", "pessoal", "tudo", "bem").inOrder()
        assertThat(Normalizacao.palavras("R$ 1.500,50 às 14:30 em 2026-10-28")).containsExactly("r", "1.500,50", "às", "14:30", "em", "2026-10-28").inOrder()
        assertThat(Normalizacao.palavras("don’t d'água")).containsExactly("don't", "d'água").inOrder()
        assertThat(Normalizacao.palavras("guarda-chuva")).containsExactly("guarda", "chuva").inOrder()
        assertThat(Normalizacao.texto("")).isEmpty()
    }

    @Test
    fun `normalization composes accents so the same word is the same word`() {
        val decomposto = "café" // e + acento combinante
        assertThat(Normalizacao.texto(decomposto)).isEqualTo(Normalizacao.texto("café"))
    }

    @Test
    fun `tokens know the sign that follows them and whether they open a sentence`() {
        val fichas = Normalizacao.fichas("Oi, tudo bem? Sim.\nOk")
        assertThat(fichas.map { it.normalizada }).containsExactly("oi", "tudo", "bem", "sim", "ok").inOrder()
        assertThat(fichas.map { it.sinal }).containsExactly(',', null, '?', '.', null).inOrder()
        assertThat(fichas.map { it.maiuscula }).containsExactly(true, false, false, true, true).inOrder()
        assertThat(fichas.map { it.inicioDeFrase }).containsExactly(true, false, false, true, true).inOrder()
    }

    @Test
    fun `levenshtein over words and characters`() {
        assertThat(Levenshtein.distancia(listOf("a", "b", "c"), listOf("a", "b", "c"))).isEqualTo(0)
        assertThat(Levenshtein.distancia("kitten".toList(), "sitting".toList())).isEqualTo(3)
        assertThat(Levenshtein.distancia(emptyList<String>(), listOf("a", "b"))).isEqualTo(2)
        assertThat(Levenshtein.distancia(listOf("a", "b"), emptyList())).isEqualTo(2)
    }

    @Test
    fun `wer by hand`() {
        val esperado = listOf("o", "gato", "subiu", "no", "telhado") // 5 palavras
        assertThat(Wer.taxa(esperado, esperado)).isEqualTo(0.0)
        assertThat(Wer.taxa(esperado, listOf("o", "gato", "subiu", "no", "muro"))).isWithin(1e-12).of(0.2) // 1 substituição
        assertThat(Wer.taxa(esperado, listOf("o", "gato", "subiu"))).isWithin(1e-12).of(0.4) // 2 remoções
        assertThat(Wer.taxa(esperado, listOf("o", "gato", "subiu", "no", "telhado", "ontem", "à", "noite", "também"))).isWithin(1e-12).of(0.8) // 4 inserções
        assertThat(Wer.nota(esperado, List(20) { "x" })).isEqualTo(0.0) // taxa passa de 1: a nota para em 0
        assertThat(Wer.taxa(emptyList(), emptyList())).isEqualTo(0.0)
        assertThat(Wer.taxa(emptyList(), listOf("a"))).isEqualTo(1.0)
    }

    @Test
    fun `cer by hand`() {
        assertThat(Cer.taxa("casa", "casa")).isEqualTo(0.0)
        assertThat(Cer.taxa("casa", "cása")).isWithin(1e-12).of(0.25)
        assertThat(Cer.nota("abcd", "abxd")).isWithin(1e-12).of(0.75)
        assertThat(Cer.taxa("", "")).isEqualTo(0.0)
    }

    @Test
    fun `f1 over multisets`() {
        assertThat(F1.multiconjunto(listOf("a", "b"), listOf("a", "b"))).isEqualTo(1.0)
        assertThat(F1.multiconjunto(emptyList<String>(), emptyList())).isEqualTo(1.0)
        assertThat(F1.multiconjunto(listOf("a"), emptyList())).isEqualTo(0.0)
        assertThat(F1.multiconjunto(emptyList(), listOf("a"))).isEqualTo(0.0)
        // esperado {a,b,c,d}, obtido {a,b,x}: tp=2, P=2/3, R=2/4 -> F1 = 2*(2/3)(1/2)/(2/3+1/2) = 4/7
        assertThat(F1.multiconjunto(listOf("a", "b", "c", "d"), listOf("a", "b", "x"))).isWithin(1e-12).of(4.0 / 7.0)
        // multiconjunto: a repetição conta pela interseção
        assertThat(F1.multiconjunto(listOf("a", "a"), listOf("a"))).isWithin(1e-12).of(2.0 / 3.0)
    }

    @Test
    fun `chrf is one for identical texts and zero for disjoint ones`() {
        assertThat(ChrF.nota("a casa é azul", "a casa é azul")).isEqualTo(1.0)
        assertThat(ChrF.nota("abc", "xyz")).isEqualTo(0.0)
        assertThat(ChrF.nota("", "")).isEqualTo(1.0)
        assertThat(ChrF.nota("abc", "")).isEqualTo(0.0)
        assertThat(ChrF.nota("a casa é azul", "a casa azul")).isGreaterThan(ChrF.nota("a casa é azul", "o carro vermelho"))
    }

    @Test
    fun `chrf by hand for a one-letter difference of two letters`() {
        // ref "ab", hip "ac": n=1 -> comuns 1 (a); P=1/2, R=1/2. n=2 -> "ab" x "ac": 0 comuns. n>=3: sem n-gramas, ignorados.
        // P médio = (1/2 + 0)/2 = 1/4, R médio = 1/4; F2 = 5*P*R/(4P+R) = 5*(1/16)/(5/4) = 1/4.
        assertThat(ChrF.nota("ab", "ac")).isWithin(1e-12).of(0.25)
    }
}
