package com.focusguard.focusmode

import com.google.common.truth.Truth.assertThat
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Test
import org.w3c.dom.Element

class FocusModeManifestTest {

    @Test
    fun `manifest declares timed service notification listener and phone visibility`() {
        val document = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val androidNamespace = "http://schemas.android.com/apk/res/android"

        val services = document.getElementsByTagName("service").let { nodes ->
            (0 until nodes.length).map { index -> nodes.item(index) as Element }
        }
        val serviceNames = services.map {
            it.getAttributeNS(androidNamespace, "name")
        }
        assertThat(serviceNames).containsAtLeast(
            ".service.FocusModeForegroundService",
            ".service.FocusModeNotificationService"
        )

        val listener = services.single {
            it.getAttributeNS(androidNamespace, "name") ==
                ".service.FocusModeNotificationService"
        }
        assertThat(listener.getAttributeNS(androidNamespace, "permission"))
            .isEqualTo("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE")

        val actions = document.getElementsByTagName("action").let { nodes ->
            (0 until nodes.length).mapNotNull { index ->
                (nodes.item(index) as? Element)
                    ?.getAttributeNS(androidNamespace, "name")
            }
        }
        assertThat(actions).containsAtLeast(
            "android.intent.action.DIAL",
            "android.intent.action.SENDTO"
        )
    }
}
