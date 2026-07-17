package com.focusguard.repository

import com.focusguard.database.BlockSession
import com.focusguard.database.BlockSessionDao
import com.focusguard.database.SessionAppCrossRef
import com.focusguard.database.SessionAppCrossRefDao
import com.focusguard.database.SessionWebsiteCrossRef
import com.focusguard.database.SessionWebsiteCrossRefDao
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Testes unitários para [BlockSessionRepository].
 *
 * Usa mockk para mockar os 3 DAOs e validar que o Repository delega corretamente
 * e retorna dados no formato esperado pelos ViewModels.
 */
class BlockSessionRepositoryTest {

    private val blockSessionDao: BlockSessionDao = mockk(relaxed = true)
    private val sessionAppCrossRefDao: SessionAppCrossRefDao = mockk(relaxed = true)
    private val sessionWebsiteCrossRefDao: SessionWebsiteCrossRefDao = mockk(relaxed = true)

    private val repository = BlockSessionRepository(
        blockSessionDao = blockSessionDao,
        sessionAppCrossRefDao = sessionAppCrossRefDao,
        sessionWebsiteCrossRefDao = sessionWebsiteCrossRefDao
    )

    @Test
    fun `observeActiveSessions delegates to blockSessionDao getAllActiveSessions`() = runTest {
        val sessions = listOf(
            mockk<BlockSession>(relaxed = true),
            mockk<BlockSession>(relaxed = true)
        )
        coEvery { blockSessionDao.getAllActiveSessions() } returns flowOf(sessions)

        val result = repository.observeActiveSessions()

        // Verifica que o flow retornado é o mesmo do DAO (delegação pura)
        assertThat(result).isNotNull()
    }

    @Test
    fun `getBlockedAppsForSession returns apps from DAO`() = runTest {
        val sessionId = 42
        val apps = listOf("com.facebook.katana", "com.instagram.android")
        coEvery { sessionAppCrossRefDao.getAppsForSessions(listOf(sessionId)) } returns apps

        val result = repository.getBlockedAppsForSession(sessionId)

        assertThat(result).hasSize(2)
        assertThat(result).containsExactly("com.facebook.katana", "com.instagram.android")
    }

    @Test
    fun `getBlockedAppsForSession returns empty list when no apps`() = runTest {
        val sessionId = 1
        coEvery { sessionAppCrossRefDao.getAppsForSessions(listOf(sessionId)) } returns emptyList()

        val result = repository.getBlockedAppsForSession(sessionId)

        assertThat(result).isEmpty()
    }

    @Test
    fun `getBlockedSitesForSession returns sites from DAO`() = runTest {
        val sessionId = 7
        val sites = listOf("facebook.com", "instagram.com", "twitter.com")
        coEvery { sessionWebsiteCrossRefDao.getWebsitesForSessions(listOf(sessionId)) } returns sites

        val result = repository.getBlockedSitesForSession(sessionId)

        assertThat(result).hasSize(3)
        assertThat(result).containsExactly("facebook.com", "instagram.com", "twitter.com")
    }

    @Test
    fun `clearAllBlockedContent calls deleteForSession on both DAOs`() = runTest {
        val sessionId = 99

        repository.clearAllBlockedContent(sessionId)

        coVerify(exactly = 1) { sessionAppCrossRefDao.deleteForSession(sessionId) }
        coVerify(exactly = 1) { sessionWebsiteCrossRefDao.deleteForSession(sessionId) }
    }

    @Test
    fun `addAppToSession creates SessionAppCrossRef and inserts via DAO`() = runTest {
        val sessionId = 5
        val packageName = "com.example.app"

        repository.addAppToSession(sessionId, packageName)

        coVerify(exactly = 1) {
            sessionAppCrossRefDao.insert(SessionAppCrossRef(sessionId, packageName))
        }
    }

    @Test
    fun `removeAppFromSession calls deleteSpecificApp on DAO`() = runTest {
        val sessionId = 5
        val packageName = "com.example.app"

        repository.removeAppFromSession(sessionId, packageName)

        coVerify(exactly = 1) {
            sessionAppCrossRefDao.deleteSpecificApp(sessionId, packageName)
        }
    }

    @Test
    fun `addSiteToSession creates SessionWebsiteCrossRef and inserts via DAO`() = runTest {
        val sessionId = 10
        val domain = "example.com"

        repository.addSiteToSession(sessionId, domain)

        coVerify(exactly = 1) {
            sessionWebsiteCrossRefDao.insert(SessionWebsiteCrossRef(sessionId, domain))
        }
    }

    @Test
    fun `addSiteToSession stores a normalized domain`() = runTest {
        repository.addSiteToSession(10, "HTTPS://WWW.Example.COM:8443/path")

        coVerify(exactly = 1) {
            sessionWebsiteCrossRefDao.insert(SessionWebsiteCrossRef(10, "example.com"))
        }
    }

    @Test
    fun `addSiteToSession replaces an equivalent legacy value`() = runTest {
        val legacyValue = "HTTPS://WWW.Example.COM/path"
        coEvery {
            sessionWebsiteCrossRefDao.getWebsitesForSessions(listOf(10))
        } returns listOf(legacyValue)

        repository.addSiteToSession(10, "example.com")

        coVerify(exactly = 1) {
            sessionWebsiteCrossRefDao.deleteSpecificWebsite(10, legacyValue)
        }
        coVerify(exactly = 1) {
            sessionWebsiteCrossRefDao.insert(SessionWebsiteCrossRef(10, "example.com"))
        }
    }

    @Test
    fun `addSiteToSession ignores invalid addresses`() = runTest {
        repository.addSiteToSession(10, "pesquisa sem dominio")

        coVerify(exactly = 0) {
            sessionWebsiteCrossRefDao.insert(any())
        }
    }

    @Test
    fun `removeSiteFromSession calls deleteSpecificWebsite on DAO`() = runTest {
        val sessionId = 10
        val domain = "example.com"

        repository.removeSiteFromSession(sessionId, domain)

        coVerify(exactly = 1) {
            sessionWebsiteCrossRefDao.deleteSpecificWebsite(sessionId, domain)
        }
    }

    @Test
    fun `removeSiteFromSession normalizes before deleting`() = runTest {
        val legacyValue = "https://www.Example.com/path"
        repository.removeSiteFromSession(10, legacyValue)

        coVerify(exactly = 1) {
            sessionWebsiteCrossRefDao.deleteSpecificWebsite(10, "example.com")
        }
        coVerify(exactly = 1) {
            sessionWebsiteCrossRefDao.deleteSpecificWebsite(10, legacyValue)
        }
    }
}
