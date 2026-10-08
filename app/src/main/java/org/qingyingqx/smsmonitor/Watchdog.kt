package org.qingyingqx.smsmonitor

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * 轻量守护调度器
 *
 * 用 WorkManager 的周期任务替代第三方保活库的双进程守护：
 * - 无第二进程，无无声音乐，无一像素 Activity
 * - 依赖 WorkManager 自身的持久化，重启后依然有效
 * - 15 分钟是 WorkManager 允许的最小周期
 */
object Watchdog {

    private const val WORK_NAME = "sms_monitor_watchdog"

    /**
     * 按当前配置同步守护任务。
     *
     * 只要"监控服务"或"定时检查任务"任一处于启用状态就需要周期扫描，
     * 因此不要再直接调用 [start] / [stop]，统一走这里避免状态不一致。
     */
    fun sync(context: Context) {
        val prefs = context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        val monitoring = prefs.getBoolean(Prefs.KEY_MONITORING_ENABLED, false)
        val taskEnabled = CheckTaskStore.load(context).enabled

        if (monitoring || taskEnabled) {
            start(context)
        } else {
            stop(context)
        }
    }

    /** 启动守护（幂等：已存在则保持原任务） */
    fun start(context: Context) {
        try {
            val request = PeriodicWorkRequestBuilder<MonitorWorker>(
                15, TimeUnit.MINUTES
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** 停止守护 */
    fun stop(context: Context) {
        try {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
