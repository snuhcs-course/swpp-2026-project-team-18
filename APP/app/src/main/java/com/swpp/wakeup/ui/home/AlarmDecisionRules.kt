// AI-generated (Claude) — fix/iter4-he-bugs 의 같은 이름 파일에서 지금 코드에 맞는 부분만 옮겼다(11-19)
package com.swpp.wakeup.ui.home

import com.swpp.wakeup.data.repository.EventRepository
import com.swpp.wakeup.domain.model.AlarmPlanView

/*
 * 알람 결정(④) 화면이 어느 일정의 계획 · 오류를 보일지 정하는 순수 함수. `HomeViewModel` 은 AndroidViewModel 이라
 * JVM 단위 테스트로 만들 수 없어서, 판정만 여기로 빼서 `AlarmDecisionRulesTest` 가 본다.
 */

/** 계획을 받지 못한 일정과 이유. 결정 화면의 오류 카드가 쓴다 */
data class PlanLoadError(val eventId: Long, val message: String)

/**
 * [eventId] 화면에 보일 계획. 다른 일정의 계획이면 null 이다 — 화면은 받는 중으로 보이고, 제 계획을 다시 받는다.
 * 예전에는 들고 있는 계획을 그대로 그려서, 다른 일정의 제목 · 알람을 보이면서 버튼(삭제 · 수정)은 이 일정에 걸렸다.
 */
internal fun AlarmPlanView?.forEvent(eventId: Long): AlarmPlanView? = this?.takeIf { it.eventId == eventId }

/** [eventId] 화면에 보일, 계획을 받지 못한 이유. 다른 일정의 실패면 null */
internal fun PlanLoadError?.messageFor(eventId: Long): String? = this?.takeIf { it.eventId == eventId }?.message

/** 결정 화면 오류 카드의 제목 */
internal const val PLAN_LOAD_TITLE = "알람 정보를 불러오지 못했어요"

/** 통신이 안 돼 계획을 받지 못했을 때 오류 카드 아래 줄 */
internal const val PLAN_LOAD_OFFLINE_REASON = "인터넷 연결을 확인하고 다시 시도해 주세요"

/**
 * 계획을 받지 못한 [failure] 를 오류 카드 아래 줄로 바꾼다. 통신 실패는 [PLAN_LOAD_OFFLINE_REASON] 으로 쓴다 —
 * 저장소 문구를 그대로 보이면 해요체 제목 아래에 다른 말투 · "서버" 같은 말이 붙었다. 그 밖의 이유는 그대로 둔다.
 */
internal fun planLoadReasonOf(failure: EventRepository.Result.Failure): String =
    if (failure.network) PLAN_LOAD_OFFLINE_REASON else failure.message
