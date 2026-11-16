package com.bloemstudios.admin.auth

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.bloemstudios.admin.MainActivity

/**
 * Receives the `org.siloserver.silo:` redirect, hands it to [OAuthRedirects] and brings
 * [MainActivity] back to the front (which closes the Custom Tab). It never logs the URI and does
 * not forward it in the Intent.
 */
class OAuthRedirectActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A real redirect is a few hundred characters; anything longer is not ours and is not kept.
        intent?.data?.toString()?.takeIf { it.length <= MAX_REDIRECT_LENGTH }?.let(OAuthRedirects::deliver)
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }

    internal companion object {
        const val MAX_REDIRECT_LENGTH = 4096
    }
}
