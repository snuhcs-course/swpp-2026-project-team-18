// AI-generated (Claude)
package com.swpp.wakeup.ui.home

import com.swpp.wakeup.data.repository.EventRepository
import com.swpp.wakeup.domain.model.AlarmPlanView
import com.swpp.wakeup.domain.model.HomeRefresh
import com.swpp.wakeup.ui.nav.AppRoute

/*
 * 알람 결정(④) · 지각 위험(⑤) 화면이 어느 일정의 계획 · 오류 · 선택지를 보일지, 다시 맨 위가 됐을 때 무엇을
 * 다시 받을지, 저장 결과를 어디에 쓸지 정하는 순수 함수.
 *
 * 두 화면은 일정마다 스택에 따로 쌓일 수 있지만(이동 중 알림이 결정 화면을 하나 더 연다) 계획 · 선택지 상태는
 * 하나를 같이 쓴다. `HomeViewModel` 은 AndroidViewModel 이라 JVM 단위 테스트로 만들 수 없어서, 판정만 여기로
 * 빼서 `AlarmDecisionRulesTest` 가 본다.
 */

/**
 * [eventId] 화면에 보일 계획. 다른 일정의 계획이면 null 이다 — 화면은 받는 중으로 보이고, 돌아왔을 때
 * [planReloadOf] 가 제 계획을 다시 받는다. 예전에는 결정 화면이 들고 있는 계획을 그대로 그려서, 다른 일정의
 * 제목 · 알람을 보이면서 버튼(삭제 · 수정)은 이 일정에 걸렸다.
 */
internal fun AlarmPlanView?.forEvent(eventId: Long): AlarmPlanView? = this?.takeIf { it.eventId == eventId }

/** [eventId] 화면에 보일, 계획을 받지 못한 이유. 다른 일정의 실패면 null */
internal fun HomeViewModel.PlanLoadError?.messageFor(eventId: Long): String? =
    this?.takeIf { it.eventId == eventId }?.message

/** 통신이 안 돼 계획을 받지 못했을 때 결정 화면의 오류 카드에 쓰는 이유([planLoadReasonOf]) */
internal const val PLAN_LOAD_OFFLINE_REASON = "인터넷 연결을 확인하고 다시 시도해 주세요"

// AI-generated (Claude)
/**
 * 계획을 받지 못한 [failure] 를 결정 화면의 오류 카드("알람 정보를 불러오지 못했어요") 아래 줄로 바꾼다.
 *
 * 통신 실패는 [PLAN_LOAD_OFFLINE_REASON] 으로 쓴다. 예전에는 저장소 문구("서버에 연결할 수 없다. …")를 그대로 보여서,
 * 해요체 제목 아래에 ~다 체 줄이 붙고 "서버" 라는 말이 보였다. 그 밖의 이유(알람이 아직 정해지지 않음 · 서버가 준
 * 문구)는 그대로 둔다.
 */
internal fun planLoadReasonOf(failure: EventRepository.Result.Failure): String =
    if (failure.network) PLAN_LOAD_OFFLINE_REASON else failure.message

// AI-generated (Claude)
/**
 * 저장(일정 수정 · 이 아침 할 일)이 돌려준 [eventId] 의 계획을 같이 쓰는 계획 칸에 쓸지(받는 중인 요청을 버리는 세대도
 * 이때만 올린다). [stack] 에서 가장 위의 알람 결정 화면이 그 일정의 것일 때만 쓴다 — 그 화면이 이 계획을 보일 화면이다.
 *
 * - 저장하는 동안 알림이 다른 일정의 결정 화면을 위에 열었으면 쓰지 않는다. 예전에는 늘 써서, 그 화면이 받던 계획이
 *   세대에 밀려 버려지거나 받은 계획이 덮였고, 그 화면은 다시 시도도 없는 스피너로 남았다. 쓰지 않은 계획은 그 일정의
 *   결정 화면이 다시 맨 위가 될 때 [planReloadOf] 가 새로 받는다(들고 있는 계획이 다른 일정의 것이다).
 * - 맨 위가 결정 화면이 아니어도(지각 위험을 연 채 스낵바의 다시 시도) 그 아래 가장 위의 결정 화면이 이 일정의 것이면
 *   쓴다. 쓰지 않으면 돌아왔을 때 1분 동안 저장 전 계획이 보인다([planReloadOf] 가 다시 받지 않는다).
 */
internal fun savedPlanApplies(stack: List<AppRoute>, eventId: Long): Boolean =
    stack.lastOrNull { it is AppRoute.AlarmDecision } == AppRoute.AlarmDecision(eventId)

// AI-generated (Claude)
/**
 * 일정 [eventId] 를 지운 뒤 홈으로 갈지. 맨 위 화면이 지운 일정의 것이면 true 다 — 그 일정의 알람 결정 · 지각 위험 ·
 * 이 아침 할 일 · 일정 수정, 그리고 그 수정 폼이 연 ⑬ 경로 선택 · 지도([addFormChildrenAbove]).
 *
 * 지우는 동안에도 지각 위험 · 이 아침 할 일 · 일정 수정은 누를 수 있다. 예전에는 알람 결정이 맨 위일 때만 홈으로 가서,
 * 그 화면들에 남은 사용자는 하는 것마다 실패했고 뒤로 가면 지운 일정의 알람 결정이 그대로 보였다. 기다리는 동안 다른
 * 화면(설정 · 다른 일정의 화면)으로 갔으면 그 화면은 닫지 않는다.
 *
 * @param mapTarget 지도 화면이 고르는 대상([HomeViewModel.MapPickState.target]). 지도 상태가 없으면 null
 */
