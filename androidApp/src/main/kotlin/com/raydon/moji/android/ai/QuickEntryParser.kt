package com.raydon.moji.android.ai

import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.Locale
import java.util.regex.Matcher
import java.util.regex.Pattern

// Ported from Moji/AI/QuickEntryParser.swift, rule for rule (spec §3.1 and §11),
// so a sentence gives the same chips on both platforms.

/**
 * One thing recognised on the device in a sentence typed into the home page's
 * field: a day, a time, a length, a repeat or a type. It becomes a chip under
 * the field and changes nothing until the user taps it.
 */
data class QuickEntryToken(
    val kind: Kind,
    /** What the chip says: 周三, 15:00, 45 分钟, 每周, 工作. */
    val text: String,
    /** Where the words sit in the sentence. A type keeps its words in the title, so it has none. */
    val span: Span?,
    val day: LocalDate? = null,
    val scheduleKind: String? = null,
    val hour: Int = 0,
    val minute: Int = 0,
    val minutes: Int? = null,
    val repeatRule: String? = null,
    val category: String? = null,
) {
    enum class Kind { Day, Time, Duration, Repeat, Category }

    /** `[start, end)` in UTF-16 offsets, like an NSRange. */
    data class Span(val start: Int, val end: Int) {
        operator fun contains(index: Int): Boolean = index >= start && index < end
        fun overlaps(other: Span): Boolean = start < other.end && other.start < end
        fun union(other: Span): Span = Span(minOf(start, other.start), maxOf(end, other.end))
    }

    /**
     * Same kind, same words: a chip stays adopted while typing goes on, and
     * becomes a new chip once its words change.
     */
    val id: String get() = "${kind.ordinal}|$text"
}

/**
 * Reads days, times, lengths, repeats and types out of one typed plan with
 * fixed rules, so the chips appear while typing, without a network and
 * without sending anything. Pure: the same sentences replay in tests.
 * `categories` are raw values ("study", "work", "custom:生活").
 */
object QuickEntryParser {
    fun tokens(
        text: String,
        now: ZonedDateTime = ZonedDateTime.now(),
        categories: List<String>,
        defaultCategory: String?,
    ): List<QuickEntryToken> {
        if (text.isBlank()) return emptyList()
        val scan = Scan(text, now.toLocalDate())
        scan.readRepeat()
        scan.readDay()
        scan.readTime()
        scan.readDuration()
        scan.readCategory(categories.filter { it != defaultCategory })
        return scan.tokens.sortedBy { it.kind.ordinal }
    }

    /**
     * The sentence without the words of the adopted chips. Words shared by
     * several chips (明早 is a day and a time) go only when all of them are
     * adopted. Empty after stripping means the sentence is kept as it was.
     */
    fun title(text: String, tokens: List<QuickEntryToken>, adopted: Set<String>): String {
        val clean = text.trim()
        val kept = StringBuilder()
        var removed = false
        for (index in text.indices) {
            val covering = tokens.filter { token -> token.span?.let { index in it } == true }
            if (covering.isNotEmpty() && covering.all { it.id in adopted }) removed = true else kept.append(text[index])
        }
        if (!removed) return clean
        var result = kept.toString().replace(spaces, " ")
        while (true) {
            val before = result
            result = result.trim { it.isWhitespace() || it in edgeMarks }
            if (result.startsWith("的")) result = result.substring(1)
            if (result == before) break
        }
        return result.ifEmpty { clean }
    }

    /** The adopted chips as one suggestion for [EntrySuggestion.applied]; null when nothing is adopted. */
    fun suggestion(text: String, tokens: List<QuickEntryToken>, adopted: Set<String>): EntrySuggestion? {
        val chosen = tokens.filter { it.id in adopted }
        if (chosen.isEmpty()) return null
        var result = EntrySuggestion()
        for (token in chosen) {
            result = when (token.kind) {
                QuickEntryToken.Kind.Day -> result.copy(day = token.day)
                QuickEntryToken.Kind.Time -> result.copy(scheduleKind = token.scheduleKind, hour = token.hour, minute = token.minute)
                QuickEntryToken.Kind.Duration -> result.copy(minutes = token.minutes)
                QuickEntryToken.Kind.Repeat -> result.copy(repeatRule = token.repeatRule)
                QuickEntryToken.Kind.Category -> result.copy(category = token.category)
            }
        }
        val title = title(text, tokens, adopted)
        if (title != text.trim()) result = result.copy(title = title)
        return result
    }

