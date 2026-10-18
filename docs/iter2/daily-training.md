# R-3 / GitHub Actions 학습 배치

학습은 `.github/workflows/train-models.yml`이 기본 브랜치 `main`에서 매일 한국 시간 **03:17**(UTC 18:17)에 실행한다. GitHub 예약 실행은 지연될 수 있어 정확한 분 단위 실행을 보장하지 않는다.

## 설정

1. 저장소 Actions Secret `TRAINING_DATABASE_URL`에 기존 Neon direct 접속 URL을 직접 등록한다. 로컬 `.env`의 키 이름은 `DATABASE_URL` 그대로 둔다.
2. 현재 코드의 마이그레이션을 웹서비스 배포에서 적용한다. 배치는 `migrate --check`가 실패하면 학습을 시작하지 않는다.
3. 검증한 워크플로를 `main`에 반영한다. 작업 브랜치만으로는 정기 실행이 활성화되지 않는다. 공개 저장소는 60일 동안 저장소 활동이 없으면 예약 워크플로가 비활성화될 수 있으므로 Actions 상태를 확인한다.

기존 웹서비스의 서명 키나 카카오 키는 필요하지 않다. 배치 전용 `config.settings.training`은 명시한 Postgres에만 연결하며 자체 임시 서명 키를 쓴다.

## 실행과 확인

Actions → Daily model training → Run workflow에서 다음 중 하나를 선택한다.

- `verify`(기본): 운영 DB 없이 독립 DB의 학습/계획 이력 테스트만 실행.
- `dry-run`: 운영 데이터로 표본/통계를 계산하지만 저장하지 않음.
- `train`: 전역/개인 아티팩트와 경로 보정을 저장. `main`에서만 허용.

예약 실행은 `train_models --users --verbosity 0`을 사용한다. 공개 로그에는 집계만 남기고 블록 이름/사용자 이메일은 숨긴다. 같은 브랜치의 예약/수동 실행은 겹치지 않으며 진행 중인 학습을 취소하지 않는다.

실행 기록의 `event`가 `schedule`인지, `train` job이 성공했는지, Summary의 표본 수/보정 갱신 수/제외 사유를 확인한다. `verify` 성공이나 `train` 건너뜀을 운영 학습 성공으로 보지 않는다.

## 학습 기준과 반영 범위

- 첫 알람 울림 전 마지막 계획을 사용한다. 알람이 없으면 첫 출발 전 마지막 계획을 사용한다.
- 기준은 `PlanRevision.route_minutes_raw`이며 현재 계획의 보정된 `travel_minutes`를 다시 학습하지 않는다(G1/G2).
- 원값 이력이 없는 과거 이동은 건너뛴다. 과거 원값을 추측해 채우지 않는다.
- 전체 재학습은 자격을 잃은 학습 보정(`sample_count>0`)을 정리한다. 수동 보정(`sample_count=0`)은 보존한다. 일부 사용자만 학습할 때는 다른 사용자의 공유 보정을 정리하지 않는다.
- 준비 관측 통계는 기존처럼 알람 재계산 때 바로 반영된다. 이동 보정 갱신 후 기존 일정은 다음 정상 알람 재계산 때 새 보정을 반영한다. 이 배치가 카카오 경로를 다시 조회하거나 기기 알람을 직접 바꾸지는 않는다.
- 개인/전역 `ModelArtifact`는 통계 스냅샷이며 알람 계산은 여전히 관측 통계와 `RouteCorrection`을 사용한다.

## 검증

```bash
cd backend
python -m pytest apps/prediction apps/planning/tests/test_plan_revision.py -q
```

변경 전 실제 실패한 G1/G2/원값 누락/공개 로그 노출 6건을 수정 후 통과시켰다. 리뷰에서 발견한 오래된 학습 보정 잔존 2건도 실패 재현 후 수정했다. 전체 백엔드 1,237건 / HTTP 8종 실패 0을 확인했다.


설정 근거: [GitHub 예약 실행](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#schedule). 워크플로는 기본 브랜치에서 실행되며 정각 부하로 지연될 수 있어 분 17을 사용한다.
