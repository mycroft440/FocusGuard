package com.focusguard.repository

import com.focusguard.database.BlockSession
import com.focusguard.database.BlockSessionDao
import com.focusguard.database.SessionAppCrossRef
import com.focusguard.database.SessionAppCrossRefDao
import com.focusguard.database.SessionWebsiteCrossRef
import com.focusguard.database.SessionWebsiteCrossRefDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository para BlockSessions e seus relacionamentos (apps/sites bloqueados).
 *
 * Encapsula o acesso a 3 DAOs que antes eram chamados diretamente de composables
 * (SessionsListScreen, SessionDetailsSheet, ContentPickerSheet). Centraliza:
 *  - Listagem de sessões ativas por tipo (PASSWORD vs TIME)
 *  - Detalhes de uma sessão (apps + sites bloqueados)
 *  - Limpeza de apps/sites de uma sessão
 *  - Adição/remoção de apps/sites
 *
 * Substitui 11 sites de chamadas diretas a AppDatabase.getDatabase(context) na UI.
 */
@Singleton
class BlockSessionRepository @Inject constructor(
    private val blockSessionDao: BlockSessionDao,
    private val sessionAppCrossRefDao: SessionAppCrossRefDao,
    private val sessionWebsiteCrossRefDao: SessionWebsiteCrossRefDao
) {
    /** Flow de sessões ativas — observável em tempo real pela UI. */
    fun observeActiveSessions(): Flow<List<BlockSession>> =
        blockSessionDao.getAllActiveSessions()

    /** Apps bloqueados por uma sessão específica. */
    suspend fun getBlockedAppsForSession(sessionId: Int): List<String> = withContext(Dispatchers.IO) {
        sessionAppCrossRefDao.getAppsForSessions(listOf(sessionId))
    }

    /** Sites bloqueados por uma sessão específica. */
    suspend fun getBlockedSitesForSession(sessionId: Int): List<String> = withContext(Dispatchers.IO) {
        sessionWebsiteCrossRefDao.getWebsitesForSessions(listOf(sessionId))
    }

    /** Limpa todos os apps/sites bloqueados de uma sessão. */
    suspend fun clearAllBlockedContent(sessionId: Int) = withContext(Dispatchers.IO) {
        sessionAppCrossRefDao.deleteForSession(sessionId)
        sessionWebsiteCrossRefDao.deleteForSession(sessionId)
    }

    /** Adiciona um app a uma sessão. */
    suspend fun addAppToSession(sessionId: Int, packageName: String) = withContext(Dispatchers.IO) {
        sessionAppCrossRefDao.insert(SessionAppCrossRef(sessionId, packageName))
    }

    /** Remove um app de uma sessão. */
    suspend fun removeAppFromSession(sessionId: Int, packageName: String) = withContext(Dispatchers.IO) {
        sessionAppCrossRefDao.deleteSpecificApp(sessionId, packageName)
    }

    /** Adiciona um site a uma sessão. */
    suspend fun addSiteToSession(sessionId: Int, domain: String) = withContext(Dispatchers.IO) {
        sessionWebsiteCrossRefDao.insert(SessionWebsiteCrossRef(sessionId, domain))
    }

    /** Remove um site de uma sessão. */
    suspend fun removeSiteFromSession(sessionId: Int, domain: String) = withContext(Dispatchers.IO) {
        sessionWebsiteCrossRefDao.deleteSpecificWebsite(sessionId, domain)
    }
}