    // Numbers

    private val numeralValues = mapOf(
        '零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
        '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9,
    )

    /** 0–999 written in digits or in Chinese numerals (十五, 二十, 两, 一百二十). */
    fun number(text: String): Int? {
        if (text.isNotEmpty() && text.all { it in '0'..'9' }) return text.toIntOrNull()
        if (text.isEmpty()) return null
        var total = 0
        var digit: Int? = null
        for (character in text) {
            val value = numeralValues[character]
            when {
                value != null -> {
                    // 二三 is not a number; 一百零五 is.
                    if (digit != null && digit != 0) return null
                    digit = value
                }
                character == '百' -> { total += (digit ?: 1) * 100; digit = null }
                character == '十' -> { total += (digit ?: 1) * 10; digit = null }
                else -> return null
            }
        }
        return total + (digit ?: 0)
    }

    /** 1–99 in Chinese numerals: 三, 十, 十二, 二十五. */
    fun chineseNumeral(value: Int): String {
        val names = listOf("零", "一", "二", "三", "四", "五", "六", "七", "八", "九")
        if (value !in 1..99) return value.toString()
        if (value < 10) return names[value]
        val tens = value / 10
        val ones = value % 10
        return (if (tens == 1) "" else names[tens]) + "十" + (if (ones == 0) "" else names[ones])
    }

    // Patterns. `\s` is widened to the ideographic space, as ICU reads it on iOS.

    private const val S = "[\\s\\u3000]"
    private val spaces = Regex("[\\s\\u3000]+")
    private const val edgeMarks = "，,、。.；;：:"

    private const val qualifiers = "早上|早晨|清晨|上午|中午|下午|午后|傍晚|晚上|晚间|夜里"
    private const val clockCore =
        "(?:(?<![0-9])(?<ch>[0-9]{1,2})[:：](?<cm>[0-9]{2})(?![0-9])" +
            "|(?:(?<![0-9.])(?<dh>[0-9]{1,2})|(?<![零〇一二两三四五六七八九十百])(?<zh>[零〇一二两三四五六七八九十]{1,3}))" +
            "点(?<zhong>钟)?(?<mm>半|一刻|三刻|(?:(?<dm>[0-9]{1,2})|(?<zm>[零〇一二两三四五六七八九十]{1,3}))分(?!钟))?)" +
            "(?<suffix>之前|以前|前|左右)?"

    internal val repeatPattern: Pattern = Pattern.compile(
        "(?<workday>(?:每个工作日|工作日)(?![志记报程]))" +
            "|(?<daily>每天|每日|(?<![今明后昨前每])天天)" +
            "|(?<weekly>每个星期|每星期|每礼拜|每周)(?<wwd>[一二三四五六日天1-7](?![次回遍趟]))?" +
            "|(?<monthly>每个月|每月)"
    )
    internal val dayPattern: Pattern = Pattern.compile(
        "(?<today>今天|今日)" +
            "|(?<compound>今早|今晨|今晚|明早|明晨|明晚)" +
            "|(?<rel>大后天|后天|明天|明日)" +
            "|(?<wp>下下|下个|下|这个|这|本)?(?:(?:周|星期|礼拜)(?<wd>[一二三四五六日天1-7])(?![次回遍趟])|(?<weekend>周末))" +
            "|(?<![0-9])(?<mo>[0-9]{1,2})月(?<md>[0-9]{1,2})(?:日|号)?(?![0-9])" +
            "|(?<![0-9月])(?<dd>[0-9]{1,2})(?:日|号)"
    )
    internal val timePattern: Pattern = Pattern.compile("(?:(?<q>$qualifiers)$S?)?$clockCore")
    internal val followingClockPattern: Pattern = Pattern.compile("$S?$clockCore")
    internal val periodPattern: Pattern = Pattern.compile("(?<q>$qualifiers)")
    internal val durationPattern: Pattern = Pattern.compile(
        "(?:(?<![0-9.])(?<hhd>[0-9]+(?:\\.[0-9]+)?)|(?<![零〇一二两三四五六七八九十百])(?<hhz>[零〇一二两三四五六七八九十百]+))" +
            "$S?个?$S?半(?:小时|钟头)" +
            "|(?:(?<![0-9.])(?<hd>[0-9]+(?:\\.[0-9]+)?)|(?<![零〇一二两三四五六七八九十百])(?<hz>[零〇一二两三四五六七八九十百]+))" +
            "$S?个?$S?(?:小时|钟头)(?:$S?(?:(?<hmd>[0-9]+)|(?<hmz>[零〇一二两三四五六七八九十]+))$S?分钟)?" +
            "|(?<half>半个?(?:小时|钟头))" +
            "|(?<quarter>一刻钟)" +
            "|(?:(?<![0-9.])(?<md>[0-9]+)|(?<![零〇一二两三四五六七八九十百])(?<mz>[零〇一二两三四五六七八九十百]+))" +
            "$S?(?:分钟|(?i:mins|min)(?![a-zA-Z]))"
    )

