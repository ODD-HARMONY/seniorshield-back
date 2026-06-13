import os
import pytest

# Use a temp directory as WORK_DIR during tests
os.environ.setdefault("WORK_DIR", "/tmp/ss_test_work")
os.environ.setdefault("MAX_DURATION_SEC", "120")
os.environ.setdefault("YT_DLP_TIMEOUT_SEC", "20")
os.environ.setdefault("FFMPEG_TIMEOUT_SEC", "15")
