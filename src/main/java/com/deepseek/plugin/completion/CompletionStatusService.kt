package com.deepseek.plugin.completion

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import java.util.concurrent.atomic.AtomicReference

/**
 * Completion state management service.
 *
 * Shares the current completion state across components so the status bar widget and Ghost Text renderer can query it.
 * Every state change automatically notifies the status bar to refresh.
 */
@Service(Service.Level.APP)
class CompletionStatusService {

    companion object {
        private val LOG = Logger.getInstance(CompletionStatusService::class.java)

        val instance: CompletionStatusService
            get() = ApplicationManager.getApplication().getService(CompletionStatusService::class.java)
    }

    /** Completion state enum */
    enum class State {
        /** Idle */
        IDLE,
        /** Generating completion */
        GENERATING,
        /** Completion ready */
        READY,
        /** An error occurred */
        ERROR
    }

    private val stateRef = AtomicReference(State.IDLE)
    private var errorMessage: String = ""
    private var consecutiveErrors: Int = 0
    private var listeners: MutableList<(State) -> Unit> = mutableListOf()

    /** Gets the current state */
    fun getState(): State = stateRef.get()

    /** Gets the most recent error message */
    fun getErrorMessage(): String = errorMessage

    /** Gets the number of consecutive failures */
    fun getConsecutiveErrors(): Int = consecutiveErrors

    /** Resets the consecutive failure count */
    fun resetConsecutiveErrors() { consecutiveErrors = 0 }

    /** Switches to the GENERATING state */
    fun onGenerating() {
        val prev = stateRef.getAndSet(State.GENERATING)
        if (prev != State.GENERATING) {
            LOG.debug("Completion status: GENERATING")
            notifyListeners(State.GENERATING)
        }
    }

    /** Switches to the READY state (a completion result is available) */
    fun onReady() {
        consecutiveErrors = 0
        val prev = stateRef.getAndSet(State.READY)
        if (prev != State.READY) {
            LOG.debug("Completion status: READY")
            notifyListeners(State.READY)
        }
    }

    /** Switches to the ERROR state */
    fun onError(message: String) {
        consecutiveErrors++
        errorMessage = message
        val prev = stateRef.getAndSet(State.ERROR)
        if (prev != State.ERROR) {
            LOG.debug("Completion status: ERROR - $message")
            notifyListeners(State.ERROR)
        }
    }

    /** Switches to the IDLE state */
    fun onIdle() {
        consecutiveErrors = 0
        val prev = stateRef.getAndSet(State.IDLE)
        if (prev != State.IDLE) {
            notifyListeners(State.IDLE)
        }
    }

    /** Registers a state change listener */
    fun addListener(listener: (State) -> Unit) {
        synchronized(listeners) {
            listeners.add(listener)
        }
    }

    /** Removes a state change listener */
    fun removeListener(listener: (State) -> Unit) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }

    private fun notifyListeners(state: State) {
        synchronized(listeners) {
            listeners.forEach { it(state) }
        }
    }
}
