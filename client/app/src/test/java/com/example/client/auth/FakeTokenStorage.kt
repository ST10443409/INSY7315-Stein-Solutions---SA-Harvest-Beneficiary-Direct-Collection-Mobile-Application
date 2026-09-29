package com.example.client.auth

/** In-memory [TokenStorage] for JVM tests. */
class FakeTokenStorage(private var stored: Session? = null) : TokenStorage {
    override fun load(): Session? = stored
    override fun save(session: Session) { stored = session }
    override fun clear() { stored = null }
}
