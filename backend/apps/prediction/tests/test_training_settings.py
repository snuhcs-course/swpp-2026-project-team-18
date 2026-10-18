"""예약 배치가 로컬 .env나 SQLite로 조용히 폴백하지 않게 한다."""

import runpy

import pytest
from django.core.exceptions import ImproperlyConfigured


@pytest.mark.parametrize("url", [None, "", "sqlite:///:memory:"])
def test_batch_settings_require_an_explicit_postgres_url(monkeypatch, url):
    monkeypatch.delenv("DATABASE_URL", raising=False)
    if url is not None:
        monkeypatch.setenv("DATABASE_URL", url)
    with pytest.raises(ImproperlyConfigured, match="Postgres DATABASE_URL"):
        runpy.run_module("config.settings.training")


def test_batch_settings_use_an_independent_signing_key(monkeypatch):
    monkeypatch.setenv("DATABASE_URL", "postgresql://unused:unused@localhost/unused")
    monkeypatch.setenv("DJANGO_SECRET_KEY", "web-service-key")

    config = runpy.run_module("config.settings.training")

    assert config["DEBUG"] is False
    assert len(config["SECRET_KEY"]) >= 48
    assert config["SECRET_KEY"] != "web-service-key"
