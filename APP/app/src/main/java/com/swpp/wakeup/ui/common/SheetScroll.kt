// Partly AI-generated (Claude)
package com.swpp.wakeup.ui.common

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity

/**
 * 바텀시트 안 스크롤이 맨 끝에 닿은 뒤 **남은 위쪽 스크롤 · 플링을 시트로 넘기지 않는다**(11-16 실기기 흔들림).
 *
 * 내용을 아래로 빠르게 내리면 끝에서 남은 속도가 ModalBottomSheet 로 올라가, 시트가 펼친 위치를 넘어 위로
 * 당겨졌다 돌아오기를 멈추지 않고 되풀이했다(에뮬레이터 `adb input swipe` 로 재현, 화면 녹화에서 시트 위 끝이
 * 화면 맨 위까지 올라갔다 내려오기를 반복). 시트는 이미 다 펼쳐져 있어 위로 받을 몫이 없으므로 여기서 먹는다.
 * 아래쪽(시트를 닫는 방향)은 그대로 넘겨 끌어 내려 닫기는 그대로 된다.
 *
 * 스크롤 컨테이너보다 **앞에** 붙인다: `Modifier.sheetScrollGuard().verticalScroll(…)`.
 * [bothWays] 면 아래쪽도 먹는다 — 시각 휠처럼 시트를 끌어 닫는 데 쓰면 안 되는 스크롤.
 */
fun Modifier.sheetScrollGuard(bothWays: Boolean = false): Modifier =
    nestedScroll(if (bothWays) SheetAllGuard else SheetUpwardGuard)

private object SheetUpwardGuard : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        if (available.y < 0f) Offset(0f, available.y) else Offset.Zero

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
        if (available.y < 0f) Velocity(0f, available.y) else Velocity.Zero
}

private object SheetAllGuard : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        Offset(0f, available.y)

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
        Velocity(0f, available.y)
}
