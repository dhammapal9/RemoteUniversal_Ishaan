package com.idp.universalremote.presentation.remote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.idp.universalremote.domain.model.ConnectionState
import com.idp.universalremote.domain.model.RemoteKey
import com.idp.universalremote.domain.repository.RemoteCommandRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RemoteViewModel @Inject constructor(
    private val remoteCommandRepository: RemoteCommandRepository
) : ViewModel() {

    val state: StateFlow<ConnectionState> = remoteCommandRepository.connectionState

    fun send(key: RemoteKey) {
        viewModelScope.launch { remoteCommandRepository.send(key) }
    }

    fun sendText(text: String) {
        viewModelScope.launch { remoteCommandRepository.sendText(text) }
    }

    fun supportsIr(): Boolean = remoteCommandRepository.supportsIr()
}
