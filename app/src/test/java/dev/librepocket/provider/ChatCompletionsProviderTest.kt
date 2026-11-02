package dev.librepocket.provider

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ChatCompletionsProviderTest {

    private fun testConfig() = ProviderConfig(
        id = "00000000-0000-0000-0000-000000000001",
        label = "test",
        baseUrl = "https://api.openai.com/v1",
        protocol = ProviderProtocol.CHAT_COMPLETIONS,
        apiKeyRef = "provider_key/00000000-0000-0000-0000-000000000001",
    )

    @Test
    fun jsonSchemaValidationContract() {
        // Valid creation
        val valid = ResponseFormat.JsonSchema(
            name = "valid_name-123",
            schemaJson = """{"type":"object"}""",
            description = "A description",
            strict = true,
        )
        assertEquals("valid_name-123", valid.name)
        assertEquals("""{"type":"object"}""", valid.schemaJson)
        assertEquals("A description", valid.description)
        assertTrue(valid.strict)

        // Blank name rejected
        try {
            ResponseFormat.JsonSchema(name = "   ", schemaJson = "{}")
            fail("expected blank name to throw")
        } catch (e: IllegalArgumentException) {
            assertEquals("name must not be blank", e.message)
        }

        // Invalid name characters rejected
        try {
            ResponseFormat.JsonSchema(name = "invalid name with spaces", schemaJson = "{}")
            fail("expected invalid name to throw")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("name must match") == true)
        }

        // Overlength name (> 64 chars) rejected
        try {
            ResponseFormat.JsonSchema(name = "a".repeat(65), schemaJson = "{}")
            fail("expected >64 name to throw")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("name must match") == true)
        }

        // Blank schemaJson rejected
        try {
            ResponseFormat.JsonSchema(name = "valid_name", schemaJson = "   ")
            fail("expected blank schemaJson to throw")
        } catch (e: IllegalArgumentException) {
            assertEquals("schemaJson must not be blank", e.message)
        }
    }

    @Test
    fun jsonSchemaNameBoundaryAndCharacterConstraints() {
        // Boundary lengths: 1 and 64 are valid
        val minLen = ResponseFormat.JsonSchema(name = "a", schemaJson = "{}")
        assertEquals("a", minLen.name)
        val minLenOther = ResponseFormat.JsonSchema(name = "_", schemaJson = "{}")
        assertEquals("_", minLenOther.name)

        val maxName = "a".repeat(64)
        val maxLen = ResponseFormat.JsonSchema(name = maxName, schemaJson = "{}")
        assertEquals(maxName, maxLen.name)

        // Boundary length: 65 is rejected
        try {
            ResponseFormat.JsonSchema(name = "a".repeat(65), schemaJson = "{}")
            fail("expected name of length 65 to throw IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("name must match") == true)
        }

        // Invalid characters: @, ., /, $, and Chinese characters
        val invalidNames = listOf(
            "name@test",
            "name.test",
            "name/test",
            "name\$test",
            "name_測試",
            "測試名稱",
        )
        for (badName in invalidNames) {
            try {
                ResponseFormat.JsonSchema(name = badName, schemaJson = "{}")
                fail("expected invalid name '$badName' to throw IllegalArgumentException")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message?.contains("name must match") == true)
            }
        }
    }

    @Test
    fun buildBodySerializesJsonSchemaWithDescription() {
        val provider = ChatCompletionsProvider(testConfig(), { null })
        val rf = ResponseFormat.JsonSchema(
            name = "extract_user",
            schemaJson = """{"type":"object","properties":{"name":{"type":"string"}},"required":["name"],"additionalProperties":false}""",
            description = "Extract user profile",
            strict = true,
        )
        val body = provider.buildBody(
            ChatRequest(
                model = "gpt-4o",
                messages = listOf(ChatMessage("user", "My name is Alice")),
                responseFormat = rf,
            ),
        )

        val root = Json.parseToJsonElement(body).jsonObject
        assertTrue(root.containsKey("response_format"))
        val rfObj = root["response_format"]!!.jsonObject
        assertEquals("json_schema", rfObj["type"]!!.jsonPrimitive.content)

        val jsObj = rfObj["json_schema"]!!.jsonObject
        assertEquals("extract_user", jsObj["name"]!!.jsonPrimitive.content)
        assertEquals("Extract user profile", jsObj["description"]!!.jsonPrimitive.content)
        assertEquals(true, jsObj["strict"]!!.jsonPrimitive.boolean)

        val schemaObj = jsObj["schema"]!!.jsonObject
        assertEquals("object", schemaObj["type"]!!.jsonPrimitive.content)
        assertTrue(schemaObj.containsKey("properties"))
    }

    @Test
    fun buildBodySerializesJsonSchemaWithoutDescriptionAndNonStrict() {
        val provider = ChatCompletionsProvider(testConfig(), { null })
        val rf = ResponseFormat.JsonSchema(
            name = "extract_item",
            schemaJson = """{"type":"object"}""",
            description = null,
            strict = false,
        )
        val body = provider.buildBody(
            ChatRequest(
                model = "gpt-4o",
                messages = listOf(ChatMessage("user", "Hello")),
                responseFormat = rf,
            ),
        )

        val root = Json.parseToJsonElement(body).jsonObject
        val rfObj = root["response_format"]!!.jsonObject
        val jsObj = rfObj["json_schema"]!!.jsonObject
        assertEquals("extract_item", jsObj["name"]!!.jsonPrimitive.content)
        assertNull(jsObj["description"])
        assertEquals(false, jsObj["strict"]!!.jsonPrimitive.boolean)
        assertEquals("object", jsObj["schema"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun buildBodySerializesJsonObject() {
        val provider = ChatCompletionsProvider(testConfig(), { null })
        val body = provider.buildBody(
            ChatRequest(
                model = "gpt-4o",
                messages = listOf(ChatMessage("user", "Hello")),
                responseFormat = ResponseFormat.JsonObject,
            ),
        )

        val root = Json.parseToJsonElement(body).jsonObject
        assertTrue(root.containsKey("response_format"))
        val rfObj = root["response_format"]!!.jsonObject
        assertEquals("json_object", rfObj["type"]!!.jsonPrimitive.content)
        assertFalse(rfObj.containsKey("json_schema"))
    }

    @Test
    fun buildBodyNullAndTextResponseFormatOmitted() {
        val provider = ChatCompletionsProvider(testConfig(), { null })

        val nullBody = provider.buildBody(
            ChatRequest(
                model = "gpt-4o",
                messages = listOf(ChatMessage("user", "Hello")),
                responseFormat = null,
            ),
        )
        val nullRoot = Json.parseToJsonElement(nullBody).jsonObject
        assertFalse(nullRoot.containsKey("response_format"))

        val textBody = provider.buildBody(
            ChatRequest(
                model = "gpt-4o",
                messages = listOf(ChatMessage("user", "Hello")),
                responseFormat = ResponseFormat.Text,
            ),
        )
        val textRoot = Json.parseToJsonElement(textBody).jsonObject
        assertFalse(textRoot.containsKey("response_format"))
    }

    @Test
    fun chatResponseFormatJsonDirect() {
        assertEquals("""{"type":"text"}""", chatResponseFormatJson(ResponseFormat.Text))
        assertEquals("""{"type":"json_object"}""", chatResponseFormatJson(ResponseFormat.JsonObject))

        val rf = ResponseFormat.JsonSchema(
            name = "my_schema",
            schemaJson = """{"type":"object"}""",
            description = "desc",
            strict = true,
        )
        val json = chatResponseFormatJson(rf)
        val obj = Json.parseToJsonElement(json).jsonObject
        assertEquals("json_schema", obj["type"]!!.jsonPrimitive.content)
        val js = obj["json_schema"]!!.jsonObject
        assertEquals("my_schema", js["name"]!!.jsonPrimitive.content)
        assertEquals("desc", js["description"]!!.jsonPrimitive.content)
        assertEquals(true, js["strict"]!!.jsonPrimitive.boolean)
        assertEquals("object", js["schema"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun responseFormatSchemaInvalidJsonFailsClosed() {
        val provider = ChatCompletionsProvider(testConfig(), { null })
        val invalidInputs = listOf("not json", "{ unclosed", "{},\"injected\":true}")
        for (invalidJson in invalidInputs) {
            val rf = ResponseFormat.JsonSchema(name = "test_schema", schemaJson = invalidJson)
            try {
                provider.buildBody(
                    ChatRequest(
                        model = "gpt-4o",
                        messages = listOf(ChatMessage("user", "Hello")),
                        responseFormat = rf,
                    ),
                )
                fail("expected buildBody to fail for invalid schema: $invalidJson")
            } catch (e: ProviderFailure) {
                assertFalse("failure must not be retryable", e.retryable)
                assertEquals("RESPONSE_FORMAT_SCHEMA_INVALID", e.message)
            }

            try {
                chatResponseFormatJson(rf)
                fail("expected chatResponseFormatJson to fail for invalid schema: $invalidJson")
            } catch (e: ProviderFailure) {
                assertFalse("failure must not be retryable", e.retryable)
                assertEquals("RESPONSE_FORMAT_SCHEMA_INVALID", e.message)
            }
        }
    }

    @Test
    fun responseFormatSchemaWrongRootTypeFailsClosed() {
        val provider = ChatCompletionsProvider(testConfig(), { null })
        val nonObjectRoots = listOf("[]", "\"abc\"", "123", "null", "true")
        for (nonObject in nonObjectRoots) {
            val rf = ResponseFormat.JsonSchema(name = "test_schema", schemaJson = nonObject)
            try {
                provider.buildBody(
                    ChatRequest(
                        model = "gpt-4o",
                        messages = listOf(ChatMessage("user", "Hello")),
                        responseFormat = rf,
                    ),
                )
                fail("expected buildBody to fail for non-object root: $nonObject")
            } catch (e: ProviderFailure) {
                assertFalse("failure must not be retryable", e.retryable)
                assertEquals("RESPONSE_FORMAT_SCHEMA_MUST_BE_OBJECT", e.message)
            }

            try {
                chatResponseFormatJson(rf)
                fail("expected chatResponseFormatJson to fail for non-object root: $nonObject")
            } catch (e: ProviderFailure) {
                assertFalse("failure must not be retryable", e.retryable)
                assertEquals("RESPONSE_FORMAT_SCHEMA_MUST_BE_OBJECT", e.message)
            }
        }
    }

    @Test
    fun responseFormatSchemaInjectionSafeEncoding() {
        val provider = ChatCompletionsProvider(testConfig(), { null })
        val injectionSchema = """{"type":"object","properties":{"malicious":{"description":"}\",\"injected\":true,"}}}"""
        val rf = ResponseFormat.JsonSchema(
            name = "safe_schema",
            schemaJson = injectionSchema,
        )
        val body = provider.buildBody(
            ChatRequest(
                model = "gpt-4o",
                messages = listOf(ChatMessage("user", "Hello")),
                responseFormat = rf,
            ),
        )

        val root = Json.parseToJsonElement(body).jsonObject
        assertFalse("root must not contain injected property", root.containsKey("injected"))

        val rfObj = root["response_format"]!!.jsonObject
        assertFalse("response_format must not contain injected property", rfObj.containsKey("injected"))

        val jsObj = rfObj["json_schema"]!!.jsonObject
        assertFalse("json_schema must not contain injected property", jsObj.containsKey("injected"))

        val schema = jsObj["schema"]!!.jsonObject
        val desc = schema["properties"]!!.jsonObject["malicious"]!!.jsonObject["description"]!!.jsonPrimitive.content
        assertEquals("}\",\"injected\":true,", desc)
    }

    @Test
    fun responseFormatSchemaElementDirect() {
        // Valid object
        val obj = responseFormatSchemaElement("""{"key":"value"}""")
        assertEquals("value", obj["key"]!!.jsonPrimitive.content)

        // Invalid JSON
        for (bad in listOf("not json", "{ unclosed", "{},\"injected\":true}", "", "   ")) {
            try {
                responseFormatSchemaElement(bad)
                fail("expected RESPONSE_FORMAT_SCHEMA_INVALID for: $bad")
            } catch (e: ProviderFailure) {
                assertFalse(e.retryable)
                assertEquals("RESPONSE_FORMAT_SCHEMA_INVALID", e.message)
            }
        }

        // Wrong root type
        for (nonObj in listOf("[]", "\"abc\"", "123", "null", "true", "false")) {
            try {
                responseFormatSchemaElement(nonObj)
                fail("expected RESPONSE_FORMAT_SCHEMA_MUST_BE_OBJECT for: $nonObj")
            } catch (e: ProviderFailure) {
                assertFalse(e.retryable)
                assertEquals("RESPONSE_FORMAT_SCHEMA_MUST_BE_OBJECT", e.message)
            }
        }
    }

    @Test
    fun streamRefusalYieldsTextDeltaAndDoneRefusal() {
        val mapper = ChatCompletionsMapper()
        val events = ArrayList<StreamEvent>()
        events += mapper.mapPayload(
            """{"choices":[{"delta":{"refusal":"I cannot fulfill this request"},"finish_reason":"refusal"}]}""",
        )
        mapper.markDone()
        events += mapper.finish()

        val textDeltas = events.filterIsInstance<StreamEvent.TextDelta>()
        assertEquals(1, textDeltas.size)
        assertEquals("I cannot fulfill this request", textDeltas[0].delta)
        assertEquals(0, textDeltas[0].round)
        assertEquals(0, textDeltas[0].blockIndex)

        val doneEvents = events.filterIsInstance<StreamEvent.Done>()
        assertEquals(1, doneEvents.size)
        assertEquals("refusal", doneEvents[0].finishReason)
    }

    @Test
    fun streamRefusalChunkedYieldsTextDeltasAndDoneRefusal() {
        val parser = SseFrameParser()
        val mapper = ChatCompletionsMapper()
        val sse = "data: {\"choices\":[{\"delta\":{\"refusal\":\"I cannot\"}}]}\n\n" +
            "data: {\"choices\":[{\"delta\":{\"refusal\":\" fulfill this request\"},\"finish_reason\":\"refusal\"}]}\n\n" +
            "data: [DONE]\n\n"
        val events = ArrayList<StreamEvent>()
        for (frame in parser.feed(sse.toByteArray())) {
            if (frame.isDone) {
                mapper.markDone()
            } else {
                events += mapper.mapPayload(frame.data)
            }
        }
        for (frame in parser.flush()) {
            if (frame.isDone) mapper.markDone() else events += mapper.mapPayload(frame.data)
        }
        events += mapper.finish()

        val textDeltas = events.filterIsInstance<StreamEvent.TextDelta>()
        assertEquals(listOf("I cannot", " fulfill this request"), textDeltas.map { it.delta })
        assertEquals(StreamEvent.Done("refusal"), events.last())
    }

    @Test
    fun nonStreamingMessageRefusalFallback() {
        val mapper = ChatCompletionsMapper()
        val events = mapper.mapPayload(
            """{"choices":[{"message":{"role":"assistant","refusal":"I cannot fulfill this request"},"finish_reason":"refusal"}]}""",
        )
        val textDeltas = events.filterIsInstance<StreamEvent.TextDelta>()
        assertEquals(1, textDeltas.size)
        assertEquals("I cannot fulfill this request", textDeltas[0].delta)
        mapper.markDone()
        val finishEvents = mapper.finish()
        val doneEvents = finishEvents.filterIsInstance<StreamEvent.Done>()
        assertEquals(1, doneEvents.size)
        assertEquals("refusal", doneEvents[0].finishReason)
    }

    @Test
    fun streamRefusalEmptyDeltaSuppressed() {
        val mapper = ChatCompletionsMapper()
        val emptyEvents = mapper.mapPayload(
            """{"choices":[{"delta":{"refusal":""}}]}""",
        )
        assertTrue("empty refusal delta must not produce TextDelta: $emptyEvents", emptyEvents.filterIsInstance<StreamEvent.TextDelta>().isEmpty())
        assertTrue("empty refusal delta must produce no events: $emptyEvents", emptyEvents.isEmpty())

        // Following non-empty refusal delta produces TextDelta
        val nextEvents = mapper.mapPayload(
            """{"choices":[{"delta":{"refusal":"I cannot fulfill this request"}}]}""",
        )
        val textDeltas = nextEvents.filterIsInstance<StreamEvent.TextDelta>()
        assertEquals(1, textDeltas.size)
        assertEquals("I cannot fulfill this request", textDeltas.single().delta)
    }

    @Test
    fun streamRefusalFinishReasonWithEmptyDeltaYieldsOnlyDoneRefusal() {
        // Case 1: Empty delta object with finish_reason: refusal
        val mapper1 = ChatCompletionsMapper()
        val events1 = ArrayList<StreamEvent>()
        events1 += mapper1.mapPayload(
            """{"choices":[{"delta":{},"finish_reason":"refusal"}]}""",
        )
        mapper1.markDone()
        events1 += mapper1.finish()
        assertTrue("events must not contain TextDelta: $events1", events1.none { it is StreamEvent.TextDelta })
        val doneEvents1 = events1.filterIsInstance<StreamEvent.Done>()
        assertEquals(1, doneEvents1.size)
        assertEquals("refusal", doneEvents1.single().finishReason)
        assertEquals(listOf(StreamEvent.Done("refusal")), events1)

        // Case 2: Explicit empty string refusal with finish_reason: refusal
        val mapper2 = ChatCompletionsMapper()
        val events2 = ArrayList<StreamEvent>()
        events2 += mapper2.mapPayload(
            """{"choices":[{"delta":{"refusal":""},"finish_reason":"refusal"}]}""",
        )
        mapper2.markDone()
        events2 += mapper2.finish()
        assertTrue("events must not contain TextDelta: $events2", events2.none { it is StreamEvent.TextDelta })
        val doneEvents2 = events2.filterIsInstance<StreamEvent.Done>()
        assertEquals(1, doneEvents2.size)
        assertEquals("refusal", doneEvents2.single().finishReason)
        assertEquals(listOf(StreamEvent.Done("refusal")), events2)
    }

    @Test
    fun streamRefusalWithRoundGreaterThanZero() {
        val mapper = ChatCompletionsMapper(round = 2)
        assertEquals(2, mapper.round)

        val events = ArrayList<StreamEvent>()
        events += mapper.mapPayload(
            """{"choices":[{"delta":{"refusal":"Refusal at round 2"},"finish_reason":"refusal"}]}""",
        )
        mapper.markDone()
        events += mapper.finish()

        val textDeltas = events.filterIsInstance<StreamEvent.TextDelta>()
        assertEquals(1, textDeltas.size)
        val textDelta = textDeltas.single()
        assertEquals(2, textDelta.round)
        assertEquals("Refusal at round 2", textDelta.delta)
        assertEquals(0, textDelta.blockIndex)

        val doneEvents = events.filterIsInstance<StreamEvent.Done>()
        assertEquals(1, doneEvents.size)
        assertEquals("refusal", doneEvents.single().finishReason)
    }

    @Test
    fun streamReasoningFollowedByRefusalSwitchesBlockIndex() {
        val mapper = ChatCompletionsMapper()
        val r1 = mapper.mapPayload(
            """{"choices":[{"delta":{"reasoning_content":"Thinking about safety guidelines..."}}]}""",
        )
        val reasoningDeltas = r1.filterIsInstance<StreamEvent.ReasoningDelta>()
        assertEquals(1, reasoningDeltas.size)
        assertEquals(0, reasoningDeltas.single().blockIndex)
        assertEquals("Thinking about safety guidelines...", reasoningDeltas.single().delta)

        // Immediately followed by refusal delta -> blockIndex switches from reasoning to text
        val r2 = mapper.mapPayload(
            """{"choices":[{"delta":{"refusal":"I cannot fulfill this request."}}]}""",
        )
        val textDeltas = r2.filterIsInstance<StreamEvent.TextDelta>()
        assertEquals(1, textDeltas.size)
        val refusalDelta = textDeltas.single()
        assertEquals("blockIndex must increment when switching from reasoning to refusal", 1, refusalDelta.blockIndex)
        assertEquals("I cannot fulfill this request.", refusalDelta.delta)

        // Continuation refusal chunks keep the switched blockIndex
        val r3 = mapper.mapPayload(
            """{"choices":[{"delta":{"refusal":" Policy forbids this action."}}]}""",
        )
        val textDeltasCont = r3.filterIsInstance<StreamEvent.TextDelta>()
        assertEquals(1, textDeltasCont.size)
        assertEquals(1, textDeltasCont.single().blockIndex)
        assertEquals(" Policy forbids this action.", textDeltasCont.single().delta)
    }
}
