// Partly AI-generated (Claude)
package com.swpp.wakeup.ui.help

/**
 * 도움말(iter4 H2-4, Figma `551:1820`)의 자주 묻는 질문. 화면 곳곳의 i 버튼이 이 이름으로 그 항목을 펼친 채 연다.
 * 경로([com.swpp.wakeup.ui.nav.AppRoute.Help])에 실려서 공개다.
 */
enum class HelpTopic {
    ALARM_TIME,
    ON_TIME_PROBABILITY,
    PREP_TIME,
    LEARNING,
    RISK_LEVELS,
    CHANGE_ALARM,
    CALENDAR,
    ALARM_DIDNT_RING,
}

/**
 * 질문 하나. [answer] 는 문단을 빈 줄로, 글머리 줄을 "• " 로 시작해 나눈다([HelpFaq.paragraphs]).
 * [searchExtra] 는 답 아래 계산식처럼 화면에는 있는데 [answer] 에 없는 글자다. 검색에만 쓴다.
 */
internal data class FaqItem(
    val topic: HelpTopic,
    val question: String,
    val answer: String,
    val searchExtra: String = "",
)

/** 첫 질문(알람 시각)의 계산식 칩. [label] 이 없으면 시각 칩이다 */
internal data class FormulaChip(val kind: Kind, val label: String?, val value: String) {
    enum class Kind { START, BUFFER, TRAVEL, PREP, RESULT }
}

/** 질문을 묶어 그리는 단위. 펼친 질문은 따로 카드 하나, 접힌 질문은 이어지는 것끼리 카드 하나(Figma) */
internal sealed interface FaqGroup {
    data class Expanded(val item: FaqItem) : FaqGroup
    data class Collapsed(val items: List<FaqItem>) : FaqGroup
}

/** 답의 한 줄. [bullet] 이면 앞에 점을 찍고 줄이 넘어가도 들여 쓴다 */
internal data class AnswerLine(val text: String, val bullet: Boolean)

/**
 * 도움말 문구와 펼침 · 검색 규칙. 화면([HelpScreen])과 떼어 JVM 테스트로 고정한다(`HelpFaqTest`).
 *
 * **답은 코드의 실제 동작에서 가져왔다(Figma 캡션 `552:1893`).** 동작이 바뀌면 여기도 고친다.
 *
 * | 질문 | 근거 |
 * | --- | --- |
 * | 알람 시각 | 서버 `planning/services.py` `alarm_fields`: 시작 − 여유 → 도착, − 이동 → 출발, − 준비 → 알람 |
 * | 정시 도착 확률 | `planning/estimators.py` `compute_alarm_math`: 준비 · 이동 둘 다 흔들림을 알아야 낸다. 경로 보정이 없으면 이동 sd 0(`estimate_travel`), 준비가 한 숫자뿐이면 준비 sd 0 |
 * | 준비 시간 | `PrepOnboardingScreen`(5분~4시간), 아침 루틴 블록 범위(`RoutineEditorScreen`), 일정마다 블록 고르기(④), `planning/prep_observations.py`(알람 끄기 → GPS 출발, 정한 값의 0.5~1.5배만) |
 * | 쓸수록 | `.github/workflows/train-models.yml`(매일 23:17), `prediction/learning.py`(걷는 속도 · 시간대 · 경로), `planning/buffers.py`(4~15분, 늘릴 때 최대 5분 · 줄일 때 2분), `planning/refresh.py`(7일), 안 감 · 직접 누른 출발 · 도착은 학습에서 뺀다 |
 * | 여유 단계 | `planning/risk_options.py`(97 · 90 · 65%), 종류 기본값 `events/migrations/0002_seed_event_tags.py` |
 * | 직접 바꾸기 | 지금은 [시각 바꾸기] · "지각 위험 바꾸기" → ⑤ 세 단계. 분 단위로 고르는 H2-3 시트는 서버(BE-H3b · c) 뒤 |
 * | 캘린더 | `CalendarImportScreen`(앞으로 2주, 시각이 정해진 일정, 고른 것만, 읽기만, 장소는 줄마다 확인) |
 * | 안 울림 | `AlarmScheduler`(7일치 `setAlarmClock`, 상태 표시줄 아이콘), `BootReceiver`(재부팅 · 시간대), `MissedAlarms`(강제 중지) |
 */
internal object HelpFaq {

