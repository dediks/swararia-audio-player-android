"""Enrich downloaded audio with Music metadata and compact JPEG album art.
App-owned postprocessor plugin. Keeps the checksum-pinned upstream engine untouched.
"""
import json
import os
import re
import shutil
import urllib.error
import urllib.parse
import urllib.request

from yt_dlp.compat import imghdr
from yt_dlp.postprocessor.common import PostProcessor
from yt_dlp.postprocessor.embedthumbnail import EmbedThumbnailPP
from yt_dlp.postprocessor.ffmpeg import FFmpegThumbnailsConvertorPP
from yt_dlp.utils import prepend_extension, replace_extension, traverse_obj

_VIDEO_ID_RE = re.compile(r'[A-Za-z0-9_-]{11}')
_ALBUM_BROWSE_RE = re.compile(r'MPREb_[A-Za-z0-9_-]{4,64}')
_YEAR_RE = re.compile(r'(19|20)\d{2}')
_YYYYMMDD_RE = re.compile(r'(\d{4})(\d{2})(\d{2})')
_TOPIC_SUFFIX_RE = re.compile(r'\s*-\s*Topic$', re.IGNORECASE)
_NOISE_TITLE_RE = re.compile(
    r'(?i)(?:\(|\[|\b)(?:official\s+(?:music\s+)?video|official\s+audio|official\s+lyric|'
    r'lyric\s+video|music\s+video|audio\s+only|4k\s*remaster|remastered|hd|hq|\bmv\b)',
)

_ALLOWED_THUMB_HOSTS = frozenset({
    'i.ytimg.com',
    'i1.ytimg.com',
    'i2.ytimg.com',
    'i3.ytimg.com',
    'i4.ytimg.com',
    'i9.ytimg.com',
    'yt3.ggpht.com',
    'lh3.googleusercontent.com',
    'yt3.googleusercontent.com',
})
_SQUARE_COVER_HOSTS = frozenset({
    'yt3.googleusercontent.com',
    'lh3.googleusercontent.com',
})
_ALLOWED_INNERTUBE_URLS = frozenset({
    'https://music.youtube.com/youtubei/v1/next?prettyPrint=false',
    'https://music.youtube.com/youtubei/v1/browse?prettyPrint=false',
    'https://www.youtube.com/youtubei/v1/next?prettyPrint=false',
})
_EMBED_SUPPORTED_EXTS = frozenset({
    'mp3', 'mkv', 'mka', 'ogg', 'opus', 'flac', 'm4a', 'mp4', 'm4v', 'mov',
})
_MAX_COVER_BYTES = 480_000
_MAX_JSON_BYTES = 2 * 1024 * 1024


class _NoRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


_NO_REDIRECT_OPENER = urllib.request.build_opener(_NoRedirectHandler())


def _clean_text(value, max_len=240):
    if not isinstance(value, str):
        return None
    cleaned = ''.join(' ' if ord(ch) < 32 or ord(ch) == 127 else ch for ch in value)
    cleaned = re.sub(r'\s+', ' ', cleaned).strip()
    if not cleaned or cleaned.casefold() == '<unknown>':
        return None
    return cleaned[:max_len]


def _allowed_thumb_url(url):
    if not isinstance(url, str) or not url or len(url) > 4096:
        return False
    if any(ord(ch) < 32 or ord(ch) == 127 for ch in url):
        return False
    try:
        parsed = urllib.parse.urlsplit(url)
    except ValueError:
        return False
    if parsed.scheme.lower() != 'https' or parsed.username is not None or parsed.password is not None:
        return False
    if parsed.port not in (None, 443):
        return False
    return (parsed.hostname or '').lower() in _ALLOWED_THUMB_HOSTS


def _durable_thumb_url(url):
    if not _allowed_thumb_url(url):
        return None
    parsed = urllib.parse.urlsplit(url)
    if parsed.query or parsed.fragment:
        return None
    return url


def _format_date(raw_date, raw_year=None):
    if isinstance(raw_date, str):
        compact = raw_date.strip().replace('-', '')
        m = _YYYYMMDD_RE.fullmatch(compact)
        if m:
            return f'{m.group(1)}-{m.group(2)}-{m.group(3)}'
        if _YEAR_RE.fullmatch(compact):
            return compact
    if raw_year is not None:
        year_str = str(raw_year).strip()
        if _YEAR_RE.fullmatch(year_str):
            return year_str
    return None


