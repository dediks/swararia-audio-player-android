"""Retain metadata already present in Music search renderers; no extra network request.
App-owned extractor override. The checksum-pinned upstream engine stays untouched.
"""
import re
from yt_dlp.extractor.youtube._search import YoutubeMusicSearchURLIE
from yt_dlp.utils import parse_duration, traverse_obj

class SwarariaMusicSearchIE(YoutubeMusicSearchURLIE, plugin_name='swararia_metadata'):
    def _music_reponsive_list_entry(self, renderer):
        info = super()._music_reponsive_list_entry(renderer)
        if not info:
            return info
        columns = renderer.get('flexColumns') or []
        fixed = renderer.get('fixedColumns') or []
        texts = []
        artists = []
        for index, column in enumerate(columns):
            runs = traverse_obj(column, ('musicResponsiveListItemFlexColumnRenderer', 'text', 'runs')) or []
            for run in runs:
                text = run.get('text') if isinstance(run, dict) else None
                if not isinstance(text, str):
                    continue
                texts.append(text)
                browse_id = traverse_obj(run, ('navigationEndpoint', 'browseEndpoint', 'browseId'))
                if index > 0 and isinstance(browse_id, str) and browse_id.startswith('UC'):
                    artists.append(text)
        for column in fixed:
            for run in traverse_obj(column, ('musicResponsiveListItemFixedColumnRenderer', 'text', 'runs')) or []:
                if isinstance(run, dict) and isinstance(run.get('text'), str):
                    texts.insert(0, run['text'])
        duration = next((parse_duration(text) for text in texts if re.fullmatch(r'(?:\d{1,3}:)?\d{1,2}:\d{2}', text.strip())), None)
        if duration is not None:
            info['duration'] = duration
        if artists:
            info['artist'] = ', '.join(dict.fromkeys(artists))
        thumbs = traverse_obj(renderer, ('thumbnail', 'musicThumbnailRenderer', 'thumbnail', 'thumbnails')) or []
        if thumbs:
            info['thumbnails'] = thumbs
            info['thumbnail'] = thumbs[-1].get('url')
        # Preserve playlist title/creator from renderer; do not call a song artist its uploader.
        browse_id = traverse_obj(renderer, ('navigationEndpoint', 'browseEndpoint', 'browseId'))
        playlist_id = traverse_obj(renderer, ('navigationEndpoint', 'watchEndpoint', 'playlistId'))
        if not playlist_id and isinstance(browse_id, str) and browse_id.startswith('VL'):
            playlist_id = browse_id[2:]
        if not traverse_obj(renderer, ('playlistItemData', 'videoId')) and isinstance(playlist_id, str):
            if re.fullmatch(r'(?:PL|RD|OLAK)[A-Za-z0-9_-]{8,126}', playlist_id):
                info.update(id=playlist_id, url=f'https://www.youtube.com/playlist?list={playlist_id}', ie_key='YoutubeTab')
                info['title'] = traverse_obj(columns, (0, 'musicResponsiveListItemFlexColumnRenderer', 'text', 'runs', 0, 'text'))
                if artists:
                    info['channel'] = artists[0]
                else:
                    # Playlist subtitle is type • creator • item count. A creator
                    # may be plain text; only use this fallback for playlists.
                    creator_runs = traverse_obj(columns, (1, 'musicResponsiveListItemFlexColumnRenderer', 'text', 'runs')) or []
                    subtitle = ''.join(run.get('text', '') for run in creator_runs
                                       if isinstance(run, dict) and isinstance(run.get('text'), str))
                    parts = [part.strip() for part in re.split(r'[•·]', subtitle) if part.strip()]
                    if parts and parts[0].casefold() == 'playlist':
                        parts.pop(0)
                    if parts and not re.fullmatch(r'[\d,.]+\s+(?:songs?|videos?|tracks?|views?)', parts[0], re.I):
                        info['channel'] = parts[0]
        return info

__all__ = []
