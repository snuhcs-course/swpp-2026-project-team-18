<!-- Partly AI-generated (Claude) -->
# 저장한 집 이름 겹침 · 리포트 문장 해요체 (서버)

`todos/iter4-todo.md` 의 **BE-H9**(H2-9 ⑭ 다른 집 이름 겹침)와 **BE-R1**(주간 요약·버퍼 이유 해요체)의 서버 구현이다.
마이그레이션은 없다. 저장한 집 API 전체는 [saved-places-api](../iter3/saved-places-api.md) 를 따르고, 여기에는 바뀐 것만 적었다.

구현: `backend/apps/accounts/saved_places.py` · `backend/apps/reports/summary.py` · `backend/apps/planning/buffers.py` · `backend/apps/planning/risk_options.py`
테스트: `apps/accounts/tests/test_saved_places.py`(`TestLabelKey`·`TestLabelDuplicate`·`TestMakeDefault`·`TestReviewFixes`) · `apps/reports/tests/test_summary.py` · `apps/planning/tests/test_buffers.py` · `apps/events/tests/test_risk_options.py`

## 1. 저장한 집 이름 겹침 (BE-H9)

### 규칙

- 다른 집의 `label` 로 **"집"** 을 쓸 수 없다. 앱이 기본 집을 늘 "집" 으로 보여 주므로 둘을 가를 수 없다.
- 같은 사용자의 **다른 저장한 집과 같은 이름**도 쓸 수 없다. 다른 사용자의 이름은 상관없다.
- 기본 집의 프로필 `home_label`(장소 이름이 들어가 있는 경우가 많다)과는 비교하지 않는다. 앱이 보여 주는 이름은 "집" 이다.
- DB 제약은 두지 않는다(마이그레이션 없음). 이미 겹쳐 있는 행은 그대로 둔다.

### 비교 방법

`label_key(label)` 이 같으면 같은 이름이다.

1. 유니코드 NFKC 로 맞춘다(전각 `ＨＯＭＥ` → `HOME`).
2. 서식 문자(유니코드 분류 Cf — 폭 없는 공백 U+200B, ZWJ U+200D, U+2060, BOM U+FEFF 등)를 뺀다(`"집​"` = `"집"`).
3. 앞뒤 공백을 빼고, 안의 공백(탭 포함)은 하나로 줄인다(`"우리  집"` = `"우리 집"`).
4. 대소문자를 무시한다(`casefold`, `"Home"` = `"home"`).

`"본가2"` 와 `"본가 2"` 는 다르다(공백을 없애지는 않는다). 저장하는 값은 지금처럼 앞뒤 공백만 뺀 입력 그대로다.

**빈 이름**: `label` 이 비었거나 공백 · 서식 문자뿐이면 400 `invalid`, `details.label: ["이름을 입력해 주세요."]`
(`message` 도 같은 문구). 409 가 아니다.

### 요청별 동작

| 요청 | 이름이 겹치면 |
| --- | --- |
| `POST /api/saved-places/` | 409 `saved_place_label_duplicate`, 저장하지 않는다 |
| `PATCH /api/saved-places/{id}/` (`label` 을 보낼 때만) | 409 `saved_place_label_duplicate` |
| `POST /api/saved-places/bulk/` | **실패하지 않는다.** 뒤에 `" 2"`·`" 3"`… 을 붙여 저장하고 `renamed` 에 알린다 |

- 고치는 행 **자신은 비교에서 뺀다.** `POST` 가 같은 `client_uuid` 나 50m 안의 행을 고치는 경우도 그 행 자신이다.
- 고치는 행의 **지금 이름과 같은 이름**(공백·대소문자만 다른 것 포함)은 늘 통과한다. 재시도나 다른 칸만 고치는 요청이 예전에 생긴 겹침 때문에 막히지 않게 하려는 것이다.
- 순서: 위치 규칙(`same_as_default`, 새 행이면 `saved_place_limit`)을 먼저 본 다음 이름을 본다. 그래서 기본 집 50m 안에 "집" 을 보내면 지금처럼 `same_as_default` 다.
  테스트로 고정한 경우:
  - 5개가 찬 뒤 **새 자리(6번째)** 에 있는 이름을 보내면 `saved_place_limit` 다(이름 오류가 아니다).
  - 5개가 찬 뒤 **같은 `client_uuid`**(그 행을 고침)로 다른 행의 이름을 보내면 한도는 보지 않고 `saved_place_label_duplicate` 다.
- **동시 요청**: 쓰는 요청(`POST` · `PATCH` · `bulk` · `make-default`)은 먼저 사용자의 프로필 행을 잠근다(사용자별 뮤텍스).
  전에는 이미 있는 저장한 집 행만 잠가서, 같은 새 이름이나 6번째 집을 거의 동시에 보낸 두 요청이 함께 검사를 통과했고
  `PATCH` 는 트랜잭션도 없었다. 이제 한 사용자의 쓰기는 한 줄로 선다. 기본 집 50m 검사도 잠근 프로필(지금 값)로 한다.
  (임시 Postgres 에서 같은 이름 8개 동시 `POST` → 201 하나 · 409 일곱, 다른 이름 8개 동시 → 201 다섯 · 409 셋. 고치기 전에는
  같은 이름 셋이 저장됐고 8개가 모두 저장됐다.)

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

번호 고르기 규칙(리뷰 수정 H9-3):

