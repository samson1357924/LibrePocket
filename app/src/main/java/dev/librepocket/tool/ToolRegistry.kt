package dev.librepocket.tool

import dev.librepocket.clipboard.ClipboardTools
import dev.librepocket.files.FileAttachTools
import dev.librepocket.files.FileEditTools
import dev.librepocket.files.FileSearchTools

/**
 * Built-in tool registry: the P2 fast-channel tools (BACKLOG B3,
 * CAPABILITY_MATRIX §1) plus S1-C backfill (shell/calendar/contacts,
 * in [FAST_TOOLS]) plus S1-B network/data tools (owned block
 * [S1B_TOOLS] below, do not edit) plus the slow-channel descriptor
 * used only for projection (hidden on play, foss/github-only,
 * default off).
 *
 * Compliance red lines (MATRIX §2), enforced here and asserted by tests:
 * - phone uses `ACTION_DIAL` prefill only, never `ACTION_CALL`;
 * - SMS goes through the system editor prefill only; this registry requests
 *   no SMS permission ([ToolAnnotations.requiresPermission] is null for
 *   `sms.compose` and no schema mentions SEND_SMS / READ_SMS).
 * - S1-C adds no blacklist permission literals (no RECORD_AUDIO, no a11y):
 *   only READ_CALENDAR / WRITE_CALENDAR / READ_CONTACTS + pseudo-grant
 *   `listener:notification` appear in the S1-C block.
 * - S2 voice adds no permission either: system STT walks `RecognizerIntent`
 *   delegation and system TTS needs none (no RECORD_AUDIO literal anywhere;
 *   Azure cloud voice is github-flavor code only).
 */
object ToolRegistry {

    const val SLOW_TOOL_NAME = "gui.automate"

    private const val ACTION_DIAL = "android.intent.action.DIAL"
    private const val ACTION_VIEW = "android.intent.action.VIEW"
    private const val ACTION_SEND = "android.intent.action.SEND"
    private const val ACTION_SET_ALARM = "android.intent.action.SET_ALARM"
    private const val ACTION_EDIT = "android.intent.action.EDIT"

    private fun schema(vararg props: String, required: String = ""): String {
        val req = if (required.isEmpty()) "" else """, "required": [$required]"""
        return """{"type": "object", "properties": {${props.joinToString(", ")}}$req}"""
    }

    private fun prop(name: String, type: String, desc: String): String =
        """"$name": {"type": "$type", "description": "$desc"}"""

