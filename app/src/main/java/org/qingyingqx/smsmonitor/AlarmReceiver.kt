package org.qingyingqx.smsmonitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 精准闹钟中转接收器
 *
 * 由 [AlarmScheduler] 通过 AlarmManager.setAlarmClock 投递。
 * 系统投递精准闹钟时会授予应用 FOREGROUND_SERVICE_ALLOWED 临时白名单，
 * 因此这里启动 [AlarmService] 前台服务是被允许的。
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val keyword = intent.getStringExtra(AlarmService.EXTRA_KEYWORD)
        val ringtoneUri = intent.getStringExtra(AlarmService.EXTRA_RINGTONE_URI)
        val volume = intent.getFloatExtra(AlarmService.EXTRA_VOLUME, 0.8f)
        val reason = AlarmReason.parse(intent.getStringExtra(AlarmService.EXTRA_REASON))

        AlarmService.start(context, ringtoneUri, volume, keyword, reason)
    }
}
