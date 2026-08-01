package com.focusguard.ui.compose.screens.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.focusguard.data.PredefinedWebsites
import com.focusguard.database.BlockSession
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.repository.BlockSessionRepository
import com.focusguard.utils.FocusGuardLogger
import com.focusguard.utils.WebsiteBlocker
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SessionsListViewModel @Inject constructor(
    private val repository: BlockSessionRepository,
    private val blockingSessionManager: BlockingSessionManager
) : ViewModel() {

    val sessions: StateFlow<List<BlockSession>> = repository.observeActiveSessions()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList()
        )

    private val _detailsSheetState = MutableStateFlow(DetailsSheetState())
    val detailsSheetState: StateFlow<DetailsSheetState> = _detailsSheetState.asStateFlow()

    private val _contentPickerState = MutableStateFlow(ContentPickerState())
    val contentPickerState: StateFlow<ContentPickerState> = _contentPickerState.asStateFlow()

    fun loadSessionDetails(sessionId: Int) {
        _detailsSheetState.value = _detailsSheetState.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            try {
                val apps = repository.getBlockedAppsForSession(sessionId)
                val sites = repository.getBlockedSitesForSession(sessionId)
                _detailsSheetState.value = DetailsSheetState(
                    blockedApps = apps.distinct(),
                    blockedSites = sites.distinct(),
                    isLoading = false
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                FocusGuardLogger.logError(
                    "SessionsListViewModel",
                    "Falha ao carregar detalhes da sessão $sessionId",
                    error
                )
                _detailsSheetState.value = _detailsSheetState.value.copy(
                    isLoading = false,
                    error = error.localizedMessage ?: "Falha ao carregar a sessão"
                )
            }
        }
    }

    fun clearAllBlockedContent(sessionId: Int) {
        viewModelScope.launch {
            runMutation("limpar conteúdo da sessão $sessionId") {
                repository.clearAllBlockedContent(sessionId)
                blockingSessionManager.checkAndEnforce()
                loadSessionDetails(sessionId)
            }
        }
    }

    fun removeAppFromSession(sessionId: Int, packageName: String) {
        viewModelScope.launch {
            runMutation("remover app da sessão $sessionId") {
                repository.removeAppFromSession(sessionId, packageName)
                blockingSessionManager.checkAndEnforce()
                loadSessionDetails(sessionId)
            }
        }
    }

    fun removeSiteFromSession(sessionId: Int, domain: String) {
        viewModelScope.launch {
            runMutation("remover site da sessão $sessionId") {
                repository.removeSiteFromSession(sessionId, WebsiteBlocker.normalizeRule(domain))
                blockingSessionManager.checkAndEnforce()
                loadSessionDetails(sessionId)
            }
        }
    }

    fun resetDetailsSheet() {
        _detailsSheetState.value = DetailsSheetState()
    }

    fun initContentPicker() {
        _contentPickerState.value = ContentPickerState()
    }

    fun updateSiteInput(input: String) {
        _contentPickerState.value = _contentPickerState.value.copy(siteInput = input)
    }

    fun addPendingSite(): Boolean {
        val site = WebsiteBlocker.normalizeRule(_contentPickerState.value.siteInput)
        if (!WebsiteBlocker.isValidRule(site)) return false
        if (site in _contentPickerState.value.sites) return false

        _contentPickerState.value = _contentPickerState.value.copy(
            sites = _contentPickerState.value.sites + site,
            siteInput = ""
        )
        return true
    }

    fun togglePendingPornography() {
        val rule = PredefinedWebsites.PORNOGRAPHY_RULE
        _contentPickerState.value = _contentPickerState.value.copy(
            sites = if (rule in _contentPickerState.value.sites) {
                _contentPickerState.value.sites - rule
            } else {
                _contentPickerState.value.sites + rule
            }
        )
    }

    fun removePendingSite(site: String) {
        val normalized = WebsiteBlocker.normalizeRule(site)
        _contentPickerState.value = _contentPickerState.value.copy(
            sites = _contentPickerState.value.sites - normalized
        )
    }

    fun bulkAddContent(sessionId: Int, selectedApps: List<String>) {
        if (_contentPickerState.value.isSaving) return
        val sitesToSave = _contentPickerState.value.sites.distinct()
        val appsToSave = selectedApps.distinct()
        _contentPickerState.value = _contentPickerState.value.copy(isSaving = true, error = null)

        viewModelScope.launch {
            try {
                appsToSave.forEach { repository.addAppToSession(sessionId, it) }
                sitesToSave.forEach { repository.addSiteToSession(sessionId, it) }
                blockingSessionManager.checkAndEnforce()
                _contentPickerState.value = ContentPickerState()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                FocusGuardLogger.logError(
                    "SessionsListViewModel",
                    "Falha ao adicionar conteúdo à sessão $sessionId",
                    error
                )
                _contentPickerState.value = _contentPickerState.value.copy(
                    isSaving = false,
                    error = error.localizedMessage ?: "Falha ao salvar"
                )
            }
        }
    }

    fun resetContentPicker() {
        _contentPickerState.value = ContentPickerState()
    }

    fun isCurrentlyInBlockingWindow(session: BlockSession?): Boolean {
        return blockingSessionManager.isCurrentlyInBlockingWindow(session)
    }

    fun endSession(sessionId: Int) {
        viewModelScope.launch {
            blockingSessionManager.endSessionAndWait(sessionId)
        }
    }

    private suspend fun runMutation(label: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            FocusGuardLogger.logError("SessionsListViewModel", "Falha ao $label", error)
        }
    }

    data class DetailsSheetState(
        val blockedApps: List<String> = emptyList(),
        val blockedSites: List<String> = emptyList(),
        val isLoading: Boolean = false,
        val error: String? = null
    )

    data class ContentPickerState(
        val sites: List<String> = emptyList(),
        val siteInput: String = "",
        val isSaving: Boolean = false,
        val error: String? = null
    )
}
