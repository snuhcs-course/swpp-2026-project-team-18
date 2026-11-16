// Partly AI-generated (Claude)
package com.swpp.wakeup.ui.home

import com.swpp.wakeup.data.local.normalizeOwner
import com.swpp.wakeup.ui.nav.AppRoute

/*
 * 쓰다 만 일정(초안) 판정과, 화면 밖으로 잠시 넘기는 보관함. 폼을 열고 닫을 때의 판정([addEntryOf] ·
 * [addFormChildrenAbove])도 여기 둔다.
 *
 * 초안은 디스크에 쓰지 않는다. 제목·장소 좌표는 계정별 민감 데이터라 소유자 표시·삭제·백업 제외를
 * 따로 챙겨야 하고, 로그인마다 `LocalStores.wipeAll` 이 돌아 어차피 지워진다. 대신 프로세스 안에서
 * 로그인 만료(401)로 액티비티가 끝나는 경우와 뒤로 나갔다 다시 여는 경우를 덮는다. 앱 프로세스가
 * 죽으면 초안도 사라진다.
 */

/**
 * 이어 쓸 만한 초안인가. 제목·목적지·출발지·경로·반복 요일 중 하나라도 사용자가 채웠으면 true.
 * 날짜·시각·종류는 기본값이 있어 그것만으로는 초안이 아니다. 수정 중이거나 이미 저장했으면 false.
 */
internal val HomeViewModel.AddState.isUnsavedDraft: Boolean
    get() = !isEditing && !done &&
        (title.isNotBlank() || selectedPlace != null || origin != null || repeatWeekdays.isNotEmpty() || routeKey != null)

// AI-generated (Claude)
/**
 * 화면 밖에 맡기거나(일정 수정이 맡겨 둠 · 로그인 만료 보관함) 되살릴 때의 사본. 그 폼에서 돌던 일의 표시
 * (저장 중 · 실패 안내 · 현재 위치 재기)는 내린다.
 *
 * 그 일의 결과는 맡긴 사본으로 돌아오지 않는다 — 늦게 온 현재 위치는 기다리지 않는 폼에 쓰지 않는다
 * ([withLocatedOrigin]). 예전에는 현재 위치를 재는 중에 맡긴 일정을 이어 쓰면 출발지 줄이 "현재 위치를 확인하는
 * 중" 으로 꺼진 채 남았고, 다시 누를 수도 없어 "새로 쓰기" 로만 빠져나갔다.
 */
internal fun HomeViewModel.AddState.forStash(): HomeViewModel.AddState =
    copy(submitting = false, failureNotice = null, originLocating = false)

// AI-generated (Claude)
/** 홈의 "일정 추가" 를 눌렀을 때 할 일([addEntryOf]) */
internal enum class AddEntry {
    /** 앞서 쓴 일정(추가 · 수정)을 저장하는 중이다. 폼을 열지 않는다 */
    WAIT,

    /** 쓰다 만 일정이 있다. 이어 쓸지 묻는다 */
    ASK_RESUME,

    /** 새 폼을 연다 */
    NEW,
}

// AI-generated (Claude)
/**
 * "일정 추가" 를 눌렀을 때 할 일. [current] 는 지금 폼, [pending] 은 일정 수정이 맡겨 둔 쓰다 만 일정이다.
 *
 * **저장 중인 폼은 이어 쓰기로 내놓지 않는다.** 이어 쓰면 submitting 이 풀려 저장 중인 폼을 다시
 * 고칠 수 있고, 늦게 온 저장 결과가 그 폼(새로 쓰기면 새 폼)을 닫고 비운다. 고친 내용은 같은
 * `client_request_id` 라 서버가 첫 일정을 돌려주며 버린다. [isUnsavedDraft] 에서 빼지 않는 이유:
 * 저장 중 로그인이 끝나면(401) `onCleared` 가 그 폼을 초안으로 맡겨야 한다(A4).
 */
internal fun addEntryOf(current: HomeViewModel.AddState, pending: HomeViewModel.AddState?): AddEntry = when {
    current.submitting -> AddEntry.WAIT
    current.isUnsavedDraft || pending != null -> AddEntry.ASK_RESUME
    else -> AddEntry.NEW
}

// AI-generated (Claude)
/**
 * 저장을 마친 폼 [form](일정 추가 · 수정)과 같이 닫을, 그 폼이 연 화면들(아래부터 맨 위까지).
 *
 * 폼 위가 **전부** 그 폼의 화면 — ⑬ 경로 선택과 도착지 · 출발지 지도 — 일 때만 돌려준다. 실패 스낵바의 [다시 시도] 는
 * 그 화면에서도 누를 수 있다. 폼만 닫으면 남은 화면이 비워진 폼을 읽어, ⑬ 은 비워진 후보로 끝나지 않는 스피너가 됐고
 * 지도는 고른 장소를 새 빈 폼에 넣어 쓰다 만 일정을 만들었다.
 *
 * 위에 다른 화면(알림 · 알람이 연 화면)이 하나라도 있으면 빈 목록이다 — 그 화면은 두고 폼만 뺀다
 * ([com.swpp.wakeup.ui.nav.NavState.close]). ⑭ 집 주소와 그 지도는 넣지 않는다. 제 폼을 따로 들고 있어 일정 폼이
 * 닫혀도 마저 쓸 수 있다. 폼이 맨 위거나 스택에 없어도 빈 목록이다.
 *
 * @param mapTarget 지도 화면이 고르는 대상([HomeViewModel.MapPickState.target]). 지도 상태가 없으면 null
 */
internal fun addFormChildrenAbove(
    stack: List<AppRoute>,
    form: AppRoute,
    mapTarget: HomeViewModel.MapTarget?,
): List<AppRoute> {
    val index = stack.lastIndexOf(form)
    if (index < 0) return emptyList()
    val above = stack.drop(index + 1)
    // 지도는 일정 폼의 도착지 · 출발지를 고를 때만 그 폼의 것이다(집 주소의 지도는 ⑭ 의 것).
    val formMap = mapTarget == HomeViewModel.MapTarget.DESTINATION || mapTarget == HomeViewModel.MapTarget.ORIGIN
    val ownOnly = above.all { it == AppRoute.RouteChoice || (it == AppRoute.MapPick && formMap) }
    return if (ownOnly) above else emptyList()
}

/**
 * 프로세스 안의 초안 보관함. 소유자(로그인 이메일)가 같을 때만 돌려준다.
 * [take] 는 맞든 틀리든 비운다 — 남의 초안이 오래 남지 않게.
 */
internal object AddDraftHolder {
    @Volatile
    private var owner: String? = null

    @Volatile
    private var draft: HomeViewModel.AddState? = null

    /** 소유자를 모르면 보관하지 않는다(누구의 것인지 말할 수 없는 초안은 내놓지 않는다) */
    @Synchronized
    fun put(owner: String?, draft: HomeViewModel.AddState) {
        val normalized = normalizeOwner(owner) ?: return
        this.owner = normalized
        this.draft = draft
    }

    @Synchronized
    fun take(owner: String?): HomeViewModel.AddState? {
        val stored = draft
        val storedOwner = this.owner
        clear()
        val normalized = normalizeOwner(owner) ?: return null
        return stored?.takeIf { storedOwner == normalized }
    }

    @Synchronized
    fun clear() {
        owner = null
        draft = null
    }
}
