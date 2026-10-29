package com.keyflow.data.models

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/** O Supertonic pede a OpenRAIL-M antes do download. O VITS não pede. */
class VoiceLicenseGateTest {

    @Test
    fun `supertonic waits for acceptance and vits starts immediately`() {
        var calls = 0
        val blocked = VoiceLicenseGate.begin(VoiceLicenseGate.SUPERTONIC_ID, accepted = false) {
            calls += 1
            StartResult.Started
        }
        assertThat(blocked).isEqualTo(StartResult.NeedsLicenseAcceptance("supertonic-3"))
        assertThat(calls).isEqualTo(0)

        val allowed = VoiceLicenseGate.begin(VoiceLicenseGate.SUPERTONIC_ID, accepted = true) { StartResult.Started }
        assertThat(allowed).isEqualTo(StartResult.Started)

        val vits = VoiceLicenseGate.begin("vits-coqui-pt", accepted = false) { StartResult.Started }
        assertThat(vits).isEqualTo(StartResult.Started)
    }

    @Test
    fun `acceptance is one id per line and survives a second write`() {
        val file = File.createTempFile("voice-license", ".txt")
        file.delete()
        val store = VoiceLicenseAcceptance(file)
        assertThat(store.isAccepted("supertonic-3")).isFalse()
        store.accept("supertonic-3")
        store.accept("supertonic-3")
        assertThat(store.isAccepted("supertonic-3")).isTrue()
        assertThat(file.readLines().filter { it.isNotBlank() }).containsExactly("supertonic-3")
    }

    @Test
    fun `the asset is the same OpenRAIL text as app licenses, with attachment A`() {
        val asset = licenseFile("src/main/assets/licenses/OpenRAIL-M_supertonic-3.txt")
        val copy = licenseFile("licenses/OpenRAIL-M_supertonic-3.txt")
        assertThat(asset.readText()).isEqualTo(copy.readText())
        assertThat(asset.readText()).contains("Attachment A")
        assertThat(asset.readText()).contains("5. Use-based restrictions")
        assertThat(VoiceLicenseGate.ASSET).isEqualTo("licenses/OpenRAIL-M_supertonic-3.txt")
    }

    private fun licenseFile(relative: String): File =
        File(relative).takeIf { it.isFile } ?: File("app/$relative")
}
