#!/usr/bin/env python3
"""Whole-page inputs for the Kotlin oracle's `roundtrip` mode → conformance/pages/roundtrip/in.

Each is a page somebody could plausibly have on disk; the oracle writes what 17d0beb's
`PageCodec.encode(PageCodec.decode(text))` makes of it into ../out, and the Swift suite must make the
same bytes. Frontmatter is where most of these live: it is the part a line fixture cannot reach.
"""
import os

pages = {
    "canonical": "---\nid: 7c3f\ntype: task\nparent: 1a2b\ntitle: Wire up the sync worker\nmodified_at: 2026-08-25T14:22:31.402Z\ndevice: sm-s921b\n---\n# Welcome\n\nA list holds tasks.\n\n- [ ] Tasks can nest ^9f1e due:2026-08-26 !high #sync @batunii\n» - [x] An indented, finished subtask ^4d2c\n\n![[ink:5b8a]]\n",
    "pinned-and-order": "---\nid: p1\ntype: smart_list\ntitle: Today\nsystem_key: today\nicon: 📥\ncolor: Teal\npinned: false\nmodified_at: 2026-09-23T10:00:00Z\ndevice: sm-s921b\n---\n",
    "pinned-out-of-place": "---\nid: p1\ntype: list\ndevice: sm-s921b\npinned: TRUE\ntitle: Groceries\nmodified_at: 2026-09-23T10:00:00Z\ncolor: Moss\n---\n",
    "pinned-garbage": "---\nid: p1\ntype: smart_list\ntitle: Today\npinned: maybe\nmodified_at: 2026-09-23T10:00:00Z\n---\n",
    "unknown-keys": "---\nid: a\nfuture_field: keep me\ntype: task\nmodified_at: 2026-01-01T00:00:00Z\nzzz: last\naaa:no space\n---\nhello\n",
    "duplicate-keys": "---\nid: a\ntype: list\ntitle: First\nx: 1\ntitle: Second\nx: 2\nmodified_at: 2026-01-01T00:00:00Z\n---\n",
    "colon-edge": "---\nid: a\ntype: list\n:starts with colon\nno colon at all\n  spaced key  :  spaced value  \ntitle: a: b: c\nmodified_at: 2026-01-01T00:00:00Z\n---\n",
    "blank-values": "---\nid: a\ntype:\nparent:   \ntitle:\nsystem_key:\nicon:\ncolor:\ndevice:\nmodified_at: 2026-01-01T00:00:00Z\n---\n",
    "modified-precise": "---\nid: a\ntype: list\nmodified_at: 2026-09-24T22:27:24.207123Z\n---\n",
    "modified-nanos": "---\nid: a\ntype: list\nmodified_at: 2026-09-24T22:27:24.000000001Z\n---\n",
    "modified-short-fraction": "---\nid: a\ntype: list\nmodified_at: 2026-09-24T22:27:24.2Z\n---\n",
    "modified-offset": "---\nid: a\ntype: list\nmodified_at: 2026-09-24T22:27:24+01:00\n---\n",
    "modified-garbage": "---\nid: a\ntype: list\nmodified_at: yesterday\n---\n",
    "modified-missing": "---\nid: a\ntype: list\n---\n",
    "no-frontmatter": "# just a heading\n- [ ] orphan ^a1\n",
    "no-closing-fence": "---\nid: a\ntype: list\ntitle: never closed\n",
    "fence-with-spaces": "  ---  \nid: a\ntype: list\nmodified_at: 2026-01-01T00:00:00Z\n\t---\nbody\n",
    "crlf": "---\r\nid: a\r\ntype: list\r\nmodified_at: 2026-01-01T00:00:00Z\r\n---\r\n# Title\r\n\r\n- [ ] one ^t1\r\n- [ ] two ^t2\r\n",
    "lone-cr": "---\nid: a\ntype: list\nmodified_at: 2026-01-01T00:00:00Z\n---\n- [ ] one ^t1\r\n![[ink:a]]\r\n",
    "bom": "﻿---\nid: a\ntype: list\nmodified_at: 2026-01-01T00:00:00Z\n---\nx\n",
    "blank-layout": "---\nid: a\ntype: list\nmodified_at: 2026-01-01T00:00:00Z\n---\n\n\n# H\n- [ ] a ^1\n\n\n- [ ] b ^2\nprose\n1. one\n1. one again\n5. five\n  \n\t\n@ 2026-09-11 x ^e\n- bullet\n![[image:p]]\n- [ ] c\n",
    "numbered-runs": "---\nid: a\ntype: list\nmodified_at: 2026-01-01T00:00:00Z\n---\n1. a\n2. b\n» 1. nested\n3. c\n\nbreak\n\n2. restarts wrong\n",
    "no-trailing-newline": "---\nid: a\ntype: list\nmodified_at: 2026-01-01T00:00:00Z\n---\n- [ ] last line ^t1",
    "real-inbox": "---\nid: 5053c43d-5c59-431c-8af4-ef4c36dd6da9\ntype: list\ntitle: Inbox\nsystem_key: inbox\nmodified_at: 2026-09-25T16:17:32.974Z\ndevice: saieeshward\n---\n@ 2026-09-23T11:00/PT30M Intro Call: Napkin <> NVIDIA Inception  ^d753f005-6723-4b7e-b616-7ac806cd8af9 loc:Microsoft%20Teams%20Meeting ext:040000008200E00074C5B7101A82E008000000006D31FB104A3CDD01000000000000000010000000ABE5725BC7B5E14EA704A7A31D00324C\n@ 2026-09-24T16:30/PT3H15M Hello ^e570f417-dbe6-4846-bb43-a667e8afede2\n- [ ] Yantra bugs ^fbca78f7-1e7c-4887-abce-5dd91039541d !High\n",
    "empty-blocks": "---\nid: a\ntype: task\nmodified_at: 2026-01-01T00:00:00Z\n---\n \n- [ ]\n# \n- \n",
    "unicode-frontmatter": "---\nid: a\ntype: list\ntitle: Café \nicon: 👩‍💻\nköy: değer\nmodified_at: 2026-01-01T00:00:00Z\n---\n",
}

here = os.path.dirname(os.path.abspath(__file__))
out = os.path.join(here, "..", "pages", "roundtrip", "in")
os.makedirs(out, exist_ok=True)
for name, text in pages.items():
    with open(os.path.join(out, name + ".md"), "wb") as f:
        f.write(text.encode("utf-8"))
print(len(pages), "pages")
