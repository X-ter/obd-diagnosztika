# Jármű- és ECU-felismerés – audit és architektúra

## 1. Audit: a korábbi gyártóspecifikus felismerés hibái

| # | Probléma | Következmény |
|---|----------|--------------|
| A1 | A „gyártói azonosítás” a PSA-RE adatbázis kb. 150 címét **minden járművön** végigkérdezte (KWP `81` / `10 C0` sessionnyitással együtt), a márkától függetlenül. | Nem PSA-járművön fölösleges, nem dokumentált buszforgalom; a PSA-RE volt az egész rendszer alapja, nem egy forrás. |
| A2 | A VIN-t csak a „Haladó diagnosztika” képernyő olvasta (Mode 09 / 02), a felismerés **nem használta**. | Nem volt márka-/csoportdöntés, ezért nem lehetett jelezni, ha egy forrás nem illik a járműre. |
| A3 | Nem volt szabványos vezérlőazonosítás (ISO 14229 `22 F1xx`, ISO 14230 `1A 8x/9x`, Mode 09 CALID/CVN/ECU-név) a felismerésben. | A hardver- és szoftververzió nem jelent meg; minden azonosítás PSA-specifikus kérésekkel történt. |
| A4 | Ha egy vezérlő az azonosító kérést **elutasította** (NRC), „ismeretlen cikkszámú” vezérlőként jelent meg. | Bármely márka szabványos motorvezérlője (7E0) hamis „ismeretlen PSA-vezérlő” lett. |
| A5 | A 29 bites címzésű adatbázis-modelleket (pl. 18DA60F1) a 11 bites kapcsolat **csendben** próbálta (biztonsági szűrő dobott kivételt). | Rejtett hiba, nem volt naplózva. |
| A6 | Csupa 0 / csupa F „cikkszámok” (kitöltés) az adatbázisban érvényes cikkszámként szerepeltek. | Ugyanazon a címen két modellnél hamis egyezés → téves „nem egyértelmű” eredmény (a generált teszt találta meg). |
| A7 | A forrás címkéje (UDS/KWP) felülírta a valós viselkedést (MED17.4.2, ESP81 stb.). | Javítva: a protokollt az azonosító szolgáltatás dönti el. Ezt most egy, az egész adatbázisra futó invariáns-teszt őrzi. |
| A8 | A szimulátor és a tesztek egy **beégetett** vezérlőt (egy konkrét PSA motorvezérlő, cikkszám, DID-ek) és terepi azonosítókat használtak. | Egy adott vezérlőre „optimalizált” ellenőrzés, ami elfedhette az általános hibákat. |
| A9 | Nem volt indokolt, lépésenkénti napló a profilválasztásról. | Egy választás nem volt visszakövethető a kiolvasott azonosítókra. |
| A10 | Nem volt kimondva, hogy a passzív (broadcast) CAN-üzenetek és a lekérdezett paraméterek külön kezelendők. | Az adatbázis-konverter elvben behúzhatta volna a `buses/` (DBMUXEv broadcast) leírásokat. |

A szabványos OBD-II lekérdezés (Mode 01, `PidPoller`, `ObdClient.readPids`) **nem változott**.

## 2. Az új architektúra

```
ObdClient (változatlan szabványos OBD)
   │
VehicleRecognizer ── 1. protokoll (ATDPN) + válaszoló ECU-k (0100)
   │                 2. VIN: Mode 09/02 → ha nincs: UDS 22 F190 / KWP 1A 90
   │                 3. VinDecoder: WMI → gyártó → BrandGroup, modellév, ellenőrző karakter
   │                 4. StandardEcuIdentifier: Mode 09 CALID/CVN/név + UDS 22 F187…F197, vagy KWP 1A 87…97
   │                 5. csoportdöntés: VIN > felhasználói megadás > NINCS (nem találgat)
   │                 6. ProfileSource-ok: csak a csoportjukba eső járműnél futnak
   │                 7. napló (Step + üzenet), támogatási szint
   ▼
ProfileSource (interfész)
   └─ DbmuxevProfileSource("psa-re")  ← PSA-RE = egy integrált forrás (STELLANTIS_PSA csoport)
          └─ MfrIdentifier: pontos cikkszám-egyezés → Identified / Ambiguous / Unknown
          └─ MfrRegistry / MfrPoller: csak az azonosított modell + változat azonosítói
```

