package com.bloemstudios.admin.network.api

import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.push.PushKindsRequest
import com.bloemstudios.admin.push.PushKindsResponse
import com.bloemstudios.admin.push.PushRegisterRequest
import com.bloemstudios.admin.push.PushRegistered
import com.bloemstudios.admin.push.PushWidgetsRequest
import com.bloemstudios.admin.push.PushWidgetsResponse

/** The feature token a Bloem server lists in `GET /api/bloem/v2/capabilities` when it can push to this app. */
const val ADMIN_PUSH_TOKEN = "admin_push_v1"

/** Listed with [ADMIN_PUSH_TOKEN] by a server that accepts the widget report and sends silent widget wakes. */
const val ADMIN_WIDGET_WAKES_TOKEN = "admin_widget_wakes_v1"

/** The admin push routes of a Bloem server. They exist only where [ADMIN_PUSH_TOKEN] is listed. */
object PushPaths {
    private const val BASE = "/api/bloem/v2/admin/push"
    const val DEVICES = "$BASE/devices"
    fun device(deviceId: String) = "$DEVICES/$deviceId"
    fun kinds(deviceId: String) = "${device(deviceId)}/kinds"
    fun retire(deviceId: String) = "${device(deviceId)}/retire"
    fun widgets(deviceId: String) = "${device(deviceId)}/widgets"
    fun content(contentId: String) = "$BASE/content/$contentId"
}

/** Registers or refreshes this installation. Needs the administrator's own sign-in. */
suspend fun ApiClient.registerPushDevice(request: PushRegisterRequest): PushRegistered = post(PushPaths.DEVICES, request)

/** Tells the server which kinds to stop (or resume) sending to this device. */
suspend fun ApiClient.setPushKinds(deviceId: String, kinds: Map<String, Boolean>): PushKindsResponse =
    patch(PushPaths.kinds(deviceId), PushKindsRequest(kinds))

/** Tells the server which widget channels this device's placed widgets show, so it sends widget wakes for them. */
suspend fun ApiClient.setPushWidgets(deviceId: String, placed: Boolean, channels: List<String>): PushWidgetsResponse =
    put(PushPaths.widgets(deviceId), PushWidgetsRequest(placed, channels))

/** Removes this device with the administrator's sign-in. The retire route works without one. */
suspend fun ApiClient.deletePushDevice(deviceId: String) = delete(PushPaths.device(deviceId))
