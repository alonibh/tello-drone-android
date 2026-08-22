package com.alonibh.tellodrone.ui

import com.alonibh.tellodrone.domain.DroneConnectionState
import com.alonibh.tellodrone.domain.DroneSessionState
import com.alonibh.tellodrone.domain.FlightState

internal fun DroneSessionState.isTakeoffEligible(): Boolean =
    connection == DroneConnectionState.Connected && flight == FlightState.Grounded && telemetry.isFresh

/** One-shot UI gate for the aircraft confirmation dialog. */
internal class TakeoffConfirmationGate {
    private var awaitingConfirmation = false

    fun request(state: DroneSessionState): Boolean {
        awaitingConfirmation = state.isTakeoffEligible()
        return awaitingConfirmation
    }

    fun cancel() {
        awaitingConfirmation = false
    }

    fun dismissIfIneligible(state: DroneSessionState): Boolean {
        if (awaitingConfirmation && !state.isTakeoffEligible()) {
            awaitingConfirmation = false
        }
        return awaitingConfirmation
    }

    fun confirm(state: DroneSessionState, onConfirmed: () -> Unit): Boolean {
        if (!awaitingConfirmation) return false
        awaitingConfirmation = false
        if (!state.isTakeoffEligible()) return false
        onConfirmed()
        return true
    }
}
