package com.idp.universalremote.presentation.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.idp.universalremote.domain.model.ConnectionState
import com.idp.universalremote.domain.model.TvDevice
import com.idp.universalremote.domain.repository.DeviceDiscoveryRepository
import com.idp.universalremote.domain.repository.RemoteCommandRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SearchDevicesViewModel @Inject constructor(
    private val discovery: DeviceDiscoveryRepository,
    private val remoteCommandRepository: RemoteCommandRepository
) : ViewModel() {

    private val _devices = MutableStateFlow<List<TvDevice>>(emptyList())
    val devices: StateFlow<List<TvDevice>> = _devices.asStateFlow()

    val state: StateFlow<ConnectionState> = remoteCommandRepository.connectionState

    private var job: Job? = null

    fun refresh() {
        job?.cancel()
        _devices.value = emptyList()
        job = viewModelScope.launch {
            discovery.discover().collect { list -> _devices.value = list }
        }
    }

    fun connect(device: TvDevice) {
        viewModelScope.launch { remoteCommandRepository.connect(device) }
    }

    fun pair(code: String) {
        viewModelScope.launch { remoteCommandRepository.pair(code) }
    }

    fun cancelConnect() {
        viewModelScope.launch { remoteCommandRepository.disconnect() }
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch { discovery.stop() }
    }
}
