package com.swpp.wakeup.data.remote

import com.google.gson.Gson
import com.swpp.wakeup.data.repository.toOption
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 서버 응답에 키가 없을 때 DTO 가 터지지 않는지 본다.
 *
 * ## 무슨 일이 있었나
 *
 * 경로 후보에 `segments` 를 추가하면서 이렇게 썼다.
 *
 * ```kotlin
 * val segments: List<RouteSegmentDto> = emptyList()
 * ```
 *
 * 그런데 **Gson 은 Kotlin 의 기본값을 모른다.** 생성자에 기본값 없는 인자가
 * 하나라도 있으면 Kotlin 은 무인자 생성자를 만들지 않고, 그러면 Gson 은
 * `Unsafe.allocateInstance` 로 객체를 만들고 필드를 리플렉션으로 채운다.
 * JSON 에 키가 없으면 그 필드는 **선언이 non-null 이어도 null 이다.**
 * 컴파일러는 non-null 이라 믿고 널 검사를 지우므로 첫 접근에서 죽는다.
 *
 * 배포 서버가 아직 그 필드를 내리지 않던 상태였고, 실기기에서 경로 선택
 * 화면이 "알 수 없는 오류(NullPointerException)" 로 떨어졌다. 단위 테스트
 * 214개와 lint 가 전부 초록이었다 — 테스트가 DTO 를 손으로 만들어서 Gson 을
 * 통과하지 않았기 때문이다.
 *
 * 디스크 쪽은 `DiskCompat` 이 같은 함정을 막고 있었는데 네트워크 DTO 는 그
 * 보호 밖에 있었다. 그래서 여기서 **실제 Gson 으로 역직렬화해** 본다.
 *
 * ## 무엇을 검사하지 않는가
 *
 * 필드마다 의미가 맞는지는 보지 않는다. 여기서 보는 것은 하나다 —
 * **키가 없어도 접근할 수 있는가.**
 */
class NetworkDtoNullSafetyTest {

    private val gson = Gson()

    @Test
    fun `구간 필드가 없는 응답도 읽을 수 있다`() {
        // 배포 서버 0.3.0 이 실제로 이 모양을 준다.
        val json = """
            {"key":"transit:5511","kind":"transit","mode":"버스","minutes":25,
             "distance_m":4412,"transfers":0,"fare":1500,
             "detail":"5511","summary":"25분 · 4.4km · 1,500원",
             "reason":"가장 빠름","source":"kakao_transit"}
        """.trimIndent()

        val dto = gson.fromJson(json, RouteCandidateDto::class.java)

        // 여기서 NPE 가 나면 경로 화면이 죽는다.
        assertNull("키가 없으면 null 이어야 한다. 기본값은 Gson 이 무시한다", dto.segments)
        assertTrue("null 을 빈 목록으로 다룰 수 있어야 한다", dto.segments.orEmpty().isEmpty())
    }

    @Test
    fun `구간 안의 필드가 없어도 읽을 수 있다`() {
        val json = """{"seconds":600}"""

        val dto = gson.fromJson(json, RouteSegmentDto::class.java)

        assertNull(dto.kind)
        assertNull(dto.label)
        assertNull(dto.vehicle)
        assertNull(dto.vehicleType)
        assertNull(dto.region)
        assertNull(dto.stops)
        assertNull(dto.guidance)
        assertNull(dto.arrivals)
        assertNull(dto.headwayMinutes)
    }