    val FAST_TOOLS: List<ToolDef> = listOf(
        ToolDef(
            name = "navigate",
            description = "Navigate somewhere via map geo intent, else web-map fallback.",
            jsonSchema = schema(
                prop("destination", "string", "Place name or address"),
                prop("mode", "string", "driving|walking|transit"),
                required = "\"destination\"",
            ),
            sideEffect = SideEffect.READ,
            annotations = ToolAnnotations(
                intentAction = "android.intent.action.VIEW(geo)",
                fallbackHint = "open the web map manually and enter the destination",
            ),
        ),
        ToolDef(
            name = "open_app",
            description = "Open an app via explicit/implicit intent; ambiguous names need clarification.",
            jsonSchema = schema(
                prop("appName", "string", "User-visible app name"),
                prop("packageName", "string", "Resolved package, if known"),
                required = "\"appName\"",
            ),
            sideEffect = SideEffect.READ,
            annotations = ToolAnnotations(
                fallbackHint = "open the app manually from the launcher",
            ),
        ),
        ToolDef(
            name = "mail.compose",
            description = "Draft mail prefilled via the system mail app; never sent in background by this app.",
            jsonSchema = schema(
                prop("to", "string", "Recipient address"),
                prop("subject", "string", "Subject"),
                prop("body", "string", "Draft body"),
            ),
            sideEffect = SideEffect.WRITE,
            annotations = ToolAnnotations(
                intentAction = ACTION_SEND,
                fallbackHint = "open the mail app manually and paste the draft",
            ),
        ),
        ToolDef(
            name = "alarm.create",
            description = "Create an alarm via the system clock app (SET_ALARM).",
            jsonSchema = schema(
                prop("hour", "integer", "0-23"),
                prop("minute", "integer", "0-59"),
                prop("label", "string", "Alarm label"),
                required = "\"hour\", \"minute\"",
            ),
            sideEffect = SideEffect.WRITE,
            annotations = ToolAnnotations(
                intentAction = ACTION_SET_ALARM,
                fallbackHint = "open the clock app manually and add the alarm",
            ),
        ),
        ToolDef(
            name = "phone.dial",
            description = "Prefill the dialer via ACTION_DIAL only; the user presses call. Never direct-dials.",
            jsonSchema = schema(
                prop("number", "string", "Phone number to prefill"),
                prop("raw", "string", "Original user text"),
                required = "\"number\"",
            ),
            sideEffect = SideEffect.PRIVILEGED,
            annotations = ToolAnnotations(
                intentAction = ACTION_DIAL,
                fallbackHint = "open the dialer manually and enter the number",
            ),
        ),
        ToolDef(
            name = "sms.compose",
            description = "Prefill the system SMS editor only; never auto-sends and requests no SMS permission.",
            jsonSchema = schema(
                prop("to", "string", "Recipient number, may be empty"),
                prop("body", "string", "Prefilled message body"),
                prop("raw", "string", "Original user text"),
                required = "\"body\"",
            ),
            sideEffect = SideEffect.PRIVILEGED,
            annotations = ToolAnnotations(
                intentAction = ACTION_VIEW,
                timeoutMs = 10_000L,
                fallbackHint = "open the SMS app manually and type the message",
            ),
        ),
        ToolDef(
            name = "calendar.create",
            description = "Query/create calendar events via Calendar Provider and system calendar intents.",
            jsonSchema = schema(
                prop("title", "string", "Event title"),
                prop("start", "string", "ISO-8601 start"),
                prop("end", "string", "ISO-8601 end"),
                required = "\"title\", \"start\"",
            ),
            sideEffect = SideEffect.WRITE,
            annotations = ToolAnnotations(
                intentAction = ACTION_EDIT,
                requiresSwitch = "calendar",
                requiresPermission = "android.permission.READ_CALENDAR",
                fallbackHint = "open the calendar app manually and add the event",
            ),
        ),
        ToolDef(
            name = "music.control",
            description = "Media control (play/pause/next) via media intents and foreground MediaSession.",
            jsonSchema = schema(
                prop("action", "string", "play|pause|next|previous"),
                required = "\"action\"",
            ),
            sideEffect = SideEffect.WRITE,
            annotations = ToolAnnotations(
                fallbackHint = "open the music app manually and press play",
            ),
        ),
        ToolDef(
            name = "volume.set",
            description = "Adjust volume or mute via the public AudioManager API.",
            jsonSchema = schema(
                prop("level", "integer", "0-100, ignored when mute is set"),
                prop("mute", "boolean", "Mute on/off"),
            ),
            sideEffect = SideEffect.WRITE,
            annotations = ToolAnnotations(
                fallbackHint = "use the hardware volume keys",
            ),
        ),
        ToolDef(
            name = "notification.read",
            description = "Read notification titles (own app, or via listener with grant); full text needs confirmation.",
            jsonSchema = schema(
                prop("limit", "integer", "Max items to return"),
                prop("fullText", "boolean", "Read full body text; always needs explicit confirmation plus listener grant and second consent"),
            ),
            sideEffect = SideEffect.READ,
            annotations = ToolAnnotations(
                requiresSwitch = "notification",
                requiresPermission = "listener:notification",
                degradedWithoutPermission = true,
                fallbackHint = "pull down the status bar and read notifications manually",
            ),
        ),
        ToolDef(
            name = "screenshot.capture",
            description = "Capture via the system screenshot / MediaProjection path with per-shot authorization.",
            jsonSchema = schema(
                prop("share", "boolean", "Hand to the system share sheet afterwards"),
            ),
            sideEffect = SideEffect.PRIVILEGED,
            annotations = ToolAnnotations(
                intentAction = "android.media.projection.MediaProjection",
                requiresSwitch = "screenshot",
                fallbackHint = "take a screenshot manually with the hardware keys",
            ),
        ),
        // ---- S1-C backfill: restricted shell (D05 restricted part) ----
        ToolDef(
            name = "shell.exec",
            description = "Restricted shell exec (no privilege): argv direct to RestrictedShell, no sh -c; allowlist/denylist/quota/truncation apply. Elevated execution is never performed in this phase.",
            jsonSchema = schema(
                prop("argv", "array", "Argument vector; argv[0] is the binary basename, never a shell string"),
                prop("timeoutMs", "integer", "Per-call timeout budget in milliseconds"),
                prop("reason", "string", "Why this command is needed (audit)"),
                required = "\"argv\"",
            ),
            sideEffect = SideEffect.PRIVILEGED,
            annotations = ToolAnnotations(
                requiresSwitch = "shell",
                switchDefault = false,
                timeoutMs = 10_000L,
                fallbackHint = "run the command manually in a terminal app",
            ),
        ),
        // ---- S1-C backfill: calendar query/update/delete ----
        ToolDef(
            name = "calendar.query",
            description = "Query calendar events via Calendar Provider; requires READ_CALENDAR.",
            jsonSchema = schema(
                prop("start", "string", "ISO-8601 range start"),
                prop("end", "string", "ISO-8601 range end"),
                prop("query", "string", "Optional title/location keyword filter"),
                prop("limit", "integer", "Max events to return"),
                required = "\"start\", \"end\"",
            ),
            sideEffect = SideEffect.READ,
            annotations = ToolAnnotations(
                requiresSwitch = "calendar",
                requiresPermission = "android.permission.READ_CALENDAR",
                fallbackHint = "open the calendar app manually and check the schedule",
            ),
        ),
        ToolDef(
            name = "calendar.update",
            description = "Update a calendar event via system calendar EDIT delegation preferred; direct provider write needs WRITE_CALENDAR.",
            jsonSchema = schema(
                prop("eventId", "string", "Event row id"),
                prop("title", "string", "New title, if changing"),
                prop("start", "string", "ISO-8601 start, if changing"),
                prop("end", "string", "ISO-8601 end, if changing"),
                required = "\"eventId\"",
            ),
            sideEffect = SideEffect.WRITE,
            annotations = ToolAnnotations(
                requiresSwitch = "calendar",
                requiresPermission = "android.permission.WRITE_CALENDAR",
                degradedWithoutPermission = true,
                fallbackHint = "open the calendar app manually and edit the event",
            ),
        ),
        ToolDef(
            name = "calendar.delete",
            description = "Delete a calendar event via system calendar delegation preferred; direct provider delete needs WRITE_CALENDAR.",
            jsonSchema = schema(
                prop("eventId", "string", "Event row id"),
                required = "\"eventId\"",
            ),
            sideEffect = SideEffect.WRITE,
            annotations = ToolAnnotations(
                requiresSwitch = "calendar",
                requiresPermission = "android.permission.WRITE_CALENDAR",
                degradedWithoutPermission = true,
                fallbackHint = "open the calendar app manually and delete the event",
            ),
        ),
        // ---- S1-C backfill: full contacts (foss/github only, default off) ----
        ToolDef(
            name = "contact.search",
            description = "Search contacts by name/phone keyword (foss/github full access only, default off; pick delegation stays permission-free).",
            jsonSchema = schema(
                prop("query", "string", "Name or phone keyword"),
                prop("limit", "integer", "Max results"),
                required = "\"query\"",
            ),
            sideEffect = SideEffect.READ,
            annotations = ToolAnnotations(
                requiresSwitch = "contacts_full",
                switchDefault = false,
                requiresPermission = "android.permission.READ_CONTACTS",
                fallbackHint = "open the contacts app manually or use the system picker",
            ),
            supportedFlavors = setOf(Flavor.FOSS, Flavor.GITHUB),
        ),
        ToolDef(
            name = "contact.list",
            description = "List contacts (foss/github full access only, default off; pick delegation stays permission-free).",
            jsonSchema = schema(
                prop("limit", "integer", "Max results"),
            ),
            sideEffect = SideEffect.READ,
            annotations = ToolAnnotations(
                requiresSwitch = "contacts_full",
                switchDefault = false,
                requiresPermission = "android.permission.READ_CONTACTS",
                fallbackHint = "open the contacts app manually or use the system picker",
            ),
            supportedFlavors = setOf(Flavor.FOSS, Flavor.GITHUB),
        ),
        ToolDef(
            name = "contact.get",
            description = "Get one contact by id (foss/github full access only, default off; pick delegation stays permission-free).",
            jsonSchema = schema(
                prop("contactId", "string", "Contact row id"),
                required = "\"contactId\"",
            ),
            sideEffect = SideEffect.READ,
            annotations = ToolAnnotations(
                requiresSwitch = "contacts_full",
                switchDefault = false,
                requiresPermission = "android.permission.READ_CONTACTS",
                fallbackHint = "open the contacts app manually or use the system picker",
            ),
            supportedFlavors = setOf(Flavor.FOSS, Flavor.GITHUB),
        ),
    )

