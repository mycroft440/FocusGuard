package com.focusguard.ui

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class OfflineBookAssetsTest {
    private val bookAssets = File("src/main/assets/easypeasy")
    private val creatorBookAssets = File("src/main/assets/creator-instructions")
    private val translationAssets = File(bookAssets, "translations")

    @Test
    fun creatorInstructionsBookIsBundledBeforeContentIsWritten() {
        val index = File(creatorBookAssets, "index.html")
        val source = index.readText()

        assertThat(index.isFile).isTrue()
        assertThat(source).contains("Instruções do Criador")
        assertThat(source).contains("Tudo que existe para sair da pornografia começa aqui")
        assertThat(source).contains("creator-instructions-progress")
        assertThat(source).doesNotContain("https://")
        assertThat(source).doesNotContain("http://")
    }

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
    fun htmlTranslationsAreBundledForOfflineAccess() {
        val expectedLanguageCodes = listOf(
            "de", "en", "es", "it", "hu", "nl", "pl", "ro",
            "sq", "so", "sv", "tr", "ru", "uk", "ko"
        )
        val multiPageLanguageCodes = setOf(
            "de", "en", "it", "nl", "pl", "ro",
            "sq", "so", "sv", "ru", "uk", "ko"
        )
        val catalog = File(translationAssets, "catalog.json").readText()
        val bundledPdfs = translationAssets
            .walkTopDown()
            .filter { it.isFile && it.extension.equals("pdf", ignoreCase = true) }
            .toList()

        assertThat(bundledPdfs).isEmpty()

        expectedLanguageCodes.forEach { languageCode ->
            val languageDirectory = File(translationAssets, languageCode)
            val entrypoint = File(languageDirectory, "index.html")
            val snapshot = File(languageDirectory, "snapshot.json").readText()
            val offlineDocument = requireNotNull(
                Regex("\"offlineDocument\"\\s*:\\s*\"([^\"]+)\"")
                    .find(snapshot)
                    ?.groupValues
                    ?.get(1)
            )
            val primaryHtml = File(languageDirectory, offlineDocument)
            val mirroredHtml = File(languageDirectory, "mirror")
                .walkTopDown()
                .filter { it.isFile && it.extension.equals("html", ignoreCase = true) }
                .toList()

            assertThat(entrypoint.isFile).isTrue()
            assertThat(entrypoint.readText()).contains("http-equiv=\"refresh\"")
            assertThat(primaryHtml.isFile).isTrue()
            assertThat(primaryHtml.length()).isGreaterThan(1_024L)
            assertThat(mirroredHtml).isNotEmpty()
            if (languageCode in multiPageLanguageCodes) {
                assertThat(mirroredHtml.size).isAtLeast(20)
            }
            assertThat(catalog).contains("\"code\": \"$languageCode\"")
            assertThat(catalog).contains("\"entrypoint\": \"$languageCode/index.html\"")
        }

        val englishMirror = File(translationAssets, "en/mirror/easypeasymethod.org")
        listOf("de", "it", "nl", "pl", "pt-br", "ro", "sq", "so", "sv", "ru", "uk", "ko")
            .forEach { translatedPath ->
                assertThat(File(englishMirror, translatedPath).exists()).isFalse()
            }
    }

    @Test
    fun redistributionNoticesAreBundled() {
        assertThat(File(bookAssets, "licenses/NOTICE.md").isFile).isTrue()
        assertThat(File(bookAssets, "licenses/LICENSE-CONTENT").isFile).isTrue()
        assertThat(File(bookAssets, "licenses/LICENSE-CODE").isFile).isTrue()
    }

    @Test
    fun mobileChapterMenuCanBeDismissedWithoutNavigating() {
        val script = File(bookAssets, "assets/js/reader.js").readText()
        val styles = File(bookAssets, "assets/css/reader.css").readText()

        assertThat(script).contains("sidebarBackdrop.addEventListener('click', closeMobileSidebar)")
        assertThat(script).contains("if (event.key === 'Escape') closeMobileSidebar()")
        assertThat(styles).contains(".reader.mobile-sidebar-open .sidebar-backdrop")
        assertThat(styles).contains("pointer-events: auto")
    }
}