def _strip_topic_suffix(name):
    cleaned = _clean_text(name, 160)
    if not cleaned:
        return None
    stripped = _TOPIC_SUFFIX_RE.sub('', cleaned).strip()
    return stripped or cleaned


def _iter_dicts(root, target_key):
    stack = [root]
    visited = 0
    while stack and visited < 30000:
        node = stack.pop()
        visited += 1
        if isinstance(node, dict):
            if target_key in node and isinstance(node[target_key], dict):
                yield node[target_key]
            for val in reversed(list(node.values())):
                if isinstance(val, (dict, list)):
                    stack.append(val)
        elif isinstance(node, list):
            for item in reversed(node):
                if isinstance(item, (dict, list)):
                    stack.append(item)


def _post_innertube(url, payload, origin, timeout=4.5):
    if url not in _ALLOWED_INNERTUBE_URLS:
        return None
    try:
        data = json.dumps(payload).encode('utf-8')
        req = urllib.request.Request(
            url,
            data=data,
            headers={
                'Content-Type': 'application/json',
                'User-Agent': 'Mozilla/5.0',
                'Origin': origin,
            },
            method='POST',
        )
        with _NO_REDIRECT_OPENER.open(req, timeout=timeout) as resp:
            if getattr(resp, 'status', 200) != 200:
                return None
            raw = resp.read(_MAX_JSON_BYTES + 1)
            if len(raw) > _MAX_JSON_BYTES:
                return None
            return json.loads(raw.decode('utf-8'))
    except Exception:
        return None


def _runs_text(node):
    runs = traverse_obj(node, ('runs',))
    if isinstance(runs, list):
        parts = [r.get('text', '') for r in runs if isinstance(r, dict) and isinstance(r.get('text'), str)]
        return _clean_text(''.join(parts))
    return _clean_text(traverse_obj(node, ('simpleText',)))


def _best_square_thumb(thumbnails):
    if not isinstance(thumbnails, list):
        return None
    candidates = []
    for item in thumbnails:
        if not isinstance(item, dict):
            continue
        url = item.get('url')
        if not _allowed_thumb_url(url):
            continue
        host = (urllib.parse.urlsplit(url).hostname or '').lower()
        w = item.get('width') if isinstance(item.get('width'), int) else 0
        h = item.get('height') if isinstance(item.get('height'), int) else 0
        if host in _SQUARE_COVER_HOSTS or (w > 0 and w == h):
            candidates.append((w * h, url))
    if not candidates:
        return None
    candidates.sort(key=lambda pair: pair[0])
    return candidates[-1][1]


def _extract_ytm_next(video_id):
    data = _post_innertube(
        'https://music.youtube.com/youtubei/v1/next?prettyPrint=false',
        {
            'videoId': video_id,
            'context': {'client': {'clientName': 'WEB_REMIX', 'clientVersion': '1.20260101.01.00'}},
        },
        'https://music.youtube.com',
    )
    if not isinstance(data, dict):
        return {}

    target = None
    for ppvr in _iter_dicts(data, 'playlistPanelVideoRenderer'):
        if ppvr.get('videoId') == video_id:
            target = ppvr
            break
    if not target:
        return {}

    title = _runs_text(target.get('title'))
    artists = []
    album = None
    album_id = None
    year = None
    for run in traverse_obj(target, ('longBylineText', 'runs')) or []:
        if not isinstance(run, dict):
            continue
        text = _clean_text(run.get('text'), 160)
        if not text:
            continue
        browse_id = traverse_obj(run, ('navigationEndpoint', 'browseEndpoint', 'browseId'))
        page_type = traverse_obj(run, (
            'navigationEndpoint', 'browseEndpoint', 'browseEndpointContextSupportedConfigs',
            'browseEndpointContextMusicConfig', 'pageType',
        ))
        if page_type == 'MUSIC_PAGE_TYPE_ARTIST' or (isinstance(browse_id, str) and browse_id.startswith('UC')):
            if text not in artists:
                artists.append(text)
        elif album is None and (
            page_type == 'MUSIC_PAGE_TYPE_ALBUM'
            or (isinstance(browse_id, str) and _ALBUM_BROWSE_RE.fullmatch(browse_id))
        ):
            album = text
            if isinstance(browse_id, str) and _ALBUM_BROWSE_RE.fullmatch(browse_id):
                album_id = browse_id
        elif year is None and _YEAR_RE.fullmatch(text):
            year = text

    square_thumb = _best_square_thumb(traverse_obj(target, ('thumbnail', 'thumbnails')))
    return {
        'title': title,
        'artists': artists,
        'album': album,
        'album_id': album_id,
        'year': year,
        'square_thumb': square_thumb,
    }


