#!/usr/bin/env python3
"""Build the Slayer Suite monster dataset from the Old School RuneScape Wiki.

Reads every "Slayer task/<name>" page plus the monster pages they link to,
merges the hand-maintained overrides (required items, finishing items,
protection, prayer, style) and writes:

  src/main/resources/com/slayersuite/data/monsters.json
  DATA-ATTRIBUTION.md  (page list with revision ids, licence notice)
  tools/report.txt     (coverage report for the maintainer)

Only the Python standard library is used. Responses are cached under
tools/cache so a rebuild does not hit the wiki again; pass --refresh to
re-download. The wiki asks clients to send a descriptive User-Agent and to
keep request rates modest, which this script does (one request at a time
with a short pause).

Usage:  python3 tools/build-dataset.py [--refresh] [--limit N]
"""
import argparse
import datetime
import hashlib
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request

API = "https://oldschool.runescape.wiki/api.php"
UA = "slayer-suite-dataset-builder/1.0 (RuneLite plugin dataset; see the plugin README for contact)"
MASTERS = ["turael", "spria", "aya", "mazchna", "achtryn", "vannaka", "chaeldar", "konar", "nieve", "steve", "duradel", "kuradal", "krystilia", "mortimer"]

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CACHE = os.path.join(ROOT, "tools", "cache")
OUT_JSON = os.path.join(ROOT, "src", "main", "resources", "com", "slayersuite", "data", "monsters.json")
OUT_ATTR = os.path.join(ROOT, "DATA-ATTRIBUTION.md")
OUT_REPORT = os.path.join(ROOT, "tools", "report.txt")
OVERRIDES = os.path.join(ROOT, "tools", "overrides.json")
CORE_NAMES = os.path.join(ROOT, "tools", "runelite-task-names.txt")
TASK_ICONS = os.path.join(ROOT, "tools", "task-icons.txt")


# ---------------------------------------------------------------- fetching

def fetch(params, refresh=False):
    params = dict(params, format="json", formatversion="2")
    qs = urllib.parse.urlencode(sorted(params.items()))
    os.makedirs(CACHE, exist_ok=True)
    path = os.path.join(CACHE, hashlib.sha1(qs.encode()).hexdigest() + ".json")
    if not refresh and os.path.exists(path):
        with open(path) as f:
            return json.load(f)
    req = urllib.request.Request(API + "?" + qs, headers={"User-Agent": UA})
    data = None
    for attempt in range(4):
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                data = json.load(r)
            break
        except Exception as e:  # network hiccup: back off and retry
            if attempt == 3:
                raise
            time.sleep(2.0 * (attempt + 1))
    with open(path, "w") as f:
        json.dump(data, f)
    time.sleep(0.3)
    return data


def parse_page(title, refresh=False):
    """Return (resolved title, wikitext, revid) following redirects."""
    d = fetch({"action": "parse", "page": title, "prop": "wikitext|revid", "redirects": "1"}, refresh)
    if "error" in d:
        return None, None, None
    p = d["parse"]
    return p["title"], p["wikitext"], p.get("revid")


def list_task_pages(refresh=False):
    d = fetch({"action": "query", "list": "allpages", "apprefix": "Slayer task/", "aplimit": "500", "apfilterredir": "nonredirects"}, refresh)
    real = [p["title"] for p in d["query"]["allpages"] if p["title"] != "Slayer task/"]
    d = fetch({"action": "query", "list": "allpages", "apprefix": "Slayer task/", "aplimit": "500", "apfilterredir": "redirects"}, refresh)
    redirs = [p["title"] for p in d["query"]["allpages"]]
    mapping = {}
    for i in range(0, len(redirs), 50):
        chunk = redirs[i:i + 50]
        q = fetch({"action": "query", "titles": "|".join(chunk), "redirects": "1"}, refresh)
        for r in q["query"].get("redirects", []):
            mapping[r["from"]] = r["to"]
    return real, mapping


# ---------------------------------------------------------------- wikitext helpers

def split_top(text, sep="|"):
    """Split at sep occurring outside {{ }} and [[ ]]."""
    parts, depth, cur, i, n = [], 0, [], 0, len(text)
    while i < n:
        two = text[i:i + 2]
        if two in ("{{", "[["):
            depth += 1
            cur.append(two)
            i += 2
            continue
        if two in ("}}", "]]"):
            depth -= 1
            cur.append(two)
            i += 2
            continue
        if depth <= 0 and text.startswith(sep, i):
            parts.append("".join(cur))
            cur = []
            i += len(sep)
            continue
        cur.append(text[i])
        i += 1
    parts.append("".join(cur))
    return parts


