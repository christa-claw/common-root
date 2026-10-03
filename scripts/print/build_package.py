#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Christa Claw
"""
build_package.py — everything a printer needs, in one archive.

WHY THIS EXISTS
---------------
Pretore asked for two things (2026-09-22): a print-ready PDF with embedded,
licensed fonts, and "the content and the original copyright holder info".
The second is the unusual one. For public domain text there IS no rights
holder, so the answer is not a licence but a PROVENANCE STATEMENT: who
translated it, when it was published, and the specific reason it is free.

Assembling that by hand once is fine. Assembling it by hand every time an
edition changes is how a wrong claim eventually reaches a printer. So it is
generated from a registry, and anything unverified is marked unverified rather
than quietly omitted.

    python3 scripts/print/build_package.py --translation bible-web \\
        --ordering chronological --canon 66 --trim standard \\
        --fonts fonts/charis --out out/web-chrono-package.zip
"""

import argparse
import hashlib
import json
import os
import subprocess
import sys
import zipfile
from datetime import date

HERE = os.path.dirname(os.path.abspath(__file__))

# ── rights registry ───────────────────────────────────────────────────────────
#
# One entry per edition we are willing to put on a press. `status` is the claim
# a printer relies on, so it is written to be checkable, not reassuring.
# VERIFIED means someone has confirmed it against a primary source; REVIEW
# means it is our understanding and has NOT been confirmed — an edition in
# REVIEW must not be printed commercially until it is resolved.

