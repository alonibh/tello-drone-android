package com.alonibh.tellodrone.service

/**
 * Serializes one Android network-selection attempt. It prevents callbacks already queued by the
 * platform from activating a new physical session after a user cancellation or service teardown.
 */
internal class ConnectionAttemptGate {
    private var requested = false
    private var starting = false
    private var active = false

    @Synchronized
    fun begin(): Boolean {
        if (requested || starting || active) return false
        requested = true
        return true
    }

    @Synchronized
    fun claimNetwork(): Boolean {
        if (!requested || starting || active) return false
        starting = true
        return true
    }

    /** Executes [activate] while the gate is held, making session publication atomic with activation. */
    @Synchronized
    fun activate(activate: () -> Unit): Boolean {
        if (!requested || !starting || active) return false
        starting = false
        active = true
        activate()
        return true
    }

    @Synchronized
    fun isRequested(): Boolean = requested

    /** Executes [clearSession] atomically with invalidating every queued callback. */
    @Synchronized
    fun finish(clearSession: () -> Unit) {
        requested = false
        starting = false
        active = false
        clearSession()
    }
}