def find_templates(text, name=None, deep=False):
    """Return the full text of each top-level {{...}} template, optionally filtered by name.
    With deep=True, templates nested inside other templates (Multi Infobox, Switch infobox) are returned too."""
    out, i, n = [], 0, len(text)
    while True:
        j = text.find("{{", i)
        if j < 0:
            break
        depth, p = 0, j
        while p < n:
            if text.startswith("{{", p):
                depth += 1
                p += 2
                continue
            if text.startswith("}}", p):
                depth -= 1
                p += 2
                if depth == 0:
                    break
                continue
            p += 1
        tpl = text[j:p]
        tname = split_top(tpl[2:-2])[0].strip().lower()
        if name is None or tname == name.lower():
            out.append(tpl)
        if deep and len(tpl) > 4:
            out.extend(find_templates(tpl[2:-2], name, True))
        i = p if p > j else j + 2
    return out


def template_params(tpl):
    parts = split_top(tpl[2:-2])
    name = parts[0].strip()
    params, positional = {}, []
    for part in parts[1:]:
        kv = split_top(part, "=")
        if len(kv) >= 2:
            params[kv[0].strip().lower()] = "=".join(kv[1:]).strip()
        else:
            positional.append(part.strip())
    return name, params, positional


def render_template(tpl):
    name, params, pos = template_params(tpl)
    n = name.lower()
    if n in ("yes", "no", "partial", "maybe"):
        return name.capitalize()
    if n == "scp":
        return " ".join(pos[:2])
    if n in ("plink", "plinkt", "plinkp"):
        return params.get("txt", pos[0] if pos else "")
    if n == "floornumber":
        return ""
    if n == "fairycode":
        return "fairy ring " + (pos[0] if pos else "")
    if n in ("citetwitter", "citeforum", "citenews", "citegeneral", "refn", "main", "map", "citereddit", "citeyoutube", "citetext", "citepodcast", "citedev", "citepost", "citetext"):
        return ""
    if n == "sic":
        return pos[0] if pos else ""
    return " ".join(p for p in pos if p)


def strip_markup(s):
    if s is None:
        return ""
    s = re.sub(r"<!--.*?-->", "", s, flags=re.S)
    s = re.sub(r"<ref[^>/]*/>", "", s)
    s = re.sub(r"<ref[^>]*>.*?</ref>", "", s, flags=re.S)
    for _ in range(3):
        for tpl in find_templates(s):
            s = s.replace(tpl, render_template(tpl), 1)
    s = re.sub(r"\[\[(?:File|Image):[^\]]*\]\]", "", s, flags=re.I)
    s = re.sub(r"\[\[([^\]|]*)\|([^\]]*)\]\]", r"\2", s)
    s = re.sub(r"\[\[([^\]]*)\]\]", r"\1", s)
    s = re.sub(r"\[https?://\S+\s+([^\]]*)\]", r"\1", s)
    s = re.sub(r"'''?", "", s)
    s = re.sub(r"<br\s*/?>", ", ", s, flags=re.I)
    s = re.sub(r"<[^>]+>", "", s)
    s = s.replace("&nbsp;", " ")
    s = re.sub(r"[ \t]+", " ", s)
    return s.strip(" ,;")


def first_link(s):
    for m in re.finditer(r"\[\[([^\]|]*)(?:\|[^\]]*)?\]\]", s):
        t = m.group(1).strip()
        if not re.match(r"(File|Image):", t, re.I):
            return t.split("#")[0]
    return None


def first_number(s):
    m = re.search(r"\d[\d,]*(?:\.\d+)?", s or "")
    if not m:
        return None
    try:
        v = float(m.group(0).replace(",", ""))
    except ValueError:
        return None
    return int(v) if v.is_integer() else v


def bullet_lines(cell):
    out = []
    for line in (cell or "").splitlines():
        t = line.strip()
        if not t:
            continue
        t = strip_markup(t.lstrip("*#").strip())
        if t:
            out.append(t)
    return out


def yesno(cell):
    low = (cell or "").lower()
    if "{{yes" in low:
        return True
    if "{{no" in low:
        return False
    return None


# ---------------------------------------------------------------- tables