EDITIONS = {
    "bible-web": {
        "title": "The World English Bible",
        "abbrev": "WEB",
        "translator": "Michael Paul Johnson and contributors, revising the "
                      "American Standard Version of 1901",
        "published": "1997–present (continuously revised)",
        "publisher": "eBible.org",
        "status": "VERIFIED",
        "why": "Dedicated to the public domain by its editor. The WEB carries "
               "an explicit worldwide public-domain dedication; it is not "
               "merely out of copyright but deliberately placed outside it.",
        "restrictions": "None known, worldwide.",
    },
    "bible-kjv-1611": {
        "title": "The Holy Bible, King James Version",
        "abbrev": "KJV",
        "translator": "The translators appointed by King James VI and I",
        "published": "1611 (this text follows the standard later revision)",
        "publisher": "Robert Barker, London",
        "status": "VERIFIED",
        "why": "Out of copyright by age everywhere its term could ever have "
               "run.",
        "restrictions": "⚠ NOT free in the United Kingdom. The KJV is held "
                        "under perpetual Crown copyright, exercised through "
                        "letters patent (Cambridge University Press). Printing "
                        "outside the UK is unaffected; SELLING OR SHIPPING "
                        "INTO THE UK IS NOT. Exclude the UK, or obtain "
                        "permission, before any KJV edition is offered there.",
    },
    "bible-asv-1901": {
        "title": "The American Standard Version",
        "abbrev": "ASV",
        "translator": "The American Revision Committee",
        "published": "1901",
        "publisher": "Thomas Nelson & Sons, New York",
        "status": "VERIFIED",
        "why": "Published in 1901; copyright expired.",
        "restrictions": "None known.",
    },
    "bible-fi-1776": {
        "title": "Biblia, Se on: Koko Pyhä Raamattu",
        "abbrev": "FB1776",
        "translator": "Revision commissioned under Gustav III",
        "published": "1776",
        "publisher": "Turku",
        "status": "VERIFIED",
        "why": "Published in 1776; copyright expired.",
        "restrictions": "None known.",
    },
    "bible-fi-1933": {
        "title": "Pyhä Raamattu, vuoden 1933/38 käännös",
        "abbrev": "KR3338",
        "translator": "Commissioned by the XI and XII General Synods of the "
                      "Evangelical Lutheran Church of Finland",
        "published": "1933 (Old Testament), 1938 (New Testament)",
        "publisher": "Suomen Pipliaseura",
        "status": "REVIEW",
        "why": "Widely treated as free to reproduce, and a small commercial "
               "publisher (KKJMK Oy, Pietarsaari) printed and sold it in 2006 "
               "— strong precedent but not a determination.",
        "restrictions": "⚠ PRINT RIGHTS NOT VERIFIED. Displaying this text and "
                        "selling it printed are different acts. Resolve with "
                        "the rights holder before any commercial print run.",
    },
    "bible-lut1912-1912": {
        "title": 'Die Bibel, nach der deutschen Übersetzung D. Martin Luthers (1912)',
        "abbrev": 'LUT1912',
        "translator": 'Martin Luther; revision authorised by the Deutsche Evangelische Kirchenkonferenz',
        "published": '1912',
        "publisher": 'Privilegierte Württembergische Bibelanstalt, Stuttgart',
        "status": "REVIEW",
        "why": 'Out of copyright by age: published 1912.',
        "restrictions": 'Not yet checked against a primary source. Our understanding is that nothing restricts printing; confirm before a commercial run.',
    },
    "bible-de-1545": {
        "title": 'Biblia: Das ist: Die gantze Heilige Schrifft: Deudsch',
        "abbrev": 'LUT1545',
        "translator": 'Martin Luther',
        "published": '1545',
        "publisher": 'Hans Lufft, Wittenberg',
        "status": "REVIEW",
        "why": 'Out of copyright by age: published 1545.',
        "restrictions": "⚠ The text is free; the digital transcription (gratis-bible) may carry an editor's rights of its own. Confirm before a commercial run.",
    },
    "bible-de-elberfelder": {
        "title": 'Die Heilige Schrift (Elberfelder 1905)',
        "abbrev": 'ELB',
        "translator": 'J. N. Darby, C. Brockhaus, J. A. von Poseck and others',
        "published": '1871; this text is the 1905 revision',
        "publisher": 'R. Brockhaus, Elberfeld',
        "status": "REVIEW",
        "why": 'Out of copyright by age: published 1905.',
        "restrictions": 'Not yet checked against a primary source. Our understanding is that nothing restricts printing; confirm before a commercial run.',
    },
    "bible-rv-1885": {
        "title": 'The Holy Bible, Revised Version',
        "abbrev": 'RV',
        "translator": 'The English Revision Committees',
        "published": '1881 (NT), 1885 (OT), 1895 (Apocrypha)',
        "publisher": 'Oxford and Cambridge University Presses',
        "status": "REVIEW",
        "why": 'Out of copyright by age: published 1881–1895.',
        "restrictions": 'Not yet checked against a primary source. Our understanding is that nothing restricts printing; confirm before a commercial run.',
    },
    "bible-gnv-1599": {
        "title": 'The Geneva Bible',
        "abbrev": 'GNV',
        "translator": 'William Whittingham and the Geneva exiles',
        "published": '1560; this text follows the 1599 edition',
        "publisher": 'Christopher Barker, London',
        "status": "REVIEW",
        "why": 'Out of copyright by age: published 1599.',
        "restrictions": '⚠ Modern-spelling editions of the 1599 Geneva are under recent copyright; establish which text the source (wldeh/bible-api) carries. Confirm before a commercial run.',
    },
    "bible-en-tyndale-1534": {
        "title": 'The Newe Testament',
        "abbrev": 'TYN',
        "translator": 'William Tyndale',
        "published": '1534',
        "publisher": 'Martin de Keyser, Antwerp',
        "status": "REVIEW",
        "why": 'Out of copyright by age: published 1534.',
        "restrictions": 'Not yet checked against a primary source. Our understanding is that nothing restricts printing; confirm before a commercial run.',
    },
    "bible-en-webster-1833": {
        "title": 'The Holy Bible, with Amendments of the Language',
        "abbrev": 'WBS',
        "translator": 'Noah Webster',
        "published": '1833',
        "publisher": 'Durrie & Peck, New Haven',
        "status": "REVIEW",
        "why": 'Out of copyright by age: published 1833.',
        "restrictions": 'Not yet checked against a primary source. Our understanding is that nothing restricts printing; confirm before a commercial run.',
    },
    "bible-en-leeser-1853": {
        "title": 'The Twenty-Four Books of the Holy Scriptures',
        "abbrev": 'LEE',
        "translator": 'Isaac Leeser',
        "published": '1853',
        "publisher": 'Philadelphia',
        "status": "REVIEW",
        "why": 'Out of copyright by age: published 1853; translator died 1868.',
        "restrictions": 'Not yet checked against a primary source. Our understanding is that nothing restricts printing; confirm before a commercial run.',
    },
    "bible-en-brenton-lxx-1851": {
        "title": 'The Septuagint Version of the Old Testament, with an English Translation',
        "abbrev": 'LXXE',
        "translator": 'Sir Lancelot Charles Lee Brenton',
        "published": '1851',
        "publisher": 'Samuel Bagster & Sons, London',
        "status": "REVIEW",
        "why": 'Out of copyright by age: published 1851; translator died 1862.',
        "restrictions": 'Not yet checked against a primary source. Our understanding is that nothing restricts printing; confirm before a commercial run.',
    },
    "bible-diaglott-il-1864": {
        "title": 'The Emphatic Diaglott (interlinear)',
        "abbrev": 'DIAGIL',
        "translator": 'Benjamin Wilson',
        "published": '1864',
        "publisher": 'Fowler & Wells, New York',
        "status": "REVIEW",
        "why": 'Out of copyright by age: published 1864.',
        "restrictions": 'Not yet checked against a primary source. Our understanding is that nothing restricts printing; confirm before a commercial run.',
    },
    "bible-en-ylt-1898": {
        "title": "Young's Literal Translation of the Holy Bible",
        "abbrev": 'YLT',
        "translator": 'Robert Young',
        "published": '1862; third edition 1898',
        "publisher": 'G. A. Young & Co., Edinburgh',
        "status": "REVIEW",
        "why": 'Out of copyright by age: published 1898; translator died 1888.',
        "restrictions": 'Not yet checked against a primary source. Our understanding is that nothing restricts printing; confirm before a commercial run.',
    },
    "bible-dra-1899": {
        "title": 'The Holy Bible, Douay-Rheims Version (Challoner revision)',
        "abbrev": 'DRA',
        "translator": 'The English College at Douai and Rheims; revised by Richard Challoner',
        "published": '1582–1610; Challoner 1749–52; this text the 1899 American edition',
        "publisher": 'John Murphy Company, Baltimore',
        "status": "REVIEW",
        "why": 'Out of copyright by age: published 1899.',
        "restrictions": 'Not yet checked against a primary source. Our understanding is that nothing restricts printing; confirm before a commercial run.',
    },
    "bible-jps1917-1917": {
        "title": 'The Holy Scriptures According to the Masoretic Text',
        "abbrev": 'JPS1917',
        "translator": 'Board of editors under Max L. Margolis',
        "published": '1917',
        "publisher": 'Jewish Publication Society of America, Philadelphia',
        "status": "REVIEW",
        "why": 'Out of copyright by age: published 1917; editor-in-chief died 1932.',
        "restrictions": 'Not yet checked against a primary source. Our understanding is that nothing restricts printing; confirm before a commercial run.',
    },
    "bible-bsb": {
        "title": 'Berean Standard Bible',
        "abbrev": 'BSB',
        "translator": 'Berean Bible translation committee',
        "published": '2016–2022',
        "publisher": 'Bible Hub',
        "status": "REVIEW",
        "why": 'Dedicated to the public domain by its publisher on 30 April 2023.',
        "restrictions": "⚠ The corpus calls this the Berean STUDY Bible, the edition's earlier name; confirm the ingested text is the one covered by the 2023 dedication. Confirm before a commercial run.",
    },
    "bible-fbv": {
        "title": 'Free Bible Version',
        "abbrev": 'FBV',
        "translator": 'Jonathan Gallagher',
        "published": '2018',
        "publisher": 'Free Bible Ministry',
        "status": "REVIEW",
        "why": 'The corpus records this as public domain.',
        "restrictions": '⚠ LICENCE IN DOUBT. The Free Bible Version is, to our knowledge, published under CC BY-SA 4.0, not dedicated to the public domain. That licence allows commercial printing but REQUIRES attribution and share-alike. Confirm at freebibleversion.org and correct the corpus licence.',
    },
    "bible-lsv": {
        "title": 'Literal Standard Version',
        "abbrev": 'LSV',
        "translator": 'Covenant Press translation team',
        "published": '2020',
        "publisher": 'Covenant Press',
        "status": "REVIEW",
        "why": 'The corpus records this as public domain.',
        "restrictions": '⚠ LICENCE IN DOUBT. The LSV is, to our knowledge, published under CC BY-SA, not dedicated to the public domain. Confirm at lsvbible.com and correct the corpus licence.',
    },
    "bible-rvr09-1909": {
        "title": 'La Santa Biblia, Reina-Valera 1909',
        "abbrev": 'RVR09',
        "translator": 'Casiodoro de Reina; revised by Cipriano de Valera and later revisers',
        "published": '1569; this text the 1909 revision',
        "publisher": 'Sociedades Bíblicas',
        "status": "REVIEW",
        "why": 'Out of copyright by age: published 1909.',
        "restrictions": 'Not yet checked against a primary source. Our understanding is that nothing restricts printing; confirm before a commercial run.',
    },
    "bible-fi-1548": {
        "title": 'Se Wsi Testamenti',
        "abbrev": 'AGR1548',
        "translator": 'Mikael Agricola',
        "published": '1548',
        "publisher": 'Amund Laurentsson, Stockholm',
        "status": "REVIEW",
        "why": 'Out of copyright by age: published 1548.',
        "restrictions": '⚠ The text is free; the transcription comes from the Kotus VKS corpus (Institute for the Languages of Finland), whose own licence terms apply to the digital edition. Confirm before a commercial run.',
    },
    "bible-fi-1642": {
        "title": 'Biblia, Se on: Coco Pyhä Ramattu Suomexi',
        "abbrev": 'FB1642',
        "translator": 'Translation committee under Aeschillus Petraeus',
        "published": '1642',
        "publisher": 'Henrik Keyser, Stockholm',
        "status": "REVIEW",
        "why": 'Out of copyright by age: published 1642.',
        "restrictions": '⚠ The text is free; the transcription comes from the Kotus VKS corpus (Institute for the Languages of Finland), whose own licence terms apply to the digital edition. Confirm before a commercial run.',
    },
    "bible-fr-darby": {
        "title": 'La Sainte Bible (Darby)',
        "abbrev": 'DBY',
        "translator": 'John Nelson Darby',
        "published": '1859 (NT), 1885 (whole Bible)',
        "publisher": 'Pau and Vevey',
        "status": "REVIEW",
        "why": 'Out of copyright by age: published 1885; translator died 1882.',
        "restrictions": 'Not yet checked against a primary source. Our understanding is that nothing restricts printing; confirm before a commercial run.',
    },
    "bible-fr-segond-1910": {
        "title": 'La Sainte Bible (Louis Segond 1910)',
        "abbrev": 'LSG',
        "translator": 'Louis Segond',
        "published": '1880; this text the 1910 revision',
        "publisher": 'Société biblique britannique et étrangère',
        "status": "REVIEW",
        "why": 'Out of copyright by age: published 1910; translator died 1885.',
        "restrictions": 'Not yet checked against a primary source. Our understanding is that nothing restricts printing; confirm before a commercial run.',
    },
    "bible-it-diodati": {
        "title": 'La Sacra Bibbia (Diodati)',
        "abbrev": 'DIO',
        "translator": 'Giovanni Diodati',
        "published": '1607; second edition 1641',
        "publisher": 'Geneva',
        "status": "REVIEW",
        "why": 'Out of copyright by age: seventeenth century; translator died 1649.',
        "restrictions": 'Not yet checked against a primary source. Our understanding is that nothing restricts printing; confirm before a commercial run.',
    },
    "bible-it-diodati-1885": {
        "title": 'La Sacra Bibbia (Diodati, 1885 edition)',
        "abbrev": 'DIO1885',
        "translator": 'Giovanni Diodati',
        "published": '1885',
        "publisher": 'Società Biblica Britannica e Forestiera',
        "status": "REVIEW",
        "why": 'Out of copyright by age: published 1885.',
        "restrictions": 'Not yet checked against a primary source. Our understanding is that nothing restricts printing; confirm before a commercial run.',
    },
    "bible-it-riveduta-1927": {
        "title": 'La Sacra Bibbia, Versione Riveduta',
        "abbrev": 'RIV1927',
        "translator": 'Revision committee under Giovanni Luzzi',
        "published": '1927',
        "publisher": 'Società Biblica Britannica e Forestiera',
        "status": "REVIEW",
        "why": 'Published 1927; the principal reviser, Giovanni Luzzi, died in 1948, so a life-plus-70 term ran out at the end of 2018.',
        "restrictions": 'Not yet checked against a primary source. Our understanding is that nothing restricts printing; confirm before a commercial run.',
    },
    "bible-la-clementina-1598": {
        "title": 'Biblia Sacra Vulgatae Editionis (Clementina)',
        "abbrev": 'VULC',
        "translator": 'Jerome; edition authorised by Clement VIII',
        "published": '1592; this text the 1598 edition',
        "publisher": 'Typographia Apostolica Vaticana, Rome',
        "status": "REVIEW",
        "why": 'Out of copyright by age: published 1598.',
        "restrictions": 'Not yet checked against a primary source. Our understanding is that nothing restricts printing; confirm before a commercial run.',
    },
    "bible-la-vulgate": {
        "title": 'Biblia Sacra Vulgatae Editionis (Clementina)',
        "abbrev": 'VUL',
        "translator": 'Jerome; edition authorised by Clement VIII',
        "published": 'c. 405; Clementine edition 1592',
        "publisher": 'Typographia Apostolica Vaticana, Rome',
        "status": "REVIEW",
        "why": 'Out of copyright by age: the Clementine text of 1592.',
        "restrictions": '⚠ The corpus source names the Clementine edition. Had it been the Stuttgart critical edition (Deutsche Bibelgesellschaft) the text would be under copyright, so confirm which one this is. Confirm before a commercial run.',
    },
    "bible-sv-1917": {
        "title": 'Bibeln eller Den Heliga Skrift (1917 års kyrkobibel)',
        "abbrev": 'SV1917',
        "translator": 'Bibelkommissionen',
        "published": '1917',
        "publisher": 'Stockholm; authorised by Gustaf V',
        "status": "REVIEW",
        "why": 'Out of copyright by age: published 1917.',
        "restrictions": 'Not yet checked against a primary source. Our understanding is that nothing restricts printing; confirm before a commercial run.',
    },
    "bible-ru-synodal": {
        "title": 'Библия. Синодальный перевод',
        "abbrev": 'SYN',
        "translator": 'Translation under the Most Holy Synod of the Russian Orthodox Church',
        "published": '1876',
        "publisher": 'Synodal Press, St Petersburg',
        "status": "REVIEW",
        "why": 'Out of copyright by age: published 1876.',
        "restrictions": 'Not yet checked against a primary source. Our understanding is that nothing restricts printing; confirm before a commercial run.',
    },
    "bible-grc-tr": {
        "title": 'Textus Receptus (Stephanus 1550 / Scrivener 1894)',
        "abbrev": 'TR',
        "translator": 'Edited by Robert Estienne (1550) and F. H. A. Scrivener (1894)',
        "published": '1550; 1894',
        "publisher": 'Paris; Cambridge',
        "status": "REVIEW",
        "why": 'Out of copyright by age: published 1550 and 1894; Scrivener died 1891.',
        "restrictions": 'Not yet checked against a primary source. Our understanding is that nothing restricts printing; confirm before a commercial run.',
    },
    "bible-byz1904-1904": {
        "title": 'Ἡ Καινὴ Διαθήκη (Patriarchal Text)',
        "abbrev": 'BYZ1904',
        "translator": 'Edited by Vasileios Antoniades for the Ecumenical Patriarchate',
        "published": '1904; corrected 1912',
        "publisher": 'Patriarchal Press, Constantinople',
        "status": "REVIEW",
        "why": 'Out of copyright by age: published 1904; editor died 1932.',
        "restrictions": '⚠ The corpus source is api.bible, whose terms of use govern the digital copy even where the text is free. Confirm before a commercial run.',
    },
    "bible-grc-tischendorf-1872": {
        "title": 'Novum Testamentum Graece, editio octava critica maior',
        "abbrev": 'TISCH',
        "translator": 'Constantin von Tischendorf',
        "published": '1869–1872',
        "publisher": 'Giesecke & Devrient, Leipzig',
        "status": "REVIEW",
        "why": 'Out of copyright by age: published 1872; editor died 1874.',
        "restrictions": 'Not yet checked against a primary source. Our understanding is that nothing restricts printing; confirm before a commercial run.',
    },
    "bible-lxx-1851": {
        "title": "The Septuagint (Greek text of Brenton's edition)",
        "abbrev": 'LXX',
        "translator": 'Greek text after the Sixtine edition, as printed by Brenton',
        "published": '1851',
        "publisher": 'Samuel Bagster & Sons, London',
        "status": "REVIEW",
        "why": 'Out of copyright by age: published 1851.',
        "restrictions": 'Not yet checked against a primary source. Our understanding is that nothing restricts printing; confirm before a commercial run.',
    },
    "bible-he-wlc": {
        "title": 'Westminster Leningrad Codex',
        "abbrev": 'WLC',
        "translator": 'Text of the Leningrad Codex (c. 1008); electronic text by the J. Alan Groves Center',
        "published": 'c. 1008; electronic edition ongoing',
        "publisher": 'J. Alan Groves Center for Advanced Biblical Research',
        "status": "REVIEW",
        "why": 'The Groves Center releases the WLC text into the public domain.',
        "restrictions": 'Not yet checked against a primary source. Our understanding is that nothing restricts printing; confirm before a commercial run.',
    },
    "bible-he-delitzsch": {
        "title": "תנ״ך והברית החדשה (Masoretic OT with Delitzsch's Hebrew NT)",
        "abbrev": 'HEBM',
        "translator": 'Franz Delitzsch (New Testament)',
        "published": '1877 (NT)',
        "publisher": 'British and Foreign Bible Society',
        "status": "REVIEW",
        "why": 'Out of copyright by age: Delitzsch died 1890.',
        "restrictions": "⚠ getbible.net calls this file 'modernhebrew'; confirm the New Testament is Delitzsch's and not a modern Hebrew version under copyright. Confirm before a commercial run.",
    },
    "bible-ar-vandyck": {
        "title": 'الكتاب المقدس (Smith & Van Dyck)',
        "abbrev": 'SVD',
        "translator": 'Eli Smith, Cornelius Van Dyck, Butrus al-Bustani, Nasif al-Yaziji',
        "published": '1865',
        "publisher": 'American Mission Press, Beirut',
        "status": "REVIEW",
        "why": 'Out of copyright by age: published 1865; Van Dyck died 1895.',
        "restrictions": '⚠ Do not print from this edition. The file has no traceable source: the repository it came from replaced it, and the best-known electronic Van Dyck transcription (arabicbible.com) claims exclusive copyright. Print bible-ar-vd-ebible instead.',
    },
        "bible-ar-vd-ebible": {
        "title": 'الكتاب المقدس — فان دايك (eBible)',
        "abbrev": 'SVD-E',
        "translator": 'Eli Smith, Cornelius Van Dyck, Butrus al-Bustani, Nasif al-Yaziji (Syrian Mission)',
        "published": '1865 (eBible.org file dated 2020-08-03)',
        "publisher": 'American Mission Press, Beirut; electronic text by eBible.org, contributor American Bible Society',
        "status": "REVIEW",
        "rtl": True,
        "why": 'The 1865 translation is out of copyright by age (Van Dyck died 1895). '
               'eBible.org publishes this electronic text as Public Domain, crediting the '
               'Syrian Mission as translator and the American Bible Society as contributor.',
        "restrictions": '⚠ ONE CHECK OUTSTANDING before VERIFIED: compare a sample (Genesis 1, Psalm 23, John 3, Romans 8) '
                        'with a dated 1865 scan, to confirm the vowelled text is the 1865 translation and not a later revision. '
                        'Source: https://ebible.org/details.php?id=arb-vd. Right-to-left layout: build_pdf.py (use --fonts fonts/scheherazade --font-family ScheherazadeNew).',
    },
    "bible-ar-onav": {
        "title": 'الترجمة العربية الجديدة المفتوحة (Open New Arabic Version)',
        "abbrev": 'ONAV',
        "translator": 'Biblica, Inc.',
        "published": '1988, 1997, 2012',
        "publisher": 'Biblica, Inc.',
        "status": "REVIEW",
        "why": 'Licensed CC BY-SA 4.0 by Biblica (https://ebible.org/arbnav/copr.htm). Printing is permitted with credit.',
        "rtl": True,
        "licence": {
            "name": "Creative Commons Attribution-ShareAlike 4.0 International (CC BY-SA 4.0)",
            "url": "https://creativecommons.org/licenses/by-sa/4.0/",
            "holder": "Biblica, Inc. (c) 1988, 1997, 2012",
            "source": "https://ebible.org/arbnav/copr.htm",
            "credit": "The original work by Biblica, Inc. is available for free at www.biblica.com and open.bible",
        },
        "restrictions": '⚠ CONDITIONS before VERIFIED. (1) The colophon must carry: "The original work by Biblica, Inc. is available for free at '
                        'www.biblica.com and open.bible", the licence name and a link. (2) A reordered edition (event or writing order) is a '
                        'derivative: it must say the text was rearranged, must NOT use the Biblica trademark, and is itself CC BY-SA 4.0. '
                        '(3) That reading of "derivative" is ours; Biblica should confirm if the printer asks. '
                        'Acts 15:25-26 is one bridged verse in the source. Right-to-left layout: build_pdf.py (use --fonts fonts/scheherazade --font-family ScheherazadeNew).',
    },
"bible-zh-cuv1919": {
        "title": "新舊約全書 (國語和合譯本, 神版)",
        "abbrev": "CUV1919",
        "translator": "The Union Version committee: Calvin W. Mateer, Chauncey Goodrich, "
                      "Frederick W. Baller, Spencer Lewis, George S. Owen and others, "
                      "with Chinese assistants",
        "published": "New Testament 1906; whole Bible 1919",
        "publisher": "The Chinese Bible Union (British and Foreign Bible Society, "
                     "American Bible Society and the National Bible Society of Scotland), Shanghai",
        "status": "REVIEW",
        "why": "The 1919 text, published more than a century ago and by a committee "
               "whose members died long ago. Chinese Wikisource tags every book of its "
               "transcription as public domain (pd/1923), and Wikipedia lists the "
               "CUV as public domain. This edition is the 1919 text in 1919 spelling and "
               "punctuation — NOT the 1988 New Punctuation text, which the corpus's "
               "bible-zh-cuv appears to be and whose status is unconfirmed.",
        "restrictions": "⚠ TWO CHECKS OUTSTANDING before this is VERIFIED. (1) Compare a "
                        "sample (say Genesis 1, Psalm 23, John 3, Romans 8) with a dated "
                        "1919 scan and record the result. (2) The electronic text is "
                        "Chinese Wikisource's transcription, which its contributors "
                        "offer under CC BY-SA 4.0: credit them in the printed edition's "
                        "front matter and in RIGHTS.txt, and decide whether the "
                        "share-alike condition matters for the interior file. The "
                        "transcription also differs from a facsimile in small ways its "
                        "editors document (orthography normalised to the 1919 usage, "
                        "one rare character replaced by plain ones, the honorific gap "
                        "before 神 removed here).",
    },
    "bible-zh-cuv": {
        "title": '和合本 (Chinese Union Version)',
        "abbrev": 'CUV',
        "translator": 'Union Version committee of the Protestant missions in China',
        "published": '1919',
        "publisher": 'British and Foreign Bible Society and American Bible Society, Shanghai',
        "status": "REVIEW",
        "why": 'Out of copyright by age: published 1919.',
        "restrictions": '⚠ The 1919 text is free. Later editions with new punctuation (1988 and after) are under copyright; confirm which one the source carries. Confirm before a commercial run.',
    },
    "bible-ja-freedom-2026": {
        "title": 'フリーダム・バイブル (Freedom Bible)',
        "abbrev": 'JFB',
        "translator": 'Not established',
        "published": '2026',
        "publisher": 'Distributed by eBible.org (jpnm)',
        "status": "REVIEW",
        "why": 'The corpus records this as public domain, from eBible.org.',
        "restrictions": "⚠ NOT ESTABLISHED. A 2026 translation cannot be free by age; it is free only if its publisher says so. Read the eBible.org details page for 'jpnm' and record the dedication or licence here.",
    },
    "bible-hlt-olcim": {
        "title": 'Baibal Olcim (Matu Chin)',
        "abbrev": 'OLCIM',
        "translator": 'Not established',
        "published": 'Not established',
        "publisher": 'Distributed by eBible.org (hlt)',
        "status": "REVIEW",
        "why": 'The corpus records this as public domain, from eBible.org.',
        "restrictions": "⚠ NOT ESTABLISHED. Read the eBible.org details page for 'hlt' and record the translator, date and the dedication or licence here.",
    },
}