    /** After these, “N点” is a count of points, not a time: 提三点意见. */
    internal val pointNouns = listOf("意见", "建议", "想法", "内容", "要求", "原因", "理由", "问题", "看法", "心得", "体会", "收获")

    /** Before these, “一点” means “a little”: 快一点, 早一点. */
    internal val littleBefore = setOf('快', '慢', '多', '少', '早', '晚', '好', '大', '小', '高', '低', '再')

    internal enum class Part {
        Morning, Noon, Afternoon, Evening;

        companion object {
            fun of(word: String?): Part? = when (word) {
                "早上", "早晨", "清晨", "上午", "今早", "今晨", "明早", "明晨" -> Morning
                "中午" -> Noon
                "下午", "午后" -> Afternoon
                "傍晚", "晚上", "晚间", "夜里", "今晚", "明晚" -> Evening
                else -> null
            }
        }
    }
}

private class Scan(private val text: String, private val today: LocalDate) {
    val tokens = mutableListOf<QuickEntryToken>()
    private val claimed = mutableListOf<QuickEntryToken.Span>()
    private val found = mutableSetOf<QuickEntryToken.Kind>()

    private fun isFree(span: QuickEntryToken.Span): Boolean = claimed.none { it.overlaps(span) }

    private fun claim(span: QuickEntryToken.Span) { claimed.add(span) }

    private fun Matcher.span(): QuickEntryToken.Span = QuickEntryToken.Span(start(), end())

    /** A named group's text; null when it took no part or the pattern has no such group. */
    private fun Matcher.named(name: String): String? = try { group(name) } catch (error: IllegalArgumentException) { null }

    private fun Matcher.namedSpan(name: String): QuickEntryToken.Span? = try {
        val start = start(name)
        if (start < 0) null else QuickEntryToken.Span(start, end(name))
    } catch (error: IllegalArgumentException) {
        null
    }

    private fun clockText(hour: Int, minute: Int) = "%02d:%02d".format(Locale.ROOT, hour, minute)

    // Repeat

    fun readRepeat() {
        val match = QuickEntryParser.repeatPattern.matcher(text)
        while (match.find()) {
            val span = match.span()
            if (!isFree(span)) continue
            val rule = when {
                match.named("workday") != null -> "weekdays"
                match.named("daily") != null -> "daily"
                match.named("weekly") != null -> "weekly"
                else -> "monthly"
            }
            claim(span)
            found.add(QuickEntryToken.Kind.Repeat)
            tokens.add(QuickEntryToken(QuickEntryToken.Kind.Repeat, AIDrafting.repeatName(rule), span, repeatRule = rule))
            // 每周三 also says which day the first one falls on.
            val weekday = match.named("wwd")?.let(::weekday)
            if (weekday != null) {
                found.add(QuickEntryToken.Kind.Day)
                val day = weekdayDate(0, weekday)
                if (day != today) tokens.add(QuickEntryToken(QuickEntryToken.Kind.Day, "周" + weekdayName(weekday), span, day = day))
            }
            return
        }
    }

