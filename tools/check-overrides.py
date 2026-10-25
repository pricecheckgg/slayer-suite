#!/usr/bin/env python3
"""Print, for a list of tasks, our override rules next to the wiki sentences that
mention items, prayers and protection, so a maintainer can check them by eye.

Usage: python3 tools/check-overrides.py aberrant-spectres gargoyles ...
       python3 tools/check-overrides.py --all
Reads the cached wiki pages written by build-dataset.py (run that first).
"""
import glob
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "tools"))
import importlib.util
spec = importlib.util.spec_from_file_location("bd", os.path.join(ROOT, "tools", "build-dataset.py"))
bd = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bd)

KEYWORDS = re.compile(r"requir|need|must|only be (damaged|attacked|hit)|protect from|prayer|antifire|anti-dragon|dragonfire|shield|poison|venom|light source|spade|boots of stone|fungicide|rock hammer|salt|ice cooler|nose peg|earmuff|facemask|face mask|mirror shield|witchwood|leaf-bladed|broad|slayer helm|spiny|insulated|bell|lantern|explosive|goggles|gloves|unlock|salve|waterskin|rope|cannon", re.I)


def cached(title):
    for f in glob.glob(os.path.join(bd.CACHE, "*.json")):
        with open(f) as fh:
            d = json.load(fh)
        p = d.get("parse")
        if p and p.get("title") == title:
            return p["wikitext"]
    return None


def prose_sentences(wikitext):
    text = re.sub(r"\{\|.*?\n\|\}", "", wikitext, flags=re.S)       # drop tables
    text = re.sub(r"^==.*$", "", text, flags=re.M)
    text = bd.strip_markup(text)
    out = []
    for para in text.split("\n"):
        for sent in re.split(r"(?<=[.!?])\s+", para.strip()):
            if KEYWORDS.search(sent) and 20 < len(sent) < 400:
                out.append(sent.strip())
    return out


def main():
    with open(bd.OUT_JSON) as f:
        tasks = {t["key"]: t for t in json.load(f)["tasks"]}
    with open(bd.OVERRIDES) as f:
        overrides = json.load(f)
    keys = sys.argv[1:]
    if keys == ["--all"]:
        keys = [k for k in overrides if not k.startswith("_")]
    for key in keys:
        t = tasks.get(key)
        ov = overrides.get(key, {})
        print("=" * 100)
        print(key, "|", t["wikiPage"] if t else "NO RECORD", "| reviewed:", ov.get("reviewed"))
        print("  OVERRIDE required:", [(r["anyOf"], r.get("level", "required")) for r in ov.get("requiredItems", [])])
        print("  OVERRIDE protection:", [(r["anyOf"], r.get("level", "required")) for r in ov.get("protection", [])])
        print("  OVERRIDE finishing:", ov.get("finishingItem"))
        print("  OVERRIDE prayer/style:", ov.get("prayer"), "/", ov.get("style"))
        print("  OVERRIDE notes:", ov.get("notes"))
        if t:
            print("  WIKI attack styles:", sorted({s for m in t["monsters"] for s in m["attackStyles"]}), "| poison:", any(m["poisonous"] for m in t["monsters"]), "| venom:", any(m["venomous"] for m in t["monsters"]), "| lvl", t["slayerLevel"], "| other:", t["otherRequirements"])
            pages = [t["wikiPage"]] + [m["wikiPage"] for m in t["monsters"][:1]]
        else:
            pages = []
        for page in pages:
            w = cached(page)
            if not w:
                print("  (no cached page", page, ")")
                continue
            print("  --", page)
            for s in prose_sentences(w)[:14]:
                print("     *", s)


if __name__ == "__main__":
    main()
