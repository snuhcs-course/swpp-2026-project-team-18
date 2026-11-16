// AI-generated (Claude)
package com.swpp.wakeup.ui.home

import com.swpp.wakeup.data.remote.PlaceSearchItem
import com.swpp.wakeup.data.repository.EventRepository
import com.swpp.wakeup.domain.model.SavedHome
import com.swpp.wakeup.domain.model.SavedHomes
import com.swpp.wakeup.sensing.GeoPoint
import kotlin.math.abs

/*
 * 일정 추가 · 수정 폼의 출발지 · 도착지 · 경로를 바꾸는 순수 함수.
 *
 * `HomeViewModel` 은 AndroidViewModel 이라 JVM 단위 테스트로 만들 수 없다. 바꿀지 말지와 무엇을 지울지만
 * 여기로 빼서 `AddEndpointsTest` 가 본다.
 *
 * 고른 경로는 출발지 · 도착지와 짝이다. 끝점이 **실제로 바뀌면** 경로를 버리고, 같은 곳을 다시 고르면
 * 아무것도 지우지 않는다. 예전에는 이미 주황인 [집] 을 다시 눌러도 경로 · 오류 줄 · 검색을 지웠고, 일정
 * 수정에서는 제목만 고쳐도 `route_key: ""` 가 나가 고른 경로가 최단 경로로 바뀌었다.
 */

// AI-generated (Claude)
/**
 * 같은 끝점인가. 둘 다 없으면(집 출발 · 도착지 없음) 같다.
 *
 * 둘 다 카카오 id 가 있으면 id 로 본다 — 한 건물의 다른 가게는 좌표가 같아도 다른 도착지다. 한쪽이라도 없으면 좌표가
 * 똑같은지로 본다. 서버는 출발지를 좌표와 이름으로만 두어 일정 수정 폼의 출발지에는 id 가 없다. 예전에는 id 가 없는
 * 쪽을 좌표 · 이름 키로 봐서, 같은 장소를 다시 검색해 골라도 늘 바뀐 것이 되어 고른 경로가 지워지고 수정 저장이
 * `route_key: ""` 를 보냈다. 서버는 앱이 보낸 좌표를 그대로 돌려주므로 같은 장소면 좌표가 똑같다. 좌표 근사(10m)로
 * 보지 않는다.
 *
 * 이름은 보지 않는다. 서버가 돌려주는 이름은 검색에서 본 이름과 다를 수 있고(80자에서 자름 · 비면 "출발지" · 저장한
 * 집은 그 이름), 수정 저장도 끝점이 바뀌었는지를 좌표로만 본다([com.swpp.wakeup.data.repository.EventEditPatch]).
 * 이름만 달라 바뀐 것으로 치면 서버에는 아무것도 바뀌지 않는데 고른 경로만 지워진다. 집 · 저장한 집은 여기서 가르지
 * 않고 [sameDestination] 의 집 표시와 [sameOrigin] 의 저장한 집 id 로 가른다.
 */
internal fun sameEndpoint(a: PlaceSearchItem?, b: PlaceSearchItem?): Boolean {
    if (a == null || b == null) return a == null && b == null
    val idA = a.kakaoPlaceId?.takeIf { it.isNotBlank() }
    val idB = b.kakaoPlaceId?.takeIf { it.isNotBlank() }
    return if (idA != null && idB != null) idA == idB else a.lat == b.lat && a.lng == b.lng
}

/** [item] · [isHome] 이 지금 도착지와 같은가. 같으면 [withDestination] 이 아무것도 바꾸지 않는다 */
internal fun HomeViewModel.AddState.sameDestination(item: PlaceSearchItem?, isHome: Boolean): Boolean =
    isHome == destinationIsHome && sameEndpoint(selectedPlace, item)

/**
 * 도착지를 [item] 으로 바꾼 폼. [isHome] 은 [집] 으로 골랐는가다(좌표가 같아도 검색한 장소는 일반 목적지다).
 *
 * 장소가 바뀌면 고른 경로를 버린다. 목적지가 달라졌는데 이전 경로 key 를 들고 있으면 서버가 그 노선을
 * 찾지 못해 `route_failed` 가 되거나, 운 나쁘면 엉뚱한 노선에 맞아 버린다. 같은 도착지면 이 폼을 그대로
 * 돌려준다([sameDestination]).
 */
internal fun HomeViewModel.AddState.withDestination(item: PlaceSearchItem?, isHome: Boolean): HomeViewModel.AddState {
    if (sameDestination(item, isHome)) return this
    return copy(
        selectedPlace = item,
        destinationIsHome = isHome,
        place = place.copy(results = emptyList(), query = item?.name ?: ""),
        routeKey = null,
        routeLabel = null,
        fieldErrors = fieldErrors - AddField.DESTINATION - AddField.ORIGIN - AddField.ROUTE,
    )
}