def _extract_ytm_album(album_id, video_id):
    if not isinstance(album_id, str) or not _ALBUM_BROWSE_RE.fullmatch(album_id):
        return {}
    data = _post_innertube(
        'https://music.youtube.com/youtubei/v1/browse?prettyPrint=false',
        {
            'browseId': album_id,
            'context': {'client': {'clientName': 'WEB_REMIX', 'clientVersion': '1.20260101.01.00'}},
        },
        'https://music.youtube.com',
    )
    if not isinstance(data, dict):
        return {}

    album_title = None
    album_artist = None
    album_year = None
    square_thumb = None
    for hdr in _iter_dicts(data, 'musicResponsiveHeaderRenderer'):
        album_title = _runs_text(hdr.get('title'))
        album_artist = _runs_text(hdr.get('straplineTextOne'))
        for run in traverse_obj(hdr, ('subtitle', 'runs')) or []:
            text = _clean_text(run.get('text') if isinstance(run, dict) else None, 20)
            if text and _YEAR_RE.fullmatch(text):
                album_year = text
                break
        square_thumb = _best_square_thumb(
            traverse_obj(hdr, ('thumbnail', 'musicThumbnailRenderer', 'thumbnail', 'thumbnails')),
        )
        break

    track_ids = []
    for item in _iter_dicts(data, 'musicResponsiveListItemRenderer'):
        vid = traverse_obj(item, ('playlistItemData', 'videoId')) or traverse_obj(
            item,
            ('flexColumns', 0, 'musicResponsiveListItemFlexColumnRenderer', 'text', 'runs', 0,
             'navigationEndpoint', 'watchEndpoint', 'videoId'),
        )
        if isinstance(vid, str) and _VIDEO_ID_RE.fullmatch(vid):
            track_ids.append(vid)

    track_number = None
    if video_id in track_ids:
        track_number = f'{track_ids.index(video_id) + 1}/{len(track_ids)}'

    return {
        'album_title': album_title,
        'album_artist': album_artist,
        'album_year': album_year,
        'square_thumb': square_thumb,
        'track_number': track_number,
    }


def _raw_runs_text(node):
    runs = traverse_obj(node, ('runs',))
    if isinstance(runs, list):
        return ''.join(r.get('text', '') for r in runs if isinstance(r, dict) and isinstance(r.get('text'), str))
    simple = traverse_obj(node, ('simpleText',))
    return simple if isinstance(simple, str) else ''


def _parse_song_credits_dialog(card):
    messages = traverse_obj(card, (
        'overflowMenuOnTap', 'innertubeCommand', 'confirmDialogEndpoint',
        'content', 'confirmDialogRenderer', 'dialogMessages',
    )) or []
    full = []
    for msg in messages:
        if isinstance(msg, dict):
            txt = _raw_runs_text(msg)
            if txt.strip():
                full.append(txt)
    if not full:
        return {}
    raw = '\n'.join(full)
    parsed = {}
    for line in re.split(r'[\r\n]+', raw):
        if ':' not in line:
            continue
        label, val = line.split(':', 1)
        key = label.strip().casefold()
        cleaned = _clean_text(val, 200)
        if not cleaned:
            continue
        if key == 'song' and 'song' not in parsed:
            parsed['song'] = cleaned
        elif key == 'artist' and 'artist' not in parsed:
            parsed['artist'] = cleaned
        elif key == 'album' and 'album' not in parsed:
            parsed['album'] = cleaned
        elif key in ('writers', 'writer', 'composer', 'composers') and 'writers' not in parsed:
            parsed['writers'] = cleaned
    return parsed


