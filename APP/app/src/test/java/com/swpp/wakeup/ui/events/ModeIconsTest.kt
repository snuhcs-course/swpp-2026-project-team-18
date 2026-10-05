package com.swpp.wakeup.ui.events

import androidx.compose.ui.graphics.vector.VectorPath
import com.swpp.wakeup.domain.model.RouteMode
import org.junit.Assert.assertTrue
import org.junit.Test

/** 수단 탭 아이콘 path 가 깨지지 않고 읽히는지. 손으로 옮긴 문자열이라 오타가 나기 쉽다. */
class ModeIconsTest {

    @Test
    fun `네 수단 모두 path 가 비어 있지 않다`() {
        RouteMode.entries.forEach { mode ->
            val icon = ModeIcons.of(mode)
            val path = icon.root.first() as VectorPath
            assertTrue("$mode 아이콘 path 가 비었다", path.pathData.size > 5)
        }
    }
}
