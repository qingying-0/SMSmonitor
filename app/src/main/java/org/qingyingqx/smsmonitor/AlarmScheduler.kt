package org.qingyingqx.smsmonitor

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 精准闹钟调度器
 *
 * 存在的意义：Android 12+ 禁止后台应用直接启动前台服务，
 * 从短信广播里直接 startForegroundService() 会抛
 * ForegroundServiceStartNotAllowedException。
 *
 * 而通过 AlarmManager 精准闹钟投递 PendingIntent 时，系统会授予本应用
 * 一个 FOREGROUND_SERVICE_ALLOWED 类型的临时白名单，此时启动前台服务
 * 是被明确允许的 —— 这正是闹钟类应用的合法路径。
 *
 * 链路：SmsReceiver → AlarmScheduler → AlarmReceiver → AlarmService
 */
object AlarmScheduler {

    private const val REQUEST_CODE = 3001

    /**
     * 中转延迟。白名单是在闹钟投递时授予的，与延迟长短无关，
     * 因此这里只需留出极短的调度余量，不必额外等待。
     */
    private const val DELAY_MS = 50L

    /**
     * 当前是否具备精准闹钟能力。
     * Android 12 以下恒为 true。
     */
    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            ?: return false
        return try {
            manager.canScheduleExactAlarms()
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * 通过精准闹钟中转触发闹钟。
     *
     * @return true 表示已成功排入闹钟队列；false 表示不可用，调用方应直接降级启动
     */
    fun schedule(
        context: Context,
        ringtoneUri: String?,
        volume: Float,
        keyword: String?,
        reason: AlarmReason
    ): Boolean {
        if (!canScheduleExact(context)) return false

        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            ?: return false

        return try {
            val relayIntent = Intent(context, AlarmReceiver::class.java).apply {
                putExtra(AlarmService.EXTRA_RINGTONE_URI, ringtoneUri)
                putExtra(AlarmService.EXTRA_VOLUME, volume)
                putExtra(AlarmService.EXTRA_KEYWORD, keyword)
                putExtra(AlarmService.EXTRA_REASON, reason.name)
            }

            val relayPendingIntent = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                relayIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            // 用户点击状态栏闹钟图标时打开本应用
            val showIntent = PendingIntent.getActivity(
                context,
                REQUEST_CODE,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val triggerAt = System.currentTimeMillis() + DELAY_MS
            manager.setAlarmClock(
                AlarmManager.AlarmClockInfo(triggerAt, showIntent),
                relayPendingIntent
            )
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * 取消尚未触发的中转闹钟（例如用户手动停用监控）
     */
    fun cancel(context: Context) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        try {
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                Intent(context, AlarmReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE
            )
            if (pendingIntent != null) {
                manager.cancel(pendingIntent)
                pendingIntent.cancel()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
