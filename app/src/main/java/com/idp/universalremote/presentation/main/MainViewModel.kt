package com.idp.universalremote.presentation.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.idp.universalremote.domain.model.ConnectionState
import com.idp.universalremote.domain.repository.RemoteCommandRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val remoteCommandRepository: RemoteCommandRepository
) : ViewModel() {

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    val connectionState: StateFlow<ConnectionState> = remoteCommandRepository.connectionState

    fun markReady() {
        viewModelScope.launch {
            delay(400)
            _isReady.value = true
        }
    }
}