    val items: List<FaqItem> = listOf(
        FaqItem(
            HelpTopic.ALARM_TIME,
            "알람 시각은 어떻게 정해지나요?",
            "일정이 시작하는 시각에서 여유 시간, 이동 시간, 준비 시간을 차례로 빼서 정해요.",
            searchExtra = "오전 9:00 여유 10분 이동 42분 준비 28분 오전 7:40 " +
                "준비·이동 시간은 기록이 쌓일수록 내게 맞춰져요.",
        ),
        FaqItem(
            HelpTopic.ON_TIME_PROBABILITY,
            "정시 도착 확률은 뭐예요?",
            "알람에 맞춰 일어났을 때 일정 시작 전에 도착할 가능성이에요. 90%면 같은 아침이 10번 온다면 " +
                "9번은 제시간에 도착한다는 뜻이에요.\n\n" +
                "준비 시간과 이동 시간이 날마다 얼마나 달라지는지 알아야 계산할 수 있어요. 준비 시간이 한 숫자뿐이거나" +
                "(아침 루틴에 '몇 분~몇 분'이 없고 기록도 없을 때), 이동 시간이 얼마나 달라지는지 아직 모르면 " +
                "확률을 비워 둬요.",
        ),
        FaqItem(
            HelpTopic.PREP_TIME,
            "준비 시간은 어떻게 정하나요?",
            "처음에는 가입할 때 답한 준비 시간(5분~4시간)으로 시작해요. 내 정보 → 준비 시간에서 언제든 " +
                "바꿀 수 있어요.\n\n" +
                "내 정보 → 아침 루틴에 씻기·옷 입기처럼 할 일을 '몇 분~몇 분'으로 적어 두면 그 할 일들로 " +
                "준비 시간을 정해요. 알람 화면에서 그 아침에 할 일만 골라 둘 수도 있어요.\n\n" +
                "그 뒤에는 알람을 끈 때부터 집을 나선 때까지 실제로 걸린 시간을 보고 맞춰 가요. 정한 시간의 " +
                "절반보다 짧거나 1.5배보다 긴 아침은 다시 잠들었거나 잘못 잡힌 것으로 보고 빼요.",
        ),
        FaqItem(
            HelpTopic.LEARNING,
            "쓸수록 알람이 어떻게 맞춰지나요?",
            "매일 밤 11시 17분에 그날까지의 기록으로 다시 배우고, 앞으로 7일 안의 알람을 새로 맞춰요.\n\n" +
                "• 이동 시간: 실제로 걸린 시간을 길찾기 예상과 견줘서 내 걷는 속도, 출근 시간처럼 붐비는 때, " +
                "자주 다니는 길의 차이를 따로 익혀요.\n" +
                "• 준비 시간: 알람을 끈 뒤 집을 나서기까지 걸린 시간으로 맞춰요.\n" +
                "• 여유 시간: 확률로 예상한 것보다 자주 늦으면 늘리고(한 번에 5분까지), 늘 일찍 도착하면 " +
                "2분씩 줄여요. 4분에서 15분 사이에서만 움직여요.\n\n" +
                "'안 감'으로 표시한 일정과 출발·도착을 직접 누른 기록은 배우는 데 쓰지 않아요.",
        ),
        FaqItem(
            HelpTopic.RISK_LEVELS,
            "여유 있게·보통·빠듯하게는 뭐가 달라요?",
            "얼마나 확실하게 제시간에 도착할지예요. 같은 아침이 100번 온다면 여유 있게는 97번, 보통은 " +
                "90번, 빠듯하게는 65번은 제시간에 도착하도록 알람을 정해요. 여유 시간이 더해져 실제 확률은 " +
                "조금 더 높게 나와요.\n\n" +
                "여유 있게를 고르면 알람이 일찍 울리고, 빠듯하게를 고르면 더 잘 수 있지만 늦을 수 있어요. " +
                "처음에는 일정 종류에 맞춰 정해져요(시험·기차는 99%, 수업은 90%, 약속은 85%).",
        ),
        FaqItem(
            HelpTopic.CHANGE_ALARM,
            "알람 시각을 직접 바꿀 수 있나요?",
            "홈의 다음 알람 카드에서 [시각 바꾸기]를, 알람 화면에서 '지각 위험 바꾸기'를 누르면 세 단계 중에서 " +
                "고를 수 있어요. 단계마다 울릴 시각과 정시 도착 확률을 미리 보여 주고, 고르면 그 단계로 알람 " +
                "시각을 다시 정해요.\n\n" +
                "분 단위로 시각을 직접 고르는 기능은 준비 중이에요. 그 아침만 알람을 끄려면 홈 일정 줄의 " +
                "스위치를 꺼 주세요.",
        ),
        FaqItem(
            HelpTopic.CALENDAR,
            "캘린더 일정은 어떻게 가져오나요?",
            "내 정보 → 캘린더에서 가져오기에서 휴대폰 캘린더의 앞으로 2주 일정을 보여 줘요. 시작 시각이 " +
                "정해진 일정만 나오고, 하루 종일 일정은 도착할 시각을 알 수 없어 빼요.\n\n" +
                "가져올 일정을 직접 고르면 고른 것만 저장해요. 캘린더는 읽기만 하고 고치지 않아요. 일정에 적힌 " +
                "장소는 찾아서 줄마다 보여 주니 맞는지 확인해 주세요.",
        ),
        FaqItem(
            HelpTopic.ALARM_DIDNT_RING,
            "알람이 울리지 않았어요",
            "이럴 때 알람이 울리지 않아요.\n\n" +
                "• 앱을 강제 중지했을 때: 설정의 '강제 중지'나 정리 앱이 등록된 알람을 모두 지워요. 앱을 다시 " +
                "열면 알람을 다시 맞추고, 아직 출발 전이면 홈 맨 위에서 알려 드려요.\n" +
                "• 홈 일정 줄의 알람 스위치가 꺼져 있거나 '안 감'으로 표시한 일정\n" +
                "• 집 주소나 장소가 없어 '알람 없음'으로 보이는 일정\n\n" +
                "휴대폰을 다시 켜거나 시간대가 바뀌어도 알람은 다시 맞춰져요. 앞으로 7일치 알람을 미리 맞춰 " +
                "두고, 상태 표시줄에 알람 시계 아이콘이 보이면 맞춰진 거예요. 내 정보 → 알림·권한에서 알림이 " +
                "켜져 있는지도 확인해 주세요.",
        ),
    )

