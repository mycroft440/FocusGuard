package com.focusguard.service

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Keeps browser accessibility-tree work off the service's Main transition coroutine. */
internal class WebsiteTreeWorker(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    suspend fun <T> run(block: suspend () -> T): T = withContext(dispatcher) {
        block()
    }
}
