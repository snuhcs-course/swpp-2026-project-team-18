package com.swpp.wakeup.alarm

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import com.swpp.wakeup.domain.model.AlarmSchedule
import com.swpp.wakeup.sensing.LocationPermissions
import com.swpp.wakeup.sensing.TripTrackingService
import com.swpp.wakeup.ui.common.JitCard
import com.swpp.wakeup.ui.common.JitDotLabel
import com.swpp.wakeup.ui.theme.JitColor
import com.swpp.wakeup.ui.theme.JitRadius
import com.swpp.wakeup.ui.theme.JitSpace
import com.swpp.wakeup.ui.theme.JitTheme

/**
 * 알람이 울릴 때 뜨는 전체화면.
 *
 * 잠금화면 위에 뜨고 화면을 켠다. `singleInstance` + 빈 `taskAffinity` 라서
 * 앱의 화면 스택과 섞이지 않는다 — 해제한 뒤 뒤로 가기를 눌렀을 때 알람이
 * 다시 나오면 안 된다.
 *
 * **뒤로 가기를 막는다.** 알람은 눌러서 해제해야 사라진다. 뒤로 가기로
 * 지워지면 사용자는 껐다고 생각하지만 소리만 멈추고 아무 기록도 남지 않는다.
 *
 * 해제하면 [TripTrackingService] 가 시작된다. 아침이 시작되는 지점이 알람
 * 해제이고, 여기서부터 "언제 나갔는지" 를 볼 수 있다.
 */
class AlarmActivity : ComponentActivity() {

    private lateinit var store: ScheduledAlarmStore
    private var schedule: AlarmSchedule? = null

    /** 소리를 자동으로 멈추는 타이머. 사람이 없는 방에서 계속 울리지 않게 한다. */
    private val silenceHandler = Handler(Looper.getMainLooper())
    private val silenceRunnable = Runnable {
        Log.i(TAG, "${AUTO_SILENCE_MINUTES}분간 응답이 없어 소리를 멈춘다")
        AlarmRinger.stop(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 매니페스트 속성만으로는 기기에 따라 동작하지 않는다. 코드에서도 켠다.
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        enableEdgeToEdge()

        store = ScheduledAlarmStore(this)
        val eventId = intent.getLongExtra(EXTRA_EVENT_ID, -1L)
        schedule = store.find(eventId)

        if (schedule == null) {
            Log.w(TAG, "일정 $eventId 의 알람 정보가 없다. 화면을 닫는다")
            finish()
            return
        }

        // 채널 소리로 한 번 울렸다. 이제 여기서 이어받아 계속 울린다.
        AlarmRinger.start(this)
        silenceHandler.postDelayed(silenceRunnable, AUTO_SILENCE_MINUTES * 60_000L)

        setContent {
            JitTheme {
                AlarmRingingScreen(
                    schedule = schedule!!,
                    canTrack = schedule!!.canTrack && LocationPermissions.granted(this),
                    onDismiss = ::dismiss,
                    onSnooze = ::snooze,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleInstance 라서 두 번째 발화가 같은 인스턴스로 들어온다.
        // 이미 다른 알람이 떠 있다면 새 것으로 갈아 끼운다.
        val eventId = intent.getLongExtra(EXTRA_EVENT_ID, -1L)
        store.find(eventId)?.let { schedule = it }
    }

    override fun onDestroy() {
        silenceHandler.removeCallbacks(silenceRunnable)
        AlarmRinger.stop(this)
        super.onDestroy()
    }

    /** 알람을 끄고 이동 추적을 시작한다. */
    private fun dismiss() {
        val current = schedule ?: return
        stopEverything()

        if (current.canTrack && LocationPermissions.granted(this)) {
            TripTrackingService.start(this, current)
        } else {
            Log.i(
                TAG,
                "추적을 시작하지 않는다 (기준점 ${current.canTrack}, " +
                    "권한 ${LocationPermissions.granted(this)})",
            )
        }
        finish()
    }

    private fun snooze() {
        val current = schedule ?: return
        stopEverything()
        AlarmScheduler(this).snooze(current, SNOOZE_MINUTES)
        finish()
    }

    private fun stopEverything() {
        silenceHandler.removeCallbacks(silenceRunnable)
        AlarmRinger.stop(this)
        NotificationManagerCompat.from(this)
            .cancel(AlarmNotifications.NOTIFICATION_ALARM)
    }

    companion object {
        const val EXTRA_EVENT_ID = "event_id"

        /** 미루기 간격. */
        const val SNOOZE_MINUTES = 5

        /** 이 시간이 지나면 소리를 멈춘다. 화면은 남는다. */
        const val AUTO_SILENCE_MINUTES = 5L

        private const val TAG = "AlarmActivity"

        fun intent(context: Context, eventId: Long): Intent =
            Intent(context, AlarmActivity::class.java)
                .putExtra(EXTRA_EVENT_ID, eventId)
                .addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION)
    }
}

@Composable
private fun AlarmRingingScreen(
    schedule: AlarmSchedule,
    canTrack: Boolean,
    onDismiss: () -> Unit,
    onSnooze: () -> Unit,
) {
    // 뒤로 가기로 알람을 지울 수 없게 한다.
    BackHandler(enabled = true) { /* 의도적으로 아무것도 하지 않는다 */ }

    Box(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(
                horizontal = JitSpace.ScreenHorizontal,
                vertical = JitSpace.ScreenBottom,
            )
    ) {
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Spacer(Modifier.height(24.dp))
                Text(
                    text = "지금 일어날 시각",
                    color = JitColor.TextSecondary,
                    fontSize = 14.sp,
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = schedule.alarmLabel,
                        color = JitColor.TextPrimary,
                        fontSize = 68.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = schedule.meridiem,
                        color = JitColor.TextSecondary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(JitSpace.Section)) {
                JitCard {
                    Text(
                        text = schedule.eventLine,
                        color = JitColor.TextPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    schedule.placeName?.let {
                        Text(text = it, color = JitColor.TextSecondary, fontSize = 13.sp)
                    }
                    schedule.arrivalLine?.let {
                        JitDotLabel(
                            text = it,
                            dotColor = JitColor.Green,
                            textColor = JitColor.TextPrimary,
                            fontSize = 13,
                        )
                    }
                }

                if (canTrack) {
                    JitCard {
                        JitDotLabel(
                            text = "해제하면 이동을 따라간다",
                            dotColor = JitColor.Blue,
                            fontSize = 12,
                        )
                        Text(
                            text = "집에서 나가는 시각과 목적지에 닿는 시각을 " +
                                "위치로 판별해 기록함. 이 기록이 다음 알람을 " +
                                "더 정확하게 만든다.",
                            color = JitColor.TextSecondary,
                            fontSize = 12.sp,
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(JitRadius.Button),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = JitColor.Accent,
                        contentColor = JitColor.Bg,
                    ),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        vertical = 18.dp
                    ),
                ) {
                    Text(
                        text = if (canTrack) "일어났음 · 이동 기록 시작" else "알람 해제",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                }

                Button(
                    onClick = onSnooze,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(JitRadius.Button),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = JitColor.Surface2,
                        contentColor = JitColor.TextSecondary,
                    ),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        vertical = 15.dp
                    ),
                ) {
                    Text(
                        text = "${AlarmActivity.SNOOZE_MINUTES}분 뒤 다시",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }

                Text(
                    text = "미루면 도착 확률이 그만큼 떨어진다",
                    color = JitColor.TextSecondary,
                    fontSize = 11.sp,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
