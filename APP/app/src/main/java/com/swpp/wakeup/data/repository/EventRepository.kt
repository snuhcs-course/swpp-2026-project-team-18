package com.swpp.wakeup.data.repository

import android.util.Log
import com.swpp.wakeup.BuildConfig
import com.swpp.wakeup.data.remote.AlarmPlanDto
import com.swpp.wakeup.data.remote.ApiClient
import com.swpp.wakeup.data.remote.EventCreateRequest
import com.swpp.wakeup.data.remote.EventDto
import com.swpp.wakeup.data.remote.EventTagDto
import com.swpp.wakeup.data.remote.EventsApi
import com.swpp.wakeup.data.remote.PlaceInput
import com.swpp.wakeup.data.remote.PlaceSearchItem
import com.swpp.wakeup.data.remote.ProfileApi
import com.swpp.wakeup.data.remote.ProfileDto
import com.swpp.wakeup.data.remote.ProfileUpdateRequest
import com.swpp.wakeup.data.remote.RouteCandidateDto
import com.swpp.wakeup.domain.model.AlarmPlanView
import com.swpp.wakeup.domain.model.AlarmSchedule
import com.swpp.wakeup.domain.model.EventSection
import com.swpp.wakeup.domain.model.PlanRow
import com.swpp.wakeup.domain.model.RouteChoice
import com.swpp.wakeup.domain.model.RouteOption
import com.swpp.wakeup.domain.model.UpcomingEvent
import retrofit2.Response
import java.io.IOException
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * 일정 저장소.
 *
 * 규칙 — **예외를 밖으로 던지지 않는다.** 실패는 [Result.Failure] 로 감싼다.
 * `AuthRepository` 와 같은 방침이고 back-spec 7절의 서버측 규칙과 같은 방향이다.
 *
 * 서버 → 화면 매핑이 전부 여기에 있다. 화면은 표시 문자열만 받는다.
 */
