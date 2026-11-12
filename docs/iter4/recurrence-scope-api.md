# 반복 일정 범위 수정·삭제 API 계약 (iter4 A2, 제안)

반복 일정의 한 회차를 고치거나 지울 때 아이폰 캘린더처럼 **"이 일정만 / 이후 일정 모두"** 를 고른다.
앱은 이 문서대로 먼저 만들어 두었고, **서버가 `APP_VERSION` 을 `0.21.0` 이상으로 올려야 켜진다**
(`RecurrenceScope.SINCE_SERVER`). 그 전에는 시트가 뜨지 않고 지금처럼 그 회차만 바뀐다.

앱 구현: `data/repository/RecurrenceScope.kt`, `EventRepository.updateEvent/deleteEvent`,
`ui/events/RecurrenceScopeSheet.kt`, `HomeViewModel.submitEdit/chooseSaveScope/deleteEvent` ·
테스트: `RecurrenceScopeTest`, `EventRepositoryTest`(범위), `RecurrenceScopeUiTest`

## 1. 지금 서버 (0.20.0)

- `PATCH /api/events/{id}` 는 그 회차만 바꾼다([event-edit-api.md](../iter3/event-edit-api.md) 5절).
- `repeat_weekdays` 를 PATCH 에 실으면 400 이다("반복 규칙은 생성할 때 설정하며…", `serializers.py`).
- `DELETE /api/events/{id}` 는 그 날짜만 뺀다(`EventRecurrence.excluded_dates`).
- `EventRecurrence` 에 끝나는 날이 없다. 그래서 "이 회차부터 끝내기"를 나타낼 수 없다.

## 2. 수정: `PATCH /api/events/{id}`

본문에 `scope` 를 더한다. 나머지 필드는 지금과 같다(바뀐 필드만).

| `scope` | 뜻 |
| --- | --- |
| 없음 · `"this"` | 지금과 같다. 그 회차만 바꾼다 |
| `"following"` | 이 회차부터 앞으로의 회차를 모두 바꾼다 |

`"following"` 일 때:

1. 원래 규칙을 이 회차 전날로 끝낸다(`EventRecurrence.until`, 새 필드).
2. 이 회차 날짜부터 새 규칙을 만든다. 값은 원래 규칙 + 이번 PATCH 의 바뀐 필드다.
3. `repeat_weekdays`(0 = 월 … 6 = 일, 오름차순)가 오면 새 규칙의 요일로 쓴다. **`following` 일 때만 받는다.**
   `this` 에 요일이 오면 지금처럼 400.
4. 7일 창 안의 회차를 다시 펼치고 알람 계획을 다시 만든다.
5. 응답은 지금처럼 이 회차의 `EventDto`. 앱은 성공하면 홈을 통째로 다시 받아 다른 회차와 기기 알람을 맞춘다.

앱이 보내는 예:

```json
{"title": "알고리즘 수업", "scope": "following", "repeat_weekdays": [1, 3]}
```

정해야 할 것(팀): 이미 따로 고친 미래 회차를 덮어쓸지. 아이폰은 덮어쓴다.

## 3. 삭제: `DELETE /api/events/{id}?scope=following`

| `scope` | 뜻 |
| --- | --- |
| 없음 · `this` | 지금과 같다. 그 날짜만 뺀다 |
| `following` | 이 회차와 앞으로의 회차를 지운다. 원래 규칙을 이 회차 전날로 끝낸다(`until`) |

성공은 지금처럼 204. 반복이 아닌 일정에 `following` 이 오면 그냥 지운다(오류 아님).

## 4. 버전

- 이 계약을 넣는 배포에서 `APP_VERSION` 을 `0.21.0` 으로 올린다. 앱은 `/api/health` 의 `version` 으로 판단한다.
- 구버전 서버는 `scope` 를 모르는 필드로 **조용히 무시한다.** 그러면 사용자는 앞으로의 일정을 다 바꿨다고
  믿는데 한 회차만 바뀐다. 그래서 앱은 버전을 확인하지 못하면 "이후 일정 모두"를 아예 보이지 않는다.
- 다른 버전으로 내면 앱의 `RecurrenceScope.SINCE_SERVER` 를 같이 바꾼다.

## 5. 앱 동작 요약

- 반복 회차 수정에서 [저장] → 바텀시트 "이 일정만 저장 / 이후 일정 모두 저장 / 취소". 반복이 아니면 바로 저장.
- 수정 화면에서 요일(반복 칸)을 고칠 수 있다. 요일을 바꿨으면 시트에 "이후 일정 모두 저장"만 보인다.
  마지막 요일은 끌 수 없다(반복 끝내기는 삭제의 "이후 일정 모두").
- ④ 알람 결정의 "일정 삭제" → "이 일정만 삭제 / 이후 일정 모두 삭제 / 취소".
- 성공하면 홈을 다시 받고(목록 · 기기 알람 다시 등록), 결정 화면은 새 계획으로 바뀐다.