def _extract_web_music_card(video_id):
    data = _post_innertube(
        'https://www.youtube.com/youtubei/v1/next?prettyPrint=false',
        {
            'videoId': video_id,
            'context': {'client': {'clientName': 'WEB', 'clientVersion': '2.20260708.00.00'}},
        },
        'https://www.youtube.com',
    )
    if not isinstance(data, dict):
        return {}

    for hclr in _iter_dicts(data, 'horizontalCardListRenderer'):
        header_title = _runs_text(traverse_obj(hclr, ('header', 'richListHeaderRenderer', 'title')))
        footer_browse = traverse_obj(hclr, ('footerButton', 'buttonViewModel', 'onTap', 'innertubeCommand', 'browseEndpoint', 'browseId'))
        if footer_browse != 'UC-9-kyTW8ZkZNDHQJ6FgpwQ' and (header_title or '').casefold() != 'music':
            continue
        cards = hclr.get('cards')
        if not isinstance(cards, list) or len(cards) != 1 or not isinstance(cards[0], dict):
            continue
        card = cards[0].get('videoAttributeViewModel')
        if not isinstance(card, dict):
            continue
        credits_info = _parse_song_credits_dialog(card)
        song = credits_info.get('song') or _clean_text(card.get('title'), 200)
        artist = credits_info.get('artist') or _clean_text(card.get('subtitle'), 160)
        album = credits_info.get('album') or _clean_text(traverse_obj(card, ('secondarySubtitle', 'content')), 160)
        writers = credits_info.get('writers')
        square_thumb = None
        if card.get('imageStyle') == 'VIDEO_ATTRIBUTE_IMAGE_STYLE_SQUARE':
            sources = traverse_obj(card, ('image', 'sources')) or []
            for src in reversed(sources):
                url = src.get('url') if isinstance(src, dict) else None
                if _allowed_thumb_url(url):
                    square_thumb = url
                    break
        return {
            'song': song,
            'artist': artist,
            'album': album,
            'writers': writers,
            'square_thumb': square_thumb,
        }
    return {}


def _resolve_genre(info):
    direct = _clean_text(info.get('genre'), 60)
    if direct:
        return direct
    for key in ('genres', 'categories'):
        values = info.get(key)
        if isinstance(values, list):
            cleaned = [c for c in (_clean_text(v, 40) for v in values) if c]
            if cleaned:
                return ', '.join(cleaned)[:60]
    return None


def _safe_public_thumb_url(info):
    candidates = []
    if info.get('thumbnail'):
        candidates.append(info['thumbnail'])
    for item in reversed(info.get('thumbnails') or []):
        if isinstance(item, dict) and item.get('url'):
            candidates.append(item['url'])
    for url in candidates:
        durable = _durable_thumb_url(url)
        if durable:
            return durable
    vid = info.get('id')
    if isinstance(vid, str) and _VIDEO_ID_RE.fullmatch(vid):
        return f'https://i.ytimg.com/vi/{vid}/hqdefault.jpg'
    return None


def _meta_payload(info, cover_file=None):
    duration = info.get('duration')
    duration_ms = int(duration * 1000) if isinstance(duration, (int, float)) and duration > 0 else None
    artist = _clean_text(info.get('artist'), 160)
    if not artist and isinstance(info.get('artists'), list):
        artist = _clean_text(', '.join(a for a in info['artists'] if isinstance(a, str) and a.strip()), 160)
    return {
        'videoId': info.get('id'),
        'title': _clean_text(info.get('title'), 240),
        'track': _clean_text(info.get('track') or info.get('title'), 240),
        'artist': artist,
        'album': _clean_text(info.get('album'), 160),
        'albumArtist': _clean_text(info.get('album_artist'), 160),
        'genre': _resolve_genre(info),
        'date': _clean_text(info.get('meta_date'), 32),
        'trackNumber': _clean_text(info.get('track_number'), 32),
        'durationMs': duration_ms,
        'thumbnailUrl': _safe_public_thumb_url(info),
        'coverFile': cover_file,
    }


def _write_meta_file(directory, payload):
    if not directory or not os.path.isdir(directory):
        return
    target = os.path.join(directory, 'swararia-meta.json')
    tmp = os.path.join(directory, 'swararia-meta.json.tmp')
    try:
        with open(tmp, 'w', encoding='utf-8') as handle:
            json.dump(payload, handle, ensure_ascii=False)
        os.replace(tmp, target)
    except Exception:
        try:
            if os.path.exists(tmp):
                os.remove(tmp)
        except Exception:
            pass


