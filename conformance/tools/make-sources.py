#!/usr/bin/env python3
"""The inputs the Kotlin oracle turns into conformance/pages/lines.json.

Every line here is fed to 17d0beb's PageCodec.decodeBlock, and the kind, the re-render without raw,
and the passthrough with raw are recorded. The first block is the set the older fixture carried; the
rest are the places a Swift port is most likely to read differently from Kotlin — graphemes versus
UTF-16, Unicode versus ASCII character classes, java.time's exact grammars.

Deliberately absent: lines with `by:`/`on:` or other unknown tokens *after* a known one. There iOS is
more tolerant than 17d0beb on purpose (PROMPT §1.2), so those are pinned by Swift tests instead.
"""
import json, os

old = [
    "Just some prose", "# A heading", "- a bullet", "3. a numbered item", "» » indented prose",
    "- [ ]", "- [ ] Open task", "- [x] Done task ^t1 done:2026-09-11", "- [~] Started ^t2",
    "![[ink:i1]]", "![[image:img1.jpg]]", "- [ ] Buy #2 pencils",
    "- [ ] Buy milk #groceries #urgent !High @sam",
    "- [ ] Blocked out ^t3 due:2026-09-11T14:00:00Z/PT1H+r15",
    "- [ ] Warned twice ^t7 due:2026-09-11T14:00:00Z+r1440,30",
    "- [ ] Warned out of order ^t8 due:2026-09-11T14:00:00Z+r30,1440,30",
    "- [ ] Warned after ^t9 due:2026-09-11T14:00:00Z+r-15",
    "- [ ] All day ^t4 due:2026-09-11 deadline:2026-09-20",
    "- [ ] About a meeting ^t5 ext:abc123@google.com",
    "- [ ] One occurrence ^t6 ext:abc123@google.com@2026-10-28T09:00",
    "- [ ] See [[Buy milk #2|^abc]] today",
    "@ 2026-09-11 Birthday ^e1", "@ 2026-09-11/2026-09-13 Conference ^e2",
    "@ 2026-09-11T14:00 Reminder ^e3", "@ 2026-09-11T14:00/PT1H Standup ^e4 rrule:FREQ=WEEKLY;BYDAY=MO",
    "@ 2026-09-11T14:00/2026-09-11T15:30 Review ^e5", "@ 2026-09-11T09:00[Europe/Dublin]/PT30M Call ^e6",
    "@ 2026-09-11T14:00/PT1H Sitting ^e7 for:t1", "@ 2026-09-11T14:00/PT1H Moved ^e8 series:s1@2026-10-28T09:00",
    "@ 2026-09-11T14:00/PT1H Skipped ^e9 series:s1 cancelled",
    "@ 2026-09-11T14:00/PT1H Lunch ^e10 loc:The%20Old%20Spot col:teal remind:10 !High #social @sam @alex",
    "@ 2026-09-11T14:00/PT1H Theirs ^e11 ext:abc123@google.com@2026-09-11T14:00",
    "@ 2026-09-11T14:00 Coffee with #2", "@ not-a-time at all",
]