def table_lines(tbl):
    """Split a wikitable into logical lines starting with |, ! or {| at depth 0."""
    lines, depth, start, i, n = [], 0, 0, 0, len(tbl)
    while i < n:
        if tbl.startswith("{{", i) or tbl.startswith("[[", i):
            depth += 1
            i += 2
            continue
        if tbl.startswith("}}", i) or tbl.startswith("]]", i):
            depth -= 1
            i += 2
            continue
        if tbl[i] == "\n" and depth <= 0 and i + 1 < n and tbl[i + 1] in "|!":
            lines.append(tbl[start:i])
            start = i + 1
        i += 1
    lines.append(tbl[start:])
    return lines


def clean_header(h):
    h = h.strip()
    if "|" in h and "[[" not in h.split("|")[0] and "{{" not in h.split("|")[0]:
        h = h.split("|", 1)[1]
    return re.sub(r"\s+", " ", strip_markup(h)).strip().lower()


def parse_table(tbl):
    header, rows, cur = [], [], []
    for ln in table_lines(tbl):
        if ln.startswith("{|") or ln.startswith("|}") or ln.startswith("|+"):
            continue
        if ln.startswith("|-"):
            if cur:
                rows.append(cur)
            cur = []
            continue
        if ln.startswith("!"):
            for h in split_top(ln[1:], "!!"):
                header.append(clean_header(h))
            continue
        if ln.startswith("|"):
            body = ln[1:]
            for c in split_top(body, "||"):
                m = re.match(r'\s*((?:[a-z]+="[^"]*"\s*)+)\|(.*)', c, re.S)
                if m:
                    c = m.group(2)
                cur.append(c)
    if cur:
        rows.append(cur)
    return header, rows


def all_tables(text):
    out = []
    for m in re.finditer(r"^\{\|", text, re.M):
        end = text.find("\n|}", m.start())
        if end < 0:
            continue
        out.append(parse_table(text[m.start():end + 3]))
    return out


def col(header, *names):
    for i, h in enumerate(header):
        for nm in names:
            if h == nm or h.startswith(nm):
                return i
    return None


def region_id(x, y):
    return ((x >> 6) << 8) | (y >> 6)


# ---------------------------------------------------------------- page parsers

def parse_slayer_infobox(text):
    tpls = find_templates(text, "Infobox Slayer", deep=True)
    if not tpls:
        return {}
    _, p, _ = template_params(tpls[0])
    rec = {
        "name": strip_markup(p.get("name", "")),
        "slayerLevel": None,
        "combatLevel": None,
        "otherRequirements": None,
        "taskId": None,
        "masters": {},
    }
    sk = strip_markup(p.get("skillreq", ""))
    m = re.search(r"Slayer\s+(\d+)", sk)
    rec["slayerLevel"] = int(m.group(1)) if m else 1
    cb = strip_markup(p.get("combatreq", ""))
    m = re.search(r"(\d+)", cb)
    rec["combatLevel"] = int(m.group(1)) if m else None
    other = strip_markup(p.get("otherreq", ""))
    rec["otherRequirements"] = None if other.lower() in ("", "none", "no") else other
    tid = strip_markup(p.get("id", ""))
    rec["taskId"] = int(tid) if tid.isdigit() else None
    for master in MASTERS:
        raw = p.get(master)
        if not raw:
            continue
        txt = strip_markup(raw)
        ranges = []
        for mm in re.finditer(r"(\d+)\s*[-–]\s*(\d+)(?:\s*\((\d+)\s*[-–]\s*(\d+)\))?", txt):
            r = {"min": int(mm.group(1)), "max": int(mm.group(2))}
            if mm.group(3):
                r["extendedMin"] = int(mm.group(3))
                r["extendedMax"] = int(mm.group(4))
            ranges.append(r)
        if ranges:
            entry = dict(ranges[0])
            if len(ranges) > 1:
                entry["alternatives"] = ranges[1:]
            entry["raw"] = txt
            rec["masters"][master] = entry
    return rec


def parse_variants(text):
    out = []
    for header, rows in all_tables(text):
        ci = col(header, "slayer xp")
        if ci is None:
            continue
        cm = col(header, "monster")
        cc = col(header, "combat level", "combat")
        cl = col(header, "locations", "location")
        cn = col(header, "notes")
        for cells in rows:
            if cm is None or cm >= len(cells):
                continue
            target = first_link(cells[cm])
            label = strip_markup(cells[cm])
            if not target and not label:
                continue
            notes = bullet_lines(cells[cn]) if cn is not None and cn < len(cells) else []
            ntxt = " ".join(notes).lower()
            out.append({
                "page": target or label,
                "label": label or target,
                "combatText": strip_markup(cells[cc]) if cc is not None and cc < len(cells) else "",
                "slayerXpText": strip_markup(cells[ci]) if ci < len(cells) else "",
                "locations": bullet_lines(cells[cl]) if cl is not None and cl < len(cells) else [],
                "notes": notes,
                "superior": ("superior slayer monster" in ntxt) or ("superior variant" in ntxt) or ("superior version" in ntxt),
            })
    return out