    // Day

    fun readDay() {
        if (QuickEntryToken.Kind.Day in found) return
        val match = QuickEntryParser.dayPattern.matcher(text)
        while (match.find()) {
            val span = match.span()
            if (!isFree(span)) continue
            if (match.named("today") != null) {
                // Today is where a quick plan lands anyway: no chip, but the
                // words are taken so nothing else reads them.
                claim(span)
                found.add(QuickEntryToken.Kind.Day)
                return
            }
            val compound = match.named("compound")
            if (compound != null) {
                readCompound(compound, span)
                return
            }
            var day: LocalDate? = null
            var label = ""
            val relative = match.named("rel")
            val month = match.named("mo")?.toIntOrNull()
            val monthDate = match.named("md")?.toIntOrNull()
            val date = match.named("dd")?.toIntOrNull()
            if (relative != null) {
                val offset = when (relative) { "大后天" -> 3; "后天" -> 2; else -> 1 }
                day = today.plusDays(offset.toLong())
                label = when (offset) { 3 -> "大后天"; 2 -> "后天"; else -> "明天" }
            } else if (match.named("wd") != null || match.named("weekend") != null) {
                val prefix = match.named("wp") ?: ""
                val weeks = when (prefix) { "下下" -> 2; "下", "下个" -> 1; else -> 0 }
                val weekday = match.named("wd")?.let(::weekday) ?: 6
                day = weekdayDate(weeks, weekday)
                label = (when (weeks) { 2 -> "下下"; 1 -> "下"; else -> "" }) + "周" + weekdayName(weekday)
            } else if (month != null && monthDate != null) {
                day = monthDay(month, monthDate)
            } else if (date != null) {
                day = dayOfMonth(date)
            }
            // A date that does not exist (2月30日) is left as words.
            if (day == null) continue
            if (label.isEmpty()) label = "${day.monthValue}月${day.dayOfMonth}日"
            claim(span)
            found.add(QuickEntryToken.Kind.Day)
            if (day != today) tokens.add(QuickEntryToken(QuickEntryToken.Kind.Day, label, span, day = day))
            return
        }
    }

    /**
     * 今早, 今晚, 明早, 明晚: a day and a part of it in one word, and with a
     * time right after (今晚8点) the time takes the word with it.
     */
    private fun readCompound(word: String, span: QuickEntryToken.Span) {
        val part = QuickEntryParser.Part.of(word) ?: QuickEntryParser.Part.Morning
        found.add(QuickEntryToken.Kind.Day)
        found.add(QuickEntryToken.Kind.Time)
        if (word.startsWith("明")) {
            tokens.add(QuickEntryToken(QuickEntryToken.Kind.Day, "明天", span, day = today.plusDays(1)))
        }
        val clock = QuickEntryParser.followingClockPattern.matcher(text)
        clock.region(span.end, text.length)
        clock.useTransparentBounds(true)
        val time = if (clock.lookingAt() && isFree(clock.span())) resolveClock(clock, part) else null
        if (time != null) {
            val joined = span.union(clock.span())
            claim(joined)
            tokens.add(QuickEntryToken(QuickEntryToken.Kind.Time, clockText(time.first, time.second), joined,
                scheduleKind = "exactTime", hour = time.first, minute = time.second))
        } else {
            claim(span)
            val kind = if (part == QuickEntryParser.Part.Evening) "evening" else "morning"
            tokens.add(QuickEntryToken(QuickEntryToken.Kind.Time, AIDrafting.scheduleName(kind), span, scheduleKind = kind))
        }
    }

    /** 1 = Monday … 7 = Sunday. */
    private fun weekday(mark: String): Int? = when (mark) {
        "一", "1" -> 1
        "二", "2" -> 2
        "三", "3" -> 3
        "四", "4" -> 4
        "五", "5" -> 5
        "六", "6" -> 6
        "日", "天", "7" -> 7
        else -> null
    }

    private fun weekdayName(weekday: Int): String = listOf("一", "二", "三", "四", "五", "六", "日")[weekday.coerceIn(1, 7) - 1]

