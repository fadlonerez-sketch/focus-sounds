package com.focussounds.core

/**
 * The fixed MVP sound catalog. [id] is the stable value stored in history; never change it.
 * [assetPath] is relative to the platform's bundled-asset root.
 */
enum class FocusSound(val id: String, val displayName: String, val assetPath: String) {
    BROWN_NOISE("brown_noise", "Brown Noise", "sounds/brown_noise.flac"),
    WHITE_NOISE("white_noise", "White Noise", "sounds/white_noise.flac"),
    OCEAN("ocean", "Ocean", "sounds/ocean.flac"),
    STREAM("stream", "Stream", "sounds/stream.flac"),
    FOREST("forest", "Forest", "sounds/forest.flac");

    companion object {
        fun fromId(id: String): FocusSound? = entries.firstOrNull { it.id == id }
    }
}

/** Why a session ended. [key] is the stable value stored in history. */
enum class EndReason(val key: String) {
    /** A timed session reached its planned duration. */
    COMPLETED("completed"),

    /** The user stopped the session (app, notification, or system media controls). */
    STOPPED("stopped"),

    /** Playback ended unexpectedly: playback error, service teardown, or the process died. */
    INTERRUPTED("interrupted"),

    /** The user stopped the session before any focus time was recorded. */
    CANCELLED("cancelled");

    companion object {
        fun fromKey(key: String?): EndReason? = entries.firstOrNull { it.key == key }
    }
}

/** Persisted lifecycle of a session row. */
enum class SessionRowState(val key: String) {
    ACTIVE("active"),
    FINISHED("finished");

    companion object {
        fun fromKey(key: String): SessionRowState = entries.first { it.key == key }
    }
}

/** Why playback is currently not producing sound while a session is active. */
enum class PauseCause {
    /** Paused by the user from the app, notification, or a media button. */
    USER,

    /** Another app took permanent audio focus (e.g. the user started music). Not auto-resumed. */
    AUDIO_FOCUS_LOSS,

    /** Temporary interruption such as a phone call or alarm. Android lets playback resume afterwards. */
    TRANSIENT_INTERRUPTION,

    /** Headphones or a Bluetooth device disconnected. Not auto-resumed. */
    HEADPHONES_DISCONNECTED,

    OTHER,
}

/** Built-in activities, identified by a stable key so they survive future renames of their labels. */
enum class BuiltInActivity(val key: String, val displayName: String) {
    STUDYING("studying", "Studying"),
    PROGRAMMING("programming", "Programming"),
    READING("reading", "Reading"),
    WRITING("writing", "Writing"),
    PROJECT("project", "Working on a project"),
    MEDITATION("meditation", "Meditation"),
}

object Limits {
    const val MAX_ACTIVITY_NAME_LENGTH = 40
    const val MIN_TIMED_MINUTES = 1
    const val MAX_TIMED_MINUTES = 600
    val PRESET_MINUTES = listOf(25, 45, 60)
}

/**
 * Validates a user-supplied activity name. Returns the cleaned name, or null when it is unusable.
 */
fun cleanActivityName(raw: String): String? {
    val name = raw.trim().replace(Regex("\\s+"), " ")
    if (name.isEmpty() || name.length > Limits.MAX_ACTIVITY_NAME_LENGTH) return null
    return name
}
