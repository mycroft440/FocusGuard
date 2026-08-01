package com.focusguard.ui.compose.screens.sessions

import com.focusguard.data.PredefinedWebsites
import com.focusguard.database.BlockSession
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.repository.BlockSessionRepository
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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

@OptIn(ExperimentalCoroutinesApi::class)
class SessionsListViewModelTest {

    private val repository: BlockSessionRepository = mockk(relaxed = true)
    private val blockingSessionManager: BlockingSessionManager = mockk(relaxed = true)
    private val dispatcher = StandardTestDispatcher()
    private lateinit var viewModel: SessionsListViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { repository.observeActiveSessions() } returns flowOf(emptyList())
        viewModel = SessionsListViewModel(repository, blockingSessionManager)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `sessions starts empty`() {
        assertThat(viewModel.sessions.value).isEmpty()
    }

    @Test
    fun `sessions eagerly receives repository data`() = runTest(dispatcher) {
        val sessions = listOf(
            mockk<BlockSession>(relaxed = true),
            mockk<BlockSession>(relaxed = true)
        )
        every { repository.observeActiveSessions() } returns flowOf(sessions)
        viewModel = SessionsListViewModel(repository, blockingSessionManager)

        testScheduler.advanceUntilIdle()

        assertThat(viewModel.sessions.value).containsExactlyElementsIn(sessions)
    }

    @Test
    fun `loadSessionDetails exposes loading synchronously and then data`() = runTest(dispatcher) {
        coEvery { repository.getBlockedAppsForSession(5) } returns listOf("com.example.app")
        coEvery { repository.getBlockedSitesForSession(5) } returns listOf("example.com")

        viewModel.loadSessionDetails(5)
        assertThat(viewModel.detailsSheetState.value.isLoading).isTrue()

        testScheduler.advanceUntilIdle()

        val state = viewModel.detailsSheetState.value
        assertThat(state.isLoading).isFalse()
        assertThat(state.error).isNull()
        assertThat(state.blockedApps).containsExactly("com.example.app")
        assertThat(state.blockedSites).containsExactly("example.com")
    }

    @Test
    fun `loadSessionDetails reports repository error`() = runTest(dispatcher) {
        coEvery { repository.getBlockedAppsForSession(5) } throws IllegalStateException("db")

        viewModel.loadSessionDetails(5)
        testScheduler.advanceUntilIdle()

        assertThat(viewModel.detailsSheetState.value.isLoading).isFalse()
        assertThat(viewModel.detailsSheetState.value.error).isNotNull()
    }

    @Test
    fun `clearAllBlockedContent delegates reconciles and reloads`() = runTest(dispatcher) {
        coEvery { repository.getBlockedAppsForSession(7) } returns emptyList()
        coEvery { repository.getBlockedSitesForSession(7) } returns emptyList()

        viewModel.clearAllBlockedContent(7)
        testScheduler.advanceUntilIdle()

        coVerify(exactly = 1) { repository.clearAllBlockedContent(7) }
        coVerify(exactly = 1) { blockingSessionManager.checkAndEnforce() }
        coVerify(exactly = 1) { repository.getBlockedAppsForSession(7) }
        coVerify(exactly = 1) { repository.getBlockedSitesForSession(7) }
    }

    @Test
    fun `removeAppFromSession delegates reconciles and reloads`() = runTest(dispatcher) {
        coEvery { repository.getBlockedAppsForSession(3) } returns emptyList()
        coEvery { repository.getBlockedSitesForSession(3) } returns emptyList()

        viewModel.removeAppFromSession(3, "com.example.app")
        testScheduler.advanceUntilIdle()

        coVerify(exactly = 1) {
            repository.removeAppFromSession(3, "com.example.app")
        }
        coVerify(exactly = 1) { blockingSessionManager.checkAndEnforce() }
    }

    @Test
    fun `removeSiteFromSession normalizes domain`() = runTest(dispatcher) {
        coEvery { repository.getBlockedAppsForSession(3) } returns emptyList()
        coEvery { repository.getBlockedSitesForSession(3) } returns emptyList()

        viewModel.removeSiteFromSession(3, "HTTPS://WWW.Example.com/path")
        testScheduler.advanceUntilIdle()

        coVerify(exactly = 1) {
            repository.removeSiteFromSession(3, "example.com")
        }
        coVerify(exactly = 1) { blockingSessionManager.checkAndEnforce() }
    }

    @Test
    fun `resetDetailsSheet clears state`() = runTest(dispatcher) {
        coEvery { repository.getBlockedAppsForSession(2) } returns listOf("com.test")
        coEvery { repository.getBlockedSitesForSession(2) } returns listOf("test.com")
        viewModel.loadSessionDetails(2)
        testScheduler.advanceUntilIdle()

        viewModel.resetDetailsSheet()

        assertThat(viewModel.detailsSheetState.value)
            .isEqualTo(SessionsListViewModel.DetailsSheetState())
    }