class EventRepository(
    private val api: EventsApi = ApiClient.events,
    private val profileApi: ProfileApi = ApiClient.profile,
) {

    sealed interface Result<out T> {
        data class Success<T>(val data: T) : Result<T>
        data class Failure(val message: String) : Result<Nothing>
    }

    /** 홈 화면이 한 번에 필요한 것. */
    data class HomeData(
        val sections: List<EventSection>,
        val nextAlarm: UpcomingEvent?,
        val totalCount: Int,
        val hasHome: Boolean,
        val homeLabel: String?,
        /** 알람이 계산되지 않은 일정 수. 원인 안내에 쓴다 */
        val unplannedCount: Int,
        /**
         * 실제로 `AlarmManager` 에 등록할 목록.
         *
         * 화면용 [sections] 와 따로 만든다. 등록에는 epoch 밀리초와 좌표가
         * 필요한데 표시 문자열("7:40")에서는 되돌릴 수 없다.
         */
        val schedules: List<AlarmSchedule>,
    )

    suspend fun loadHome(): Result<HomeData> = guard {
        val profile = unwrap(profileApi.get()) ?: return@guard Result.Failure(MESSAGE_UNKNOWN)
        val page = unwrap(api.list()) ?: return@guard Result.Failure(MESSAGE_UNKNOWN)
        val events = page.results

        val zone = ZoneId.systemDefault()
        val mapped = events.mapNotNull { it.toUpcoming(zone) }
        val sorted = mapped.sortedBy { it.startAtEpochSecond }

        Result.Success(
            HomeData(
                sections = groupByDay(sorted, zone),
                nextAlarm = sorted.firstOrNull { it.alarmAt != null },
                totalCount = sorted.size,
                hasHome = profile.hasHome,
                homeLabel = profile.homeLabel?.takeIf { it.isNotBlank() },
                unplannedCount = sorted.count { it.alarmAt == null },
                schedules = events.mapNotNull { it.toSchedule(zone, profile) },
            )
        )
    }

    suspend fun createEvent(
        title: String,
        startAt: OffsetDateTime,
        place: PlaceSearchItem?,
        tagKey: String?,
        routeKey: String? = null,
        origin: PlaceSearchItem? = null,
    ): Result<EventDto> = guard {
        val body = EventCreateRequest(
            title = title.trim(),
            startAt = startAt.toString(),
            place = place?.let {
                PlaceInput(
                    name = it.name,
                    lat = it.lat,
                    lng = it.lng,
                    address = it.address,
                    kakaoPlaceId = it.kakaoPlaceId,
                )
            },
            tagKey = tagKey,
            routeKey = routeKey?.takeIf { it.isNotBlank() },
            // 경로 선택 때 쓴 출발지를 그대로 보낸다. 빠뜨리면 서버가 집 기준으로
            // 계산해 사용자가 본 소요시간과 달라진다.
            originLat = origin?.lat,
            originLng = origin?.lng,
            originLabel = origin?.name,
        )
        val response = api.create(body)
        unwrap(response)?.let { Result.Success(it) } ?: Result.Failure(errorMessage(response))
    }

    /**
     * 경로 후보 조회.
     *
     * 서버가 외부 API 를 최대 4번 부르므로 사용자가 "경로 고르기" 를 눌렀을
     * 때만 호출한다.
     *
     * [origin] 이 null 이면 출발지를 보내지 않고 서버가 프로필 집을 쓴다.
     * 사용자가 출발지를 바꿨으면 그 좌표를 보낸다.
     */
    suspend fun routeCandidates(
        destination: PlaceSearchItem,
        origin: PlaceSearchItem? = null,
    ): Result<RouteChoice> = guard {
        val response = api.routeCandidates(
            destLat = destination.lat,
            destLng = destination.lng,
            originLat = origin?.lat,
            originLng = origin?.lng,
            originLabel = origin?.name,
        )
        val body = unwrap(response) ?: return@guard Result.Failure(errorMessage(response))

        val options = body.results.map { it.toOption() }
        if (options.isEmpty()) {
            return@guard Result.Failure("경로를 찾지 못했다. 장소를 다시 확인한다.")
        }
        Result.Success(
            RouteChoice(
                originLabel = body.origin?.label?.takeIf { it.isNotBlank() }
                    ?: origin?.name ?: "집",
                destination = destination.name,
                options = options,
                selectedKey = options.firstOrNull()?.key,
                // 사용자가 고른 출발지만 좌표를 남긴다. 집을 쓴 경우 null 이라
                // 일정 생성 때 출발지를 보내지 않고 서버 기본값에 맡긴다.
                originLat = origin?.lat,
                originLng = origin?.lng,
            )
        )
    }

    /**
     * 현재 위치의 주소. 경로 선택 화면의 출발지 기본값에 쓴다.
     *
     * 주소가 없는 좌표(바다·국외)는 실패가 아니다. 그 경우 [Result.Success] 에
     * null 이 담긴다 — 화면은 "현재 위치를 쓸 수 없음" 으로 안내하고 사용자가
     * 직접 검색하게 둔다.
     */
    suspend fun reversePlace(lat: Double, lng: Double): Result<PlaceSearchItem?> = guard {
        val response = api.reversePlace(lat, lng)
        val body = unwrap(response) ?: return@guard Result.Failure(errorMessage(response))
        if (body.degraded) {
            return@guard Result.Failure("현재 위치의 주소를 불러오지 못했다.")
        }
        Result.Success(body.result)
    }

    suspend fun deleteEvent(id: Long): Result<Unit> = guard {
        val response = api.delete(id)
        if (response.isSuccessful) Result.Success(Unit)
        else Result.Failure(errorMessage(response))
    }

    suspend fun searchPlaces(query: String): Result<List<PlaceSearchItem>> = guard {
        unwrap(api.searchPlaces(query))?.let { Result.Success(it.results) }
            ?: Result.Failure(MESSAGE_UNKNOWN)
    }

    suspend fun tags(): Result<List<EventTagDto>> = guard {
        unwrap(api.tags())?.let { Result.Success(it) } ?: Result.Failure(MESSAGE_UNKNOWN)
    }

    suspend fun setHome(
        label: String,
        lat: Double,
        lng: Double,
        prepMinutes: Int?,
    ): Result<ProfileDto> = guard {
        val body = ProfileUpdateRequest(
            homeLat = lat,
            homeLng = lng,
            homeLabel = label,
            onboardingPrepMin = prepMinutes,
        )
        unwrap(profileApi.update(body))?.let { Result.Success(it) }
            ?: Result.Failure(MESSAGE_UNKNOWN)
    }

    /**
     * 알람 결정 화면(Figma ④)이 필요한 상세.
     *
     * 목록을 훑어 찾지 않고 상세 엔드포인트를 부른다. 목록은 페이지 경계가
     * 있어 21번째 일정부터는 찾지 못한다.
     */
    suspend fun loadPlan(eventId: Long): Result<AlarmPlanView> = guard {
        val response = api.get(eventId)
        val dto = unwrap(response)
            ?: return@guard Result.Failure(errorMessage(response))
        dto.toPlanView(ZoneId.systemDefault())?.let { Result.Success(it) }
            ?: Result.Failure("알람이 아직 계산되지 않았다.")
    }

    // --- 내부 -------------------------------------------------------------

    /**
     * 예외를 [Result.Failure] 로 바꾼다.
     *
     * 개발 빌드에서는 예외 종류를 문구에 붙인다. 그냥 "알 수 없는 오류" 만
     * 띄우면 파싱 실패와 통신 실패를 화면에서 구분할 수 없다. 실제로 응답
     * 스키마가 어긋났을 때 이 때문에 원인 찾기가 늦어졌다.
     */
    private inline fun <T> guard(block: () -> Result<T>): Result<T> = try {
        block()
    } catch (e: IOException) {
        Log.w(TAG, "network failure", e)
        Result.Failure(MESSAGE_NETWORK)
    } catch (e: Exception) {
        Log.e(TAG, "unexpected failure", e)
        Result.Failure(
            if (BuildConfig.DEV_TOOLS) {
                "${MESSAGE_UNKNOWN} (${e.javaClass.simpleName})"
            } else {
                MESSAGE_UNKNOWN
            }
        )
    }

    private fun <T> unwrap(response: Response<T>): T? =
        if (response.isSuccessful) response.body() else null

    private fun errorMessage(response: Response<*>): String =
        ApiClient.parseErrorMessage(response.errorBody()?.string())
            ?: when (response.code()) {
                400 -> "입력값을 확인해야 한다."
                401 -> "다시 로그인해야 한다."
                404 -> "대상을 찾을 수 없다."
                in 500..599 -> "서버에 문제가 생겼다."
                else -> MESSAGE_UNKNOWN
            }

    private companion object {
        const val TAG = "EventRepository"
        const val MESSAGE_NETWORK = "서버에 연결할 수 없다. 네트워크와 서버 상태를 확인한다."
        const val MESSAGE_UNKNOWN = "알 수 없는 오류가 발생했다."
    }
}

