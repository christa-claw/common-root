#!/usr/bin/env python3
"""
generate_arguments_sql.py
=========================
Parses transcript filenames across all channels and generates SQL INSERT
statements for the comments and comment_references tables.

Video URLs are resolved by:
  1. Video ID embedded in VTT filename as [XXXXXXXXXXX].en.vtt  (new files)
  2. Sibling .info.json in same directory, matched by title stem  (moved/co-located)

Move .info.json files into the same folder as the VTTs, then run:
    python3 generate_arguments_sql.py > transcripts/arguments.sql
    mysql -u rtuser -p religioustext < transcripts/arguments.sql
"""

import os
import re
import glob
import json
from datetime import datetime

TRANSCRIPTS_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))), "transcripts")
PLATFORM_USER_ID = "usr-00000000-0000-7000-8000-000000000001"

# Tradition reflects the channel's primary perspective or format.
# Channels that host cross-tradition debates use "Multi" rather than
# picking a single religion. This is stored in the comments table and
# can be used for filtering in the UI.
CHANNEL_TRADITION = {
    # Islamic perspective
    "Dr Zakir Naik":              "Islamic",
    "Ali Dawah":                  "Islamic",
    "DUS Dawah":                  "Islamic",
    "Mohammed Hijab":             "Islamic",
    "Let the Quran Speak":        "Islamic",
    "DAWAH BRO'S PODCAST":        "Islamic",
    # Christian perspective
    "Apologetics Roadshow":       "Christian",
    "GodLogic Apologetics":       "Christian",
    "Hatun Tash DCCI Ministries": "Christian",
    "Shamounian Explains":        "Christian",
    # Cross-tradition / debate format
    "Modern Day Debate":          "Multi",
    # Critical / secular analysis
    "JihadWatchVideo":            "Critical",
    "Raymond Ibrahim":            "Critical",
}

# Argument types — broad enough to cover intra-religion debates too
# (e.g. Catholic vs Protestant, Sunni vs Shia) not just Islam vs Christianity.
ARGUMENT_TYPES = {
    'prophecy':    ['predict', 'prophecy', 'prophes', 'fulfill', 'isaiah 53',
                    'deuteronomy 18', 'sign of jonah', 'melchizedek', 'immanuel'],
    'historical':  ['contradict', 'contradiction', 'refuted', 'error', 'corrupt',
                    'changed', 'removed', 'manuscript'],
    'contextual':  ['context', 'parable', 'what did jesus mean', 'what does it mean',
                    'slay unbelievers', 'dash babies', 'kill babies', 'eye for an eye',
                    'slavery', 'rapist', 'polygamy'],
    'debate':      ['vs', 'debate', 'debates', 'full debate', 'live debate',
                    'discusses', 'face off'],
    'comparative': ['quran', 'muhammad', 'islam', 'muslim', 'allah',
                    'christian vs', 'islamic vs', 'sunni', 'shia', 'protestant',
                    'catholic vs', 'orthodox vs'],
}

BOOK_CODES = {
    "genesis":"GEN","exodus":"EXO","leviticus":"LEV","numbers":"NUM",
    "deuteronomy":"DEU","joshua":"JOS","judges":"JDG","ruth":"RUT",
    "1samuel":"1SA","2samuel":"2SA","1kings":"1KI","2kings":"2KI",
    "1chronicles":"1CH","2chronicles":"2CH","ezra":"EZR","nehemiah":"NEH",
    "esther":"EST","job":"JOB","psalms":"PSA","psalm":"PSA","proverbs":"PRO",
    "ecclesiastes":"ECC","songofsolomon":"SNG","song":"SNG","isaiah":"ISA",
    "jeremiah":"JER","lamentations":"LAM","ezekiel":"EZK","daniel":"DAN",
    "hosea":"HOS","joel":"JOL","amos":"AMO","obadiah":"OBA","jonah":"JON",
    "micah":"MIC","nahum":"NAM","habakkuk":"HAB","zephaniah":"ZEP",
    "haggai":"HAG","zechariah":"ZEC","malachi":"MAL","matthew":"MAT",
    "mark":"MRK","luke":"LUK","john":"JHN","acts":"ACT","romans":"ROM",
    "1corinthians":"1CO","2corinthians":"2CO","galatians":"GAL",
    "ephesians":"EPH","philippians":"PHP","colossians":"COL",
    "1thessalonians":"1TH","2thessalonians":"2TH","1timothy":"1TI",
    "2timothy":"2TI","titus":"TIT","philemon":"PHM","hebrews":"HEB",
    "james":"JAS","1peter":"1PE","2peter":"2PE","1john":"1JN",
    "2john":"2JN","3john":"3JN","jude":"JUD","revelation":"REV",
}

