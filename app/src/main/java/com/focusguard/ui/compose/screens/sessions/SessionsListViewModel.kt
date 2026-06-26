package com.focusguard.ui.compose.screens.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.focusguard.database.BlockSession
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.repository.BlockSessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UiState consolidado para a tela de listagem de sessões + sub-sheets.
 *
 * Antes da Fase 3, [com.focusguard.ui.compose.screens.SessionsListScreen] declarava
 * 11 `mutableStateOf` no topo da composable + 4 chamadas diretas a
 * `AppDatabase.getDatabase(context)` em sub-composables. Tudo isso vive agora
 * neste ViewModel — a UI só consome o StateFlow.
 */
data class SessionsListUiState(
    val isLoading: Boolean = true,
    val sessions: List<BlockSession> = emptyList(),
    // Estado do SessionDetailsSheet
    val detailsSheetBlockedApps: List<String> = emptyList(),
    val detailsSheetBlockedSites: List<String> = emptyList(),
    val detailsSheetIsLoading: Boolean = false,
    // Estado do ContentPickerSheet
    val contentPickerSites: List<String> = emptyList(),
    val contentPickerSiteInput: String = "",
    val contentPickerIsSaving: Boolean = false
)

/**
 * ViewModel para a tela de listagem de sessões de bloqueio.
 *
 * Observa o [BlockSessionRepository] para a lista de sessões ativas e expõe
 * ações para gerenciar apps/sites bloqueados de uma sessão específica.
 * Após qualquer mutação (add/remove/clear), chama [BlockingSessionManager.checkAndEnforce]
 * para sincronizar o estado de bloqueio com o Accessibility Service.
 */
@HiltViewModel
class SessionsListViewModel @Inject constructor(
    private val repository: BlockSessionRepository,
    private val blockingSessionManager: BlockingSessionManager
) : ViewModel() {

    /**
     * Estado principal: lista de sessões ativas, observada em tempo real.
     * `stateIn` converte o Flow do Room em StateFlow que sobrevive a mudanças
     * de configuração (rotação, dark mode toggle, etc).
     */
    val sessions: StateFlow<List<BlockSession>> = repository.observeActiveSessions()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    // Estado do SessionDetailsSheet — exposto como StateFlow para a UI observar
    private val _detailsSheetState = MutableStateFlow(DetailsSheetState())
    val detailsSheetState: StateFlow<DetailsSheetState> = _detailsSheetState.asStateFlow()

    // Estado do ContentPickerSheet
    private val _contentPickerState = MutableStateFlow(ContentPickerState())
    val contentPickerState: StateFlow<ContentPickerState> = _contentPickerState.asStateFlow()

    // ─────────────────────────────────────────────────────────────────────
    // SessionDetailsSheet
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Carrega apps/sites bloqueados de uma sessão para o DetailsSheet.
     */
    fun loadSessionDetails(sessionId: Int) {
        viewModelScope.launch {
            _detailsSheetState.value = _detailsSheetState.value.copy(isLoading = true)
            val apps = repository.getBlockedAppsForSession(sessionId)
            val sites = repository.getBlockedSitesForSession(sessionId)
            _detailsSheetState.value = DetailsSheetState(
                blockedApps = apps,
                blockedSites = sites,
                isLoading = false
            )
        }
    }

    /**
     * Limpa todos os apps/sites bloqueados de uma sessão.
     * Após limpar, chama checkAndEnforce() para sincronizar o bloqueio.
     */
    fun clearAllBlockedContent(sessionId: Int) {
        viewModelScope.launch {
            repository.clearAllBlockedContent(sessionId)
            blockingSessionManager.checkAndEnforce()
            loadSessionDetails(sessionId)
        }
    }

    /**
     * Remove um app específico de uma sessão.
     * Após remover, chama checkAndEnforce() para sincronizar o bloqueio.
     */
    fun removeAppFromSession(sessionId: Int, packageName: String) {
        viewModelScope.launch {
            repository.removeAppFromSession(sessionId, packageName)
            blockingSessionManager.checkAndEnforce()
            loadSessionDetails(sessionId)
        }
    }

    /**
     * Remove um site específico de uma sessão.
     * Após remover, chama checkAndEnforce() para sincronizar o bloqueio.
     */
    fun removeSiteFromSession(sessionId: Int, domain: String) {
        viewModelScope.launch {
            repository.removeSiteFromSession(sessionId, domain)
            blockingSessionManager.checkAndEnforce()
            loadSessionDetails(sessionId)
        }
    }

    /**
     * Reseta o estado do DetailsSheet (chamar quando fechar).
     */
    fun resetDetailsSheet() {
        _detailsSheetState.value = DetailsSheetState()
    }

    // ─────────────────────────────────────────────────────────────────────
    // ContentPickerSheet
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Carrega os sites já bloqueados de uma sessão para o ContentPicker.
     * Apps instalados no dispositivo são carregados diretamente na UI porque
     * dependem de PackageManager (não do DB).
     */
    fun loadContentPickerSites(sessionId: Int) {
        viewModelScope.launch {
            val sites = repository.getBlockedSitesForSession(sessionId)
            _contentPickerState.value = _contentPickerState.value.copy(
                sites = sites
            )
        }
    }

    /**
     * Atualiza o texto digitado no campo de novo site.
     */
    fun updateSiteInput(input: String) {
        _contentPickerState.value = _contentPickerState.value.copy(siteInput = input)
    }

    /**
     * Adiciona o site digitado à sessão.
     * @return true se adicionou com sucesso, false se input estava vazio.
     */
    fun addSiteToSession(sessionId: Int): Boolean {
        val site = _contentPickerState.value.siteInput.trim()
        if (site.isEmpty()) return false

        viewModelScope.launch {
            _contentPickerState.value = _contentPickerState.value.copy(isSaving = true)
            repository.addSiteToSession(sessionId, site)
            _contentPickerState.value = _contentPickerState.value.copy(
                siteInput = "",
                isSaving = false
            )
            loadContentPickerSites(sessionId)
        }
        return true
    }

    /**
     * Adiciona um pacote de app à sessão (se ainda não estiver bloqueado).
     */
    fun addAppToSession(sessionId: Int, packageName: String) {
        viewModelScope.launch {
            repository.addAppToSession(sessionId, packageName)
        }
    }

    /**
     * Reseta o estado do ContentPicker (chamar quando fechar).
     */
    fun resetContentPicker() {
        _contentPickerState.value = ContentPickerState()
    }

    // ─────────────────────────────────────────────────────────────────────
// Sub-estados tipados
// ─────────────────────────────────────────────────────────────────────

    data class DetailsSheetState(
        val blockedApps: List<String> = emptyList(),
        val blockedSites: List<String> = emptyList(),
        val isLoading: Boolean = false
    )

    data class ContentPickerState(
        val sites: List<String> = emptyList(),
        val siteInput: String = "",
        val isSaving: Boolean = false
    )
}
