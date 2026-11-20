<!-- Partly AI-generated (Claude) -->
# 기록이 적은 주의 AI 요약 · 확률 판정 기준 (BE-LLM1, BE-R2)

앱 요청(11-19) BE-LLM1 · BE-R2 의 서버 쪽이다. 11-20, `back-iter4`.

## 1. 바뀐 것

| | 전 | 후 |
| --- | --- | --- |
| AI 요약을 부르는 주 | 끝난 주 · 도착 3건 이상 · 12주 안 | **도착 1건 이상** · 12주 안 · 진행 중인 주는 **일요일 12:00(서버 시간, KST) 뒤** |
| 기록이 적은 주(`thin_data`) | 템플릿만 | AI 요약. 비율 · 목표를 보내지 않고, 비율로 판단한 문장은 버린다 |
| "기록이 적다" 를 세는 수 | 요약은 도착 기록 수, 판정은 구간별 | 둘 다 **τ 가 있는 도착 기록 수**(새 `judged_count` = `calibration.scored_count`). 3건 미만이면 요약은 비율로 말하지 않고 판정은 보류한다 |
| 확률 카드 판정(`calibration.verdict`) | τ 구간마다 3건 이상 찬 구간이 있어야 판정 | **그 주 전체 기록 3건 이상**이면 판정. 요약과 같은 수(3)다 |
| 판정까지 남은 수 | 없음(앱이 가장 많이 찬 구간으로 셈) | `calibration.samples_needed` |

구현: `reports/summary.py`(`llm_allowed`, `build_facts`, `check_text`, 프롬프트 8 · 9번, `PROMPT_VERSION` 5),
`reports/services.py`(`calibration_from`, `_verdict`). 테스트: `reports/tests/test_summary.py`, `reports/tests/test_reports.py`.

## 2. 응답 (`GET /api/reports/weekly`, `calibration` 안)

| 필드 | 뜻 |
| --- | --- |
| `verdict` | `insufficient` · `calibrated` · `overconfident` · `conservative`. 그 주에 **채점한 기록(도착 기록 + 계획의 τ)이 3건 이상**이면 판정한다 |
| `mean_gap` | 실제 정시율 − 그 기록들의 τ 평균. 3건 미만이면 null. (전에는 신뢰 구간들의 편차 평균이었다) |
| `samples_needed` (새) | 판정까지 남은 기록 수 = `max(0, 3 − scored_count)`. 판정했으면 0 |
| `min_samples_for_reliable` | 3. 판정 기준이자 구간 점(`buckets[].reliable`)을 진하게 그리는 기준 |
| `reliable_bucket_count` | 그대로(구간별) |

**앱 할 일:** "N번 더 다니면 보여 드릴게요" 의 N 은 `samples_needed` 를 그대로 쓴다(`ReportMapping.kt` 의 "가장 많이 찬 구간"
계산을 지운다). 판정이 나왔으면(`verdict != insufficient`) 0 이다.

주간 리포트 최상위에 `judged_count`(τ 가 있는 도착 기록 수)가 더해졌다. 계획 없이 "도착했어요" 를 누른 아침(계획
상태가 `ok` 가 아니라 τ 가 없음)은 도착 수(`arrived_count`)에는 들지만 판정 수에는 들지 않는다. 목표 판정(`target_met`)도
이 수로 3건 이상일 때만 낸다.

**90일 캘리브레이션(`GET /api/reports/calibration`)도 같은 판정 규칙이다.** 90일 동안 채점한 기록이 3건 이상이면 판정한다
(전에는 구간 하나에 3건). 구간 점(`buckets[].reliable`)은 그대로 구간별이라, 표 전체는 "과신" 인데 진하게 그려진 점이 없을 수
있다. 앱의 안내 "같은 구간에서 N번 이상의 결과가 필요해요"(`ReportMapping.kt`)는 "기록이 N번 이상 필요해요" 로 바꿔야 맞다.

## 3. AI 요약 규칙

- 도착 0건인 주는 지금처럼 템플릿이다.
- 판정 수(`judged_count`)가 1~2건인 주(`thin_data`)는 LLM 에 `on_time_pct` · `target_pct` · `target_met` · `prev_on_time_pct` ·
  `on_time_change_pp` 를 **null 로** 보낸다. 보내지 않은 숫자는 문장에 쓸 수 없으므로(숫자 검사) "정시율 50%" 가 나오지
  않는다. 그래도 `%` 나 "목표" 가 든 문장이 오면 버리고 템플릿을 쓴다(`thin_judgement`). "정시율로 판단하기는 일러요" 는
  프롬프트가 시키는 말이라 통과한다.
  프롬프트 8번: 횟수만 말하고, 기록이 적어 비율로 판단하기 이르다는 것과 제안을 말한다.
- 진행 중인 주는 그 주 일요일 12:00 뒤에 부른다. 프롬프트 9번: 첫 문장에서 지금까지의 기록이라고 밝힌다. 템플릿은 전처럼
  "진행 중인 주라 지금까지 기록만 요약했어요." 로 시작한다.
- 진행 중인 주는 수치가 바뀔 때마다 다시 부른다(저장 해시가 바뀜). 일요일 저녁에는 일정 시작이 지날 때마다 수치가 바뀐다.
  사용자별 하루 30번 · 전체 하루 상한은 그대로라 상한을 넘으면 템플릿이다.
- "지금까지의 기록" 이라고 밝히라는 규칙(9번)과 "절반만 제때" 처럼 숫자 없이 비율로 판단한 문장은 검사로 막지 않는다.
- `PROMPT_VERSION` 이 5 로 올라 저장된 문장은 다음에 열 때 한 번씩 다시 만든다.

## 4. 한계

- 1~2건짜리 주의 LLM 문장은 템플릿과 내용이 거의 같다. 말투만 자연스러워진다.
- 판정은 3건이면 내리므로 "과신" 이 우연일 수 있다(예: 3건 중 1건 지각). 전에는 구간 하나에 3건이 모여야 했다.
- S-3 평가 세트에 "부르면 안 되는 주" 를 둔다면 기준은 "도착 0건 · 12주보다 오래됨 · 일요일 12:00 전의 진행 중인 주" 다.
