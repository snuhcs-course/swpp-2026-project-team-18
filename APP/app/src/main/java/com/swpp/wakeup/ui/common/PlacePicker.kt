package com.swpp.wakeup.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swpp.wakeup.data.remote.PlaceSearchItem
import com.swpp.wakeup.data.remote.PlaceSearchResponse
import com.swpp.wakeup.ui.home.HomeViewModel
import com.swpp.wakeup.ui.theme.JitColor
import com.swpp.wakeup.ui.theme.JitRadius

/**
 * 장소 검색·선택.
 *
 * 카카오 로컬 검색을 **서버 프록시**(`/api/places/search`)로 부른다. 앱에 카카오
 * 키를 넣으면 APK 를 뜯어 꺼낼 수 있어서다.
 *
 * 일정 추가·집 주소·출발지 선택이 같은 컴포저블과 같은 상태를 쓴다. 셋 다
 * "좌표가 있는 장소 하나" 를 고르는 일이다.
 *
 * ## 결과를 어떻게 보여 주는가
 *
 * 예전에는 결과 목록에 높이 상한만 걸려 있고 스크롤이 없었다. 그래서 몇 건이
 * 오든 **세 건만 보였고** 나머지는 잘렸다. 지금은 스크롤로 받은 전부를 보고,
 * "더 보기" 로 다음 페이지를 붙인다.
 *
 * 한 줄에 이름·업종·거리·주소를 함께 둔다. "레드포스PC" 가 셋이나 나올 때
 * 이름만으로는 어느 것인지 알 수 없고, 실제로 고르는 근거는 거리다.
 *
 * ## 평점이 없는 이유
 *
 * 카카오 로컬 API 응답에는 평점·사진·영업시간이 **아예 없다**(필드 12개를
 * 확인했다). 카카오맵 앱 화면에는 있지만 공개 API 가 주지 않는다. 별을
 * 지어내는 대신 장소 페이지 링크로 보낸다.
 */
@Composable
fun PlacePicker(
    label: String,
    state: HomeViewModel.PlaceSearch,
    selected: PlaceSearchItem?,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onSelect: (PlaceSearchItem?) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** 다음 페이지. null 이면 "더 보기" 를 그리지 않는다 */
    onLoadMore: (() -> Unit)? = null,
    /** 정렬 전환. null 이면 정렬 칩을 그리지 않는다 */
    onSortChange: ((String) -> Unit)? = null,
    /** 지도로 보기. null 이면 지도 버튼을 그리지 않는다 */
    onOpenMap: (() -> Unit)? = null,
    /** 카카오맵 장소 페이지 열기. 평점·사진이 있는 곳이다 */
    onOpenPlaceUrl: ((String) -> Unit)? = null,
    /**
     * 저장된 집. null 이 아니면 검색 옆에 "집" 버튼이 붙는다.
     *
     * 집은 출발지·도착지로 가장 자주 쓰이는데 매번 검색해서 고르게 하면
     * 가입할 때 받아 둔 주소가 쓰이지 않는다.
     */
    homePlace: PlaceSearchItem? = null,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            JitTextField(
                label = label,
                value = state.query,
                onValueChange = onQueryChange,
                imeAction = ImeAction.Search,
                onImeAction = onSearch,
                enabled = enabled && !state.searching,
                modifier = Modifier.weight(1f),
            )
            // 이미 고른 장소가 있으면 숨긴다. 그 상태에서 집을 누르면 방금 고른
            // 것이 조용히 덮여서, 무엇이 선택됐는지 알 수 없다.
            if (homePlace != null && selected == null) {
                Spacer(Modifier.width(9.dp))
                SquareButton(
                    text = "집",
                    color = JitColor.TextPrimary,
                    enabled = enabled && !state.searching,
                    onClick = { onSelect(homePlace) },
                )
            }
            Spacer(Modifier.width(9.dp))
            SearchButton(onClick = onSearch, loading = state.searching, enabled = enabled)
        }

        // 선택된 장소를 확인할 수 있게 남긴다. 좌표가 알람 계산에 쓰인다.
        selected?.let { place ->
            SelectedPlaceRow(
                place = place,
                enabled = enabled,
                onClear = { onSelect(null) },
                onOpenPlaceUrl = onOpenPlaceUrl,
            )
        }

        // 검색했는데 아무것도 없을 때만 말한다. 검색 전에 띄우면 사용자가
        // 이미 실패한 것으로 읽는다.
        //
        // **이미 고른 것이 있으면 말하지 않는다.** 지도에서 골라 돌아오면 목록은
        // 비는데 선택은 있다. 그때도 띄우면 방금 고른 장소 바로 아래에 "검색
        // 결과가 없음" 이 붙어 서로 모순된다 — 실기기에서 그렇게 나왔다.
        if (selected == null &&
            state.searched && state.results.isEmpty() && !state.searching && state.error == null
        ) {
            Text(
                text = "검색 결과가 없음. 상호나 지번을 넣어 볼 것",
                color = JitColor.TextSecondary,
                fontSize = 12.sp,
            )
        }

        state.error?.let {
            Text(text = it, color = JitColor.Red, fontSize = 12.sp)
        }

        if (state.results.isNotEmpty()) {
            ResultToolbar(
                state = state,
                enabled = enabled,
                onSortChange = onSortChange,
                onOpenMap = onOpenMap,
            )
            ResultList(
                state = state,
                enabled = enabled,
                onSelect = onSelect,
                onLoadMore = onLoadMore,
                onOpenPlaceUrl = onOpenPlaceUrl,
            )
        }
    }
}

