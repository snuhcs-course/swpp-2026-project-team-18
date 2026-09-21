# 팀 공용 서버 이미지.
#
# 특정 PaaS 에 묶이지 않게 도커로 싼다. Render·Railway·Fly 는 물론 연구실
# 리눅스 서버에서도 같은 명령으로 뜬다 - 배포처를 바꿀 때 설정을 다시 쓰지
# 않으려는 것이다.
#
# 로컬에서 확인:
#   docker build -t justintime-api ./backend
#   docker run --rm -p 8000:8000 \
#     -e DJANGO_SECRET_KEY=... \
#     -e DATABASE_URL=postgres://... \
#     -e DJANGO_ALLOWED_HOSTS=localhost \
#     -e DJANGO_SECURE_SSL_REDIRECT=0 \
#     justintime-api

FROM python:3.12-slim

# 파이썬이 .pyc 를 쓰지 않게 하고 출력 버퍼링을 끈다. 버퍼링을 켜 두면
# 컨테이너가 죽을 때 마지막 로그가 사라져 원인을 못 찾는다.
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1

WORKDIR /app

# 의존성을 먼저 복사한다. 소스만 바뀔 때 이 레이어를 재사용해 빌드가 빨라진다.
COPY requirements/ requirements/
RUN pip install --upgrade pip && pip install -r requirements/prod.txt

COPY . .

# admin 정적 파일을 모은다. whitenoise 가 이 디렉터리에서 서빙한다.
#
# collectstatic 은 설정을 로드하므로 필수 환경변수가 필요하다. 빌드 시점에는
# 실제 값이 없으므로 더미를 넣는다 - 이 값들은 이미지에 남지 않고 런타임
# 환경변수가 덮어쓴다. DATABASE_URL 은 연결을 열지 않고 파싱만 된다.
RUN DJANGO_SECRET_KEY=build-only \
    DATABASE_URL=postgres://u:p@localhost:5432/build \
    DJANGO_ALLOWED_HOSTS=localhost \
    DJANGO_SETTINGS_MODULE=config.settings.prod \
    python manage.py collectstatic --noinput

ENV DJANGO_SETTINGS_MODULE=config.settings.prod

# 플랫폼이 주는 PORT 를 쓴다. 없으면 8000.
ENV PORT=8000
EXPOSE 8000

# 마이그레이션을 컨테이너 시작 시점에 돌린다. 팀원이 스키마 변경을 올렸을 때
# 따로 명령을 치지 않아도 반영되게 하려는 것이다.
#
# 워커 2개. 무료 티어의 메모리(512MB)에 맞춘 값이다. 늘리려면 WEB_CONCURRENCY.
CMD ["sh", "-c", "python manage.py migrate --noinput && exec gunicorn config.wsgi:application --bind 0.0.0.0:${PORT} --workers ${WEB_CONCURRENCY:-2} --timeout 60 --access-logfile - --error-logfile -"]