    /** The same arithmetic as `AIDrafting.day`: a day of this week that has passed means the one next week. */
    private fun weekdayDate(weeks: Int, weekday: Int): LocalDate {
        val monday = AIDrafting.weekStart(today)
        var target = monday.plusDays((weeks * 7 + weekday - 1).toLong())
        if (weeks == 0 && target < today) target = target.plusDays(7)
        return target
    }

    private fun exactDay(year: Int, month: Int, day: Int): LocalDate? =
        try { LocalDate.of(year, month, day) } catch (error: Exception) { null }

    /** 12月5日: this year's, or next year's once it has passed. */
    private fun monthDay(month: Int, day: Int): LocalDate? {
        val date = exactDay(today.year, month, day) ?: return null
        return if (date < today) exactDay(today.year + 1, month, day) else date
    }

    /** 3号: this month's, or next month's once it has passed. */
    private fun dayOfMonth(day: Int): LocalDate? {
        val date = exactDay(today.year, today.monthValue, day) ?: return null
        if (date >= today) return date
        return if (today.monthValue == 12) exactDay(today.year + 1, 1, day) else exactDay(today.year, today.monthValue + 1, day)
    }

    // Time

    fun readTime() {
        if (QuickEntryToken.Kind.Time in found) return
        var best: Pair<QuickEntryToken.Span, QuickEntryToken>? = null
        fun consider(span: QuickEntryToken.Span, token: QuickEntryToken) {
            val current = best
            if (current != null) {
                if (span.start > current.first.start) return
                if (span.start == current.first.start && span.end - span.start <= current.first.end - current.first.start) return
            }
            best = span to token
        }
        val match = QuickEntryParser.timePattern.matcher(text)
        while (match.find()) {
            val span = match.span()
            if (!isFree(span)) continue
            val word = match.named("q")
            val time = resolveClock(match, QuickEntryParser.Part.of(word), hasPart = word != null)
            if (time != null) {
                consider(span, QuickEntryToken(QuickEntryToken.Kind.Time, clockText(time.first, time.second), span,
                    scheduleKind = "exactTime", hour = time.first, minute = time.second))
                break
            }
            // 晚上12点 is not read as a time; 晚上 on its own still is.
            val partSpan = match.namedSpan("q")
            val token = if (partSpan != null && word != null) periodToken(word, partSpan) else null
            if (partSpan != null && token != null) {
                consider(partSpan, token)
                break
            }
        }
        val period = QuickEntryParser.periodPattern.matcher(text)
        while (period.find()) {
            val span = period.span()
            if (!isFree(span)) continue
            periodToken(period.group(), span)?.let { consider(span, it) }
            break
        }
        val chosen = best ?: return
        claim(chosen.first)
        found.add(QuickEntryToken.Kind.Time)
        tokens.add(chosen.second)
    }

    private fun periodToken(word: String, span: QuickEntryToken.Span): QuickEntryToken? = when (QuickEntryParser.Part.of(word)) {
        QuickEntryParser.Part.Morning -> QuickEntryToken(QuickEntryToken.Kind.Time, "上午", span, scheduleKind = "morning")
        QuickEntryParser.Part.Noon -> QuickEntryToken(QuickEntryToken.Kind.Time, "12:00", span, scheduleKind = "exactTime", hour = 12)
        QuickEntryParser.Part.Afternoon -> QuickEntryToken(QuickEntryToken.Kind.Time, "下午", span, scheduleKind = "afternoon")
        QuickEntryParser.Part.Evening -> QuickEntryToken(QuickEntryToken.Kind.Time, "晚上", span, scheduleKind = "evening")
        null -> null
    }

