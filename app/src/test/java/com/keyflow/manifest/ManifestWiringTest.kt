package com.keyflow.manifest

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class ManifestWiringTest {

    @Test
    fun `manifest declares final runtime permissions`() {
        val manifest = parseXml(File("src/main/AndroidManifest.xml")).documentElement

        val permissions = manifest
            .elements("uses-permission")
            .map { it.androidAttr("name") }

        assertThat(permissions).containsAtLeast(
            "android.permission.INTERNET",
            "android.permission.SYSTEM_ALERT_WINDOW",
            "android.permission.RECORD_AUDIO",
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_MICROPHONE",
            "android.permission.POST_NOTIFICATIONS",
        )
    }

    @Test
    fun `manifest wires production ime overlay service and network security config`() {
        val manifest = parseXml(File("src/main/AndroidManifest.xml")).documentElement
        val application = manifest.elements("application").single()
        val services = application.elements("service")

        val ime = services.single { it.androidAttr("name") == ".ime.KeyFlowKeyboardService" }
        assertThat(ime.androidAttr("permission")).isEqualTo("android.permission.BIND_INPUT_METHOD")
        assertThat(ime.elements("meta-data").single().androidAttr("resource")).isEqualTo("@xml/method_keyboard")

        val overlay = services.single { it.androidAttr("name") == ".overlay.OverlayService" }
        assertThat(overlay.androidAttr("exported")).isEqualTo("false")
        assertThat(overlay.androidAttr("foregroundServiceType")).isEqualTo("microphone")

        assertThat(application.androidAttr("networkSecurityConfig"))
            .isEqualTo("@xml/network_security_config")
    }

    @Test
    fun `network security config permits cleartext localhost and emulator host only`() {
        val config = parseXml(File("src/main/res/xml/network_security_config.xml")).documentElement
        val cleartextDomains = config
            .elements("domain-config")
            .filter { it.attr("cleartextTrafficPermitted") == "true" }
            .flatMap { domainConfig -> domainConfig.elements("domain").map { it.textContent.trim() } }

        assertThat(cleartextDomains).containsAtLeast("localhost", "10.0.2.2")
    }

    private fun parseXml(file: File) = DocumentBuilderFactory
        .newInstance()
        .apply { isNamespaceAware = true }
        .newDocumentBuilder()
        .parse(file)

    private fun Element.elements(name: String): List<Element> {
        val nodes = getElementsByTagName(name)
        return (0 until nodes.length)
            .map { nodes.item(it) }
            .filterIsInstance<Element>()
    }

    private fun Element.attr(name: String): String = getAttribute(name)

    private fun Element.androidAttr(name: String): String =
        getAttributeNS("http://schemas.android.com/apk/res/android", name)
}