DEFAULT_SPEC = {
    "paper": "40 gsm Thinprint India paper (Pretore house stock)",
    "printing": "Digital, monochrome",
    "binding": "Sewn",
    "cover": "To be confirmed — printed paper with lamination, or a "
             "leather-grain material (Skivertex / Quinel Nubuk / Silktouch / "
             "Novalite)",
    "bleed": "None. Text-only interior, no element runs to the trim.",
    "colour": "Black only. No spot colours, no ICC-dependent artwork.",
}


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 16), b""):
            h.update(chunk)
    return h.hexdigest()


def rights_document(edition_id, ed, fonts_family, font_licences,
                    latin_family=None, ordering="canonical"):
    lic = ed.get("licence")
    if lic:
        head = [
            "HOW THIS TEXT IS LICENSED",
            "-" * 25,
            "This edition is NOT in the public domain. It is under an open licence",
            "that permits copying, adapting and printing, on conditions. There is",
            "no per-copy royalty; there ARE conditions, set out here so they can",
            "be checked against the source.",
            "",
            f"  Rights holder  {lic['holder']}",
            f"  Licence        {lic['name']}",
            f"  Licence text   {lic['url']}",
            f"  Stated at      {lic['source']}",
            "",
            "  Credit, which this book prints on its rights page:",
            f"    \"{lic['credit']}\"",
            "",
            "  Conditions as we read them (not legal advice):",
            "    1. Credit Biblica as above and give the licence name and link.",
            "    2. The text unmodified may carry the Biblica(R) trademark.",
            "    3. A derivative must not use the Biblica(R) trademark, must say",
            "       what was changed, and must itself be offered under CC BY-SA 4.0.",
            "    4. " + ("The books stand in their canonical order." if ordering == "canonical"
                         else "This book rearranges the order of the books, and says so.")
            + " The verse text is as published, but the source's section",
            "       headings and footnotes are not reproduced (and Acts 15:25-26 is one",
            "       verse, as in the source), so we treat the book as an adaptation:",
            "       the Biblica(R) name does not appear in its title, and it carries the",
            "       same CC BY-SA 4.0 licence.",
            "",
            "  This book is offered by Common Root under the same licence.",
            "",
        ]
    else:
        head = [
            "WHY THERE IS NO COPYRIGHT HOLDER",
            "-" * 32,
            "This edition is in the public domain. There is no rights holder to",
            "name, no licence to grant and no per-copy royalty to account for.",
            "What follows is the evidence for that, so it can be checked rather",
            "than taken on trust.",
            "",
        ]
    r = [
        f"RIGHTS AND PROVENANCE",
        f"{'=' * 21}",
        "",
        f"Prepared by Common Root (common-root.org) on {date.today()}.",
        "",
    ] + head + [
        "THE TEXT",
        "-" * 8,
        f"  Title        {ed['title']}",
        f"  Abbreviation {ed['abbrev']}",
        f"  Corpus id    {edition_id}",
        f"  Translator   {ed['translator']}",
        f"  Published    {ed['published']}",
        f"  Publisher    {ed['publisher']}",
        f"  Status       {ed['status']}",
        "",
        "  Why it is free:",
        f"    {ed['why']}",
        "",
        "  Restrictions:",
        f"    {ed['restrictions']}",
        "",
        "THE TYPOGRAPHY",
        "-" * 14,
        f"  Set in {fonts_family}"
        + (f" (script) and {latin_family} (numbers and English front pages)"
           if latin_family else "") + ", embedded and subset in the interior PDF.",
        "  The typefaces are licensed for embedding and commercial printing;",
        "  the full licence texts accompany this package:",
    ]
    for f in font_licences:
        r.append(f"    fonts/{f}")
    r += [
        "",
        "  No system font is used anywhere in the file. Every glyph in the",
        "  PDF comes from an embedded, licensed face.",
        "",
        "THE ARRANGEMENT",
        "-" * 15,
        ("  The ORDER of the books is editorial work by Common Root and is not"
         if not lic else
         "  Any rearrangement of the books is editorial work by Common Root,"),
        ("  part of the public-domain text. We assert no claim over the"
         if not lic else
         "  offered under the same licence as the text. We assert no claim over the"),
        "  scripture itself; the selection and sequence are ours, and we grant",
        "  the printer whatever permission is needed to print this edition.",
        "",
    ]
    return "\n".join(r)


