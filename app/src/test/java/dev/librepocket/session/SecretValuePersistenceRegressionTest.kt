package dev.librepocket.session

import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Room append/export regression for complete JSON secret-value redaction. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SecretValuePersistenceRegressionTest {

    private val now = 1_700_000_000_000L
    private lateinit var db: LibrePocketDb
    private lateinit var store: RoomSessionStore
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        db = LibrePocketDb.openInMemory(ApplicationProvider.getApplicationContext())
        store = RoomSessionStore(db) { now }
        tempDir = Files.createTempDirectory("secret-value-redaction").toFile()
    }

    @After
    fun tearDown() {
        if (this::db.isInitialized) db.close()
        if (this::tempDir.isInitialized) tempDir.deleteRecursively()
    }

    @Test
    fun roomAppendAndJsonlExportDoNotPersistOrExportSecretTails() = runBlocking {
        val sid = store.createSession("synthetic regression", "fake/provider")
        val secret = """fake-alpha beta & tail?x=three with "quote" and \\ slash 雪秘密🔐"""
        val transcriptJson = buildJsonObject {
            put("password", secret)
            put("requestId", "safe-request-42")
        }.toString()

        store.appendEvent(
            TranscriptEvent(
                sessionId = sid,
                runId = "synthetic-run",
                kind = "user",
                text = transcriptJson,
                createdAt = now,
            ),
        )

        val stored = store.loadEvents(sid).single().text
        assertRedactedJsonValue(stored, secret)

        val export = File(tempDir, "synthetic-session.jsonl")
        store.exportJsonl(sid, export)
        val exportedEvent = JsonlCodec.decode(1, export.readText(Charsets.UTF_8).trimEnd('\n', '\r'))
        assertRedactedJsonValue(exportedEvent.text, secret)
        assertFalse("export leaked the synthetic secret", export.readText(Charsets.UTF_8).contains(secret))
    }

    private fun assertRedactedJsonValue(text: String, secret: String) {
        val event = Json.parseToJsonElement(text).jsonObject
        assertEquals("⟦REDACTED⟧", event.getValue("password").jsonPrimitive.content)
        assertEquals("safe-request-42", event.getValue("requestId").jsonPrimitive.content)
        assertFalse("stored/exported text leaked the synthetic secret: $text", text.contains(secret))
        assertFalse("stored/exported text leaked a secret tail: $text", text.contains("beta & tail"))
        assertTrue("legacy JSON redaction marker missing: $text", text.contains("⟦REDACTED⟧"))
    }
}
