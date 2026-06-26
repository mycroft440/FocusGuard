package com.focusguard.ui.compose.screens.sessions

import com.focusguard.database.BlockSession
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.repository.BlockSessionRepository
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Testes unitários para [SessionsListViewModel].
 *
 * Usa mockk para mockar o [BlockSessionRepository] e testar que o ViewModel:
 *  - Expõe corretamente o Flow de sessões do repository
 *  - Delega chamadas de adicionar/remover apps/sites ao repository
 *  - Atualiza o estado do DetailsSheet e ContentPicker corretamente
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionsListViewModelTest {

    private val repository: BlockSessionRepository = mockk(relaxed = true)
    private val blockingSessionManager: BlockingSessionManager = mockk(relaxed = true)
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var viewModel: SessionsListViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        coEvery { repository.observeActiveSessions() } returns flowOf(emptyList())
        viewModel = SessionsListViewModel(repository, blockingSessionManager)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `sessions flow emits empty list initially`() = runTest(testDispatcher) {
        assertThat(viewModel.sessions.value).isEmpty()
    }

    @Test
    fun `sessions flow emits sessions from repository`() = runTest(testDispatcher) {
        val sessions = listOf(
            mockk<BlockSession>(relaxed = true),
            mockk<BlockSession>(relaxed = true)
        )
        coEvery { repository.observeActiveSessions() } returns flowOf(sessions)
        // Recriar o ViewModel para que pegue o novo flow mockado
        viewModel = SessionsListViewModel(repository, blockingSessionManager)

        // Aguardar o stateIn propagar
        testScheduler.advanceUntilIdle()

        assertThat(viewModel.sessions.value).hasSize(2)
    }

    @Test
    fun `loadSessionDetails updates detailsSheetState with apps and sites`() = runTest(testDispatcher) {
        val sessionId = 5
        val apps = listOf("com.facebook.katana", "com.instagram.android")
        val sites = listOf("facebook.com", "instagram.com")
        coEvery { repository.getBlockedAppsForSession(sessionId) } returns apps
        coEvery { repository.getBlockedSitesForSession(sessionId) } returns sites

        viewModel.loadSessionDetails(sessionId)
        testScheduler.advanceUntilIdle()

        val state = viewModel.detailsSheetState.value
        assertThat(state.blockedApps).containsExactly("com.facebook.katana", "com.instagram.android")
        assertThat(state.blockedSites).containsExactly("facebook.com", "instagram.com")
        assertThat(state.isLoading).isFalse()
    }

    @Test
    fun `loadSessionDetails sets isLoading true initially then false`() = runTest(testDispatcher) {
        val sessionId = 1
        coEvery { repository.getBlockedAppsForSession(sessionId) } returns emptyList()
        coEvery { repository.getBlockedSitesForSession(sessionId) } returns emptyList()

        // Estado inicial: isLoading = false
        assertThat(viewModel.detailsSheetState.value.isLoading).isFalse()

        viewModel.loadSessionDetails(sessionId)

        // Após chamar (mas antes de rodar coroutines): isLoading deve ser true
        assertThat(viewModel.detailsSheetState.value.isLoading).isTrue()

        testScheduler.advanceUntilIdle()

        // Após completar: isLoading = false
        assertThat(viewModel.detailsSheetState.value.isLoading).isFalse()
    }

    @Test
    fun `clearAllBlockedContent delegates to repository and reloads details`() = runTest(testDispatcher) {
        val sessionId = 7
        coEvery { repository.getBlockedAppsForSession(sessionId) } returns emptyList()
        coEvery { repository.getBlockedSitesForSession(sessionId) } returns emptyList()

        viewModel.clearAllBlockedContent(sessionId)
        testScheduler.advanceUntilIdle()

        coVerify(exactly = 1) { repository.clearAllBlockedContent(sessionId) }
        // Verifica que também recarregou os detalhes (chamou GetBlockedAppsForSession 1x para reload)
        coVerify(exactly = 1) { repository.getBlockedAppsForSession(sessionId) }
        coVerify(exactly = 1) { repository.getBlockedSitesForSession(sessionId) }
    }

    @Test
    fun `clearAllBlockedContent calls checkAndEnforce to sync blocking state`() = runTest(testDispatcher) {
        val sessionId = 7
        coEvery { repository.getBlockedAppsForSession(sessionId) } returns emptyList()
        coEvery { repository.getBlockedSitesForSession(sessionId) } returns emptyList()

        viewModel.clearAllBlockedContent(sessionId)
        testScheduler.advanceUntilIdle()

        // Verifica que checkAndEnforce foi chamado exatamente 1x após a mutation
        coVerify(exactly = 1) { blockingSessionManager.checkAndEnforce() }
    }

    @Test
    fun `removeAppFromSession delegates to repository and reloads details`() = runTest(testDispatcher) {
        val sessionId = 3
        val packageName = "com.example.app"
        coEvery { repository.getBlockedAppsForSession(sessionId) } returns emptyList()
        coEvery { repository.getBlockedSitesForSession(sessionId) } returns emptyList()

        viewModel.removeAppFromSession(sessionId, packageName)
        testScheduler.advanceUntilIdle()

        coVerify(exactly = 1) { repository.removeAppFromSession(sessionId, packageName) }
    }

    @Test
    fun `removeAppFromSession calls checkAndEnforce to sync blocking state`() = runTest(testDispatcher) {
        val sessionId = 3
        val packageName = "com.example.app"
        coEvery { repository.getBlockedAppsForSession(sessionId) } returns emptyList()
        coEvery { repository.getBlockedSitesForSession(sessionId) } returns emptyList()

        viewModel.removeAppFromSession(sessionId, packageName)
        testScheduler.advanceUntilIdle()

        coVerify(exactly = 1) { blockingSessionManager.checkAndEnforce() }
    }

    @Test
    fun `removeSiteFromSession calls checkAndEnforce to sync blocking state`() = runTest(testDispatcher) {
        val sessionId = 3
        val domain = "example.com"
        coEvery { repository.getBlockedAppsForSession(sessionId) } returns emptyList()
        coEvery { repository.getBlockedSitesForSession(sessionId) } returns emptyList()

        viewModel.removeSiteFromSession(sessionId, domain)
        testScheduler.advanceUntilIdle()

        coVerify(exactly = 1) { blockingSessionManager.checkAndEnforce() }
    }

    @Test
    fun `removeSiteFromSession delegates to repository and reloads details`() = runTest(testDispatcher) {
        val sessionId = 3
        val domain = "example.com"
        coEvery { repository.getBlockedAppsForSession(sessionId) } returns emptyList()
        coEvery { repository.getBlockedSitesForSession(sessionId) } returns emptyList()

        viewModel.removeSiteFromSession(sessionId, domain)
        testScheduler.advanceUntilIdle()

        coVerify(exactly = 1) { repository.removeSiteFromSession(sessionId, domain) }
    }

    @Test
    fun `resetDetailsSheet clears detailsSheetState`() = runTest(testDispatcher) {
        // Popula o estado primeiro
        val sessionId = 5
        coEvery { repository.getBlockedAppsForSession(sessionId) } returns listOf("com.test")
        coEvery { repository.getBlockedSitesForSession(sessionId) } returns listOf("test.com")
        viewModel.loadSessionDetails(sessionId)
        testScheduler.advanceUntilIdle()
        assertThat(viewModel.detailsSheetState.value.blockedApps).isNotEmpty()

        // Reseta
        viewModel.resetDetailsSheet()
        assertThat(viewModel.detailsSheetState.value.blockedApps).isEmpty()
        assertThat(viewModel.detailsSheetState.value.blockedSites).isEmpty()
        assertThat(viewModel.detailsSheetState.value.isLoading).isFalse()
    }

    @Test
    fun `updateSiteInput updates contentPickerState siteInput`() {
        viewModel.updateSiteInput("facebook.com")
        assertThat(viewModel.contentPickerState.value.siteInput).isEqualTo("facebook.com")
    }

    @Test
    fun `addPendingSite returns false for empty input`() {
        viewModel.updateSiteInput("")
        val result = viewModel.addPendingSite()
        assertThat(result).isFalse()
        assertThat(viewModel.contentPickerState.value.sites).isEmpty()
    }

    @Test
    fun `addPendingSite returns false for blank input`() {
        viewModel.updateSiteInput("   ")
        val result = viewModel.addPendingSite()
        assertThat(result).isFalse()
        assertThat(viewModel.contentPickerState.value.sites).isEmpty()
    }

    @Test
    fun `addPendingSite returns true and adds site to pending list for valid input`() {
        viewModel.updateSiteInput("facebook.com")
        val result = viewModel.addPendingSite()
        assertThat(result).isTrue()
        assertThat(viewModel.contentPickerState.value.sites).containsExactly("facebook.com")
    }

    @Test
    fun `addPendingSite lowercases the site before adding`() {
        viewModel.updateSiteInput("FACEBOOK.COM")
        viewModel.addPendingSite()
        assertThat(viewModel.contentPickerState.value.sites).containsExactly("facebook.com")
    }

    @Test
    fun `addPendingSite returns false if site already in pending list`() {
        viewModel.updateSiteInput("facebook.com")
        viewModel.addPendingSite()
        viewModel.updateSiteInput("facebook.com")
        val result = viewModel.addPendingSite()
        assertThat(result).isFalse()
        assertThat(viewModel.contentPickerState.value.sites).hasSize(1)
    }

    @Test
    fun `addPendingSite clears siteInput after adding`() {
        viewModel.updateSiteInput("facebook.com")
        viewModel.addPendingSite()
        assertThat(viewModel.contentPickerState.value.siteInput).isEmpty()
    }

    @Test
    fun `addPendingSite can add multiple distinct sites`() {
        viewModel.updateSiteInput("facebook.com")
        viewModel.addPendingSite()
        viewModel.updateSiteInput("instagram.com")
        viewModel.addPendingSite()
        viewModel.updateSiteInput("twitter.com")
        viewModel.addPendingSite()
        assertThat(viewModel.contentPickerState.value.sites)
            .containsExactly("facebook.com", "instagram.com", "twitter.com")
    }

    @Test
    fun `removePendingSite removes site from pending list`() {
        viewModel.updateSiteInput("facebook.com")
        viewModel.addPendingSite()
        viewModel.updateSiteInput("instagram.com")
        viewModel.addPendingSite()
        assertThat(viewModel.contentPickerState.value.sites).hasSize(2)

        viewModel.removePendingSite("facebook.com")
        assertThat(viewModel.contentPickerState.value.sites).containsExactly("instagram.com")
    }

    @Test
    fun `removePendingSite does nothing if site not in list`() {
        viewModel.updateSiteInput("facebook.com")
        viewModel.addPendingSite()

        viewModel.removePendingSite("instagram.com")
        assertThat(viewModel.contentPickerState.value.sites).containsExactly("facebook.com")
    }

    @Test
    fun `initContentPicker resets state`() {
        viewModel.updateSiteInput("foo")
        viewModel.addPendingSite()

        viewModel.initContentPicker()

        assertThat(viewModel.contentPickerState.value.siteInput).isEmpty()
        assertThat(viewModel.contentPickerState.value.sites).isEmpty()
        assertThat(viewModel.contentPickerState.value.isSaving).isFalse()
    }

    @Test
    fun `bulkAddContent inserts all selected apps via repository`() = runTest(testDispatcher) {
        val sessionId = 5
        val selectedApps = listOf("com.facebook.katana", "com.instagram.android", "com.twitter.android")

        viewModel.bulkAddContent(sessionId, selectedApps)
        testScheduler.advanceUntilIdle()

        coVerify(exactly = 1) { repository.addAppToSession(sessionId, "com.facebook.katana") }
        coVerify(exactly = 1) { repository.addAppToSession(sessionId, "com.instagram.android") }
        coVerify(exactly = 1) { repository.addAppToSession(sessionId, "com.twitter.android") }
    }

    @Test
    fun `bulkAddContent inserts all pending sites via repository`() = runTest(testDispatcher) {
        val sessionId = 5
        viewModel.updateSiteInput("facebook.com")
        viewModel.addPendingSite()
        viewModel.updateSiteInput("instagram.com")
        viewModel.addPendingSite()

        viewModel.bulkAddContent(sessionId, emptyList())
        testScheduler.advanceUntilIdle()

        coVerify(exactly = 1) { repository.addSiteToSession(sessionId, "facebook.com") }
        coVerify(exactly = 1) { repository.addSiteToSession(sessionId, "instagram.com") }
    }

    @Test
    fun `bulkAddContent calls checkAndEnforce to sync blocking state`() = runTest(testDispatcher) {
        val sessionId = 5
        viewModel.updateSiteInput("facebook.com")
        viewModel.addPendingSite()

        viewModel.bulkAddContent(sessionId, listOf("com.example.app"))
        testScheduler.advanceUntilIdle()

        coVerify(exactly = 1) { blockingSessionManager.checkAndEnforce() }
    }

    @Test
    fun `bulkAddContent resets contentPickerState after save`() = runTest(testDispatcher) {
        val sessionId = 5
        viewModel.updateSiteInput("facebook.com")
        viewModel.addPendingSite()

        viewModel.bulkAddContent(sessionId, listOf("com.example.app"))
        testScheduler.advanceUntilIdle()

        assertThat(viewModel.contentPickerState.value.sites).isEmpty()
        assertThat(viewModel.contentPickerState.value.siteInput).isEmpty()
        assertThat(viewModel.contentPickerState.value.isSaving).isFalse()
    }

    @Test
    fun `bulkAddContent with no apps and no sites still calls checkAndEnforce`() = runTest(testDispatcher) {
        val sessionId = 5

        viewModel.bulkAddContent(sessionId, emptyList())
        testScheduler.advanceUntilIdle()

        coVerify(exactly = 1) { blockingSessionManager.checkAndEnforce() }
    }

    @Test
    fun `resetContentPicker clears contentPickerState`() = runTest(testDispatcher) {
        viewModel.updateSiteInput("foo")
        viewModel.addPendingSite()
        assertThat(viewModel.contentPickerState.value.siteInput).isEqualTo("foo")
        assertThat(viewModel.contentPickerState.value.sites).hasSize(1)

        viewModel.resetContentPicker()

        assertThat(viewModel.contentPickerState.value.siteInput).isEmpty()
        assertThat(viewModel.contentPickerState.value.sites).isEmpty()
        assertThat(viewModel.contentPickerState.value.isSaving).isFalse()
    }

    // ─────────────────────────────────────────────────────────────────────
    // SessionListItem actions (isCurrentlyInBlockingWindow, endSession)
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `isCurrentlyInBlockingWindow delegates to BlockingSessionManager`() {
        val session = mockk<BlockSession>(relaxed = true)
        coEvery { blockingSessionManager.isCurrentlyInBlockingWindow(session) } returns true

        val result = viewModel.isCurrentlyInBlockingWindow(session)

        assertThat(result).isTrue()
        coVerify(exactly = 1) { blockingSessionManager.isCurrentlyInBlockingWindow(session) }
    }

    @Test
    fun `isCurrentlyInBlockingWindow returns false when manager returns false`() {
        val session = mockk<BlockSession>(relaxed = true)
        coEvery { blockingSessionManager.isCurrentlyInBlockingWindow(session) } returns false

        val result = viewModel.isCurrentlyInBlockingWindow(session)

        assertThat(result).isFalse()
    }

    @Test
    fun `isCurrentlyInBlockingWindow handles null session`() {
        coEvery { blockingSessionManager.isCurrentlyInBlockingWindow(null) } returns false

        val result = viewModel.isCurrentlyInBlockingWindow(null)

        assertThat(result).isFalse()
        coVerify(exactly = 1) { blockingSessionManager.isCurrentlyInBlockingWindow(null) }
    }

    @Test
    fun `endSession delegates to BlockingSessionManager`() {
        val sessionId = 42
        viewModel.endSession(sessionId)

        coVerify(exactly = 1) { blockingSessionManager.endSession(sessionId) }
    }

    @Test
    fun `endSession with different sessionIds delegates correctly`() {
        viewModel.endSession(1)
        viewModel.endSession(99)
        viewModel.endSession(123)

        coVerify(exactly = 1) { blockingSessionManager.endSession(1) }
        coVerify(exactly = 1) { blockingSessionManager.endSession(99) }
        coVerify(exactly = 1) { blockingSessionManager.endSession(123) }
    }
}
