package com.example.client.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The database is opened with no destructive fallback (#55), so an update that bumps the schema without a migration
 * would stop the app opening on every phone that has the old one. This catches it first: the schemas Room exports
 * (app/schemas) say what the current version is, and there must be a migration for every step up to it.
 */
class MigrationsTest {

    @Test
    fun everyVersionSinceTheFirstRelease_hasAMigrationToTheNext() {
        val schemas = File("schemas/com.example.client.data.AppDatabase").listFiles { f -> f.extension == "json" }
        assertTrue("run from the app module, where Room exports its schemas", !schemas.isNullOrEmpty())
        val current = schemas!!.maxOf { it.nameWithoutExtension.toInt() }

        val expected = (FIRST_MIGRATED_VERSION until current).map { it to it + 1 }

        assertEquals(expected, ALL_MIGRATIONS.map { it.startVersion to it.endVersion })
    }
}