new = [
    # --- task tokens, the scan and its edges ---
    "- [ ] x ^", "- [ ] x ^a ^", "- [ ]x", "- [ ]  two spaces", "- [ ] trailing  ", "- [X] upper",
    "- [ ] tab\ttoken #a", "- [x] Done ^t1 done:2026-09-11 due:2026-09-10", "- [ ] open but done:2026-09-11",
    "- [ ] x ext:", "- [ ] x ext:@2026-09-11T09:00", "- [ ] x ext:a%20b%25c", "- [ ] x ext:abc@notadate",
    "- [ ] x @", "- [ ] x #", "- [ ] x !", "- [ ] x !High !Low", "- [ ] x @a @b",
    "- [ ] Contractor or employee  ^f40f", "- [ ]    lots   of   spacing   ^a1",
    "- [ ] See [[Buy milk #2|^abc]] first ^t1 #real !High", "- [ ] Ping [[ask @bob|^x]]",
    "- [ ] ping [[the @team page]] ^a1", "- [ ] pay rent due:not-a-date ^a1",
    "- [ ] x ^i due:2026-08-26T09:00:00Z+r30,x", "- [ ] Café résumé ^t1 #naïve",
    "- [ ] x #́", "- [ ] x !́", "- [ ] x @́", "- [ ] x ^́",
    "- [ ] a ́b ^t1", "- [ ] Buy milk ^t1", "- [ ] x ^t1", "- [ ] x  ^t1",
    "- [ ] Call \U0001F600 #\U0001F600", "- [ ] x ^t1 #a #b #c @me",
    # 17d0beb reads these as title; a newer build writes them straight after it. Same bytes either way.
    "- [~] Draft the doc on:sai by:sai ^t1 due:2026-10-01 @sai", "- [ ] Added by:batunii ^t2",
    "- [ ] Fix login re:bug", "- [ ] Read https://example.com/a", "- [ ] Meet at 10:30",
    # --- due grammar: java.time's, exactly ---
    "- [ ] x ^i due:2026-08-26T09:00Z", "- [ ] x ^i due:2026-08-26T09:00:00+01:00",
    "- [ ] x ^i due:2026-08-26T09:00:00-05:30:15", "- [ ] x ^i due:2026-08-26T09:00:00+01",
    "- [ ] x ^i due:2026-08-26t09:00:00z", "- [ ] x ^i due:2026-08-26T09:00:00z",
    "- [ ] x ^i due:2026-08-26T09:00:00.5Z", "- [ ] x ^i due:2026-08-26T09:00:00.Z",
    "- [ ] x ^i due:2026-08-26T09:00:00.123Z", "- [ ] x ^i due:2026-08-26T24:00:00Z",
    "- [ ] x ^i due:2026-08-26T23:59:60Z", "- [ ] x ^i due:2026-08-26T12:59:60Z",
    "- [ ] x ^i due:2026-08-26+r+5", "- [ ] x ^i due:2026-08-26+r٣", "- [ ] x ^i due:2026-08-26+r",
    "- [ ] x ^i due:2026-08-26+r3000000000", "- [ ] x ^i due:2026-08-26+r5,5,-5,0",
    "- [ ] x ^i due:2026-08-26/PT1H", "- [ ] x ^i due:2026-08-26T09:00:00Z/pt30m",
    "- [ ] x ^i due:2026-08-26T09:00:00Z/P1D", "- [ ] x ^i due:2026-08-26T09:00:00Z/PT1.5S",
    "- [ ] x ^i due:2026-08-26T09:00:00Z/PT0S", "- [ ] x ^i due:2026-08-26T09:00:00Z/-PT1H",
    "- [ ] x ^i due:2026-08-26T09:00:00Z/PT", "- [ ] x ^i due:2026-08-26T09:00:00Z/P1W",
    "- [ ] x ^i due:2026-02-29", "- [ ] x ^i due:2028-02-29", "- [ ] x ^i due:+2026-01-01",
    "- [ ] x ^i due:-2026-01-01", "- [ ] x ^i due:+12026-01-01", "- [ ] x ^i due:2026-1-01",
    "- [ ] x ^i due:２０２６-01-01", "- [ ] x ^i deadline:2026-13-01", "- [ ] x ^i deadline:0000-01-01",
    # --- block classification ---
    "#heading", "## h2", "# ", "#", "- ", "-", " ", " ", "\u0085", " ", "\t", "@ ", "@sam owes me",
    "* star", "> quote", "# ́x", "- ́x", "»́ x", "» » - [ ] deep ^a1", "»» x", "» ", "»",
    "1. item", "1.\titem", "١. item", "1. a b", "99999999999. big", "3.  two spaces",
    "1.", "1. ", "12. twelve", "0. zero", "1.x",
    "![[ink:]]", "![[ink:a]]b", "![[ink:a]b]]", "![[ink:a]]\r", "![[image:content://x/y]]",
    "![[ink:a b]]", " ![[ink:a]]", "» ![[ink:a]]", "![[image:pic-1]]",
    # --- event line ---
    "@ 2026-09-11T14:00/PT1H30M Meeting  with  gaps ^e1", "@ 2026-09-11T14:00/pt1h x",
    "@ 2026-09-11T14:00/PT1.5S x", "@ 2026-09-11T14:00/P1D x", "@ 2026-09-11T14:00/P1DT2H30M x",
    "@ 2026-09-11T14:00/-PT1H x", "@ 2026-09-11T14:00/PT0S x", "@ 2026-09-11T14:00/PT-0.5S x",
    "@ 2026-09-11T14:00[GMT+1]/PT1H x", "@ 2026-09-11T14:00[+1]/PT1H x", "@ 2026-09-11T14:00[+00:00]/PT1H x",
    "@ 2026-09-11T14:00[EST]/PT1H x", "@ 2026-09-11T14:00[UTC]/PT1H x", "@ 2026-09-11T14:00[Z]/PT1H x",
    "@ 2026-09-11T14:00[europe/dublin]/PT1H x", "@ 2026-09-11T14:00[Europe/Dublin x",
    "@ 2026-09-11[Europe/Dublin]/2026-09-12 two days", "@ 2026-09-11T14:00:30.5/PT1H x",
    "@ 2026-09-11T14:00:30./PT1H x", "@ 2026-09-11T14:00:00/PT1H x", "@ 2026-09-11T14:00:30/PT1H x",
    "@ +12026-09-11 far", "@ 2026-09-11T24:00 x", "@ 2026-09-11/2026-09-11 one day", "@ 2026-09-11/2026-09-10 back",
    "@ 2026-09-11T14:00/2026-09-11 x", "@ 2026-09-11/2026-09-12T10:00 x",
    "@ 2026-09-11 x remind:٣", "@ 2026-09-11 x remind:+5", "@ 2026-09-11 x remind:-5",
    "@ 2026-09-11 x remind:3000000000", "@ 2026-09-11 x remind:", "@ 2026-09-11 x by:a%20b",
    "@ 2026-09-11 x by:", "@ 2026-09-11 x loc:", "@ 2026-09-11 x col:", "@ 2026-09-11 x for:",
    "@ 2026-09-11 x rrule:", "@ 2026-09-11 x rrule:A rrule:", "@ 2026-09-11 x rrule: rrule:A",
    "@ 2026-09-23T11:00/PT30M Intro Call ^79d24109 ext:_60q30c1g by:batunii remind:15",
    "@ 2026-09-23T11:00/PT30M Intro Call: Napkin <> NVIDIA Inception  ^d753 loc:Microsoft%20Teams%20Meeting ext:0400",
    "@ 2026-09-11 x series:", "@ 2026-09-11 x series:@2026-09-11T09:00", "@ 2026-09-11 x series:s1@bad",
    "@ 2026-09-11T09:00/PT15M x ^s1x2 series:s1@2026-09-11T09:00", "@ 2026-09-11 x cancelled cancelled",
    "@ 2026-09-11 x Cancelled", "@ 2026-09-11 x #́ @́ !́", "@ 2026-09-11 x",
    "@  2026-09-11 x", "@ 2026-09-11 x", "@ 2026-09-11\tx",
]

here = os.path.dirname(os.path.abspath(__file__))
with open(os.path.join(here, "lines-sources.json"), "w", encoding="utf-8") as f:
    json.dump(old + new, f, ensure_ascii=False, indent=1)
print(len(old + new), "sources")
