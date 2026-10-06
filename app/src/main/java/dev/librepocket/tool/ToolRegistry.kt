package dev.librepocket.tool

/**
 * Built-in tool registry: the 11 P2 fast-channel tools (BACKLOG B3,
 * CAPABILITY_MATRIX §1) plus the slow-channel descriptor used only for
 * projection (hidden on play, full-only, default off).
 *
 * Compliance red lines (MATRIX §2), enforced here and asserted by tests:
 * - phone uses `ACTION_DIAL` prefill only, never `ACTION_CALL`;
 * - SMS goes through the system editor prefill only; this registry requests
 *   no SMS permission ([ToolAnnotations.requiresPermission] is null for
 *   `sms.compose` and no schema mentions SEND_SMS / READ_SMS).
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
    )

    /** Slow-channel descriptor: projection-only, never executed by FastRouter. */
    val SLOW_TOOLS: List<ToolDef> = listOf(
        ToolDef(
            name = SLOW_TOOL_NAME,
            description = "Screen-understanding GUI automation (full flavor only, default off).",
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
            supportedFlavors = setOf(Flavor.FULL),
        ),
    )

    val ALL: List<ToolDef> = FAST_TOOLS + SLOW_TOOLS

    fun find(name: String): ToolDef? = ALL.firstOrNull { it.name == name }

    /** Pure projection over every known tool. */
    fun projectAll(ctx: ProjectionContext): Map<String, Projection> =
        ALL.associate { it.name to it.project(ctx) }

    /** Tools visible to the model this round (excludes UNAVAILABLE). */
    fun visibleTools(ctx: ProjectionContext): List<ToolDef> =
        ALL.filter { it.project(ctx).level != CapabilityLevel.UNAVAILABLE }
}
