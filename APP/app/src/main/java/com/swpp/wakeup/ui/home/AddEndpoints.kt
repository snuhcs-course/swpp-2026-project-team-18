// AI-generated (Claude) — fix/iter4-he-bugs 의 같은 이름 파일에서 지금 코드에 맞는 판단만 옮겼다(11-19)
package com.swpp.wakeup.ui.home

import com.swpp.wakeup.data.remote.PlaceSearchItem
import com.swpp.wakeup.domain.model.SavedHomes
import com.swpp.wakeup.sensing.GeoPoint
import kotlin.math.abs

/**
 * 같은 끝점(출발지 · 도착지)인가. 둘 다 없으면(집 출발) 같다.
 *
 * 고른 경로는 끝점과 짝이다. 끝점이 **실제로 바뀌면** 경로를 버리고, 같은 곳을 다시 고르면 아무것도 지우지 않는다.
 * 예전에는 이미 주황인 [집] 을 다시 눌러도 고른 경로 · 오류 줄 · 검색을 지웠다.
 *
 * 둘 다 카카오 id 가 있으면 id 로 본다 — 한 건물의 다른 가게는 좌표가 같아도 다른 도착지다. 한쪽이라도 없으면 좌표가
 * 똑같은지로 본다(서버는 앱이 보낸 좌표를 그대로 돌려준다). 이름은 보지 않는다 — 서버가 돌려주는 이름은 검색에서 본
 * 이름과 다를 수 있다.
 */
internal fun sameEndpoint(a: PlaceSearchItem?, b: PlaceSearchItem?): Boolean {
    if (a == null || b == null) return a == null && b == null
    val idA = a.kakaoPlaceId?.takeIf { it.isNotBlank() }
    val idB = b.kakaoPlaceId?.takeIf { it.isNotBlank() }
    return if (idA != null && idB != null) idA == idB else a.lat == b.lat && a.lng == b.lng
}

/** [item] · [isHome] 이 지금 도착지와 같은가 */
internal fun HomeViewModel.AddState.sameDestination(item: PlaceSearchItem?, isHome: Boolean): Boolean =
    isHome == destinationIsHome && sameEndpoint(selectedPlace, item)

/** [item](null 이면 집) · [placeId](저장한 집이면 서버의 그 집 id) 가 지금 출발지와 같은가. 저장한 집과의 연결이 달라지면 바뀐 것이다 */
internal fun HomeViewModel.AddState.sameOrigin(item: PlaceSearchItem?, placeId: Long? = null): Boolean =
    originPlaceId == placeId && sameEndpoint(origin, item)

/**
 * 경로 후보 응답을 ⑬ 상태에 써도 되는가(fix/iter4-he-bugs 의 `candidatesApply`). 상태가 비었으면([cleared]) 쓰지
 * 않는다 — 도착지 · 출발지를 바꾸거나 다른 일정을 열 때 일부러 비운 것이다. 세대가 다르면 그 뒤에 새로 요청한 것이다.
 */
internal fun candidatesApply(cleared: Boolean, requestGen: Long, currentGen: Long): Boolean =
    !cleared && requestGen == currentGen

// --- 집이 옮겨졌을 때 (fix/iter4-he-bugs 이식, 11-19) ---------------------------------

// AI-generated (Claude)
/**
 * 계정의 집이 [previous] 에서 [next] 로 옮겨졌는가. 한쪽이라도 모르면(화면을 막 열어 처음 받음) 무엇과 비교할지
 * 몰라 false 다.
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
 * 집은 폼에 보이지 않는 끝점이다. 도착지가 [집] 이면 새 집을 가리키게 하고, 출발지가 집(null)이거나 도착지가 [집] 이면
 * 고른 경로는 예전 집 기준이라 버린다. 예전에는 "이어서 쓰기" 가 예전 집에서 고른 경로와 예전 집 이름을 그대로
 * 되살렸고, 서버는 새 집 기준으로 그 경로를 찾았다.
 *
 * 출발지가 저장한 집([HomeViewModel.AddState.originPlaceId])인데 그 자리가 이제 계정의 집이면 "기본으로" 로 자리를
 * 바꾼 것이다. 서버는 그 id 의 행을 예전 기본 집으로 고쳐 쓰므로 id 를 그대로 보내면 화면과 다른 곳에서 출발한다.
 * 준비 시간을 넣는 출발이면 집 출발로 바꾸고(좌표가 같아 경로는 둔다), 준비 시간을 끈 출발이면 좌표는 두고 그 집과의
 * 연결만 끊는다(집 출발로 바꾸면 준비 시간이 들어가 버린다).
 */
internal fun HomeViewModel.AddState.afterHomeMoved(newHome: PlaceSearchItem): HomeViewModel.AddState {
    val clearRoute: (HomeViewModel.AddState) -> HomeViewModel.AddState = {
        it.copy(
            routeKey = null,
            routeLabel = null,
            routeMinutes = null,
            routeAutoCaption = null,
            fieldErrors = it.fieldErrors - AddField.DESTINATION - AddField.ORIGIN - AddField.ROUTE,
        )
    }
    var next = this
    if (destinationIsHome) {
        next = clearRoute(next.copy(selectedPlace = newHome, place = next.place.copy(query = newHome.name)))
    }
    val from = origin
    if (from == null) {
        next = clearRoute(next)
    } else if (originPlaceId != null &&
        SavedHomes.samePlace(GeoPoint(from.lat, from.lng), GeoPoint(newHome.lat, newHome.lng))
    ) {
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
 * 계정의 집이 옮겨졌을 때 받아 둔 경로 후보 [cache] 를 버려야 하는가. [formChanged] 는 폼이 새 집에 맞춰 바뀌었는가다.
 * 폼이 그대로여도 집에서 출발한 후보(출발지 null)는 버린다 — 서버가 예전 집에서 만든 목록이다.
 */
internal fun routeCacheStaleAfterHomeMove(cache: HomeViewModel.RouteState?, formChanged: Boolean): Boolean =
    cache != null && (formChanged || cache.origin == null)