    /**
     * The hour and minute of a clock match, after the part of day before it;
     * null when the words are not a time (提三点意见, 多读一点书).
     */
    private fun resolveClock(match: Matcher, part: QuickEntryParser.Part?, hasPart: Boolean = true): Pair<Int, Int>? {
        var hour: Int
        var minute = 0
        val colonHour = match.named("ch")?.toIntOrNull()
        val colonMinute = match.named("cm")?.toIntOrNull()
        if (colonHour != null && colonMinute != null) {
            hour = colonHour
            minute = colonMinute
        } else {
            val chinese = match.named("zh")
            val hourText = match.named("dh") ?: chinese ?: return null
            hour = QuickEntryParser.number(hourText) ?: return null
            val mark = match.named("mm")
            minute = when (mark) {
                null -> 0
                "半" -> 30
                "一刻" -> 15
                "三刻" -> 45
                else -> QuickEntryParser.number(match.named("dm") ?: match.named("zm") ?: return null) ?: return null
            }
            val hourSpan = match.namedSpan(if (match.named("dh") != null) "dh" else "zh") ?: return null
            val before: Char? = if (hourSpan.start > 0) text[hourSpan.start - 1] else null
            val after = text.substring(match.end())
            val pointEnd = hourSpan.end + 1
            val afterPoint = if (pointEnd <= text.length) text.substring(pointEnd) else ""
            if (part == null || !hasPart) {
                // 第三点 is a numbered point, not three o'clock.
                if (before == '第') return null
                if (chinese == "一") {
                    if (before != null && before in QuickEntryParser.littleBefore) return null
                    if (afterPoint.startsWith("点")) return null
                    // A bare 一点 is “a little” far more often than one o'clock.
                    if (mark == null && match.named("zhong") == null && match.named("suffix") == null) return null
                }
            }
            if (QuickEntryParser.pointNouns.any { after.startsWith(it) || afterPoint.startsWith(it) }) return null
        }
        when (if (hasPart) part else null) {
            QuickEntryParser.Part.Morning -> Unit
            QuickEntryParser.Part.Noon -> if (hour in 1..2) hour += 12
            QuickEntryParser.Part.Afternoon -> if (hour in 1..11) hour += 12
            QuickEntryParser.Part.Evening -> {
                if (hour == 12) return null
                if (hour in 1..11) hour += 12
            }
            null -> if (hour in 1..6) hour += 12
        }
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour to minute
    }

    // Duration

    fun readDuration() {
        if (QuickEntryToken.Kind.Duration in found) return
        val match = QuickEntryParser.durationPattern.matcher(text)
        while (match.find()) {
            val span = match.span()
            if (!isFree(span)) continue
            val halfHours = match.named("hhd")?.toDoubleOrNull() ?: match.named("hhz")?.let(QuickEntryParser::number)?.toDouble()
            val hours = match.named("hd")?.toDoubleOrNull() ?: match.named("hz")?.let(QuickEntryParser::number)?.toDouble()
            val plain = (match.named("md") ?: match.named("mz"))?.let(QuickEntryParser::number)
            val total: Double? = when {
                halfHours != null -> halfHours * 60 + 30
                hours != null -> {
                    val extra = (match.named("hmd") ?: match.named("hmz"))?.let(QuickEntryParser::number) ?: 0
                    hours * 60 + extra
                }
                match.named("half") != null -> 30.0
                match.named("quarter") != null -> 15.0
                plain != null -> plain.toDouble()
                else -> null
            }
            if (total == null || !total.isFinite()) continue
            // Swift's rounded(): halves go away from zero.
            val minutes = Math.round(total).toInt()
            if (minutes !in 1..1_440) continue
            claim(span)
            found.add(QuickEntryToken.Kind.Duration)
            tokens.add(QuickEntryToken(QuickEntryToken.Kind.Duration, AIDrafting.durationText(minutes), span, minutes = minutes))
            return
        }
    }

    // Type

    fun readCategory(categories: List<String>) {
        var best: Triple<Int, Int, String>? = null
        for (category in categories) {
            val name = AIDrafting.categoryDisplayName(category)
            if (name.isEmpty()) continue
            var from = 0
            while (from < text.length) {
                val start = text.indexOf(name, from)
                if (start < 0) break
                val span = QuickEntryToken.Span(start, start + name.length)
                if (isFree(span)) {
                    val current = best
                    if (current == null || start < current.first || (start == current.first && name.length > current.second)) {
                        best = Triple(start, name.length, category)
                    }
                    break
                }
                from = start + 1
            }
        }
        val chosen = best ?: return
        found.add(QuickEntryToken.Kind.Category)
        tokens.add(QuickEntryToken(QuickEntryToken.Kind.Category, AIDrafting.categoryDisplayName(chosen.third), null, category = chosen.third))
    }
}
