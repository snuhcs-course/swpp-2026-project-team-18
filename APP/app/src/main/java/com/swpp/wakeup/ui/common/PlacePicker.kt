package com.swpp.wakeup.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import com.swpp.wakeup.ui.theme.JitColor
import com.swpp.wakeup.ui.theme.JitRadius

/**
 * 장소 검색·선택.
 *
 * 카카오 로컬 검색을 **서버 프록시**(`/api/places/search`)로 부른다. 앱에 카카오
 * 키를 넣으면 APK 를 뜯어 꺼낼 수 있어서다.
 *
 * 일정 추가와 집 위치 설정이 같은 컴포저블을 쓴다. 둘 다 "좌표가 있는 장소
 * 하나" 를 고르는 일이라 화면을 따로 만들 이유가 없다.
 */
@Composable
fun PlacePicker(
    label: String,
    query: String,
    results: List<PlaceSearchItem>,
    searching: Boolean,
    selected: PlaceSearchItem?,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onSelect: (PlaceSearchItem?) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            JitTextField(
                label = label,
                value = query,
                onValueChange = onQueryChange,
                imeAction = ImeAction.Search,
                onImeAction = onSearch,
                enabled = enabled && !searching,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(9.dp))
            SearchButton(onClick = onSearch, loading = searching, enabled = enabled)
        }

        // 선택된 장소를 확인할 수 있게 남긴다. 좌표가 알람 계산에 쓰인다.
        selected?.let { place ->
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
                    place.address?.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.height(3.dp))
                        Text(text = it, color = JitColor.TextSecondary, fontSize = 11.sp)
                    }
                }
                Text(
                    text = "변경",
                    color = JitColor.Accent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable(enabled = enabled) { onSelect(null) },
                )
            }
        }

        if (results.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp)
                    .clip(RoundedCornerShape(JitRadius.Card))
                    .background(JitColor.Surface),
            ) {
                results.forEachIndexed { index, place ->
                    if (index > 0) HorizontalDivider(color = JitColor.Bg)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = enabled) { onSelect(place) }
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                    ) {
                        Text(
                            text = place.name,
                            color = JitColor.TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        place.address?.takeIf { it.isNotBlank() }?.let {
                            Spacer(Modifier.height(2.dp))
                            Text(text = it, color = JitColor.TextSecondary, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchButton(onClick: () -> Unit, loading: Boolean, enabled: Boolean) {
    androidx.compose.foundation.layout.Box(
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
