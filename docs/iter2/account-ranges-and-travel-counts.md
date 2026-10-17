# R-6·R-8·R-1 검증과 직접 테스트

2026-10-17, `back-iter2`. 기준 `20e2c0a`에서 세 전용 브랜치로 병렬 구현 후 R-6 → R-8 → R-1 순서로 통합했다. 기존 검증기·스로틀·추정기의 값을 재사용한다.

## 직접 확인

최신 백엔드에 `python manage.py migrate` 후 앱과 연결한다. `planning.0010`은 기존 계획·이력의 표본 수를 0으로 채우며, 재계산부터 실제 표본 수를 저장한다.

- **R-6:** 설정 → 평소 준비 시간. 5~240분 저장·재진입 유지를 확인한다. API로 `PATCH /api/profile`에 4·241을 보내면 400과 `error.details.onboarding_prep_min`의 한국어 오류가 나온다. 5·240은 허용하고, null·필드 생략은 기존 동작을 유지한다. 잘못된 요청과 함께 보낸 집 이름 등도 저장하지 않는다.
- **R-8:** 새 일회용 로컬 서버에서 `/api/auth/register`에 빈 JSON 요청을 10번 보내면 모두 400이고 계정은 생기지 않는다. 같은 IP의 실제 앱에서 유효한 가입을 제출하면 한국어 429가 보이며 계정이 생성되지 않는다. 성공·실패를 합쳐 시간당 10회이고 `Retry-After`를 제공한다. 공용 서버에서 한도를 소모하는 방식으로 확인하지 않는다.
- **R-1:** 이동 보정이 적용된 일정의 알람 근거에서 "이동 기록 N회로 보정"을 확인한다. 숫자는 기존 추정기가 실제 계산에 사용한 `sample_count`다. 보정이 없거나 경로 계산에 실패하면 0으로 초기화하며 구버전 API 응답은 기존 문구를 유지한다.

## 자동 검증 재현

API 34+ 에뮬레이터, 시간대 Asia/Seoul, 백엔드 가상환경·Android SDK/JDK를 준비하고 저장소 루트에서 실행한다.

```bash
backend/.venv/bin/python qa/demo/run.py --replay-routing --no-video --device emulator-5554

backend/.venv/bin/python -m pytest -c backend/pytest.ini \
  backend/apps/accounts/tests/test_api.py \
  backend/apps/accounts/tests/test_register_throttle.py \
  backend/apps/planning/tests/test_services.py -q
```

E2E는 실제 UI로 가입·준비 시간·일정을 저장하고 알람 → 루틴 → GPS 도착 → 리포트까지 확인한다. 보정 입력 20회를 일회용 DB에 준비해 실제 API·계획 화면의 숫자를 확인한 뒤 입력을 복원한다. 모든 이동 시나리오가 끝나면 실제 가입 한도를 채워 가입 화면의 429와 DB 계정 미생성을 확인한다. 제한을 우회하는 응답은 만들지 않는다.

외부 경로·지도만 기록·고정 응답을 재생한다. 공용 DB·실시간 카카오·공용 배포 검증은 별도다.

대량 계정을 만드는 HTTP 회귀 스위트는 `config.settings.local_suite`에서 가입 scope만 비활성화한다. 운영은 10/hour, 전용 API 테스트는 운영 rate와 가상 시계로 경계·IP 격리·시간 만료를 확인한다.

## 변경 전후

| 항목 | 변경 전 실제 실패 | 변경 후 |
| --- | --- | --- |
| R-6 | 4·241분이 200으로 저장되는 2건 | 동일 사례 400, 경계·null·생략·동반 필드 보존 포함 7건 통과 |
| R-8 | 11번째 가입이 201로 계정을 생성 | 429·미생성·한국어·대기 시간·다른 IP·비밀번호 검사 독립·한 시간 뒤 해제 통과 |
| R-1 | API·저장·초기화·이력 7건, 앱 실제 매퍼 1건 실패 | 동일 사례 통과, 구버전 응답·마이그레이션 기존 행·구버전 INSERT 기본값 확인 |

상세 로그는 `.artifacts/implementation/2026-10-17-r6-r8-r1/`의 기능별 폴더와 최종 회귀 로그에 있다. 세 구현자의 교차 리뷰에서 추가로 수정해야 할 문제는 발견되지 않았다.

## 최종 결과

- 백엔드 **1,182건**, Android **611건** 통과. HTTP **8종** 실패 0(외부 키 관련 34건 스킵), lint 오류 0, 마이그레이션 누락 없음.
- 최종 E2E **통과, 약 386초**. 실제 알람·루틴·출발/도착 각 1건·도착 상태 유지·리포트 1/1 반영을 확인했다. 기존 경로·리스크·집 목적지 시나리오도 함께 통과했다.
- 준비 시간 5분 설정, "이동 기록 20회로 보정", 가입 차단 한국어 문구를 실제 에뮬레이터 PNG로 직접 확인했다. 차단 계정은 DB에 없었다. 전용 가입 제한 UI 검증은 약 11초였다.
- 실행 증거: `.artifacts/demo-qa/20261017T083856Z/`. `device/03_prep_settings.png`, `device/feature-travel-samples.png`, `device/feature-signup-throttled.png`와 `result.json`에서 확인한다.
- 로컬 일회용 SQLite와 외부 경로·지도 재생 응답을 사용했다. 공용 배포·실시간 카카오 검증은 별도다.