// ---------------------------------------------------------------------------
// 매핑
// ---------------------------------------------------------------------------

private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")
private val ALARM_FORMAT = DateTimeFormatter.ofPattern("H:mm")
private val DATE_FORMAT = DateTimeFormatter.ofPattern("M월 d일")
private val DAY_NAMES = listOf("월", "화", "수", "목", "금", "토", "일")

private fun dayLabel(date: LocalDate): String = DAY_NAMES[date.dayOfWeek.value - 1]

/**
 * 경로 후보를 화면용으로 바꾼다.
 *
 * 서버가 이미 `summary`("23분 · 5.1km · 환승 1회 · 1,550원")를 만들어 준다.
 * 여기서 다시 조립하면 같은 규칙이 두 곳에 생긴다. 소요시간은 카드 상단에
 * 크게 따로 쓰므로 요약에서 앞의 "N분 · " 만 떼어 쓴다.
 */
private fun RouteCandidateDto.toOption(): RouteOption {
    val summaryTail = (summary ?: "")
        .removePrefix("${minutes}분")
        .removePrefix(" · ")
        .trim()

    val detailLine = listOf(detail?.trim().orEmpty(), summaryTail)
        .filter { it.isNotBlank() }
        .joinToString(" · ")

    return RouteOption(
        key = key,
        mode = mode,
        minutes = minutes,
        minutesLabel = "${minutes}분",
        detailLine = detailLine.ifBlank { summary.orEmpty() },
        badge = reason?.takeIf { it.isNotBlank() },
    )
}

