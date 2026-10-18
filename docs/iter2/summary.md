# Iteration 2 프론트(front-iter2) 작업 요약 — 팀 18 JustInTime

- 브랜치 `front-iter2`(worktree `SWPP-PJ-front-iter2`), 출발점 `b1eff1f`(main).
- Phase A·B·C 와 그 뒤 작업은 커밋 뒤 `origin/front-iter2` 에 push 했다. main 머지는 하지 않았다.
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

## 후속 — 모든 수단 지도 미리보기 · 지도 정확도 (2026-10-18)

바뀐 것
- 경로선이 지도 가운데에서 두 배 멀리 그려지던 것을 고침. 정적 지도 축척 실측은 요청 단위당 `2^(lv-3)` m 이고(예전 값은 마커를 넣고 재서 두 배 틀림), 좌표 변환은 카카오 지도 좌표계 EPSG:5181 로 바꿈. 경로 미리보기·일정 지도·이동 중 지도·⑰ 핀·끌기·"이 지역 재검색"이 함께 맞음.
- 자동차·대중교통도 지도로 미리 봄. 서버 후보가 모든 수단에 `path` 를 실음(`preview_path`, 1m 단순화). 대중교통은 카드를 누르면 고르기이고 "지도로 보기 ›" 로 엶. 서버는 배포 전이라 지금 공용 서버에서는 자동차·대중교통 카드에 "지도 미리보기를 받지 못했음" 이 뜸.
- Figma F-14: ⑬-a·⑬-b·⑬-e 와 ⑬-e 자동차 변형(`386:18`)을 실제 데이터로, ④-a 를 앱의 이동 중 화면과 같게. [figma-changes.md](figma-changes.md) 맨 아래.

커밋: `e8339ea`(축척·좌표계), `798d300`(모든 수단 미리보기), 이 기록 커밋

테스트: 백엔드 pytest 1137 passed(기준 1134), 앱 단위 테스트 631건(실패 0), lint 오류 0(경고 62, 기준과 같음). 기기 위 확인은 하지 않았다.

## 남은 일

- 바뀐 QA 흐름의 기기 위 E2E: 아직 돌리지 않았다(Phase A 재생 모드 `qa/demo/run.py`, Phase B `DemoFlowTest` 리포트 단계, Phase C 알람 기록 큐, 지도 미리보기 전 수단).
- Figma F-0~F-13 은 메인 세션이 10-13 에, F-14 는 10-18 에 반영했다. 노드 목록과 명세와 다른 점은 [figma-changes.md](figma-changes.md) 맨 위 "Figma 반영 상태".
- [backend-requests.md](backend-requests.md) R-1~R-10: 박준하(back-iter2)에게 전달. R-9(머지 뒤 사용자·일정 삭제 도구는 새 모델이 있는 코드로)는 머지 전에 알린다. R-10 은 경로 후보 응답이 바뀐다는 알림이다.
- Phase C 리뷰: APPROVED, 막지 않는 지적 5건 중 #1~#4 를 반영했다(#5 테스트 보강은 남김). [phaseC-verification.md](phaseC-verification.md) "리뷰 반영".
- 팀 결정 대기: 도착 시각 편향(geofence 진입·체류 기준 도착 시각의 치우침)을 어떻게 다룰지, 실제 도착 시각의 정답값(ground truth)을 어떻게 모을지.
- 버리는 QA 계정: 공용 서버(Neon)에는 만든 계정이 없다. 로컬 스위트가 실행마다 임시 SQLite 에 만든 계정(Phase C 회귀에서 11명)은 실행이 끝나면 DB 와 함께 사라진다.
- 배포: 공용 DB 에 `migrate` 하지 않았다. main 머지 뒤 `scripts/db_status.py`·`check_db_defaults.py` 로 확인한다.
