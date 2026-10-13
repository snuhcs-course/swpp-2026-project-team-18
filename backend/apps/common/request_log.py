# Partly AI-generated (Claude)
"""API 요청 기록의 규칙 — 무엇을 남기고 무엇을 지우는가(db-schema.md A4).

미들웨어(`apps/common/middleware.py`)는 이 규칙을 그대로 따르기만 한다. 규칙을 한곳에
두는 이유는 테스트가 미들웨어 없이 정리 결과를 볼 수 있어야 하기 때문이다.

## 본문은 허용 목록에 있는 경로만

요청 본문은 [REQUEST_BODY_ROUTES] 의 쓰기 요청만, 응답 본문은 [RESPONSE_BODY_ROUTES]
(그때 보여 준 경로 후보와 일정 생성·수정·재계산이 만든 계획)만 남긴다. **목록에 없는 새
경로는 본문을 남기지 않는다** — 경로를 더할 때 무엇이 오가는지 보고 넣는다. 가입·로그인·
토큰 갱신·비밀번호 검사는 본문 없이 메타만 남긴다([META_ONLY_ROUTES]).

## 남긴 본문에서도 지운다

- 비밀: `password*`·`token`·`access`·`refresh`·`authorization`·`secret`·`api_key` 같은
  키는 값을 [REDACTED] 로 바꾼다. 설계의 `*_key` 는 `api_key`·`*_api_key` 로 좁혔다 —
  `route_key`·`tag_key` 는 사용자가 고른 경로·일정 종류라 비밀이 아니고, 남기려는 값이다.
- 좌표: `lat`·`lng`·`*_lat`·`*_lng`·`x`·`y`·`*_x`·`*_y`·`path`·`*_path`·`points`·`rect`·
  `markers` 는 값을 지운다(N19). 이동 중 현재 위치(`routes/live` 요청)가 쌓여 궤적이 되는
  것을 막는다. 경로 갱신(`routes/live`) 응답은 아예 남기지 않는다. 목록은 점 개수만 남긴다(`[coords:12]`). 거리·정확도·체류 같은
  파생값은 키가 달라 그대로 남는다.
- 크기: 정리한 JSON 이 요청 8 KB·응답 16 KB 를 넘으면 앞부분만 남기고 `_truncated: true`.
"""

from __future__ import annotations

import json
import math

# ---------------------------------------------------------------------------
# 어느 요청을 남기나
# ---------------------------------------------------------------------------

# 남기지 않는 경로. 헬스 체크는 Render 가 수시로 부르고, 정적 지도는 이미지다.
SKIPPED_PATHS = ("/api/health", "/api/places/staticmap")
SKIPPED_ROUTES = frozenset({"api/health", "api/places/staticmap"})

# 본문을 읽지도 남기지도 않는 경로. 비밀번호·토큰이 오간다.
META_ONLY_PREFIX = "/api/auth/"
META_ONLY_ROUTES = frozenset(
    {
        "api/auth/register",
        "api/auth/password/check",
        "api/auth/token",
        "api/auth/token/refresh",
    }
)

WRITE_METHODS = frozenset({"POST", "PUT", "PATCH", "DELETE"})

# 요청 본문을 남기는 경로(쓰기만). 사용자가 넣은 값과 앱이 올린 기록이다.
REQUEST_BODY_ROUTES = frozenset(
    {
        "api/profile",
        "api/events",
        "api/events/<int:pk>",
        "api/events/import",
        "api/events/<int:event_id>/blocks",
        "api/routes/live",
        "api/observations/batch",
        "api/alarm-logs/batch",
        "api/routines/blocks",
        "api/routines/blocks/<int:pk>",
        "api/routines/observations/batch",
    }
)

# 응답 본문을 남기는 (메서드, 경로). 2xx 일 때만 남긴다.
RESPONSE_BODY_ROUTES = frozenset(
    {
        # 그때 보여 준 경로 후보.
        ("GET", "api/routes/candidates"),
        # 일정 생성·수정·재계산 — 그 결과 계획.
        ("POST", "api/events"),
        ("PATCH", "api/events/<int:pk>"),
        ("PUT", "api/events/<int:pk>"),
        ("POST", "api/events/<int:pk>/recompute"),
        # `routes/live`(이동 중 1분마다 다시 푼 경로)의 응답은 남기지 않는다. 좌표를 지워도
        # 1분 간격의 남은 거리·분이 쌓이면 이동 경과가 된다(N19). 요청은 좌표를 지우고 남는다.
    }
)

REQUEST_LIMIT_BYTES = 8 * 1024
RESPONSE_LIMIT_BYTES = 16 * 1024
# 이보다 큰 요청 본문은 미리 읽지 않는다. 뷰(DRF)가 스트림에서 직접 읽게 둔다.
MAX_READ_BYTES = 1024 * 1024
# 중첩이 이보다 깊으면 더 들어가지 않는다. 중첩 폭탄 요청에 재귀가 터지지 않게 한다.
MAX_DEPTH = 32

# ---------------------------------------------------------------------------
# 지우는 값
# ---------------------------------------------------------------------------

REDACTED = "[redacted]"
COORDS = "[coords]"
TOO_DEEP = "[depth]"