/**
 * [item](null 이면 집) · [placeId](저장한 집이면 서버의 그 집 id) 가 지금 출발지와 같은가. 같으면
 * [withOrigin] 이 경로를 그대로 둔다. 같은 좌표라도 저장한 집과의 연결이 달라지면 바뀐 것이다.
 */
internal fun HomeViewModel.AddState.sameOrigin(item: PlaceSearchItem?, placeId: Long? = null): Boolean =
    sameEndpoint(origin, item) && originPlaceId == placeId

/**
 * 출발지를 [item] 으로 바꾼 폼. null 이면 집이다.
 *
 * 출발지가 달라지면 소요시간과 노선이 전부 달라지므로 고른 경로를 버린다. 남겨 두면 없는 노선 key 를 들고
 * 일정을 만들어 `route_failed` 가 된다. 바뀐 출발지에서 준비 시간을 넣을지는 [includePrep] 이다(저장한
 * 집은 켠 채로 둔다). 집으로 되돌리면 스위치가 사라지므로 값도 지운다(include-prep-api.md §5 ①).
 *
 * 같은 출발지면([sameOrigin]) 경로 · 오류 줄 · 검색 · 스위치를 그대로 둔다. 현재 위치를 못 구했다는 안내만
 * 지운다 — 사용자가 출발지를 직접 골랐으니 더 알릴 것이 없다.
 *
 * 고르면 현재 위치 기다리기도 끝난다([HomeViewModel.AddState.originLocating]). 그 뒤에 온 위치는 고른 출발지를
 * 덮지 않는다([withLocatedOrigin]). (AI-generated (Claude))
 */
internal fun HomeViewModel.AddState.withOrigin(
    item: PlaceSearchItem?,
    placeId: Long? = null,
    includePrep: Boolean = this.includePrep,
): HomeViewModel.AddState {
    if (sameOrigin(item, placeId)) {
        return if (originNotice == null && !originLocating) this else copy(originNotice = null, originLocating = false)
    }
    return copy(
        origin = item,
        originLabel = item?.name,
        originPlaceId = placeId,
        originPlace = HomeViewModel.PlaceSearch(),
        originLocating = false,
        originNotice = null,
        routeKey = null,
        routeLabel = null,
        fieldErrors = fieldErrors - AddField.DESTINATION - AddField.ORIGIN - AddField.ROUTE,
        includePrep = if (item == null) false else includePrep,
    )
}

// AI-generated (Claude)
/**
 * "현재 위치 사용" 이 끝났을 때의 폼. 위치를 물어본 폼([formId] = 그 폼의 `clientRequestId`)이 아직 기다릴
 * 때만([HomeViewModel.AddState.originLocating]) 쓴다.
 *
 * 위치는 10초까지 기다리고 주소도 받아야 해서, 그 사이 저장하거나 다른 일정의 수정 화면을 열 수 있다.
 * 예전에는 결과가 그때 열린 폼에 들어가, 고친 적 없는 일정의 출발지와 경로가 바뀌고 저장한 뒤의 빈 폼이
 * 쓰다 만 일정이 됐다. 폼마다 `clientRequestId` 가 새로 생기고 이어 쓰기는 그대로 두므로 이것으로 가린다.
 *
 * 같은 폼이라도 더 기다리지 않으면 버린다. 맡겼다 되살린 폼([forStash])은 출발지 줄 · 경로 고르기 · 저장이 다시
 * 켜져 있어, 늦게 온 위치가 그 사이 고른 출발지를 덮고 고른 경로와 ⑬ 의 후보를 지웠다. 출발지를 직접 골라도
 * 기다리기가 끝난다([withOrigin]).
 *
 * [here] 가 null 이면 못 구한 것이다. 값은 그대로 두고 [notice] 로 이유만 알린다([currentPlaceNotice]).
 */
internal fun HomeViewModel.AddState.withLocatedOrigin(
    formId: String,
    here: PlaceSearchItem?,
    notice: String?,
): HomeViewModel.AddState = when {
    clientRequestId != formId -> this
    !originLocating -> this
    here == null -> copy(originLocating = false, originNotice = notice)
    else -> copy(originLocating = false).withOrigin(here)
}