    // S1-B-ANCHOR-BEGIN: network + data tools owned by S1-B.
    // S1-A / S1-C: append your own anchored blocks AFTER S1-B-ANCHOR-END;
    // do not edit inside this block (merge-conflict avoidance).
    val S1B_TOOLS: List<ToolDef> = listOf(
        ToolDef(
            name = WebFetch.TOOL_NAME,
            description = "Fetch an https URL as text (http only for loopback); byte-capped, 15s timeout, transcode-downgraded.",
            jsonSchema = schema(
                prop("url", "string", "https URL to fetch (http only for localhost/loopback)"),
                prop("maxBytes", "integer", "Byte cap, clamped to 1 MiB"),
                required = "\"url\"",
            ),
            sideEffect = SideEffect.READ,
            annotations = ToolAnnotations(
                timeoutMs = WebFetch.TIMEOUT_MS,
                requiresSwitch = WebFetch.SWITCH,
                switchDefault = WebFetch.SWITCH_DEFAULT,
                fallbackHint = WebFetch.FALLBACK_HINT,
            ),
        ),
        ToolDef(
            name = WebSearchLocal.TOOL_NAME,
            description = "Local web search {query, count} via a configured supplier; distinct from the server-side hosted web_search passthrough.",
            jsonSchema = schema(
                prop("query", "string", "Search keywords"),
                prop("count", "integer", "Max results, 1-10"),
                required = "\"query\"",
            ),
            sideEffect = SideEffect.READ,
            annotations = ToolAnnotations(
                requiresSwitch = WebSearchLocal.SWITCH,
                switchDefault = WebSearchLocal.SWITCH_DEFAULT,
                fallbackHint = WebSearchLocal.FALLBACK_HINT,
            ),
        ),
        ToolDef(
            name = DbTools.QUERY_NAME,
            description = "Read-only SELECT over the app database with a row cap; rejects non-SELECT and multi-statements.",
            jsonSchema = schema(
                prop("sql", "string", "Single SELECT/WITH statement"),
                prop("limit", "integer", "Row cap, clamped to 200"),
                required = "\"sql\"",
            ),
            sideEffect = SideEffect.READ,
            annotations = ToolAnnotations(
                requiresSwitch = DbTools.SWITCH,
                switchDefault = DbTools.SWITCH_DEFAULT,
                fallbackHint = DbTools.FALLBACK_HINT,
            ),
        ),
        ToolDef(
            name = DbTools.EXEC_NAME,
            description = "Write to the app database; needs confirmation and bans ATTACH/DROP.",
            jsonSchema = schema(
                prop("sql", "string", "Single write statement, no ATTACH/DROP"),
                prop("confirmed", "boolean", "User confirmation for the write"),
                required = "\"sql\"",
            ),
            sideEffect = SideEffect.WRITE,
            annotations = ToolAnnotations(
                requiresSwitch = DbTools.SWITCH,
                switchDefault = DbTools.SWITCH_DEFAULT,
                fallbackHint = DbTools.FALLBACK_HINT,
            ),
        ),
        ToolDef(
            name = LspTools.SYMBOLS_NAME,
            description = "Code symbol lookup via the local stub, or an MCP-backed LSP bridge when provided.",
            jsonSchema = schema(
                prop("query", "string", "Symbol name to look up"),
                prop("pathPrefix", "string", "Optional path scope"),
                prop("limit", "integer", "Max symbols, clamped to 200"),
                required = "\"query\"",
            ),
            sideEffect = SideEffect.READ,
            annotations = ToolAnnotations(
                requiresSwitch = LspTools.SWITCH,
                switchDefault = LspTools.SWITCH_DEFAULT,
                fallbackHint = LspTools.FALLBACK_HINT,
            ),
        ),
        ToolDef(
            name = LspTools.DIAGNOSTICS_NAME,
            description = "File diagnostics via the local stub, or an MCP-backed LSP bridge when provided.",
            jsonSchema = schema(
                prop("path", "string", "File path to inspect"),
                required = "\"path\"",
            ),
            sideEffect = SideEffect.READ,
            annotations = ToolAnnotations(
                requiresSwitch = LspTools.SWITCH,
                switchDefault = LspTools.SWITCH_DEFAULT,
                fallbackHint = LspTools.FALLBACK_HINT,
            ),
        ),
    )
    // S1-B-ANCHOR-END

