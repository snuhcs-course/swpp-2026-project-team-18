# JustInTime — 백엔드 실행

Django·DRF 서버이며 팀 공용 Neon Postgres를 사용한다.
앱만 테스트할 때는 [공용 서버로 앱을 실행](../APP/README.md)하면 된다.
[구성·API](../README.md) · [환경변수 목록](../README.md#configuration) · [배포·운영](../docs/team-setup.md)

## 개발 환경

Python 3.12를 사용한다. 저장소 루트에서 시작한다.

### macOS / Linux

```bash
cd backend
python3.12 -m venv .venv
source .venv/bin/activate
python -m pip install -r requirements/dev.txt
```

`uv`로 만든 환경에는 pip이 없다. `uv pip install --python .venv/bin/python -r requirements/dev.txt`를 쓴다.

### Windows PowerShell

```powershell
cd backend
py -3.12 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements\dev.txt
```

이후 Windows에서는 `python` 대신 `.\.venv\Scripts\python.exe`를 쓴다.

`requirements`가 바뀌면 같은 설치 명령을 다시 실행한다. 빠진 패키지가 있으면 테스트 수집이 실패한다.

## 설정과 실행

서버는 **`backend/.env`**를 읽는다. `.env.example`을 복사하고 값은 팀에서 받는다.
셸 환경변수가 `.env`보다 우선한다.

- `DATABASE_URL`: 팀 Neon **direct** 주소. 비우면 서버가 시작하지 않는다.
- `KAKAO_NATIVE_APP_KEY`·`GOOGLE_WEB_CLIENT_ID`: 소셜 로그인. 비우면 그 로그인만 503이다.
- `JWT_ROTATE_REFRESH_TOKENS`: 앱이 새 refresh를 저장하기 전까지 비워 둔다([토큰 보안](../docs/iter3/token-security-api.md)).

**로컬 서버도 공용 DB에 쓴다.** 마이그레이션은 팀과 시점을 맞추고, 삭제·정리 전에는
`python scripts/backup_db.py`로 백업한다. 새 컬럼에는 `db_default`를 준다(`python scripts/check_db_defaults.py`).

```bash
python manage.py runserver 127.0.0.1:8000
curl http://127.0.0.1:8000/api/health
```

에뮬레이터 연결은 [앱 안내](../APP/README.md), 실기기 LAN 연결(`runserver 0.0.0.0:8000`)은 [기기 설정](../docs/device-setup.md)을 따른다.

## API 계약

요청·응답 전체는 각 문서를 따른다. 아래는 앱이 놓치기 쉬운 규칙만 적었다.

| 기능 | 문서 |
| --- | --- |
| 일정 수정 · 준비 시간 포함 · 안 감 | [event-edit](../docs/iter3/event-edit-api.md) · [include-prep](../docs/iter3/include-prep-api.md) · [skip-event](../docs/iter3/skip-event-api.md) |
| 소셜 로그인 · 토큰 폐기 | [social-login](../docs/iter3/social-login-api.md) · [token-security](../docs/iter3/token-security-api.md) |
| 대중교통 후보 5개 · 버스만 다른 경로 합치기 | [transit-candidates](../docs/iter3/transit-candidates.md) |
| 알람 초기화 · 100m 도착 | [alarm-arrival-policy](../docs/iter2/alarm-arrival-policy.md) |
| 경로 그룹 · 리스크 · 집 목적지 | [route-risk-home](../docs/iter2/route-risk-home.md) |
| 준비 시간 범위 · 가입 제한 · 이동 표본 수 | [account-ranges](../docs/iter2/account-ranges-and-travel-counts.md) |
| 주간 반복 | [weekly-recurrence](../docs/iter2/weekly-recurrence.md) |

- **가입·비밀번호:** `POST /api/auth/password/check`는 조건 미충족도 200이고 결과를 `messages`에 담는다.
  가입에는 `terms_agreed: true`가 필요하고 IP당 10회/시간으로 제한한다.
- **알람 켬/끔:** `alarm_enabled`는 null(자동)/true/false, `alarm_on`은 최종 결과, `is_first_alarm`은 그날 켜진 첫 알람이다.
  PATCH는 일정 한 건만 돌려주므로 목록을 다시 조회한다. 직접 일정을 추가하면 같은 날 기존 일정의 `alarm_enabled`가 null로 돌아간다.
- **장소:** 출발지와 목적지가 50m 미만이면 400이다. 주소 검색은 도로명·지번을 먼저 찾고, 주소 결과의 `kakao_place_id`는 null이다.
- **경로 후보:** `fastest_key`는 전체 최단, `preferred_key`·`preferred_mode`는 사용자가 전에 고른 경로다
  (`preferred_basis`: `same_destination` 또는 `recent_mode`, 기록이 없으면 null). 탑승 구간의 `alt_vehicles`는 같은 구간의 다른 노선이다.
- **도착:** 목적지 100m 안의 첫 유효 위치에서 도착한다. `detector: "manual"` 관측은 위치가 없거나 오차가 커도 받는다.
- **지각 민감도:** τ ≥ 0.95는 `high`, ≥ 0.85는 `normal`, 나머지는 `low`다.
- **도착 확률:** 변동성 근거가 없으면 null이다. 앱은 홈에서 숨기고 알람 근거에 이유를 표시한다.

## 검증과 학습

두 명령 모두 공용 DB와 분리된 테스트 DB를 쓴다. 카카오 키가 없으면 관련 항목은 실패가 아니라 건너뜀이다.

```bash
python -m pytest
python scripts/run_local_suite.py   # HTTP 8종, 8000 포트가 비어 있어야 한다
```

- 준비 관측은 다음 계산에 바로 반영된다. 설정한 준비 시간의 0.5~1.5배 안의 아침만 쓴다.
- 이동 보정과 안전 버퍼(4~15분)는 GitHub Actions가 매일 23:17(KST)에 학습하고, 다음 7일 알람을 경로 재조회 없이 다시 계산한다.
- 학습은 운영 DB에 마이그레이션이 다 적용됐는지 먼저 확인하고, 아니면 멈춘다. 배포가 `main`보다 뒤처지면 학습도 멈춘다.
- `train_models`·`check_deployed.py`·`check_same_db.py`는 공용 DB에 쓴다. 팀과 시점을 맞춘다. [학습 운영](../docs/iter2/daily-training.md)
