// AI-generated (Claude)
package com.swpp.wakeup.ui.home

import com.swpp.wakeup.data.repository.EventRepository
import com.swpp.wakeup.domain.model.RouteOption

/*
 * 일정 추가·경로 선택의 실패를 화면 어디에 둘지 정하는 순수 함수.
 *
 * `HomeViewModel` 은 AndroidViewModel 이라 JVM 단위 테스트로 만들 수 없다. 판정만
 * 여기로 빼서 `RouteChoiceRulesTest` 가 본다. 저장 실패의 줄 나누기는 `EventSaveErrors.kt`.
 *
 * 규칙(출발·도착 50m 등)은 서버가 소유한다. 앱은 미리 검사하지 않고 숫자도 두지
 * 않는다. 서버가 `error.details` 에 붙인 필드 이름으로 줄만 고른다.
 */

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
