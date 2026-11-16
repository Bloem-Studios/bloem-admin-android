package com.bloemstudios.admin.platform

import androidx.lifecycle.ViewModel
import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.network.api.applyOrganizationEntitlement
import com.bloemstudios.admin.network.api.getPlatformOrganizationEntitlement
import com.bloemstudios.admin.network.api.listOfferedEntitlementTemplates
import com.bloemstudios.admin.network.api.previewOrganizationEntitlement
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** What a dry-run showed for the plan the administrator chose; applying it needs this very preview. */
data class PlanPreview(val template: EntitlementTemplate, val dryRun: EntitlementDryRun)

data class OrganizationPlanState(
    val loading: Boolean = true,
    /** False when the server has no plan routes (an older server): the section is not shown at all. */
    val available: Boolean = true,
    val entitlement: OrganizationEntitlement? = null,
    val templates: List<EntitlementTemplate> = emptyList(),
    val templatesError: Boolean = false,
    val selectedKey: String? = null,
    val preview: PlanPreview? = null,
    val previewing: Boolean = false,
    val applying: Boolean = false,
    val error: ErrorText? = null,
    val notice: PlatformNotice? = null,
)

/**
 * The Plan section of one organization: the plan it is on, the plans on offer, and changing it in the web's two steps. A
 * preview (dry-run) comes first and shows what would change; only that preview can be applied, and the apply is sent once,
 * with a key that makes an accidental repeat harmless. When the organization moved on while the administrator was looking,
 * the preview is dropped and they preview again.
 */
class OrganizationPlanViewModel(
    private val newIdempotencyKey: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {
    private val _state = MutableStateFlow(OrganizationPlanState())
    val state: StateFlow<OrganizationPlanState> = _state

    suspend fun load(session: PlatformSession, organizationId: String) {
        _state.update { it.copy(loading = it.entitlement == null, error = null) }
        val entitlement = try {
            Result.success(session.read { it.getPlatformOrganizationEntitlement(organizationId) })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
        entitlement.exceptionOrNull()?.let { failure ->
            // An older server has no plan routes: say nothing about plans.
            if (failure is ApiError && failure.statusCode in ABSENT) {
                _state.update { it.copy(loading = false, available = false) }
            } else {
                _state.update { it.copy(loading = false, error = failure.platformFailureText(R.string.platform_plan_load_error)) }
            }
            return
        }
        val templates = try {
            Result.success(session.read { it.listOfferedEntitlementTemplates() })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
        _state.update {
            it.copy(
                loading = false, available = true, entitlement = entitlement.getOrThrow(),
                templates = templates.getOrDefault(it.templates), templatesError = templates.isFailure,
            )
        }
    }

    /** Chooses the plan to preview. Any earlier preview is dropped: it was for another plan. */
    fun select(key: String) = _state.update {
        if (it.selectedKey == key) it else it.copy(selectedKey = key, preview = null, error = null, notice = null)
    }

    suspend fun preview(session: PlatformSession, organizationId: String) {
        val current = _state.value
        val template = current.templates.firstOrNull { it.key == current.selectedKey } ?: return
        if (current.previewing || current.applying) return
        _state.update { it.copy(previewing = true, preview = null, error = null, notice = null) }
        try {
            val dryRun = session.read { it.previewOrganizationEntitlement(organizationId, template.key, template.revision) }
            _state.update { it.copy(previewing = false, preview = PlanPreview(template, dryRun)) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            _state.update { it.copy(previewing = false, error = error.platformFailureText(R.string.platform_plan_preview_error)) }
        }
    }

    /** Applies the previewed plan. True when the organization is now on it; false when nothing was applied. */
    suspend fun apply(session: PlatformSession, organizationId: String, organizationName: String): Boolean {
        val current = _state.value
        val preview = current.preview ?: return false
        if (!preview.dryRun.changed || current.applying) return false
        _state.update { it.copy(applying = true, error = null, notice = null) }
        val key = newIdempotencyKey()
        try {
            val result = session.write {
                it.applyOrganizationEntitlement(organizationId, preview.template.key, preview.template.revision, preview.dryRun.dry_run_token, key)
            }
            val refreshed = try {
                session.read { it.getPlatformOrganizationEntitlement(organizationId) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            _state.update {
                it.copy(
                    applying = false, preview = null, selectedKey = null, entitlement = refreshed ?: it.entitlement,
                    notice = if (result.changed) PlatformNotice.PlanApplied(organizationName, preview.template.label)
                    else PlatformNotice.PlanUnchanged(organizationName, preview.template.label),
                )
            }
            return true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (changed: PlatformStateChanged) {
            _state.update { it.copy(applying = false, preview = null, error = ErrorText.Res(R.string.platform_plan_changed)) }
        } catch (error: Exception) {
            _state.update {
                it.copy(
                    applying = false,
                    error = if (error is ApiError && error.statusCode == 403) ErrorText.Res(R.string.platform_orgs_no_access)
                    else ErrorText.Res(R.string.platform_plan_apply_error),
                )
            }
        }
        return false
    }

    fun dismissMessages() = _state.update { it.copy(error = null, notice = null) }

    private companion object {
        val ABSENT = setOf(404, 405, 501)
    }
}
