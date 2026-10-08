package org.qingyingqx.smsmonitor

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 记录来源
 * 标明这条记录是"哪条通道 / 哪个功能"产生的。
 */
enum class TriggerChannel(val label: String) {
    /** 静态或动态注册的 SMS_RECEIVED 广播 */
    BROADCAST("短信广播"),

    /** content://sms 的 ContentObserver */
    CONTENT_OBSERVER("ContentObserver"),

    /** 定时检查任务：到点回看窗口内是否收到符合条件的短信 */
    TASK_CHECK("定时检查")
}

/**
 * 本条记录的处理结果
 */
enum class AlarmOutcome(val label: String) {
    /** 短信未命中任何关键字 */
    NOT_MATCHED("未命中"),

    /** 短信命中且已触发闹钟 */
    RINGING("已响铃"),

    /** 短信命中但被冷却窗口抑制，未重复响铃 */
    SUPPRESSED("冷却抑制"),

    /** 短信命中，但前台服务启动失败，走了兜底播放 */
    DEGRADED("降级播放"),

    /** 定时检查：窗口内收到了符合条件的短信 */
    TASK_RECEIVED("已收到"),

    /** 定时检查：窗口内没有符合条件的短信，已响铃 */
    TASK_MISSING("未收到"),

    /** 定时检查：无法读取短信，按未收到处理 */
    TASK_UNVERIFIABLE("无法读取")
}

/**
 * 一条监控记录
 */
data class AlarmLogEntry(
    val timestamp: Long,
    val keyword: String,
    val body: String,
    val channel: TriggerChannel,
    val outcome: AlarmOutcome,
    /** 仅定时检查条目有意义：窗口内命中的短信条数 */
    val hits: Int = 0
) {
    /** 是否为定时检查条目 */
    val isTaskCheck: Boolean get() = channel == TriggerChannel.TASK_CHECK

    /** 短信条目且命中关键字（定时检查条目不计入"命中"统计） */
    val matched: Boolean
        get() = !isTaskCheck && outcome != AlarmOutcome.NOT_MATCHED
}

/**
 * 短信监控日志
 *
 * 同时记录两类事件：
 *  - 短信事件：所有监控到的短信（含未命中关键字的）
 *  - 定时检查事件：每次到点检查的结果
 *
 * 仅使用 SharedPreferences + org.json 存储，不引入任何新依赖。
 * 所有数据留在本机，与应用的隐私承诺一致。
 */
object AlarmLog {

    /** 最多保留的记录条数，超出后丢弃最旧的 */
    private const val MAX_ENTRIES = 200

    /** 正文留存长度。弹窗中需要能看清整条短信，故比预览更长 */
    private const val BODY_MAX_LEN = 200

    private const val FIELD_TIME = "ts"
    private const val FIELD_KEYWORD = "kw"
    private const val FIELD_BODY = "body"
    private const val FIELD_CHANNEL = "ch"
    private const val FIELD_OUTCOME = "outcome"
    private const val FIELD_HITS = "hits"

    /** 兼容旧版本仅记录 started 布尔值的数据 */
    private const val FIELD_LEGACY_STARTED = "ok"
    private const val FIELD_LEGACY_MATCHED = "matched"

    private val displayFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

    private fun prefs(context: Context) =
        context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)

    /**
     * 追加一条记录（最新的排在最前）
     */
    fun append(context: Context, entry: AlarmLogEntry) {
        try {
            val list = read(context).toMutableList()
            list.add(0, entry)
            while (list.size > MAX_ENTRIES) {
                list.removeAt(list.size - 1)
            }
            write(context, list)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 读取全部记录（最新在前）
     */
    fun read(context: Context): List<AlarmLogEntry> {
        return try {
            val raw = prefs(context).getString(Prefs.KEY_ALARM_LOGS, null)
            if (raw.isNullOrBlank()) return emptyList()

            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    add(
                        AlarmLogEntry(
                            timestamp = obj.optLong(FIELD_TIME),
                            keyword = obj.optString(FIELD_KEYWORD),
                            body = obj.optString(FIELD_BODY),
                            channel = parseChannel(obj.optString(FIELD_CHANNEL)),
                            outcome = parseOutcome(obj),
                            hits = obj.optInt(FIELD_HITS, 0)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * 清空日志
     */
    fun clear(context: Context) {
        try {
            prefs(context).edit().remove(Prefs.KEY_ALARM_LOGS).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** 格式化为可读时间 */
    fun formatTime(timestamp: Long): String =
        displayFormat.format(Date(timestamp))

    /** 折叠换行并截断正文 */
    fun preview(body: String): String {
        val flat = body.replace('\n', ' ').replace('\r', ' ').trim()
        return if (flat.length <= BODY_MAX_LEN) {
            flat
        } else {
            flat.take(BODY_MAX_LEN) + "…"
        }
    }

    private fun parseChannel(name: String): TriggerChannel {
        return try {
            TriggerChannel.valueOf(name)
        } catch (e: Exception) {
            TriggerChannel.BROADCAST
        }
    }

    private fun parseOutcome(obj: JSONObject): AlarmOutcome {
        val raw = obj.optString(FIELD_OUTCOME)
        if (raw.isNotEmpty()) {
            return try {
                AlarmOutcome.valueOf(raw)
            } catch (e: Exception) {
                AlarmOutcome.NOT_MATCHED
            }
        }

        // 旧数据兼容：只有 matched / started 两个字段
        val legacyMatched = if (obj.has(FIELD_LEGACY_MATCHED)) {
            obj.optBoolean(FIELD_LEGACY_MATCHED)
        } else {
            obj.optString(FIELD_KEYWORD).isNotEmpty()
        }
        if (!legacyMatched) return AlarmOutcome.NOT_MATCHED

        return if (obj.optBoolean(FIELD_LEGACY_STARTED, true)) {
            AlarmOutcome.RINGING
        } else {
            AlarmOutcome.DEGRADED
        }
    }

    private fun write(context: Context, list: List<AlarmLogEntry>) {
        val array = JSONArray()
        list.forEach { entry ->
            array.put(
                JSONObject().apply {
                    put(FIELD_TIME, entry.timestamp)
                    put(FIELD_KEYWORD, entry.keyword)
                    put(FIELD_BODY, entry.body)
                    put(FIELD_CHANNEL, entry.channel.name)
                    put(FIELD_OUTCOME, entry.outcome.name)
                    put(FIELD_HITS, entry.hits)
                }
            )
        }
        prefs(context).edit().putString(Prefs.KEY_ALARM_LOGS, array.toString()).apply()
    }
}
