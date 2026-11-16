package com.bloemstudios.admin.serverconfig

import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.network.toErrorText
import com.bloemstudios.admin.network.api.getEffectiveSettingsWithValidator
import com.bloemstudios.admin.network.api.updateSetting
import kotlinx.coroutines.CancellationException

/**
 * The validator belongs to the settings snapshot read when editing began, never a later poll. It covers every
 * setting, so a change to any other key also makes the save a conflict.
 */
data class SettingEditSnapshot(val setting: AdminSetting, val validator: String)
sealed interface SettingSaveResult {
    data object Busy : SettingSaveResult
    data class Saved(val setting: AdminSetting) : SettingSaveResult
    data object Conflict : SettingSaveResult
    data class Failed(val error: ErrorText) : SettingSaveResult
}

suspend fun loadSettingForEdit(client: ApiClient, key: String): SettingEditSnapshot {
    val (values, validator) = client.getEffectiveSettingsWithValidator()
    require(!validator.isNullOrBlank()) { "The server did not provide an edit validator" }
    return SettingEditSnapshot(AdminSetting(key, values[key].orEmpty()), validator)
}

suspend fun saveSetting(client: ApiClient, original: SettingEditSnapshot, value: String): SettingSaveResult = try {
    val setting = client.updateSetting(original.setting.key, value, original.validator)
    SettingSaveResult.Saved(setting)
} catch (e: CancellationException) {
    throw e
} catch (e: ApiError) {
    if (e.statusCode == 412 || e.statusCode == 428) SettingSaveResult.Conflict
    else SettingSaveResult.Failed(e.toErrorText(R.string.error_could_not_update_setting))
} catch (_: Exception) {
    SettingSaveResult.Failed(ErrorText.Res(R.string.error_could_not_update_setting))
}
