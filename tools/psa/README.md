# Peugeot gyártói diagnosztikai adatbázis

Az `app/src/main/assets/mfr/psa_peugeot.dat` a [PSA-RE](https://github.com/prototux/PSA-RE) projekt
(Apache License 2.0, licenc: `app/src/main/assets/mfr/LICENSE-PSA-RE.txt`) `diag/` adataiból készül.

**Módosítások az eredetihez képest:** csak a Peugeot-autókon előforduló, CAN-en (ISO-TP) elérhető UDS / KWP-PSA2000
vezérlők; csak olvasható azonosítók (UDS 0x22 DID, KWP 0x21 LID); rutinok, aktuátortesztek, írás és biztonsági
hozzáférési adatok kihagyva; nyelvek: en, hu, fr; néhány hibás kis- és nagybetűs mértékegység normalizálva
(lásd `UNIT_FIX`); tömör JSON-formátum.

Újragenerálás (a forrás commitja bekerül a kimenetbe):

```
git clone https://github.com/prototux/PSA-RE /tmp/psa-re
python3 tools/psa/convert_psa_re.py /tmp/psa-re app/src/main/assets/mfr/psa_peugeot.dat --report tools/psa/reports/conversion.md
```

Keresztellenőrzés az [OBDb](https://github.com/OBDb) Peugeot-jelkészleteivel (CC BY-SA 4.0; csak ellenőrzés, adat nem kerül át):

```
python3 tools/psa/crosscheck_obdb.py app/src/main/assets/mfr/psa_peugeot.dat <OBDb klónok> tools/psa/reports/obdb_crosscheck.md
```

A PSA-RE nem hivatalos, közösségi projekt, és nem kapcsolódik a Stellantishoz. Az adatok hibásak lehetnek, ezért az app
csak pontosan azonosított vezérlőmodell és szoftverváltozat esetén mutat értéket.
