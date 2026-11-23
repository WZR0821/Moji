package com.raydon.moji.android.ai

import com.raydon.moji.core.PlanSnapshot
import java.net.URI
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Debug-only stand-in for a provider, so screens that wait on a model can be
 * opened and photographed without a key or a network. Mode `ready` starts
 * with a provider set up (keys held in memory only), `empty` starts as a new
 * install does, `error` makes every request fail with 401. It is off unless
 * MainActivity calls [activate] in a debug build, so a release build never
 * turns it on.
 */
object AIQAFixture {
    @Volatile var mode: String? = null
        private set

    /** Seconds each stubbed request waits, like MOJI_QA_AI_DELAY. */
    @Volatile var delaySeconds: Double = 0.5
        private set

    /** The settings page to open first: switcher, add, add-preset, add-custom, help, provider or model. */
    @Volatile var page: String? = null
        private set

    val isActive: Boolean get() = mode != null

    /** The photographed scenario (`moji_qa_route`), like MOJI_QA_SCENARIO; empty outside QA. */
    @Volatile var scenario: String = ""
        private set

    @Volatile private var fixtureStore: AISettingsStore? = null

    /** Called from MainActivity with the `moji_qa_ai*` extras and the route, only when BuildConfig.DEBUG. */
    fun activate(mode: String?, delaySeconds: Double?, page: String?, scenario: String? = null) {
        val clean = mode?.trim()?.takeIf { it.isNotEmpty() }
        synchronized(this) {
            this.mode = clean
            this.delaySeconds = delaySeconds?.takeIf { it.isFinite() && it >= 0 } ?: 0.5
            this.page = page?.trim()?.takeIf { it.isNotEmpty() }
            this.scenario = scenario?.trim().orEmpty()
            fixtureStore = if (clean == null) null else makeStore(clean)
        }
        // The brief's cache, dismissals and order start empty at each launch,
        // and so do the evening wrap, the write log and the letters.
        AIProactiveStore.resetFixture()
        AIWriteLog.resetFixture()
        AIEveningWrapState.resetFixture()
        AIWeeklyLetterState.resetFixture()
    }

    /**
     * 21:30 today for the evening screens, this week's Sunday 20:30 for the
     * letter, otherwise null (the real clock).
     */
    val clock: ZonedDateTime?
        get() {
            if (!isActive) return null
            val current = scenario
            val today = LocalDate.now()
            val zone = ZoneId.systemDefault()
            if (current.startsWith("ai-wrap") || current == "ai-writes") return today.atTime(21, 30).atZone(zone)
            if (current.startsWith("ai-letter")) return AIDrafting.weekStart(today).plusDays(6).atTime(20, 30).atZone(zone)
            return null
        }

    /**
     * Whole days from today to this week's Sunday: the letter screens move the
     * sample data that far so it falls inside the week being written about.
     */
    val letterShiftDays: Int
        get() {
            if (!isActive || !scenario.startsWith("ai-letter")) return 0
            val today = LocalDate.now()
            return ChronoUnit.DAYS.between(today, AIDrafting.weekStart(today).plusDays(6)).toInt()
        }

    /** The sample data moved by whole days, in the device zone. */
    fun shifted(snapshot: PlanSnapshot, days: Int, zone: ZoneId = ZoneId.systemDefault()): PlanSnapshot {
        if (days == 0) return snapshot
        fun move(text: String): String = AIDrafting.instant(text)?.atZone(zone)?.plusDays(days.toLong())?.toInstant()?.toString() ?: text
        return snapshot.copy(
            checkInItems = snapshot.checkInItems.map { item ->
                item.copy(
                    scheduledStart = move(item.scheduledStart),
                    createdAt = move(item.createdAt),
                    completedAt = item.completedAt?.let(::move),
                    actualStartDate = item.actualStartDate?.let(::move),
                    actualEndDate = item.actualEndDate?.let(::move),
                )
            },
            records = snapshot.records.map { record -> record.copy(startDate = move(record.startDate), endDate = move(record.endDate)) },
        )
    }

    /** The in-memory settings store while the fixture is on. */
    fun store(): AISettingsStore? = if (isActive) fixtureStore else null

    private fun makeStore(mode: String): AISettingsStore {
        val store = AISettingsStore(AIMemoryPreferences(), AIMemoryKeyStore())
        if (mode == "empty") return store
        val settings = AISettings(
            isEnabled = true,
            activeID = AIProvider.deepSeek.rawValue,
            providerList = listOf(AIProvider.deepSeek.rawValue, AIProvider.claude.rawValue),
        )
        runCatching {
            store.save(settings, mapOf(AIProvider.deepSeek.rawValue to "qa-placeholder", AIProvider.claude.rawValue to "qa-placeholder"))
        }
        return store
    }

