package org.qingyingqx.smsmonitor

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.os.Build
import android.provider.Telephony
import androidx.core.content.ContextCompat
import java.util.Calendar

/**
 * 定时检查结果
 */
enum class CheckOutcome {
    /** 窗口内找到了符合条件的短信 */
    FOUND,

    /** 窗口内没有符合条件的短信 */
    NOT_FOUND,

    /** 无法读取短信（权限缺失或查询异常），按"未收到"处理（宁可误报不可漏报） */
    UNVERIFIABLE
}

/**
 * 定时检查任务配置
 *
 * 语义：每天 [hour]:[minute] 截止，回看前 [lookbackMinutes] 分钟；
 * 窗口内若没有正文含 [keyword] 的短信（[sender] 非空时还要求来自该号码），
 * 则触发闹钟。
 */
data class CheckTask(
    val enabled: Boolean = false,
    val hour: Int = 8,
    val minute: Int = 0,
    val lookbackMinutes: Int = 30,
    val sender: String = "",
    val keyword: String = ""
) {
    /** 只有关键字是必填的；号码留空表示"任意号码" */
    val configComplete: Boolean get() = keyword.isNotBlank()

    val timeText: String get() = "%02d:%02d".format(hour, minute)

    /** 号码展示文案 */
    val senderText: String get() = if (sender.isBlank()) "任意号码" else sender
}

/**
 * 上次检查的留痕，用于主页展示与排查"到底有没有触发过"
 */
data class CheckResult(
    val checkedAt: Long,
    val outcome: CheckOutcome,
    val hits: Int
)

/**
 * 任务配置与结果的持久化
 */
object CheckTaskStore {

    private fun prefs(context: Context) =
        context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)

    fun load(context: Context): CheckTask {
        val p = prefs(context)
        return CheckTask(
            enabled = p.getBoolean(Prefs.KEY_TASK_ENABLED, false),
            hour = p.getInt(Prefs.KEY_TASK_HOUR, 8).coerceIn(0, 23),
            minute = p.getInt(Prefs.KEY_TASK_MINUTE, 0).coerceIn(0, 59),
            lookbackMinutes = p.getInt(Prefs.KEY_TASK_LOOKBACK, 30).coerceIn(1, 24 * 60),
            sender = p.getString(Prefs.KEY_TASK_SENDER, "") ?: "",
            keyword = p.getString(Prefs.KEY_TASK_KEYWORD, "") ?: ""
        )
    }

    fun save(context: Context, task: CheckTask) {
        prefs(context).edit()
            .putBoolean(Prefs.KEY_TASK_ENABLED, task.enabled)
            .putInt(Prefs.KEY_TASK_HOUR, task.hour)
            .putInt(Prefs.KEY_TASK_MINUTE, task.minute)
            .putInt(Prefs.KEY_TASK_LOOKBACK, task.lookbackMinutes)
            .putString(Prefs.KEY_TASK_SENDER, task.sender)
            .putString(Prefs.KEY_TASK_KEYWORD, task.keyword)
            .apply()
    }

    /** 已完成的最近截止时刻（毫秒），用于幂等判定 */
    fun lastCheckedDeadline(context: Context): Long =
        prefs(context).getLong(Prefs.KEY_TASK_LAST_DEADLINE, 0L)

    fun saveLastCheckedDeadline(context: Context, deadline: Long) {
        prefs(context).edit().putLong(Prefs.KEY_TASK_LAST_DEADLINE, deadline).apply()
    }

    /**
     * 把"当前已过去的那个截止时刻"直接标记为已处理。
     *
     * 用户启用任务或改了时间时调用：否则刚启用就会对今天早些时候
     * 那个早已错过的截止时刻补检，凭空响一次。
     */
    fun startFreshFromNow(context: Context, task: CheckTask) {
        val deadline = TaskAlarmScheduler.mostRecentDeadline(
            task.hour, task.minute, System.currentTimeMillis()
        )
        saveLastCheckedDeadline(context, deadline)
    }

    fun loadLastResult(context: Context): CheckResult? {
        val p = prefs(context)
        val at = p.getLong(Prefs.KEY_TASK_LAST_AT, 0L)
        if (at <= 0L) return null

        val outcome = try {
            CheckOutcome.valueOf(p.getString(Prefs.KEY_TASK_LAST_OUTCOME, "") ?: "")
        } catch (e: Exception) {
            CheckOutcome.UNVERIFIABLE
        }
        return CheckResult(at, outcome, p.getInt(Prefs.KEY_TASK_LAST_HITS, 0))
    }

    fun saveLastResult(context: Context, result: CheckResult) {
        prefs(context).edit()
            .putLong(Prefs.KEY_TASK_LAST_AT, result.checkedAt)
            .putString(Prefs.KEY_TASK_LAST_OUTCOME, result.outcome.name)
            .putInt(Prefs.KEY_TASK_LAST_HITS, result.hits)
            .apply()
    }
}

