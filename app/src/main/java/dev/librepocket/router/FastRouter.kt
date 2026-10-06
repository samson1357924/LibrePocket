package dev.librepocket.router

import dev.librepocket.tool.CapabilityLevel
import dev.librepocket.tool.ProjectionContext
import dev.librepocket.tool.ToolRegistry
import java.util.concurrent.atomic.AtomicLong

/**
 * Fast lane: deterministic intent-template routing (ARCHITECTURE §9.1/§9.3).
 *
 * Rule engine first: an intent matching a known template goes FAST with a
 * schema-checked [ToolCall]; a match whose projection is UNAVAILABLE is
 * denied with the projection reason code; anything else is DENIED as
 * no-match with manual guidance. Every outcome carries a [TranscriptHeader]
 * with the decision and the visible-tool snapshot.
 *
 * Pure logic, no Android dependencies. Confirmation is NOT checked here;
 * [PrivilegeGate] owns the PRIVILEGED execution gate.
 */
class FastRouter(
    private val registry: ToolRegistry = ToolRegistry,
) {
    private val callIds = AtomicLong(0)

    fun decide(
        intentText: String,
        ctx: ProjectionContext,
        sessionId: String? = null,
        turnIndex: Int? = null,
    ): RouteDecision {
        val projections = registry.projectAll(ctx)
        val visible = projections.values
            .filter { it.level != CapabilityLevel.UNAVAILABLE }
            .map { it.toolName }
        fun header(route: RouteKind, toolName: String?, reason: dev.librepocket.tool.DenyReason?) =
            TranscriptHeader(
                sessionId = sessionId,
                turnIndex = turnIndex,
                route = route,
                toolName = toolName,
                reasonCode = reason?.name,
                visibleTools = visible,
            )

        val match = matchTemplate(intentText.trim())
            ?: return RouteDecision(
                route = RouteKind.DENIED,
                toolCall = null,
                reasonCode = null,
                fallbackMessage = FallbackMessages.forNoMatch(intentText),
                header = header(RouteKind.DENIED, null, null),
            )

        val tool = registry.find(match.toolName)
            ?: return RouteDecision(
                route = RouteKind.DENIED,
                toolCall = null,
                reasonCode = null,
                fallbackMessage = FallbackMessages.forNoMatch(intentText),
                header = header(RouteKind.DENIED, match.toolName, null),
            )
        val projection = projections.getValue(tool.name)
        if (projection.level == CapabilityLevel.UNAVAILABLE) {
            val reason = projection.reason ?: dev.librepocket.tool.DenyReason.USER_DISABLED
            return RouteDecision(
                route = RouteKind.DENIED,
                toolCall = null,
                reasonCode = reason,
                fallbackMessage = FallbackMessages.forTool(tool.name, reason, tool.annotations.fallbackHint),
                header = header(RouteKind.DENIED, tool.name, reason),
            )
        }
        val call = ToolCall(
            toolName = tool.name,
            argumentsJson = match.argumentsJson,
            callId = "fast-${callIds.incrementAndGet()}",
        )
        return RouteDecision(
            route = RouteKind.FAST,
            toolCall = call,
            reasonCode = null,
            fallbackMessage = null,
            header = header(RouteKind.FAST, tool.name, null),
        )
    }

    private data class TemplateMatch(val toolName: String, val argumentsJson: String)

    private fun jsonText(text: String): String =
        "{\"text\": \"${text.replace("\\", "\\\\").replace("\"", "\\\"")}\"}"

    private fun matchTemplate(text: String): TemplateMatch? {
        if (text.isEmpty()) return null
        // Slow-lane intents resolve to the automation descriptor so projection
        // can deny them honestly (FLAVOR_BLOCKED on play) instead of NO_MATCH.
        if (containsAny(text, SLOW_HINTS)) {
            return TemplateMatch(ToolRegistry.SLOW_TOOL_NAME, jsonText(text))
        }
        for ((toolName, hints, args) in RULES) {
            if (containsAny(text, hints)) return TemplateMatch(toolName, args(text))
        }
        return null
    }

    private fun containsAny(text: String, hints: List<Regex>): Boolean =
        hints.any { it.containsMatchIn(text) }

    private fun r(pattern: String): Regex = Regex(pattern, RegexOption.IGNORE_CASE)

    private val RULES: List<Triple<String, List<Regex>, (String) -> String>> = listOf(
        Triple(
            "screenshot.capture",
            listOf(r("截圖"), r("截图"), r("擷取"), r("screenshot"), r("螢幕.*存"), r("屏幕.*存")),
            { t -> "{\"share\": false, \"text\": \"${esc(t)}\"}" },
        ),
        Triple(
            "notification.read",
            listOf(r("通知"), r("notification"), r("未讀"), r("未读"), r("訊息中心")),
            { t -> "{\"limit\": 10, \"text\": \"${esc(t)}\"}" },
        ),
        Triple(
            "alarm.create",
            listOf(r("鬧鐘"), r("闹钟"), r("alarm"), r("定時.*響"), r("定时.*响"), r("提醒.*點"), r("提醒.*点")),
            { t -> alarmArgs(t) },
        ),
        Triple(
            "phone.dial",
            listOf(r("打電話"), r("打电话"), r("撥打"), r("拨打"), r("撥號"), r("拨号"), r("打給"), r("打给"), r("致電"), r("致电"), r("\\bcall\\b"), r("tel:")),
            { t -> phoneArgs(t) },
        ),
        Triple(
            "sms.compose",
            listOf(r("簡訊"), r("简讯"), r("短信"), r("短訊"), r("短讯"), r("\\bsms\\b"), r("傳訊息給"), r("发消息")),
            { t -> "{\"to\": \"\", \"body\": \"${esc(t)}\", \"raw\": \"${esc(t)}\"}" },
        ),
        Triple(
            "mail.compose",
            listOf(r("郵件"), r("邮件"), r("e-?mail"), r("寫信"), r("写信"), r("寄信"), r("發郵件"), r("发邮件")),
            { t -> "{\"to\": \"\", \"subject\": \"\", \"body\": \"${esc(t)}\"}" },
        ),
        Triple(
            "calendar.create",
            listOf(r("日曆"), r("日历"), r("calendar"), r("行程"), r("會議"), r("会议"), r("約會"), r("约会"), r("安排.*(會|事)")),
            { t -> "{\"title\": \"${esc(t)}\", \"start\": \"\", \"end\": \"\"}" },
        ),
        Triple(
            "music.control",
            listOf(r("音樂"), r("音乐"), r("播放.*歌"), r("放.*歌"), r("切歌"), r("下一首"), r("上一首"), r("暫停.*音樂"), r("暂停.*音乐"), r("\\bmusic\\b")),
            { t -> "{\"action\": \"${musicAction(t)}\", \"text\": \"${esc(t)}\"}" },
        ),
        Triple(
            "volume.set",
            listOf(r("音量"), r("靜音"), r("静音"), r("大聲"), r("大声"), r("小聲"), r("小声"), r("調.*音"), r("调.*音"), r("volume"), r("mute")),
            { t -> volumeArgs(t) },
        ),
        Triple(
            "navigate",
            listOf(r("導航"), r("导航"), r("路線"), r("路线"), r("帶我去"), r("带我去"), r("怎麼去"), r("怎么去"), r("去.*(路|街|店|餐廳|餐厅|公司|家|站|場|场|店)"), r("\\bmap\\b")),
            { t -> "{\"destination\": \"${esc(t)}\", \"mode\": \"driving\"}" },
        ),
        Triple(
            "open_app",
            listOf(r("打開"), r("打开"), r("開啟"), r("开启"), r("啟動"), r("启动"), r("開.*app"), r("开.*app")),
            { t -> "{\"appName\": \"${esc(t)}\", \"packageName\": \"\"}" },
        ),
    )

    private val SLOW_HINTS: List<Regex> = listOf(
        r("自動點擊"), r("自动点击"), r("點擊螢幕"), r("点击屏幕"), r("幫我點"), r("帮我点"),
        r("自動填表"), r("自动填表"), r("代操"), r("自動操作"), r("自动操作"),
    )

    private fun esc(text: String): String =
        text.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun phoneArgs(text: String): String {
        val digits = Regex("[+\\d][\\d\\-\\.\\s]{5,16}").find(text)?.value?.trim() ?: ""
        return "{\"number\": \"${esc(digits)}\", \"raw\": \"${esc(text)}\"}"
    }

    private fun musicAction(text: String): String = when {
        Regex("下一首|切歌|next", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "next"
        Regex("上一首|previous", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "previous"
        Regex("暫停|暂停|pause", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "pause"
        else -> "play"
    }

    private fun volumeArgs(text: String): String {
        val mute = Regex("靜音|静音|mute", RegexOption.IGNORE_CASE).containsMatchIn(text)
        val level = Regex("(\\d{1,3})").find(text)?.groupValues?.get(1)?.toIntOrNull()
            ?.coerceIn(0, 100)
        return "{\"level\": ${level ?: 50}, \"mute\": $mute}"
    }

    private fun alarmArgs(text: String): String {
        val m = Regex("(\\d{1,2})[:點点](\\d{1,2})?").find(text)
        val hour = m?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(0, 23) ?: 7
        val minute = m?.groupValues?.get(2)?.takeIf { it.isNotEmpty() }?.toIntOrNull()
            ?.coerceIn(0, 59) ?: 0
        return "{\"hour\": $hour, \"minute\": $minute, \"label\": \"${esc(text)}\"}"
    }
}