    @Test
    fun `addPendingSite rejects blank and invalid values`() {
        viewModel.updateSiteInput("   ")
        assertThat(viewModel.addPendingSite()).isFalse()
        viewModel.updateSiteInput("duas palavras")
        assertThat(viewModel.addPendingSite()).isFalse()
        assertThat(viewModel.contentPickerState.value.sites).isEmpty()
    }

    @Test
    fun `addPendingSite normalizes domain and clears input`() {
        viewModel.updateSiteInput("HTTPS://WWW.Example.COM/path")

        assertThat(viewModel.addPendingSite()).isTrue()

        val state = viewModel.contentPickerState.value
        assertThat(state.sites).containsExactly("example.com")
        assertThat(state.siteInput).isEmpty()
    }

    @Test
    fun `addPendingSite accepts and normalizes a domain keyword`() {
        viewModel.updateSiteInput("Porn")

        assertThat(viewModel.addPendingSite()).isTrue()

        val state = viewModel.contentPickerState.value
        assertThat(state.sites).containsExactly("keyword:porn")
        assertThat(state.siteInput).isEmpty()
    }

    @Test
    fun `addPendingSite rejects duplicate normalized domain`() {
        viewModel.updateSiteInput("example.com")
        assertThat(viewModel.addPendingSite()).isTrue()
        viewModel.updateSiteInput("https://www.example.com/path")

        assertThat(viewModel.addPendingSite()).isFalse()
        assertThat(viewModel.contentPickerState.value.sites).containsExactly("example.com")
    }

    @Test
    fun `removePendingSite normalizes input`() {
        viewModel.updateSiteInput("example.com")
        viewModel.addPendingSite()

        viewModel.removePendingSite("https://www.example.com/path")

        assertThat(viewModel.contentPickerState.value.sites).isEmpty()
    }

    @Test
    fun `pornography preset toggles one category rule`() {
        viewModel.togglePendingPornography()

        assertThat(viewModel.contentPickerState.value.sites)
            .containsExactly(PredefinedWebsites.PORNOGRAPHY_RULE)

        viewModel.togglePendingPornography()

        assertThat(viewModel.contentPickerState.value.sites).isEmpty()
    }

    @Test
    fun `initContentPicker clears all picker state`() {
        viewModel.updateSiteInput("example.com")
        viewModel.addPendingSite()

        viewModel.initContentPicker()

        assertThat(viewModel.contentPickerState.value)
            .isEqualTo(SessionsListViewModel.ContentPickerState())
    }

    @Test
    fun `resetContentPicker clears sites input and saving state`() {
        viewModel.updateSiteInput("example.com")
        viewModel.addPendingSite()
        assertThat(viewModel.contentPickerState.value.sites).isNotEmpty()
        assertThat(viewModel.contentPickerState.value.siteInput).isEmpty()

        viewModel.resetContentPicker()

        assertThat(viewModel.contentPickerState.value)
            .isEqualTo(SessionsListViewModel.ContentPickerState())
    }

    @Test
    fun `bulkAddContent persists distinct apps and sites then reconciles`() = runTest(dispatcher) {
        viewModel.updateSiteInput("example.com")
        viewModel.addPendingSite()

        viewModel.bulkAddContent(
            sessionId = 5,
            selectedApps = listOf("com.example.app", "com.example.app")
        )
        assertThat(viewModel.contentPickerState.value.isSaving).isTrue()
        testScheduler.advanceUntilIdle()

        coVerify(exactly = 1) { repository.addAppToSession(5, "com.example.app") }
        coVerify(exactly = 1) { repository.addSiteToSession(5, "example.com") }
        coVerify(exactly = 1) { blockingSessionManager.checkAndEnforce() }
        assertThat(viewModel.contentPickerState.value)
            .isEqualTo(SessionsListViewModel.ContentPickerState())
    }

    @Test
    fun `bulkAddContent restores saving state after failure`() = runTest(dispatcher) {
        coEvery { repository.addAppToSession(5, any()) } throws IllegalStateException("db")

        viewModel.bulkAddContent(5, listOf("com.example.app"))
        testScheduler.advanceUntilIdle()

        assertThat(viewModel.contentPickerState.value.isSaving).isFalse()
        assertThat(viewModel.contentPickerState.value.error).isNotNull()
    }

    @Test
    fun `isCurrentlyInBlockingWindow delegates to manager`() {
        val session = mockk<BlockSession>(relaxed = true)
        every { blockingSessionManager.isCurrentlyInBlockingWindow(session) } returns true

        assertThat(viewModel.isCurrentlyInBlockingWindow(session)).isTrue()
        verify(exactly = 1) { blockingSessionManager.isCurrentlyInBlockingWindow(session) }
    }

    @Test
    fun `endSession awaits manager result`() = runTest(dispatcher) {
        coEvery { blockingSessionManager.endSessionAndWait(9) } returns
            BlockingSessionManager.EndSessionResult.ENDED

        viewModel.endSession(9)
        testScheduler.advanceUntilIdle()

        coVerify(exactly = 1) { blockingSessionManager.endSessionAndWait(9) }
    }
}