    /** 첫 질문 아래 계산식(Figma `formula`). 값은 예시다 */
    val formula: List<FormulaChip> = listOf(
        FormulaChip(FormulaChip.Kind.START, null, "오전 9:00"),
        FormulaChip(FormulaChip.Kind.BUFFER, "여유", "10분"),
        FormulaChip(FormulaChip.Kind.TRAVEL, "이동", "42분"),
        FormulaChip(FormulaChip.Kind.PREP, "준비", "28분"),
        FormulaChip(FormulaChip.Kind.RESULT, null, "오전 7:40"),
    )

    const val FORMULA_NOTE = "준비·이동 시간은 기록이 쌓일수록 내게 맞춰져요."
    const val SEARCH_HINT = "궁금한 것을 찾아보세요"

    fun item(topic: HelpTopic): FaqItem = items.first { it.topic == topic }

    /** 처음 펼칠 질문. i 버튼으로 왔으면 그 항목, 아니면 첫 질문(Figma) */
    fun initialExpanded(requested: HelpTopic?): HelpTopic = requested ?: items.first().topic

    /** 아코디언. 하나만 펼친다. 펼친 것을 다시 누르면 접는다 */
    fun toggle(current: HelpTopic?, tapped: HelpTopic): HelpTopic? = if (current == tapped) null else tapped

    /**
     * 질문 · 답의 낱말로 거른다. 띄어 쓴 낱말이 모두 들어 있어야 하고, 띄어쓰기는 보지 않는다("준비시간" 도
     * "준비 시간" 을 찾는다). 영문은 대소문자를 가리지 않는다. 비어 있으면 전부다.
     */
    fun search(query: String): List<FaqItem> {
        val words = query.trim().split(WHITESPACE).filter { it.isNotEmpty() }.map(::normalize)
        if (words.isEmpty()) return items
        return items.filter { item ->
            val haystack = normalize(item.question + item.answer + item.searchExtra)
            words.all { haystack.contains(it) }
        }
    }

    /** 찾은 뒤 펼칠 것. 펼쳐 둔 질문이 결과에 있으면 그대로, 없으면 첫 결과 */
    fun expandAfterSearch(current: HelpTopic?, results: List<FaqItem>): HelpTopic? =
        if (results.any { it.topic == current }) current else results.firstOrNull()?.topic

    /** 그릴 묶음. 펼친 질문 앞뒤로 접힌 질문 카드가 나뉜다 */
    fun groups(items: List<FaqItem>, expanded: HelpTopic?): List<FaqGroup> = buildList {
        val run = mutableListOf<FaqItem>()
        fun flush() {
            if (run.isNotEmpty()) add(FaqGroup.Collapsed(run.toList()))
            run.clear()
        }
        items.forEach { item ->
            if (item.topic == expanded) {
                flush()
                add(FaqGroup.Expanded(item))
            } else {
                run += item
            }
        }
        flush()
    }

    /** 답을 문단과 줄로 나눈다 */
    fun paragraphs(answer: String): List<List<AnswerLine>> =
        answer.split("\n\n").map { paragraph ->
            paragraph.split("\n").map { line ->
                if (line.startsWith(BULLET)) AnswerLine(line.removePrefix(BULLET), bullet = true)
                else AnswerLine(line, bullet = false)
            }
        }

    /** "8개" */
    fun countLabel(count: Int): String = "${count}개"

    /** 찾은 것이 없을 때 */
    fun emptyMessage(query: String): String = "‘${query.trim()}’에 맞는 질문이 없어요.\n다른 낱말로 찾아보세요."

    private const val BULLET = "• "
    private val WHITESPACE = Regex("\\s+")

    private fun normalize(text: String): String = text.lowercase().replace(WHITESPACE, "")
}
