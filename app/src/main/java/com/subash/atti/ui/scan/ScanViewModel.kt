package com.subash.atti.ui.scan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.subash.atti.data.NearbyManager
import com.subash.atti.data.UserPrefs
import com.subash.atti.data.model.Peer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface ScanUiState {
    data object Searching : ScanUiState
    data object NobodyFound : ScanUiState
    data class Found(val peers: List<Peer>) : ScanUiState
}

class ScanViewModel(
    private val nearby: NearbyManager,
    userPrefs: UserPrefs,
) : ViewModel() {

    val myName: StateFlow<String> = MutableStateFlow("").also { name ->
        viewModelScope.launch {
            name.value = userPrefs.name.first().orEmpty()
            nearby.start(name.value)
        }
    }

    /** Flips to true once we've searched for a while with no results. */
    private val searchedLongEnough = MutableStateFlow(false)
    private var timerJob: Job? = null

    val uiState: StateFlow<ScanUiState> =
        combine(nearby.peers, searchedLongEnough) { peers, waitedLong ->
            when {
                peers.isNotEmpty() -> ScanUiState.Found(peers.sortedBy { it.name.lowercase() })
                waitedLong -> ScanUiState.NobodyFound
                else -> ScanUiState.Searching
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScanUiState.Searching)

    init {
        restartTimer()
    }

    fun rescan() {
        nearby.rescan()
        restartTimer()
    }

    private fun restartTimer() {
        timerJob?.cancel()
        searchedLongEnough.value = false
        timerJob = viewModelScope.launch {
            delay(NOBODY_FOUND_AFTER_MS)
            searchedLongEnough.value = true
        }
    }

    private companion object {
        const val NOBODY_FOUND_AFTER_MS = 15_000L
    }
}