    @Test
    fun `구간이 있는 응답은 그대로 읽는다`() {
        val json = """
            {"key":"transit:5511","mode":"버스","minutes":25,"fare":1500,
             "segments":[
               {"kind":"walk","seconds":240,"label":"도보"},
               {"kind":"bus","seconds":912,"label":"5511","vehicle":"5511",
                "vehicle_type":"지선","region":"metro_seoul",
                "stops":["제2공학관","관악구청"],"guidance":"5511 (제2공학관 > 관악구청)",
                "headway_minutes":12,
                "arrivals":[
                  {"seconds":536,"message":"8분 56초 뒤 도착","source":"seoul_bus","crowding":"여유"},
                  {"seconds":930,"message":"15분 30초 뒤 도착","source":"seoul_bus"}
                ]}
             ]}
        """.trimIndent()

        val dto = gson.fromJson(json, RouteCandidateDto::class.java)
        val segments = dto.segments.orEmpty()

        assertTrue("구간 2개를 읽어야 한다", segments.size == 2)
        assertTrue(segments[0].kind == "walk")
        assertTrue(segments[1].kind == "bus")
        // snake_case 를 @SerializedName 으로 잇는다. 빠뜨리면 색이 안 나온다.
        assertTrue("vehicle_type 이 매핑되지 않았다", segments[1].vehicleType == "지선")
        assertTrue(segments[1].region == "metro_seoul")
        assertTrue(segments[1].stops == listOf("제2공학관", "관악구청"))
        assertTrue(segments[1].headwayMinutes == 12)
        assertTrue(segments[1].arrivals.orEmpty().size == 2)
        assertTrue(segments[1].arrivals.orEmpty()[0].seconds == 536)
        assertTrue(segments[1].arrivals.orEmpty()[0].crowding == "여유")
    }

    @Test
    fun `경로 응답 전체가 비어도 읽을 수 있다`() {
        // 서버가 degraded 로 빈 응답을 줄 수 있다.
        val dto = gson.fromJson("{}", RouteCandidateResponse::class.java)

        // results 도 같은 함정에 있다. null 을 견디는지 확인한다.
        assertTrue(runCatching { dto.results.orEmpty().size }.isSuccess)
    }

    /**
     * **이게 실제 회귀 시험이다.**
     *
     * 위의 `assertNull` 들은 Gson 의 동작을 확인할 뿐이라 선언을 non-null 로
     * 되돌려도 통과한다. 터지는 자리는 그 값을 쓰는 변환 함수이므로 그것을
     * 직접 태운다. 누군가 `segments.orEmpty()` 를 `segments.mapNotNull` 로
     * 되돌리면 여기서 `NullPointerException` 이 난다.
     */
    @Test
    fun `구간 없는 응답을 화면용으로 바꿔도 터지지 않는다`() {
        val json = """
            {"key":"transit:5511","kind":"transit","mode":"버스","minutes":25,
             "distance_m":4412,"transfers":0,"fare":1500,"detail":"5511",
             "summary":"25분 · 4.4km · 1,500원","reason":"가장 빠름"}
        """.trimIndent()
        val dto = gson.fromJson(json, RouteCandidateDto::class.java)

        val option = dto.toOption()

        assertTrue("구간이 없으면 막대를 그리지 않아야 한다", !option.hasSegmentBar)
        assertTrue(option.segments.isEmpty)
        assertTrue(option.minutes == 25)
    }

    @Test
    fun `구간 있는 응답이 화면용으로 제대로 넘어온다`() {
        val json = """
            {"key":"transit:5511","mode":"버스","minutes":25,"fare":1500,
             "segments":[
               {"kind":"walk","seconds":240,"label":"도보"},
               {"kind":"bus","seconds":912,"label":"5511","vehicle":"5511",
                "vehicle_type":"지선","region":"metro_seoul",
                "stops":["제2공학관","관악구청"],"headway_minutes":12,
                "arrivals":[
                  {"seconds":536,"message":"8분 56초 뒤 도착","source":"seoul_bus","crowding":"여유"},
                  {"seconds":930,"message":"15분 30초 뒤 도착","source":"seoul_bus"}
                ]},
               {"kind":"walk","seconds":330,"label":"도보"}
             ]}
        """.trimIndent()

        val option = gson.fromJson(json, RouteCandidateDto::class.java).toOption()

        assertTrue("막대를 그려야 한다", option.hasSegmentBar)
        assertTrue(option.segments.items.size == 3)
        // 색을 고르는 값이 여기까지 살아 와야 한다.
        assertTrue(option.segments.items[1].busType == "지선")
        assertTrue(option.segments.items[1].lineName == "5511")
        assertTrue(option.segments.items[1].region == "metro_seoul")
        assertTrue(option.segments.items[1].stops == listOf("제2공학관", "관악구청"))
        assertTrue(option.segments.items[1].headwayMinutes == 12)
        assertTrue(option.segments.items[1].arrivals.size == 2)
        assertTrue(option.segments.items[1].arrivals[0].seconds == 536)
        assertTrue(option.segments.items[1].arrivals[0].crowding == "여유")
        assertTrue(option.hasCheckpoints)
        // 비율 합은 언제나 1이다.
        assertTrue(kotlin.math.abs(option.segments.weights().sum() - 1f) < 1e-5f)
    }

