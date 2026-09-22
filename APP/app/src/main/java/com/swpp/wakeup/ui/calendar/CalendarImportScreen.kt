package com.swpp.wakeup.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swpp.wakeup.domain.model.CalendarImportState
import com.swpp.wakeup.domain.model.ImportCandidate
import com.swpp.wakeup.ui.common.JitCard
import com.swpp.wakeup.ui.common.JitChip
import com.swpp.wakeup.ui.common.JitDotLabel
import com.swpp.wakeup.ui.common.JitPrimaryButton
import com.swpp.wakeup.ui.events.ScreenHeader
import com.swpp.wakeup.ui.theme.JitColor
import com.swpp.wakeup.ui.theme.JitRadius
import com.swpp.wakeup.ui.theme.JitSpace

/**
 * 캘린더 가져오기.
 *
 * ## 왜 목록을 보여주고 고르게 하는가
 *
 * 캘린더를 통째로 서버에 올리는 것은 사용자가 예상하지 못하는 일이다. 일정
 * 제목에는 병원 예약이나 사람 이름이 들어간다. 무엇이 나가는지 보여 주고
 * 고르게 하는 것이 유일하게 정직한 방식이다.
 *
 * 부수 효과로 "앱에서 지운 일정이 동기화로 되살아나는" 문제도 사라진다 —
 * 되살리는 것이 사용자의 선택이 된다.
 *
 * ## 장소를 확정하지 않는다
 *
 * 캘린더의 장소는 사람이 쓴 문자열이다. 검색 첫 결과를 말없이 쓰면 엉뚱한
 * 좌표로 알람이 잡히고, 사용자는 알람이 틀린 뒤에야 안다. 찾아 준 장소를
 * 줄마다 보여 준다.
 */
@Composable
fun CalendarImportScreen(
    state: CalendarImportState,
    onBack: () -> Unit,
    onToggle: (String) -> Unit,
    onRequestPermission: () -> Unit,
    onRetry: () -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(JitColor.Bg),
        contentPadding = PaddingValues(
            start = JitSpace.ScreenHorizontal,
            end = JitSpace.ScreenHorizontal,
            top = JitSpace.ScreenTop,
            bottom = JitSpace.ScreenBottom,
        ),
        verticalArrangement = Arrangement.spacedBy(JitSpace.Section),
    ) {
        item(key = "header") {
            Column(verticalArrangement = Arrangement.spacedBy(JitSpace.Section)) {
                ScreenHeader(
                    title = "캘린더에서 가져오기",
                    onBack = onBack,
                    enabled = !state.importing,
                )
                Text(
                    text = "어떤 일정을 가져올까?",
                    color = JitColor.TextPrimary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "고른 것만 서버로 보냄. 앞으로 2주 안의 시간 지정 일정만 보여줌",
                    color = JitColor.TextSecondary,
                    fontSize = 13.sp,
                )
            }
        }

        if (state.permissionDenied) {
            item(key = "permission") { PermissionCard(onRequestPermission) }
            return@LazyColumn
        }

        if (state.loading) {
            item(key = "loading") { LoadingBlock() }
            return@LazyColumn
        }

        state.error?.let { message ->
            item(key = "error") { ErrorCard(message, onRetry) }
        }

        if (state.isEmpty) {
            item(key = "empty") { EmptyCard() }
            return@LazyColumn
        }

        item(key = "summary") { SummaryCard(state) }

        items(state.candidates, key = { it.externalId }) { candidate ->
            CandidateRow(
                candidate = candidate,
                enabled = !state.importing,
                onToggle = { onToggle(candidate.externalId) },
            )
        }

        item(key = "submit") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Spacer(Modifier.height(4.dp))
                state.tooManyNote?.let {
                    Text(text = it, color = JitColor.Red, fontSize = 11.sp)
                }
                JitPrimaryButton(
                    label = if (state.selectedCount > 0) {
                        "${state.selectedCount}건 가져오기"
                    } else {
                        "가져올 일정을 고를 것"
                    },
                    onClick = onImport,
                    enabled = state.canImport,
                    loading = state.importing,
                )
                Text(
                    text = "장소를 찾은 일정만 알람 시각이 계산됨. 나머지는 목록에 남고 " +
                        "나중에 장소를 넣을 수 있음",
                    color = JitColor.TextSecondary,
                    fontSize = 10.sp,
                )
            }
        }
    }
}