    /** Answers every request locally after the configured delay. */
    val transport: AITransport = object : AITransport {
        override suspend fun send(request: AIHttpRequest): AIHttpResponse {
            delay((delaySeconds * 1000).toLong())
            val path = runCatching { URI(request.url).path }.getOrNull() ?: ""
            val (status, body) = AIQAStubProtocol.response(path, request.body ?: ByteArray(0))
            return AIHttpResponse(status, body)
        }
    }

    /** The answer a model would give, picked by the task named in the instructions. */
    fun answer(instructions: String, input: String): String {
        if (instructions.contains("收尾今天")) {
            val items = mutableListOf<String>()
            var inPending = false
            for (line in input.split("\n")) {
                if (line.startsWith("没做完的")) { inPending = true; continue }
                if (line.startsWith("最近的日子")) inPending = false
                val number = if (inPending) numbered(line) else null
                if (number == null) continue
                items.add(if (line.contains("已顺延")) """{"index":$number,"action":"split","note":"已经顺延三天"}"""
                    else """{"index":$number,"action":"tomorrow","note":""}""")
            }
            return """{"items":[${items.joinToString(",")}]}"""
        }
        if (instructions.contains("本周来信")) {
            val numbers = input.split("\n").mapNotNull { line -> numbered(line)?.let { it to line } }
            val a = numbers.firstOrNull { it.second.contains("待办") }?.first ?: 1
            var b = numbers.firstOrNull { it.second.contains("汇报") || it.second.contains("发布") }?.first ?: 2
            if (b == a) b = if (numbers.any { it.first == a + 1 }) a + 1 else 1
            return """{"title":"卡在第一步的一周","body":"前半周很稳；周四起两件大事一直顺延，都卡在没拆开的第一步。","next_focus":"先把大事拆出能马上开始的第一步。","evidence":[{"point":"「归纳昨日待办」一直顺延","plans":[$a]},{"point":"大事还没开始","plans":[$b]}]}"""
        }
        if (instructions.contains("排今天计划的先后")) {
            // The release notes plan goes first, whatever its number is today.
            val line = input.lines().firstOrNull { it.contains("发布说明") }
            val number = line?.let { Regex("""^\[(\d+)\]""").find(it.trim())?.groupValues?.get(1)?.toIntOrNull() } ?: 1
            return """{"order":[$number],"reason":"离项目交付只剩两天，先把说明定稿"}"""
        }
        if (instructions.contains("拆出结构")) {
            val parts = input.split('、', '，', ',').map { it.trim { c -> c == ' ' || c == '\t' } }.filter { it.isNotEmpty() }
            if (parts.size >= 2) {
                val items = parts.joinToString(",") { JsonPrimitive(it).toString() }
                return """{"title":null,"date":null,"offset_days":null,"weekday":null,"week_offset":null,"part":null,"time":null,"minutes":null,"category":null,"repeat":null,"items":[$items]}"""
            }
            return """{"title":"交保险计划书","date":null,"offset_days":1,"weekday":null,"week_offset":null,"part":"exact","time":"15:00","minutes":45,"category":"学习","repeat":null,"items":[]}"""
        }
        if (instructions.contains("拆成可以直接动手的小步骤")) {
            return """{"steps":[{"title":"列出第三节要补的两处论据","minutes":10},{"title":"查中出教授 2019 年论文的页码","minutes":20},{"title":"改写第三节第一段","minutes":40},{"title":"通读全文并核对字数","minutes":15}]}"""
        }
        if (instructions.contains("整理成清单")) {
            return """{"items":["打印准考证","确认考场路线和到达时间","准备黑色签字笔两支","复习损害保险的三个判例"]}"""
        }
        if (instructions.contains("准备转成计划的几行")) {
            return """{"rows":[{"index":0,"date":null,"offset_days":1,"weekday":null,"week_offset":null,"category":"学习"},{"index":1,"date":null,"offset_days":null,"weekday":5,"week_offset":0,"category":"工作"}]}"""
        }
        if (instructions.contains("起草周复盘")) {
            return """{"note":"这周把复习按天排开后，前半周完成得比较稳；周四起两件大的计划一直顺延，卡在开头那一步没有拆开。","next_focus":"大的计划先拆出十分钟能开始的第一步。","evidence":[{"point":"前半周的计划都按时完成","plans":[1,2]},{"point":"顺延的计划都偏大","plans":[3]}]}"""
        }
        if (instructions.contains("拟下周的计划草稿")) {
            return """{"plans":[{"title":"把顺延的计划拆出第一步","weekday":1,"part":"morning","minutes":25,"category":"学习","reason":"顺延最久"},{"title":"补完第三节论据","weekday":2,"part":"afternoon","minutes":60,"category":"学习","reason":"本周没做完"},{"title":"通读全文并核对字数","weekday":4,"part":"evening","minutes":30,"category":"学习","reason":"收尾"},{"title":"写下周复盘","weekday":7,"part":"evening","minutes":15,"category":null,"reason":"下周关注里提到"}]}"""
        }
        if (instructions.contains("拟一份准备计划")) {
            return """{"plans":[{"title":"列出要准备的材料清单","days_before":2,"minutes":20,"category":"学习","reason":"先看清要做什么"},{"title":"完成第一轮准备","days_before":1,"minutes":90,"category":"学习","reason":"留出修改的时间"},{"title":"查漏补缺并模拟一遍","days_before":1,"minutes":60,"category":"学习","reason":"发现问题还来得及"},{"title":"确认时间、地点和要带的东西","days_before":0,"minutes":15,"category":null,"reason":"前一天核对"}]}"""
        }
        if (instructions.contains("计划库的筛选条件")) {
            return """{"keyword":null,"category":"学习","status":"planned","period":"last_week","from":null,"to":null}"""
        }
        if (instructions.contains("粘贴的一段文字") && input.contains("季度报告")) {
            // ai-share: the shared sentence, so the drafts match what was shared.
            return """{"plans":[{"title":"交季度报告","date":null,"offset_days":null,"weekday":5,"week_offset":0,"part":null,"time":null,"minutes":null,"category":"工作"},{"title":"和小王对一下数据","date":null,"offset_days":null,"weekday":3,"week_offset":0,"part":"afternoon","time":null,"minutes":30,"category":"工作"},{"title":"订会议室","date":null,"offset_days":1,"weekday":null,"week_offset":null,"part":"morning","time":null,"minutes":10,"category":"工作"}]}"""
        }
        if (instructions.contains("粘贴的一段文字")) {
            return """{"plans":[{"title":"交志望理由書终稿","date":null,"offset_days":2,"weekday":null,"week_offset":null,"part":"exact","time":"17:00","minutes":null,"category":"学习"},{"title":"预约体检","date":null,"offset_days":null,"weekday":5,"week_offset":0,"part":"morning","time":null,"minutes":20,"category":null},{"title":"给老师回邮件确认面谈时间","date":null,"offset_days":0,"weekday":null,"week_offset":null,"part":null,"time":null,"minutes":10,"category":"学习"}]}"""
        }
        return """{"ok":true}"""
    }
}

