package com.bloemstudios.admin.network.api

/** Single-use ticket for the events socket. */
const val EVENTS_TICKET_PATH = "/api/v2/events/ws-ticket"

/** The events socket; the ticket rides in the subprotocol, never the URL. */
const val EVENTS_SOCKET_PATH = "/api/v2/events/ws"