// AI-generated (Claude)
/**
 * "현재 위치 사용" 이 끝난 결과. 못 구했으면 무엇이 안 됐는지 남긴다 — 이유마다 안내가 다르다
 * ([currentPlaceNotice]). 예전에는 모두 "못 구함" 하나여서, 통신이 끊겨도 위치 권한과 GPS 를 확인하라고 했다.
 */
internal sealed interface CurrentPlaceLookup {
    data class Found(val place: PlaceSearchItem) : CurrentPlaceLookup

    /** 위치 권한이 없거나 위치를 재지 못했다 */
    data object NoFix : CurrentPlaceLookup

    /** 위치는 쟀지만 주소를 받지 못했다(연결 끊김 · 서버 오류 · 주소 조회 일부 실패) */
    data object LookupFailed : CurrentPlaceLookup

    /** 주소가 없는 자리다(바다 · 국외) */
    data object NoAddress : CurrentPlaceLookup
}

// AI-generated (Claude)
/** 잰 위치의 주소 조회 결과를 [CurrentPlaceLookup] 으로. 성공했는데 장소가 없으면 주소가 없는 자리다 */
internal fun currentPlaceLookupOf(reverse: EventRepository.Result<PlaceSearchItem?>): CurrentPlaceLookup =
    when (reverse) {
        is EventRepository.Result.Success -> reverse.data?.let(CurrentPlaceLookup::Found) ?: CurrentPlaceLookup.NoAddress
        is EventRepository.Result.Failure -> CurrentPlaceLookup.LookupFailed
    }

// AI-generated (Claude)
/**
 * 현재 위치를 못 구했을 때 출발지 줄 아래에 보일 안내. 구했으면 null.
 *
 * 위치 권한 · GPS 를 확인하라는 말은 위치를 재지 못했을 때만 한다. 주소를 받지 못한 것은 다시 누르면 될 수
 * 있어 그렇게 말하고, 주소가 없는 자리는 다시 눌러도 같으니 직접 검색하게 한다.
 */
internal fun currentPlaceNotice(lookup: CurrentPlaceLookup): String? = when (lookup) {
    is CurrentPlaceLookup.Found -> null
    CurrentPlaceLookup.NoFix -> "현재 위치를 확인할 수 없음. 위치 권한과 GPS 를 확인하거나 직접 검색할 것"
    CurrentPlaceLookup.LookupFailed -> "현재 위치의 주소를 불러오지 못했어요. 잠시 뒤 다시 누르거나 직접 검색해 주세요"
    CurrentPlaceLookup.NoAddress -> "이 위치의 주소를 찾지 못했어요. 직접 검색해 주세요"
}

// --- 집이 옮겨졌을 때 · 저장한 집 id ------------------------------------------------

// AI-generated (Claude)
/**
 * 계정의 집이 옮겨졌는가. 좌표로 본다 — 같은 집을 다시 받거나 이름만 바뀐 것은 옮긴 것이 아니다. 앞의 집을
 * 모르면(화면을 막 열어 처음 받음) 무엇과 비교할지 몰라 false 다.
 */
internal fun homeMoved(previous: PlaceSearchItem?, next: PlaceSearchItem?): Boolean =
    previous != null && next != null &&
        (abs(previous.lat - next.lat) > HOME_COORD_EPS || abs(previous.lng - next.lng) > HOME_COORD_EPS)

/** [homeMoved] 가 같은 좌표로 보는 차이(도). 서버가 준 값을 그대로 비교하므로 반올림 오차만 덮는다 */
private const val HOME_COORD_EPS = 1e-7

// AI-generated (Claude)
/**
 * 계정의 집이 [newHome] 으로 옮겨졌을 때(설정의 "변경" · "기본으로", 다른 기기) 쓰다 만 일정을 맞춘다.
 *
 * 집은 폼에 보이지 않는 끝점이다. 출발지가 집(null)이거나 도착지가 [집] 이면 고른 경로는 예전 집 기준이라
 * 버리고, [집] 도착지는 새 집을 가리키게 한다. 예전에는 "이어서 쓰기" 가 예전 집에서 고른 경로와 예전 집
 * 이름을 그대로 되살렸고, 서버는 새 집 기준으로 그 경로를 찾았다.
 *
 * 출발지가 저장한 집([HomeViewModel.AddState.originPlaceId])인데 그 자리가 이제 계정의 집이면 "기본으로" 로
 * 자리를 바꾼 것이다. 서버는 그 id 의 행을 예전 기본 집으로 고쳐 쓰므로, id 를 그대로 보내면 화면에는
 * 본가인데 예전 집에서 출발한 알람이 된다. 서버가 앞으로의 일정에 하듯 집 출발로 바꾸고, 좌표가 같으니 고른
 * 경로는 둔다. 준비 시간을 끈 출발은 집 출발로 바꾸면 준비 시간이 들어가므로 좌표는 두고 그 집과의 연결만
 * 끊는다(좌표만 보내면 서버는 준비 시간을 넣지 않는다).
 */