/** 서버는 ISO 8601 로 준다. 파싱 실패한 항목은 목록에서 뺀다. */
private fun EventDto.toUpcoming(zone: ZoneId): UpcomingEvent? {
    val start = runCatching { OffsetDateTime.parse(startAt) }.getOrNull() ?: return null
    val local = start.atZoneSameInstant(zone)
    val plan = alarmPlan

    val alarmLocal = plan?.alarmAt
        ?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }
        ?.atZoneSameInstant(zone)

    val routeText = when {
        plan == null -> place?.address?.takeIf { it.isNotBlank() }
        plan.status == AlarmPlanDto.STATUS_OK -> buildString {
            append(place?.name ?: "장소 없음")
            plan.routeSummary?.takeIf { it.isNotBlank() }?.let {
                append(" · ")
                append(plan.travelMode?.takeIf(String::isNotBlank) ?: "이동")
                append(" ")
                append(it)
            }
        }
        else -> buildString {
            place?.name?.let { append(it); append(" · ") }
            append(plan.statusLabel ?: "알람 계산 불가")
        }
    }

    return UpcomingEvent(
        id = id,
        startTime = local.format(TIME_FORMAT),
        dayLabel = dayLabel(local.toLocalDate()),
        title = title,
        placeAndRoute = routeText ?: "장소 없음",
        alarmAt = alarmLocal?.format(ALARM_FORMAT),
        onTimeProbability = plan?.onTimeProbability,
        tag = tag?.label,
        planStatus = plan?.status ?: AlarmPlanDto.STATUS_NO_PLACE,
        planStatusLabel = plan?.statusLabel,
        startAtEpochSecond = start.toEpochSecond(),
        startDate = local.toLocalDate(),
    )
}

private fun EventDto.toPlanView(zone: ZoneId): AlarmPlanView? {
    val plan = alarmPlan ?: return null
    val start = runCatching { OffsetDateTime.parse(startAt) }.getOrNull() ?: return null
    val startLocal = start.atZoneSameInstant(zone)

    val alarmLocal = plan.alarmAt
        ?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }
        ?.atZoneSameInstant(zone)
    val arriveLocal = plan.arriveAt
        ?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }
        ?.atZoneSameInstant(zone)

    // 근거 한 줄에 **출처**를 함께 적는다. 세 값의 신뢰도가 다르다 —
    // 이동 시간만 카카오 실측이고 준비·버퍼는 아직 고정값이다. 구분해 주지
    // 않으면 셋 다 학습된 값처럼 읽힌다.
    val rows = buildList {
        plan.prepMinutes?.let {
            val note = when (plan.prepSource) {
                "onboarding" -> "고정값 · 집 설정에서 답한 값. 관측이 쌓이면 학습값으로 바뀜"
                else -> "고정값 · 기본 30분. 집 설정에서 바꿀 수 있음"
            }
            add(PlanRow("준비 시간", it, note, PlanRow.Kind.PREP))
        }
        plan.travelMinutes?.let {
            val label = plan.travelMode?.takeIf(String::isNotBlank)?.let { m -> "$m 이동" }
                ?: "이동 시간"
            val bits = buildList {
                add("실측")
                plan.routeDetail?.takeIf(String::isNotBlank)?.let(::add)
                plan.routeSummary?.takeIf(String::isNotBlank)?.let(::add)
            }
            add(PlanRow(label, it, bits.joinToString(" · "), PlanRow.Kind.TRAVEL))
        }
        plan.bufferMinutes?.let {
            add(PlanRow("안전 버퍼", it, "고정값 · 문 앞에서 실제 출발까지의 여유", PlanRow.Kind.BUFFER))
        }
    }

    val today = LocalDate.now(zone)
    val days = ChronoUnit.DAYS.between(today, startLocal.toLocalDate())
    val whenLabel = when (days) {
        0L -> "오늘"
        1L -> "내일 아침"
        else -> "${days}일 뒤"
    }

    val remaining = alarmLocal?.let {
        val minutes = Duration.between(OffsetDateTime.now(zone), it).toMinutes()
        if (minutes <= 0) "지난 알람" else "${minutes / 60}시간 ${minutes % 60}분 남음"
    }

    return AlarmPlanView(
        eventId = id,
        whenLabel = whenLabel,
        dateLabel = "${startLocal.format(DATE_FORMAT)} ${dayLabel(startLocal.toLocalDate())}",
        eventTitle = "${startLocal.format(TIME_FORMAT)} $title",
        eventPlace = place?.let { p ->
            listOfNotNull(p.name, p.address?.takeIf(String::isNotBlank)).joinToString(" · ")
        } ?: "장소 없음",
        sensitivityTag = tag?.label,
        alarmAt = alarmLocal?.format(ALARM_FORMAT),
        meridiem = alarmLocal?.let { if (it.hour < 12) "AM" else "PM" } ?: "",
        remaining = remaining,
        onTimeProbability = plan.onTimeProbability,
        tauUsed = plan.tauUsed,
        breakdown = rows,
        totalMinutes = plan.totalMinutes,
        arrivalLine = arriveLocal?.let { "${it.format(ALARM_FORMAT)} 도착 예정" },
        status = plan.status,
        statusLabel = plan.statusLabel,
        routeKey = plan.routeKey?.takeIf { it.isNotBlank() },
        // false 일 때만 알린다. null(고른 적 없음)과 true(그대로 쓰임)는
        // 사용자가 알 필요가 없다.
        routeFellBack = plan.routeChoiceHonored == false,
    )
}

