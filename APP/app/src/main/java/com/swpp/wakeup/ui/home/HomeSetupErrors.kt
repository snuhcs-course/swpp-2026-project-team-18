package com.swpp.wakeup.ui.home

import com.swpp.wakeup.data.remote.PlaceSearchItem
import com.swpp.wakeup.data.repository.SavedPlacesRepository
import com.swpp.wakeup.data.repository.SavedPlacesResult
import com.swpp.wakeup.data.repository.savedPlaceAddMessage
import com.swpp.wakeup.domain.model.SavedHomes
import com.swpp.wakeup.domain.model.takeCodePoints

// Partly AI-generated (Claude)
/**
 * 다른 집 저장 실패를 ⑭ 의 어느 줄에 보일지 정한다.
 *
 * `same_as_default` 는 이름 칸 아래(Figma F-50 `528:105`), 나머지는 폼 아래 오류 줄이다.
 * 뷰모델은 [android.app.Application] 이 있어야 만들 수 있어 JVM 에서 시험하지 못하므로
 * 이 분기만 순수 함수로 뺐다.
 */
internal fun HomeViewModel.HomeSetupState.withAddFailure(
    failure: SavedPlacesResult.Failure,
): HomeViewModel.HomeSetupState {
    val message = savedPlaceAddMessage(failure)
    return if (failure.code == SavedPlacesRepository.CODE_SAME_AS_DEFAULT) {
        copy(submitting = false, labelError = message, error = null)
    } else {
        copy(submitting = false, error = message, labelError = null)
    }
}

// AI-generated (Claude)
/**
 * 이름 칸을 고친 상태. 이름은 [SavedHomes.LABEL_MAX] 글자까지 둔다 — 서버처럼 코드 포인트로 센다. 예전처럼
 * UTF-16 단위로 자르면 끝의 이모지가 반쪽만 남아 깨진 글자가 됐다.
 *
 * 이름 칸 아래의 "기본 집과 같은 곳이에요"([HomeViewModel.HomeSetupState.labelError])는 지우지 않는다. 고른
 * 주소가 기본 집과 겹친다는 뜻이라 이름을 고쳐도 그대로이고, 다른 곳을 고르면([withPlace]) 지운다. 예전에는
 * 글자 하나만 고쳐도 사라져서, 이름이 문제인 줄 알고 같은 주소로 다시 저장해 같은 거절을 받았다. 폼 아래
 * 오류 줄([HomeViewModel.HomeSetupState.error])은 예전처럼 지운다.
 */
internal fun HomeViewModel.HomeSetupState.withLabel(value: String): HomeViewModel.HomeSetupState =
    copy(label = value.takeCodePoints(SavedHomes.LABEL_MAX), error = null)

// Partly AI-generated (Claude)
/** 주소 후보를 [item] 으로 고른 상태. 다른 곳을 골랐으니 기본 집과 겹친다는 이름 칸 아래 문구를 지운다 */
internal fun HomeViewModel.HomeSetupState.withPlace(item: PlaceSearchItem?): HomeViewModel.HomeSetupState =
    copy(selected = item, labelError = null)