def parse_locations(text):
    out = []
    for header, rows in all_tables(text):
        cc = col(header, "cannonable", "cannon")
        if cc is None:
            continue
        cl = col(header, "location")
        cmap = col(header, "maplink", "map")
        ca = col(header, "amount")
        cmu = col(header, "multicombat", "multi")
        cs = col(header, "safespottable", "safespot")
        cn = col(header, "notes")
        for cells in rows:
            if cl is None or cl >= len(cells):
                continue
            raw = cells[cl]
            floor = None
            for tpl in find_templates(raw, "FloorNumber"):
                _, fp, fpos = template_params(tpl)
                v = fp.get("uk") or fp.get("us") or (fpos[0] if fpos else None)
                if v and re.match(r"-?\d+", v):
                    floor = int(re.match(r"-?\d+", v).group(0))
            name = strip_markup(raw)
            name = re.sub(r"\(\s*\)", "", name).strip(" ,")
            coords, plane, map_id = [], None, None
            if cmap is not None and cmap < len(cells):
                for tpl in find_templates(cells[cmap], "Map"):
                    _, mp, mpos = template_params(tpl)
                    if mp.get("plane", "").strip().isdigit():
                        plane = int(mp["plane"])
                    if mp.get("mapid", "").strip().lstrip("-").isdigit():
                        map_id = int(mp["mapid"])
                    for item in mpos:
                        m = re.match(r"\s*(\d+)\s*,\s*(\d+)", item)
                        if m:
                            coords.append((int(m.group(1)), int(m.group(2))))
            regions = sorted({region_id(x, y) for x, y in coords})
            cx = cy = None
            if coords:
                xs = sorted(x for x, _ in coords)
                ys = sorted(y for _, y in coords)
                cx, cy = xs[len(xs) // 2], ys[len(ys) // 2]
            out.append({
                "name": name,
                "page": first_link(raw),
                "floor": floor,
                "plane": plane,
                "mapId": map_id,
                "regions": regions,
                "x": cx,
                "y": cy,
                "pins": [[x, y] for x, y in coords[:80]],
                "spawnCount": len(coords),
                "amount": strip_markup(cells[ca]) if ca is not None and ca < len(cells) else "",
                "multicombat": yesno(cells[cmu]) if cmu is not None and cmu < len(cells) else None,
                "cannon": yesno(cells[cc]) if cc < len(cells) else None,
                "safespot": yesno(cells[cs]) if cs is not None and cs < len(cells) else None,
                "notes": bullet_lines(cells[cn]) if cn is not None and cn < len(cells) else [],
            })
    return out


FAIRY_SUFFIX = r"\s*\((?:fairy ring )?[A-Z]{3}\)\s*$"


def parse_locline_locations(text):
    """Locations from a monster page's {{LocLine}} rows (newer pages carry x:N,y:N pins)."""
    out = {}
    for tpl in find_templates(text, "LocLine", deep=True):
        _, params, pos = template_params(tpl)
        name = strip_markup(params.get("location", ""))
        if not name:
            continue
        coords = []
        for item in pos + list(params.values()):
            for m in re.finditer(r"x\s*[:=]\s*(\d+)\s*,\s*y\s*[:=]\s*(\d+)", item):
                coords.append((int(m.group(1)), int(m.group(2))))
        plane = None
        if params.get("plane", "").strip().isdigit():
            plane = int(params["plane"])
        rec = out.setdefault(name, {"name": name, "page": first_link(params.get("location", "")), "floor": None, "plane": plane, "mapId": None,
                                    "regions": [], "x": None, "y": None, "spawnCount": 0, "amount": "", "multicombat": None, "cannon": None, "safespot": None, "notes": [], "coords": []})
        rec["coords"].extend(coords)
    result = []
    for rec in out.values():
        coords = rec.pop("coords")
        if coords:
            xs = sorted(x for x, _ in coords)
            ys = sorted(y for _, y in coords)
            rec["x"], rec["y"] = xs[len(xs) // 2], ys[len(ys) // 2]
            rec["pins"] = [[x, y] for x, y in coords[:80]]
            rec["regions"] = sorted({region_id(x, y) for x, y in coords})
            rec["spawnCount"] = len(coords)
            rec["amount"] = str(len(coords))
        result.append(rec)
    return result


def parse_monster_infobox(text):
    tpls = find_templates(text, "Infobox Monster", deep=True)
    if not tpls:
        return None
    plist = [template_params(t)[1] for t in tpls]
    p = plist[0]

    def vals(base):
        out = []
        for params in plist:
            if base in params:
                out.append(params[base])
            for k, v in params.items():
                if re.fullmatch(re.escape(base) + r"\d+", k):
                    out.append(v)
        return out

    def nums(base):
        res = []
        for v in vals(base):
            n = first_number(strip_markup(v))
            if n is not None:
                res.append(n)
        return res

    ids = sorted({int(x) for v in vals("id") for x in re.findall(r"\d+", strip_markup(v))})
    xp = nums("slayxp")
    cb = nums("combat")
    hp = nums("hitpoints")
    styles = []
    for v in vals("attack style"):
        for part in re.split(r",|/| and ", strip_markup(v)):
            t = part.strip().strip(".").lower()
            if t and t not in styles and t != "none":
                styles.append(t)
    attrs = []
    for v in vals("attributes"):
        for part in strip_markup(v).split(","):
            t = part.strip().lower()
            if t and t not in attrs and t != "none":
                attrs.append(t)
    poison = " ".join(strip_markup(v).lower() for v in vals("poisonous"))
    maxhit = [strip_markup(v) for v in vals("max hit")]
    weak = [strip_markup(v) for v in vals("elementalweaknesstype")]
    rec = {
        "name": strip_markup(p.get("name", "")),
        "npcIds": ids,
        "versions": [strip_markup(v) for v in vals("version")],
        "slayerLevel": (nums("slaylvl") or [None])[0],
        "slayerXp": xp[0] if xp else None,
        "slayerXpMax": max(xp) if len(set(xp)) > 1 else None,
        "combat": cb[0] if cb else None,
        "combatMax": max(cb) if len(set(cb)) > 1 else None,
        "hitpoints": hp[0] if hp else None,
        "hitpointsMax": max(hp) if len(set(hp)) > 1 else None,
        "maxHit": maxhit[0] if maxhit else None,
        "attackStyles": styles,
        "attributes": attrs,
        "poisonous": ("yes" in poison or "poison" in poison) and "venom" not in poison,
        "venomous": "venom" in poison,
        "category": strip_markup(p.get("cat", "")) or None,
        "cannonImmune": "yes" in strip_markup(p.get("immunecannon", "")).lower(),
        "elementalWeakness": (weak[0].lower() if weak and weak[0] else None),
    }
    return rec


# ---------------------------------------------------------------- build

def singular(s):
    if s.endswith("ies"):
        return s[:-3] + "y"
    if s.endswith("ves"):
        return s[:-3] + "f"
    if s.endswith("es") and not s.endswith("ees"):
        return s[:-2]
    if s.endswith("s") and not s.endswith("ss"):
        return s[:-1]
    return s


def slug(s):
    return re.sub(r"-+", "-", re.sub(r"[^a-z0-9]+", "-", s.lower())).strip("-")


def norm(s):
    return re.sub(r"[^a-z0-9]+", "", (s or "").lower())


def plural_forms(n):
    forms = {n}
    if n.endswith("ies"):
        forms.add(n[:-3] + "y")
    if n.endswith("es"):
        forms.add(n[:-2])
    if n.endswith("s"):
        forms.add(n[:-1])
    else:
        forms.add(n + "s")
        forms.add(n + "es")
    return forms


def load_json(path, default):
    if not os.path.exists(path):
        return default
    with open(path) as f:
        return json.load(f)


def load_core_names():
    """tools/runelite-task-names.txt: ENUM|Display name|npc name|npc name..."""
    out = []
    if not os.path.exists(CORE_NAMES):
        return out
    with open(CORE_NAMES) as f:
        for line in f:
            if line.startswith("#"):
                continue
            parts = [p.strip() for p in line.rstrip("\n").split("|") if p.strip()]
            if len(parts) >= 2:
                out.append({"enum": parts[0], "name": parts[1], "targets": parts[2:]})
    return out


def load_icons():
    out = {}
    if os.path.exists(TASK_ICONS):
        with open(TASK_ICONS) as f:
            for line in f:
                if line.startswith("#") or "|" not in line:
                    continue
                name, item = line.rstrip("\n").split("|", 1)
                if item.strip().isdigit():
                    out[norm(name)] = int(item)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--refresh", action="store_true", help="ignore the cache and re-download")
    ap.add_argument("--limit", type=int, default=0, help="only process the first N task pages (debugging)")
    args = ap.parse_args()

    overrides = load_json(OVERRIDES, {})
    core = load_core_names()
    icons = load_icons()
    today = datetime.date.today().isoformat()

    task_pages, redirect_map = list_task_pages(args.refresh)
    if args.limit:
        task_pages = task_pages[:args.limit]
    print(f"{len(task_pages)} task pages, {len(redirect_map)} redirects", file=sys.stderr)

    aliases_by_target = {}
    for frm, to in redirect_map.items():
        aliases_by_target.setdefault(to, set()).add(frm.split("/", 1)[1].strip())

    sources = []          # (title, revid)
    monster_cache = {}    # page title -> record
    tasks = []
    report = []

    def monster(page_title):
        if page_title in monster_cache:
            return monster_cache[page_title]
        title, text, revid = parse_page(page_title, args.refresh)
        rec = parse_monster_infobox(text) if text else None
        if rec:
            rec["page"] = title
            sources.append((title, revid))
        else:
            report.append(f"no monster infobox: {page_title}")
        monster_cache[page_title] = rec
        return rec

    for title in task_pages:
        short = title.split("/", 1)[1]
        rtitle, text, revid = parse_page(title, args.refresh)
        if not text:
            report.append(f"could not fetch {title}")
            continue
        sources.append((rtitle, revid))
        info = parse_slayer_infobox(text)
        variants = parse_variants(text)
        locations = parse_locations(text)
        key = slug(short)
        name = info.get("name") or short
        if not variants:
            for cand in dict.fromkeys([name, short, singular(name), singular(short)]):
                mtitle, mtext, mrev = parse_page(cand, args.refresh)
                if mtext and parse_monster_infobox(mtext):
                    variants = [{"page": mtitle, "label": mtitle, "combatText": "", "slayerXpText": "", "locations": [], "notes": [], "superior": False}]
                    report.append(f"variants from monster page: {title} <- {mtitle}")
                    break
        rec = {
            "key": key,
            "name": short,
            "displayName": name,
            "wikiPage": rtitle,
            "aliases": sorted(aliases_by_target.get(title, set())),
            "slayerLevel": info.get("slayerLevel"),
            "combatLevel": info.get("combatLevel"),
            "otherRequirements": info.get("otherRequirements"),
            "taskId": info.get("taskId"),
            "masters": info.get("masters", {}),
            "monsters": [],
            "superiors": [],
            "locations": locations,
            "requiredItems": [],
            "finishingItem": None,
            "protection": [],
            "prayer": None,
            "style": None,
            "notes": [],
            "coreTargetNames": [],
        }
        seen = set()
        for v in variants:
            mon = monster(v["page"]) or {}
            mname = mon.get("name") or v["label"]
            if mname in seen:
                continue
            seen.add(mname)
            entry = {
                "name": mname,
                "label": v["label"],
                "wikiPage": mon.get("page", v["page"]),
                "npcIds": mon.get("npcIds", []),
                "combat": mon.get("combat") or first_number(v["combatText"]),
                "combatMax": mon.get("combatMax"),
                "slayerXp": mon.get("slayerXp") if mon.get("slayerXp") is not None else first_number(v["slayerXpText"]),
                "slayerXpMax": mon.get("slayerXpMax"),
                "hitpoints": mon.get("hitpoints"),
                "hitpointsMax": mon.get("hitpointsMax"),
                "maxHit": mon.get("maxHit"),
                "attackStyles": mon.get("attackStyles", []),
                "attributes": mon.get("attributes", []),
                "poisonous": mon.get("poisonous", False),
                "venomous": mon.get("venomous", False),
                "cannonImmune": mon.get("cannonImmune", False),
                "elementalWeakness": mon.get("elementalWeakness"),
                "category": mon.get("category"),
                "superior": v["superior"],
                "locations": v["locations"],
                "notes": v["notes"],
            }
            rec["monsters"].append(entry)
            if v["superior"]:
                rec["superiors"].append(mname)
        if not variants:
            report.append(f"no variants table: {title}")
        # Monster pages carry the map pins in their LocLine rows. Task-page tables often name a place
        # without pins, so merge: pins onto matching table rows, pin-only places added, and when the
        # table has nothing the pins are the locations.
        pinned = {}
        for v in variants:
            if v["superior"]:
                continue
            mtitle, mtext, _ = parse_page(v["page"], args.refresh)
            if not mtext:
                continue
            for loc in parse_locline_locations(mtext):
                loc["name"] = re.sub(FAIRY_SUFFIX, "", loc["name"])
                pinned.setdefault(loc["name"], loc)
        def same_place(a, b):
            na, nb = norm(a), norm(b)
            if na == nb or (len(na) > 6 and (na in nb or nb in na)):
                return True
            wa, wb = na.split(), nb.split()
            return len(wa) >= 2 and len(wb) >= 2 and wa[:2] == wb[:2]
        filled = 0
        for loc in locations:
            if loc.get("regions"):
                continue
            for pname, ploc in pinned.items():
                if same_place(loc["name"], pname):
                    for k in ("x", "y", "regions", "pins", "spawnCount", "plane", "mapId"):
                        if ploc.get(k) not in (None, [], 0, "") and loc.get(k) in (None, [], 0, ""):
                            loc[k] = ploc[k]
                    if not loc.get("amount"):
                        loc["amount"] = ploc.get("amount", "")
                    filled += 1
                    break
        added = 0
        for pname, ploc in pinned.items():
            if not any(same_place(loc["name"], pname) for loc in locations):
                locations.append(ploc)
                added += 1
        rec["locations"] = locations
        if filled or added:
            report.append(f"monster pins merged: {title} (filled {filled}, added {added})")
        if not locations:
            report.append(f"no location table: {title}")

        # core RuneLite task names -> aliases + NPC target names
        cands = plural_forms(norm(short)) | plural_forms(norm(name))
        for a in rec["aliases"]:
            cands |= plural_forms(norm(a))
        for c in core:
            if norm(c["name"]) in cands or plural_forms(norm(c["name"])) & cands:
                rec["coreTargetNames"] = [c["name"]] + c["targets"]
                if c["name"] not in rec["aliases"] and norm(c["name"]) != norm(short):
                    rec["aliases"].append(c["name"])
                c["matched"] = True
                rec["iconItemId"] = icons.get(norm(c["name"]))
                break

        # overrides
        ov = overrides.get(key)
        if ov:
            if ov.get("skip"):
                report.append(f"skipped by override: {key}")
                continue
            for fld in ("requiredItems", "finishingItem", "protection", "prayer", "style"):
                if fld in ov:
                    rec[fld] = ov[fld]
            for fld in ("aliases", "superiors", "notes"):
                for x in ov.get(fld, []):
                    if x not in rec[fld]:
                        rec[fld].append(x)
            if "coreTargetNames" in ov:
                for x in ov["coreTargetNames"]:
                    if x not in rec["coreTargetNames"]:
                        rec["coreTargetNames"].append(x)
            for lov in ov.get("locations", []):
                for loc in rec["locations"]:
                    if norm(loc["name"]) == norm(lov.get("name", "")):
                        loc.update({k: v for k, v in lov.items() if k != "name"})
            for extra in ov.get("extraLocations", []):
                rec["locations"].append(extra)
            rec["reviewed"] = bool(ov.get("reviewed", False))
        else:
            report.append(f"no overrides: {key}")
            rec["reviewed"] = False
        tasks.append(rec)

    # Core tasks without a "Slayer task/" page: build a minimal record from the monster page.
    for c in core:
        if c.get("matched"):
            continue
        cname = c["name"]
        found = None
        for cand in dict.fromkeys([f"Slayer task/{cname}", cname, singular(cname), cname.replace("The ", ""), singular(cname.replace("The ", ""))]):
            rtitle, text, revid = parse_page(cand, args.refresh)
            if not text:
                continue
            mon = parse_monster_infobox(text)
            if mon:
                mon["page"] = rtitle
                mon["text"] = text
                sources.append((rtitle, revid))
                found = mon
                break
        if not found:
            report.append(f"core task without a wiki record: {cname}")
            continue
        key = slug(cname)
        ov = overrides.get(key, {})
        # The monster page's own location rows carry the map pins; without them the GPS has nothing to aim at.
        page_locs = parse_locline_locations(found["text"])
        for loc in page_locs:
            loc["name"] = re.sub(FAIRY_SUFFIX, "", loc["name"])
        for lov in ov.get("locations", []):
            for loc in page_locs:
                if norm(loc["name"]) == norm(lov.get("name", "")):
                    loc.update({k: v for k, v in lov.items() if k != "name"})
        rec = {
            "key": key, "name": cname, "displayName": cname, "wikiPage": found["page"], "aliases": [],
            "slayerLevel": found.get("slayerLevel") or 1, "combatLevel": None, "otherRequirements": None, "taskId": None,
            "masters": {},
            "monsters": [{
                "name": found["name"], "label": found["name"], "wikiPage": found["page"], "npcIds": found["npcIds"],
                "combat": found.get("combat"), "combatMax": found.get("combatMax"), "slayerXp": found.get("slayerXp"), "slayerXpMax": found.get("slayerXpMax"),
                "hitpoints": found.get("hitpoints"), "hitpointsMax": found.get("hitpointsMax"), "maxHit": found.get("maxHit"),
                "attackStyles": found.get("attackStyles", []), "attributes": found.get("attributes", []), "poisonous": found.get("poisonous", False),
                "venomous": found.get("venomous", False), "cannonImmune": found.get("cannonImmune", False), "elementalWeakness": found.get("elementalWeakness"),
                "category": found.get("category"), "superior": False, "locations": [l["name"] for l in page_locs], "notes": []}],
            "superiors": list(ov.get("superiors", [])), "locations": page_locs + list(ov.get("extraLocations", [])),
            "requiredItems": ov.get("requiredItems", []), "finishingItem": ov.get("finishingItem"), "protection": ov.get("protection", []),
            "prayer": ov.get("prayer"), "style": ov.get("style"), "notes": list(ov.get("notes", [])),
            "coreTargetNames": [cname] + c["targets"], "reviewed": bool(ov.get("reviewed", False)), "fallback": True,
            "iconItemId": icons.get(norm(cname)),
        }
        for a in ov.get("aliases", []):
            rec["aliases"].append(a)
        tasks.append(rec)
        report.append(f"fallback record from monster page: {cname} <- {found['page']}")

    tasks.sort(key=lambda t: t["name"].lower())
    # Every task needs at least one location with map regions, or the card cannot place you and the pin cannot be set.
    no_loc = [t["name"] for t in tasks if not t["locations"]]
    no_regions = [t["name"] for t in tasks if t["locations"] and not any(l.get("regions") for l in t["locations"])]
    report.append("")
    report.append(f"GAP CHECK: {len(no_loc)} tasks with no locations, {len(no_regions)} with locations but no map regions")
    for n in no_loc:
        report.append(f"  no locations: {n}")
    for n in no_regions:
        report.append(f"  no regions: {n}")
    if no_loc or no_regions:
        print(f"WARNING: {len(no_loc)} tasks have no locations and {len(no_regions)} have none with map regions; see tools/report.txt", file=sys.stderr)
    out = {
        "schemaVersion": 1,
        "generated": today,
        "source": "Old School RuneScape Wiki (https://oldschool.runescape.wiki), CC BY-NC-SA 3.0. See DATA-ATTRIBUTION.md.",
        "tasks": tasks,
    }
    os.makedirs(os.path.dirname(OUT_JSON), exist_ok=True)
    with open(OUT_JSON, "w") as f:
        json.dump(out, f, indent=1, ensure_ascii=False, sort_keys=False)
        f.write("\n")

    uniq = {}
    for t, r in sources:
        if t and t not in uniq:
            uniq[t] = r
    with open(OUT_ATTR, "w") as f:
        f.write("# Data attribution\n\n")
        f.write("The monster dataset in `src/main/resources/com/slayersuite/data/monsters.json` is built by `tools/build-dataset.py` from the following pages of the [Old School RuneScape Wiki](https://oldschool.runescape.wiki), retrieved on " + today + ".\n\n")
        f.write("The wiki's text is available under the [Creative Commons Attribution-NonCommercial-ShareAlike 3.0 Unported licence](https://creativecommons.org/licenses/by-nc-sa/3.0/). The dataset is a reformatted and condensed derivative of those pages and is distributed under the same licence. RuneScape and Old School RuneScape are trademarks of Jagex Ltd; the wiki is not affiliated with Jagex.\n\n")
        f.write("Hand-maintained corrections and additions live in `tools/overrides.json` and `src/main/resources/com/slayersuite/data/items.json`.\n\n")
        f.write("| Page | Revision |\n|---|---|\n")
        for t in sorted(uniq):
            f.write(f"| [{t}](https://oldschool.runescape.wiki/w/{urllib.parse.quote(t.replace(' ', '_'))}) | {uniq[t]} |\n")

    with open(OUT_REPORT, "w") as f:
        f.write(f"generated {today}\n{len(tasks)} tasks, {sum(len(t['monsters']) for t in tasks)} monsters, {len(uniq)} wiki pages\n\n")
        for line in report:
            f.write(line + "\n")
    print(f"wrote {OUT_JSON} ({len(tasks)} tasks), {OUT_ATTR}, {OUT_REPORT}", file=sys.stderr)
    for line in report:
        print("  " + line, file=sys.stderr)


if __name__ == "__main__":
    main()
