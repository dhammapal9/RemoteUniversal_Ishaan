package com.idp.universalremote.presentation.apps

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.idp.universalremote.domain.model.ConnectionState
import com.idp.universalremote.domain.repository.RemoteCommandRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AppsViewModel @Inject constructor(
    private val remoteCommandRepository: RemoteCommandRepository
) : ViewModel() {

    val connectionState: StateFlow<ConnectionState> = remoteCommandRepository.connectionState

    fun launch(shortcut: AppShortcut) {
        viewModelScope.launch { remoteCommandRepository.send(shortcut.key) }
    }
}
