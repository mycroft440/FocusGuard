package com.focusguard.ui

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class OfflineBookAssetsTest {
    private val bookAssets = File("src/main/assets/easypeasy")

    @Test
    fun offlineSnapshotContainsTheCompleteBook() {
        val snapshot = File(bookAssets, "snapshot.json").readText()
        val searchIndex = File(bookAssets, "search-index.json").readText()
        val htmlPages = bookAssets.listFiles().orEmpty().filter { it.extension == "html" }
        val pageCount = requireNotNull(
            Regex("\"pages\"\\s*:\\s*(\\d+)")
                .find(snapshot)
                ?.groupValues
                ?.get(1)
                ?.toInt()
        )

        assertThat(snapshot).doesNotContain("\"status\": \"placeholder\"")
        assertThat(pageCount).isAtLeast(34)
        assertThat(searchIndex.length).isGreaterThan(80_000)
        assertThat(htmlPages.size).isAtLeast(34)
        assertThat(File(bookAssets, "index.html").isFile).isTrue()
        assertThat(File(bookAssets, "assets/css/reader.css").isFile).isTrue()
        assertThat(File(bookAssets, "assets/js/reader.js").isFile).isTrue()
    }

    @Test
    fun redistributionNoticesAreBundled() {
        assertThat(File(bookAssets, "licenses/NOTICE.md").isFile).isTrue()
        assertThat(File(bookAssets, "licenses/LICENSE-CONTENT").isFile).isTrue()
        assertThat(File(bookAssets, "licenses/LICENSE-CODE").isFile).isTrue()
    }
}
