package com.swpp.wakeup.ui.alarm

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swpp.wakeup.ui.common.JitPrimaryButton
import com.swpp.wakeup.ui.common.JitSecondaryButton
import com.swpp.wakeup.ui.theme.JitColor
import com.swpp.wakeup.ui.theme.JitRadius

/** Figma 725:2976; coordinates and an unverified distance are never displayed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ManualArrivalReviewDialog(
    position: ManualArrivalReview.Position,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val far = position == ManualArrivalReview.Position.FAR
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().testTag("manual_arrival_review")
                .clip(RoundedCornerShape(JitRadius.Card)).background(JitColor.Surface)
                .verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                if (far) "목적지와 떨어져 있어요" else "도착을 직접 기록할까요?",
                color = JitColor.TextPrimary, fontWeight = FontWeight.Bold,
                fontSize = 16.sp, lineHeight = 20.sp,
            )
            Text(
                when (position) {
                    ManualArrivalReview.Position.FAR -> "현재 위치가 목적지 반경 100m 밖이에요. 도착한 것이 맞나요? 직접 기록한 도착은 GPS 판정과 구분돼요."
                    ManualArrivalReview.Position.UNKNOWN -> "현재 위치를 확인하지 못했어요. 실제로 도착한 경우에만 기록해 주세요."
                    ManualArrivalReview.Position.NEAR -> "실제로 도착한 경우에만 기록해 주세요. 직접 기록한 도착은 GPS 판정과 구분돼요."
                },
                color = JitColor.TextSecondary, fontSize = 12.sp, lineHeight = 16.sp,
            )
            JitSecondaryButton(
                "계속 이동", onDismiss, Modifier.fillMaxWidth().testTag("manual_arrival_cancel"),
            )
            JitPrimaryButton(
                "도착 기록", onConfirm,
                Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("manual_arrival_confirm"),
            )
        }
    }
}
