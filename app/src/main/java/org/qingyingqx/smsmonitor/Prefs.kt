package org.qingyingqx.smsmonitor

/**
 * SharedPreferences 常量定义
 */
object Prefs {
    const val NAME = "sms_monitor_prefs"
    const val KEY_KEYWORDS = "keywords"
    const val KEY_VOLUME = "volume"
    const val KEY_RINGTONE_URI = "ringtone_uri"
    const val KEY_RINGTONE_NAME = "ringtone_name"
    const val KEY_MONITORING_ENABLED = "monitoring_enabled"
    const val KEY_ALARM_LOGS = "alarm_logs"

    // ── 定时检查任务 ──
    const val KEY_TASK_ENABLED = "task_enabled"
    const val KEY_TASK_HOUR = "task_hour"
    const val KEY_TASK_MINUTE = "task_minute"
    const val KEY_TASK_LOOKBACK = "task_lookback"
    const val KEY_TASK_SENDER = "task_sender"
    const val KEY_TASK_KEYWORD = "task_keyword"

    /** 上次检查结果，供主页展示 */
    const val KEY_TASK_LAST_AT = "task_last_at"
    const val KEY_TASK_LAST_OUTCOME = "task_last_outcome"
    const val KEY_TASK_LAST_HITS = "task_last_hits"

    /** 已完成的最近截止时刻，用于幂等与补检判定 */
    const val KEY_TASK_LAST_DEADLINE = "task_last_deadline"
}