/** The number of a line that starts with `[n]`. */
private fun numbered(line: String): Int? {
    if (!line.startsWith("[")) return null
    val close = line.indexOf(']')
    if (close < 0) return null
    return line.substring(1, close).toIntOrNull()
}

/** The stub's replies, by request path; Swift's AIQAStubProtocol. */
object AIQAStubProtocol {
    fun response(path: String, body: ByteArray): Pair<Int, ByteArray> {
        fun json(value: JsonObject) = value.toString().toByteArray(Charsets.UTF_8)
        if (AIQAFixture.mode == "error") {
            return 401 to json(buildJsonObject { put("error", buildJsonObject { put("message", "Authentication Fails (QA fixture)") }) })
        }
        if (path.endsWith("/models")) {
            return 200 to json(buildJsonObject {
                put("data", buildJsonArray {
                    listOf("deepseek-flash", "deepseek-v4-pro", "deepseek-coder-2").forEach { id -> add(buildJsonObject { put("id", id) }) }
                })
            })
        }
        val root = AIClient.parse(body) as? JsonObject ?: JsonObject(emptyMap())
        var instructions = root["system"].aiString() ?: root["instructions"].aiString() ?: ""
        var input = root["input"].aiString() ?: ""
        for (message in (root["messages"] as? JsonArray).orEmpty()) {
            val item = message as? JsonObject ?: continue
            val content = item["content"].aiString() ?: ""
            if (item["role"].aiString() == "system") instructions = content else input = content
        }
        val text = AIQAFixture.answer(instructions, input)
        if (path.endsWith("/messages")) {
            return 200 to json(buildJsonObject {
                put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", text) }) })
                put("stop_reason", "end_turn")
            })
        }
        if (path.endsWith("/responses")) {
            return 200 to json(buildJsonObject {
                put("status", "completed")
                put("output", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "message")
                        put("content", buildJsonArray { add(buildJsonObject { put("type", "output_text"); put("text", text) }) })
                    })
                })
            })
        }
        return 200 to json(buildJsonObject {
            put("choices", buildJsonArray {
                add(buildJsonObject {
                    put("message", buildJsonObject { put("role", "assistant"); put("content", text) })
                    put("finish_reason", "stop")
                })
            })
        })
    }
}
