package org.qingyingqx.smsmonitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * 定时检查任务的纯逻辑单测。
 *
 * 这两块逻辑是功能稳定性的关键：
 *  - 下一次触发时刻的计算（决定"到点"是否准时）
 *  - 号码归一化匹配（决定"收到没收到"判断是否准确）
 * 两者都不依赖 Android 运行时，可以在 JVM 上直接验证。
 */
class CheckTaskLogicTest {

    /** 构造一个"今天/明天 hour:minute:second"的时间戳，避免测试依赖固定时区 */
    private fun at(hour: Int, minute: Int, second: Int = 0, dayOffset: Int = 0): Long {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, dayOffset)
        cal.set(Calendar.HOUR_OF_DAY, hour)
        cal.set(Calendar.MINUTE, minute)
        cal.set(Calendar.SECOND, second)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    // ─────────────── 下一次触发时刻 ───────────────

    @Test
    fun beforeTodayTime_schedulesToday() {
        val now = at(7, 0)
        assertEquals(at(8, 0), TaskAlarmScheduler.nextTriggerAt(8, 0, now))
    }

    @Test
    fun afterTodayTime_schedulesTomorrow() {
        val now = at(9, 0)
        assertEquals(at(8, 0, dayOffset = 1), TaskAlarmScheduler.nextTriggerAt(8, 0, now))
    }

    @Test
    fun exactlyAtTargetTime_schedulesTomorrow() {
        val now = at(8, 0)
        assertEquals(at(8, 0, dayOffset = 1), TaskAlarmScheduler.nextTriggerAt(8, 0, now))
    }

    @Test
    fun oneMillisecondAfterTarget_schedulesTomorrow() {
        val now = at(8, 0) + 1
        assertEquals(at(8, 0, dayOffset = 1), TaskAlarmScheduler.nextTriggerAt(8, 0, now))
    }

    @Test
    fun nextTriggerIsAlwaysInTheFuture() {
        for (hour in 0..23) {
            val now = at(hour, 30)
            assertTrue(
                "hour=$hour 时应严格晚于当前时刻",
                TaskAlarmScheduler.nextTriggerAt(hour, 0, now) > now
            )
        }
    }

    // ─────────────── 最近截止时刻（补检锚点） ───────────────

    @Test
    fun beforeTodayTime_mostRecentDeadlineIsYesterday() {
        val now = at(7, 0)
        assertEquals(at(8, 0, dayOffset = -1), TaskAlarmScheduler.mostRecentDeadline(8, 0, now))
    }

    @Test
    fun afterTodayTime_mostRecentDeadlineIsToday() {
        val now = at(9, 0)
        assertEquals(at(8, 0), TaskAlarmScheduler.mostRecentDeadline(8, 0, now))
    }

    @Test
    fun exactlyAtTarget_mostRecentDeadlineIsToday() {
        val now = at(8, 0)
        assertEquals(at(8, 0), TaskAlarmScheduler.mostRecentDeadline(8, 0, now))
    }

    @Test
    fun mostRecentDeadlineIsNeverInTheFuture() {
        for (hour in 0..23) {
            val now = at(hour, 30)
            assertTrue(
                "hour=$hour 时截止时刻不应晚于当前",
                TaskAlarmScheduler.mostRecentDeadline(hour, 0, now) <= now
            )
        }
    }

    @Test
    fun nextTriggerIsExactlyOneDayAfterMostRecentDeadline() {
        // 补检锚点与下次排程必须严格对齐，否则会出现"跳过一次"或"重复一次"
        val now = at(9, 0)
        assertEquals(
            at(8, 0, dayOffset = 1),
            TaskAlarmScheduler.nextTriggerAt(8, 0, now)
        )
        assertEquals(at(8, 0), TaskAlarmScheduler.mostRecentDeadline(8, 0, now))
    }

    // ─────────────── 号码匹配 ───────────────

    @Test
    fun exactNumberMatches() {
        assertTrue(SmsLookup.addressMatches("13800138000", "13800138000"))
    }

