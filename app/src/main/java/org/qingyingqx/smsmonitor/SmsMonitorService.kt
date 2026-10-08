package org.qingyingqx.smsmonitor

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.BaseColumns
import android.provider.Telephony
import androidx.core.content.ContextCompat

/**
 * 短信监控前台服务
 *
 * 承担"双保险"中的第二通道：
 * 1. ContentObserver 监听 content://sms 变化（即使 ROM 拦截了静态广播也能兜底）
 * 2. 动态注册 SmsReceiver（进程存活期间的第二条广播通道）
 *
 * 静态广播通道由 AndroidManifest 中的 SmsReceiver 承担，负责进程被杀后的拉起。
 */
class SmsMonitorService : Service() {

    private var contentObserver: ContentObserver? = null
    private var dynamicReceiver: SmsReceiver? = null
    private var receiverRegistered = false

    /** 已处理的最新收件箱 _id，防止服务重启后重复触发历史短信 */
    private var lastSeenId = -1L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // 用户从通知栏明确停止：必须同步关闭总开关并撤销守护，
            // 否则会被 onResume 自愈与 MonitorWorker 重新拉起
            shutdown(this)
            return START_NOT_STICKY
        }

        // 必须尽快进入前台；失败时安全退出，绝不能让异常冲出 onStartCommand
        if (!startForegroundCompat()) {
            stopSelf()
            return START_NOT_STICKY
        }

        isRunning = true
        startObserving()

        // START_STICKY：被系统回收后自动重建，保证监控长期有效
        return START_STICKY
    }

    /**
     * 进入前台。
     * @return true 表示已成功成为前台服务；false 表示失败，调用方应停止自身
     */
    private fun startForegroundCompat(): Boolean {
        return try {
            val notification = Notifications.buildMonitorNotification(this)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14+ 必须显式声明前台服务类型
                startForeground(
                    Notifications.MONITOR_NOTIF_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(Notifications.MONITOR_NOTIF_ID, notification)
            }
            true
        } catch (e: Exception) {
            // 可能抛 ForegroundServiceStartNotAllowedException 或前台服务类型不匹配
            e.printStackTrace()
            false
        }
    }

    private fun startObserving() {
        if (!hasReadSmsPermission()) {
            // 没有 READ_SMS 权限时 ContentObserver 无法工作，
            // 静态广播通道仍然有效，这里静默降级。
            return
        }

        if (contentObserver == null) {
            // 先记录当前收件箱最新 _id，避免把历史短信当成新短信
            lastSeenId = queryLatestInboxId()

            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    super.onChange(selfChange, uri)
                    checkNewInboxMessages()
                }
            }
            try {
                contentResolver.registerContentObserver(
                    Telephony.Sms.CONTENT_URI,
                    true,
                    observer
                )
                contentObserver = observer
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (!receiverRegistered) {
            try {
                val filter = IntentFilter(Telephony.Sms.Intents.SMS_RECEIVED_ACTION)
                val receiver = SmsReceiver()
                ContextCompat.registerReceiver(
                    this,
                    receiver,
                    filter,
                    ContextCompat.RECEIVER_EXPORTED
                )
                dynamicReceiver = receiver
                receiverRegistered = true
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun stopObserving() {
        contentObserver?.let {
            try {
                contentResolver.unregisterContentObserver(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        contentObserver = null

        if (receiverRegistered) {
            try {
                dynamicReceiver?.let { unregisterReceiver(it) }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        receiverRegistered = false
        dynamicReceiver = null
    }

    private fun hasReadSmsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_SMS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun queryLatestInboxId(): Long {
        var cursor: Cursor? = null
        return try {
            cursor = contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(BaseColumns._ID),
                null,
                null,
                "${BaseColumns._ID} DESC"
            )
            if (cursor != null && cursor.moveToFirst()) cursor.getLong(0) else 0L
        } catch (e: Exception) {
            e.printStackTrace()
            0L
        } finally {
            cursor?.close()
        }
    }

    /**
     * 查询比 lastSeenId 更新的收件箱短信并逐条匹配
     */
    private fun checkNewInboxMessages() {
        if (!hasReadSmsPermission()) return

        var cursor: Cursor? = null
        try {
            cursor = contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(BaseColumns._ID, Telephony.Sms.BODY),
                "${BaseColumns._ID} > ?",
                arrayOf(lastSeenId.toString()),
                "${BaseColumns._ID} ASC"
            )

            if (cursor != null) {
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val body = cursor.getString(1) ?: ""

                    // 无论是否命中都要推进游标，避免重复处理
                    if (id > lastSeenId) lastSeenId = id

                    SmsTrigger.handle(this, body, TriggerChannel.CONTENT_OBSERVER)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            cursor?.close()
        }
    }

    override fun onDestroy() {
        isRunning = false
        stopObserving()
        // 显式移除常驻通知，避免个别 ROM 残留
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "org.qingyingqx.smsmonitor.action.MONITOR_START"
        const val ACTION_STOP = "org.qingyingqx.smsmonitor.action.MONITOR_STOP"

        /** 服务是否存活（同进程内有效，进程被杀后静态变量随进程重置为 false） */
        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, SmsMonitorService::class.java).setAction(ACTION_START)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        /** 仅停止服务本身，不清除监控开关（供 shutdown 内部使用） */
        fun stop(context: Context) {
            context.stopService(Intent(context, SmsMonitorService::class.java))
        }

        /**
         * 完整关闭监控。
         *
         * 用户主动停止监控时必须走这里：清除总开关 → 撤销中转闹钟 → 停闹钟 →
         * 重新评估守护任务 → 停服务。缺任何一步都会导致"停止"被自愈逻辑撤销。
         */
        fun shutdown(context: Context) {
            context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(Prefs.KEY_MONITORING_ENABLED, false).apply()
            AlarmScheduler.cancel(context)
            AlarmService.stop(context)
            // 监控停了，但定时检查任务可能仍在启用 → 由 sync 决定守护去留
            Watchdog.sync(context)
            stop(context)
        }
    }
}
