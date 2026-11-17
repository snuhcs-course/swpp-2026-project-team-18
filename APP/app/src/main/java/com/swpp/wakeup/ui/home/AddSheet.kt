// Partly AI-generated (Claude)
package com.swpp.wakeup.ui.home

/**
 * 알람 추가 바텀시트(H2-8 `545:192`)를 열고 닫는 규칙. 뷰모델은 이 결정만 따른다 — 안드로이드 없이
 * 단위 테스트로 묶어 두려고 따로 뺐다.
 */
internal object AddSheetRule {

    /** 홈 "일정 추가" 를 눌렀을 때 할 일 */
    enum class OnAdd {
        /** 닫아 둔 채 저장 중인 시트를 그대로 다시 보인다(이어 쓰기를 묻지 않는다) */
        SHOW_PENDING,
        /** 쓰다 만 일정이 있다. 이어 쓸지 묻는다(A4) */
        ASK_RESUME,
        /** 새 폼으로 연다 */
        OPEN_NEW,
    }

    fun onAdd(current: HomeViewModel.AddState, pendingDraft: HomeViewModel.AddState?): OnAdd = when {
        current.submitting && !current.isEditing -> OnAdd.SHOW_PENDING
        current.isUnsavedDraft || pendingDraft != null -> OnAdd.ASK_RESUME
        else -> OnAdd.OPEN_NEW
    }

    /**
     * 시트를 닫아도 되는가(X · 바깥 · 아래로 밀기 · 뒤로). 저장을 기다리는 10초 동안은 닫지 않는다.
     * 10초가 넘으면([HomeViewModel.AddState.submitSlow]) 닫아도 요청은 뷰모델에서 이어진다(H2-8 `546:766`).
     */
    fun canDismiss(state: HomeViewModel.AddState): Boolean = !state.submitting || state.submitSlow
}

/**
 * 도착지를 집으로 바꾼 직후의 되돌리기(H2-9, Figma ⑫ 변형 `546:1822`). 바꾸기 전 도착지 · 경로를 한 번 보관한다.
 *
 * @property routeCleared 고른 경로가 있었는데 바꾸면서 지웠다. 문구와 경로 줄 안내가 달라진다
 */
data class DestinationUndo(
    val id: Long,
    val selectedPlace: com.swpp.wakeup.data.remote.PlaceSearchItem?,
    val destinationIsHome: Boolean,
    val routeKey: String?,
    val routeLabel: String?,
    val query: String,
    val routeMinutes: Int? = null,
) {
    val routeCleared: Boolean get() = routeKey != null

    val message: String
        get() = if (routeCleared) "도착지를 집으로 바꿨어요.\n고른 경로도 지웠어요" else "도착지를 집으로 바꿨어요."

    companion object {
        /** 되돌리기를 보여 주는 시간 */
        const val SHOW_MS = 6_000L

        /** 되돌릴 것이 있을 때만 만든다. 도착지가 없었거나 이미 집이었으면 null */
        fun capture(before: HomeViewModel.AddState, id: Long): DestinationUndo? {
            if (before.selectedPlace == null || before.destinationIsHome) return null
            return DestinationUndo(
                id = id,
                selectedPlace = before.selectedPlace,
                destinationIsHome = before.destinationIsHome,
                routeKey = before.routeKey,
                routeLabel = before.routeLabel,
                query = before.place.query,
                routeMinutes = before.routeMinutes,
            )
        }

        /** 보관한 값을 한꺼번에 되살린다. 되돌릴 것이 없으면 그대로 */
        fun restore(state: HomeViewModel.AddState): HomeViewModel.AddState {
            val undo = state.destinationUndo ?: return state
            return state.copy(
                selectedPlace = undo.selectedPlace,
                destinationIsHome = undo.destinationIsHome,
                routeKey = undo.routeKey,
                routeLabel = undo.routeLabel,
                routeMinutes = undo.routeMinutes,
                place = state.place.copy(results = emptyList(), query = undo.query),
                destinationUndo = null,
            )
        }
    }
}
