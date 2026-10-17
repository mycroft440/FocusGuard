package com.focusguard.ui

import com.google.common.truth.Truth.assertThat
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Test
import org.w3c.dom.Element

/** Um toque duplo no botão não pode empilhar duas cópias destas telas. */
class ButtonLaunchedActivitiesManifestTest {

    private val androidNamespace = "http://schemas.android.com/apk/res/android"

    @Test
    fun `screens opened from a button are single top`() {
        val document = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val activities = document.getElementsByTagName("activity").let { nodes ->
            (0 until nodes.length).map { nodes.item(it) as Element }
        }.associateBy { it.getAttributeNS(androidNamespace, "name") }

        listOf(
            ".ui.CreateSessionActivity",
            ".ui.PermissionsActivity",
            ".ui.MasterPasswordActivity",
            ".ui.RemoveAllBlocksActivity"
        ).forEach { name ->
            assertThat(activities.getValue(name).getAttributeNS(androidNamespace, "launchMode"))
                .isEqualTo("singleTop")
        }
    }
}
