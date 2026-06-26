package com.focusguard.ui.compose.screens.sessions

import com.focusguard.database.BlockSession
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
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var viewModel: SessionsListViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        coEvery { repository.observeActiveSessions() } returns flowOf(emptyList())
        viewModel = SessionsListViewModel(repository)
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
        viewModel = SessionsListViewModel(repository)

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
    fun `addSiteToSession returns false for empty input`() = runTest(testDispatcher) {
        viewModel.updateSiteInput("")
        val result = viewModel.addSiteToSession(1)
        assertThat(result).isFalse()
    }

    @Test
    fun `addSiteToSession returns false for blank input`() = runTest(testDispatcher) {
        viewModel.updateSiteInput("   ")
        val result = viewModel.addSiteToSession(1)
        assertThat(result).isFalse()
    }

    @Test
    fun `addSiteToSession returns true and delegates to repository for valid input`() = runTest(testDispatcher) {
        val sessionId = 4
        viewModel.updateSiteInput("facebook.com")

        val result = viewModel.addSiteToSession(sessionId)
        assertThat(result).isTrue()

        testScheduler.advanceUntilIdle()

        coVerify(exactly = 1) { repository.addSiteToSession(sessionId, "facebook.com") }
    }

    @Test
    fun `addSiteToSession clears siteInput after successful add`() = runTest(testDispatcher) {
        val sessionId = 4
        coEvery { repository.getBlockedSitesForSession(sessionId) } returns emptyList()
        viewModel.updateSiteInput("facebook.com")

        viewModel.addSiteToSession(sessionId)
        testScheduler.advanceUntilIdle()

        assertThat(viewModel.contentPickerState.value.siteInput).isEmpty()
    }

    @Test
    fun `addAppToSession delegates to repository`() = runTest(testDispatcher) {
        val sessionId = 2
        val packageName = "com.example.app"

        viewModel.addAppToSession(sessionId, packageName)
        testScheduler.advanceUntilIdle()

        coVerify(exactly = 1) { repository.addAppToSession(sessionId, packageName) }
    }

    @Test
    fun `resetContentPicker clears contentPickerState`() = runTest(testDispatcher) {
        viewModel.updateSiteInput("foo")
        assertThat(viewModel.contentPickerState.value.siteInput).isEqualTo("foo")

        viewModel.resetContentPicker()

        assertThat(viewModel.contentPickerState.value.siteInput).isEmpty()
        assertThat(viewModel.contentPickerState.value.sites).isEmpty()
        assertThat(viewModel.contentPickerState.value.isSaving).isFalse()
    }
}
