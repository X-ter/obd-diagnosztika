#!/usr/bin/env python3
"""OBDb (CC BY-SA 4.0) Peugeot jelkészletek összevetése a konvertált PSA-RE adatbázissal.
Csak ellenőrzésre szolgál: OBDb-adat NEM kerül az appba.
Futtatás: python3 tools/psa/crosscheck_obdb.py <psa.dat> <OBDb klónok könyvtára> <riport.md>"""
import gzip, json, glob, sys, collections
db = json.load(gzip.open(sys.argv[1]))
by = collections.defaultdict(list)  # (rq, svc, id) -> [(modell, param)]
for e in db["ecus"]:
    svc = 0x22 if e["proto"] == "UDS" else 0x21
    for it in e["data"]:
        for p in it["p"]:
            by[(e["rq"], svc, it["id"])].append((e["key"], p))
stat = collections.Counter(); lines = []
for f in sorted(glob.glob(f"{sys.argv[2]}/Peugeot-*/signalsets/v3/default.json")):
    car = f.split("/")[-4]
    for c in json.load(open(f)).get("commands", []):
        svc_s, ident = next(iter(c["cmd"].items()))
        key = (int(c["hdr"], 16), int(svc_s, 16), int(ident, 16))
        for s in c["signals"]:
            fm = s["fmt"]; scale = fm.get("mul", 1) / fm.get("div", 1); off = fm.get("add", 0); start = fm.get("bix", 0)
            cands = [(m, p) for m, p in by.get(key, []) if p["s"] == start and p["l"] == fm["len"]]
            if not by.get(key):
                stat["nincs a PSA-RE-ben"] += 1; continue
            if not cands:
                stat["más bitpozíció"] += 1; continue
            same = [m for m, p in cands if abs(p.get("f", 1) - scale) <= 1e-6 * max(1, abs(scale)) and abs(p.get("o", 0) - off) < 0.02]
            diff = [(m, p.get("f", 1), p.get("o", 0)) for m, p in cands if m not in same]
            if same and not diff: stat["minden modellel egyezik"] += 1
            elif same: stat["csak egyes modellekkel egyezik"] += 1
            else: stat["egyik modellel sem egyezik"] += 1
            if diff:
                lines.append(f"| {car} | {c['hdr']} | {svc_s} {ident} | {s.get('name','')} | ×{scale:g} {off:+g} | "
                             + "; ".join(sorted({f'{m.split('/')[-1]}: ×{f:g} {o:+g}' for m, f, o in diff})) + " |")
with open(sys.argv[3], "w", encoding="utf-8") as r:
    r.write("# OBDb ↔ PSA-RE keresztellenőrzés (Peugeot)\n\n")
    r.write("Az OBDb autótípusonként egy skálát ad, a PSA-RE vezérlőmodellenként. Ahol a táblázat eltérést mutat, "
            "ott az OBDb-érték csak bizonyos motorvezérlőkön lenne helyes – ezért az app a PSA-RE vezérlőmodell-szintű "
            "adatait használja, azonosított vezérlő esetén.\n\n")
    for k, v in stat.most_common(): r.write(f"- {k}: {v}\n")
    r.write("\n| OBDb modell | Cím | Kérés | Jel | OBDb skála | Eltérő PSA-RE vezérlőmodellek |\n|---|---|---|---|---|---|\n")
    seen = set()
    for l in lines:
        k = l.split("|")[2:5]
        if tuple(k) in seen: continue
        seen.add(tuple(k)); r.write(l + "\n")
print(dict(stat))
