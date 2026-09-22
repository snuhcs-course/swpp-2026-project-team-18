"""검증 스크립트가 "키가 없어서 못 함" 과 "깨져서 실패" 를 구분하게 한다.

## 왜 이 구분이 중요한가

`check_*.py` 중 일부는 카카오 경로·장소 검색에 의존한다. 그 항목을 어떻게
처리할지에 따라 두 방향으로 거짓말을 할 수 있다.

- **키가 없을 때 실패로 적으면** CI 가 항상 빨갛다. 빨간불이 상수가 되면 팀이
  그것을 무시하고, 그때부터 CI 는 없는 것과 같다.
- **경로 실패를 전부 건너뜀으로 적으면** 진짜 경로 회귀가 조용히 숨는다. 코드가
  카카오 응답 파싱을 깨뜨려도 "건너뜀" 으로 보인다.

그래서 판정 기준을 **결과가 아니라 키 유무**로 둔다.

    키가 없다              → 건너뜀 (검사할 수 없었다)
    키가 있는데 경로 실패   → 실패   (회귀다)

## 왜 Django 를 안 쓰고 직접 읽는가

HTTP 전용 스크립트는 `django.setup()` 을 부르지 않는다. 그런데 판정은 모든
스크립트에서 같아야 하므로 `_local_guard` 와 같은 방식으로 직접 읽는다 —
환경변수가 먼저, 없으면 `backend/.env`.

## 한 가지 전제

이 판정은 검증 스크립트와 서버가 **같은 환경**에서 돈다고 가정한다. 로컬 개발과
CI 는 그렇다. 배포 서버를 상대로 도는 `check_deployed.py` 는 서버 쪽 키를 알 수
없으므로 이것을 쓰지 않는다.
"""

from __future__ import annotations

import os
from pathlib import Path

BACKEND_DIR = Path(__file__).resolve().parent.parent

# 이 이름이 비어 있으면 카카오를 부르는 검사를 건너뛴다.
KAKAO_KEYS = ("KAKAO_REST_API_KEY",)


def _from_dotenv(name: str) -> str:
    """`backend/.env` 에서 한 값을 읽는다. dotenv 패키지를 쓰지 않는다."""
    env_path = BACKEND_DIR / ".env"
    if not env_path.exists():
        return ""
    for raw in env_path.read_text(encoding="utf-8", errors="replace").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        if key.strip() == name:
            return value.strip().strip('"').strip("'")
    return ""


def env_value(name: str) -> str:
    """환경변수 우선, 없으면 `.env`. `base.py` 와 같은 순서다."""
    from_env = os.environ.get(name)
    if from_env is not None:
        # **빈 문자열로 존재하는 것도 "없음" 이다.** dotenv 는 이미 있는 이름을
        # 덮지 않으므로, 빈 값으로 export 하면 .env 값이 쓰이지 않는다.
        return from_env.strip()
    return _from_dotenv(name).strip()


def kakao_configured() -> bool:
    """카카오 REST 키가 설정돼 있는가."""
    return all(env_value(name) for name in KAKAO_KEYS)


def kakao_skip_reason() -> str:
    """건너뛰는 이유로 쓸 문구. 사람이 읽고 바로 행동할 수 있게 적는다."""
    return (
        "KAKAO_REST_API_KEY 가 없다 — 경로·장소 검색을 부를 수 없다. "
        "backend/.env 에 키를 넣으면 이 항목도 검사한다"
    )


def banner() -> str:
    """스크립트 시작 줄에 찍을 한 줄 요약."""
    return (
        "카카오 키 있음 — 경로 의존 항목까지 검사한다"
        if kakao_configured()
        else "카카오 키 없음 — 경로 의존 항목은 건너뛴다(실패가 아니다)"
    )