    // S1-A-ANCHOR-BEGIN: clipboard + files tools owned by S1-A.
    // S1-B / S1-C: append your own anchored blocks elsewhere;
    // do not edit inside this block (merge-conflict avoidance).
    val S1A_TOOLS: List<ToolDef> = listOf(
        ToolDef(
            name = ClipboardTools.READ_NAME,
            description = "Read text from the foreground clipboard via ClipboardManager; background reads are unavailable.",
            jsonSchema = schema(),
            sideEffect = SideEffect.READ,
            annotations = ToolAnnotations(
                requiresSwitch = ClipboardTools.SWITCH,
                switchDefault = ClipboardTools.SWITCH_DEFAULT,
                foregroundOnly = true,
                degradedWithoutPermission = false,
                fallbackHint = ClipboardTools.FALLBACK_READ_HINT,
            ),
        ),
        ToolDef(
            name = ClipboardTools.WRITE_NAME,
            description = "Write text to the foreground clipboard via ClipboardManager; background writes are unavailable.",
            jsonSchema = schema(
                prop("text", "string", "Text to copy to the clipboard"),
                prop("label", "string", "Clip label shown by the system"),
                required = "\"text\"",
            ),
            sideEffect = SideEffect.WRITE,
            annotations = ToolAnnotations(
                requiresSwitch = ClipboardTools.SWITCH,
                switchDefault = ClipboardTools.SWITCH_DEFAULT,
                foregroundOnly = true,
                degradedWithoutPermission = false,
                fallbackHint = ClipboardTools.FALLBACK_WRITE_HINT,
            ),
        ),
        ToolDef(
            name = FileEditTools.EDIT_NAME,
            description = "Create or overwrite a file in the private domain (or a granted SAF tree) with full content; cross-domain paths are refused.",
            jsonSchema = schema(
                prop("path", "string", "Private-domain relative path or absolute path"),
                prop("content", "string", "Full file content (UTF-8)"),
                required = "\"path\", \"content\"",
            ),
            sideEffect = SideEffect.WRITE,
            annotations = ToolAnnotations(
                requiresSwitch = FileEditTools.SWITCH,
                switchDefault = FileEditTools.SWITCH_DEFAULT,
                degradedWithoutPermission = false,
                fallbackHint = FileEditTools.FALLBACK_HINT,
            ),
        ),
        ToolDef(
            name = FileEditTools.PATCH_NAME,
            description = "Replace a unique text span inside a private-domain (or granted SAF) file; always keeps a .bak backup and writes atomically.",
            jsonSchema = schema(
                prop("path", "string", "Private-domain relative path or absolute path"),
                prop("oldText", "string", "Text span to find (must be unique by default)"),
                prop("newText", "string", "Replacement text"),
                prop("singleMatch", "boolean", "Fail when the span matches more than once"),
                required = "\"path\", \"oldText\", \"newText\"",
            ),
            sideEffect = SideEffect.WRITE,
            annotations = ToolAnnotations(
                requiresSwitch = FileEditTools.SWITCH,
                switchDefault = FileEditTools.SWITCH_DEFAULT,
                degradedWithoutPermission = false,
                fallbackHint = FileEditTools.FALLBACK_HINT,
            ),
        ),
        ToolDef(
            name = FileSearchTools.SEARCH_NAME,
            description = "Search file names and text inside the private domain (or granted SAF trees); absolute roots pass the restricted-shell path gate.",
            jsonSchema = schema(
                prop("query", "string", "Single-line literal to search for"),
                prop("root", "string", "Private relative prefix or absolute root; empty means the whole private domain"),
                prop("maxHits", "integer", "Max hits to return"),
                required = "\"query\"",
            ),
            sideEffect = SideEffect.READ,
            annotations = ToolAnnotations(
                requiresSwitch = FileSearchTools.SWITCH,
                switchDefault = FileSearchTools.SWITCH_DEFAULT,
                degradedWithoutPermission = false,
                fallbackHint = FileSearchTools.FALLBACK_HINT,
            ),
        ),
        ToolDef(
            name = FileAttachTools.ATTACH_NAME,
            description = "Stage a Photo Picker / SAF-picked image or file into the private workspace for chat attachments (skeleton for the ChatScreen entry point).",
            jsonSchema = schema(
                prop("mimeTypes", "array", "Accepted MIME types, e.g. image/*"),
                prop("maxSizeBytes", "integer", "Size cap in bytes"),
            ),
            sideEffect = SideEffect.READ,
            annotations = ToolAnnotations(
                requiresSwitch = FileAttachTools.SWITCH,
                switchDefault = FileAttachTools.SWITCH_DEFAULT,
                degradedWithoutPermission = false,
                fallbackHint = FileAttachTools.FALLBACK_HINT,
            ),
        ),
    )
    // S1-A-ANCHOR-END

