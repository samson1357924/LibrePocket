package dev.librepocket.provider

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ChatCompletionsProviderTest {

    private fun testConfig(baseUrl: String = "https://api.openai.com/v1") = ProviderConfig(
        id = "00000000-0000-0000-0000-000000000001",
        label = "test",
        baseUrl = baseUrl,
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
            assertEquals("name must match ^[a-zA-Z0-9_-]{1,64}$, was: invalid name with spaces", e.message)
        }

        // Overlength name (> 64 chars) rejected
        try {
            ResponseFormat.JsonSchema(name = "a".repeat(65), schemaJson = "{}")
            fail("expected >64 name to throw")
        } catch (e: IllegalArgumentException) {
            assertEquals("name must match ^[a-zA-Z0-9_-]{1,64}$, was: ${"a".repeat(65)}", e.message)
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
            assertEquals("name must match ^[a-zA-Z0-9_-]{1,64}$, was: ${"a".repeat(65)}", e.message)
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
                assertEquals("name must match ^[a-zA-Z0-9_-]{1,64}$, was: $badName", e.message)
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
            """{"choices":[{"delta":{"refusal":"I cannot fulfill this request"},"finish_reason":null}]}""",
        )
        events += mapper.mapPayload(
            """{"choices":[{"delta":{},"finish_reason":"stop"}]}""",
        )
        mapper.markDone()
        events += mapper.finish()

        assertEquals(
            listOf(
                StreamEvent.TextDelta(0, 0, "I cannot fulfill this request"),
                StreamEvent.Done("refusal"),
            ),
            events,
        )
    }

    @Test
    fun streamRefusalChunkedYieldsTextDeltasAndDoneRefusal() {
        val parser = SseFrameParser()
        val mapper = ChatCompletionsMapper()
        val sse = "data: {\"choices\":[{\"delta\":{\"refusal\":\"I cannot\"},\"finish_reason\":null}]}\n\n" +
            "data: {\"choices\":[{\"delta\":{\"refusal\":\" fulfill this request\"},\"finish_reason\":null}]}\n\n" +
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" +
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

        assertEquals(
            listOf(
                StreamEvent.TextDelta(0, 0, "I cannot"),
                StreamEvent.TextDelta(0, 0, " fulfill this request"),
                StreamEvent.Done("refusal"),
            ),
            events,
        )
    }

    @Test
    fun nonStreamingMessageRefusalFallback() {
        val mapper = ChatCompletionsMapper()
        val events = mapper.mapPayload(
            """{"choices":[{"message":{"role":"assistant","refusal":"I cannot fulfill this request"},"finish_reason":"stop"}]}""",
        )
        assertEquals(
            listOf(StreamEvent.TextDelta(0, 0, "I cannot fulfill this request")),
            events,
        )
        mapper.markDone()
        val finishEvents = mapper.finish()
        assertEquals(listOf(StreamEvent.Done("refusal")), finishEvents)
    }

    @Test
    fun streamRefusalEmptyDeltaSuppressed() {
        val mapper = ChatCompletionsMapper()
        val emptyEvents = mapper.mapPayload(
            """{"choices":[{"delta":{"refusal":""}}]}""",
        )
        assertEquals(emptyList<StreamEvent>(), emptyEvents)

        // Following non-empty refusal delta produces TextDelta
        val nextEvents = mapper.mapPayload(
            """{"choices":[{"delta":{"refusal":"I cannot fulfill this request"}}]}""",
        )
        assertEquals(
            listOf(StreamEvent.TextDelta(0, 0, "I cannot fulfill this request")),
            nextEvents,
        )
    }

    @Test
    fun streamRefusalEmptyDeltaYieldsOnlyDoneRefusal() {
        // Explicit empty string refusal in delta followed by terminal stop yields Done("stop") without TextDelta
        val mapper = ChatCompletionsMapper()
        val events = ArrayList<StreamEvent>()
        events += mapper.mapPayload(
            """{"choices":[{"delta":{"refusal":""},"finish_reason":null}]}""",
        )
        events += mapper.mapPayload(
            """{"choices":[{"delta":{},"finish_reason":"stop"}]}""",
        )
        mapper.markDone()
        events += mapper.finish()
        assertEquals(listOf(StreamEvent.Done("stop")), events)
    }

    @Test
    fun deltaWithContentAndEmptyRefusalFieldEmitsContentAndCompletesWithStop() {
        val mapper = ChatCompletionsMapper()
        val events = ArrayList<StreamEvent>()
        events += mapper.mapPayload(
            """{"choices":[{"delta":{"content":"Hello world","refusal":""},"finish_reason":null}]}""",
        )
        events += mapper.mapPayload(
            """{"choices":[{"delta":{},"finish_reason":"stop"}]}""",
        )
        mapper.markDone()
        events += mapper.finish()
        assertEquals(
            listOf(
                StreamEvent.TextDelta(0, 0, "Hello world"),
                StreamEvent.Done("stop"),
            ),
            events,
        )
    }

    @Test
    fun deltaWithContentAndStrayRefusalPrioritizesContentAndPreservesStop() {
        val mapper = ChatCompletionsMapper()
        val events = ArrayList<StreamEvent>()
        events += mapper.mapPayload(
            """{"choices":[{"delta":{"content":"Valid answer","refusal":"stray refusal"},"finish_reason":null}]}""",
        )
        events += mapper.mapPayload(
            """{"choices":[{"delta":{},"finish_reason":"stop"}]}""",
        )
        mapper.markDone()
        events += mapper.finish()
        assertEquals(
            listOf(
                StreamEvent.TextDelta(0, 0, "Valid answer"),
                StreamEvent.Done("stop"),
            ),
            events,
        )
    }

    @Test
    fun standaloneEmptyRefusalDeltaFollowedByStopCompletesWithStop() {
        val mapper = ChatCompletionsMapper()
        val events = ArrayList<StreamEvent>()
        events += mapper.mapPayload(
            """{"choices":[{"delta":{"refusal":""},"finish_reason":null}]}""",
        )
        events += mapper.mapPayload(
            """{"choices":[{"delta":{},"finish_reason":"stop"}]}""",
        )
        mapper.markDone()
        events += mapper.finish()
        assertEquals(
            listOf(StreamEvent.Done("stop")),
            events,
        )
    }

    @Test
    fun streamRefusalWithRoundGreaterThanZero() {
        val mapper = ChatCompletionsMapper(round = 2)
        assertEquals(2, mapper.round)

        val events = ArrayList<StreamEvent>()
        events += mapper.mapPayload(
            """{"choices":[{"delta":{"refusal":"Refusal at round 2"},"finish_reason":null}]}""",
        )
        events += mapper.mapPayload(
            """{"choices":[{"delta":{},"finish_reason":"stop"}]}""",
        )
        mapper.markDone()
        events += mapper.finish()

        assertEquals(
            listOf(
                StreamEvent.TextDelta(2, 0, "Refusal at round 2"),
                StreamEvent.Done("refusal"),
            ),
            events,
        )
    }

    @Test
    fun streamReasoningFollowedByRefusalSwitchesBlockIndex() {
        val mapper = ChatCompletionsMapper()
        val r1 = mapper.mapPayload(
            """{"choices":[{"delta":{"reasoning_content":"Thinking about safety guidelines..."}}]}""",
        )
        assertEquals(
            listOf(StreamEvent.ReasoningDelta(0, 0, "Thinking about safety guidelines...")),
            r1,
        )

        // Immediately followed by refusal delta -> blockIndex switches from reasoning to text
        val r2 = mapper.mapPayload(
            """{"choices":[{"delta":{"refusal":"I cannot fulfill this request."}}]}""",
        )
        assertEquals(
            listOf(StreamEvent.TextDelta(0, 1, "I cannot fulfill this request.")),
            r2,
        )

        // Continuation refusal chunks keep the switched blockIndex
        val r3 = mapper.mapPayload(
            """{"choices":[{"delta":{"refusal":" Policy forbids this action."}}]}""",
        )
        assertEquals(
            listOf(StreamEvent.TextDelta(0, 1, " Policy forbids this action.")),
            r3,
        )
    }

    @Test
    fun streamFlowCollectsRefusalDeltasAndDoneSuccessfully() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val body =
                "data: {\"choices\":[{\"delta\":{\"refusal\":\"I cannot \"},\"finish_reason\":null}]}\n\n" +
                    "data: {\"choices\":[{\"delta\":{\"refusal\":\"comply with this request.\"},\"finish_reason\":null}]}\n\n" +
                    "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" +
                    "data: [DONE]\n\n"
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody(body),
            )
            val provider = ChatCompletionsProvider(
                testConfig(server.url("/").toString().trimEnd('/')),
                { "test_api_key".toCharArray() },
            )
            val events = withTimeout(10_000) {
                provider.stream(
                    ChatRequest(
                        model = "gpt-4o",
                        messages = listOf(ChatMessage("user", "Do something bad")),
                        responseFormat = ResponseFormat.JsonObject,
                    ),
                ).toList()
            }
            assertEquals(
                listOf(
                    StreamEvent.TextDelta(0, 0, "I cannot "),
                    StreamEvent.TextDelta(0, 0, "comply with this request."),
                    StreamEvent.Done("refusal"),
                ),
                events,
            )
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun streamFlowCollectsJsonSchemaDeltasAndDoneSuccessfully() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val body =
                "data: {\"choices\":[{\"delta\":{\"content\":\"{\\\"status\\\":\\\"ok\\\"}\"},\"finish_reason\":\"stop\"}]}\n\n" +
                    "data: [DONE]\n\n"
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody(body),
            )
            val provider = ChatCompletionsProvider(
                testConfig(server.url("/").toString().trimEnd('/')),
                { "test_api_key".toCharArray() },
            )
            val rf = ResponseFormat.JsonSchema(
                name = "test_status",
                schemaJson = """{"type":"object"}""",
            )
            val events = withTimeout(10_000) {
                provider.stream(
                    ChatRequest(
                        model = "gpt-4o",
                        messages = listOf(ChatMessage("user", "Give status")),
                        responseFormat = rf,
                    ),
                ).toList()
            }
            val recorded = server.takeRequest()
            val requestBody = recorded.body.readUtf8()
            val root = Json.parseToJsonElement(requestBody).jsonObject
            assertEquals("json_schema", root["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content)

            assertEquals(
                listOf(
                    StreamEvent.TextDelta(0, 0, "{\"status\":\"ok\"}"),
                    StreamEvent.Done("stop"),
                ),
                events,
            )
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun refusalDeltaWithFinishReasonStopYieldsDoneRefusal() {
        val mapper = ChatCompletionsMapper()
        val events = ArrayList<StreamEvent>()
        // Real OpenAI wire sends refusal in delta, but finish_reason: "stop"
        events += mapper.mapPayload(
            """{"choices":[{"delta":{"refusal":"I cannot fulfill this request"}}]}""",
        )
        events += mapper.mapPayload(
            """{"choices":[{"delta":{},"finish_reason":"stop"}]}""",
        )
        mapper.markDone()
        events += mapper.finish()

        assertEquals(
            listOf(
                StreamEvent.TextDelta(0, 0, "I cannot fulfill this request"),
                StreamEvent.Done("refusal"),
            ),
            events,
        )
    }

    @Test
    fun proxyNativeFinishReasonRefusalSupported() {
        val mapper = ChatCompletionsMapper()
        val events = ArrayList<StreamEvent>()
        // Proxy natively sends finish_reason: "refusal" without refusal in delta
        events += mapper.mapPayload(
            """{"choices":[{"delta":{"content":"I cannot help."}}]}""",
        )
        events += mapper.mapPayload(
            """{"choices":[{"delta":{},"finish_reason":"refusal"}]}""",
        )
        mapper.markDone()
        events += mapper.finish()

        assertEquals(
            listOf(
                StreamEvent.TextDelta(0, 0, "I cannot help."),
                StreamEvent.Done("refusal"),
            ),
            events,
        )
    }

    @Test
    fun streamFlowCollectsRefusalWithFinishReasonStopYieldsDoneRefusal() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val body =
                "data: {\"choices\":[{\"delta\":{\"refusal\":\"I cannot comply.\"}}]}\n\n" +
                    "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" +
                    "data: [DONE]\n\n"
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody(body),
            )
            val provider = ChatCompletionsProvider(
                testConfig(server.url("/").toString().trimEnd('/')),
                { "test_api_key".toCharArray() },
            )
            val events = withTimeout(10_000) {
                provider.stream(
                    ChatRequest(
                        model = "gpt-4o",
                        messages = listOf(ChatMessage("user", "Do something bad")),
                        responseFormat = ResponseFormat.JsonObject,
                    ),
                ).toList()
            }
            assertEquals(
                listOf(
                    StreamEvent.TextDelta(0, 0, "I cannot comply."),
                    StreamEvent.Done("refusal"),
                ),
                events,
            )
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun refusalFollowedByFinishReasonLengthPreservesLength() {
        val mapper = ChatCompletionsMapper()
        val events = ArrayList<StreamEvent>()
        events += mapper.mapPayload(
            """{"choices":[{"delta":{"refusal":"I cannot fulfill this request due to length limit"},"finish_reason":null}]}""",
        )
        events += mapper.mapPayload(
            """{"choices":[{"delta":{},"finish_reason":"length"}]}""",
        )
        mapper.markDone()
        events += mapper.finish()

        assertEquals(
            listOf(
                StreamEvent.TextDelta(0, 0, "I cannot fulfill this request due to length limit"),
                StreamEvent.Done("length"),
            ),
            events,
        )
    }

    @Test
    fun refusalFollowedByFinishReasonContentFilterPreservesContentFilter() {
        val mapper = ChatCompletionsMapper()
        val events = ArrayList<StreamEvent>()
        events += mapper.mapPayload(
            """{"choices":[{"delta":{"refusal":"I cannot fulfill this request due to policy"},"finish_reason":null}]}""",
        )
        events += mapper.mapPayload(
            """{"choices":[{"delta":{},"finish_reason":"content_filter"}]}""",
        )
        mapper.markDone()
        events += mapper.finish()

        assertEquals(
            listOf(
                StreamEvent.TextDelta(0, 0, "I cannot fulfill this request due to policy"),
                StreamEvent.Done("content_filter"),
            ),
            events,
        )
    }

    @Test
    fun streamFlowDeltaWithContentAndEmptyRefusalCompletesWithDoneStop() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val body =
                "data: {\"choices\":[{\"delta\":{\"content\":\"Valid response\",\"refusal\":\"\"},\"finish_reason\":null}]}\n\n" +
                    "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" +
                    "data: [DONE]\n\n"
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody(body),
            )
            val provider = ChatCompletionsProvider(
                testConfig(server.url("/").toString().trimEnd('/')),
                { "test_api_key".toCharArray() },
            )
            val events = withTimeout(10_000) {
                provider.stream(
                    ChatRequest(
                        model = "gpt-4o",
                        messages = listOf(ChatMessage("user", "Hello")),
                    ),
                ).toList()
            }
            assertEquals(
                listOf(
                    StreamEvent.TextDelta(0, 0, "Valid response"),
                    StreamEvent.Done("stop"),
                ),
                events,
            )
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun streamFlowRefusalFollowedByLengthPrecedencePreservedOverWireSSE() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val body =
                "data: {\"choices\":[{\"delta\":{\"refusal\":\"I cannot finish because\"},\"finish_reason\":null}]}\n\n" +
                    "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"length\"}]}\n\n" +
                    "data: [DONE]\n\n"
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody(body),
            )
            val provider = ChatCompletionsProvider(
                testConfig(server.url("/").toString().trimEnd('/')),
                { "test_api_key".toCharArray() },
            )
            val events = withTimeout(10_000) {
                provider.stream(
                    ChatRequest(
                        model = "gpt-4o",
                        messages = listOf(ChatMessage("user", "Do something bad")),
                    ),
                ).toList()
            }
            assertEquals(
                listOf(
                    StreamEvent.TextDelta(0, 0, "I cannot finish because"),
                    StreamEvent.Done("length"),
                ),
                events,
            )
        } finally {
            server.shutdown()
        }
    }
}
