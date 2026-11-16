package com.bloemstudios.admin.serverconfig

import androidx.annotation.StringRes
import kotlinx.serialization.Serializable

/** GET /api/v2/admin/settings/restart-keys: keys, and whole key namespaces, that only take effect after a restart. */
@Serializable
data class RestartKeys(val keys: List<String> = emptyList(), val prefixes: List<String> = emptyList()) {
    fun requiresRestart(key: String): Boolean = key in keys || prefixes.any { key.startsWith(it) }
}

/** GET /api/v2/admin/settings/sensitive-status: which secrets are set (never their values) and which the environment owns. */
@Serializable
data class SensitiveStatus(val configured: List<String> = emptyList(), val managed_by_env: List<String> = emptyList())

/** The answer to PUT /api/v2/admin/settings: the saved values and whether a restart is now needed. */
@Serializable
data class SettingsReceipt(
    val values: Map<String, String> = emptyMap(),
    val restart_required: Boolean = false,
    val restart_required_keys: List<String> = emptyList(),
)

/** The 202 answer to a restart request: restart_requested, or already_requested while the server is already shutting down. */
@Serializable
data class RestartAnswer(val status: String = "", val message: String = "", val notified_sessions: Int = 0) {
    val alreadyRequested get() = status == "already_requested"
}

@Serializable
data class SettingsWriteRequest(val values: Map<String, String>)

/**
 * Everything the settings pages read in one go: the effective values (stored values over the server's defaults, secrets
 * left out) with the validator of that snapshot, the restart-only keys, the secrets that are set, and the server status.
 */
data class SettingsSnapshot(
    val values: Map<String, String>,
    val validator: String,
    val restart: RestartKeys,
    val sensitive: SensitiveStatus,
    val status: ServerStatus,
) {
    fun reports(key: String) = key in values
}

/**
 * The edits on top of one snapshot of the server's values. Immutable: every change returns a new draft, so a screen can
 * hold it as state. An edit back to the server's own value is not an edit.
 */
data class SettingsDraft(val base: Map<String, String>, val edits: Map<String, String> = emptyMap()) {
    fun value(key: String): String = edits[key] ?: base[key].orEmpty()
    fun isDirty(key: String): Boolean = key in edits
    val dirtyKeys: Set<String> get() = edits.keys

    fun set(key: String, value: String): SettingsDraft =
        if (value == base[key].orEmpty()) copy(edits = edits - key) else copy(edits = edits + (key to value))

    fun discard(): SettingsDraft = copy(edits = emptyMap())

    /** The edits for [keys] that differ from the server, ready to send. */
    fun changes(keys: Set<String>): Map<String, String> = edits.filterKeys { it in keys }

    /** A newer read of the server: the new values become the base, and only the edits that still differ stay. */
    fun rebase(newBase: Map<String, String>): SettingsDraft =
        SettingsDraft(newBase, edits.filter { (key, value) -> value != newBase[key].orEmpty() })

    /** [keys] were saved and the server answered with [savedValues]: they are no longer edits. */
    fun saved(keys: Set<String>, savedValues: Map<String, String>): SettingsDraft =
        SettingsDraft(base + savedValues, edits - keys)
}

sealed interface SettingKind {
    data object Toggle : SettingKind

    /** A whole number, with the unit the web shows after it and the range the web documents (the server checks too). */
    data class Number(val unit: String? = null, val min: Long? = null, val max: Long? = null) : SettingKind {
        fun problem(text: String): NumberProblem? {
            val n = text.trim().toLongOrNull() ?: return NumberProblem.NotANumber
            if (min != null && n < min) return NumberProblem.TooLow
            if (max != null && n > max) return NumberProblem.TooHigh
            return null
        }
    }

    data class Choice(val options: List<ChoiceOption>) : SettingKind
    data class Text(val example: String? = null) : SettingKind

    /** A secret: only whether it is set is shown. */
    data object Secret : SettingKind
}

enum class NumberProblem { NotANumber, TooLow, TooHigh }

data class ChoiceOption(val value: String, @StringRes val labelRes: Int)

/** Every restart reason the server gave, newest form first: the list, else the single legacy reason. */
val ServerStatus.allRestartReasons: List<String>
    get() = restart_required_reasons.filter { it.isNotBlank() }
        .ifEmpty { listOfNotNull(restart_required_reason?.takeIf { it.isNotBlank() }) }

private const val SETTING_REASON_PREFIX = "setting:"

/**
 * The restart reasons as words: "setting:<key>" is that setting's name from the catalogue (or the key in plain words when
 * the catalogue does not know it), any other slug is humanized. Repeats are listed once. Null when there is nothing to say.
 */
fun describeRestartReasons(reasons: List<String>, labelFor: (String) -> String?): String? =
    reasons.filter { it.isNotBlank() }.map { reason ->
        if (reason.startsWith(SETTING_REASON_PREFIX)) {
            val key = reason.removePrefix(SETTING_REASON_PREFIX)
            labelFor(key) ?: plainKey(key)
        } else plainWords(reason)
    }.distinct().takeIf { it.isNotEmpty() }?.joinToString(", ")

private fun plainWords(text: String) = text.replace('_', ' ').replace('.', ' ').trim().replaceFirstChar { it.uppercase() }
private fun plainKey(key: String) = plainWords(key)