internal fun showsDeletedEvent(stack: List<AppRoute>, eventId: Long, mapTarget: HomeViewModel.MapTarget?): Boolean =
    when (val top = stack.lastOrNull()) {
        is AppRoute.AlarmDecision -> top.eventId == eventId
        is AppRoute.RiskChoice -> top.eventId == eventId
        is AppRoute.EventBlocks -> top.eventId == eventId
        is AppRoute.EditEvent -> top.eventId == eventId
        // 그 수정 폼 위가 전부 그 폼이 연 화면이면 그 일정의 화면이다
        else -> addFormChildrenAbove(stack, AppRoute.EditEvent(eventId), mapTarget).isNotEmpty()
    }

/** 알람 결정 화면이 다시 보일 때 계획을 어떻게 받을지([planReloadOf]) */
internal enum class PlanReload {
    /** 그대로 둔다 */
    NONE,

    /** 보이는 계획을 둔 채 다시 받는다. 실패하면 보던 계획이 그대로 남는다 */
    SILENT,

    /** 비우고(받는 중으로 보인다) 새로 받는다 */
    FULL,
}

/**
 * 알람 결정 화면 [eventId] 가 다시 맨 위가 됐을 때(뒤로 돌아옴 · 앱 복귀) 계획을 어떻게 받을지.
 *
 * - 그 일정을 이미 받는 중이면 다시 보내지 않는다.
 * - 들고 있는 계획이 다른 일정의 것(위에 쌓였던 결정 화면이 바꿔 놓았다)이거나 없으면(받지 못함) 새로 받는다.
 * - 그 일정의 계획이면 마지막으로 받기 시작한 지 1분이 지났을 때만 조용히 다시 받는다([HomeRefresh] 와 같은
 *   간격). 밤새 열어 둔 화면은 경로 갱신 워커가 옮긴 알람을 몰랐다.
 *
 * @param heldPlanEventId 들고 있는 계획의 일정. 없으면 null
 * @param loadingEventId 받는 중인 일정. 없으면 null
 * @param nowElapsed 지금(`elapsedRealtime`)
 * @param lastLoadElapsed 마지막으로 받기 시작한 시각(`elapsedRealtime`). 없으면 null
 */
internal fun planReloadOf(
    eventId: Long,
    heldPlanEventId: Long?,
    loadingEventId: Long?,
    nowElapsed: Long,
    lastLoadElapsed: Long?,
): PlanReload = when {
    loadingEventId == eventId -> PlanReload.NONE
    heldPlanEventId != eventId -> PlanReload.FULL
    HomeRefresh.shouldRefreshOnResume(
        nowElapsed = nowElapsed,
        lastStartElapsed = lastLoadElapsed,
        inFlight = loadingEventId != null,
        // 계획을 바꾸는 저장은 끝날 때 세대를 올려 늦게 온 이 응답을 버린다. 기다릴 까닭이 없다.
        mutating = false,
    ) -> PlanReload.SILENT
    else -> PlanReload.NONE
}

/**
 * 뒤로 가서 맨 위가 된 [top] 이 지각 위험 화면인데 선택지 상태가 다른 일정의 것이면 true. 그사이 다른 일정의
 * 지각 위험을 열었다 닫아 상태가 비었다. 다시 받지 않으면 "선택지 불러오는 중" 이 끝나지 않는다.
 */
internal fun riskReloadNeeded(top: AppRoute, risk: HomeViewModel.RiskState): Boolean =
    top is AppRoute.RiskChoice && risk.eventId != top.eventId

/** 지각 위험 저장이 끝났을 때 할 일([riskSaveResultOf]) */
internal enum class RiskSaveResult {
    /** 저장했다. 그 화면을 닫는다. 위에 다른 화면이 쌓였으면 그 화면은 두고 스택에서만 뺀다 */
    CLOSE,

    /** 실패했다. 그 화면의 버튼 위에 이유를 쓴다. 위에 다른 화면이 쌓였어도 돌아오면 보인다 */
    SHOW_ERROR,

    /** 그 화면은 이미 닫혔다(뒤로 · 다른 일정의 지각 위험). 실패는 지금 보는 화면에 스낵바로 알린다 */
    NOTIFY,

    /** 그 화면은 이미 닫혔고 저장했다. 알릴 것이 없다 — 결정 화면이 바뀐 계획을 받는다 */
    DROP,
}

/**
 * 지각 위험 저장 결과를 어디에 쓸지.
 *
 * "그 화면이 아직 살아 있는가" 는 세대로 본다([sameScreen] — 뒤로 나가거나 다른 지각 위험을 열면 세대가
 * 바뀐다). 예전에는 "지금 맨 위인가" 로 보아서, 저장하는 동안 알림 · 알람이 다른 화면을 쌓으면 결과를 버렸다.
 * 돌아오면 "계속하기" 가 계속 돌았고, 뒤로 나간 뒤의 실패(선택은 저장했지만 알람 목록을 못 맞춤 포함)는
 * 어디에도 보이지 않았다.
 *
 * @param error 실패 이유. 저장했으면 null
 */
internal fun riskSaveResultOf(sameScreen: Boolean, error: String?): RiskSaveResult = when {
    sameScreen && error == null -> RiskSaveResult.CLOSE
    sameScreen -> RiskSaveResult.SHOW_ERROR
    error != null -> RiskSaveResult.NOTIFY
    else -> RiskSaveResult.DROP
}
