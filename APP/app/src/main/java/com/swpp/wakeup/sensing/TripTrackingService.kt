package com.swpp.wakeup.sensing

import android.Manifest
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.gson.Gson
import com.swpp.wakeup.alarm.AlarmNotifications
import com.swpp.wakeup.data.local.LiveRouteStore
import com.swpp.wakeup.data.local.withDiskDefaults
import com.swpp.wakeup.data.remote.ApiClient
import com.swpp.wakeup.data.remote.TripObservationInput
import com.swpp.wakeup.data.repository.EventRepository
import com.swpp.wakeup.domain.model.AlarmSchedule
import com.swpp.wakeup.domain.model.ArrivalVerdict
import com.swpp.wakeup.domain.model.LiveRouteRefreshGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID
import kotlin.math.roundToInt

/**
 * 한 아침의 이동을 따라가며 출발·도착을 판별하는 포그라운드 서비스.
 *
 * **왜 포그라운드 서비스인가.** 사용자는 알람을 끄고 주머니에 휴대폰을 넣는다.
 * 화면이 꺼진 채로 30분을 이동한다. 그동안 위치를 받아야 하고, 그건 백그라운드
 * 프로세스에게 허용되지 않는다. 상태바에 무엇을 왜 하고 있는지 계속 보여주는
 * 대가로 위치를 계속 받는다.
 *
 * **정확도를 단계별로 바꾼다.** 집을 나서는지 보는 동안은 150m 를 구분하면
 * 되므로 저전력 모드로 30초에 한 번만 본다. 나간 뒤에는 50m 반경을 봐야 하니
 * 고정확도로 10초에 한 번 본다. 처음부터 고정확도로 켜 두면 준비하는 30분
 * 동안 GPS 를 헛돌린다.
 *
 * 스스로 멈추는 조건 — 도착 판정이 났거나, 일정 시작 후 한 시간이 지났거나,
 * 사용자가 알림에서 중지를 눌렀을 때.
 */
class TripTrackingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var queue: TripObservationQueue
    private lateinit var liveRouteStore: LiveRouteStore
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private val repository by lazy { EventRepository() }

    /** 화면 수명과 무관하게 서비스가 소유하는 1분 호출 게이트. */
    private val liveRouteGate = LiveRouteRefreshGate()
    private var liveRouteJob: Job? = null
    @Volatile private var liveRouteSessionGeneration = 0L

    private var schedule: AlarmSchedule? = null
    private var geofence: TripGeofence? = null

    /** 지금 어떤 정확도로 받고 있는지. 단계가 바뀔 때만 재요청한다. */
    private var highAccuracy = false
    private var startedUpdates = false

    /**
     * 이동이 시작된 것으로 판정한 시각. 아직 준비 중이면 null.
     *
     * 실시간 도착 예정의 경과 시간 기준이다. 한 번 정하면 바꾸지 않는다 —
     * 중간에 다시 잡으면 지하철에서 멈춰 있던 시간이 계산에서 빠져 전망이
     * 실제보다 낙관적으로 나온다.
     */
    private var movingSinceMillis: Long? = null

    private val deadlineRunnable = Runnable {
        Log.i(TAG, "마감 시각이 지나 추적을 멈춘다")
        finishAtDeadline()
    }

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach(::handleLocation)
        }
    }

    override fun onCreate() {
        super.onCreate()
        queue = TripObservationQueue(this)
        liveRouteStore = LiveRouteStore(this)
        AlarmNotifications.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "사용자가 추적을 중지했다")
            stopSelf()
            return START_NOT_STICKY
        }

        val raw = intent?.getStringExtra(EXTRA_SCHEDULE)
        val parsed = raw?.let {
            runCatching {
                // 이 JSON 은 같은 버전이 만들었을 것이나, 시스템이 들고 있던
                // 낡은 Intent 가 재전달될 수 있다. 되살리는 모든 경로를 같은
                // 방식으로 다룬다 — 이유는 [withDiskDefaults] 에 있다.
                gson.fromJson(it, AlarmSchedule::class.java)?.withDiskDefaults()
            }.getOrNull()
        }

        if (parsed == null) {
            Log.w(TAG, "추적할 일정 정보가 없다. 멈춘다")
            stopSelf()
            return START_NOT_STICKY
        }

        if (!LocationPermissions.granted(this)) {
            // Android 14+ 는 위치 권한 없이 location 타입 포그라운드 서비스를
            // 시작하면 SecurityException 을 던진다. 미리 막는다.
            Log.w(TAG, "위치 권한이 없다. 추적하지 않는다")
            stopSelf()
            return START_NOT_STICKY
        }

        val redelivered = flags and START_FLAG_REDELIVERY != 0

        // 정상 ACTION_START 는 새 추적 세션의 경계다. 같은 event id가 다음 날
        // 다시 쓰이더라도 어제 좌표의 경로를 잠깐 보여 주지 않는다. 반대로
        // 프로세스 복구의 redelivery라면 5분 안의 마지막 성공 결과를 유지한다.
        liveRouteJob?.cancel()
        liveRouteGate.reset()
        liveRouteSessionGeneration++
        liveRouteStore.beginSession()
        if (redelivered) {
            TripLiveState.restoreLiveRoute(liveRouteStore.current(parsed.eventId))
        } else {
            liveRouteStore.clear()
            TripLiveState.clearLiveRoute()
        }
        movingSinceMillis = null

        schedule = parsed
        geofence = TripGeofence(
            home = parsed.homeLat?.let { lat ->
                parsed.homeLng?.let { lng -> GeoPoint(lat, lng) }
            },
            destination = parsed.destLat?.let { lat ->
                parsed.destLng?.let { lng -> GeoPoint(lat, lng) }
            },
        )

        // startForeground 는 즉시 불러야 한다. 늦으면 시스템이 죽인다.
        promoteToForeground(initialNotificationText(parsed))

        val remaining = parsed.trackingDeadlineMillis - System.currentTimeMillis()
        if (remaining <= 0) {
            Log.i(TAG, "이미 마감 시각이 지났다. 추적하지 않는다")
            stopSelf()
            return START_NOT_STICKY
        }
        handler.postDelayed(deadlineRunnable, remaining)

        requestUpdates(highAccuracy = geofence?.phase != TripGeofence.Phase.BEFORE_DEPARTURE)

        // 밀린 관측이 있으면 이 기회에 올린다.
        scope.launch { queue.flush() }

        Log.i(
            TAG,
            "추적 시작: 일정 ${parsed.eventId} " +
                "출발판별=${parsed.canDetectDeparture} 도착판별=${parsed.canDetectArrival} " +
                "마감=${remaining / 60_000}분 뒤",
        )
        // 프로세스가 메모리 압박으로 죽으면 원래 schedule Intent를 다시 받아야
        // 같은 포그라운드 추적과 백그라운드 경로 갱신을 이어 갈 수 있다.
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        handler.removeCallbacks(deadlineRunnable)
        liveRouteSessionGeneration++
        runCatching { fused.removeLocationUpdates(callback) }
        // 남은 관측을 마지막으로 한 번 올려 본다. scope 를 바로 닫으면
        // 이 요청이 취소되므로 별도 스코프를 쓴다.
        val pending = queue.pendingCount
        if (pending > 0) {
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                TripObservationQueue(applicationContext).flush()
            }
        }
        scope.cancel()
        // **화면에 내보낸 위치를 반드시 지운다.** 남겨 두면 알람 결정 화면이
        // 몇 시간 전 좌표를 현재 위치처럼 그리고, 사용자는 그 진행률로 여유가
        // 있다고 판단한다. 도착·마감·사용자 중지 모두 여기를 지나간다.
        schedule?.eventId?.let(liveRouteStore::clear)
        liveRouteGate.reset()
        TripLiveState.clear()
        Log.i(TAG, "추적 종료 (대기 관측 ${pending}건)")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // --- 위치 처리 ---------------------------------------------------------

    private fun handleLocation(location: Location) {
        val current = schedule ?: return
        val fence = geofence ?: return

        if (System.currentTimeMillis() > current.trackingDeadlineMillis) {
            finishAtDeadline()
            return
        }

        val fix = LocationFix(
            lat = location.latitude,
            lng = location.longitude,
            // accuracy 를 보고하지 않는 fix 는 신뢰할 수 없다. 최악으로 본다.
            accuracyM = if (location.hasAccuracy()) location.accuracy else Float.MAX_VALUE,
            atMillis = if (location.time > 0) location.time else System.currentTimeMillis(),
        )

        val phaseBefore = fence.phase
        val decision = fence.offer(fix)

        // 이동이 시작된 시각을 여기서 한 번만 잡는다.
        //
        // **화면이 아니라 서비스가 들고 있어야 한다.** 화면에서 처음 본 좌표를
        // 시작점으로 삼으면, 30분을 이동한 뒤에 화면을 연 사용자가 "방금
        // 출발했다" 로 계산되어 도착 예정이 실제보다 이르게 나온다.
        //
        // 판정이 `Departed` 인 순간이 아니라 **단계가 IN_TRANSIT 인 첫 fix** 를
        // 쓴다. 첫 위치부터 집 밖이면 출발 사건이 만들어지지 않지만
        // (`departureMissed`) 그 사람도 이동 중이다.
        if (movingSinceMillis == null && fence.phase != TripGeofence.Phase.BEFORE_DEPARTURE) {
            movingSinceMillis = fix.atMillis
        }

        // 판정 결과와 무관하게 **매 위치를** 화면에 내보낸다. 판정이 난 순간만
        // 보내면 출발과 도착 사이에 진행률이 멈춰 있고, 그 사이가 사용자가
        // 가장 많이 들여다보는 구간이다.
        TripLiveState.publish(
            TripLiveState.Snapshot(
                eventId = current.eventId,
                point = GeoPoint(fix.lat, fix.lng),
                accuracyM = fix.accuracyM,
                atMillis = fix.atMillis,
                phase = fence.phase,
                awaitingDwell = fence.awaitingDwell,
                movingSinceMillis = movingSinceMillis,
            )
        )

        // 위치를 계속 받는 포그라운드 서비스가 경로 갱신도 소유한다. Activity가
        // STOPPED/파괴된 동안에도 이 지점은 10초마다 실행되고, 게이트가 실제
        // 서버 호출을 최대 1분에 한 번으로 제한한다.
        maybeRefreshLiveRoute(current, fix, fence.phase)

        when (decision) {
            is TripEvent.Departed -> {
                Log.i(TAG, "출발 판정: 집에서 ${decision.distanceM.roundToInt()}m")
                record(
                    current,
                    TripObservationInput.KIND_DEPART,
                    decision.fix,
                    decision.distanceM,
                )
            }

            is TripEvent.Arrived -> {
                Log.i(
                    TAG,
                    "도착 판정: 목적지 ${decision.distanceM.roundToInt()}m " +
                        "체류 ${decision.dwellMillis / 1000}초",
                )
                record(
                    current,
                    TripObservationInput.KIND_ARRIVE,
                    decision.fix,
                    decision.distanceM,
                    dwellMillis = decision.dwellMillis,
                )
                // 도착했으면 화면도 그것을 알아야 한다. 이 갱신을 빼면 목록은
                // "이동 중" 에 멈춰 있고, 사용자는 도착이 기록됐는지 알 수 없다.
                TripLiveState.publish(
                    TripLiveState.Snapshot(
                        eventId = current.eventId,
                        point = GeoPoint(decision.fix.lat, decision.fix.lng),
                        accuracyM = decision.fix.accuracyM,
                        atMillis = decision.fix.atMillis,
                        phase = TripGeofence.Phase.ARRIVED,
                        movingSinceMillis = movingSinceMillis,
                    )
                )
                // 결과 알림을 **멈추기 전에** 띄운다. 이건 포그라운드 서비스
                // 알림이 아닌 별도 알림이라 stopSelf 에 휩쓸리지 않는다.
                postArrivalResult(current, decision.fix)
                stopSelf()
                return
            }

            null -> Unit
        }

        // 집을 나섰으면 목적지 반경을 봐야 하므로 정확도를 올린다.
        if (phaseBefore == TripGeofence.Phase.BEFORE_DEPARTURE &&
            fence.phase != TripGeofence.Phase.BEFORE_DEPARTURE
        ) {
            if (fence.departureMissed) {
                Log.i(TAG, "첫 위치부터 집 밖이다. 출발 시각은 기록하지 않는다")
            }
            requestUpdates(highAccuracy = true)
        }

        refreshProgressNotification(current, fence)
    }

    /** 판정 결과를 큐에 적고 올린다. 저장이 먼저다. */
    private fun record(
        current: AlarmSchedule,
        kind: String,
        fix: LocationFix,
        distanceM: Double,
        dwellMillis: Long? = null,
    ) {
        val observation = TripObservationInput(
            event = current.eventId,
            kind = kind,
            observedAt = OffsetDateTime
                .ofInstant(Instant.ofEpochMilli(fix.atMillis), ZoneId.systemDefault())
                .toString(),
            lat = fix.lat,
            lng = fix.lng,
            accuracyM = fix.accuracyM.toDouble(),
            distanceM = distanceM,
            // 도착에만 있다. 출발은 "반경을 벗어남" 이라 머문 시간이 없다.
            dwellSeconds = dwellMillis?.let { (it / 1000).toInt() },
            // 판정 시점에 만든다. 재전송할 때 새로 만들면 멱등성이 깨진다.
            clientUuid = UUID.randomUUID().toString(),
        )
        queue.enqueue(observation)
        scope.launch { queue.flush() }
    }

    /** 현재 위치부터 목적지까지의 최신 경로를 백그라운드에서도 갱신한다. */
    private fun maybeRefreshLiveRoute(
        current: AlarmSchedule,
        fix: LocationFix,
        phase: TripGeofence.Phase,
    ) {
        if (phase != TripGeofence.Phase.IN_TRANSIT) return
        if (fix.accuracyM > TripGeofence.MAX_ACCURACY_M) return
        if (!ApiClient.isReady) return

        val here = GeoPoint(fix.lat, fix.lng)
        val token = liveRouteGate.beginIfDue(SystemClock.elapsedRealtime()) ?: return
        val sessionGeneration = liveRouteSessionGeneration

        liveRouteJob = scope.launch {
            val result = repository.liveRoute(current.eventId, here)
            val route = (result as? EventRepository.Result.Success)?.data

            // 다른 ACTION_START/종료 뒤 도착한 응답은 디스크와 화면에 쓰지 않는다.
            if (!liveRouteGate.finish(token)) return@launch
            if (!isCurrentLiveRouteSession(current.eventId, sessionGeneration)) return@launch

            if (route == null) {
                val reason = (result as? EventRepository.Result.Failure)?.message
                    ?: "사용 가능한 경로가 없음"
                Log.w(TAG, "현재 위치 경로 갱신 실패: $reason")
                // 직전 성공 경로는 유지한다. 잠깐의 지하 구간 때문에 지도를
                // 출발지 경로로 되돌리는 것보다, 다음 1분 재시도까지 마지막으로
                // 확인된 현재-위치 경로를 보여 주는 편이 안전하다.
                return@launch
            }

            val snapshot = LiveRouteStore.Snapshot(
                eventId = current.eventId,
                origin = here,
                fetchedAtMillis = System.currentTimeMillis(),
                route = route,
            )
            // 디스크 기록이 먼저다. 직후 Activity가 재생성돼도 Flow에는 있는데
            // 디스크에는 없는 짧은 창이 생기지 않게 한다.
            var published = false
            val saved = liveRouteStore.save(snapshot) {
                if (isCurrentLiveRouteSession(current.eventId, sessionGeneration)) {
                    TripLiveState.publishLiveRoute(snapshot)
                    published = true
                }
            }
            if (!saved) {
                Log.i(TAG, "로그아웃 상태라 현재 위치 경로를 저장하지 않는다")
                return@launch
            }
            if (!published) {
                // save 직후 새 여정이 시작됐다. 방금 쓴 사본도 남기지 않는다.
                liveRouteStore.clear(current.eventId)
                return@launch
            }
            Log.i(TAG, "현재 위치 경로 갱신: 일정 ${current.eventId}, ${route.minutes}분")
        }
    }

    private fun isCurrentLiveRouteSession(eventId: Long, generation: Long): Boolean =
        liveRouteSessionGeneration == generation &&
            schedule?.eventId == eventId &&
            geofence?.phase == TripGeofence.Phase.IN_TRANSIT

    /**
     * 마감 시각에 추적을 끝낸다.
     *
     * 목적지 반경 안에서 체류 시간을 채우는 중이었다면 **그 상태로 확정한다.**
     * 마감에 걸려 버리면 실제로 관측한 도착이 사라지는데, 그건 체류 조건을
     * 넣기 전에는 기록됐던 도착이다. 판정 근거의 세기는 관측에 함께 올리는
     * 체류 시간이 말해 준다 — 근거는 [TripGeofence.finalizeArrival] 에 있다.
     */
    private fun finishAtDeadline() {
        val current = schedule
        val fence = geofence
        if (current != null && fence != null) {
            val event = fence.finalizeArrival()
            if (event != null) {
                Log.i(
                    TAG,
                    "마감 직전 도착 확정: 목적지 ${event.distanceM.roundToInt()}m " +
                        "체류 ${event.dwellMillis / 1000}초 (기준 미달)",
                )
                record(
                    current,
                    TripObservationInput.KIND_ARRIVE,
                    event.fix,
                    event.distanceM,
                    dwellMillis = event.dwellMillis,
                )
                postArrivalResult(current, event.fix)
            }
        }
        stopSelf()
    }

    // --- 위치 요청 ---------------------------------------------------------

    /**
     * 위치 갱신 요청.
     *
     * 이미 같은 정확도로 받고 있으면 아무것도 하지 않는다 — 재요청하면 GPS 가
     * 다시 warm-up 하면서 첫 fix 가 늦어진다.
     */
    private fun requestUpdates(highAccuracy: Boolean) {
        if (this.highAccuracy == highAccuracy && startedUpdates) return
        this.highAccuracy = highAccuracy

        val interval = if (highAccuracy) TRANSIT_INTERVAL_MS else IDLE_INTERVAL_MS
        val request = LocationRequest.Builder(
            if (highAccuracy) Priority.PRIORITY_HIGH_ACCURACY
            else Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            interval,
        )
            // 더 자주 오는 fix 도 받는다. 다른 앱이 이미 GPS 를 켰으면 공짜다.
            .setMinUpdateIntervalMillis(interval / 2)
            // 묶어서 늦게 주는 것을 막는다. 반경 판정은 제때 와야 의미가 있다.
            .setMaxUpdateDelayMillis(interval)
            .setWaitForAccurateLocation(highAccuracy)
            .build()

        // 권한 확인을 **여기에 펼쳐 쓴다.** onStartCommand 가 이미
        // LocationPermissions.granted 로 막지만 lint 는 메서드 경계를 넘어
        // 보지 못한다. 억제 주석으로 덮으면 나중에 가드를 지워도 주석만 남는다.
        //
        // 다시 확인하는 것이 낭비도 아니다. 이 함수는 정확도 전환에서 다시
        // 불리고, 그 사이에 사용자가 설정에서 권한을 끌 수 있다.
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

        if (!granted) {
            Log.w(TAG, "위치 권한이 없다. 추적을 멈춘다")
            stopSelf()
            return
        }

        runCatching {
            fused.removeLocationUpdates(callback)
            fused.requestLocationUpdates(request, callback, Looper.getMainLooper())
            startedUpdates = true
        }.onFailure {
            Log.e(TAG, "위치 갱신 요청 실패", it)
            stopSelf()
        }

        Log.i(
            TAG,
            "위치 갱신: ${if (highAccuracy) "고정확도" else "저전력"} ${interval / 1000}초 주기",
        )
    }

    // --- 알림 -------------------------------------------------------------

    private fun promoteToForeground(text: String) {
        ServiceCompat.startForeground(
            this,
            AlarmNotifications.NOTIFICATION_TRIP,
            AlarmNotifications.tripNotification(
                context = this,
                title = "이동 기록 중",
                text = text,
                stopIntent = stopPendingIntent(),
            ),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )
    }

    private fun updateNotification(
        title: String,
        text: String,
        phase: AlarmNotifications.TripPhaseLabel? = null,
    ) {
        // 포그라운드 서비스 알림이라 이미 떠 있지만, 갱신도 POST_NOTIFICATIONS
        // 를 요구한다. 권한이 없으면 조용히 넘긴다 — 서비스 자체는 계속 돈다.
        // 조건을 펼쳐 쓴 이유는 [AlarmReceiver] 와 같다(lint 가 메서드 경계를
        // 넘어 보지 못한다).
        val canPost = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!canPost) return

        runCatching {
            NotificationManagerCompat.from(this).notify(
                AlarmNotifications.NOTIFICATION_TRIP,
                AlarmNotifications.tripNotification(
                    context = this,
                    title = title,
                    text = text,
                    stopIntent = stopPendingIntent(),
                    phase = phase,
                ),
            )
        }
    }

    private fun refreshProgressNotification(
        current: AlarmSchedule,
        fence: TripGeofence,
    ) {
        val phase = phaseLabel(fence.phase)
        val remaining = fence.distanceToDestinationM

        // 단계마다 사용자가 알고 싶은 것이 다르다. 준비 중에는 "아직 안 나갔다"
        // 는 사실이, 이동 중에는 남은 거리가 중요하다. 준비 중에 목적지까지의
        // 거리를 보여 주면 집에서 그 숫자를 보며 초조해질 뿐 할 수 있는 것이 없다.
        val text = buildString {
            append(current.eventLine)
            append(" · ")
            when (fence.phase) {
                TripGeofence.Phase.BEFORE_DEPARTURE -> append("아직 출발 전")

                // 목적지 반경 안에 있으면 남은 거리(수십 m)는 알려 줄 것이
                // 없다. 대신 확인이 얼마나 진행됐는지를 보여 준다 — 서 있는
                // 동안 화면이 그대로면 앱이 멈춘 것처럼 보인다.
                TripGeofence.Phase.IN_TRANSIT -> {
                    val dwelled = fence.dwellElapsedMillis
                    when {
                        fence.awaitingDwell && dwelled != null -> {
                            append("도착 확인 중 ")
                            append(formatDwell(dwelled))
                        }

                        remaining != null -> {
                            append("목적지까지 ")
                            append(formatDistance(remaining))
                        }

                        else -> append("목적지로 가는 중")
                    }
                }

                TripGeofence.Phase.ARRIVED -> append("도착을 기록했다")
            }
        }
        updateNotification(phase.label, text, phase)
    }

    /**
     * 판정기 단계를 알림 문구로 옮긴다.
     *
     * 출발 판별 기준점이 없으면 판정기가 처음부터 [TripGeofence.Phase.IN_TRANSIT]
     * 로 시작한다. 그건 "출발을 알 수 없어서 건너뛴 것" 이지만 사용자에게는
     * 그대로 "이동 중" 으로 보여 주는 것이 맞다 — 준비 중이라고 하면 출발을
     * 기다리는 것처럼 보이는데 실제로는 아무것도 기다리지 않는다.
     */
    private fun phaseLabel(phase: TripGeofence.Phase): AlarmNotifications.TripPhaseLabel =
        when (phase) {
            TripGeofence.Phase.BEFORE_DEPARTURE -> AlarmNotifications.TripPhaseLabel.PREPARING
            TripGeofence.Phase.IN_TRANSIT -> AlarmNotifications.TripPhaseLabel.IN_TRANSIT
            TripGeofence.Phase.ARRIVED -> AlarmNotifications.TripPhaseLabel.ARRIVED
        }

    /**
     * 도착 결과를 알린다. 약속 시각과 비교해 얼마나 이르거나 늦었는지 말한다.
     *
     * 이 알림은 사용자가 "확인" 을 누를 때까지 남는다. 예전에는 결과를
     * 포그라운드 서비스 알림에 써 넣고 곧바로 [stopSelf] 했는데, 그러면
     * 시스템이 그 알림을 함께 치워서 **아무도 결과를 보지 못했다.**
     */
    private fun postArrivalResult(current: AlarmSchedule, fix: LocationFix) {
        val verdict = ArrivalVerdict(
            arrivedAtMillis = fix.atMillis,
            // 약속 시각은 일정 시작 시각이다. 계획이 추정한 도착 예정
            // (arriveAtMillis) 이 아니다 — 근거는 [ArrivalVerdict] 에 있다.
            appointmentMillis = current.startAtMillis,
        )

        val canPost = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED

        if (!canPost) {
            // 판정 자체는 이미 큐에 적혔다. 알림만 못 띄운다.
            Log.w(TAG, "알림 권한이 없어 도착 결과를 띄우지 못한다: ${verdict.headline}")
            return
        }

        val line = listOfNotNull(current.eventLine, current.placeName).joinToString(" · ")

        runCatching {
            NotificationManagerCompat.from(this).notify(
                AlarmNotifications.NOTIFICATION_ARRIVAL,
                AlarmNotifications.arrivalNotification(
                    context = this,
                    verdict = verdict,
                    eventLine = line,
                    confirmIntent = TripArrivalReceiver.confirmIntent(this),
                    dismissIntent = TripArrivalReceiver.dismissIntent(this),
                ),
            )
        }.onFailure { Log.e(TAG, "도착 결과 알림 실패", it) }

        Log.i(TAG, "도착 결과: ${verdict.headline} (${verdict.detail()})")
    }

    private fun initialNotificationText(current: AlarmSchedule): String = buildString {
        append(current.eventLine)
        append(" · ")
        append(
            when {
                current.canDetectDeparture && current.canDetectArrival ->
                    "집을 나서는 시각과 도착 시각을 기록함"

                current.canDetectArrival -> "도착 시각을 기록함"
                else -> "집을 나서는 시각을 기록함"
            }
        )
    }

    private fun formatDistance(meters: Double): String =
        if (meters >= 1000) "%.1fkm".format(meters / 1000)
        else "${meters.roundToInt()}m"

    /**
     * 체류 확인에 남은 시간.
     *
     * 지난 시간이 아니라 **남은 시간**을 쓴다. "40초 지남" 은 얼마나 더 서
     * 있어야 하는지 알려 주지 않는다. 알림 한 줄에 들어가야 하므로 분모까지
     * 붙이지 않는다.
     */
    private fun formatDwell(elapsedMillis: Long): String {
        val left = ((TripGeofence.DEFAULT_DWELL_MILLIS - elapsedMillis) / 1000)
            .coerceAtLeast(0)
        if (left == 0L) return "곧 확정"
        if (left < 60) return "${left}초 남음"
        return "${left / 60}분 ${left % 60}초 남음"
    }

    private fun stopPendingIntent(): PendingIntent = PendingIntent.getService(
        this,
        0,
        Intent(this, TripTrackingService::class.java).setAction(ACTION_STOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        private const val TAG = "TripTrackingService"

        const val ACTION_START = "com.swpp.wakeup.action.TRIP_START"
        const val ACTION_STOP = "com.swpp.wakeup.action.TRIP_STOP"
        private const val EXTRA_SCHEDULE = "schedule"

        /** 출발을 기다리는 동안. 150m 를 구분하면 되므로 느슨하게 본다. */
        private const val IDLE_INTERVAL_MS = 30_000L

        /** 이동 중. 50m 반경을 놓치지 않으려면 자주 봐야 한다. */
        private const val TRANSIT_INTERVAL_MS = 10_000L

        /**
         * 추적을 시작한다. 알람 해제 시점에 호출된다.
         *
         * 호출 전에 [LocationPermissions.granted] 를 확인해야 한다. 권한이
         * 없으면 서비스가 스스로 멈추지만, 그러면 사용자에게 이유를 알릴 기회가
         * 없다.
         */
        fun start(context: Context, schedule: AlarmSchedule) {
            if (!schedule.canTrack) {
                Log.i(TAG, "기준점이 없어 추적하지 않는다 (집·목적지 좌표 없음)")
                return
            }
            val intent = Intent(context, TripTrackingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_SCHEDULE, Gson().toJson(schedule))
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            // 로그아웃에서는 저장소를 지우기 전에 생산자를 동기적으로 멈춰야
            // 한다. ACTION_STOP startService는 전달되기 전에 wipe가 끝나 경로를
            // 다시 쓰는 경합이 생길 수 있으므로 stopService를 쓴다.
            context.stopService(Intent(context, TripTrackingService::class.java))
        }
    }
}
