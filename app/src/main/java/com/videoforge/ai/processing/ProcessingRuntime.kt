package com.videoforge.ai.processing

/**
 * Process-local cooperative pause switch shared by the Activity and WorkManager worker.
 * It is intentionally not persisted: a restarted worker must start in the unpaused state.
 */
object ProcessingRuntime {
    @Volatile
    var paused: Boolean = false
}