def readme(edition_id, ed, spec, stats):
    return "\n".join([
        "COMMON ROOT — PRINT PACKAGE",
        "=" * 27,
        "",
        f"  {ed['title']} — {spec['ordering']} order",
        f"  Generated {date.today()} from common-root.org",
        "",
        "WHAT IS IN HERE",
        "-" * 15,
        "  interior.pdf    The book. Final trim size, single pages in reading",
        "                  order, fonts embedded and subset, black only.",
        "  RIGHTS.txt      Who wrote it, when, and why it is free to print.",
        "  SPEC.json       The manufacturing spec, machine-readable.",
        "  MANIFEST.json   Checksums and provenance for every file here.",
        "  fonts/          Licence texts for the typefaces used.",
        "",
        "THE SPEC AT A GLANCE",
        "-" * 20,
        f"  Trim          {spec['trim_mm']}",
        f"  Extent        {stats['pages']} pages",
        f"  Spine         ≈{stats['spine_mm']} mm on 40 gsm (estimate — please",
        "                confirm against your own bulking figure)",
        f"  Setting       {spec['columns']} column(s), {spec['measure_mm']} mm measure",
        f"  Running head  {spec['running_head']}",
        f"  Book titles   {spec['book_titles']}",
        f"  Book close    {spec['book_end']}",
        f"  Margins       inner {spec['inner_mm']} / outer {spec['outer_mm']} /",
        f"                head {spec['top_mm']} / foot {spec['bottom_mm']} mm",
        f"  Paper         {spec['paper']}",
        f"  Printing      {spec['printing']}",
        f"  Binding       {spec['binding']}",
        f"  Cover         {spec['cover']}",
        f"  Bleed         {spec['bleed']}",
        f"  Colour        {spec['colour']}",
    ] + ([""] + ["  " + spec["colour_warning"]] if "colour_warning" in spec
         else []) + [
        "",
        "NOTES",
        "-" * 5,
        "  The margins are set in the file and cannot be changed without",
        "  re-typesetting, which changes the extent. If a different margin is",
        "  wanted, ask us and we will regenerate rather than scale.",
        "",
        "  Questions to christa.claw@proton.me — or reply to whoever sent this.",
        "",
    ])


