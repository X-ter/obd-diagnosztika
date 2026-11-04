#!/usr/bin/env python3
"""
PSA-RE (https://github.com/prototux/PSA-RE, Apache-2.0) → az app gyártói diagnosztikai adatbázisa.

Futtatás:  python3 tools/psa/convert_psa_re.py <PSA-RE könyvtár> <kimenet.json.gz> [--brand peugeot] [--report riport.md]

Mit vesz át (és mit NEM):
  * csak a CAN-en (ISO-TP) elérhető vezérlőket, UDS vagy KWP-PSA2000 protokollal
    (a K-line-os AEE2001 és a VAN-os vezérlők nem kerülnek be)
  * csak OLVASÁST: UDS 0x22 DID-ek és KWP 0x21 LID-ek, amelyek olvashatók (access: read / read-write)
  * NEM kerülnek át: rutinok, aktuátortesztek, írás, biztonsági hozzáférés (seed/key), session-váltás konfigurációhoz
  * csak az adott márka autóin előforduló vezérlőmodellek (alapértelmezés: Peugeot)

Minden kihagyott vagy hibás definíciót a riport listáz; a kimenet a forrás commitját is tartalmazza.
"""
import argparse, glob, gzip, json, os, re, subprocess, sys, collections

try:
    import yaml
except ImportError:
    sys.exit("PyYAML szükséges: pip install pyyaml")

LANGS = ("en", "hu", "fr")

# A forrásban egyes egységek hibás kis- és nagybetűvel szerepelnek (fordítási hiba); ezeket normalizáljuk.
UNIT_FIX = {
    "Mm": "mm", "Cm": "cm", "M": "m", "M/s": "m/s", "Mg/s": "mg/s", "Cm²": "cm²", "M3/h": "m³/h", "Metres": "m",
    "Km(s)": "km", "Litres/100 km": "L/100km", "L/100 Kms": "L/100km", "N·m": "Nm", "DB": "dB", "DB/V": "dB/V",
    "DBµV": "dBµV", "G/l": "g/L",
}
SAFE_SERVICES = {"UDS": 0x22, "KWP-PSA2000": 0x21}


def text(m):
    """Többnyelvű szöveg → csak a használt nyelvek."""
    if not isinstance(m, dict):
        return {"en": str(m)} if m else None
    out = {k: m[k] for k in LANGS if m.get(k)}
    return out or None


BITS_RE = re.compile(r"^(\d+)\.([0-7])(?:-(?:(\d+)\.([0-7])|n))?$")


def parse_bits(bits, length):
    """'1.7-2.0' → (kezdő bitpozíció a payloadban, hossz bitben); 'n' = a payload végéig. Hiba esetén None."""
    m = BITS_RE.match(str(bits).strip())
    if not m:
        return None
    sb, si = int(m.group(1)), int(m.group(2))
    if m.group(3) is None:
        if "-n" in str(bits):
            if length is None:
                return None
            eb, ei = length, 0
        else:
            eb, ei = sb, si
    else:
        eb, ei = int(m.group(3)), int(m.group(4))
    start = (sb - 1) * 8 + (7 - si)
    end = (eb - 1) * 8 + (7 - ei)
    n = end - start + 1
    if n <= 0:
        return None
    return start, n


def conv_params(params, length, problems, where):
    out = []
    for name, p in (params or {}).items():
        if not isinstance(p, dict):
            continue
        if p.get("unused"):
            continue
        pb = parse_bits(p.get("bits", ""), length)
        if pb is None:
            problems.append(f"{where}/{name}: értelmezhetetlen bitpozíció {p.get('bits')!r}")
            continue
        start, n = pb
        if length is not None and start + n > length * 8:
            problems.append(f"{where}/{name}: a bitek ({p.get('bits')}) túlnyúlnak a {length} bájtos adaton")
            continue
        t = p.get("type", "uint")
        if t not in ("uint", "sint", "bool", "enum", "bcd", "str", "bytes", "float"):
            problems.append(f"{where}/{name}: ismeretlen típus {t}")
            continue
        if t == "float" and n not in (32, 64):
            problems.append(f"{where}/{name}: float csak 32/64 bites lehet")
            continue
        q = {"n": name, "s": start, "l": n, "t": t}
        lab = text(p.get("comment"))
        if lab:
            q["c"] = lab
        for k_src, k_dst in (("factor", "f"), ("offset", "o"), ("min", "mn"), ("max", "mx")):
            if p.get(k_src) is not None:
                q[k_dst] = p[k_src]
        if p.get("units"):
            q["u"] = UNIT_FIX.get(str(p["units"]), str(p["units"]))
        if p.get("invalid") is not None:
            q["inv"] = p["invalid"] if isinstance(p["invalid"], list) else [p["invalid"]]
        if p.get("byte_order") == "little_endian":
            q["le"] = True
        vals = p.get("values")
        if isinstance(vals, dict):
            vv = {}
            for raw, lab in vals.items():
                if isinstance(lab, dict) and (lab.get("unused") or not text(lab)):
                    continue
                try:
                    vv[str(int(raw))] = text(lab)
                except (TypeError, ValueError):
                    pass
            if vv:
                q["v"] = vv
        out.append(q)
    return out


