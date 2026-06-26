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
     * Inicia o estado do ContentPicker para uma sessão.
     * Reseta a lista de sites pendentes e o input.
     * Apps instalados no dispositivo são carregados na UI porque dependem
     * de PackageManager (não do DB) — o VM só cuida da lista de sites.
     */
    fun initContentPicker() {
        _contentPickerState.value = ContentPickerState()
    }

    /**
     * Atualiza o texto digitado no campo de novo site.
     */
    fun updateSiteInput(input: String) {
        _contentPickerState.value = _contentPickerState.value.copy(siteInput = input)
    }

    /**
     * Adiciona o site digitado à lista pendente (ainda não salva no DB).
     * O site é commitado no DB apenas quando bulkAddContent é chamado.
     * @return true se adicionou, false se input vazio ou site já na lista.
     */
    fun addPendingSite(): Boolean {
        val site = _contentPickerState.value.siteInput.trim().lowercase()
        if (site.isBlank()) return false
        if (_contentPickerState.value.sites.contains(site)) return false

        _contentPickerState.value = _contentPickerState.value.copy(
            sites = _contentPickerState.value.sites + site,
            siteInput = ""
        )
        return true
    }

    /**
     * Remove um site da lista pendente (antes de salvar).
     */
    fun removePendingSite(site: String) {
        _contentPickerState.value = _contentPickerState.value.copy(
            sites = _contentPickerState.value.sites - site
        )
    }

    /**
     * Salva em bulk todos os apps selecionados + sites pendentes na sessão.
     * Após salvar, chama checkAndEnforce() para sincronizar o bloqueio e
     * reseta o estado do ContentPicker.
     *
     * @param sessionId ID da sessão destino
     * @param selectedApps Lista de packageNames selecionados pelo usuário
     * @return true sempre (a função não falha — errores são logados)
     */
    fun bulkAddContent(
        sessionId: Int,
        selectedApps: List<String>
    ) {
        val sitesToSave = _contentPickerState.value.sites

        viewModelScope.launch {
            _contentPickerState.value = _contentPickerState.value.copy(isSaving = true)

            // Insere todos os apps selecionados
            selectedApps.forEach { pkg ->
                repository.addAppToSession(sessionId, pkg)
            }
            // Insere todos os sites pendentes
            sitesToSave.forEach { domain ->
                repository.addSiteToSession(sessionId, domain)
            }
            // Sincroniza o estado de bloqueio com o Accessibility Service
            blockingSessionManager.checkAndEnforce()

            _contentPickerState.value = ContentPickerState()
        }
    }

    /**
     * Reseta o estado do ContentPicker (chamar quando fechar sem salvar).
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