// ---------------------------------------------------------------------------

@Composable
private fun SelectedPlaceRow(
    place: PlaceSearchItem,
    enabled: Boolean,
    onClear: () -> Unit,
    onOpenPlaceUrl: ((String) -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(JitRadius.Hint))
            .background(JitColor.Surface2)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            JitDotLabel(
                text = place.name,
                dotColor = JitColor.Green,
                fontSize = 12,
                dotSize = 6.dp,
            )
            MetaLine(place)
            val url = place.placeUrl
            if (onOpenPlaceUrl != null && !url.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "카카오맵에서 평점·사진 보기 ›",
                    color = JitColor.Blue,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable(enabled = enabled) { onOpenPlaceUrl(url) },
                )
            }
        }
        Text(
            text = "변경",
            color = JitColor.Accent,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable(enabled = enabled, onClick = onClear),
        )
    }
}

@Composable
private fun ResultToolbar(
    state: HomeViewModel.PlaceSearch,
    enabled: Boolean,
    onSortChange: ((String) -> Unit)?,
    onOpenMap: (() -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 거리 정렬은 기준 좌표가 있어야 서버가 적용한다. 거리를 못 받은
        // 상태에서 칩을 보여 주면 눌러도 아무 변화가 없다.
        if (onSortChange != null && state.hasDistances) {
            SortChip(
                text = "정확도",
                active = state.sort == PlaceSearchResponse.SORT_ACCURACY,
                enabled = enabled,
                onClick = { onSortChange(PlaceSearchResponse.SORT_ACCURACY) },
            )
            Spacer(Modifier.width(6.dp))
            SortChip(
                text = "거리순",
                active = state.sort == PlaceSearchResponse.SORT_DISTANCE,
                enabled = enabled,
                onClick = { onSortChange(PlaceSearchResponse.SORT_DISTANCE) },
            )
        }
        Spacer(Modifier.weight(1f))
        state.countLabel?.let {
            Text(text = it, color = JitColor.TextSecondary, fontSize = 10.sp)
        }
        if (onOpenMap != null) {
            Spacer(Modifier.width(8.dp))
            SortChip(text = "지도", active = false, enabled = enabled, onClick = onOpenMap)
        }
    }
}