def main():
    ap = argparse.ArgumentParser(
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--translation", required=True)
    ap.add_argument("--ordering", default="chronological")
    ap.add_argument("--canon", default="66", choices=("66", "full"))
    ap.add_argument("--trim", default="standard")
    ap.add_argument("--outer", type=float)
    ap.add_argument("--fonts")
    ap.add_argument("--running-head", default="split",
                    choices=("none", "book", "book-chapter", "split"))
    ap.add_argument("--book-titles", default="long",
                    choices=("long", "short"))
    ap.add_argument("--no-book-end", action="store_true")
    ap.add_argument("--accent", default="black",
                    choices=("black", "rubric", "indigo", "sepia", "forest"))
    ap.add_argument("--font-family", default="CrimsonPro")
    ap.add_argument("--title", default="The Holy Bible")
    ap.add_argument("--out", required=True, help="the .zip to write")
    ap.add_argument("--self-test", action="store_true",
                    help="use synthetic text so the archive can be built "
                         "without BaseX")
    ap.add_argument("--allow-review", action="store_true",
                    help="build even when the edition's rights are in REVIEW")
    a = ap.parse_args()

    ed = EDITIONS.get(a.translation)
    if not ed:
        raise SystemExit(
            f"No rights entry for '{a.translation}'. Add one to EDITIONS in "
            f"this file — a package without provenance is exactly what the "
            f"printer asked us not to send. Known: {sorted(EDITIONS)}")
    if ed["status"] == "REVIEW" and not a.allow_review:
        raise SystemExit(
            f"'{a.translation}' rights are in REVIEW:\n  {ed['restrictions']}\n"
            f"Resolve it, or pass --allow-review to build a package clearly "
            f"marked as unresolved (do NOT send that one to a printer).")

    workdir = os.path.dirname(os.path.abspath(a.out)) or "."
    os.makedirs(workdir, exist_ok=True)
    pdf = os.path.join(workdir, "interior.pdf")

    rtl = bool(ed.get("rtl"))
    latin_dir = None
    if rtl:
        if not a.fonts:
            raise SystemExit("A right-to-left edition needs --fonts (the script's face).")
        latin_dir = os.path.join(os.path.dirname(os.path.abspath(a.fonts)), "crimson")
    cmd = [sys.executable, os.path.join(HERE, "build_pdf.py"),
           "--ordering", a.ordering, "--canon", a.canon, "--trim", a.trim,
           "--running-head", a.running_head, "--accent", a.accent,
           "--book-titles", a.book_titles,
           "--title", a.title, "--out", pdf]
    if a.no_book_end:
        cmd.append("--no-book-end")
    if a.self_test:
        cmd += ["--self-test", "--self-test-chapters", "12"]
    else:
        cmd += ["--translation", a.translation]
    if a.fonts:
        cmd += ["--fonts", a.fonts, "--font-family", a.font_family]
    if a.outer is not None:
        cmd += ["--outer", str(a.outer)]

    print("  building interior …")
    proc = subprocess.run(cmd, capture_output=True, text=True)
    sys.stderr.write(proc.stderr)
    if proc.returncode != 0:
        raise SystemExit("interior build failed")
    out = proc.stdout
    print(out)

    def grab(token, cast=float):
        import re
        m = re.search(token, out)
        return cast(m.group(1)) if m else None

    stats = {
        "pages": grab(r"(\d+) pages", int) or 0,
        "spine_mm": grab(r"spine ≈ ?(\d+) mm", int) or 0,
    }
    spec = dict(DEFAULT_SPEC)
    if a.accent != "black":
        sys.path.insert(0, HERE)
        from build_pdf import ACCENTS
        c, m, y, k, desc = ACCENTS[a.accent]
        spec["printing"] = ("Digital, COLOUR — the apparatus carries an "
                            "accent colour; scripture is black")
        spec["colour"] = (
            f"Two inks in effect. Body text 100K. Apparatus (running heads, "
            f"book titles, chapter numerals) in {desc}, "
            f"CMYK {c*100:.0f}/{m*100:.0f}/{y*100:.0f}/{k*100:.0f} — supplied "
            f"as CMYK in the file, so no RGB conversion is needed. Total ink "
            f"coverage {((c+m+y+k)*100):.0f}%.")
        spec["colour_warning"] = (
            "⚠ This is NOT the monochrome job quoted on 2026-09-22. Please "
            "requote: colour digital printing, and confirm it is available at "
            "this run length.")
    if rtl:
        spec["binding"] = ("Sewn, bound on the RIGHT-hand edge: a right-to-left book. "
                           "Page 1 is the left-hand page of the first spread; the "
                           "inner (gutter) margin is on the RIGHT of odd pages and "
                           "the LEFT of even pages")
        spec["reading_direction"] = "Right to left (Arabic)"
        a.book_titles = "short"
    spec.update({
        "edition": a.translation,
        "ordering": a.ordering,
        "running_head": {
            "none": "None — no book or chapter at the head of the page",
            "book": "Book name only",
            "book-chapter": "Book name and the chapters on that page "
                            "(e.g. JEREMIAH 41–42), verso left, recto right",
            "split": "Split across the spread — verso names the\n"
                     "                book, recto the chapters, and neither\n"
                     "                on a page that opens a book",
        }[a.running_head],
        "book_titles": (
            "Traditional English title, set small under\n"
            "                the book rule"
            if a.book_titles == "long" else "Book name only"),
        "book_end": ("Each book opens with a rule across the page; a "
                     "closing rule follows the last book only"
                     if not a.no_book_end else "None"),
        "canon": "66 books" if a.canon == "66"
                 else "every book this edition carries",
        "trim_mm": (grab(r"· (\d+) × \d+ mm", int),
                    grab(r"· \d+ × (\d+) mm", int)),
        "columns": grab(r"× (\d+)\s*$", int) or grab(r"mm × (\d+)", int) or 2,
        "measure_mm": grab(r"column (\d+) mm", int),
        "inner_mm": grab(r"inner (\d+)", int),
        "outer_mm": grab(r"outer (\d+)", int),
        "top_mm": grab(r"head (\d+)", int),
        "bottom_mm": grab(r"foot (\d+)", int),
        "pages": stats["pages"],
        "spine_mm_estimate": stats["spine_mm"],
    })
    spec["trim_mm"] = f"{spec['trim_mm'][0]} × {spec['trim_mm'][1]} mm"

    import re
    licence_files = {}      # name inside the zip -> path on disk
    for d in [x for x in (a.fonts, latin_dir) if x]:
        for f in sorted(os.listdir(d)):
            if re.search(r"(ofl|licen[cs]e|copying)", f, re.I):
                name = f if not latin_dir else (
                    os.path.basename(os.path.abspath(d)) + "-" + f)
                licence_files[name] = os.path.join(d, f)
    licences = sorted(licence_files)

    rights = rights_document(a.translation, ed, a.font_family, licences,
                             latin_family="CrimsonPro" if rtl else None,
                             ordering=a.ordering)
    manifest = {
        "generator": "common-root build_package.py",
        "generated": str(date.today()),
        "edition": a.translation,
        "ordering": a.ordering,
        "canon": a.canon,
        "rights_status": ed["status"],
        "files": {},
    }

    with zipfile.ZipFile(a.out, "w", zipfile.ZIP_DEFLATED) as z:
        z.write(pdf, "interior.pdf")
        manifest["files"]["interior.pdf"] = {
            "sha256": sha256(pdf), "bytes": os.path.getsize(pdf)}
        for name, text in (("RIGHTS.txt", rights),
                           ("README.txt", readme(a.translation, ed, spec, stats))):
            z.writestr(name, text)
            manifest["files"][name] = {
                "sha256": hashlib.sha256(text.encode()).hexdigest(),
                "bytes": len(text.encode())}
        sj = json.dumps(spec, indent=2, ensure_ascii=False)
        z.writestr("SPEC.json", sj)
        manifest["files"]["SPEC.json"] = {
            "sha256": hashlib.sha256(sj.encode()).hexdigest(),
            "bytes": len(sj.encode())}
        for f in licences:
            z.write(licence_files[f], f"fonts/{f}")
            manifest["files"][f"fonts/{f}"] = {
                "sha256": sha256(licence_files[f]),
                "bytes": os.path.getsize(licence_files[f])}
        z.writestr("MANIFEST.json", json.dumps(manifest, indent=2))

    os.remove(pdf)
    size = os.path.getsize(a.out)
    print(f"  {a.out}  ({size/1024:.0f} kB)")
    for n in sorted(manifest["files"]):
        print(f"    {n}")
    print("    MANIFEST.json")
    if ed["status"] == "REVIEW":
        print("\n  ⚠ RIGHTS IN REVIEW — do not send this to a printer.")


if __name__ == "__main__":
    main()
