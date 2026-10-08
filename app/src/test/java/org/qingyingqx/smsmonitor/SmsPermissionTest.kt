package org.qingyingqx.smsmonitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 短信权限三态推导的纯逻辑单测。
 *
 * `RECEIVE_SMS` 与 `READ_SMS` 是两个独立权限，可以被单独授予。其中
 * "只有接收权限"会产生本应用最难排查的一种状态：界面看起来一切正常、
 * 广播通道也照常工作，但唯一不依赖系统广播的 ContentObserver 兜底通道
 * 已经静默失效——而这恰恰是它存在的理由。
 *
 * 因此这里把四种授予组合全部固定下来，防止以后有人把它简化回 Boolean。
 */
class SmsPermissionTest {

    @Test
    fun bothGranted_isFull() {
        assertEquals(
            SmsPermission.FULL,
            resolveSmsPermission(receiveGranted = true, readGranted = true)
        )
    }

    @Test
    fun onlyReceive_isDegraded_notReady() {
        // 关键用例：只有接收权限时必须报告为"降级"，
        // 不能显示成"已就绪"，否则兜底通道失效用户毫无察觉
        assertEquals(
            SmsPermission.RECEIVE_ONLY,
            resolveSmsPermission(receiveGranted = true, readGranted = false)
        )
    }

    @Test
    fun onlyRead_isNotUsable() {
        // 只有读取权限、没有接收权限：广播通道不可用，同样无法工作
        assertEquals(
            SmsPermission.NONE,
            resolveSmsPermission(receiveGranted = false, readGranted = true)
        )
    }

    @Test
    fun noneGranted_isNone() {
        assertEquals(
            SmsPermission.NONE,
            resolveSmsPermission(receiveGranted = false, readGranted = false)
        )
    }

    @Test
    fun receiveOnlyIsNotConfusedWithTheOtherStates() {
        // 三态必须互不相同：界面按"是否为 FULL"决定是否显示权限按钮，
        // 按"是否为 RECEIVE_ONLY"决定是否提示降级
        val receiveOnly = resolveSmsPermission(receiveGranted = true, readGranted = false)
        assertEquals(3, SmsPermission.values().size)
        assertEquals(3, SmsPermission.values().toSet().size)
        assertNotEquals(SmsPermission.FULL, receiveOnly)
        assertNotEquals(SmsPermission.NONE, receiveOnly)
    }
}
