package com.example.client.auth

/** Persists the [Session] across process restarts. Implementations must store it encrypted. */
interface TokenStorage {
    fun load(): Session?
    fun save(session: Session)
    fun clear()
}
