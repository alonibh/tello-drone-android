package com.alonibh.tellodrone.data

import com.alonibh.tellodrone.domain.DroneSessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object TelloSessionStore {
    private val mutableState = MutableStateFlow(DroneSessionState())
    val state: StateFlow<DroneSessionState> = mutableState.asStateFlow()

    fun set(value: DroneSessionState) { mutableState.value = value }
    fun update(transform: (DroneSessionState) -> DroneSessionState) { mutableState.value = transform(mutableState.value) }
}
