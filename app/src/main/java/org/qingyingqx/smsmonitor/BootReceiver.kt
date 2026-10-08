package org.qingyingqx.smsmonitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机 / 时间变化 接收器
 *
 * 两类职责：
 *  1. 重启后恢复常驻监控服务（ContentObserver 通道随进程消失）
 *  2. 重排定时检查任务 —— AlarmManager 的闹钟不跨重启，
 *     且时区或系统时间变化后必须重算，否则"每天 08:00"会漂移
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return

        val isTimeChange = action == Intent.ACTION_TIME_CHANGED ||
                action == Intent.ACTION_TIMEZONE_CHANGED

        // 时间/时区变化：只需重排任务，不必动监控服务
        if (isTimeChange) {
            rescheduleTask(context)
            return
        }

        val isBoot = action == Intent.ACTION_BOOT_COMPLETED ||
                action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
                action == "android.intent.action.QUICKBOOT_POWERON" ||
                action == Intent.ACTION_MY_PACKAGE_REPLACED

        if (!isBoot) return

        // 恢复双通道中的 ContentObserver 载体
        val prefs = context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(Prefs.KEY_MONITORING_ENABLED, false)) {
            SmsMonitorService.start(context)
        }

        // AlarmManager 的闹钟不跨重启，必须重排定时检查任务
        rescheduleTask(context)

        // 按当前配置决定是否需要周期守护（监控或定时检查任一启用）
        Watchdog.sync(context)
    }

    private fun rescheduleTask(context: Context) {
        val task = CheckTaskStore.load(context)
        if (task.enabled && task.configComplete) {
            TaskAlarmScheduler.scheduleNext(context, task)
        }
    }
}
