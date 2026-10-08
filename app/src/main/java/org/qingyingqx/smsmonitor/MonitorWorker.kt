package org.qingyingqx.smsmonitor

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

/**
 * 15 分钟周期的兜底任务
 *
 * 两件事：
 *   1. 监控开关为开但前台服务已死 → 重新拉起（保住 ContentObserver 通道）
 *   2. 定时检查任务到点却没被检查过 → 补检
 *
 * 第 2 条是关键：AlarmManager 的闹钟可能被 OEM ROM 静默掐掉，
 * 而 WorkManager 是另一套调度子系统（JobScheduler），自带跨重启持久化。
 * 两条链路同时失效的概率远低于单条。
 *
 * Worker 默认运行在主进程，因此 [SmsMonitorService.isRunning] 静态标志是准确的。
 */
class MonitorWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        val prefs = ctx.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)

        // 1) 守护监控服务
        if (prefs.getBoolean(Prefs.KEY_MONITORING_ENABLED, false)) {
            if (!SmsMonitorService.isRunning) {
                SmsMonitorService.start(ctx)
            }
        }

        // 2) 定时检查任务补检（幂等，重复调用不会重复响铃）
        try {
            CheckRunner.runIfDue(ctx, "15分钟巡检")
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return Result.success()
    }
}
