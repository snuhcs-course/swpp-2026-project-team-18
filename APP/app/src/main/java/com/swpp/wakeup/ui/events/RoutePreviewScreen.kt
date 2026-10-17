// Partly AI-generated (Claude)
package com.swpp.wakeup.ui.events

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swpp.wakeup.data.repository.EventRepository
import com.swpp.wakeup.domain.model.RouteMode
import com.swpp.wakeup.domain.model.RouteOption
import com.swpp.wakeup.sensing.GeoPoint
import com.swpp.wakeup.ui.common.JitPrimaryButton
import com.swpp.wakeup.ui.theme.JitColor
import com.swpp.wakeup.ui.theme.JitSpace

/**
 * 도보·자전거 후보의 전체 화면 지도(Figma ⑬-e). ⑬ 카드를 누르면 같은 경로 안에서 열린다.
 *
 * ## 일정 지도·추적과 섞이지 않는다
 *
 * 상태는 [CandidateRouteMapCard] 안의 `remember` 뿐이다. 일정 지도(`_routeMap`),
 * 추적 상태(`TripLiveState`·`LiveRouteStore`·`TripTrackingService`)를 읽거나 쓰지
 * 않고 현재 위치도 묻지 않는다(N19). 닫으면 버려진다.
 *
 * 지도 이미지를 못 받아도 "이 경로로 계산" 은 막지 않는다. 경로를 고르는 데 지도가
 * 꼭 필요하지는 않다. 실패 문구는 지도 자리에 서버 메시지 그대로 뜨고 "경로 전체
 * 보기" 로 다시 받는다.
 *
 * **스크롤하지 않는다.** 지도가 남은 높이를 채우려면(`weight`) 부모가 높이를 정해
 * 줘야 하고, 스크롤 안에서는 지도 높이가 0 이 된다. 핀치·드래그도 스크롤과 겹치지
 * 않는다.
 *
 * @param onConfirm 호출부가 `{ onSelect(option.key); onConfirm() }` 로 넘긴다
 */
@Composable
internal fun RoutePreviewScreen(
    option: RouteOption,
    originLabel: String,
    destination: String,
    loadMap: suspend (GeoPoint, Int, Int, Int) -> EventRepository.Result<Bitmap>,
    onClose: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(JitColor.Bg)
            .testTag("route_preview")
            .padding(
                start = JitSpace.ScreenHorizontal,
                end = JitSpace.ScreenHorizontal,
                top = JitSpace.ScreenTop,
                bottom = JitSpace.ScreenBottom,
            ),
        verticalArrangement = Arrangement.spacedBy(JitSpace.Section),
    ) {
        ScreenHeader(title = "경로 지도", onBack = onClose)

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = option.minutesLabel,
                color = JitColor.TextPrimary,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(8.dp))
            // 목록에는 탭이 수단을 말하지만 여기에는 탭이 없다. 수단 이름을 적는다.
            Text(text = option.mode, color = JitColor.TextSecondary, fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            RouteBadges(option.badges)
        }

        Text(
            text = "$originLabel → $destination",
            color = JitColor.TextSecondary,
            fontSize = 13.sp,
        )
        RouteGuide(option.guide)

        key(option.key, option.routePath) {
            CandidateRouteMapCard(
                path = option.routePath,
                loadMap = loadMap,
                modifier = Modifier.weight(1f),
            )
        }

        if (option.travelMode == RouteMode.BICYCLE) {
            MetricCells(bicycleMetrics(option))
        } else if (option.detailLine.isNotBlank()) {
            // 도보는 서버 문구 그대로("1.2km · 1,600걸음 · 60kcal (거리 기반 추정)").
            // 앱이 걸음·kcal 을 다시 계산하지 않는다.
            Text(text = option.detailLine, color = JitColor.TextSecondary, fontSize = 12.sp)
        }

        JitPrimaryButton(
            label = "이 경로로 계산",
            modifier = Modifier.testTag("route_preview_confirm"),
            onClick = onConfirm,
        )
    }
}
