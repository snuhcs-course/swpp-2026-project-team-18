// AI-generated (Claude)
package com.swpp.wakeup.ui.home

import com.swpp.wakeup.data.repository.EventRepository
import com.swpp.wakeup.domain.model.RouteOption

/*
 * 일정 추가·경로 선택의 실패를 화면 어디에 둘지 정하는 순수 함수.
 *
 * `HomeViewModel` 은 AndroidViewModel 이라 JVM 단위 테스트로 만들 수 없다. 판정만
 * 여기로 빼서 `EndpointErrorsTest` 가 본다.
 *
 * 규칙(출발·도착 50m 등)은 서버가 소유한다. 앱은 미리 검사하지 않고 숫자도 두지
 * 않는다. 서버가 `error.details` 에 붙인 필드 이름으로 줄만 고른다.
 */

/** 출발지 줄·도착지 줄·맨 아래 공통 줄에 보일 문구. 없으면 null. */
internal data class EndpointErrors(
    val origin: String?,
    val destination: String?,
    val general: String?,
)

/**
 * 일정 생성 실패를 줄별로 나눈다.
 *
 * 줄에 붙일 오류가 하나라도 있으면 공통 줄은 비운다 — 같은 말을 두 번 하지 않는다.
 * 줄에 붙일 것이 없으면 지금처럼 공통 줄에 서버 문구를 쓴다.
 */
internal fun endpointErrorsOf(failure: EventRepository.Result.Failure): EndpointErrors {
    val origin = failure.fields["origin_lat"] ?: failure.fields["origin_lng"]
    val destination = failure.fields["destination"] ?: failure.fields["destination_is_home"]
    return EndpointErrors(
        origin = origin,
        destination = destination,
        general = failure.message.takeIf { origin == null && destination == null },
    )
}

/**
 * 경로 후보 실패를 "다시 시도" 로 풀 수 있는가.
 *
 * 가까운 출발·도착(400 `details.destination`)은 다시 불러도 같은 400 이다. 장소를
 * 바꿔야 풀리므로 false. 그 밖(네트워크·5xx·429·집 없음 등)은 지금처럼 다시 시도.
 */
internal fun candidatesRetryable(failure: EventRepository.Result.Failure): Boolean =
    failure.fields["destination"] == null

/**
 * 후보를 다시 받은 뒤에도 미리보기를 열어 둘지. 열려 있던 후보가 새 목록에 없으면 닫는다.
 */
internal fun previewKeyAfterReload(previewKey: String?, options: List<RouteOption>): String? =
    previewKey?.takeIf { key -> options.any { it.key == key } }