def load_brand_cars(root, brand):
    cars = set()
    for f in glob.glob(f"{root}/cars/*.yml"):
        y = yaml.safe_load(open(f, encoding="utf-8")) or {}
        brands = [str(b).lower() for b in (y.get("brand") or [])]
        if brand in brands:
            cars.add(os.path.splitext(os.path.basename(f))[0])
            for code in (y.get("codes") or {}):
                cars.add(str(code))
    return cars


def parse_ident(idf):
    if not idf or not idf.get("request") or not idf.get("part_number"):
        return None
    req = idf["request"].replace(" ", "").upper()
    if not re.fullmatch(r"(22[0-9A-F]{4}|21[0-9A-F]{2}|1A[0-9A-F]{2})", req):
        return None  # csak olvasó azonosító kérés megengedett
    pb = parse_bits(idf["part_number"], None)
    if pb is None or pb[0] % 8 or pb[1] % 8:
        return None
    return {"req": req, "byte": pb[0] // 8, "len": pb[1] // 8}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("root")
    ap.add_argument("out")
    ap.add_argument("--brand", default="peugeot")
    ap.add_argument("--report")
    a = ap.parse_args()
    root = a.root
    try:
        commit = subprocess.check_output(["git", "-C", root, "rev-parse", "HEAD"], text=True).strip()
    except Exception:
        commit = "unknown"

    brand_cars = load_brand_cars(root, a.brand)
    problems, skipped = [], collections.Counter()
    ecus = []
    part_index = collections.defaultdict(set)  # (req_id, ident req, part) -> modell kulcsok

    for ecu_file in sorted(glob.glob(f"{root}/diag/*/*/ecu.yml") + glob.glob(f"{root}/diag/*/*/*/ecu.yml")):
        d = os.path.dirname(ecu_file)
        rel = os.path.relpath(d, f"{root}/diag")
        if rel.startswith("protocols"):
            continue
        y = yaml.safe_load(open(ecu_file, encoding="utf-8")) or {}
        cars = {str(c) for c in (y.get("cars") or [])}
        if not (cars & brand_cars):
            skipped["más márka"] += 1
            continue
        addr = None
        for ad in y.get("addressing") or []:
            if ad.get("transport") == "can-tp" and ad.get("protocol") in SAFE_SERVICES and ad.get("request_id") is not None:
                addr = ad
                break
        if addr is None:
            skipped["nem CAN / nem UDS-KWP"] += 1
            continue
        ident = parse_ident(y.get("identification"))
        variants = {}
        for vid, v in (y.get("variants") or {}).items():
            if isinstance(v, dict):
                parts = [str(p).upper() for p in (v.get("parts") or [])]
                if parts:
                    variants[str(vid)] = parts
        if ident is None or not variants:
            skipped["nincs azonosítás / cikkszám"] += 1
            continue
        proto = addr["protocol"]
        svc = SAFE_SERVICES[proto]
        sub = "dids" if svc == 0x22 else "lids"
        data = []
        for f in sorted(glob.glob(f"{d}/{sub}/*.yml")):
            base = os.path.basename(f)
            if "_" in base:  # pl. _IDENT (0x1A) – külön szolgáltatás, kimarad
                skipped["speciális azonosító fájl"] += 1
                continue
            dd = yaml.safe_load(open(f, encoding="utf-8")) or {}
            access = str(dd.get("access", "read"))
            if "read" not in access:
                skipped["nem olvasható"] += 1
                continue
            if dd.get("mapped"):
                skipped["mapped (táblázatos) – még nem támogatott"] += 1
                continue
            ident_val = dd.get("did") if svc == 0x22 else dd.get("lid")
            if ident_val is None:
                continue
            length = dd.get("length")
            where = f"{rel}/{sub}/{base}"
            params = conv_params(dd.get("params"), length, problems, where)
            if not params:
                skipped["paraméter nélküli azonosító"] += 1
                continue
            item = {"id": int(ident_val), "k": dd.get("kind", "other"), "p": params}
            if dd.get("name"):
                item["nm"] = dd["name"]
            lab = text(dd.get("display_name"))
            if lab:
                item["d"] = lab
            if length is not None:
                item["len"] = length
            if dd.get("variants"):
                item["var"] = [str(v) for v in dd["variants"]]
            alts = []
            for alt in dd.get("alternatives") or []:
                if not isinstance(alt, dict) or not alt.get("variants") or not alt.get("params"):
                    continue
                ap_ = conv_params(alt["params"], alt.get("length", length), problems, where + " (alternatíva)")
                if ap_:
                    alts.append({"var": [str(v) for v in alt["variants"]], "p": ap_, **({"len": alt["length"]} if alt.get("length") else {})})
            if alts:
                item["alt"] = alts
            data.append(item)
        if not data:
            skipped["nincs olvasható adat"] += 1
            continue
        key = rel.replace(os.sep, "/")
        e = {
            "key": key, "node": y.get("node"), "model": y.get("model", y.get("node")),
            "proto": proto, "rq": addr["request_id"], "rs": addr["response_id"],
            "ident": ident, "variants": variants, "cars": sorted(cars & brand_cars), "data": data,
        }
        dn = text(y.get("display_name"))
        if dn:
            e["d"] = dn
        ecus.append(e)
        for parts in variants.values():
            for p in parts:
                part_index[(addr["request_id"], ident["req"], p)].add(key)

    # Ugyanaz a cikkszám több modellnél ugyanazon a címen: ott az azonosítás nem egyértelmű
    ambiguous = {k: sorted(v) for k, v in part_index.items() if len(v) > 1}
    for e in ecus:
        amb = sorted({p for (rq, req, p), keys in ambiguous.items() if rq == e["rq"] and req == e["ident"]["req"] and e["key"] in keys})
        if amb:
            e["ambiguousParts"] = amb

    nodes = {}
    for f in glob.glob(f"{root}/nodes/*.yml"):
        y = yaml.safe_load(open(f, encoding="utf-8")) or {}
        for name, n in (y.get("nodes") or {}).items() if isinstance(y.get("nodes"), dict) else []:
            if isinstance(n, dict) and n.get("description") and name not in nodes:
                nodes[name] = text(n.get("description"))

    out = {
        "format": 1,
        "source": {"name": "PSA-RE", "url": "https://github.com/prototux/PSA-RE", "commit": commit, "license": "Apache-2.0"},
        "brand": a.brand,
        "nodes": nodes,
        "ecus": ecus,
    }
    raw = json.dumps(out, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    os.makedirs(os.path.dirname(os.path.abspath(a.out)), exist_ok=True)
    with gzip.open(a.out, "wb", compresslevel=9) as fh:
        fh.write(raw)

    n_items = sum(len(e["data"]) for e in ecus)
    n_params = sum(len(i["p"]) for e in ecus for i in e["data"])
    n_live = sum(1 for e in ecus for i in e["data"] if i["k"] == "live")
    summary = (f"PSA-RE {commit[:8]} | márka: {a.brand} | ECU-modellek: {len(ecus)} | azonosítók: {n_items} "
               f"(élő: {n_live}) | paraméterek: {n_params} | nem egyértelmű cikkszám: {len(ambiguous)} | "
               f"méret: {len(raw)/1e6:.1f} MB → {os.path.getsize(a.out)/1e6:.2f} MB (gzip)")
    print(summary)
    if a.report:
        with open(a.report, "w", encoding="utf-8") as r:
            r.write(f"# PSA-RE konverziós riport\n\n{summary}\n\n## Kihagyva\n\n")
            for k, v in skipped.most_common():
                r.write(f"- {k}: {v}\n")
            r.write(f"\n## Hibás definíciók ({len(problems)}) – nem kerültek be\n\n")
            for p in problems:
                r.write(f"- {p}\n")
            r.write(f"\n## Nem egyértelmű cikkszámok ({len(ambiguous)})\n\nEzeknél az app nem választ modellt automatikusan.\n\n")
            for (rq, req, p), keys in sorted(ambiguous.items()):
                r.write(f"- 0x{rq:03X} {req} {p}: {', '.join(keys)}\n")


if __name__ == "__main__":
    main()
