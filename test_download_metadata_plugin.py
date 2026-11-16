"""Download metadata & cover-art postprocessor tests using the pinned yt-dlp engine + host ffmpeg/mutagen."""
import importlib.util
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT / 'app/src/main/res/raw/ytdlp'))
PLUGIN = ROOT / 'app/src/main/assets/download_plugins/yt_dlp_plugins/postprocessor/swararia_metadata.py'

import yt_dlp
from yt_dlp.postprocessor.ffmpeg import FFmpegMetadataPP

try:
    import mutagen
    from mutagen.id3 import APIC
    from mutagen.mp4 import MP4Cover
    from mutagen.oggopus import OggOpus
except ImportError:
    mutagen = None


def load_plugin():
    spec = importlib.util.spec_from_file_location('swararia_metadata_test', PLUGIN)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def ydl_for_dir(work_dir: Path) -> yt_dlp.YoutubeDL:
    return yt_dlp.YoutubeDL({
        'quiet': True,
        'no_warnings': True,
        'outtmpl': {'default': str(work_dir / '%(title).80s [%(id)s].%(ext)s')},
    })


def run_ffmpeg(args):
    subprocess.run(
        ['ffmpeg', '-hide_banner', '-loglevel', 'error', '-y', *args],
        check=True,
    )


