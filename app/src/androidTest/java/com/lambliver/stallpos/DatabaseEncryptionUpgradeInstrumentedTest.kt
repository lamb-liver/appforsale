package com.lambliver.stallpos

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lambliver.stallpos.data.*
import java.io.File
import java.security.SecureRandom
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseEncryptionUpgradeInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private val databaseNames = mutableListOf<String>()

    @After
    fun cleanup() {
        databaseNames.forEach { name ->
            context.deleteDatabase(name)
            listOf(".v2.tmp", ".v1.backup").forEach { suffix ->
                File(context.getDatabasePath(name).path + suffix).delete()
            }
        }
    }

    @Test
    fun keystoreWrappedPassphrase_isStableAcrossReads() {
        val first = DatabasePassphraseStore.getOrCreate(context)
        val second = DatabasePassphraseStore.getOrCreate(context)
        try {
            assertEquals(32, first.size)
            assertArrayEquals(first, second)
        } finally {
            first.fill(0)
            second.fill(0)
        }
    }

    @Test
    fun plaintextV1_isEncryptedMigratedValidatedAndReopened() = runBlocking {
        val source = createV1Database()
        val passphrase = randomPassphrase()
        try {
            assertTrue(DatabaseEncryptionUpgrader.isPlaintext(source))
            DatabaseEncryptionUpgrader.upgrade(context, source, passphrase)

            assertFalse(DatabaseEncryptionUpgrader.isPlaintext(source))
            assertFalse(File(source.parentFile, "${source.name}.v2.tmp").exists())
            assertFalse(File(source.parentFile, "${source.name}.v1.backup").exists())
            val reopened = StallPosV2Database.open(context, source.absolutePath, passphrase.copyOf())
            assertEquals(2, reopened.openHelper.writableDatabase.version)
            assertEquals(1, reopened.posDao().products().size)
            reopened.close()

            val wrong = StallPosV2Database.open(context, source.absolutePath, randomPassphrase())
            assertTrue(runCatching { wrong.openHelper.writableDatabase }.isFailure)
            wrong.close()
        } finally {
            passphrase.fill(0)
        }
    }

    @Test
    fun everyInjectedFailure_restoresReadablePlaintextV1() = runBlocking {
        UpgradeStage.entries.forEach { failedStage ->
            val source = createV1Database()
            val passphrase = randomPassphrase()
            try {
                val result = runCatching {
                    DatabaseEncryptionUpgrader.upgrade(context, source, passphrase) { stage ->
                        if (stage == failedStage) error("injected $stage")
                    }
                }
                assertTrue("$failedStage must fail", result.isFailure)
                assertTrue("$failedStage must restore plaintext", DatabaseEncryptionUpgrader.isPlaintext(source))
                val legacy = Room.databaseBuilder(context, StallPosDatabase::class.java, source.name)
                    .allowMainThreadQueries()
                    .build()
                assertEquals("fixture", legacy.posDao().products().single().id)
                legacy.close()
            } finally {
                passphrase.fill(0)
            }
        }
    }

    private suspend fun createV1Database(): File {
        val name = "m1-native-${UUID.randomUUID()}.db".also(databaseNames::add)
        val legacy = Room.databaseBuilder(context, StallPosDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()
        legacy.posDao().upsertProducts(listOf(ProductEntity("fixture", "Fixture", 100, "", 5, 0)))
        legacy.close()
        return context.getDatabasePath(name)
    }

    private fun randomPassphrase() = ByteArray(32).also(SecureRandom()::nextBytes)
}
