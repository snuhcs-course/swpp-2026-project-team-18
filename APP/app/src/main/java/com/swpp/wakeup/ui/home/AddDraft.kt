// Partly AI-generated (Claude)
package com.swpp.wakeup.ui.home

import com.swpp.wakeup.data.local.normalizeOwner

/*
 * 쓰다 만 일정(초안) 판정과, 화면 밖으로 잠시 넘기는 보관함.
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
