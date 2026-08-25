package com.focusguard.focusmode

import com.google.common.truth.Truth.assertThat
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Test
import org.w3c.dom.Element

class FocusModeNavigationManifestTest {

    private val androidNamespace = "http://schemas.android.com/apk/res/android"

    @Test
    fun `manifest contains disabled temporary home alias targeting main activity`() {
        val document = parseXml(File("src/main/AndroidManifest.xml"))
        val aliases = document.getElementsByTagName("activity-alias").let { nodes ->
            (0 until nodes.length).map { nodes.item(it) as Element }
        }
        val alias = aliases.single {
            it.getAttributeNS(androidNamespace, "name") == ".focusmode.FocusModeHomeActivity"
        }

        assertThat(alias.getAttributeNS(androidNamespace, "enabled")).isEqualTo("false")
        assertThat(alias.getAttributeNS(androidNamespace, "exported")).isEqualTo("true")
        assertThat(alias.getAttributeNS(androidNamespace, "targetActivity"))
            .isEqualTo(".MainActivity")

        val actions = alias.getElementsByTagName("action").let { nodes ->
            (0 until nodes.length).map {
                (nodes.item(it) as Element).getAttributeNS(androidNamespace, "name")
            }
        }
        val categories = alias.getElementsByTagName("category").let { nodes ->
            (0 until nodes.length).map {
                (nodes.item(it) as Element).getAttributeNS(androidNamespace, "name")
            }
        }

        assertThat(actions).contains("android.intent.action.MAIN")
        assertThat(categories).containsAtLeast(
            "android.intent.category.HOME",
            "android.intent.category.DEFAULT"
        )
    }

    @Test
    fun `accessibility metadata can filter navigation key stream`() {
        val root = parseXml(File("src/main/res/xml/accessibility_service_config.xml"))
            .documentElement

        assertThat(root.getAttributeNS(androidNamespace, "canRequestFilterKeyEvents"))
            .isEqualTo("true")
        assertThat(root.getAttributeNS(androidNamespace, "accessibilityFlags"))
            .contains("flagRequestFilterKeyEvents")
    }

    private fun parseXml(file: File) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
    }.newDocumentBuilder().parse(file)
}