internal fun HomeViewModel.AddState.afterHomeMoved(newHome: PlaceSearchItem): HomeViewModel.AddState {
    var next = if (destinationIsHome) withDestination(newHome, isHome = true) else this
    val from = origin
    if (from == null) {
        // 집에서 출발한다. 경로와 출발 · 도착 줄의 오류는 예전 집 기준이었다.
        next = next.copy(
            routeKey = null,
            routeLabel = null,
            fieldErrors = next.fieldErrors - AddField.DESTINATION - AddField.ORIGIN - AddField.ROUTE,
        )
    } else if (originPlaceId != null && SavedHomes.samePlace(GeoPoint(from.lat, from.lng), GeoPoint(newHome.lat, newHome.lng))) {
        next = if (includePrep) {
            next.copy(origin = null, originLabel = null, originPlaceId = null, includePrep = false)
        } else {
            next.copy(originPlaceId = null)
        }
    }
    return next
}

// AI-generated (Claude)
/**
 * 일정 만들기에 보낼 `origin_place_id`. 지금 아는 저장한 집 목록([savedHomes])에서 그 id 의 집이 출발지와 같은
 * 자리(50m 안)일 때만 보내고, 아니면 좌표만 보낸다(null).
 *
 * 서버는 id 를 받으면 좌표를 그 행의 것으로 바꿔 쓴다. "기본으로" 는 행 id 를 그대로 둔 채 내용을 예전 기본
 * 집으로 바꾸고, 지운 집의 id 는 400 이다. 폼이 들고 있던 id 가 그런 행이면 화면에 보인 출발지와 다른 곳에서
 * 출발한 알람이 된다. 좌표만 보내도 준비 시간은 스위치대로 들어간다
 * ([com.swpp.wakeup.data.repository.createIncludePrep]).
 */
internal fun originPlaceIdToSend(originPlaceId: Long?, origin: PlaceSearchItem?, savedHomes: List<SavedHome>): Long? {
    val id = originPlaceId ?: return null
    val from = origin ?: return null
    val home = savedHomes.firstOrNull { it.serverId == id } ?: return null
    return id.takeIf { SavedHomes.samePlace(home.point, GeoPoint(from.lat, from.lng)) }
}

// AI-generated (Claude)
/**
 * 지도에서 [picked] 를 "이 장소로 선택" 했을 때 원래 값([current])이 바뀌는가.
 *
 * 지도는 지금 값을 1번 핀으로 골라 둔 채 열린다. 그대로 확인하면 바뀐 것이 없다 — 그때 값을 다시 넣으면
 * [집] 으로 고른 도착지가 일반 장소가 되고(집 표시가 꺼지고 경로가 지워진다), 저장한 집 출발지는 그 집과의
 * 연결(id)을 잃는다. 일정 수정에서는 일정이 더 이상 집을 따라가지 않게 된다.
 *
 * 지도가 핀을 가르는 키([placeStableKey])로 본다. 집 핀 대신 같은 자리의 검색한 장소 핀을 고른 것도 고른 것이다 —
 * 좌표가 같아 [sameEndpoint] 로는 같은 끝점이지만, 받는 쪽([withDestination] · [withOrigin])이 집 표시 ·
 * 저장한 집 id 와 함께 정말 바뀌었는지 다시 본다.
 */
internal fun mapPickChangesValue(current: PlaceSearchItem?, picked: PlaceSearchItem): Boolean =
    current == null || placeStableKey(current) != placeStableKey(picked)

/**
 * 일정 수정 폼의 경로 줄 문구. 사용자가 고른 경로([routeKey])가 있을 때만 계획의 소요시간 · 수단으로
 * 만든다. 예: "23분 · 지하철+도보".
 *
 * 경로 줄은 문구가 있으면 "선택함" 이라고 말한다. 고르지 않은 일정은 서버가 빠른 쪽을 쓰는데, 그 요약을
 * 넣으면 고른 적 없는 경로를 고른 것처럼 보인다. null 이면 "선택 안 함 · 최단 경로 사용" 이다.
 */
internal fun editRouteLabel(routeKey: String?, travelMinutes: Int?, travelMode: String?): String? {
    if (routeKey == null) return null
    val minutes = travelMinutes ?: return null
    return listOfNotNull("${minutes}분", travelMode?.takeIf(String::isNotBlank)).joinToString(" · ")
}
