package org.qingyingqx.smsmonitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 定时检查任务的闹钟接收器
 *
 * 主闹钟（setAlarmClock）与备用闹钟（setExactAndAllowWhileIdle）都投递到这里。
 * 本身不判断"该不该响"，只负责：
 *   1. 交给幂等的 [CheckRunner] 去决定是否执行检查
 *   2. 排下一次
 *
 * 这样无论两道闹钟哪一道先到、哪一道被系统丢掉，
 * 判断逻辑都不会重复触发，也不会漏掉。
 */
class TaskCheckReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        try {
            CheckRunner.runIfDue(context, "闹钟")

            // 无论本次是否真的执行了检查，都排下一次
            val task = CheckTaskStore.load(context)
            if (task.enabled && task.configComplete) {
                TaskAlarmScheduler.scheduleNext(context, task)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try {
                pending.finish()
            } catch (ignore: Exception) {
                ignore.printStackTrace()
            }
        }
    }
}
