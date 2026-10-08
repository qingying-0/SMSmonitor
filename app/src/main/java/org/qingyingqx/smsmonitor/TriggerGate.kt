package org.qingyingqx.smsmonitor

/**
 * 触发闸门
 *
 * 职责拆成两件互不干扰的事：
 *
 * 1. [register] —— 判断"这条短信是不是已经被别的通道处理过了"。
 *    静态广播、动态广播、ContentObserver 三路都会看到同一条短信，
 *    三者通常在 1 秒内先后到达，因此用一个较短的窗口按正文去重。
 *
 * 2. [acquireAlarmSlot] —— 判断"此刻是否允许真正响铃"。
 *    只做冷却，不参与去重；这样日志可以如实记录每一条短信，
 *    而闹钟不会被连续重启。
 */
object TriggerGate {

    /**
     * 同一条短信被重复看到的判定窗口。
     * 跨通道重复通常发生在 1 秒内，5 秒足够覆盖且不易误合并相邻短信。
     */
    private const val DUPLICATE_WINDOW_MS = 5_000L

    /** 两次实际响铃之间的最小间隔，避免闹钟被反复重启 */
    private const val ALARM_COOLDOWN_MS = 1_000L

    private const val MAX_ENTRIES = 40

    private val recentBodies = LinkedHashMap<String, Long>()
    private var lastAlarmAt = 0L

    /**
     * 登记一条刚到达的短信。
     *
     * 说明：指纹只取正文，是为了规避广播通道的 timestampMillis /
     * originatingAddress 与 Provider 的 date / address 字段格式不一致
     * 导致去重失效。代价是同一窗口内两条正文完全相同的短信会被合并为一条。
     *
     * @return true 表示首次见到，应当记入日志；false 表示其他通道重复上报
     */
    @Synchronized
    fun register(body: String): Boolean {
        val now = System.currentTimeMillis()

        // 清理过期指纹
        val iterator = recentBodies.entries.iterator()
        while (iterator.hasNext()) {
            if (now - iterator.next().value > DUPLICATE_WINDOW_MS) iterator.remove()
        }

        val key = body.trim().hashCode().toString()
        if (recentBodies.containsKey(key)) return false

        recentBodies[key] = now

        while (recentBodies.size > MAX_ENTRIES) {
            val oldest = recentBodies.keys.firstOrNull() ?: break
            recentBodies.remove(oldest)
        }

        return true
    }

    /**
     * 申请一次响铃许可。
     *
     * @return true 表示已过冷却期，可以真正触发闹钟
     */
    @Synchronized
    fun acquireAlarmSlot(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastAlarmAt < ALARM_COOLDOWN_MS) return false
        lastAlarmAt = now
        return true
    }
}
