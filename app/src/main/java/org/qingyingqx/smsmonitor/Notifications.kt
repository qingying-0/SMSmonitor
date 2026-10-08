package org.qingyingqx.smsmonitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.toBitmap

/**
 * 应用图标（与桌面图标同一张图）在通知中的用法：
 *
 *  - setLargeIcon：普通彩色位图，通知正文中原样显示应用图标原图。
 *    这是"通知里显示应用图标"的正确途径。
 *  - setSmallIcon：系统只保留 Alpha 通道并强制单色，且实际显示尺寸仅 24dp。
 *    实测把原图做成剪影后，在这个尺寸下完全糊成一团无法辨认，
 *    因此状态栏改用可辨识的「对话气泡 + 铃铛」单色符号。
 */
private fun appIconLarge(context: Context) =
    androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_notification_large)!!
        .toBitmap()

/**
 * 通知渠道与通知构建集中管理
 */
object Notifications {

    const val CHANNEL_MONITOR = "monitor_channel"
    const val CHANNEL_ALARM = "alarm_channel"

    const val MONITOR_NOTIF_ID = 1000
    const val ALARM_NOTIF_ID = 1001

    private const val REQ_OPEN_APP = 2001
    private const val REQ_DISMISS = 2002

    /**
     * 通知渠道只需创建一次。
     * createNotificationChannel 每次都是一次 binder IPC，两条渠道就是两次；
     * 在闹钟触发路径上属于纯浪费，故用进程内短路标志。
     */
    @Volatile
    private var channelsReady = false

    /**
     * 创建通知渠道（幂等，创建成功后本进程内不再重复调用）
     */
    fun ensureChannels(context: Context) {
        if (channelsReady) return

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            channelsReady = true
            return
        }

        synchronized(this) {
            if (channelsReady) return

            val manager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // 监控常驻通知：低优先级、无声、不打扰
            val monitorChannel = NotificationChannel(
                CHANNEL_MONITOR,
                "短信监控服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "短信关键字监控常驻通知"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            manager.createNotificationChannel(monitorChannel)

            // 闹钟通知：高优先级
            val alarmChannel = NotificationChannel(
                CHANNEL_ALARM,
                "关键字闹钟",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "短信命中关键字时的闹钟提醒"
                setShowBadge(true)
            }
            manager.createNotificationChannel(alarmChannel)

            channelsReady = true
        }
    }

    /**
     * 监控服务常驻通知
     */
    fun buildMonitorNotification(context: Context): Notification {
        ensureChannels(context)

        val openApp = PendingIntent.getActivity(
            context,
            REQ_OPEN_APP,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            context,
            REQ_DISMISS + 10,
            Intent(context, SmsMonitorService::class.java).setAction(SmsMonitorService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(context, CHANNEL_MONITOR)
            .setSmallIcon(R.drawable.ic_notification_small)
            // 常驻通知刻意不设 setLargeIcon：大图标会显示在这行文字右侧，
            // 常驻通知只需要文字 + 状态栏小图标，右侧保持干净。
            .setContentTitle("短信关键字监控运行中")
            .setContentText("正在监听短信，命中关键字将立即响铃")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(openApp)
            .addAction(0, "停止监控", stopIntent)
            .build()
    }

    /**
     * 闹钟通知：点击关闭按钮可停止响铃
     */
    fun buildAlarmNotification(
        context: Context,
        matchedKeyword: String?,
        reason: AlarmReason = AlarmReason.SMS_KEYWORD,
        taskTime: String? = null
    ): Notification {
        ensureChannels(context)

        val openApp = PendingIntent.getActivity(
            context,
            REQ_OPEN_APP,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val dismiss = PendingIntent.getBroadcast(
            context,
            REQ_DISMISS,
            Intent(context, AlarmDismissReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val title: String
        val text: String
        when (reason) {
            AlarmReason.TEST -> {
                title = "测试铃声播放中"
                text = "这是测试播放，点击下方按钮可停止"
            }
            AlarmReason.TASK_MISSING -> {
                title = "定时检查未通过"
                text = if (taskTime.isNullOrBlank()) {
                    "设定时段内未收到符合条件的短信"
                } else {
                    "截至 $taskTime 未收到符合条件的短信"
                }
            }
            AlarmReason.TASK_UNVERIFIABLE -> {
                title = "定时检查无法完成"
                text = "未能读取短信，已按未收到处理"
            }
            AlarmReason.SMS_KEYWORD -> {
                title = "短信关键字匹配"
                text = if (matchedKeyword.isNullOrBlank()) {
                    "检测到包含关键字的短信，闹钟已触发"
                } else {
                    "命中关键字「$matchedKeyword」，闹钟已触发"
                }
            }
        }

        return NotificationCompat.Builder(context, CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_notification_small)
            .setLargeIcon(appIconLarge(context))
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setOngoing(false)
            .setContentIntent(openApp)
            .addAction(0, "关闭闹钟", dismiss)
            .build()
    }
}
