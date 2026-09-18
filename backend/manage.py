#!/usr/bin/env python
"""Django 관리 명령 진입점."""

import os
import sys


def main():
    # 환경변수로 덮을 수 있게 두되, 기본은 개발 설정이다.
    os.environ.setdefault("DJANGO_SETTINGS_MODULE", "config.settings.dev")
    try:
        from django.core.management import execute_from_command_line
    except ImportError as exc:
        raise ImportError(
            "Django 를 import 할 수 없다. venv 를 활성화했는지 확인한다:\n"
            "  .venv\\Scripts\\Activate.ps1"
        ) from exc
    execute_from_command_line(sys.argv)


if __name__ == "__main__":
    main()
