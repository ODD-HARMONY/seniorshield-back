"""
Unit tests for the SeniorShield extractor service.
Integration tests (marked with @pytest.mark.integration) require network access.
Run only unit tests: pytest -m "not integration"
Run all tests:       pytest
"""
import base64
import textwrap
from pathlib import Path
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from fastapi.testclient import TestClient

from app.main import app
from app.schemas import ExtractRequest
from app.subtitle_parser import _parse_vtt, parse_subtitle

client = TestClient(app)


# ── Schema validation ──────────────────────────────────────────────────────────

def test_extract_request_defaults():
    req = ExtractRequest(url="https://www.youtube.com/watch?v=dQw4w9WgXcQ")
    assert req.extract_subtitle is True
    assert req.extract_frames   is True
    assert req.frame_count      == 3


def test_extract_request_frame_count_bounds():
    with pytest.raises(Exception):
        ExtractRequest(url="https://www.youtube.com/watch?v=dQw4w9WgXcQ", frame_count=0)
    with pytest.raises(Exception):
        ExtractRequest(url="https://www.youtube.com/watch?v=dQw4w9WgXcQ", frame_count=11)
    req = ExtractRequest(url="...", frame_count=10)
    assert req.frame_count == 10


# ── Health endpoint ────────────────────────────────────────────────────────────

def test_health_returns_ok():
    resp = client.get("/health")
    assert resp.status_code == 200
    data = resp.json()
    assert data["status"] == "ok"
    assert "yt_dlp_version" in data
    assert "ffmpeg_version" in data


# ── Subtitle parser (unit, no network) ────────────────────────────────────────

SAMPLE_VTT = textwrap.dedent("""\
    WEBVTT

    00:00:01.000 --> 00:00:03.000
    안녕하세요

    00:00:04.000 --> 00:00:06.000
    <c.colorCCCCCC>오늘은</c> 건강 정보를 알려드립니다

    00:00:07.000 --> 00:00:09.000
    안녕하세요

    00:00:10.000 --> 00:00:12.000
    감사합니다
""")


def test_parse_vtt_deduplicates_and_strips_tags(tmp_path):
    vtt_file = tmp_path / "test.ko.vtt"
    vtt_file.write_text(SAMPLE_VTT, encoding="utf-8")

    text, count = _parse_vtt(vtt_file)
    assert "안녕하세요" in text
    assert "오늘은 건강 정보를 알려드립니다" in text
    assert "감사합니다" in text
    # Duplicated "안녕하세요" should appear only once
    assert text.count("안녕하세요") == 1
    assert "<c." not in text
    assert count == 4  # total cue count (before dedup)


def test_parse_subtitle_no_captions(tmp_path):
    result = parse_subtitle(tmp_path, {}, "dQw4w9WgXcQ")
    assert result.available is False
    assert result.reason == "no_captions"


def test_parse_subtitle_manual_source(tmp_path):
    vtt_file = tmp_path / "dQw4w9WgXcQ.ko.vtt"
    vtt_file.write_text(SAMPLE_VTT, encoding="utf-8")

    info = {
        "subtitles":           {"ko": [{"url": "...", "ext": "vtt"}]},
        "automatic_captions":  {},
    }
    result = parse_subtitle(tmp_path, info, "dQw4w9WgXcQ")
    assert result.available is True
    assert result.source    == "manual"
    assert result.language  == "ko"
    assert result.char_count > 0


def test_parse_subtitle_auto_source(tmp_path):
    vtt_file = tmp_path / "dQw4w9WgXcQ.ko.vtt"
    vtt_file.write_text(SAMPLE_VTT, encoding="utf-8")

    info = {
        "subtitles":           {},
        "automatic_captions":  {"ko": [{"url": "...", "ext": "vtt"}]},
    }
    result = parse_subtitle(tmp_path, info, "dQw4w9WgXcQ")
    assert result.available is True
    assert result.source    == "auto"


# ── Frame extraction helper ────────────────────────────────────────────────────

def test_extract_frames_subprocess_called(tmp_path):
    from app.extractor import _extract_frames

    # Create a dummy 1-byte "video" file so the path exists
    video = tmp_path / "fake.mp4"
    video.write_bytes(b"\x00")

    def fake_run(cmd, **kwargs):
        # Write a tiny valid JPEG so base64 encoding works
        out = Path(cmd[cmd.index("-y") - 1] if "-y" in cmd else cmd[-2])
        # Minimal 1×1 white JPEG
        out.write_bytes(
            bytes.fromhex(
                "ffd8ffe000104a46494600010100000100010000"
                "ffdb004300080606070605080707070909080a0c"
                "140d0c0b0b0c1912130f141d1a1f1e1d1a1c1c20"
                "242e2720222c231c1c2837292c30313434341f27"
                "39403d323c2e333432ffc0000b080001000101011"
                "1000ffc40014000100000000000000000000000000"
                "000008ffc4001401010000000000000000000000000"
                "00000ffd9"
            )
        )
        result = MagicMock()
        result.returncode = 0
        return result

    with patch("app.extractor.subprocess.run", side_effect=fake_run):
        result = _extract_frames(video, duration=10.0, count=2, job_dir=tmp_path)

    assert result.count == 2
    assert len(result.frames_base64) == 2
    assert len(result.timestamps_sec) == 2
    assert result.timestamps_sec == pytest.approx([3.33, 6.67], abs=0.1)


# ── Full pipeline (mocked yt-dlp) ─────────────────────────────────────────────

MOCK_INFO = {
    "id":                  "dQw4w9WgXcQ",
    "title":               "Test Video",
    "duration":            28.0,
    "is_live":             False,
    "subtitles":           {"ko": [{"url": "...", "ext": "vtt"}]},
    "automatic_captions":  {},
}


def test_extract_subtitle_only(tmp_path):
    from app import extractor as ext_module

    def fake_ydl(opts, url):
        # Write a fake .ko.vtt when skip_download is True
        if opts.get("skip_download"):
            out_tpl = opts.get("outtmpl", "")
            job_dir = Path(out_tpl).parent
            vtt = job_dir / "dQw4w9WgXcQ.ko.vtt"
            vtt.write_text(SAMPLE_VTT, encoding="utf-8")
        return MOCK_INFO

    with patch.object(ext_module, "_ydl_extract", side_effect=fake_ydl), \
         patch.object(ext_module, "WORK_DIR", str(tmp_path)):

        import asyncio
        req    = ExtractRequest(url="https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                                extract_frames=False)
        result = asyncio.get_event_loop().run_until_complete(ext_module.extract(req))

    assert result.video_id     == "dQw4w9WgXcQ"
    assert result.duration_sec == 28.0
    assert result.subtitle is not None
    assert result.subtitle.available is True
    assert result.subtitle.source   == "manual"
    assert result.frames is None


# ── Integration tests (require network) ───────────────────────────────────────

@pytest.mark.integration
def test_real_extract_subtitle_only():
    """Sends a real request to the running stack. Requires docker compose up."""
    import requests
    resp = requests.post(
        "http://localhost:8080/api/extract",
        json={"url": "https://www.youtube.com/shorts/dQw4w9WgXcQ",
              "extract_subtitle": True, "extract_frames": False},
        timeout=40,
    )
    assert resp.status_code == 200
    data = resp.json()
    assert "subtitle" in data
    assert "job_id"   in data