@Composable
private fun ResultList(
    state: HomeViewModel.PlaceSearch,
    enabled: Boolean,
    onSelect: (PlaceSearchItem?) -> Unit,
    onLoadMore: (() -> Unit)?,
    onOpenPlaceUrl: ((String) -> Unit)?,
) {
    // **자체 스크롤을 두지 않는다.** 이 컴포저블을 쓰는 세 화면이 모두 바깥에서
    // `verticalScroll` 을 걸고 있어서, 여기에 또 스크롤과 높이 상한을 주면
    // 스크롤이 겹친다. 실기기에서 그 결과가 드러났다 — 목록이 긴 폼의 맨 아래에
    // 320dp 창으로 갇혀 **한 건도 온전히 안 보였고**, 그 좁은 창 안에서 다시
    // 스크롤해야 했다. 세 건만 보이던 처음 문제보다 오히려 나빠졌다.
    //
    // 상한을 없애면 목록이 페이지의 일부가 되어 페이지 스크롤로 전부 읽힌다.
    // 검색창 바로 아래 "45건 중 15건" 이 남아 있어 결과가 왔다는 신호는 그대로다.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(JitRadius.Card))
            .background(JitColor.Surface),
    ) {
        state.results.forEachIndexed { index, place ->
            if (index > 0) HorizontalDivider(color = JitColor.Bg)
            ResultRow(
                place = place,
                enabled = enabled,
                onClick = { onSelect(place) },
                onOpenPlaceUrl = onOpenPlaceUrl,
            )
        }

        if (state.loadingMore) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = JitColor.Accent,
                    strokeWidth = 2.dp,
                )
            }
        } else if (onLoadMore != null && state.canLoadMore) {
            HorizontalDivider(color = JitColor.Bg)
            Text(
                text = "결과 더 보기",
                color = JitColor.Accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = enabled, onClick = onLoadMore)
                    .padding(vertical = 13.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ResultRow(
    place: PlaceSearchItem,
    enabled: Boolean,
    onClick: () -> Unit,
    onOpenPlaceUrl: ((String) -> Unit)?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = place.name,
                color = JitColor.TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f, fill = false),
            )
            place.categoryGroup?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.width(7.dp))
                CategoryChip(it)
            }
        }
        MetaLine(place)

        val url = place.placeUrl
        if (onOpenPlaceUrl != null && !url.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "카카오맵에서 평점·사진 보기 ›",
                color = JitColor.Blue,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable(enabled = enabled) { onOpenPlaceUrl(url) },
            )
        }
    }
}

/** 거리 · 주소 한 줄. 둘 다 없으면 아무것도 그리지 않는다. */
@Composable
private fun MetaLine(place: PlaceSearchItem) {
    val distance = place.distanceLabel
    val address = place.address?.takeIf { it.isNotBlank() }
    if (distance == null && address == null) return

    Spacer(Modifier.height(3.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (distance != null) {
            Text(
                text = distance,
                color = JitColor.Green,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
            if (address != null) {
                Text(text = " · ", color = JitColor.TextSecondary, fontSize = 11.sp)
            }
        }
        if (address != null) {
            Text(
                text = address,
                color = JitColor.TextSecondary,
                fontSize = 11.sp,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun CategoryChip(text: String) {
    Text(
        text = text,
        color = JitColor.TextSecondary,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(CircleShape)
            .background(JitColor.Surface2)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun SortChip(text: String, active: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        color = if (active) JitColor.Bg else JitColor.TextSecondary,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(CircleShape)
            .background(if (active) JitColor.Accent else JitColor.Surface2)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

/** 검색창 옆의 정사각 버튼. 검색과 집이 같은 모양을 쓴다. */
@Composable
private fun SquareButton(
    text: String,
    color: androidx.compose.ui.graphics.Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(46.dp)
            .clip(RoundedCornerShape(JitRadius.Button))
            .background(JitColor.Surface2)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SearchButton(onClick: () -> Unit, loading: Boolean, enabled: Boolean) {
    Box(
        modifier = Modifier
            .size(46.dp)
            .clip(RoundedCornerShape(JitRadius.Button))
            .background(JitColor.Surface2)
            .clickable(enabled = enabled && !loading, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                color = JitColor.Accent,
                strokeWidth = 2.dp,
            )
        } else {
            Text("검색", color = JitColor.Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}
