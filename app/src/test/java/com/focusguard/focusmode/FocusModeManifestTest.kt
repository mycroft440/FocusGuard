package com.focusguard.focusmode

import com.google.common.truth.Truth.assertThat
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Test
import org.w3c.dom.Element

class FocusModeManifestTest {

    @Test
    fun `manifest declares timed service notification listener phone visibility and direct boot`() {
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

        // MainActivity must not auto-enter Lock Task merely because another
        // FocusGuard feature temporarily allowlisted the package. Focus Mode
        // launches it into Lock Task explicitly only while its persisted session
        // is active.
        val activities = document.getElementsByTagName("activity").let { nodes ->
            (0 until nodes.length).map { index -> nodes.item(index) as Element }
        }
        val mainActivity = activities.single {
            it.getAttributeNS(androidNamespace, "name") == ".MainActivity"
        }
        assertThat(mainActivity.hasAttributeNS(androidNamespace, "lockTaskMode")).isFalse()

        val receivers = document.getElementsByTagName("receiver").let { nodes ->
            (0 until nodes.length).map { index -> nodes.item(index) as Element }
        }
        val bootReceiver = receivers.single {
            it.getAttributeNS(androidNamespace, "name") == ".receiver.BootReceiver"
        }
        assertThat(bootReceiver.hasAttributeNS(androidNamespace, "directBootAware")).isFalse()
        val directBootReceiver = receivers.single {
            it.getAttributeNS(androidNamespace, "name") == ".receiver.DirectBootReceiver"
        }
        assertThat(directBootReceiver.getAttributeNS(androidNamespace, "directBootAware"))
            .isEqualTo("true")

        val actions = document.getElementsByTagName("action").let { nodes ->
            (0 until nodes.length).mapNotNull { index ->
                (nodes.item(index) as? Element)
                    ?.getAttributeNS(androidNamespace, "name")
            }
        }
        assertThat(actions).containsAtLeast(
            "android.intent.action.DIAL",
            "android.intent.action.SENDTO",
            "android.intent.action.BOOT_COMPLETED",
            "android.intent.action.LOCKED_BOOT_COMPLETED"
        )
    }
}
