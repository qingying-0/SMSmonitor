package org.qingyingqx.smsmonitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 闹钟关闭广播接收器
 * 响应通知栏中的"关闭闹钟"按钮
 */
class AlarmDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        AlarmService.stop(context)
    }
}