    // S2-ANCHOR-BEGIN: voice tools owned by S2 (system STT/TTS first, Azure github-only).
    // Do not edit inside this block from other workstreams (merge-conflict avoidance).
    // - voice.transcribe (READ, switch voice_input default true): system RecognizerIntent
    //   transcript entry; audio never touches disk; text is redacted before the store.
    // - voice.speak (WRITE, switch voice_output default true): system TextToSpeech,
    //   zero new permissions.
    // - voice.speak.azure (WRITE, github-only, switch azure_tts default false):
    //   Azure cloud fallback; additionally requires voice_output on (dual-switch,
    //   enforced by AzureSpeechGate AND by the second switch below, so the
    //   projection never diverges from the gate); text is redacted again before
    //   the cloud call.
    val VOICE_TOOLS: List<ToolDef> = listOf(
        ToolDef(
            name = "voice.transcribe",
            description = "Transcribe speech via the system recognizer; audio is memory-only and the text is redacted before storage.",
            jsonSchema = schema(
                prop("locale", "string", "BCP-47 locale hint, e.g. zh-TW"),
            ),
            sideEffect = SideEffect.READ,
            annotations = ToolAnnotations(
                requiresSwitch = "voice_input",
                switchDefault = true,
                fallbackHint = "type the message manually in the input box",
            ),
        ),
        ToolDef(
            name = "voice.speak",
            description = "Read text aloud via the system text-to-speech engine; no new permissions.",
            jsonSchema = schema(
                prop("text", "string", "Text to speak aloud"),
                required = "\"text\"",
            ),
            sideEffect = SideEffect.WRITE,
            annotations = ToolAnnotations(
                requiresSwitch = "voice_output",
                switchDefault = true,
                fallbackHint = "read the message text on screen manually",
            ),
        ),
        ToolDef(
            name = "voice.speak.azure",
            description = "Cloud voice fallback via Azure Speech (github flavor only); needs voice_output and azure_tts on, text redacted before upload.",
            jsonSchema = schema(
                prop("text", "string", "Text to synthesize via Azure"),
                prop("voice", "string", "Optional Azure voice name"),
                required = "\"text\"",
            ),
            sideEffect = SideEffect.WRITE,
            annotations = ToolAnnotations(
                requiresSwitch = "azure_tts",
                switchDefault = false,
                // M1 雙開關自訂投影：azure_tts 關閉或 voice_output 關閉，
                // 任一即 UNAVAILABLE + USER_DISABLED（與 AzureSpeechGate 同語義；
                // 預設值與 VoiceTools.SWITCH_AZURE_DEFAULT / SWITCH_OUTPUT_DEFAULT 同源）。
                requiresSwitch2 = "voice_output",
                switchDefault2 = true,
                fallbackHint = "use the system voice output, or read the text on screen manually",
            ),
            supportedFlavors = setOf(Flavor.GITHUB),
        ),
    )
    // S2-ANCHOR-END

