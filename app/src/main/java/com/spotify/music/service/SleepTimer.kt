package com.spotify.music.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Sleep timer that counts down and fires a callback when expired. */
class SleepTimer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    private val _remainingSeconds = MutableStateFlow(0L)
    val remainingSeconds: StateFlow<Long> = _remainingSeconds.asStateFlow()

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    var onExpire: (() -> Unit)? = null

    fun start(minutes: Long) {
        stop()
        val total = minutes.coerceAtLeast(1) * 60L
        _remainingSeconds.value = total
        _active.value = true
        job = scope.launch {
            while (isActive && _remainingSeconds.value > 0) {
                delay(1000)
                _remainingSeconds.value = _remainingSeconds.value - 1
            }
            if (_remainingSeconds.value <= 0) {
                _active.value = false
                onExpire?.invoke()
            }
        }
    }

    /** remaining time in minutes (rounded up) */
    fun remainingMinutes(): Long = (remainingOrZero() + 59) / 60

    fun remainingOrZero(): Long {
        val s = _remainingSeconds.value
        return if (s > 0) s else 0L
    }

    fun stop() {
        job?.cancel()
        job = null
        _remainingSeconds.value = 0
        _active.value = false
    }
}