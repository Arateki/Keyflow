package com.keyflow.computador.app

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** CMP-33: o manifesto declara a permissão, o tipo `specialUse` e a `property` com a justificativa; o WakeLock é permissão normal. */
class ManifestSessoesTest {
    private val manifesto = File("src/main/AndroidManifest.xml")
    private val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(manifesto)
    private val ns = "http://schemas.android.com/apk/res/android"

    private fun servico(): Element {
        val servicos = doc.getElementsByTagName("service")
        return (0 until servicos.length).map { servicos.item(it) as Element }.firstOrNull { it.getAttributeNS(ns, "name") == ".computador.app.SessoesService" }
            ?: error("o SessoesService não está no manifesto")
    }

    @Test fun permissaoDoTipoSpecialUse() {
        val nomes = (0 until doc.getElementsByTagName("uses-permission").length).map { (doc.getElementsByTagName("uses-permission").item(it) as Element).getAttributeNS(ns, "name") }
        assertTrue(nomes.toString(), "android.permission.FOREGROUND_SERVICE_SPECIAL_USE" in nomes)
        assertTrue("android.permission.FOREGROUND_SERVICE" in nomes)
    }

    @Test fun tipoSpecialUseEServicoNaoExportado() {
        val s = servico()
        assertEquals("specialUse", s.getAttributeNS(ns, "foregroundServiceType"))
        assertEquals("false", s.getAttributeNS(ns, "exported"))
    }

    @Test fun propertyComASubtipoDaJustificativa() {
        val props = servico().getElementsByTagName("property")
        val p = (0 until props.length).map { props.item(it) as Element }.firstOrNull { it.getAttributeNS(ns, "name") == "android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" }
        assertNotNull("falta a property do subtipo", p)
        assertEquals("Keeps user-started interactive SSH terminal sessions connected while the app is in the background", p!!.getAttributeNS(ns, "value"))
    }

    @Test fun wakeLockEPermissaoNormalDeclarada() {
        val nomes = (0 until doc.getElementsByTagName("uses-permission").length).map { (doc.getElementsByTagName("uses-permission").item(it) as Element).getAttributeNS(ns, "name") }
        assertTrue("android.permission.WAKE_LOCK" in nomes)
    }

    @Test fun oServicoDaOrbeContinuaComSeusTipos() {
        val servicos = doc.getElementsByTagName("service")
        val orbe = (0 until servicos.length).map { servicos.item(it) as Element }.first { it.getAttributeNS(ns, "name").endsWith("OverlayService") }
        assertTrue(orbe.getAttributeNS(ns, "foregroundServiceType").contains("microphone"))
        assertTrue(!orbe.getAttributeNS(ns, "foregroundServiceType").contains("specialUse"))
    }

    @Test fun checklistDaPlayTemAJustificativa() {
        val txt = File("../PLAYSTORE_CHECKLIST.md").readText()
        assertTrue(txt.contains("FOREGROUND_SERVICE_SPECIAL_USE") && txt.contains("PROPERTY_SPECIAL_USE_FGS_SUBTYPE") && txt.contains("Policy > App content"))
    }

    @Test fun regrasDeBackupExcluemOKnownHostsEOCofre() {
        for (f in listOf("backup_rules.xml", "data_extraction_rules.xml")) {
            val t = File("src/main/res/xml/$f").readText()
            assertTrue("$f sem known_hosts", t.contains("path=\"known_hosts\""))
            assertTrue("$f sem o cofre", t.contains("path=\"keyflow_auth.xml\""))
        }
    }
}
