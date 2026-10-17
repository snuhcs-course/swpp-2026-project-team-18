# Iteration 2 통합 상태와 프론트 작업 기록 — 팀 18 JustInTime

## 최신 통합 상태 (2026-10-17)

- 기능 기준: `back-iter2` `d434097`. `front-iter2` Phase A·B·C와 후속 BE-25·24·12·7·16·18·R-6·8·1을 통합했다.
- 백엔드 1,182건·Android 611건·HTTP 8종 실패 0(외부 키 34건 스킵), lint 오류 0. 실제 에뮬레이터 UI와 로컬 재생 E2E 약 386초 통과.
- 완료: 경로 그룹·안내, 리스크 옵션·저장·기기 예약, 현재 집 목적지, 알람 수동 선택 초기화, 100m 즉시 도착, 준비 시간 서버 범위, 가입 제한, 실제 보정 표본 수 표시.
- 남은 필수: R-3 학습 자동 실행, BE-8 자동차 후보 4종, BE-11 시각·운행 조건 경로, BE-20 반복 일정. 선택/후순위: BE-14·21·22, R-5·7. R-9는 삭제 도구 운영 지침이다.
- 최신 공용 배포·실시간 카카오·실기기 검증은 별도다. [TODO](../../task.md) · [추가 요청](backend-requests.md) · [검증](account-ranges-and-travel-counts.md).

## 2026-10-13 프론트 작업 기록

아래 브랜치·push 상태·테스트 수와 당시 남은 일은 각 Phase 종료 시점의 기록이다. 현재 완료 여부는 위 통합 상태를 따른다.

- 브랜치 `front-iter2`(worktree `SWPP-PJ-front-iter2`), 출발점 `b1eff1f`(main).
- Phase A·B 는 커밋 뒤 push 했다(`origin/front-iter2` = `71c9f0d`). Phase C 는 로컬 커밋만 했고 push·머지는 하지 않았다.
- 자세한 기록: 각 Phase 의 `*-design.md`·`*-review.md`·`*-verification.md`.

## Phase A — P4/P5 프론트 수정

바뀐 것
- "학습 중" 문구 제거, 변동성 안내를 "이동 기록이 쌓이면 …" 처럼 실제 동작에 맞게 수정.
- 도보·자전거 경로(1개)를 누르면 지도 화면(경로 미리보기)으로 이어짐.
- 회원가입 초기 준비 시간을 일정 화면과 같은 시계 다이얼로 입력(+5분 식 UI 정리).
- 칸별 서버 오류 표시(가까운 출발·도착 400 등), τ·이동 시간 출처 표시.

커밋: `ac29183`, `2572040`, `ab25817`, `8df2a34`(문서), `5a41095`(task.md)

테스트: 앱 단위 테스트 563건(실패 0), lint 오류 0. 백엔드는 고치지 않아 pytest·로컬 스위트를 돌리지 않았다(기준 865 passed).

## Phase B — P6 리포트 + 서버 LLM 요약

바뀐 것
- 주간 리포트 확장(요일별, 지각한 날, 계획 대비 실제, 지난주 비교)과 줄글 요약 API. LLM 실패·키 없음이면 고정 문장(템플릿)으로 대신함. 사용자 하루 30회 상한.
- 앱: 주간 리포트 화면 개편, 상세 리포트(⑨) 추가. LLM 키는 `.env` 에 자리만 둠.

커밋: `4e693e6`, `9cb90d6`, `630ebdb`(문서·README), `7a0c920`(task.md), `71c9f0d`(Phase C DB 설계 문서)

테스트: 백엔드 pytest 1040 passed(작업 전 865), 로컬 스위트 8/8(실패 0), 앱 단위 테스트 598건(실패 0), lint 오류 0.

## Phase C — P12 데이터 기록 + DB 스키마

[db-schema.md](db-schema.md) §7 의 추가 변경 A1~A7 과 §9 보존 명령. 전부 새 표·NULL 컬럼이라 기존 데이터·동작은 그대로다.

바뀐 것
- A1 도착 관측에 반경 진입 시각 `entered_at` 저장(앱이 보냄).
- A2 알람 계획이 바뀔 때마다 계획 이력(`planning_plan_revision`) 저장.
- A3 알람 기록 표와 `POST /api/alarm-logs/batch`. 앱 `AlarmLogQueue` 가 울림·끄기·미루기·자동 멈춤·등록 시각 변경을 올림.
- A4 API 요청 기록(`common_api_request_log`, 비밀·좌표 지움). 앱은 `X-App-Version` 헤더 전송.
- A5 LLM 호출 기록(`reports_llm_call`)과 전체 하루 상한 `LLM_GLOBAL_DAILY_LIMIT`(기본 100).
- A6 주간 요약 표: 이미 있어 변경 없음.
- A7 admin 섹션 번호와 일정 화면에 계획 이력·알람 기록·이동 관측 읽기 전용 inline.
- §9 `manage.py purge_logs`(요청 기록 30일, LLM 호출 90일).

커밋: `f212d5c`(A2), `9ba7e03`(A1·A3 서버), `07c0154`(A4·A5·§9), `f48d4dd`(A7), `fdcf0dc`(앱 A1·A3·A4), `60e8a89`(검증 기록), 이 요약 커밋

테스트(커밋 전 게이트, [phaseC-verification.md](phaseC-verification.md)): 백엔드 pytest 1127 passed(기준 1040), 로컬 스위트 8/8(실패 0), DB 기본값 규칙 검사 20건·위험 0, `makemigrations --check` 변경 없음, 앱 단위 테스트 618건(기준 598, 실패 0), lint 오류 0(경고 62, 기준과 같음).

## 당시 남은 일과 후속 상태

- 기기 위 E2E: 당시 미실행이었으나 2026-10-17 통합 후 실제 에뮬레이터에서 통과했다(상단 최신 결과).
- Figma F-0~F-13: 메인 세션이 10-13 에 반영했다. 노드 목록과 명세와 다른 점은 [figma-changes.md](figma-changes.md) 맨 위 "Figma 반영 상태".
- [backend-requests.md](backend-requests.md) R-1~R-9: 박준하(back-iter2)에게 전달. R-9(머지 뒤 사용자·일정 삭제 도구는 새 모델이 있는 코드로)는 머지 전에 알린다.
- Phase C 리뷰: APPROVED, 막지 않는 지적 5건 중 #1~#4 를 반영했다(#5 테스트 보강은 남김). [phaseC-verification.md](phaseC-verification.md) "리뷰 반영".
- 도착 정책: 후속 BE-24에서 100m 진입 즉시 도착으로 확정했다. 실제 건물·착석 도착 정답값 수집과 과거 체류 데이터 보정은 별도 연구·팀 결정이다.
- 버리는 QA 계정: 공용 서버(Neon)에는 만든 계정이 없다. 로컬 스위트가 실행마다 임시 SQLite 에 만든 계정(Phase C 회귀에서 11명)은 실행이 끝나면 DB 와 함께 사라진다.
- 배포: 공용 DB 에 `migrate` 하지 않았다. main 머지 뒤 `scripts/db_status.py`·`check_db_defaults.py` 로 확인한다.