/**
 * 任务闹钟排程
 *
 * 排两道闹钟：
 *  1. 主闹钟 —— setAlarmClock，精准且免于 Doze，投递时附带前台服务启动白名单
 *  2. 备用闹钟 —— setExactAndAllowWhileIdle，晚 3 分钟，走另一个 AlarmManager API
 *
 * 两者用不同的 requestCode，避免互相覆盖。目的是对抗 OEM ROM
 * 对某一种闹钟 API 的差异化处理。
 */
object TaskAlarmScheduler {

    private const val REQUEST_CODE = 4001
    private const val REQUEST_CODE_BACKUP = 4002

    /** 备用闹钟的延迟：不追求准时，只求不漏 */
    private const val BACKUP_DELAY_MS = 3 * 60_000L

    private fun calendarAt(hour: Int, minute: Int, now: Long): Calendar {
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        cal.set(Calendar.HOUR_OF_DAY, hour)
        cal.set(Calendar.MINUTE, minute)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal
    }

    /**
     * 下一个触发时刻（严格晚于 [now]）。
     *
     * 用 Calendar 而非硬编码 24 小时毫秒数，让夏令时由系统历法处理。
     *
     * @param now 作为参数传入，便于单元测试
     */
    fun nextTriggerAt(hour: Int, minute: Int, now: Long): Long {
        val cal = calendarAt(hour, minute, now)
        if (cal.timeInMillis <= now) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }

