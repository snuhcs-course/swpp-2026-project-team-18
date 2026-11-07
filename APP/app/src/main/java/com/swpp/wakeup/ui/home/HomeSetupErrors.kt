package com.swpp.wakeup.ui.home

import com.swpp.wakeup.data.repository.SavedPlacesRepository
import com.swpp.wakeup.data.repository.SavedPlacesResult
import com.swpp.wakeup.data.repository.savedPlaceAddMessage

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
