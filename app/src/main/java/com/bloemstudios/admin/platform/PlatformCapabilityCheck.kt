package com.bloemstudios.admin.platform

import com.bloemstudios.admin.network.ApiClient

/**
 * Whether this server has organizations: the public capability document says `features.organization_memberships`. The
 * document is read once per server ([ApiClient.bloem]); a plain Silo server has none, and so does an older Bloem build. Any
 * doubt is "no": the page is then not offered, and the server still decides every request.
 */
suspend fun ApiClient.supportsPlatformOrganizations(): Boolean =
    bloem.capabilities()?.features?.organization_memberships == true