BIBLE_PATTERN = re.compile(r'((?:\d\s*)?[A-Za-z]+)\s+(\d+)[:\._]+(\d+)', re.IGNORECASE)
QURAN_PATTERN = re.compile(r'(?:surah?|quran|chapter)\s*(\d+)[:\.\s]+(\d+)', re.IGNORECASE)

SKIP_KEYWORDS = [
    'reality check', 'dating a muslim', 'george janko',
    'charlie kirk', 'nick fuentes', 'pbd podcast', 'david wood',
    'apostate prophet', 'upbringing', 'why he became',
    'miracle on why', 'clarifies why he curses', 'san diego mosque tragedy',
    'hantavirus', 'aliens', 'cruise ship', 'bondi beach',
    'israel banning', 'pride festival', 'chuck norris',
    'censorship', 'voice of reason', 'miss logic', 'lady logic',
    'needgodnet', 'hospital', 'muslim australia', 'palm sunday',
    "da'wah training", 'become effective', 'dhul hijjah', 'arafah',
    'ramadan live', 'friday night',
]


def build_dir_json_index(directory):
    """
    Build a stem -> url index for all .info.json files in a directory.
    Strips the [VIDEOID] suffix so stems match VTT filenames.
    """
    index = {}
    for json_path in glob.glob(os.path.join(directory, "*.info.json")):
        basename = os.path.basename(json_path)
        stem = re.sub(r'\s*\[[A-Za-z0-9_-]{11}\]\.info\.json$', '', basename)
        stem = re.sub(r'\.info\.json$', '', stem)
        try:
            with open(json_path) as f:
                data = json.load(f)
            url = data.get("webpage_url") or data.get("original_url")
            if url:
                index[stem] = url
        except Exception:
            pass
    return index


_dir_cache = {}

def video_url_for(vtt_path):
    """Resolve YouTube URL for a VTT file."""
    # Strategy 1: ID embedded in VTT filename
    m = re.search(r'\[([A-Za-z0-9_-]{11})\]\.en\.vtt$', vtt_path)
    if m:
        return f"https://www.youtube.com/watch?v={m.group(1)}"

    # Strategy 2: Sibling .info.json matched by stem
    vtt_dir = os.path.dirname(vtt_path)
    if vtt_dir not in _dir_cache:
        _dir_cache[vtt_dir] = build_dir_json_index(vtt_dir)
    index = _dir_cache[vtt_dir]
    vtt_stem = re.sub(r'\.en\.vtt$', '', os.path.basename(vtt_path))
    if vtt_stem in index:
        return index[vtt_stem]

    return None


def extract_verse_refs(filename):
    name = os.path.basename(filename)
    name = re.sub(r'^\d{8}_', '', name)
    name = re.sub(r'\[[A-Za-z0-9_-]{11}\]', '', name)
    name = re.sub(r'\.en\.vtt$', '', name)
    name = name.replace('_', ' ')
    refs = []
    for m in BIBLE_PATTERN.finditer(name):
        book_raw = m.group(1).lower().replace(' ', '')
        chapter, verse = int(m.group(2)), int(m.group(3))
        code = BOOK_CODES.get(book_raw)
        if code:
            refs.append({'type': 'bible', 'code': code, 'chapter': chapter, 'verse': verse})
    for m in QURAN_PATTERN.finditer(name):
        refs.append({'type': 'quran', 'surah': int(m.group(1)), 'ayah': int(m.group(2))})
    return refs


def title_from_filename(filename):
    name = os.path.basename(filename)
    name = re.sub(r'^\d{8}_', '', name)
    name = re.sub(r'\[[A-Za-z0-9_-]{11}\]', '', name)
    name = re.sub(r'\.en\.vtt$', '', name)
    name = re.sub(r'\s*[｜|]\s*(Sam Shamoun|@shamounian|Dr Zakir Naik|Mohammed Hijab|Shamounian|GodLogic|God Logic).*$', '', name)
    return name.strip()


def is_useful(filename):
    return not any(kw in filename.lower() for kw in SKIP_KEYWORDS)


