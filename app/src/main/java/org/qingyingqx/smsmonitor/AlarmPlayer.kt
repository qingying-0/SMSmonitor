package org.qingyingqx.smsmonitor

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 闹钟播放器单例
 * 管理 MediaPlayer 实例，播放闹钟铃声
 */
object AlarmPlayer {
    private var mediaPlayer: MediaPlayer? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    /**
     * 播放闹钟铃声
     * @param context 上下文
     * @param ringtoneUri 铃声URI，null则使用系统默认闹钟铃声
     * @param volume 音量 (0.0 ~ 1.0)
     */
    fun play(context: Context, ringtoneUri: Uri?, volume: Float) {
        stop()

        val uri = ringtoneUri ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val actualVolume = volume.coerceIn(0f, 1f)

        try {
            mediaPlayer = createPlayer(context, uri, actualVolume)
            _isPlaying.value = true
        } catch (e: Exception) {
            e.printStackTrace()
            // 回退到默认闹钟铃声
            try {
                val defaultUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                mediaPlayer = createPlayer(context, defaultUri, actualVolume)
                _isPlaying.value = true
            } catch (e2: Exception) {
                e2.printStackTrace()
            }
        }
    }

    private fun createPlayer(context: Context, uri: Uri, volume: Float): MediaPlayer {
        return MediaPlayer().apply {
            setDataSource(context.applicationContext, uri)
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            setVolume(volume, volume)
            isLooping = true
            prepare()
            start()
        }
    }

    /**
     * 停止播放并释放资源
     */
    fun stop() {
        try {
            mediaPlayer?.apply {
                if (isPlaying) stop()
                release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        mediaPlayer = null
        _isPlaying.value = false
    }
}
