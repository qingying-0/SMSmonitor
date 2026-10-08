package org.qingyingqx.smsmonitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

/**
 * 短信广播接收器（双保险之第一通道）
 *
 * 静态注册在 AndroidManifest 中，即使 APP 进程被杀，
 * 系统收到短信时也会拉起本接收器。
 *
 * 职责：解析短信内容 → 交给 [SmsTrigger] 匹配关键字 → 触发 [AlarmService]。
 * 真正的播放动作由 AlarmService 以前台服务方式完成（避免进程被回收导致闹钟哑掉）。
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val pendingResult = goAsync()
        try {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            if (messages.isEmpty()) return

            // 长短信会被拆成多个 PDU，按顺序拼接还原完整正文
            val body = buildString {
                messages.forEach { part ->
                    part?.messageBody?.let { append(it) }
                }
            }

            SmsTrigger.handle(context, body, TriggerChannel.BROADCAST)
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try {
                pendingResult.finish()
            } catch (ignore: Exception) {
                ignore.printStackTrace()
            }
        }
    }
}
