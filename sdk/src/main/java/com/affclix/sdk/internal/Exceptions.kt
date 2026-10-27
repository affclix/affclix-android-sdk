package com.affclix.sdk.internal

/**
 * Thrown when a required field is missing from a server response
 * (e.g. click_id absent from the /c endpoint response).
 */
internal class SdkException(val code: String, message: String) : Exception(message)

/**
 * Thrown when OkHttp receives a non-2xx HTTP response and retries are exhausted.
 */
internal class NetworkException(message: String, val statusCode: Int = -1) : Exception(message)
