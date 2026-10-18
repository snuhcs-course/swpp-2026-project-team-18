# JustInTime — 백엔드 실행

Django·DRF 서버이며 팀 공용 Neon Postgres를 사용한다.
앱만 테스트할 때는 [공용 서버로 앱을 실행](../APP/README.md)하면 된다.
[구성·API](../README.md) · [환경변수 목록](../README.md#configuration)

## 개발 환경

Python 3.12를 사용한다. 저장소 루트에서 시작하며 기존 `.venv`는 재사용한다.

### macOS / Linux

```bash
cd backend
python3.12 -m venv .venv
source .venv/bin/activate
python -m pip install -r requirements/dev.txt
```

### Windows PowerShell

```powershell
cd backend
py -3.12 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements\dev.txt
```

이후 Windows에서는 `python` 대신 `.\.venv\Scripts\python.exe`를 사용한다.

## 설정과 실행

서버는 **`backend/.env`**를 읽는다. 받은 파일을 이 위치에 두고 기존 값을 덮어쓰지 않는다.
`DATABASE_URL`에는 팀 Neon direct 주소를 넣는다. 셸 환경변수는 `.env`보다 우선한다.

**로컬 서버도 같은 공용 DB에 쓴다.** 마이그레이션은 팀과 적용 시점을 맞추고,
삭제·정리·되돌리기 전에는 `python scripts/backup_db.py`로 저장소 밖에 백업한다.

가상 환경을 활성화한 `backend` 폴더에서 실행한다.

```bash
python manage.py runserver 127.0.0.1:8000
```

다른 터미널에서 `curl http://127.0.0.1:8000/api/health`로 연결·버전을 확인한다.
Windows에서는 `Invoke-RestMethod`를 쓸 수 있다. health는 DB·인증 정상 여부까지 검사하지 않는다.

에뮬레이터 연결은 [앱 안내](../APP/README.md)를 따른다.
실기기 LAN 연결은 `runserver 0.0.0.0:8000`을 사용하며 [기기 설정](../docs/device-setup.md)을 본다.

## 프론트 연결 계약 (BE-1~6)

- `POST /api/auth/password/check` `{password}` → `{min_length, letters_and_digits, messages}`.
  가입과 같은 규칙(8자 이상·영문+숫자·흔한 비밀번호 검사), 이메일 유사성 검사 없음.
  비로그인 호출, IP당 분당 600회 제한. 조건 미충족도 200이며 오류는 `messages`에 담긴다.
- `POST /api/auth/register`에 JSON boolean `terms_agreed: true` 필수.
  동의 시각은 `User.terms_agreed_at`에 저장하며 기존 계정은 null을 유지한다.
- 일정 목록·상세의 `alarm_enabled`는 null(자동)/true/false, `alarm_on`은 최종 켬/끔,
  `is_first_alarm`은 그날 켜진 첫 알람이다. 계산 완료된 일정을 사용자 시간대의 **일정 시작 날짜**로
  묶고 알람 시각·ID 순으로 판정한다. 첫 비-OFF 일정만 자동 ON이며 명시적 ON은 함께 켤 수 있다.
- `PATCH /api/events/{id}`의 `alarm_enabled`에 true/false/null을 보내 켬/끔/자동 복원.
  수정된 일정 한 건을 반환하므로 **목록을 다시 조회**한다. 켬/끔만 바꾸면 경로 재조회는 없다.

앱의 비밀번호 검사·약관 전송·스위치 저장·기기 예약 필터 연결을 완료했다.
[데모 QA](../qa/demo/README.md)는 같은 UI 흐름으로 로컬·공용 서버를 검증한다.

## 장소 거리·대중교통 표시 (BE-19, BE-9·10)

- 직접 입력 일정 생성, 출발지·목적지 수정, 경로 후보 조회는 두 장소의 직선 거리가
  **50m 미만이면 400**을 반환한다. 오류는 `error.details.destination`에
  한국어로 담긴다. 지정 출발지가 없으면 현재 프로필의 집을 기준으로 한다.
  장소가 없는 초안과 기존 일정의 켬/끔·제목 변경은 계속 허용한다.
- 대중교통 후보의 `badges`에는 같은 대중교통 후보들 사이에서 만족하는
  `최단 시간`·`최소 비용`·`최소 환승`을 모두 담는다. 동률도 같은 배지를 받는다.
  요금 미상(null)은 비용 비교에서 제외하며, 실제 0원은 포함한다.
- 탑승 구간의 `alt_vehicles`는 `[{name, vehicle_type}]`다. 카카오의 두 번째
  차량부터 원래 순서로 제공하고 대표 노선·중복·빈 이름은 제외한다.
  대체 노선이 없으면 빈 배열이다. 표시값 생성 때문에 외부 호출을 추가하지 않는다.
- 전용 시나리오: `python -m pytest apps/events/tests/test_location_distance.py
  apps/routing/tests/test_transit_display.py`.

## 지각 민감도·도보 표시·후보 지도 (BE-15, BE-13, BE-23)

- 일정 응답의 `lateness_sensitivity`는 기존 `effective_tau`로 판정한다.
  τ ≥ 0.95는 `high`, ≥ 0.85는 `normal`, 그 외는 `low`다.
- 도보 후보의 `steps`·`kcal`는 보폭 0.75m·1km당 50kcal를 사용한 거리 기반
  추정치(정수 반올림)다. 유효한 양수 거리가 없으면 null이며, 기존 `summary`에
  추정 표시를 포함해 앱에 전달한다.
- 도보·자전거 후보의 `path`·`path_distance_m`는 기존 경로 응답에서 추출한다.
  경로 조회를 추가하지 않으며 좌표가 없으면 빈 path다. 앱은 선택한 카드의 지도만
  표시하고 기존 일정 지도·GPS 추적 상태를 변경하지 않는다.

## 일정 추가·도착 정책 (BE-25·24)

- 직접 일정 추가가 성공하면 같은 사용자의 같은 현지 시작 날짜에 속한 기존 일정의
  `alarm_enabled`를 null(자동)로 돌린다. 새 일정의 명시적 선택·다른 날짜·다른 사용자·
  캘린더 가져오기는 유지한다. 기존 계획은 재계산하지 않으며 감사 로그에 `auto`를 남긴다.
- 앱은 첫 유효 GPS 위치가 목적지 100m 안이면 도착을 확정한다. 새 관측은
  `entered_at == observed_at`, `dwell_seconds == 0`이다. `dwell_confirmed`는 과거
  2분 체류 여부를 읽는 호환 필드이며 현재 도착 유효성·학습·리포트의 조건이 아니다.
- [전후 검증·직접 테스트](../docs/iter2/alarm-arrival-policy.md).

## 주소·장소 검색

`GET /api/places/search?q=...`는 도로명·지번 주소를 먼저 찾고, 없으면 기존 장소명 검색을 사용한다.
응답 형식은 동일하며 주소 결과의 `kakao_place_id`는 null이다. 주소는 정확도순으로 반환하고,
기준 좌표가 있으면 직선 거리를 표시한다. 지역·도로 중심점은 집 주소 후보에서 제외한다.
주소 조회가 실패해도 정상 장소 결과는 제공한다. 조회 실패 후 대체 결과도 없으면 `degraded: true`다.
[카카오 주소 검색](https://developers.kakao.com/docs/ko/local/dev-guide)을 사용하며 새 키·DB 변경은 없다.

## 경로·리스크·집 목적지 (BE-12·7·16·18)

- 후보 응답은 기존 `results`와 함께 `car/transit/walk/bicycle/fastest_key` 및 후보별 `guide`를 제공한다. 자동차 후보 확장은 BE-8로 남는다.
- `GET /api/events/{id}/risk-options`: 기존 계산 모델의 3개 τ 선택지. 정보가 부족하면 빈 옵션과 이유를 준다. 분포 `bins.mass`는 모형 비율이며 실측 건수가 아니다.
- 생성·수정의 `destination_is_home:true`는 `place`와 함께 보내지 않는다. 현재 프로필 집을 사용하며 `place` 행을 만들지 않는다. 마이그레이션 `events.0007` 적용이 필요하다.
- [직접 테스트·전후 검증](../docs/iter2/route-risk-home.md).

## 준비 시간·가입 제한·이동 기록 수 (R-6·R-8·R-1)

프로필 준비 시간은 5~240분(또는 기존 null), 가입은 IP당 10회/시간이다. 계획의 `travel_sample_count`는 실제 계산에 사용한 기존 추정기 표본 수이며 보정이 없으면 0이다. 마이그레이션 `planning.0010`이 필요하다.
[직접 테스트·전후 검증](../docs/iter2/account-ranges-and-travel-counts.md).

## 주간 반복 일정 (BE-20)

BE-20 주간 반복은 `repeat_weekdays`(0=월~6=일)로 생성하며, 7일 범위의 회차마다 기존 일정/알람을 사용한다. 회차별 OFF/삭제를 지원하고 삭제 날짜는 다시 생성하지 않는다. 마이그레이션 `events.0008`이 필요하다. [API/직접 테스트/검증](../docs/iter2/weekly-recurrence.md).

## 검증과 학습

`backend` 폴더에서 실행한다. 두 명령 모두 공용 DB와 분리된 테스트 DB를 사용한다.

```bash
python -m pytest
python scripts/run_local_suite.py
```

HTTP 스위트는 서버를 자동 실행한다. 대량 가입 검증용 `config.settings.local_suite`는 가입 한도만 해제하며, 운영의 IP당 10회/시간 제한은 전용 테스트·로컬 에뮬레이터에서 확인한다. 기존 서버가 8000 포트를 사용하면 종료하고 다시 실행한다.
카카오 키가 없으면 관련 항목은 건너뛰므로 실패 수와 건너뜀을 함께 확인한다.
결과는 [MVP 체크리스트](../docs/demo-checklist.md)에 기록한다.

`check_deployed.py`·`check_same_db.py`는 검증 데이터를 만들고,
`train_models`는 DB에 이동 보정값을 저장한다. 공용 DB에서 실행할 때 팀과 시점을 맞춘다.
준비 관측은 다음 계산에 직접 반영된다. 이동 보정은 GitHub Actions가 매일 한국 시간 23:17에 학습하도록 설정했다. 기존 일정은 다음 정상 알람 재계산 때 갱신값을 반영한다. 결정 시점 계획의 경로 원값을 사용하고 원값 없는 과거 이동은 제외한다. [설정/실행 확인](../docs/iter2/daily-training.md).
변동성 근거가 부족하면 도착 확률은 null이다. 앱 홈은 확률을 숨기며 알람 근거에는 “정시 도착 확률 · 아직 계산할 수 없음”과 이유를 표시한다.
