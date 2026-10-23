package com.keyflow.data

import com.google.common.truth.Truth.assertThat
import com.keyflow.core.FalaSource
import com.keyflow.core.ReasoningSource
import com.keyflow.core.TranscriptionSource
import com.keyflow.core.VisionSource
import org.junit.Test

class SettingsCreditsFallbackTest {

    @Test
    fun `legacy credits choices fall back to each capability default`() {
        val restored = Settings(
            source = TranscriptionSource.CREDITS,
            reasoningSource = ReasoningSource.CREDITS,
            visionSource = VisionSource.CREDITS,
            falaSource = FalaSource.CREDITS,
        ).withoutCredits()

        assertThat(restored.source).isEqualTo(TranscriptionSource.CUSTOM)
        assertThat(restored.reasoningSource).isEqualTo(ReasoningSource.LOCAL)
        assertThat(restored.visionSource).isEqualTo(VisionSource.LOCAL)
        assertThat(restored.falaSource).isEqualTo(FalaSource.LOCAL)
    }

    @Test
    fun `available choices are not changed by the compatibility fallback`() {
        val settings = Settings(
            source = TranscriptionSource.OPENAI,
            reasoningSource = ReasoningSource.CUSTOM,
            visionSource = VisionSource.OPENAI,
            falaSource = FalaSource.GOOGLE,
        )

        assertThat(settings.withoutCredits()).isEqualTo(settings)
    }
}