class SwarariaEnrichPP(PostProcessor):
    @PostProcessor._restrict_to(images=False)
    def run(self, info):
        if info.get('_type', 'video') != 'video':
            return [], info
        video_id = info.get('id')
        if not isinstance(video_id, str) or not _VIDEO_ID_RE.fullmatch(video_id):
            return [], info

        ytm = _extract_ytm_next(video_id)
        ytm_album_info = _extract_ytm_album(ytm.get('album_id'), video_id) if ytm.get('album_id') else {}

        has_album = bool(info.get('album') or ytm.get('album') or ytm_album_info.get('album_title'))
        has_artists = bool(info.get('artists') or info.get('artist') or ytm.get('artists'))
        web_card = _extract_web_music_card(video_id) if (not has_album or not has_artists) else {}

        # Track title
        ytm_title = ytm.get('title')
        card_song = web_card.get('song')
        card_album = web_card.get('album')
        if not info.get('track'):
            if ytm_title and not (card_song and card_album and (' - ' in ytm_title or _NOISE_TITLE_RE.search(ytm_title))):
                info['track'] = ytm_title
            elif card_song and card_album:
                info['track'] = card_song
            elif ytm_title:
                info['track'] = ytm_title
            elif card_song:
                info['track'] = card_song

        # Artists
        existing_artists = []
        if isinstance(info.get('artists'), list):
            existing_artists = [a for a in (_clean_text(x, 120) for x in info['artists']) if a]
        elif _clean_text(info.get('artist'), 120):
            existing_artists = [_clean_text(info.get('artist'), 120)]

        if existing_artists:
            resolved_artists = existing_artists
        elif ytm.get('artists'):
            resolved_artists = ytm['artists']
        elif web_card.get('artist'):
            resolved_artists = [web_card['artist']]
        elif isinstance(info.get('creators'), list) and any(_clean_text(x, 120) for x in info['creators']):
            resolved_artists = [c for c in (_clean_text(x, 120) for x in info['creators']) if c]
        else:
            fallback = _strip_topic_suffix(info.get('channel') or info.get('uploader'))
            resolved_artists = [fallback] if fallback else []

        if resolved_artists:
            info['artists'] = list(dict.fromkeys(resolved_artists))
            info['artist'] = ', '.join(info['artists'])

        # Album & album artist
        resolved_album = (
            _clean_text(info.get('album'), 160)
            or ytm.get('album')
            or ytm_album_info.get('album_title')
            or card_album
        )
        if resolved_album:
            info['album'] = resolved_album

        resolved_album_artist = (
            _clean_text(info.get('album_artist'), 160)
            or ytm_album_info.get('album_artist')
            or (info['artists'][0] if resolved_album and info.get('artists') else None)
        )
        if resolved_album_artist:
            info['album_artist'] = resolved_album_artist

        if not info.get('track_number') and ytm_album_info.get('track_number'):
            info['track_number'] = ytm_album_info['track_number']

        if not info.get('composer') and web_card.get('writers'):
            info['composer'] = web_card['writers']

        # Prefer actual release date/year over upload date
        resolved_date = (
            _format_date(info.get('release_date'), info.get('release_year'))
            or _format_date(None, ytm.get('year') or ytm_album_info.get('album_year'))
            or _format_date(info.get('upload_date'))
        )
        if resolved_date:
            info['meta_date'] = resolved_date

        # Ensure all thumbnails have an id and promote square cover art if available
        thumbs = [dict(t) for t in (info.get('thumbnails') or []) if isinstance(t, dict) and t.get('url')]
        for idx, thumb in enumerate(thumbs):
            if not thumb.get('id'):
                thumb['id'] = f'thumb_{idx}'
        square_cover = (
            ytm_album_info.get('square_thumb')
            or ytm.get('square_thumb')
            or web_card.get('square_thumb')
        )
        if square_cover and _allowed_thumb_url(square_cover):
            thumbs.append({
                'id': 'swararia_cover',
                'url': square_cover,
                'preference': 100,
            })
            info['thumbnail'] = square_cover
        if thumbs:
            info['thumbnails'] = thumbs

        try:
            predicted = self._downloader.prepare_filename(info)
            if predicted:
                _write_meta_file(os.path.dirname(predicted), _meta_payload(info, cover_file=None))
        except Exception:
            pass

        return [], info


