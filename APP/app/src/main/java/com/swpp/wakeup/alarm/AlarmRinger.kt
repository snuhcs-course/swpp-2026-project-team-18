package com.swpp.wakeup.alarm

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.CombinedVibration
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log

/**
 * 알람 소리와 진동.
 *
 * 액티비티가 아니라 object 인 이유 — 화면이 회전하거나 재생성될 때 소리가
 * 끊기거나 두 번 겹치면 안 된다. 재생 상태는 화면보다 오래 살아야 한다.
 *
 * **`USAGE_ALARM` 으로 재생한다.** 미디어 볼륨이 0 이어도, 무음 모드여도
 * 알람은 울려야 한다. 알람 스트림은 그렇게 동작한다.
 */
object AlarmRinger {

    private var player: MediaPlayer? = null
    private var vibrating = false

    val isRinging: Boolean get() = player != null

    @Synchronized
    fun start(context: Context) {
        if (player != null) return // 이미 울리고 있다

        startSound(context)
        startVibration(context)
    }

    @Synchronized
    fun stop(context: Context) {
        player?.let {
            runCatching { if (it.isPlaying) it.stop() }
            runCatching { it.release() }
        }
        player = null

        if (vibrating) {
            vibratorOf(context)?.cancel()
            vibrating = false
        }
    }

    private fun startSound(context: Context) {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: run {
                Log.w(TAG, "기본 알람음을 찾지 못했다. 진동만 쓴다")
                return
            }

        player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(context, uri)
                isLooping = true
                prepare()
                start()
            }
        }.getOrElse { e ->
            Log.e(TAG, "알람음 재생 실패", e)
            null
        }

        // 알람 스트림이 0 이면 소리가 안 난다. 사용자가 직접 내린 값이라
        // 함부로 올리지 않고, 0 일 때만 들리는 최소치로 올린다.
        val audio = context.getSystemService(AudioManager::class.java)
        if (audio != null && audio.getStreamVolume(AudioManager.STREAM_ALARM) == 0) {
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            runCatching {
                audio.setStreamVolume(AudioManager.STREAM_ALARM, max / 3, 0)
            }
            Log.i(TAG, "알람 볼륨이 0 이라 최소치로 올렸다")
        }
    }

    private fun startVibration(context: Context) {
        val vibrator = vibratorOf(context) ?: return
        // 0.5초 진동 · 0.5초 정지 반복. index 1 부터 되풀이한다.
        val pattern = longArrayOf(0, 500, 500)
        runCatching {
            vibrator.vibrate(
                CombinedVibration.createParallel(
                    VibrationEffect.createWaveform(pattern, 1)
                )
            )
            vibrating = true
        }.onFailure { Log.w(TAG, "진동 실패", it) }
    }

    private fun vibratorOf(context: Context): VibratorManager? =
        context.getSystemService(VibratorManager::class.java)

    private const val TAG = "AlarmRinger"
}
