package com.affclix.sdk.internal

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Request signatures: sig= is the hex HMAC-SHA256 of the request under the app
 * secret — over the raw JSON body for a POST, over the query string (without
 * sig=) for a GET. The MMP checks it against the secret it derives from the
 * app key.
 */
internal object RequestSigner {
    fun sign(secret: String, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
