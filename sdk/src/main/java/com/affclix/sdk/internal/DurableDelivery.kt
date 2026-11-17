package com.affclix.sdk.internal

/**
 * Sends a request that must survive the process being killed: it is saved before
 * the first attempt and removed once the server has answered. A transient failure
 * leaves it saved for [EventQueueWorker]; a resend is dropped by event_id.
 *
 * @return the saved row, for the caller's failure handling; null when not saved
 */
internal suspend fun deliverDurably(
    save: () -> Long?,
    remove: (Long) -> Unit,
    isTransient: (Exception) -> Boolean,
    send: suspend () -> Unit,
    onSaved: (Long?) -> Unit = {},
): Long? {
    val id = save()
    onSaved(id)
    try {
        send()
        id?.let(remove)
    } catch (e: Exception) {
        if (!isTransient(e)) id?.let(remove)
        throw e
    }
    return id
}
