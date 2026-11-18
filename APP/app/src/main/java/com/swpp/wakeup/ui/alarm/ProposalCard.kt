// Partly AI-generated (Claude)
package com.swpp.wakeup.ui.alarm

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swpp.wakeup.domain.model.AlarmProposal
import com.swpp.wakeup.domain.model.AlarmProposalRule
import com.swpp.wakeup.ui.common.LineIcons
import com.swpp.wakeup.ui.theme.JitColor
import com.swpp.wakeup.ui.theme.JitRadius

/**
 * "알람을 바꿀까요?" 제안 카드(BE-H3d). 홈 다음 알람 카드 아래 · ④ 알람 카드 아래에 둔다.
 *
 * 문구는 서버 `reason_text` 그대로, 확률은 90% 상한 규칙으로. 버튼 둘: [오전 7:28로 앞당기기](주황 테두리 — 화면의 꽉 찬
 * 주황 버튼은 하나뿐이라는 규칙) · [그대로 두기](회색 채움). 높이는 최소값만 둔다.
 */
@Composable
fun ProposalCard(
    proposal: AlarmProposal,
    busy: Boolean,
    onAccept: () -> Unit,
    onKeep: () -> Unit,
    modifier: Modifier = Modifier,
    tag: String = "alarm_proposal",
) {
    val shape = RoundedCornerShape(JitRadius.Card)
    Column(
        modifier = modifier
            .testTag(tag)
            .fillMaxWidth()
            .clip(shape)
            .background(JitColor.Surface)
            .border(1.dp, JitColor.Amber, shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(LineIcons.alarm, contentDescription = null, tint = JitColor.Amber, modifier = Modifier.size(18.dp))
            Text(
                AlarmProposalRule.TITLE,
                color = JitColor.TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        proposal.reasonText?.let {
            Text(it, color = JitColor.TextPrimary, fontSize = 13.sp, modifier = Modifier.testTag("${tag}_reason"))
        }
        AlarmProposalRule.probabilityLine(proposal)?.let {
            Text(it, color = JitColor.TextSecondary, fontSize = 12.sp, modifier = Modifier.testTag("${tag}_probability"))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ProposalButton(
                text = AlarmProposalRule.acceptLabel(proposal),
                accent = true,
                enabled = !busy,
                busy = busy,
                onClick = onAccept,
                modifier = Modifier.weight(1f).testTag("${tag}_accept"),
            )
            ProposalButton(
                text = AlarmProposalRule.KEEP_LABEL,
                accent = false,
                enabled = !busy,
                busy = false,
                onClick = onKeep,
                modifier = Modifier.weight(1f).testTag("${tag}_keep"),
            )
        }
    }
}

@Composable
private fun ProposalButton(
    text: String,
    accent: Boolean,
    enabled: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clip(shape)
            .then(if (accent) Modifier.border(1.5.dp, JitColor.Accent, shape) else Modifier.background(JitColor.Surface2))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = JitColor.Accent, strokeWidth = 2.dp)
        } else {
            Text(
                text = text,
                color = if (accent) JitColor.Accent else JitColor.TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
        }
    }
}
