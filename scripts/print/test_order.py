#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Christa Claw
"""Tests for print orders: the file the order page writes and build_package.py --order reads.
python3 -m unittest scripts/print/test_order.py"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import build_package as bp  # noqa: E402


def order(edition="WEB", **settings):
    return {"format": "common-root-print-order", "version": 1, "created": "2026-10-10T00:00:00Z",
            "edition": edition, "settings": settings,
            "estimate": {"pages": 1024, "note": "ignored"}}


def pairs(args):
    """The options as {option: value}, for the ones that take a value."""
    return {a: args[i + 1] for i, a in enumerate(args) if a.startswith("--") and i + 1 < len(args)
            and not args[i + 1].startswith("--")}


class OrderToArgs(unittest.TestCase):
    def test_a_plain_order_becomes_the_options_a_person_would_type(self):
        a = pairs(bp.order_to_args(order(ordering="chronological", canon="66", trim="standard",
                                         body=8.8, outer=12, head="split", titles="long",
                                         accent="black", xrefs="none", mode="CHAPTERS_VERSES")))
        self.assertEqual(a["--translation"], "bible-web")
        self.assertEqual((a["--ordering"], a["--canon"], a["--trim"]), ("chronological", "66", "standard"))
        self.assertEqual((a["--running-head"], a["--book-titles"], a["--accent"]), ("split", "long", "black"))
        self.assertEqual((a["--body-size"], a["--outer"]), ("8.8", "12"))
        self.assertEqual(a["--font-family"], "CrimsonPro")
        self.assertTrue(a["--fonts"].endswith(os.path.join("fonts", "crimson")))
        self.assertEqual(a["--out"], os.path.join("out", "print", "web-chronological-package.zip"))
        self.assertNotIn("--xrefs", a)

    def test_margin_cross_references_take_the_wide_trim_and_no_outer(self):
        a = pairs(bp.order_to_args(order(trim="standard", outer=12, xrefs="margin", xrefTop=2)))
        self.assertEqual((a["--trim"], a["--xrefs"], a["--xref-top"]), ("notes", "margin", "2"))
        self.assertNotIn("--outer", a)
        self.assertTrue(a["--out"].endswith("web-chronological-xrefs-margin-package.zip"))

    def test_inline_cross_references_keep_the_trim(self):
        a = pairs(bp.order_to_args(order("KJV", trim="large", xrefs="inline")))
        self.assertEqual((a["--translation"], a["--trim"], a["--xrefs"], a["--xref-top"]),
                         ("bible-kjv-1611", "large", "inline", "3"))

    def test_an_arabic_edition_is_set_in_its_own_typeface(self):
        a = pairs(bp.order_to_args(order("ONAV", ordering="canonical")))
        self.assertEqual(a["--translation"], "bible-ar-onav")
        self.assertEqual(a["--font-family"], "ScheherazadeNew")

    def test_what_the_builder_cannot_build_yet_is_refused_with_the_reason(self):
        for settings, reason in (
                ({"mode": "ORIGINAL"}, "verse-numbered"),
                ({"canon": "full", "ordering": "chronological"}, "apocrypha"),
                ({"trim": "notes"}, "margin"),
                ({"body": 14}, "7.2"),
                ({"outer": 3}, "8 to 40"),
                ({"xrefs": "inline", "xrefTop": 9}, "1 to 5"),
                ({"colour": "red"}, "does not know")):
            with self.assertRaises(SystemExit) as e:
                bp.order_to_args(order(**settings))
            self.assertIn(reason, str(e.exception), settings)

    def test_an_edition_without_rights_or_a_foreign_file_is_refused(self):
        for bad in (order("NIV"), order("bible-web; rm -rf /"), {"format": "something else"},
                    dict(order(), version=2), []):
            with self.assertRaises(SystemExit):
                bp.order_to_args(bad)

    def test_the_spec_id_names_the_interior(self):
        settings = dict(ordering="chronological", canon="66", trim="standard", body=8.8, outer=12,
                        head="split", titles="long", accent="black", xrefs="none", xrefTop=3)
        sid = bp.spec_id("WEB", settings)
        self.assertEqual(sid, bp.spec_id("WEB", dict(settings, mode="CHAPTERS_VERSES")))   # mode is not part of it
        self.assertNotEqual(sid, bp.spec_id("KJV", settings))
        self.assertNotEqual(sid, bp.spec_id("WEB", dict(settings, outer=13)))
        self.assertNotEqual(sid, bp.spec_id("WEB", dict(settings, xrefs="inline")))
        self.assertEqual(len(sid), 12)
        # A golden value: edition-designer.html makes the same id with SHA-256 in the browser.
        self.assertEqual(sid, "1eeb9bcbf673")

    def test_an_order_whose_settings_no_longer_match_its_spec_id_is_refused(self):
        o = order(ordering="chronological", canon="66", trim="standard", body=8.8, outer=12, head="split",
                  titles="long", accent="black", xrefs="none", xrefTop=3)
        o["orderRef"] = "CR-1A2B3C4D"
        o["specId"] = bp.spec_id("WEB", o["settings"])
        a = pairs(bp.order_to_args(o))
        self.assertEqual((a["--order-ref"], a["--spec-id"]), ("CR-1A2B3C4D", o["specId"]))
        o["settings"]["outer"] = 20                              # edited after the order was made
        with self.assertRaises(SystemExit) as e:
            bp.order_to_args(o)
        self.assertIn("does not match", str(e.exception))

    def test_a_malformed_reference_or_spec_id_is_refused(self):
        for key, bad in (("orderRef", "CR-xyz"), ("orderRef", "ORDER-1"), ("specId", "nothex123456"),
                         ("specId", "abc")):
            o = order()
            o[key] = bad
            with self.assertRaises(SystemExit):
                bp.order_to_args(o)

    def test_the_printers_order_number_goes_into_the_package_name(self):
        a = pairs(bp.order_to_args(order(ordering="chronological"), "PR-2026-0042"))
        self.assertEqual(a["--order-id"], "PR-2026-0042")
        self.assertTrue(a["--out"].endswith("web-chronological-PR-2026-0042-package.zip"))
        for bad in ("", "a b", "x;rm -rf /", "../../etc", "x" * 41):
            with self.assertRaises(SystemExit):
                bp.order_to_args(order(), bad)

    def test_an_order_id_given_beside_the_order_is_not_repeated(self):
        self.assertEqual(bp._without_option(["--out", "x.zip", "--order-id", "A1", "--accent", "black"],
                                            "--order-id"), ["--out", "x.zip", "--accent", "black"])
        self.assertEqual(bp._without_option(["--order-id=A1", "--out", "x"], "--order-id"), ["--out", "x"])

    def test_the_estimate_is_information_only(self):
        o = order()
        o["estimate"] = {"pages": 1, "--out": "/etc/passwd"}
        self.assertNotIn("/etc/passwd", " ".join(bp.order_to_args(o)))


if __name__ == "__main__":
    unittest.main()