- **같은 요청의 뒤 항목들이 보낸 이름은 번호 후보에서 뺀다.** `["집", "집 2"]` 는 `"집 3"` · `"집 2"` 다. 전에는 앞 항목이
  `"집 2"` 를 가져가 뒤 항목이 `"집 2 2"` 가 됐다. (뒤 항목이 한도 등으로 건너뛰어져도 그 이름은 비워 둔다 — 단순하게.)
- **다시 보내도 이름이 바뀌지 않는다.** 그 행의 지금 이름이 같은 이름의 번호 붙은 꼴(`"본가 3"`)이고 여전히 비어 있으면, 더 작은
  번호(`"본가 2"`)가 비었어도 그대로 둔다. 40자 이름(앞을 잘라 `"가…가 2"`)도 다시 보내면 같다.

### `make-default` 로 내려온 예전 기본 집의 이름 (리뷰 수정 H9-1, 결정)

전에는 `home_label` 또는 `"집"` 을 이름 규칙 없이 넣어 "집" 이름의 행이나 다른 행과 같은 이름이 생겼다. 이제:

1. 예전 `home_label`(장소 이름인 경우가 많다)을 쓴다(공백 정리, 40자로 자름).
2. 그것이 비었거나 "집"(비교 키 기준)이면 **`"예전 집"`** 을 쓴다.
3. 남은 다른 저장한 집과 겹치면 `bulk` 처럼 `" 2"` 부터 비는 번호를 붙인다(`"신림"` → `"신림 2"`, `"예전 집 2"`).

실패하지 않는다(맞바꾸기는 사용자가 이미 고른 동작이다). 응답(목록 모양)의 `results` 에 바뀐 이름이 있다. 예전 기본 집은 잠근
프로필의 지금 값으로 읽는다 — 같은 때 들어온 프로필 집 변경을 덮지 않는다.

### 오류 문구 (리뷰 수정 H9-5)

앱이 서버 문구를 그대로 보이는 경우가 있어 저장한 집 문구도 해요체로 바꿨다. 코드는 그대로다.

| 코드 | 문구 |
| --- | --- |
| 409 `saved_place_limit` | 집은 기본 집 말고 5개까지 저장할 수 있어요. |
| 409 `same_as_default` | 기본 집에서 50m 안이에요. 기본 집으로 이미 저장돼 있어요. |
| 409 `saved_place_duplicate` | 저장한 다른 집에서 50m 안이에요. |
| 400 `invalid` `details.lat` | 국내 좌표만 집으로 저장할 수 있어요. |
| 400 `invalid` `details.label` | 이름을 입력해 주세요. |
| 400 `invalid` `details.items` (`bulk`) | 한 번에 20개까지 올릴 수 있어요. |

프로필(`PATCH /api/profile`) 집 좌표 문구도 해요체다 — [server-first-run-guide](server-first-run-guide.md) "같은 때 들어온 PATCH".

### 앱이 할 일

- **409 `saved_place_label_duplicate` 를 이름 칸 아래(`labelError`)로 보낸다.** 지금 `withAddFailure`(`ui/home/HomeSetupErrors.kt`)는
  `same_as_default` 만 이름 칸으로 보내고 나머지는 폼 아래 오류 줄에 서버 문구를 보인다. 문구는 `details.label[0]`(= `message`)
  을 그대로 써도 된다 — "집" 이면 "'집'은 기본 집 이름이라…", 아니면 "같은 이름의 집이 이미 있어요…". 다른 집 이름 고치기(PATCH)도 같다.
- **빈 이름(400, `details.label`)도 이름 칸으로 보낸다.** 지금 `savedPlaceAddMessage` 의 400 분기는 `lat`/`lng` 칸만 보고 없으면
  `message` 를 폼 오류 줄에 보인다. `fields["label"]` 이 있으면 이름 칸 아래에 보이는 것이 맞다. 저장 버튼을 빈 이름에서 막으면
  이 경우는 거의 없다.
- **저장 전 검사(H2-9 ⑭)는 같은 규칙을 쓴다**: NFKC → 서식 문자(Cf) 빼기 → 앞뒤 공백 빼기 + 안의 공백 하나로 → 대소문자 무시.
  그 결과가 비면 빈 이름, `"집"` 이면 금지, 기기의 다른 집과 같으면 겹침. Kotlin 예:
  `Normalizer.normalize(s, NFKC).filterNot { Character.getType(it) == Character.FORMAT.toInt() }.trim().split(Regex("\\s+")).joinToString(" ").lowercase()`.
  서버가 마지막 판정이므로 앱 검사가 조금 달라도 409 를 이름 칸에 보이면 된다.
- `bulk` 의 `renamed` 는 읽지 않아도 된다(바뀐 이름은 `results` 에 이미 있다). 원하면 "같은 이름이 있어 '본가 2' 로 바꿨어요"
  같은 알림을 한 번 띄울 수 있다(선택).
- `make-default` 뒤 목록은 응답의 `results` 로 바꾼다(지금처럼). 예전 기본 집 이름이 `"예전 집"` 이나 번호 붙은 이름일 수 있다.

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
| 공통 오류 문구 | 오류 문구 일괄 해요체는 A3/H2-1 범위라 이 작업에서 건드리지 않았다. 저장한 집 문구는 리뷰 수정(H9-5)에서 바꿨다 — 1절 "오류 문구" |

### 앱이 할 일

없다. 같은 필드(`summary`, `reason_text`, `unavailable_reason`)에 말투만 바뀐 문장이 온다.