def determine_argument_type(title):
    t = title.lower()
    for arg_type, keywords in ARGUMENT_TYPES.items():
        if any(k in t for k in keywords):
            return arg_type
    return 'theological'


def sql_escape(s):
    return s.replace("'", "''").replace("\\", "\\\\")


def generate_comment_id(channel, date_str, index):
    slug = re.sub(r'[^a-z0-9]', '', channel.lower())[:8]
    return f"cmt-{date_str}-{slug}-{index:04d}"


def main():
    print("-- Generated by generate_arguments_sql.py")
    print(f"-- Generated at: {datetime.now().isoformat()}")
    print("-- Load with: mysql -u rtuser -p religioustext < arguments.sql")
    print()
    print("START TRANSACTION;")
    print()
    print("-- System user (usr-...001) is created by Flyway V2__seed_arguments.sql;")
    print("-- it already exists in the DB, so it is NOT inserted here.")
    print()

    total = 0
    missing_urls = 0
    ref_index = 0

    for channel in sorted(os.listdir(TRANSCRIPTS_DIR)):
        channel_dir = os.path.join(TRANSCRIPTS_DIR, channel)
        if not os.path.isdir(channel_dir) or channel.startswith('.'):
            continue

        tradition = CHANNEL_TRADITION.get(channel, "Unknown")
        vtt_files = sorted(glob.glob(os.path.join(channel_dir, "**", "*.vtt"), recursive=True))
        useful_files = [f for f in vtt_files if is_useful(f)]

        if not useful_files:
            continue

        print(f"-- Channel: {channel} ({tradition}) — {len(useful_files)} videos")
        print()

        for i, vtt_file in enumerate(useful_files):
            title    = title_from_filename(vtt_file)
            refs     = extract_verse_refs(vtt_file)
            arg_type = determine_argument_type(title)

            basename   = os.path.basename(vtt_file)
            date_match = re.match(r'^(\d{8})_', basename)
            date_str   = date_match.group(1) if date_match else "00000000"
            pub_date   = f"{date_str[:4]}-{date_str[4:6]}-{date_str[6:8]}"

            video_url    = video_url_for(vtt_file)
            comment_id   = generate_comment_id(channel, date_str, i)
            # Option A: fold metadata into `content`, mirroring DataSeeder.java.
            # The live `comments` schema is (id, user_id, content, is_public,
            # moderation_status) — there are no channel/tradition/argument_type/
            # video_url columns, so all of that goes into the content text. The
            # source-video link is appended there too (kept clickable as plain URL).
            content_text = f"[{channel} \u2014 {tradition} \u2014 {arg_type}] {title}"
            if video_url:
                content_text += f"  \u2014  Source: {video_url}"
            else:
                missing_urls += 1
            content = sql_escape(content_text)

            print(f"INSERT IGNORE INTO comments "
                  f"(id, user_id, content, is_public, moderation_status) VALUES ("
                  f"'{comment_id}', '{PLATFORM_USER_ID}', '{content}', TRUE, 'approved');")

            position = 0
            for ref in refs:
                ref_index += 1
                ref_id = f"ref-{comment_id}-{ref_index:04d}"
                if ref['type'] == 'bible':
                    # Internal ref; source_id NULL = translation-independent (same
                    # convention DataSeeder uses for Bible refs).
                    print(f"INSERT IGNORE INTO comment_references "
                          f"(id, comment_id, position, ref_type, source_id, book_code, chapter, verse) VALUES ("
                          f"'{ref_id}', '{comment_id}', {position}, 'internal', NULL, "
                          f"'{ref['code']}', {ref['chapter']}, {ref['verse']});")
                else:
                    # Quran ref mapped onto the internal-ref columns with
                    # source_id='quran', book_code=zero-padded surah (matches
                    # DataSeeder until Quran ingestion lands).
                    surah = ref['surah']
                    print(f"INSERT IGNORE INTO comment_references "
                          f"(id, comment_id, position, ref_type, source_id, book_code, chapter, verse) VALUES ("
                          f"'{ref_id}', '{comment_id}', {position}, 'internal', 'quran', "
                          f"'{surah:03d}', {surah}, {ref['ayah']});")
                position += 1

            total += 1

        print()

    print(f"-- Total: {total} comments, {ref_index} verse refs")
    print(f"-- Missing video_url: {missing_urls}")
    print("COMMIT;")


if __name__ == "__main__":
    main()