### Döntési szabályok
* **Csoport:** a VIN WMI-je dönt. Ha ismeretlen vagy nincs VIN → `NEEDS_INPUT`: a felhasználó megadhatja a csoportot (naplózva: „felhasználói megadás”), vagy marad a szabványos OBD. Ha a felhasználói megadás ellentmond a VIN-nek, a VIN nyer (naplózva).
* **Profil:** csak akkor választunk, ha a kiolvasott azonosító **egyetlen** modell **egyetlen** változatához tartozik. Több jelölt → `Ambiguous`, ismeretlen → `Unknown`; mindkettő korlátozott támogatás, gyártói érték nélkül.
* **Elutasított azonosító kérés** (csak NRC) → nem detekció („ezzel a forrással nem azonosítható”).
* **Biztonságkritikus vezérlők** (fék, kormány, légzsák, váltó, hajtás): azonosíthatók, de folyamatos lekérdezés soha.
* **Csak olvasás:** szabványos azonosítás `22 F1xx` / `1A 8x-9x`, gyártói forrás `22 xxxx` / `21 xx` / `1A xx` + session `81/82/10 C0/10 81`. Biztonsági feloldás, írás, kódolás, rutin, programozás nincs. Hibakód-törlés csak kifejezett, megerősített felhasználói kérésre.

### Passzív és aktív adatok
`DataOrigin`: `STANDARD_ACTIVE`, `MANUFACTURER_ACTIVE`, `CALCULATED`, `PASSIVE_BROADCAST`. Az adatbázis fejléce `"kind": "active-diagnostic"`; a betöltő ezt ellenőrzi. A PSA-RE `buses/` broadcast leírásai szándékosan nincsenek importálva. Passzív CAN-figyelés jelenleg nem fut (az adapter kérés–válasz módban dolgozik, és a diagnosztikai aljzaton a gateway jellemzően nem adja ki a belső buszokat); ha később lesz, külön forrásként és külön jelölve jelenik meg.

### Új forrás hozzáadása
Egy `ProfileSource` implementáció a saját `groups` halmazával (pl. egy másik DBMUXEv-formátumú adatbázis egy másik csoportra), majd felvétel a `MfrRepository.sources` listába. A felismerési logika nem változik.

## 3. Tesztek
* `RecognitionTest`: VIN-elemzés minden WMI-re (generált VIN, helyes ellenőrző karakter); ismeretlen márka → `NEEDS_INPUT` és nincs gyártói buszforgalom; felhasználói megadás; VIN-ből automatikus aktiválás + ellentmondó megadás; más csoport → forrás kihagyva; VIN nélküli jármű; ismeretlen vezérlő; KWP-s szabványos ECU (`1A`); minden felismerés csak olvasó parancsot küld (több véletlen jármű); csak aktív diagnosztikai jelek.
* `MfrTest`: 24 véletlenszerűen generált vezérlő (UDS/KWP, sok csomópont) pontos azonosítása; több vezérlő egyszerre; ismeretlen és nem egyértelmű cikkszám; biztonságkritikus vezérlők; írásvédelem; protokoll–azonosítás invariáns az egész adatbázisra; hibakód olvasás/törlés mindkét protokollon; lassú vezérlő.
* A szimulált gyártói vezérlőket a `SimulatedManufacturerEcu` az adatbázis leírásából **generálja**; a demó mód minden kapcsolódáskor véletlenszerűen választ.
