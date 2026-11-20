"""LyricWave's Android bridge to the Gamdl 4.1.1 Python API.

Runs only in the LyricWave application's process. No secrets are logged,
transmitted to the host process, or returned to Kotlin.
"""
import asyncio
from pathlib import Path

SUPPORTED = frozenset({
    "aac", "aac-he", "aac-web", "alac", "alac-lossless",
    "alac-hires", "atmos", "ac3",
})


def download_song(media_id: int, codec: str, token: str, destination: str, callback):
    if not isinstance(media_id, int) or media_id <= 0:
        raise ValueError("invalid_media_id")
    if codec not in SUPPORTED:
        raise ValueError("unsupported_codec")
    if not token or not token.strip():
        raise ValueError("authentication_required")

    target = Path(destination).resolve()
    if target.suffix.lower() != ".m4a":
        raise ValueError("invalid_output_format")
    if not target.parent.is_dir():
        raise ValueError("missing_private_staging_directory")

    async def execute():
        # Import lazily: a missing Android Gamdl wheel becomes an actionable
        # setup error instead of crashing Android Application startup.
        from gamdl.api import AppleMusicApi
        from gamdl.download import Downloader
        from gamdl.drm import Drm
        from gamdl.media import MediaResolver, SongCodec

        api = await AppleMusicApi.create(media_user_token=token.strip())
        async with api:
            resolver = MediaResolver(api)
            media = await anext(resolver.from_song(str(media_id)), None)
            if media is None:
                raise ValueError("catalog_song_not_found")
            drm = Drm.from_widevine(api)
            downloader = Downloader(drm=drm, concurrency=8, temp_path=target.parent)

            def on_event(event):
                progress = event.progress
                callback.onEvent(
                    str(event.stage.value),
                    str(event.state.value),
                    int(progress.emitted_bytes) if progress else 0,
                    int(progress.total_bytes) if progress and progress.total_bytes else -1,
                )
            result = await downloader.download(
                media, output=str(target), codec=SongCodec(codec),
                overwrite=False, event_callback=on_event,
            )
            if not result.path.is_file() or result.path.stat().st_size == 0:
                raise RuntimeError("empty_media_result")
            return str(result.path)

    # A dedicated worker thread invokes this function. A fresh loop keeps
    # Gamdl's async resources scoped to this individual transfer.
    return asyncio.run(execute())


def download_video(media_id: int, codec: str, height: int, token: str, destination: str, callback):
    if not isinstance(media_id, int) or media_id <= 0:
        raise ValueError("invalid_media_id")
    if codec not in ("h264", "h265") or height not in (720, 1080, 2160):
        raise ValueError("unsupported_video_quality")
    if not token or not token.strip():
        raise ValueError("authentication_required")

    target = Path(destination).resolve()
    if target.suffix.lower() != ".mp4":
        raise ValueError("invalid_video_container")
    if not target.parent.is_dir():
        raise ValueError("missing_private_staging_directory")

    async def execute():
        from gamdl.api import AppleMusicApi
        from gamdl.download import Downloader
        from gamdl.drm import Drm
        from gamdl.media import MediaResolver, MusicVideoCodec

        api = await AppleMusicApi.create(media_user_token=token.strip())
        async with api:
            resolver = MediaResolver(api)
            media = await anext(resolver.from_music_video(str(media_id)), None)
            if media is None:
                raise ValueError("music_video_not_found")
            downloader = Downloader(
                drm=Drm.from_widevine(api), concurrency=8, temp_path=target.parent,
            )

            def on_event(event):
                progress = event.progress
                callback.onEvent(
                    str(event.stage.value),
                    str(event.state.value),
                    int(progress.emitted_bytes) if progress else 0,
                    int(progress.total_bytes) if progress and progress.total_bytes else -1,
                )
            result = await downloader.download(
                media, output=str(target), video_extension=".mp4",
                video_codec=MusicVideoCodec(codec), max_height=height,
                overwrite=False, event_callback=on_event,
            )
            if not result.path.is_file() or result.path.stat().st_size == 0:
                raise RuntimeError("empty_video_result")
            return str(result.path)

    return asyncio.run(execute())