    /** Slow-channel descriptor: projection-only, never executed by FastRouter. */
    val SLOW_TOOLS: List<ToolDef> = listOf(
        ToolDef(
            name = SLOW_TOOL_NAME,
            description = "Screen-understanding GUI automation (foss/github only, default off).",
            jsonSchema = schema(
                prop("goal", "string", "What to achieve on screen"),
                required = "\"goal\"",
            ),
            sideEffect = SideEffect.PRIVILEGED,
            annotations = ToolAnnotations(
                requiresSwitch = ToolDef.SWITCH_AUTOMATION,
                switchDefault = false,
                fallbackHint = "follow the generated manual step-by-step guide",
            ),
            supportedFlavors = setOf(Flavor.FOSS, Flavor.GITHUB),
        ),
    )

    // S1-B: ALL covers FAST + SLOW + S1B + S1A + VOICE (S1-A appends S1A; S1-C extended FAST; S2 appends VOICE).
    val ALL: List<ToolDef> = FAST_TOOLS + SLOW_TOOLS + S1B_TOOLS + S1A_TOOLS + VOICE_TOOLS

    fun find(name: String): ToolDef? = ALL.firstOrNull { it.name == name }

    /** Pure projection over every known tool. */
    fun projectAll(ctx: ProjectionContext): Map<String, Projection> =
        ALL.associate { it.name to it.project(ctx) }

    /** Tools visible to the model this round (excludes UNAVAILABLE). */
    fun visibleTools(ctx: ProjectionContext): List<ToolDef> =
        ALL.filter { it.project(ctx).level != CapabilityLevel.UNAVAILABLE }
}
