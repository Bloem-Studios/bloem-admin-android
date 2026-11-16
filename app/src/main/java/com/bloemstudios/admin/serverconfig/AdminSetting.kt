package com.bloemstudios.admin.serverconfig

import kotlinx.serialization.Serializable

@Serializable
data class AdminSetting(val key: String, val value: String, val restart_required: Boolean = false)

fun adminSettingsFromWire(values: Map<String, String>): List<AdminSetting> =
    values.entries.sortedBy { it.key }.map { AdminSetting(it.key, it.value) }

@Serializable
data class UpdateSettingRequest(val value: String)

@Serializable
data class ServerStatus(
    val started_at: String,
    val restart_required: Boolean,
    val restart_requested: Boolean,
    // Short machine-readable slug (e.g. "jellyfin_compat") explaining WHY a
    // restart is required. Optional/omitempty on the server side -- an
    // open-ended set, so this is surfaced as free text (humanized for
    // display) rather than switched over every possible value.
    val restart_required_reason: String? = null,
    val restart_required_reasons: List<String> = emptyList(),
    // Absent on servers that predate the dashboard health summary.
    val health: com.bloemstudios.admin.dashboard.ServerHealth? = null
) {
    /**
     * Humanizes the restart reasons for display (e.g. "jellyfin_compat" ->
     * "Jellyfin compat"), or null when there is nothing to show. Prefers
     * [restart_required_reasons] and falls back to [restart_required_reason].
     * The reason set is open-ended and machine-defined, so this is a generic
     * slug->words transform rather than a lookup table.
     */
    val humanizedRestartRequiredReason: String?
        get() {
            val reasons = restart_required_reasons.filter { it.isNotBlank() }
                .ifEmpty { listOfNotNull(restart_required_reason?.takeIf { it.isNotBlank() }) }
            return reasons.takeIf { it.isNotEmpty() }?.joinToString(", ") { reason ->
                reason.replace('_', ' ').replaceFirstChar { it.uppercase() }
            }
        }
}