_SECRET_KEYS = frozenset(
    {
        "token",
        "access",
        "refresh",
        "authorization",
        "secret",
        "api_key",
        "apikey",
        "service_key",
        "servicekey",
        "cookie",
        "sessionid",
        "csrftoken",
    }
)
_SECRET_PARTS = ("password", "passwd", "secret", "token")
_COORD_KEYS = frozenset(
    {
        "lat",
        "lng",
        "lon",
        "latitude",
        "longitude",
        "x",
        "y",
        "path",
        "points",
        "polyline",
        "rect",
        "markers",
        "coords",
        "coordinates",
        "geometry",
    }
)
# `_x`·`_y` 는 카카오식 좌표 이름(`start_x`·`end_y`)이다. 지금 응답에는 없지만 외부 응답을
# 그대로 넘기는 경로가 생겨도 새지 않게 막아 둔다.
_COORD_SUFFIXES = ("_lat", "_lng", "_lon", "_path", "_points", "_x", "_y")


def is_secret_key(key: str) -> bool:
    name = key.lower()
    return (
        name in _SECRET_KEYS
        or name.endswith("_api_key")
        or any(part in name for part in _SECRET_PARTS)
    )


def is_coordinate_key(key: str) -> bool:
    name = key.lower()
    return name in _COORD_KEYS or name.endswith(_COORD_SUFFIXES)


def sanitize(value, depth: int = 0):
    """비밀과 좌표를 지운 사본. 원본은 건드리지 않는다. 결과는 JSON 으로 쓸 수 있다."""
    if depth > MAX_DEPTH:
        return TOO_DEEP
    if isinstance(value, dict):
        out = {}
        for raw_key, item in value.items():
            key = str(raw_key)
            if is_secret_key(key):
                out[key] = REDACTED
            elif is_coordinate_key(key):
                out[key] = _coordinate_marker(item)
            else:
                out[key] = sanitize(item, depth + 1)
        return out
    if isinstance(value, (list, tuple)):
        return [sanitize(item, depth + 1) for item in value]
    if isinstance(value, float):
        # Postgres jsonb 는 NaN·Infinity 를 받지 않는다. 그 행 하나 때문에 기록이 실패하지 않게 한다.
        return value if math.isfinite(value) else str(value)
    if value is None or isinstance(value, (bool, int, str)):
        return value
    return str(value)


def _coordinate_marker(value):
    """좌표 대신 남기는 표시. null 은 그대로 둔다 — "출발지 없음(집)" 도 뜻이 있다."""
    if value is None:
        return None
    if isinstance(value, (list, tuple)):
        return f"[coords:{len(value)}]"
    return COORDS


def bounded(value, limit: int):
    """정리한 값이 `limit` 바이트를 넘으면 앞부분 문자열과 `_truncated` 로 바꾼다."""
    text = json.dumps(value, ensure_ascii=False)
    encoded = text.encode("utf-8")
    if len(encoded) <= limit:
        return value
    return {
        "_truncated": True,
        "_bytes": len(encoded),
        "preview": encoded[:limit].decode("utf-8", "ignore"),
    }


# ---------------------------------------------------------------------------
# 요청에서 읽기
# ---------------------------------------------------------------------------


def should_log(path: str) -> bool:
    """`/api/` 아래이고 제외 경로가 아니면 남긴다."""
    if not path.startswith("/api/"):
        return False
    return not any(path == p or path.startswith(p + "/") for p in SKIPPED_PATHS)


def read_json_body(request):
    """쓰기 요청의 JSON 본문. **뷰보다 먼저** 부른다.

    DRF 는 본문을 요청 스트림에서 직접 읽어서, 뷰가 끝난 뒤에는 `request.body` 를 읽을 수
    없다. 먼저 읽어 두면 DRF 는 읽어 둔 사본을 쓴다(동작은 같다). 인증 경로, JSON 이 아닌
    본문, [MAX_READ_BYTES] 보다 큰 본문은 읽지 않는다.
    """
    if request.path.startswith(META_ONLY_PREFIX):
        return None
    try:
        length = int(request.META.get("CONTENT_LENGTH") or 0)
    except (TypeError, ValueError):
        length = 0
    if length <= 0:
        return {}
    if length > MAX_READ_BYTES:
        return {"_skipped": "too_large", "_bytes": length}
    content_type = (request.META.get("CONTENT_TYPE") or "").split(";")[0].strip().lower()
    if content_type != "application/json":
        return {"_skipped": "not_json", "_bytes": length}
    try:
        raw = request.body
    except Exception:  # noqa: BLE001 - 읽지 못하면 뷰도 같은 오류로 답한다
        return {"_skipped": "unreadable"}
    try:
        return json.loads(raw)
    except (ValueError, RecursionError):
        return {"_unparsed": True, "_bytes": len(raw)}


def query_dict(request) -> dict:
    """쿼리 문자열. 값이 하나면 문자열, 여럿이면 목록이다."""
    out = {}
    for key, values in request.GET.lists():
        out[key] = values[0] if len(values) == 1 else list(values)
    return out


def app_version(request) -> str:
    """`X-App-Version` 헤더. 버전 문자열에 쓰는 글자만 남기고 32자로 자른다."""
    raw = request.headers.get("X-App-Version") or ""
    kept = "".join(ch for ch in raw if ch.isascii() and (ch.isalnum() or ch in ".+-_ ()"))
    return kept.strip()[:32]


def error_code(response) -> str:
    """공통 오류 형식의 code. 없으면 빈 값."""
    data = getattr(response, "data", None)
    if isinstance(data, dict):
        error = data.get("error")
        if isinstance(error, dict):
            code = error.get("code")
            if isinstance(code, str):
                return code[:64]
    return ""
