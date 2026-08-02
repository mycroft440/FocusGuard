package com.focusguard.utils

import com.google.common.truth.Truth.assertThat
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Test

class BatteryOptimizationPermissionManifestTest {

    @Test
    fun `manifest declares permission required by direct battery optimization request`() {
        val manifest = File("src/main/AndroidManifest.xml")
        val document = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(manifest)

        val declaredPermissions = document
            .getElementsByTagName("uses-permission")
            .let { nodes ->
                (0 until nodes.length).map { index ->
                    nodes.item(index).attributes.getNamedItemNS(
                        "http://schemas.android.com/apk/res/android",
                        "name"
                    )?.nodeValue
                }
            }

        assertThat(declaredPermissions).contains(
            "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"
        )
    }
}
