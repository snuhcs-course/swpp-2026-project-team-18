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

## 검증과 학습

`backend` 폴더에서 실행한다. 두 명령 모두 공용 DB와 분리된 테스트 DB를 사용한다.

```bash
python -m pytest
python scripts/run_local_suite.py
```

HTTP 스위트는 서버를 자동 실행한다. 기존 서버가 8000 포트를 사용하면 종료하고 다시 실행한다.
카카오 키가 없으면 관련 항목은 건너뛰므로 실패 수와 건너뜀을 함께 확인한다.
결과는 [MVP 체크리스트](../docs/demo-checklist.md)에 기록한다.

`check_deployed.py`·`check_same_db.py`는 검증 데이터를 만들고,
`train_models`는 DB에 이동 보정값을 저장한다. 공용 DB에서 실행할 때 팀과 시점을 맞춘다.
준비 관측은 다음 계산에 직접 반영되며, 이동 보정 학습은 수동 실행 후 일정 재계산이 필요하다.
변동성 근거가 부족하면 도착 확률은 null이고 앱은 “학습 중”으로 표시한다.
