// Partly AI-generated (Claude)
package com.swpp.wakeup.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * 선 아이콘. Figma 공통 아이콘(`fb/…`, `536:9`)이 쓰는 Lucide(ISC 라이선스) 24px 선 아이콘의 path 를 옮겼다.
 * 아이콘 라이브러리를 넣지 않으려고 쓰는 것만 둔다. `Icon(tint = …)` 로 색을 입힌다.
 */
object LineIcons {

    val mapPin: ImageVector by lazy {
        icon(
            "MapPin",
            "M20 10c0 4.993-5.539 10.193-7.399 11.799a1 1 0 0 1-1.202 0C9.539 20.193 4 14.993 4 10a8 8 0 0 1 16 0",
            "M9 10a3 3 0 1 0 6 0a3 3 0 1 0-6 0",
        )
    }

    val alarm: ImageVector by lazy {
        icon(
            "AlarmClock",
            "M4 13a8 8 0 1 0 16 0a8 8 0 1 0-16 0",
            "M12 9v4l2 2",
            "M5 3L2 6",
            "M22 6l-3-3",
            "M6.38 18.7L4 21",
            "M17.64 18.67L20 21",
        )
    }

    val plus: ImageVector by lazy { icon("Plus", "M5 12h14", "M12 5v14") }

    val pencil: ImageVector by lazy {
        icon(
            "Pencil",
            "M21.174 6.812a1 1 0 0 0-3.986-3.987L3.842 16.174a2 2 0 0 0-.5.83l-1.321 4.352a.5.5 0 0 0 .623.622" +
                "l4.353-1.32a2 2 0 0 0 .83-.497z",
            "M15 5l4 4",
        )
    }

    val help: ImageVector by lazy {
        icon(
            "CircleHelp",
            "M2 12a10 10 0 1 0 20 0a10 10 0 1 0-20 0",
            "M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3",
            "M12 17h.01",
        )
    }

    val info: ImageVector by lazy {
        icon("Info", "M2 12a10 10 0 1 0 20 0a10 10 0 1 0-20 0", "M12 16v-4", "M12 8h.01")
    }

    /** 이동 탭 "가는 길" 의 출발 */
    val door: ImageVector by lazy {
        icon(
            "DoorOpen",
            "M13 4h3a2 2 0 0 1 2 2v14",
            "M2 20h3",
            "M13 20h9",
            "M10 12v.01",
            "M13 4.562v16.157a1 1 0 0 1-1.242.97L5 20V5.562a2 2 0 0 1 1.515-1.94l4-1A2 2 0 0 1 13 4.561z",
        )
    }

    val train: ImageVector by lazy {
        icon(
            "TrainFront",
            "M8 3.1V7a4 4 0 0 0 8 0V3.1",
            "M9 15l-1-1",
            "M15 15l1-1",
            "M9 19c-2.8 0-5-2.2-5-5v-4a8 8 0 0 1 16 0v4c0 2.8-2.2 5-5 5z",
            "M8 19l-2 3",
            "M16 19l2 3",
        )
    }

    val bus: ImageVector by lazy {
        icon(
            "Bus",
            "M8 6v6",
            "M15 6v6",
            "M2 12h19.6",
            "M18 18h3s.5-1.7.8-2.8c.1-.4.2-.8.2-1.2 0-.4-.1-.8-.2-1.2l-1.4-5C20.1 6.8 19.1 6 18 6H4a2 2 0 0 0-2 2v10h3",
            "M7 18a2 2 0 1 0 4 0a2 2 0 1 0-4 0",
            "M9 18h5",
            "M14 18a2 2 0 1 0 4 0a2 2 0 1 0-4 0",
        )
    }

    /** 걷는 구간 */
    val walk: ImageVector by lazy {
        icon(
            "Footprints",
            "M4 16v-2.38C4 11.5 2.97 10.5 3 8c.03-2.72 1.49-6 4.5-6C9.37 2 10 3.8 10 5.5c0 3.11-2 5.66-2 8.68V16" +
                "a2 2 0 1 1-4 0z",
            "M20 20v-2.38c0-2.12 1.03-3.12 1-5.62-.03-2.72-1.49-6-4.5-6C14.63 6 14 7.8 14 9.5c0 3.11 2 5.66 2 8.68" +
                "V20a2 2 0 1 0 4 0z",
            "M16 17h4",
            "M4 13h4",
        )
    }

    val flag: ImageVector by lazy {
        icon("Flag", "M4 15s1-1 4-1 5 2 8 2 4-1 4-1V3s-1 1-4 1-5-2-8-2-4 1-4 1z", "M4 22v-7")
    }

    /** 경로(이동 탭 아이콘, [경로 바꾸기]) */
    val route: ImageVector by lazy {
        icon(
            "Route",
            "M3 19a3 3 0 1 0 6 0a3 3 0 1 0-6 0",
            "M9 19h8.5a3.5 3.5 0 0 0 0-7h-11a3.5 3.5 0 0 1 0-7H15",
            "M15 5a3 3 0 1 0 6 0a3 3 0 1 0-6 0",
        )
    }