class SwarariaEmbedCoverPP(PostProcessor):
    @PostProcessor._restrict_to(images=False)
    def run(self, info):
        filepath = info.get('filepath')
        if not filepath or not os.path.isfile(filepath):
            return [], info
        work_dir = os.path.dirname(filepath)
        cover_name = 'swararia-cover.jpg'
        cover_path = os.path.join(work_dir, cover_name)
        temp_audio = prepend_extension(filepath, 'temp')

        thumbs = info.get('thumbnails') or []
        idx = next(
            (-i for i, t in enumerate(thumbs[::-1], 1)
             if isinstance(t, dict) and t.get('filepath') and os.path.isfile(t['filepath'])),
            None,
        )

        wrote_cover = False
        if idx is not None:
            raw_thumb = None
            tmp_jpg = os.path.join(work_dir, 'swararia-cover.tmp.jpg')
            tmp_scaled = os.path.join(work_dir, 'swararia-cover.scaled.jpg')
            try:
                conv = FFmpegThumbnailsConvertorPP(self._downloader, 'jpg')
                raw_thumb = info['thumbnails'][idx]['filepath']
                if imghdr.what(raw_thumb) == 'webp' and not raw_thumb.lower().endswith('.webp'):
                    webp_thumb = replace_extension(raw_thumb, 'webp')
                    os.replace(raw_thumb, webp_thumb)
                    raw_thumb = webp_thumb
                    info['thumbnails'][idx]['filepath'] = webp_thumb
                kind = imghdr.what(raw_thumb)
                raw_size = os.path.getsize(raw_thumb)

                if kind == 'jpeg' and 0 < raw_size <= _MAX_COVER_BYTES:
                    if os.path.realpath(raw_thumb) != os.path.realpath(cover_path):
                        shutil.copyfile(raw_thumb, cover_path)
                else:
                    in_opts = [] if kind == 'gif' else ['-f', 'image2', '-pattern_type', 'none']
                    if kind == 'jpeg' and raw_size > _MAX_COVER_BYTES:
                        conv.real_run_ffmpeg(
                            [(raw_thumb, in_opts)],
                            [(tmp_jpg, [
                                '-vf', "scale='min(720,iw)':'min(720,ih)':force_original_aspect_ratio=decrease",
                                '-q:v', '4', '-update', '1', '-bsf:v', 'mjpeg2jpeg',
                            ])],
                        )
                    else:
                        conv.real_run_ffmpeg(
                            [(raw_thumb, in_opts)],
                            [(tmp_jpg, ['-update', '1', '-bsf:v', 'mjpeg2jpeg'])],
                        )
                        if os.path.getsize(tmp_jpg) > _MAX_COVER_BYTES:
                            conv.real_run_ffmpeg(
                                [(tmp_jpg, ['-f', 'image2', '-pattern_type', 'none'])],
                                [(tmp_scaled, [
                                    '-vf', "scale='min(720,iw)':'min(720,ih)':force_original_aspect_ratio=decrease",
                                    '-q:v', '4', '-update', '1', '-bsf:v', 'mjpeg2jpeg',
                                ])],
                            )
                            os.replace(tmp_scaled, tmp_jpg)
                    os.replace(tmp_jpg, cover_path)

                if os.path.isfile(cover_path) and os.path.getsize(cover_path) > 0:
                    wrote_cover = True
                    if raw_thumb and os.path.realpath(raw_thumb) != os.path.realpath(cover_path) and os.path.exists(raw_thumb):
                        try:
                            os.remove(raw_thumb)
                        except OSError:
                            pass

                    thumb_entry = info['thumbnails'][idx]
                    thumb_entry['filepath'] = cover_path
                    thumb_entry['ext'] = 'jpg'
                    thumb_entry.pop('width', None)
                    thumb_entry.pop('height', None)

                    ext = (info.get('ext') or os.path.splitext(filepath)[1][1:]).lower()
                    if ext in _EMBED_SUPPORTED_EXTS:
                        EmbedThumbnailPP(self._downloader, already_have_thumbnail=True).run(info)
            except Exception as err:
                self.report_warning(f'Optional cover embedding skipped: {err}')
            finally:
                for leftover in (temp_audio, tmp_jpg, tmp_scaled):
                    if os.path.exists(leftover):
                        try:
                            os.remove(leftover)
                        except OSError:
                            pass

        _write_meta_file(work_dir, _meta_payload(info, cover_file=cover_name if wrote_cover else None))
        return [], info


__all__ = ['SwarariaEnrichPP', 'SwarariaEmbedCoverPP']