/**
 * 알람 등록·이동 추적에 쓸 형태로 바꾼다.
 *
 * **계산되지 않은 계획은 건너뛴다.** 알람 시각이 없으면 등록할 것이 없다.
 * 집 위치가 없거나 장소가 없어 `status != ok` 인 일정이 그렇다. 이때 임의의
 * 시각을 만들어 등록하면 엉뚱한 시간에 울린다.
 *
 * 좌표는 판별 기준점이다 — 집은 출발, 일정 장소는 도착. 둘 다 없으면 알람은
 * 울리지만 추적은 하지 않는다([AlarmSchedule.canTrack]).
 */
private fun EventDto.toSchedule(zone: ZoneId, profile: ProfileDto): AlarmSchedule? {
    val plan = alarmPlan ?: return null
    if (plan.status != AlarmPlanDto.STATUS_OK) return null

    val alarm = plan.alarmAt
        ?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }
        ?: return null
    val start = runCatching { OffsetDateTime.parse(startAt) }.getOrNull() ?: return null

    val alarmLocal = alarm.atZoneSameInstant(zone)
    val startLocal = start.atZoneSameInstant(zone)
    val arriveLocal = plan.arriveAt
        ?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }
        ?.atZoneSameInstant(zone)

    return AlarmSchedule(
        eventId = id,
        alarmAtMillis = alarm.toInstant().toEpochMilli(),
        departByMillis = plan.departBy
            ?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }
            ?.toInstant()?.toEpochMilli(),
        arriveAtMillis = plan.arriveAt
            ?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }
            ?.toInstant()?.toEpochMilli(),
        startAtMillis = start.toInstant().toEpochMilli(),
        alarmLabel = alarmLocal.format(ALARM_FORMAT),
        meridiem = if (alarmLocal.hour < 12) "AM" else "PM",
        eventLine = "${startLocal.format(TIME_FORMAT)} $title",
        placeName = place?.let { p ->
            listOfNotNull(p.name, p.address?.takeIf(String::isNotBlank)).joinToString(" · ")
        },
        arrivalLine = arriveLocal?.let { "${it.format(ALARM_FORMAT)} 도착 예정" },
        homeLat = profile.homeLat,
        homeLng = profile.homeLng,
        destLat = place?.lat,
        destLng = place?.lng,
    )
}

/** 날짜별로 묶고 오늘·내일에 이름을 붙인다. */
private fun groupByDay(events: List<UpcomingEvent>, zone: ZoneId): List<EventSection> {
    if (events.isEmpty()) return emptyList()
    val today = LocalDate.now(zone)

    return events
        .groupBy { it.startDate }
        .toSortedMap()
        .map { (date, list) ->
            val label = when (ChronoUnit.DAYS.between(today, date)) {
                0L -> "오늘"
                1L -> "내일"
                else -> "${date.format(DATE_FORMAT)} ${dayLabel(date)}"
            }
            EventSection(
                label = label,
                dateLabel = "${date.format(DATE_FORMAT)} ${dayLabel(date)}",
                events = list,
            )
        }
}
