package com.example.client.data.repository

import com.example.client.network.ApiEnvelope
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import retrofit2.Response
import java.io.IOException

// How every Admin call (failed syncs #50, user activity #51) turns the server's answer into an [AdminResult].

/**
 * Makes one Admin call and sorts the answer: data, no connection, refused account, no such record, a request the server
 * would not accept (400, 409, with its own message) or anything else. Never throws for network or parsing problems.
 */
internal suspend fun <T> adminCall(request: suspend () -> Response<ApiEnvelope<T>>): AdminResult<T> {
    val response = try {
        request()
    } catch (e: IOException) {
        return AdminResult.Offline
    } catch (e: JsonParseException) {
        return AdminResult.Failed
    }
    if (response.isSuccessful) {
        return response.body()?.data?.let { AdminResult.Success(it) } ?: AdminResult.Failed
    }
    return when (response.code()) {
        401, 403 -> AdminResult.Denied
        404 -> AdminResult.NotFound
        409 -> AdminResult.Conflict(serverMessage(response))
        400 -> AdminResult.Invalid(serverMessage(response))
        else -> AdminResult.Failed
    }
}

/** The server's own message from its error envelope, or null if there is none. Never throws. */
private fun serverMessage(response: Response<*>): String? = try {
    JsonParser.parseString(response.errorBody()?.string().orEmpty())
        .asJsonObject["error"]?.asJsonObject?.get("message")?.asString
} catch (e: Exception) {
    null
}

internal inline fun <T, R> AdminResult<T>.map(transform: (T) -> R): AdminResult<R> =
    if (this is AdminResult.Success) AdminResult.Success(transform(value)) else failure()

/** A result that carries no data can be handed on as a result of any type. */
@Suppress("UNCHECKED_CAST")
internal fun <T> AdminResult<*>.failure(): AdminResult<T> = this as AdminResult<T>
