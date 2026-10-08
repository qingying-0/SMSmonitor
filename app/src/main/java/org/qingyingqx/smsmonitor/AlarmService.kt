package org.qingyingqx.smsmonitor

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * 闹钟触发原因。
 * 决定通知文案，也便于排查"这次为什么响"。
 */
enum class AlarmReason {
    /** 短信命中关键字 */
    SMS_KEYWORD,

    /** 定时检查：时段内未收到符合条件的短信 */
    TASK_MISSING,

    /** 定时检查：无法读取短信（权限或系统限制），按未收到处理 */
    TASK_UNVERIFIABLE,

    /** 用户手动测试 */
    TEST;

    companion object {
        fun parse(name: String?): AlarmReason =
            values().firstOrNull { it.name == name } ?: SMS_KEYWORD
    }
}

/**
 * 闹钟前台服务
 *
 * 必须以前台服务方式播放闹钟，原因：
 * 若直接在 BroadcastReceiver 中播放，onReceive() 返回后进程没有活跃组件，
 * 系统会在数秒内回收进程，导致闹钟刚响就哑掉。
 */
class AlarmService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopAlarm()
                return START_NOT_STICKY
            }
            else -> {
                val keyword = intent?.getStringExtra(EXTRA_KEYWORD)
                val reason = AlarmReason.parse(intent?.getStringExtra(EXTRA_REASON))
                val uriStr = intent?.getStringExtra(EXTRA_RINGTONE_URI)
                val volume = intent?.getFloatExtra(EXTRA_VOLUME, 0.8f) ?: 0.8f
                val taskTime = intent?.getStringExtra(EXTRA_TASK_TIME)

                startForegroundCompat(keyword, reason, taskTime)

                // 前台服务已建立，进程不会被回收，可以安全循环播放
                AlarmPlayer.play(this, uriStr?.let { Uri.parse(it) }, volume)
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundCompat(
        keyword: String?,
        reason: AlarmReason,
        taskTime: String?
    ) {
        Notifications.ensureChannels(this)
        val notification = Notifications.buildAlarmNotification(this, keyword, reason, taskTime)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                Notifications.ALARM_NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(Notifications.ALARM_NOTIF_ID, notification)
        }
    }

    private fun stopAlarm() {
        AlarmPlayer.stop()
        NotificationManagerCompat.from(this).cancel(Notifications.ALARM_NOTIF_ID)
        stopForegroundCompat()
        stopSelf()
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
    }

    override fun onDestroy() {
        AlarmPlayer.stop()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "org.qingyingqx.smsmonitor.action.ALARM_START"
        const val ACTION_STOP = "org.qingyingqx.smsmonitor.action.ALARM_STOP"

        const val EXTRA_RINGTONE_URI = "extra_ringtone_uri"
        const val EXTRA_VOLUME = "extra_volume"
        const val EXTRA_KEYWORD = "extra_keyword"
        const val EXTRA_REASON = "extra_reason"
        const val EXTRA_TASK_TIME = "extra_task_time"

        /**
         * 触发闹钟。优先走前台服务；若系统限制后台启动前台服务，
         * 则退化为在当前进程直接播放（尽力而为）。
         *
         * @return true 表示以前台服务方式正常启动；false 表示走了降级播放
         */
        fun start(
            context: Context,
            ringtoneUri: String?,
            volume: Float,
            keyword: String?,
            reason: AlarmReason = AlarmReason.SMS_KEYWORD,
            taskTimeText: String? = null
        ): Boolean {
            val intent = Intent(context, AlarmService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RINGTONE_URI, ringtoneUri)
                putExtra(EXTRA_VOLUME, volume)
                putExtra(EXTRA_KEYWORD, keyword)
                putExtra(EXTRA_REASON, reason.name)
                putExtra(EXTRA_TASK_TIME, taskTimeText)
            }

            return try {
                ContextCompat.startForegroundService(context, intent)
                true
            } catch (e: Exception) {
                // Android 12+ 后台启动前台服务受限时的兜底
                e.printStackTrace()
                Notifications.ensureChannels(context)
                AlarmPlayer.play(context, ringtoneUri?.let { Uri.parse(it) }, volume)
                try {
                    NotificationManagerCompat.from(context).notify(
                        Notifications.ALARM_NOTIF_ID,
                        Notifications.buildAlarmNotification(context, keyword, reason, taskTimeText)
                    )
                } catch (ignore: Exception) {
                    ignore.printStackTrace()
                }
                false
            }
        }

        /**
         * 停止闹钟
         */
        fun stop(context: Context) {
            AlarmPlayer.stop()
            NotificationManagerCompat.from(context).cancel(Notifications.ALARM_NOTIF_ID)
            context.stopService(Intent(context, AlarmService::class.java))
        }
    }
}