    /** 하단 탭 "홈" */
    val house: ImageVector by lazy {
        icon(
            "House",
            "M15 21v-8a1 1 0 0 0-1-1h-4a1 1 0 0 0-1 1v8",
            "M3 10a2 2 0 0 1 .709-1.528l7-5.999a2 2 0 0 1 2.582 0l7 5.999A2 2 0 0 1 21 10v9a2 2 0 0 1-2 2H5" +
                "a2 2 0 0 1-2-2z",
        )
    }

    /** 하단 탭 "리포트" */
    val chart: ImageVector by lazy {
        icon("ChartColumn", "M3 3v16a2 2 0 0 0 2 2h16", "M18 17V9", "M13 17V5", "M8 17v-3")
    }

    /** 하단 탭 "내 정보" */
    val user: ImageVector by lazy {
        icon("User", "M19 21v-2a4 4 0 0 0-4-4H9a4 4 0 0 0-4 4v2", "M8 7a4 4 0 1 0 8 0a4 4 0 1 0-8 0")
    }

    /** 리포트 탭 주 넘기기 · 줄 끝 › */
    val chevronLeft: ImageVector by lazy { icon("ChevronLeft", "M15 18L9 12L15 6") }

    val chevronRight: ImageVector by lazy { icon("ChevronRight", "M9 18L15 12L9 6") }

    /** 리포트 탭 "준비 시간" 안내 */
    val check: ImageVector by lazy { icon("Check", "M20 6L9 17L4 12") }

    // 내 정보 탭 줄 아이콘(Figma `547:1244`)

    /** "준비 시간" */
    val clock: ImageVector by lazy { icon("Clock", "M2 12a10 10 0 1 0 20 0a10 10 0 1 0-20 0", "M12 6v6l4 2") }

    /** "아침 루틴" */
    val sliders: ImageVector by lazy {
        icon(
            "SlidersHorizontal",
            "M21 4h-7", "M10 4H3", "M21 12h-9", "M8 12H3", "M21 20h-5", "M12 20H3",
            "M14 2v4", "M8 10v4", "M16 18v4",
        )
    }

    /** "캘린더에서 가져오기" */
    val calendar: ImageVector by lazy {
        icon(
            "Calendar",
            "M8 2v4",
            "M16 2v4",
            "M5 4h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z",
            "M3 10h18",
        )
    }

    /** "알림·권한" */
    val bell: ImageVector by lazy {
        icon(
            "Bell",
            "M10.268 21a2 2 0 0 0 3.464 0",
            "M3.262 15.326A1 1 0 0 0 4 17h16a1 1 0 0 0 .74-1.673C19.41 13.956 18 12.499 18 8A6 6 0 0 0 6 8" +
                "c0 4.499-1.411 5.956-2.738 7.326",
        )
    }

    /** "도움말" */
    val book: ImageVector by lazy {
        icon(
            "BookOpen",
            "M12 7v14",
            "M3 18a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1h5a4 4 0 0 1 4 4a4 4 0 0 1 4-4h5a1 1 0 0 1 1 1v13" +
                "a1 1 0 0 1-1 1h-6a3 3 0 0 0-3 3a3 3 0 0 0-3-3z",
        )
    }

    /** "처음 안내 다시 보기" */
    val sunrise: ImageVector by lazy {
        icon(
            "Sunrise",
            "M12 2v8", "m4.93 10.93 1.41 1.41", "M2 18h2", "M20 18h2", "m19.07 10.93-1.41 1.41",
            "M22 22H2", "m8 6 4-4 4 4", "M16 18a4 4 0 0 0-8 0",
        )
    }

    /** 첫 실행 안내 1장의 "여유" (Partly AI-generated (Claude)) */
    val hourglass: ImageVector by lazy {
        icon(
            "Hourglass",
            "M5 22h14",
            "M5 2h14",
            "M17 22v-4.172a2 2 0 0 0-.586-1.414L12 12l-4.414 4.414A2 2 0 0 0 7 17.828V22",
            "M7 2v4.172a2 2 0 0 0 .586 1.414L12 12l4.414-4.414A2 2 0 0 0 17 6.172V2",
        )
    }

    // 도움말(Figma `551:1820`) — 검색 칸과 아코디언 화살표 (Partly AI-generated (Claude))
    val search: ImageVector by lazy { icon("Search", "M3 11a8 8 0 1 0 16 0a8 8 0 1 0-16 0", "M21 21l-4.3-4.3") }
    val chevronDown: ImageVector by lazy { icon("ChevronDown", "M6 9L12 15L18 9") }
    val chevronUp: ImageVector by lazy { icon("ChevronUp", "M18 15L12 9L6 15") }

    /** "설정" */
    val settings: ImageVector by lazy {
        icon(
            "Settings",
            "M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08" +
                "a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51" +
                "a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08" +
                "a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18" +
                "a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39" +
                "a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09" +
                "a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25" +
                "a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z",
            "M9 12a3 3 0 1 0 6 0a3 3 0 1 0-6 0",
        )
    }

    private fun icon(name: String, vararg paths: String): ImageVector {
        val builder = ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        )
        paths.forEach { path ->
            builder.addPath(
                pathData = addPathNodes(path),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return builder.build()
    }
}
