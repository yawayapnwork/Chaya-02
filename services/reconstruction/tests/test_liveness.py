"""Worker container health: healthy only while control-plane calls succeed, not merely because the process runs."""

from __future__ import annotations

from chaya_worker.liveness import check, mark_alive, max_age_seconds
from chaya_worker.settings import Settings


def test_missing_file_is_unhealthy(tmp_path):
    ok, reason = check(tmp_path / ".alive", 60)
    assert not ok and "not completed a control-plane call" in reason


def test_fresh_file_is_healthy_and_stale_file_is_not(tmp_path):
    f = tmp_path / ".alive"
    mark_alive(f)
    now = float(f.read_text())
    assert check(f, 60, now=now + 10)[0] is True
    ok, reason = check(f, 60, now=now + 120)
    assert not ok and "limit 60" in reason


def test_garbage_is_unhealthy(tmp_path):
    f = tmp_path / ".alive"
    f.write_text("not-a-time")
    assert check(f, 60)[0] is False


def test_max_age_covers_the_longer_of_poll_and_heartbeat():
    assert max_age_seconds(Settings(poll_interval=5, heartbeat_interval=60)) == 3 * 60 + 30
