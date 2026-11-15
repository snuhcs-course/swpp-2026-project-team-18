<!-- Partly AI-generated (Claude) -->
# 저장한 집 이름 겹침 · 리포트 문장 해요체 (서버)

`todos/iter4-todo.md` 의 **BE-H9**(H2-9 ⑭ 다른 집 이름 겹침)와 **BE-R1**(주간 요약·버퍼 이유 해요체)의 서버 구현이다.
마이그레이션은 없다. 저장한 집 API 전체는 [saved-places-api](../iter3/saved-places-api.md) 를 따르고, 여기에는 바뀐 것만 적었다.

구현: `backend/apps/accounts/saved_places.py` · `backend/apps/reports/summary.py` · `backend/apps/planning/buffers.py` · `backend/apps/planning/risk_options.py`
테스트: `apps/accounts/tests/test_saved_places.py`(`TestLabelKey`·`TestLabelDuplicate`) · `apps/reports/tests/test_summary.py` · `apps/planning/tests/test_buffers.py` · `apps/events/tests/test_risk_options.py`

## 1. 저장한 집 이름 겹침 (BE-H9)

### 규칙

- 다른 집의 `label` 로 **"집"** 을 쓸 수 없다. 앱이 기본 집을 늘 "집" 으로 보여 주므로 둘을 가를 수 없다.
- 같은 사용자의 **다른 저장한 집과 같은 이름**도 쓸 수 없다. 다른 사용자의 이름은 상관없다.
- 기본 집의 프로필 `home_label`(장소 이름이 들어가 있는 경우가 많다)과는 비교하지 않는다. 앱이 보여 주는 이름은 "집" 이다.
- DB 제약은 두지 않는다(마이그레이션 없음). 이미 겹쳐 있는 행은 그대로 둔다.

### 비교 방법

`label_key(label)` 이 같으면 같은 이름이다.

1. 유니코드 NFKC 로 맞춘다(전각 `ＨＯＭＥ` → `HOME`).
2. 앞뒤 공백을 빼고, 안의 공백(탭 포함)은 하나로 줄인다(`"우리  집"` = `"우리 집"`).
3. 대소문자를 무시한다(`casefold`, `"Home"` = `"home"`).

`"본가2"` 와 `"본가 2"` 는 다르다(공백을 없애지는 않는다). 저장하는 값은 지금처럼 앞뒤 공백만 뺀 입력 그대로다.

### 요청별 동작

| 요청 | 이름이 겹치면 |
| --- | --- |
| `POST /api/saved-places/` | 409 `saved_place_label_duplicate`, 저장하지 않는다 |
| `PATCH /api/saved-places/{id}/` (`label` 을 보낼 때만) | 409 `saved_place_label_duplicate` |
| `POST /api/saved-places/bulk/` | **실패하지 않는다.** 뒤에 `" 2"`·`" 3"`… 을 붙여 저장하고 `renamed` 에 알린다 |

- 고치는 행 **자신은 비교에서 뺀다.** `POST` 가 같은 `client_uuid` 나 50m 안의 행을 고치는 경우도 그 행 자신이다.
- 고치는 행의 **지금 이름과 같은 이름**(공백·대소문자만 다른 것 포함)은 늘 통과한다. 재시도나 다른 칸만 고치는 요청이 예전에 생긴 겹침 때문에 막히지 않게 하려는 것이다.
- 순서: 위치 규칙(`same_as_default`, 새 행이면 `saved_place_limit`)을 먼저 본 다음 이름을 본다. 그래서 기본 집 50m 안에 "집" 을 보내면 지금처럼 `same_as_default` 다.

오류 응답(공통 형식):

```json
HTTP 409
{"error": {"code": "saved_place_label_duplicate",
           "message": "같은 이름의 집이 이미 있어요. 다른 이름을 써 주세요.",
           "details": {"label": ["같은 이름의 집이 이미 있어요. 다른 이름을 써 주세요."]}}}
```

"집" 이면 문구가 `"'집'은 기본 집 이름이라 쓸 수 없어요. 다른 이름을 써 주세요."` 다. 코드는 같다.

### `bulk` 는 이름을 바꿔 저장한다 (결정)

앱(front-iter4 B2 `HomeViewModel.syncSavedHomes`)은 처음 로그인한 기기의 목록을 `bulk` 로 한 번 올린다. 기기 목록은
BE-H9 전에 만들어졌으므로 같은 이름이 둘 있거나 "집" 이 있을 수 있다.

- `bulk` 가 409 를 주면 앱은 400 이 아니라서 "다음 기회에 다시" 로 두고, 다음 실행에서도 같은 409 를 받는다 — **첫 업로드가 영영 끝나지 않는다.**
- 50m 규칙처럼 `skipped` 로 빼면 앱은 응답의 `results` 로 기기 목록을 바꾸고 `limit` 만 사용자에게 알리므로 **그 집이 말없이 사라진다.**

그래서 `bulk` 는 겹치는 항목의 이름 뒤에 `" 2"` 부터 비는 번호를 붙여 저장한다(40자를 넘으면 앞을 자른다). 같은 요청 안의 앞 항목과도 비교한다.
응답에 `renamed` 가 늘었다:

```json
HTTP 200
{"default": {...}, "results": [...], "skipped": [],
 "renamed": [{"client_uuid": "u-2", "label": "본가 2", "requested_label": "본가"}]}
```

같은 기기 목록을 다시 보내도 늘지 않는다(`client_uuid` 행 자신과는 겹치지 않는다).

### 앱이 할 일

- `saved_place_label_duplicate` 를 `labelError`(이름 칸 아래)로 보낸다. 지금 `withAddFailure` 는 `same_as_default` 만 이름 칸으로 보내고 나머지는 폼 아래 오류 줄에 서버 문구를 보인다 — 문구는 그대로 써도 된다.
- 저장 전 검사(H2-9 ⑭)는 같은 규칙을 쓰면 서버와 엇갈리지 않는다: 앞뒤 공백 빼기 + 안의 공백 하나로 + 대소문자 무시, "집" 금지.
- `bulk` 의 `renamed` 는 읽지 않아도 된다(바뀐 이름은 `results` 에 이미 있다). 원하면 "같은 이름이 있어 '본가 2' 로 바꿨어요" 를 띄울 수 있다.

### 남은 것

- `make-default` 는 예전 기본 집을 행으로 내릴 때 이름을 `home_label` 또는 `"집"` 으로 쓴다(iter3 동작 그대로). `home_label` 이 비어 있던 계정이면 "집" 이름의 행이 생길 수 있다. 그 행을 고치는 요청은 이름을 바꾸지 않는 한 통과한다. 이름을 어떻게 줄지는 팀 결정 뒤에 바꾼다.

## 2. 리포트 문장 해요체 (BE-R1)

### 바꾼 것

| 어디 | 전 | 후 |
| --- | --- | --- |
| `summary.SYSTEM_PROMPT` 2번 | "~함 · ~음 · ~임 으로 끝낸다" | 친근한 해요체("~했어요", "~였어요", "~예요", "~해 보세요"), "~함·~음·~임·~다" 금지 |
| 프롬프트 3·5번 | "지각은 없었음", "기록이 없음" | "지각은 없었어요", "기록이 없어요" |
| 템플릿 문장 · 제안 문장(`_suggestion`) | "…달성했음." "…나서 볼 것." | "…달성했어요." "…나서 보세요." |
| `summary.FALLBACK` | "…정리하지 못했음. 아래 수치를 확인할 것." | "…정리하지 못했어요. 아래 수치를 확인해 주세요." |
| `buffers.reason_text` (리포트 `buffer.adjustments[].reason_text`) | "…늦었음. … 늘림." | "…늦었어요. … 늘렸어요." |
| `risk_options` `unavailable_reason` (앱 알람 시각 시트가 그대로 보임) | "…볼 수 있습니다." 등 | "…볼 수 있어요." 등 |

- `PROMPT_VERSION` 2 → **3**. 해시가 바뀌어 저장된 LLM 요약(`WeeklySummary`)은 다음 조회 때 다시 만든다(LLM 을 부를 수 있는 주만. 나머지는 템플릿).
- 문장 내용·숫자는 그대로다. 수치 검사(`check_text`)도 그대로다.

### 길이 상한

해요체는 문장마다 1~2자 길다. 가장 긴 템플릿 조합(진행 중 머리말 + 5문장, 세 자리 수)이 232자로 `MAX_CHARS` 400 보다 한참 작다.
프롬프트의 "300자 이내" 와 `MIN_CHARS` 40 도 그대로 둔다. 테스트 `test_longest_template_fits_the_length_check` 가 고정한다.

### 그대로 둔 것

| 문장 | 이유 |
| --- | --- |
| 위험 선택지 `label` "안전 · 보통 · 도박" | 앱(front-iter4 `AlarmTimeChoice.quickLabel`)이 key 로 "여유 있게 · 보통 · 빠듯하게" 를 직접 쓰고 서버 라벨은 모르는 key 일 때만 쓴다. key(`safe`·`normal`·`gamble`)도 바꾸지 않는다 |
| 위험 선택지 `auto_reason` ("… 일정 기본값 τ 0.97 적용 …") | 앱이 화면에 보이지 않는다 |
| 알람 계획 `tau_source_label` "직접 고른 값" 등 | 앱이 이 문자열을 비교에 쓴다(`MANUAL_TAU_SOURCE`) |
| 원인 이름 "준비 초과 · 이동 초과 · 늦은 출발 · 계획 여유 부족 · 원인 측정 못 함" | LLM 에 보내는 집계값의 고정 문자열이다. 문장 끝이 아니다 |
| 버퍼 배치 결과 "늘림 · 줄임 · 그대로(…)" | 배치 로그 전용 |
| 저장한 집의 기존 오류 문구("…저장할 수 있다." 등) · 공통 오류 문구 | 앱이 코드로 자기 문구를 쓴다. 오류 문구 일괄 해요체는 A3/H2-1 범위라 이 작업에서 건드리지 않았다 |

### 앱이 할 일

없다. 같은 필드(`summary`, `reason_text`, `unavailable_reason`)에 말투만 바뀐 문장이 온다.