    @Test
    fun countryCodePrefixMatches() {
        assertTrue(SmsLookup.addressMatches("+8613800138000", "13800138000"))
    }

    @Test
    fun trunkZeroPrefixMatches() {
        assertTrue(SmsLookup.addressMatches("013800138000", "13800138000"))
    }

    @Test
    fun formattedWithSpacesAndDashesMatches() {
        assertTrue(SmsLookup.addressMatches("+86 138-0013-8000", "13800138000"))
    }

    @Test
    fun shortServiceNumberMatches() {
        assertTrue(SmsLookup.addressMatches("10086", "10086"))
    }

    @Test
    fun differentNumbersDoNotMatch() {
        assertFalse(SmsLookup.addressMatches("13800138000", "13900139000"))
    }

    @Test
    fun suffixIsNotTreatedAsSameNumber() {
        // 不做模糊后缀匹配，否则 12345 会被 55512345 误判为同一号码
        assertFalse(SmsLookup.addressMatches("55512345", "12345"))
    }

    @Test
    fun blankNeverMatches() {
        assertFalse(SmsLookup.addressMatches("", "13800138000"))
        assertFalse(SmsLookup.addressMatches("13800138000", ""))
        assertFalse(SmsLookup.addressMatches(null, "13800138000"))
        assertFalse(SmsLookup.addressMatches("   ", "13800138000"))
    }

    @Test
    fun nonNumericSenderFallsBackToExactMatch() {
        assertTrue(SmsLookup.addressMatches("Bank-Alert", "bank-alert"))
        assertFalse(SmsLookup.addressMatches("Bank-Alert", "Other-Alert"))
    }

    // ─────────────── 配置校验 ───────────────

    @Test
    fun taskOnlyRequiresKeyword() {
        // 号码可选：留空表示"任意号码"
        assertTrue(CheckTask(sender = "", keyword = "平安").configComplete)
        assertTrue(CheckTask(sender = "13800138000", keyword = "平安").configComplete)
        assertFalse(CheckTask(sender = "13800138000", keyword = "").configComplete)
    }

    @Test
    fun senderTextFallsBackToAnyNumber() {
        assertEquals("任意号码", CheckTask(sender = "").senderText)
        assertEquals("任意号码", CheckTask(sender = "   ").senderText)
        assertEquals("13800138000", CheckTask(sender = "13800138000").senderText)
    }

    @Test
    fun timeTextIsZeroPadded() {
        assertEquals("08:05", CheckTask(hour = 8, minute = 5).timeText)
        assertEquals("23:59", CheckTask(hour = 23, minute = 59).timeText)
    }

    // ─────────────── 日志条目语义 ───────────────

    @Test
    fun smsEntriesCountTowardsMatched() {
        val hit = AlarmLogEntry(0, "平安", "", TriggerChannel.BROADCAST, AlarmOutcome.RINGING)
        val miss = AlarmLogEntry(0, "", "", TriggerChannel.CONTENT_OBSERVER, AlarmOutcome.NOT_MATCHED)
        assertTrue(hit.matched)
        assertFalse(miss.matched)
        assertFalse(hit.isTaskCheck)
    }

    @Test
    fun taskEntriesAreNotCountedAsKeywordHits() {
        val found = AlarmLogEntry(0, "平安", "", TriggerChannel.TASK_CHECK, AlarmOutcome.TASK_RECEIVED, 2)
        val missing = AlarmLogEntry(0, "平安", "", TriggerChannel.TASK_CHECK, AlarmOutcome.TASK_MISSING, 0)
        assertTrue(found.isTaskCheck)
        assertFalse("定时检查不应计入短信命中", found.matched)
        assertFalse(missing.matched)
        assertEquals(2, found.hits)
        assertEquals(0, missing.hits)
    }

    @Test
    fun everyChannelAndOutcomeHasALabel() {
        // 新增枚举值却忘记配文案时，弹窗会出现空白徽标
        TriggerChannel.values().forEach { assertTrue(it.name, it.label.isNotBlank()) }
        AlarmOutcome.values().forEach { assertTrue(it.name, it.label.isNotBlank()) }
    }
}
