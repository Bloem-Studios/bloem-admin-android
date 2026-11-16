package com.bloemstudios.admin.network.api

import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.nodes.NodeCapabilities
import com.bloemstudios.admin.nodes.NodeEnabledUpdate
import com.bloemstudios.admin.nodes.NodeConfigAck
import com.bloemstudios.admin.nodes.NodeConfigRef
import com.bloemstudios.admin.nodes.NodeSummary
import io.ktor.http.encodeURLPathPart

private fun nodePath(id: String, suffix: String = "") = "/api/v2/admin/nodes/${id.encodeURLPathPart()}$suffix"

/** What this server supports for nodes: the summary read and drain. An older server answers 404, so the page stays hidden. */
suspend fun ApiClient.getNodeCapabilities(): NodeCapabilities = get("/api/v2/admin/nodes/capabilities")

/** Every node's health, load and capacity, without addresses or configuration; read in full across pages. */
suspend fun ApiClient.listNodeSummaries(): List<NodeSummary> = readAllPages("/api/v2/admin/nodes/summary")

/** Stops new work on a transcode node while its sessions finish. A proxy node answers 409. */
suspend fun ApiClient.drainNode(id: String) = post(nodePath(id, "/drain"))

/** Lifts an administrator's drain request. */
suspend fun ApiClient.resumeNode(id: String) = post(nodePath(id, "/resume"))

/** The full node rows, read only for each node's `config_etag`, the validator a configuration write needs. */
suspend fun ApiClient.listNodeConfigurations(): List<NodeConfigRef> = readAllPages("/api/v2/admin/nodes")

/** Turns a node on or off (stored configuration only; running streams keep serving). Guarded by the node's `config_etag`. */
suspend fun ApiClient.setNodeEnabled(id: String, enabled: Boolean, validator: String): NodeConfigAck =
    putConditional(nodePath(id), NodeEnabledUpdate(enabled), validator)
