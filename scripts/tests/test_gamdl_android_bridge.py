"""Mocked contract tests for LyricWave's pinned Gamdl Python bridge."""
import importlib.util
import pathlib
import sys
import tempfile
import types
import unittest
from unittest.mock import patch

ROOT = pathlib.Path(__file__).resolve().parents[2]
PATH = ROOT / "app/src/main/python/lyricwave_gamdl.py"
spec = importlib.util.spec_from_file_location("lyricwave_gamdl", PATH)
bridge = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bridge)


class EventSink:
    def __init__(self):
        self.calls = []

    def onEvent(self, stage, state, bytes_received, total):
        self.calls.append((stage, state, bytes_received, total))


class FakeApi:
    @classmethod
    async def create(cls, *, media_user_token):
        if media_user_token != "token-for-test":
            raise AssertionError("token not forwarded correctly")
        return cls()

    async def __aenter__(self):
        return self

    async def __aexit__(self, *exc):
        return False


class FakeResolver:
    def __init__(self, api):
        pass

    async def from_song(self, media_id):
        assert media_id == "1234"
        yield types.SimpleNamespace()

    async def from_music_video(self, media_id):
        assert media_id == "2345"
        yield types.SimpleNamespace()


class FakeDrm:
    @classmethod
    def from_widevine(cls, api):
        return object()


class FakeDownloader:
    last_kwargs = None

    def __init__(self, **kwargs):
        assert "drm" in kwargs
        assert kwargs["concurrency"] == 8

    async def download(self, media, *, output, **kwargs):
        self.__class__.last_kwargs = kwargs
        target = pathlib.Path(output)
        target.write_bytes(b"test-mp4-payload")
        callback = kwargs["event_callback"]
        event = types.SimpleNamespace(
            stage=types.SimpleNamespace(value="downloading"),
            state=types.SimpleNamespace(value="progress"),
            progress=types.SimpleNamespace(emitted_bytes=16, total_bytes=32),
        )
        callback(event)
        return types.SimpleNamespace(path=target)


def fake_modules():
    package = types.ModuleType("gamdl")
    api = types.ModuleType("gamdl.api")
    api.AppleMusicApi = FakeApi
    media = types.ModuleType("gamdl.media")
    media.MediaResolver = FakeResolver
    media.SongCodec = str
    media.MusicVideoCodec = str
    drm = types.ModuleType("gamdl.drm")
    drm.Drm = FakeDrm
    download = types.ModuleType("gamdl.download")
    download.Downloader = FakeDownloader
    return {
        "gamdl": package, "gamdl.api": api,
        "gamdl.media": media, "gamdl.drm": drm, "gamdl.download": download,
    }


class AndroidGamdlBridgeTest(unittest.TestCase):
    def test_song_quality_and_output(self):
        with tempfile.TemporaryDirectory() as temp, patch.dict(sys.modules, fake_modules()):
            output = str(pathlib.Path(temp) / "track.m4a")
            sink = EventSink()
            path = bridge.download_song(1234, "alac-hires", "token-for-test", output, sink)
            self.assertEqual(path, output)
            self.assertEqual(FakeDownloader.last_kwargs["codec"], "alac-hires")
            self.assertEqual(len(sink.calls), 1)
            self.assertEqual(sink.calls[0][2:], (16, 32))

    def test_video_mp4_quality_and_height(self):
        with tempfile.TemporaryDirectory() as temp, patch.dict(sys.modules, fake_modules()):
            output = str(pathlib.Path(temp) / "video.mp4")
            sink = EventSink()
            path = bridge.download_video(2345, "h265", 1080, "token-for-test", output, sink)
            self.assertEqual(path, output)
            self.assertEqual(FakeDownloader.last_kwargs["video_extension"], ".mp4")
            self.assertEqual(FakeDownloader.last_kwargs["max_height"], 1080)
            self.assertEqual(FakeDownloader.last_kwargs["video_codec"], "h265")

    def test_unsafe_or_invalid_arguments(self):
        with tempfile.TemporaryDirectory() as temp:
            audio = str(pathlib.Path(temp) / "track.m4a")
            with self.assertRaises(ValueError):
                bridge.download_song(0, "aac", "token-for-test", audio, EventSink())
            with self.assertRaises(ValueError):
                bridge.download_song(1234, "mp3", "token-for-test", audio, EventSink())
            with self.assertRaises(ValueError):
                bridge.download_song(1234, "aac", "", audio, EventSink())
            with self.assertRaises(ValueError):
                bridge.download_video(2345, "h265", 9999, "token-for-test",
                                      str(pathlib.Path(temp) / "video.mp4"), EventSink())


if __name__ == "__main__":
    unittest.main()
