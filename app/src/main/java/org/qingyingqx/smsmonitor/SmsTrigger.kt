package org.qingyingqx.smsmonitor

import android.content.Context

/**
 * 短信监控处理
 *
 * 广播通道（SmsReceiver）与观察者通道（SmsMonitorService）共用此逻辑，
 * 保证两条通道行为完全一致。
 *
 * 处理流程：
 * 1. [TriggerGate.register] 去重 —— 同一条短信被多通道看到只处理一次
 * 2. 关键字匹配
 * 3. 命中则申请响铃许可并触发闹钟
 * 4. **所有**监控到的短信都写入日志（含未命中的）
 */
object SmsTrigger {

    /**
     * 处理一条监控到的短信。
     *
     * @param channel 本次调用的来源通道，用于日志区分双保险哪一路生效
     * @return true 表示命中关键字
     */
    fun handle(context: Context, body: String, channel: TriggerChannel): Boolean {
        if (body.isBlank()) return false

        val prefs = context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)

        // 监控总开关
        if (!prefs.getBoolean(Prefs.KEY_MONITORING_ENABLED, false)) return false

        // 多通道去重：不是首次见到就直接返回，既不记日志也不响铃
        if (!TriggerGate.register(body)) return false

        val keywords = prefs.getStringSet(Prefs.KEY_KEYWORDS, emptySet()) ?: emptySet()
        val matched = keywords.firstOrNull { it.isNotBlank() && body.contains(it) }

        val outcome = when {
            matched == null -> AlarmOutcome.NOT_MATCHED

            // 命中但处于冷却期，不重复响铃（仍记日志）
            !TriggerGate.acquireAlarmSlot() -> AlarmOutcome.SUPPRESSED

            else -> {
                val volume = prefs.getInt(Prefs.KEY_VOLUME, 80) / 100f
                val ringtoneUri = prefs.getString(Prefs.KEY_RINGTONE_URI, null)

                // 优先通过精准闹钟中转：系统会授予前台服务启动白名单，
                // 规避 Android 12+ 后台启动前台服务的限制。
                val scheduled = AlarmScheduler.schedule(
                    context = context,
                    ringtoneUri = ringtoneUri,
                    volume = volume,
                    keyword = matched,
                    reason = AlarmReason.SMS_KEYWORD
                )

                // 没有精准闹钟能力时降级为直接启动
                val started = if (scheduled) {
                    true
                } else {
                    AlarmService.start(context, ringtoneUri, volume, matched)
                }

                if (started) AlarmOutcome.RINGING else AlarmOutcome.DEGRADED
            }
        }

        // 记录日志：所有监控到的短信都留痕
        AlarmLog.append(
            context,
            AlarmLogEntry(
                timestamp = System.currentTimeMillis(),
                keyword = matched ?: "",
                body = AlarmLog.preview(body),
                channel = channel,
                outcome = outcome
            )
        )

        // 命中后顺带把监控服务拉起来，避免下次只剩单通道
        if (matched != null) {
            SmsMonitorService.start(context)
        }

        return matched != null
    }
}