class DownloadMetadataPluginTests(unittest.TestCase):
    def setUp(self):
        self.assertTrue(PLUGIN.is_file(), 'Download metadata postprocessor plugin is missing')
        self.mod = load_plugin()

    def test_plugin_loads_via_yt_dlp_plugin_dirs_contract(self):
        with tempfile.TemporaryDirectory() as td:
            base = Path(td)
            target = base / 'swararia/yt_dlp_plugins/postprocessor/swararia_metadata.py'
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(PLUGIN, target)
            # Load in an isolated subprocess matching IngestService CLI flags (--no-plugin-dirs --plugin-dirs --use-postprocessor)
            probe = (
                "import sys; sys.path.insert(0, sys.argv[1]); "
                "import yt_dlp, yt_dlp.globals, yt_dlp.plugins; "
                "_, opts, _, ydl_opts = yt_dlp.parse_options(["
                "'--no-plugin-dirs', '--plugin-dirs', sys.argv[2], "
                "'--use-postprocessor', 'SwarariaEnrich:when=pre_process', "
                "'--use-postprocessor', 'SwarariaEmbedCover:when=after_move'"
                "]); "
                "yt_dlp.globals.plugin_dirs.value = opts.plugin_dirs; "
                "yt_dlp.plugins.load_all_plugins(); "
                "ydl = yt_dlp.YoutubeDL(ydl_opts); "
                "assert [pp.pp_key() for pp in ydl._pps['pre_process']][-1] == 'SwarariaEnrich'; "
                "assert [pp.pp_key() for pp in ydl._pps['after_move']][-1] == 'SwarariaEmbedCover'"
            )
            subprocess.run(
                [sys.executable, '-c', probe, str(ROOT / 'app/src/main/res/raw/ytdlp'), str(base)],
                check=True,
            )

    def test_enrich_prefers_ytm_next_and_album_browse_with_square_cover(self):
        video_id = 'BaW_jenozKc'
        album_id = 'MPREb_1234567890abcd'
        next_payload = {
            'contents': {'singleColumnMusicWatchNextResultsRenderer': {'tabbedRenderer': {'watchNextTabbedResultsRenderer': {'tabs': [{
                'tabRenderer': {'content': {'musicQueueRenderer': {'content': {'playlistPanelRenderer': {'contents': [
                    {'playlistPanelVideoRenderer': {
                        'videoId': video_id,
                        'title': {'runs': [{'text': 'Nectar'}]},
                        'longBylineText': {'runs': [
                            {'text': 'Joji', 'navigationEndpoint': {'browseEndpoint': {'browseId': 'UC12345678901234567890ab', 'browseEndpointContextSupportedConfigs': {'browseEndpointContextMusicConfig': {'pageType': 'MUSIC_PAGE_TYPE_ARTIST'}}}}},
                            {'text': ' • '},
                            {'text': 'Nectar', 'navigationEndpoint': {'browseEndpoint': {'browseId': album_id, 'browseEndpointContextSupportedConfigs': {'browseEndpointContextMusicConfig': {'pageType': 'MUSIC_PAGE_TYPE_ALBUM'}}}}},
                            {'text': ' • '},
                            {'text': '2020'},
                        ]},
                        'thumbnail': {'thumbnails': [
                            {'url': 'https://lh3.googleusercontent.com/small=w60-h60', 'width': 60, 'height': 60},
                            {'url': 'https://yt3.googleusercontent.com/cover=w544-h544', 'width': 544, 'height': 544},
                        ]},
                    }},
                ]}}}}},
            }]}}}},
        }
        album_payload = {
            'contents': {'twoColumnBrowseResultsRenderer': {'tabs': [{
                'tabRenderer': {'content': {'sectionListRenderer': {'contents': [
                    {'musicResponsiveHeaderRenderer': {
                        'title': {'runs': [{'text': 'Nectar'}]},
                        'straplineTextOne': {'runs': [{'text': 'Joji'}]},
                        'subtitle': {'runs': [{'text': 'Album'}, {'text': ' • '}, {'text': '2020'}]},
                        'thumbnail': {'musicThumbnailRenderer': {'thumbnail': {'thumbnails': [
                            {'url': 'https://yt3.googleusercontent.com/album=w1200-h1200', 'width': 1200, 'height': 1200},
                        ]}}},
                    }},
                    {'musicShelfRenderer': {'contents': [
                        {'musicResponsiveListItemRenderer': {'playlistItemData': {'videoId': 'otherTrk001'}}},
                        {'musicResponsiveListItemRenderer': {'playlistItemData': {'videoId': video_id}}},
                        {'musicResponsiveListItemRenderer': {'playlistItemData': {'videoId': 'otherTrk003'}}},
                    ]}},
                ]}}},
            }]}},
        }

        def fake_post(url, payload, origin, timeout=4.5):
            if url.startswith('https://music.youtube.com/youtubei/v1/next'):
                return next_payload
            if url.startswith('https://music.youtube.com/youtubei/v1/browse'):
                return album_payload
            raise AssertionError(f'Unexpected fallback URL when YT Music already resolved metadata: {url}')

        with tempfile.TemporaryDirectory() as td, mock.patch.object(self.mod, '_post_innertube', side_effect=fake_post):
            work_dir = Path(td)
            ydl = ydl_for_dir(work_dir)
            pp = self.mod.SwarariaEnrichPP(ydl)
            info = {
                '_type': 'video',
                'id': video_id,
                'title': 'Joji - Nectar',
                'ext': 'opus',
                'duration': 210.4,
                'categories': ['Music'],
                'thumbnails': [{'url': 'https://i.ytimg.com/vi/BaW_jenozKc/maxresdefault.jpg'}],
            }
            _, updated = pp.run(info)
            self.assertEqual('Nectar', updated.get('track'))
            self.assertEqual(['Joji'], updated.get('artists'))
            self.assertEqual('Joji', updated.get('artist'))
            self.assertEqual('Nectar', updated.get('album'))
            self.assertEqual('Joji', updated.get('album_artist'))
            self.assertEqual('2/3', updated.get('track_number'))
            self.assertEqual('2020', updated.get('meta_date'))
            self.assertEqual('https://yt3.googleusercontent.com/album=w1200-h1200', updated.get('thumbnail'))
            self.assertEqual(100, updated['thumbnails'][-1].get('preference'))

            meta_file = work_dir / 'swararia-meta.json'
            self.assertTrue(meta_file.is_file())
            written = json.loads(meta_file.read_text(encoding='utf-8'))
            self.assertEqual('Nectar', written['track'])
            self.assertEqual('Joji', written['artist'])
            self.assertEqual('Nectar', written['album'])
            self.assertEqual('2/3', written['trackNumber'])
            self.assertEqual(210400, written['durationMs'])
            self.assertEqual('https://yt3.googleusercontent.com/album=w1200-h1200', written['thumbnailUrl'])

    def test_enrich_falls_back_to_web_music_card_for_official_mv(self):
        video_id = 'dQw4w9WgXcQ'
        web_payload = {
            'engagementPanels': [{'engagementPanelSectionListRenderer': {'content': {'structuredDescriptionContentRenderer': {'items': [
                {'horizontalCardListRenderer': {
                    'header': {'richListHeaderRenderer': {'title': {'runs': [{'text': 'Games'}]}}},
                    'cards': [{'videoAttributeViewModel': {'title': 'Wrong Game Card', 'subtitle': 'Wrong Artist'}}],
                }},
                {'horizontalCardListRenderer': {
                    'header': {'richListHeaderRenderer': {'title': {'runs': [{'text': 'Music'}]}}},
                    'footerButton': {'buttonViewModel': {'onTap': {'innertubeCommand': {'browseEndpoint': {'browseId': 'UC-9-kyTW8ZkZNDHQJ6FgpwQ'}}}}},
                    'cards': [{'videoAttributeViewModel': {
                        'title': 'Song From Card',
                        'subtitle': 'Band Name',
                        'secondarySubtitle': {'content': 'Album From Card'},
                        'imageStyle': 'VIDEO_ATTRIBUTE_IMAGE_STYLE_SQUARE',
                        'image': {'sources': [
                            {'url': 'https://evil.example/reject.jpg'},
                            {'url': 'https://lh3.googleusercontent.com/cardcover=w544-h544'},
                        ]},
                        'overflowMenuOnTap': {'innertubeCommand': {'confirmDialogEndpoint': {'content': {'confirmDialogRenderer': {'dialogMessages': [
                            {'runs': [{'text': 'Song: Clean Song\nArtist: Clean Artist\nAlbum: Clean Album\nWriters: Alice, Bob'}]},
                        ]}}}}},
                    }}],
                }},
            ]}}}}]
        }

        def fake_post(url, payload, origin, timeout=4.5):
            if url.startswith('https://music.youtube.com/'):
                return {}
            if url.startswith('https://www.youtube.com/'):
                return web_payload
            return None

        with tempfile.TemporaryDirectory() as td, mock.patch.object(self.mod, '_post_innertube', side_effect=fake_post):
            work_dir = Path(td)
            pp = self.mod.SwarariaEnrichPP(ydl_for_dir(work_dir))
            info = {
                '_type': 'video',
                'id': video_id,
                'title': 'Band Name - Clean Song (Official Music Video)',
                'channel': 'BandVEVO',
                'ext': 'm4a',
                'upload_date': '20210514',
                'thumbnails': [],
            }
            _, updated = pp.run(info)
            self.assertEqual('Clean Song', updated.get('track'))
            self.assertEqual('Clean Artist', updated.get('artist'))
            self.assertEqual('Clean Album', updated.get('album'))
            self.assertEqual('Alice, Bob', updated.get('composer'))
            self.assertEqual('2021-05-14', updated.get('meta_date'))
            self.assertEqual('https://lh3.googleusercontent.com/cardcover=w544-h544', updated.get('thumbnail'))

    def test_enrich_strips_topic_suffix_and_rejects_unsafe_or_disallowed_cover_urls(self):
        video_id = 'aqz-KE-bpKQ'
        bad_next = {
            'contents': {'playlistPanelVideoRenderer': {
                'videoId': video_id,
                'title': {'runs': [{'text': '<unknown>'}]},
                'longBylineText': {'runs': []},
                'thumbnail': {'thumbnails': [
                    {'url': 'https://evil.example/fake=w544-h544', 'width': 544, 'height': 544},
                    {'url': 'http://yt3.googleusercontent.com/http=w544-h544', 'width': 544, 'height': 544},
                ]},
            }}
        }
        with tempfile.TemporaryDirectory() as td, mock.patch.object(self.mod, '_post_innertube', return_value=bad_next):
            work_dir = Path(td)
            pp = self.mod.SwarariaEnrichPP(ydl_for_dir(work_dir))
            info = {
                '_type': 'video',
                'id': video_id,
                'title': 'Real\n\tTitle',
                'channel': 'Some Indie Artist - Topic',
                'ext': 'opus',
                'thumbnails': [{'url': 'https://i.ytimg.com/vi/aqz-KE-bpKQ/hqdefault.jpg?sqp=123'}],
            }
            _, updated = pp.run(info)
            self.assertEqual('Some Indie Artist', updated.get('artist'))
            self.assertIsNone(updated.get('album'))
            # Rejected unsafe cover hosts; durable thumbnail falls back to canonical hqdefault without query string.
            meta = json.loads((work_dir / 'swararia-meta.json').read_text(encoding='utf-8'))
            self.assertEqual('Real Title', meta['track'])
            self.assertEqual(f'https://i.ytimg.com/vi/{video_id}/hqdefault.jpg', meta['thumbnailUrl'])

    @unittest.skipIf(shutil.which('ffmpeg') is None or mutagen is None, 'ffmpeg and mutagen required')
    def test_embed_cover_converts_thumbnails_and_embeds_into_opus_m4a_and_mp3(self):
        cases = [
            ('opus', ['-c:a', 'libopus', '-b:a', '64k'], 'png'),
            ('m4a', ['-c:a', 'aac', '-b:a', '96k'], 'webp_misnamed'),
            ('mp3', ['-c:a', 'libmp3lame', '-b:a', '96k'], 'big_jpeg'),
        ]
        for ext, audio_codec_args, thumb_mode in cases:
            with self.subTest(ext=ext, thumb_mode=thumb_mode), tempfile.TemporaryDirectory() as td:
                work_dir = Path(td)
                audio_path = work_dir / f'Track [{ext[:1].upper()}1234567890].{ext}'
                run_ffmpeg(['-f', 'lavfi', '-i', 'sine=frequency=440:duration=1', *audio_codec_args, str(audio_path)])

                if thumb_mode == 'png':
                    thumb_path = work_dir / 'cover.png'
                    run_ffmpeg(['-f', 'lavfi', '-i', 'color=c=crimson:s=500x500:d=1', '-frames:v', '1', str(thumb_path)])
                elif thumb_mode == 'webp_misnamed':
                    # Produce real WebP bytes if libwebp is available, else PNG misnamed check still tests non-JPEG conversion;
                    # test explicit misnamed .webp if encoder exists.
                    webp_check = subprocess.run(['ffmpeg', '-hide_banner', '-encoders'], capture_output=True, text=True)
                    thumb_path = work_dir / 'cover.jpg'
                    if 'libwebp' in webp_check.stdout:
                        real_webp = work_dir / 'cover.webp'
                        run_ffmpeg(['-f', 'lavfi', '-i', 'testsrc2=s=400x400:d=1', '-frames:v', '1', '-c:v', 'libwebp', str(real_webp)])
                        real_webp.replace(thumb_path)
                    else:
                        run_ffmpeg(['-f', 'lavfi', '-i', 'testsrc2=s=400x400:d=1', '-frames:v', '1', '-f', 'image2', '-c:v', 'png', str(thumb_path)])
                else:
                    # Oversized >480KB JPEG
                    thumb_path = work_dir / 'cover.jpg'
                    run_ffmpeg([
                        '-f', 'lavfi', '-i', 'nullsrc=s=1800x1800,geq=random(1)*255:128:128',
                        '-frames:v', '1', '-q:v', '1', str(thumb_path),
                    ])
                    self.assertGreater(thumb_path.stat().st_size, 480_000)

                ydl = ydl_for_dir(work_dir)
                info = {
                    'id': 'BaW_jenozKc',
                    'title': 'Test Track',
                    'track': 'Test Track',
                    'artist': 'Test Artist',
                    'artists': ['Test Artist'],
                    'album': 'Test Album',
                    'album_artist': 'Test Artist',
                    'genre': 'Electronic',
                    'meta_date': '2024',
                    'track_number': '1/5',
                    'ext': ext,
                    'duration': 1.0,
                    'filepath': str(audio_path),
                    'thumbnails': [{'id': 'cover', 'url': 'https://yt3.googleusercontent.com/cover=w544-h544', 'filepath': str(thumb_path)}],
                }
                FFmpegMetadataPP(ydl).run(info)
                self.mod.SwarariaEmbedCoverPP(ydl).run(info)

                cover_jpg = work_dir / 'swararia-cover.jpg'
                self.assertTrue(cover_jpg.is_file())
                self.assertLessEqual(cover_jpg.stat().st_size, 480_000)
                header = cover_jpg.read_bytes()[:2]
                self.assertEqual(b'\xff\xd8', header, 'swararia-cover.jpg must be JPEG')
                self.assertFalse(list(work_dir.glob('*.temp.*')), 'Temp audio files must be cleaned up')

                meta = json.loads((work_dir / 'swararia-meta.json').read_text(encoding='utf-8'))
                self.assertEqual('swararia-cover.jpg', meta['coverFile'])
                self.assertEqual('Test Artist', meta['artist'])
                self.assertEqual('Test Album', meta['album'])

                if ext == 'opus':
                    parsed = OggOpus(audio_path)
                    self.assertEqual(['Test Artist'], parsed.tags.get('artist'))
                    self.assertEqual(['Test Album'], parsed.tags.get('album'))
                    self.assertTrue(parsed.tags.get('metadata_block_picture'), 'Opus file must contain embedded cover art')
                elif ext == 'm4a':
                    parsed = mutagen.File(audio_path)
                    self.assertEqual(['Test Artist'], parsed.tags.get('\xa9ART'))
                    self.assertEqual(['Test Album'], parsed.tags.get('\xa9alb'))
                    covr = parsed.tags.get('covr')
                    self.assertTrue(covr and covr[0].imageformat == MP4Cover.FORMAT_JPEG)
                elif ext == 'mp3':
                    parsed = mutagen.File(audio_path)
                    self.assertEqual(['Test Artist'], parsed.tags.get('TPE1').text)
                    self.assertEqual(['Test Album'], parsed.tags.get('TALB').text)
                    apics = [f for f in parsed.tags.values() if isinstance(f, APIC)]
                    self.assertTrue(apics and apics[0].mime == 'image/jpeg')

    @unittest.skipIf(shutil.which('ffmpeg') is None, 'ffmpeg required')
    def test_embed_cover_skips_unsupported_aac_safely_while_keeping_cover_file(self):
        with tempfile.TemporaryDirectory() as td:
            work_dir = Path(td)
            audio_path = work_dir / 'Track [BaW_jenozKc].aac'
            thumb_path = work_dir / 'cover.png'
            run_ffmpeg(['-f', 'lavfi', '-i', 'sine=frequency=440:duration=1', '-c:a', 'aac', str(audio_path)])
            run_ffmpeg(['-f', 'lavfi', '-i', 'color=c=navy:s=256x256:d=1', '-frames:v', '1', str(thumb_path)])

            info = {
                'id': 'BaW_jenozKc',
                'title': 'Raw AAC Song',
                'track': 'Raw AAC Song',
                'artist': 'AAC Artist',
                'album': 'AAC Album',
                'ext': 'aac',
                'filepath': str(audio_path),
                'thumbnails': [{'id': 'cover', 'url': 'https://lh3.googleusercontent.com/art=w544-h544', 'filepath': str(thumb_path)}],
            }
            self.mod.SwarariaEmbedCoverPP(ydl_for_dir(work_dir)).run(info)
            self.assertTrue(audio_path.is_file() and audio_path.stat().st_size > 0)
            self.assertTrue((work_dir / 'swararia-cover.jpg').is_file())
            meta = json.loads((work_dir / 'swararia-meta.json').read_text(encoding='utf-8'))
            self.assertEqual('swararia-cover.jpg', meta['coverFile'])
            self.assertFalse(list(work_dir.glob('*.temp.*')))


if __name__ == '__main__':
    unittest.main(verbosity=2)