@Composable
private fun SummaryCard(state: CalendarImportState) {
    val withPlace = state.candidates.count { it.selected && it.willHavePlan }
    JitCard(padding = 14.dp, gap = 6.dp) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            JitDotLabel(
                text = "선택 ${state.selectedCount} / ${state.candidates.size}건",
                dotColor = JitColor.Accent,
                fontSize = 13,
                dotSize = 7.dp,
            )
            Text(
                text = "알람 계산 가능 ${withPlace}건",
                color = JitColor.TextSecondary,
                fontSize = 11.sp,
            )
        }
        Text(
            text = "이미 가져온 일정은 기본으로 꺼져 있음. 다시 켜면 서버 값이 갱신됨",
            color = JitColor.TextSecondary,
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun CandidateRow(
    candidate: ImportCandidate,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(JitRadius.Card))
            .background(JitColor.Surface)
            .clickable(enabled = enabled, onClick = onToggle)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 체크 표시. Checkbox 대신 직접 그린다 — Material 기본색이 이 테마와
        // 어긋나고, 색을 전부 덮어쓰면 코드가 더 길어진다.
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(if (candidate.selected) JitColor.Accent else JitColor.Surface2),
            contentAlignment = Alignment.Center,
        ) {
            if (candidate.selected) {
                Text("✓", color = JitColor.Bg, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = candidate.whenLabel,
                    color = JitColor.TextSecondary,
                    fontSize = 11.sp,
                )
                if (candidate.alreadyImported) {
                    Spacer(Modifier.width(6.dp))
                    JitChip("이미 가져옴", JitColor.TextSecondary, fontSize = 9)
                }
            }
            Text(
                text = candidate.source.title,
                color = if (candidate.selected) JitColor.TextPrimary else JitColor.TextSecondary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(
                            if (candidate.willHavePlan) JitColor.Green
                            else JitColor.TextSecondary
                        )
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = candidate.placeNote,
                    color = if (candidate.willHavePlan) JitColor.Green
                    else JitColor.TextSecondary,
                    fontSize = 10.sp,
                )
            }
            candidate.source.calendarName?.let {
                Text(text = it, color = JitColor.TextSecondary, fontSize = 9.sp)
            }
        }
    }
}

/**
 * 권한 안내.
 *
 * **무엇에 쓰는지 적는다.** 시스템 대화상자는 "캘린더 접근" 만 말하고 이유를
 * 말해 주지 않는다. 이유를 모르면 거절하는 것이 합리적인 선택이다.
 */
@Composable
private fun PermissionCard(onRequest: () -> Unit) {
    JitCard(padding = 16.dp, gap = 9.dp) {
        JitDotLabel(
            text = "캘린더 읽기 권한이 필요함",
            dotColor = JitColor.Amber,
            fontSize = 13,
            dotSize = 8.dp,
        )
        Text(
            text = "기기 캘린더의 앞으로 2주 일정을 **읽기만** 함. 캘린더를 고치지 않음.\n" +
                "읽은 목록에서 고른 것만 서버로 보냄 — 전부 자동으로 올리지 않음",
            color = JitColor.TextSecondary,
            fontSize = 11.sp,
        )
        Text(
            text = "권한 없이도 일정을 직접 입력해 쓸 수 있음",
            color = JitColor.TextSecondary,
            fontSize = 10.sp,
        )
        Spacer(Modifier.height(2.dp))
        JitPrimaryButton(label = "권한 허용하기", onClick = onRequest)
    }
}

@Composable
private fun EmptyCard() {
    JitCard(padding = 16.dp, gap = 7.dp) {
        JitDotLabel(
            text = "가져올 일정이 없음",
            dotColor = JitColor.TextSecondary,
            fontSize = 13,
            dotSize = 8.dp,
        )
        Text(
            text = "앞으로 2주 안에 시간이 정해진 일정이 없음. 종일 일정은 도착 시각이 " +
                "없어서 제외함",
            color = JitColor.TextSecondary,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit) {
    JitCard(
        modifier = Modifier.clickable(onClick = onRetry),
        padding = 14.dp,
        gap = 6.dp,
    ) {
        JitDotLabel(
            text = "불러오지 못했음",
            dotColor = JitColor.Red,
            fontSize = 12,
            dotSize = 7.dp,
        )
        Text(text = message, color = JitColor.TextSecondary, fontSize = 11.sp)
        Text(
            text = "눌러서 다시 시도",
            color = JitColor.Accent,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun LoadingBlock() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(22.dp),
            color = JitColor.Accent,
            strokeWidth = 2.dp,
        )
    }
}