    /**
     * 最近一个已经过去的截止时刻（小于等于 [now]）。
     *
     * 这是补检的核心：无论实际在什么时刻执行，检查窗口始终锚定在
     * 这个截止时刻上，因此同一天的多次触发天然幂等。
     */
    fun mostRecentDeadline(hour: Int, minute: Int, now: Long): Long {
        val cal = calendarAt(hour, minute, now)
        if (cal.timeInMillis > now) {
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        return cal.timeInMillis
    }

    /**
     * 排入下一次检查（主 + 备两道）。
     *
     * @return true 表示至少主闹钟已成功排入
     */
    fun scheduleNext(context: Context, task: CheckTask): Boolean {
        if (!task.enabled || !task.configComplete) {
            cancel(context)
            return false
        }
        if (!AlarmScheduler.canScheduleExact(context)) return false

        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            ?: return false

        val triggerAt = nextTriggerAt(task.hour, task.minute, System.currentTimeMillis())

        var primaryOk = false
        try {
            manager.setAlarmClock(
                AlarmManager.AlarmClockInfo(triggerAt, showIntent(context)),
                broadcastIntent(context, REQUEST_CODE)
            )
            primaryOk = true
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 备用：晚 3 分钟，另一个 API。失败不影响主闹钟。
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                manager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt + BACKUP_DELAY_MS,
                    broadcastIntent(context, REQUEST_CODE_BACKUP)
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return primaryOk
    }

    fun cancel(context: Context) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        for (code in intArrayOf(REQUEST_CODE, REQUEST_CODE_BACKUP)) {
            try {
                val pi = PendingIntent.getBroadcast(
                    context,
                    code,
                    Intent(context, TaskCheckReceiver::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE
                )
                if (pi != null) {
                    manager.cancel(pi)
                    pi.cancel()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun broadcastIntent(context: Context, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, TaskCheckReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    /** 用户点击状态栏闹钟图标时打开本应用 */
    private fun showIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            REQUEST_CODE,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
}

/**
 * 收件箱查询
 */
object SmsLookup {

    /**
     * 在 [sinceMs, untilMs] 窗口内查找正文含 [keyword] 的收件箱短信。
     * [sender] 为空表示不限号码。
     *
     * @return 判定结果与命中的条数
     */
    fun check(
        context: Context,
        sinceMs: Long,
        untilMs: Long,
        sender: String,
        keyword: String
    ): Pair<CheckOutcome, Int> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return CheckOutcome.UNVERIFIABLE to 0
        }
        if (keyword.isBlank()) return CheckOutcome.UNVERIFIABLE to 0

        val senderFilter = sender.trim()
        val restrictSender = senderFilter.isNotEmpty()

        var cursor: Cursor? = null
        return try {
            cursor = context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY),
                "${Telephony.Sms.DATE} >= ? AND ${Telephony.Sms.DATE} <= ?",
                arrayOf(sinceMs.toString(), untilMs.toString()),
                "${Telephony.Sms.DATE} DESC"
            )

            var hits = 0
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    val address = cursor.getString(0)
                    val body = cursor.getString(1) ?: ""
                    val senderOk = !restrictSender || addressMatches(address, senderFilter)
                    if (senderOk && body.contains(keyword)) {
                        hits++
                    }
                }
            }

            (if (hits > 0) CheckOutcome.FOUND else CheckOutcome.NOT_FOUND) to hits
        } catch (e: Exception) {
            e.printStackTrace()
            CheckOutcome.UNVERIFIABLE to 0
        } finally {
            try {
                cursor?.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * 号码匹配。
     *
     * 运营商下发的 address 与用户手输的号码经常不一致：
     * "+8613800138000" / "013800138000" / "13800138000" 是同一个号码。
     * 因此先各自归一化成若干候选形式，再看是否有交集。
     *
     * 不做"后缀包含"这类模糊匹配，避免把 12345 和 55512345 误判为同一号码。
     */
    fun addressMatches(stored: String?, wanted: String): Boolean {
        if (stored.isNullOrBlank() || wanted.isBlank()) return false

        val a = stored.filter { it.isDigit() }
        val b = wanted.filter { it.isDigit() }

        // 非数字号码（如 106 服务号里的字母短号）退化为忽略大小写的全等匹配
        if (a.isEmpty() || b.isEmpty()) {
            return stored.trim().equals(wanted.trim(), ignoreCase = true)
        }

        val va = numberVariants(a)
        return numberVariants(b).any { it in va }
    }

    /** 同一号码的若干等价写法 */
    private fun numberVariants(digits: String): Set<String> {
        val out = mutableSetOf(digits)

        var s = digits
        while (s.startsWith("0")) s = s.substring(1)
        if (s.isNotEmpty()) out += s

        // 中国区号 86：仅当剥离后长度仍合理时才加入，
        // 避免误伤本身就以 86 开头的号码
        if (s.startsWith("86") && s.length > 8) {
            out += s.substring(2)
        }

        return out
    }
}

/**
 * 检查执行器 —— 幂等 + 可补检
 *
 * 设计要点：判定"是否该检查"只依赖**最近一个截止时刻**，
 * 与"实际在什么时刻被调用"无关。因此下面四条互不相干的路径
 * 可以随意重复触发，同一天只会真正检查一次、只会响一次：
 *
 *   1. 主闹钟        setAlarmClock
 *   2. 备用闹钟      setExactAndAllowWhileIdle（+3 分钟，另一个 API）
 *   3. WorkManager  15 分钟周期扫描（另一套调度子系统，自带跨重启持久化）
 *   4. APP 启动补检  打开应用时立即补齐（这是"强行停止"之后唯一的恢复路径）
 *
 * 前三条都可能被系统/OEM 静默掐掉，只要还有一条活着，
 * 最迟 15 分钟内就会补上。
 */
object CheckRunner {

    /** 同进程内的并发保护：闹钟与 Worker 可能同时命中 */
    private val lock = Any()

    /**
     * 若最近一个截止时刻尚未检查过，则执行检查。
     *
     * @param source 调用来源，仅用于日志排查
     * @return true 表示本次真的执行了检查
     */
    fun runIfDue(context: Context, source: String): Boolean {
        val task = CheckTaskStore.load(context)
        if (!task.enabled || !task.configComplete) return false

        synchronized(lock) {
            val now = System.currentTimeMillis()
            val deadline = TaskAlarmScheduler.mostRecentDeadline(task.hour, task.minute, now)

            // 该截止时刻已处理过 → 幂等跳过
            if (CheckTaskStore.lastCheckedDeadline(context) >= deadline) return false

            val since = deadline - task.lookbackMinutes * 60_000L
            val (outcome, hits) = SmsLookup.check(context, since, deadline, task.sender, task.keyword)

            // 先落盘"已完成"，再响铃：宁可极少数情况下漏响，
            // 也不要四条路径同时响成一团
            CheckTaskStore.saveLastCheckedDeadline(context, deadline)
            CheckTaskStore.saveLastResult(context, CheckResult(now, outcome, hits))

            var degraded = false
            if (outcome != CheckOutcome.FOUND) {
                val prefs = context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
                val volume = prefs.getInt(Prefs.KEY_VOLUME, 80) / 100f
                val ringtoneUri = prefs.getString(Prefs.KEY_RINGTONE_URI, null)

                val started = AlarmService.start(
                    context = context,
                    ringtoneUri = ringtoneUri,
                    volume = volume,
                    keyword = task.keyword,
                    reason = if (outcome == CheckOutcome.UNVERIFIABLE) {
                        AlarmReason.TASK_UNVERIFIABLE
                    } else {
                        AlarmReason.TASK_MISSING
                    },
                    taskTimeText = task.timeText
                )
                degraded = !started
            }

            // 与短信事件记入同一份日志，便于统一排查
            // 来源一栏能直接看出是闹钟准点触发、还是某条兜底路径补上的
            AlarmLog.append(
                context,
                AlarmLogEntry(
                    timestamp = now,
                    keyword = task.keyword,
                    body = "截止 ${task.timeText} · 回看前 ${task.lookbackMinutes} 分钟 · 命中 $hits 条" +
                            " · 来源 $source" +
                            if (degraded) " · 降级播放" else "",
                    channel = TriggerChannel.TASK_CHECK,
                    outcome = when (outcome) {
                        CheckOutcome.FOUND -> AlarmOutcome.TASK_RECEIVED
                        CheckOutcome.NOT_FOUND -> AlarmOutcome.TASK_MISSING
                        CheckOutcome.UNVERIFIABLE -> AlarmOutcome.TASK_UNVERIFIABLE
                    },
                    hits = hits
                )
            )

            return true
        }
    }
}
