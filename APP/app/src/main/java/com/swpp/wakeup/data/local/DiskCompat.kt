package com.swpp.wakeup.data.local

import com.swpp.wakeup.domain.model.AlarmSchedule
import com.swpp.wakeup.domain.model.MorningSession

/**
 * 디스크에서 되살린 객체의 **빠진 필드를 메운다.**
 *
 * ## 왜 필요한가
 *
 * Gson 은 Kotlin 의 기본값을 모른다. 생성자에 기본값 없는 인자가 하나라도 있으면
 * Kotlin 은 무인자 생성자를 만들지 않고, 그럴 때 Gson 은 생성자를 아예 건너뛰고
 * (`Unsafe.allocateInstance`) 필드를 리플렉션으로 직접 채운다. JSON 에 키가 없으면
 * 그 필드는 **선언이 non-null 이어도 null 로 남는다.** Kotlin 컴파일러는 그 자리에
 * 검사를 넣지 않으므로 경고도 없다.
 *
 * 구버전 앱이 저장한 알람 JSON 에는 `prepBlocks` 키가 없다. 업그레이드 직후
 * `canLogBlocks`(= `prepBlocks.isNotEmpty()`)를 부르면 그 자리에서 죽는데, 그
 * 호출은 **알람을 해제하는 순간** 일어난다. 사용자가 앱을 가장 필요로 하는
 * 시점에 정확히 크래시가 난다.
 *
 * ## 왜 버리지 않는가
 *
 * 다른 캐시([JitDatabase])는 모양이 바뀌면 버린다. 다음 서버 동기화가 다시 채우기
 * 때문에 안전하다. **알람 사본은 그럴 수 없다.** 그것을 버리면 업그레이드 뒤
 * 사용자가 앱을 한 번이라도 열기 전까지 알람이 울리지 않는다. 지각을 막는 앱이
 * 업데이트 때문에 지각을 만드는 셈이다. 그래서 버리지 않고 채워서 살린다.
 *
 * ## 필드를 새로 추가하는 사람에게
 *
 * non-null 참조 타입 필드를 기본값과 함께 추가하면 여기도 같이 고쳐야 한다.
 * 잊어도 `DiskCompatTest` 가 잡는다 — 그 테스트는 필드 이름을 하드코딩하지 않고
 * "정상 인스턴스에는 있는데 되살린 인스턴스에는 없는 키" 를 찾는다.
 */

/**
 * non-null 로 선언된 값을 nullable 로 본다.
 *
 * 디스크에서 온 값은 선언과 무관하게 null 일 수 있다. 그 사실을 컴파일러에 알릴
 * 방법이 없어 통로를 하나 만든다. `if (x == null)` 로 직접 쓰면 "항상 false" 라고
 * 경고가 나고, 경고를 억누르면 **왜** 억눌렀는지가 코드에 남지 않는다.
 */
private fun <T> fromDisk(value: T): T? = value

/** 되살린 알람 하나를 쓸 수 있는 상태로 만든다. */
internal fun AlarmSchedule.withDiskDefaults(): AlarmSchedule =
    if (fromDisk(prepBlocks) == null) copy(prepBlocks = emptyList()) else this

/** 되살린 아침 기록을 쓸 수 있는 상태로 만든다. */
internal fun MorningSession.withDiskDefaults(): MorningSession =
    if (fromDisk(blocks) == null) copy(blocks = emptyList()) else this
