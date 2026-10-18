# Iteration 2 통합 상태와 프론트 작업 기록 — 팀 18 JustInTime

## 최신 통합 상태 (2026-10-19)

- 기능 기준: `back-iter2`에서 구현한 BE-20 통합 소스. `front-iter2` Phase A/B/C와 후속 BE-25/24/12/7/16/18/20/R-6/8/1을 포함한다. R-3은 GitHub Actions 구현/수동 저장 검증 완료, 실제 예약 이벤트 확인 대기다.
- 백엔드 BE-20 전용 13건 / 전체 1,250건 / 일회용 PostgreSQL 일정/계획 이력 231건 통과. HTTP 8종 실패 0(2항목 스킵). Android JVM 677건 / lint 오류 0, 2026-10-19 실제 에뮬레이터 UI/전체 로컬 재생 E2E 약 597초 통과.
- 완료: 경로 그룹·안내, 리스크 옵션·저장·기기 예약, 현재 집 목적지, 알람 수동 선택 초기화, 100m 즉시 도착, 준비 시간 서버 범위, 가입 제한, 실제 보정 표본 수 표시, 주간 반복 일정/회차별 OFF/삭제.
- 남은 필수: R-3 실제 예약 실행 확인, BE-8 자동차 후보 4종, BE-11 시각/운행 조건 경로. 선택/후순위: BE-14/21/22, R-5/7. R-9는 삭제 도구 운영 지침이다.
- [학습 배치 설정/실행 검증](daily-training.md): 결정 시점 경로 원값 사용(G1/G2), 원값 없는 과거 이동 제외, 공개 로그 집계만 출력.
- 최신 공용 배포·실시간 카카오·실기기 검증은 별도다. [TODO](../../task.md) · [추가 요청](backend-requests.md) · [이전 통합 검증](account-ranges-and-travel-counts.md) / [BE-20 검증/직접 테스트](weekly-recurrence.md).

## 이전 작업 기록

과거 브랜치/커밋/테스트 수치는 각 단계의 기록이다. 현재 완료 여부는 위 상태와 TODO를 따른다.

- Phase A / 입력과 화면 연결: [설계](phaseA-design.md) / [검증](phaseA-verification.md).
- Phase B / 주간 리포트와 서버 요약: [설계](report-design.md) / [검증](phaseB-verification.md).
- Phase C / 관측/계획 이력/API 기록/관리 도구: [DB 설계](db-schema.md) / [검증](phaseC-verification.md).
- [Figma 반영 상태](figma-changes.md) / [추가 요청과 삭제 도구 운영 지침](backend-requests.md).
