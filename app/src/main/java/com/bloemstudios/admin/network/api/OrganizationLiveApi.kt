package com.bloemstudios.admin.network.api

import kotlinx.serialization.Serializable

/*
 * The organization live socket: a Bloem server tells an organization administrator's app what
 * changed, and the page reads the REST route again. It is not Silo's events socket: it is opened with a ticket minted by the
 * organization context pass, and the socket is bound to the organization that pass names.
 */

/** The public feature token a server advertises when it has the organization live socket. Without it the pages keep what they did. */
const val ORGANIZATION_LIVE_TOKEN = "organization_live_v1"

/** Context pass. Mints a single-use ticket (valid 30 seconds) for the socket. No body. */
const val ORGANIZATION_LIVE_TICKET_PATH = "/api/bloem/v2/admin/organization/live/ticket"

/** The socket. It sits outside `/admin` because a WebSocket client cannot send a header; the ticket rides in the subprotocol. */
const val ORGANIZATION_LIVE_SOCKET_PATH = "/api/bloem/v2/organization/live/ws"

/** What the server says about this socket. [channels] is what it serves: `org.streams` is missing when it cannot list live sessions. */
@Serializable
internal class OrganizationLiveTicket(val ticket: String, val protocol: String, val channels: List<String> = emptyList())