    @Test
    fun `지하철 급행과 막차가 화면용으로 넘어온다`() {
        // snake_case 두 개를 @SerializedName 으로 이어야 한다. 빠뜨리면 배지가
        // 조용히 안 나온다 — 응답은 200 이고 도착 시각도 맞으니 아무도 모른다.
        val json = """
            {"key":"transit:2호선","mode":"지하철","minutes":23,"fare":1550,
             "segments":[
               {"kind":"subway","seconds":420,"label":"2호선","vehicle":"2호선",
                "region":"metro_seoul","stops":["신림","봉천"],
                "arrivals":[
                  {"seconds":110,"message":"1분 50초 뒤 도착","source":"seoul_subway",
                   "train_kind":"급행","last_train":false},
                  {"seconds":210,"message":"3분 30초 뒤 도착","source":"seoul_subway",
                   "train_kind":null,"last_train":true}
                ]}
             ]}
        """.trimIndent()

        val arrivals = gson.fromJson(json, RouteCandidateDto::class.java)
            .toOption().segments.items[0].arrivals

        assertTrue("급행이 넘어오지 않았다", arrivals[0].trainKind == "급행")
        assertTrue(!arrivals[0].lastTrain)
        // train_kind 가 null 이면 빈 문자열이어야 한다. null 이 새면 배지에서 터진다.
        assertTrue("null 등급이 빈 문자열이 아니다", arrivals[1].trainKind == "")
        assertTrue("막차가 넘어오지 않았다", arrivals[1].lastTrain)
    }

    @Test
    fun `등급 필드가 없는 구버전 응답도 읽는다`() {
        // 배포 서버가 아직 재배포 전일 수 있다. 그때는 배지만 없어야 하고
        // 도착 시각은 그대로 나와야 한다.
        val json = """
            {"key":"transit:2호선","mode":"지하철","minutes":23,
             "segments":[
               {"kind":"subway","seconds":420,"label":"2호선","vehicle":"2호선",
                "region":"metro_seoul","stops":["신림","봉천"],
                "arrivals":[{"seconds":110,"message":"1분 50초 뒤 도착","source":"seoul_subway"}]}
             ]}
        """.trimIndent()

        val arrival = gson.fromJson(json, RouteCandidateDto::class.java)
            .toOption().segments.items[0].arrivals[0]

        assertTrue(arrival.seconds == 110)
        assertTrue(arrival.trainKind == "")
        assertTrue(!arrival.lastTrain)
    }

    @Test
    fun `종류를 모르는 구간도 버리지 않는다`() {
        // 서버가 수단을 추가했을 때 막대 합이 소요시간과 어긋나면 안 된다.
        val json = """
            {"key":"x","mode":"기타","minutes":20,
             "segments":[{"kind":"ferry","seconds":600},{"kind":"walk","seconds":600}]}
        """.trimIndent()

        val option = gson.fromJson(json, RouteCandidateDto::class.java).toOption()

        assertTrue(option.segments.items.size == 2)
        assertTrue(option.segments.totalSeconds == 1200)
    }

    @Test
    fun `시간이 0인 구간은 버린다`() {
        val json = """
            {"key":"x","mode":"버스","minutes":10,
             "segments":[{"kind":"walk","seconds":0},{"kind":"bus","seconds":600}]}
        """.trimIndent()

        val option = gson.fromJson(json, RouteCandidateDto::class.java).toOption()

        // 폭 0 인 칸은 색만 한 줄 끼어 경계선처럼 보인다.
        assertTrue(option.segments.items.size == 1)
    }
}
