package com.bloemstudios.admin.network.api

import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.serverconfig.RequestSettings

/** The request settings with the ETag that guards a write. */
suspend fun ApiClient.getRequestSettingsWithValidator(): Pair<RequestSettings, String?> =
    getWithValidator("/api/v2/admin/request-settings")

/** Guarded write of the whole request settings: sends [validator] as If-Match and never replays after a 401. */
suspend fun ApiClient.updateRequestSettings(settings: RequestSettings, validator: String): RequestSettings =
    putConditional<RequestSettings, RequestSettings>("/api/v2/admin/request-settings", settings, validator)
