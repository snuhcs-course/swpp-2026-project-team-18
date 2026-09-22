"""개발 설정. 에뮬레이터와 같은 LAN 의 실기기 접근을 전제한다."""

import os
import socket

from django.core.exceptions import ImproperlyConfigured

from .base import *  # noqa: F401,F403

DEBUG = True

# ---------------------------------------------------------------------------
# DB 를 명시하지 않으면 시작하지 않는다
# ---------------------------------------------------------------------------
#
# **팀은 DB 를 Neon 하나로 쓴다.** 예전에는 `DATABASE_URL` 이 비면 조용히 로컬
# SQLite 로 떨어졌는데, 그 조용함이 문제였다 — `.env` 를 아직 안 받은 사람이
# 서버를 띄우면 빈 SQLite 에 붙고, 화면에 아무것도 없는 이유를 "앱 버그" 로
# 오해한다. 반대로 로컬 파일에 데이터를 쌓아 두면 그것이 팀 데이터와 갈라진다.
#
# 그래서 **어느 DB 에 붙는지 말하게 한다.** 둘 중 하나를 골라야 한다.
#
#   1. 팀 Neon 주소를 `backend/.env` 의 DATABASE_URL 에 넣는다 (기본).
#   2. 일회용 SQLite 를 명시한다. 검증 스크립트가 쓰는 방식이다.
#        $env:DATABASE_URL="sqlite:///$env:TEMP/jit_verify.sqlite3"
#
# `config.settings.test` 는 이 검사를 받지 않는다. 그쪽은 base 만 import 하고
# DATABASES 를 메모리 SQLite 로 무조건 덮어쓴다 — 테스트가 팀 DB 에 붙어 테스트
# 데이터베이스를 만들고 지우는 것을 막기 위한 설계다.
if not os.getenv("DATABASE_URL", "").strip():
    raise ImproperlyConfigured(
        "DATABASE_URL 이 비어 있다. 어느 DB 에 붙을지 명시할 것.\n"
        "\n"
        "  팀 Neon 을 쓸 때 (기본):\n"
        "    backend/.env 의 DATABASE_URL 에 Neon direct 주소를 넣는다.\n"
        "    주소는 관리자에게 받는다 (docs/team-setup.md 1절).\n"
        "\n"
        "  일회용 로컬 DB 로 잠깐 돌릴 때:\n"
        '    $env:DATABASE_URL="sqlite:///$env:TEMP/jit_scratch.sqlite3"\n'
        "\n"
        "  검증 스크립트를 돌릴 때는 아무것도 하지 않아도 된다.\n"
        "    python scripts/run_local_suite.py   ← 일회용 DB 를 알아서 쓴다\n"
        "\n"
        '  주의: PowerShell 에서 $env:DATABASE_URL="" 는 값을 비우는 것이 아니라\n'
        "  변수를 **삭제**한다. 그러면 .env 값이 다시 읽힌다."
    )


def _local_ipv4_addresses() -> list[str]:
    """이 PC 의 LAN IPv4 주소 목록.

    실기기로 테스트할 때 앱은 `http://<개발 PC IP>:8000` 을 부른다. 그 IP 를
    `ALLOWED_HOSTS` 에 넣지 않으면 Django 가 400 DisallowedHost 를 돌려준다.

    IP 를 손으로 적게 하면 팀원마다 다르고, Wi-Fi 를 옮기거나 테더링으로
    바꾸면 같은 사람도 달라진다. 그래서 실행할 때마다 자동으로 알아낸다.
    """
    addresses: set[str] = set()

    # 호스트명으로 붙은 주소들. 보통 Wi-Fi·이더넷 주소가 여기 나온다.
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            addresses.add(info[4][0])
    except OSError:
        pass

    # 위 방법이 비거나 일부만 잡는 경우가 있다. 외부로 UDP 소켓을 열어
    # (실제로 보내지는 않는다) 기본 경로에 쓰이는 주소를 확인한다.
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
            sock.connect(("8.8.8.8", 80))
            addresses.add(sock.getsockname()[0])
    except OSError:
        pass

    return sorted(a for a in addresses if a and not a.startswith("127."))


# 에뮬레이터는 호스트 PC 의 127.0.0.1 을 10.0.2.2 로 본다.
#
# 주의: ALLOWED_HOSTS 가 비어 있을 때만 Django 가 DEBUG 에서 localhost 를 자동
# 허용한다. 10.0.2.2 를 넣는 순간 그 자동 허용이 사라지므로 localhost 와
# 127.0.0.1 을 반드시 함께 명시해야 한다. 이걸 빼면 호스트에서 curl 이 400 을 받는다.
ALLOWED_HOSTS = ["10.0.2.2", "localhost", "127.0.0.1"]

# 실기기 접근용. 자동 감지한 LAN IP 를 더한다.
ALLOWED_HOSTS += _local_ipv4_addresses()

# 자동 감지가 놓치는 경우(VPN, 도커 브리지, 사내망 등)를 위한 탈출구.
#   DEV_EXTRA_HOSTS=192.168.0.12,jinho-pc.local
ALLOWED_HOSTS += [
    host.strip()
    for host in os.environ.get("DEV_EXTRA_HOSTS", "").split(",")
    if host.strip()
]

# 중복 제거. 순서는 상관없다.
ALLOWED_HOSTS = sorted(set(ALLOWED_HOSTS))

# Android 클라이언트는 CORS 영향을 받지 않는다. 브라우저 전용 메커니즘이기 때문이다.
# admin 과 향후 웹 대시보드용으로만 좁게 허용하고 전체 허용은 두지 않는다.
CORS_ALLOWED_ORIGINS = [
    "http://localhost:8000",
    "http://127.0.0.1:8000",
]
