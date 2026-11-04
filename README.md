# OBD Diagnosztika – Android OBD-II diagnosztikai alkalmazás

Natív Android alkalmazás (Kotlin, Jetpack Compose, Material 3, MVVM) ELM327- és STN-kompatibilis
OBD-II adapterekhez, Bluetooth Classic, Bluetooth LE és Wi-Fi kapcsolattal.

**Letöltés:** a legfrissebb debug APK a [Releases → latest](../../releases/tag/latest) oldalon található
(közvetlen link: `releases/download/latest/obd-diagnosztika-debug.apk`), illetve minden build
Actions-artifactként is elérhető.

## Modulok

| Modul | Tartalom |
|---|---|
| `core:obd` | Tiszta Kotlin OBD-mag: ELM327 munkamenet, válaszértelmezés (CAN 11/29 bit ISO-TP, J1850, ISO 9141, KWP), PID-képletek, hibakódok (Mode 03/07/0A/04), freeze frame (02), Mode 06, Mode 09, readiness, lekérdezési ütemező, kapcsolatkezelő újracsatlakozással, szimulátor. Android-függőség nélkül tesztelhető. |
| `core:data` | Room adatbázis: járművek, hibakód-előzmények, naplózott mérések, műszerfalprofilok. |
| `app` | Compose felület, Bluetooth/BLE/Wi-Fi transportok, DataStore beállítások, magyar szövegek (`res/values/strings.xml`), DTC-leírások (`assets/dtc/hu.json`). |

## Gyártói diagnosztika (Peugeot)

A *Továbbiak → Gyártói diagnosztika – Peugeot* menüben az app cikkszám alapján azonosítja a PSA-vezérlőket, és az
azonosított modell / szoftverváltozat gyártói értékeit (pl. olajnyomás) olvassa, kizárólag olvasó kérésekkel.
Adatforrás: [PSA-RE](https://github.com/prototux/PSA-RE) (Apache 2.0) – részletek: [tools/psa/README.md](tools/psa/README.md).

## Bővítés

- **Új nyelv:** `app/src/main/res/values-xx/strings.xml` és `assets/dtc/xx.json`.
- **Gyártóspecifikus hibakódleírások:** új `DtcDescriptionProvider` a `DtcRepository` láncában.
- **Gyártóspecifikus PID-ek:** a `PidDef`/`SignalDef` modell módonként és fejlécenként bővíthető; az
  ütemező a definíció hossza alapján dolgozik.
- **Szövegek és DTC-adatbázis újragenerálása:** `python3 tools/gen_strings.py`, `python3 tools/gen_dtc.py`.

## Fordítás

A fordítást a GitHub Actions végzi (`.github/workflows/android.yml`): tesztek → debug APK → artifact és
„latest” release. Helyben: `./gradlew :core:obd:test :app:testDebugUnitTest :app:assembleDebug` (JDK 17, Android SDK 35).

## Biztonság

A szakértői terminál csak olvasó szolgáltatásokat enged (fehérlista); az író, vezérlő, programozó és
biztonsági hozzáférést kérő parancsok le vannak tiltva. A hibakód-törlés megerősítést kér.
