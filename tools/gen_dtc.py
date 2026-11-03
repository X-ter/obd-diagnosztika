#!/usr/bin/env python3
"""A szabványos (generikus) OBD-II hibakódok magyar leírásainak generálása: app/src/main/assets/dtc/hu.json
Csak a SAE J2012 / ISO 15031-6 szerinti szabványos jelentés; okok és teendők csak általánosan elfogadott esetekben."""
import json, os

D = {}
def c(code, desc, causes=None, tips=None):
    e = {"d": desc}
    if causes: e["c"] = causes
    if tips: e["t"] = tips
    D[code] = e

LEAN = ["Fals levegő (szívócső-tömítés, vákuumcső, PCV) a légtömegmérő után", "Szennyezett vagy hibás légtömegmérő (MAF)",
        "Alacsony üzemanyagnyomás (szivattyú, szűrő, nyomásszabályozó)", "Eltömődött vagy hibás befecskendezők", "Kipufogó-szivárgás a lambdaszonda előtt"]
LEAN_T = ["Nézd meg élő adatban a rövid és hosszú távú korrekciót alapjáraton és 2500/min-en", "Füstteszttel keress fals levegőt",
          "Mérd meg az üzemanyagnyomást"]
RICH = ["Túl magas üzemanyagnyomás vagy szivárgó befecskendező", "Hibás légtömegmérő vagy MAP-érzékelő", "Eltömődött légszűrő",
        "Hibás hűtőfolyadék-hőmérséklet-érzékelő (hideg motort jelez)", "EVAP-öblítőszelep nyitva ragadt"]
MISFIRE = ["Hibás gyújtógyertya vagy gyújtótekercs", "Befecskendező-hiba", "Alacsony kompresszió (szelep, gyűrű, hengerfejtömítés)", "Fals levegő", "Vezetékezési hiba"]
MISFIRE_T = ["Cseréld fel az érintett henger tekercsét egy másikkal, és nézd meg, vándorol-e a hiba", "Ellenőrizd a gyertyát", "Szükség esetén végezz kompressziómérést"]

c("P0010", "„A” vezérműtengely-állító áramkör (1. hengersor)", ["Hibás vezérműtengely-állító mágnesszelep", "Vezetékezési vagy csatlakozóhiba"])
c("P0011", "„A” vezérműtengely helyzete – túl korai időzítés vagy rendszerhiba (1. hengersor)",
  ["Alacsony olajszint vagy nem megfelelő viszkozitású olaj", "Szennyezett vagy beragadt állítószelep (OCV)", "Vezérműlánc nyúlása"],
  ["Ellenőrizd az olajszintet és az olaj állapotát", "Élő adatban hasonlítsd össze a cél- és tényleges vezérlési szöget (gyártóspecifikus)"])
c("P0012", "„A” vezérműtengely helyzete – túl késői időzítés (1. hengersor)", ["Alacsony olajnyomás", "Beragadt állítószelep", "Vezérműlánc nyúlása"])
c("P0016", "Főtengely- és vezérműtengely-helyzet összefüggési hiba (1. hengersor, „A” érzékelő)",
  ["Vezérműlánc vagy -szíj nyúlása, átugrása", "Hibás főtengely- vagy vezérműtengely-érzékelő", "Vezérműtengely-állító hiba"],
  ["Ne használd hosszan a járművet, amíg a vezérlés állapota nincs ellenőrizve"])
c("P0030", "Lambdaszonda-fűtés vezérlő áramkör (1. hengersor, 1. szonda)", ["Szakadt fűtőszál a szondában", "Kiégett biztosíték", "Vezetékezési hiba"])
c("P0036", "Lambdaszonda-fűtés vezérlő áramkör (1. hengersor, 2. szonda)", ["Szakadt fűtőszál a szondában", "Kiégett biztosíték", "Vezetékezési hiba"])
c("P0068", "Szívócsőnyomás / légtömegáram – fojtószelep-helyzet összefüggési hiba", ["Fals levegő", "Hibás MAF- vagy MAP-érzékelő", "Fojtószelep-hiba"])
c("P0087", "Üzemanyag-rail / rendszernyomás túl alacsony",
  ["Eltömődött üzemanyagszűrő", "Gyenge alacsony nyomású szivattyú", "Hibás nagynyomású szivattyú vagy nyomásszabályozó", "Szivárgás"],
  ["Élő adatban hasonlítsd össze az előírt és a tényleges rail-nyomást (ha a jármű megadja)"])
c("P0088", "Üzemanyag-rail / rendszernyomás túl magas", ["Hibás nyomásszabályozó szelep", "Hibás rail-nyomásérzékelő"])
c("P0089", "Üzemanyagnyomás-szabályozó teljesítményhiba", ["Hibás nyomásszabályozó", "Szennyezett üzemanyag"])
c("P0093", "Nagy üzemanyag-szivárgás észlelve", ["Üzemanyag-szivárgás a nagynyomású körben", "Hibás rail-nyomásérzékelő"],
  ["Ellenőrizd szemrevételezéssel a nagynyomású vezetékeket – tűzveszély!"])
c("P0100", "Légtömegmérő (MAF) áramkör hibája", ["Csatlakozó- vagy vezetékhiba", "Hibás légtömegmérő"])
c("P0101", "Légtömegmérő (MAF) – mérési tartomány / teljesítmény hiba",
  ["Szennyezett MAF-érzékelő", "Fals levegő a MAF után", "Eltömődött légszűrő", "Kipufogó-ellennyomás"],
  ["Alapjáraton a MAF értéke jellemzően 2–7 g/s a motortól függően; hasonlítsd a gyári értékhez", "Ellenőrizd a szívóoldali tömítettséget"])
c("P0102", "Légtömegmérő (MAF) – alacsony bemenő jel", ["Szakadt vagy zárlatos vezeték", "Hibás érzékelő", "Hiányzó tápfeszültség"])
c("P0103", "Légtömegmérő (MAF) – magas bemenő jel", ["Zárlat tápfeszültségre", "Hibás érzékelő"])
c("P0106", "Szívócső abszolút nyomása (MAP) / barometrikus nyomás – tartomány / teljesítmény", ["Vákuumszivárgás", "Hibás MAP-érzékelő", "Eldugult érzékelőcső"])
c("P0107", "Szívócsőnyomás (MAP) / barometrikus nyomás – alacsony bemenő jel", ["Szakadt jelvezeték", "Hibás érzékelő"])
c("P0108", "Szívócsőnyomás (MAP) / barometrikus nyomás – magas bemenő jel", ["Zárlat", "Hibás érzékelő", "Vákuumcső leesett"])
c("P0110", "Szívólevegő-hőmérséklet érzékelő áramkör", ["Vezeték- vagy csatlakozóhiba", "Hibás érzékelő"])
c("P0112", "Szívólevegő-hőmérséklet érzékelő – alacsony bemenő jel", ["Zárlat a testre", "Hibás érzékelő"])
c("P0113", "Szívólevegő-hőmérséklet érzékelő – magas bemenő jel", ["Szakadt vezeték vagy kontakthiba", "Hibás érzékelő"],
  ["Élő adatban −40 °C körüli érték szakadásra utal"])
c("P0115", "Hűtőfolyadék-hőmérséklet érzékelő áramkör", ["Vezeték- vagy csatlakozóhiba", "Hibás érzékelő"])
c("P0116", "Hűtőfolyadék-hőmérséklet érzékelő – tartomány / teljesítmény", ["Hibás érzékelő", "Hibás termosztát"])
c("P0117", "Hűtőfolyadék-hőmérséklet érzékelő – alacsony bemenő jel", ["Zárlat a testre", "Hibás érzékelő"])
c("P0118", "Hűtőfolyadék-hőmérséklet érzékelő – magas bemenő jel", ["Szakadt vezeték", "Hibás érzékelő"])
c("P0120", "Fojtószelep / pedál helyzetérzékelő „A” áramkör", ["Vezeték- vagy csatlakozóhiba", "Hibás érzékelő"])
c("P0121", "Fojtószelep / pedál helyzetérzékelő „A” – tartomány / teljesítmény", ["Kopott vagy hibás érzékelő", "Szennyezett fojtószelep-ház"])
c("P0122", "Fojtószelep / pedál helyzetérzékelő „A” – alacsony bemenő jel", ["Szakadt vezeték", "Hibás érzékelő"])
c("P0123", "Fojtószelep / pedál helyzetérzékelő „A” – magas bemenő jel", ["Zárlat", "Hibás érzékelő"])
c("P0125", "A hűtőfolyadék hőmérséklete nem elegendő a zárt hurkú üzemanyag-szabályozáshoz",
  ["Nyitva ragadt termosztát", "Hibás hűtőfolyadék-hőmérséklet érzékelő", "Alacsony hűtőfolyadék-szint"])
c("P0128", "A hűtőfolyadék hőmérséklete a termosztát szabályozási hőmérséklete alatt van",
  ["Nyitva ragadt vagy hibás termosztát", "Hibás hűtőfolyadék-hőmérséklet érzékelő", "Folyamatosan működő hűtőventilátor"],
  ["Élő adatban kövesd a bemelegedést: a motor 10–15 perc alatt jellemzően 80–95 °C-ra melegszik"])
c("P0130", "Lambdaszonda áramkör (1. hengersor, 1. szonda)", ["Hibás szonda", "Vezetékezési hiba", "Kipufogó-szivárgás"])
c("P0131", "Lambdaszonda – alacsony feszültség (1. hengersor, 1. szonda)", ["Szegény keverék", "Fals levegő", "Hibás szonda", "Zárlat a testre"])
c("P0132", "Lambdaszonda – magas feszültség (1. hengersor, 1. szonda)", ["Dús keverék", "Zárlat a tápfeszültségre", "Hibás szonda"])
c("P0133", "Lambdaszonda – lassú válasz (1. hengersor, 1. szonda)", ["Elöregedett vagy szennyezett szonda", "Kipufogó-szivárgás", "Fals levegő"],
  ["Élő adatban egy jó keskenysávú szonda zárt hurokban gyorsan, kb. 0,1–0,9 V között változik"])
c("P0134", "Lambdaszonda – nincs aktivitás (1. hengersor, 1. szonda)", ["Hibás szonda", "Szakadt vezeték", "Hibás szondafűtés"])
c("P0135", "Lambdaszonda-fűtés áramkör (1. hengersor, 1. szonda)", ["Szakadt fűtőszál", "Kiégett biztosíték", "Vezetékezési hiba"])
c("P0136", "Lambdaszonda áramkör (1. hengersor, 2. szonda)", ["Hibás szonda", "Vezetékezési hiba"])
c("P0137", "Lambdaszonda – alacsony feszültség (1. hengersor, 2. szonda)", ["Kipufogó-szivárgás", "Hibás szonda"])
c("P0138", "Lambdaszonda – magas feszültség (1. hengersor, 2. szonda)", ["Zárlat", "Hibás szonda", "Dús keverék"])
c("P0141", "Lambdaszonda-fűtés áramkör (1. hengersor, 2. szonda)", ["Szakadt fűtőszál", "Kiégett biztosíték", "Vezetékezési hiba"])
c("P0150", "Lambdaszonda áramkör (2. hengersor, 1. szonda)", ["Hibás szonda", "Vezetékezési hiba"])
c("P0151", "Lambdaszonda – alacsony feszültség (2. hengersor, 1. szonda)", ["Szegény keverék", "Fals levegő", "Hibás szonda"])
c("P0153", "Lambdaszonda – lassú válasz (2. hengersor, 1. szonda)", ["Elöregedett szonda", "Kipufogó-szivárgás"])
c("P0155", "Lambdaszonda-fűtés áramkör (2. hengersor, 1. szonda)", ["Szakadt fűtőszál", "Kiégett biztosíték"])
c("P0161", "Lambdaszonda-fűtés áramkör (2. hengersor, 2. szonda)", ["Szakadt fűtőszál", "Kiégett biztosíték"])
c("P0171", "A rendszer túl szegény (1. hengersor)", LEAN, LEAN_T)
c("P0172", "A rendszer túl dús (1. hengersor)", RICH)
c("P0174", "A rendszer túl szegény (2. hengersor)", LEAN, LEAN_T)
c("P0175", "A rendszer túl dús (2. hengersor)", RICH)
c("P0190", "Üzemanyag-rail nyomásérzékelő „A” áramkör", ["Vezeték- vagy csatlakozóhiba", "Hibás érzékelő"])
c("P0191", "Üzemanyag-rail nyomásérzékelő „A” – tartomány / teljesítmény", ["Hibás érzékelő", "Üzemanyag-ellátási hiba"])
c("P0192", "Üzemanyag-rail nyomásérzékelő „A” – alacsony bemenő jel", ["Szakadt vezeték", "Hibás érzékelő"])
c("P0193", "Üzemanyag-rail nyomásérzékelő „A” – magas bemenő jel", ["Zárlat", "Hibás érzékelő"])
c("P0200", "Befecskendező áramkör / nyitott", ["Vezetékezési hiba", "Hibás befecskendező", "Vezérlőegység-kimenet hibája"])
for n in range(1, 9):
    c(f"P020{n}", f"Befecskendező áramkör / nyitott – {n}. henger", ["Szakadt vezeték vagy csatlakozóhiba", "Hibás befecskendező tekercs"],
      ["Mérd meg a befecskendező tekercsellenállását, és hasonlítsd össze a többi hengerével"])
c("P0217", "Motor túlmelegedés", ["Alacsony hűtőfolyadék-szint", "Hibás termosztát vagy vízpumpa", "Hűtőventilátor-hiba", "Eltömődött hűtő"],
  ["Azonnal állítsd le a motort, és hagyd lehűlni – a hűtőrendszer forró állapotban ne nyisd ki!"])
c("P0234", "Turbó- / kompresszortúltöltés", ["Beragadt waste-gate vagy VGT-mechanizmus", "Hibás töltőnyomás-szabályozó szelep", "Hibás töltőnyomás-érzékelő"])
c("P0235", "Töltőnyomás-érzékelő „A” áramkör", ["Vezeték- vagy csatlakozóhiba", "Hibás érzékelő"])
c("P0236", "Töltőnyomás-érzékelő „A” – tartomány / teljesítmény", ["Hibás érzékelő", "Szivárgás a töltőlevegő-rendszerben"])
c("P0237", "Töltőnyomás-érzékelő „A” – alacsony bemenő jel", ["Szakadt vezeték", "Hibás érzékelő"])
c("P0238", "Töltőnyomás-érzékelő „A” – magas bemenő jel", ["Zárlat", "Hibás érzékelő"])
c("P0299", "Turbó- / kompresszor – alacsony töltőnyomás",
  ["Szivárgás a töltőlevegő-csövekben vagy az intercoolerben", "Hibás töltőnyomás-szabályozás (vákuumdoboz, szelep)", "Turbófeltöltő-hiba", "Eltömődött részecskeszűrő vagy katalizátor"],
  ["Ellenőrizd a töltőlevegő-rendszer tömítettségét", "Ha a jármű támogatja, hasonlítsd össze az előírt és a tényleges töltőnyomást"])
c("P0300", "Véletlenszerű vagy több hengeres gyújtáskimaradás", MISFIRE + ["Szegény keverék"], MISFIRE_T)
for n in range(1, 9):
    c(f"P030{n}", f"Gyújtáskimaradás – {n}. henger", MISFIRE, MISFIRE_T)
c("P0325", "Kopogásérzékelő 1. áramkör (1. hengersor vagy önálló érzékelő)", ["Vezetékezési hiba", "Hibás vagy lazán rögzített érzékelő"])
c("P0327", "Kopogásérzékelő 1. – alacsony bemenő jel", ["Vezetékezési hiba", "Nem megfelelő meghúzási nyomaték", "Hibás érzékelő"])
c("P0335", "Főtengely-helyzetérzékelő „A” áramkör", ["Hibás érzékelő", "Sérült jeladó kerék", "Vezetékezési hiba"],
  ["Ez a hiba indítási nehézséget vagy leállást okozhat"])
c("P0336", "Főtengely-helyzetérzékelő „A” – tartomány / teljesítmény", ["Szennyezett vagy sérült jeladó kerék", "Hibás érzékelő", "Laza csatlakozó"])
c("P0340", "Vezérműtengely-helyzetérzékelő „A” áramkör (1. hengersor)", ["Hibás érzékelő", "Vezetékezési hiba"])
c("P0341", "Vezérműtengely-helyzetérzékelő „A” – tartomány / teljesítmény (1. hengersor)", ["Vezérlés átugrása", "Hibás érzékelő", "Sérült jeladó"])
c("P0380", "Izzítógyertya / izzítás „A” áramkör", ["Hibás izzítógyertya", "Hibás izzítórelé vagy -vezérlő", "Vezetékezési hiba"])
c("P0400", "Kipufogógáz-visszavezetés (EGR) áramlási hiba", ["Elkokszosodott EGR-szelep vagy -csatorna", "Hibás EGR-szelep"])
c("P0401", "Kipufogógáz-visszavezetés (EGR) – elégtelen áramlás", ["Elkokszosodott EGR-csatornák vagy szelep", "Hibás EGR-szelep vagy -vezérlés", "Hibás nyomás- vagy helyzetérzékelő"])
c("P0402", "Kipufogógáz-visszavezetés (EGR) – túlzott áramlás", ["Nyitva ragadt EGR-szelep", "Hibás vezérlés"])
c("P0403", "EGR „A” vezérlő áramkör", ["Vezetékezési hiba", "Hibás EGR-szelep tekercs"])
c("P0404", "EGR „A” vezérlés – tartomány / teljesítmény", ["Szennyezett, akadozó EGR-szelep", "Hibás helyzetérzékelő"])
c("P0405", "EGR „A” helyzetérzékelő – alacsony bemenő jel", ["Vezetékezési hiba", "Hibás érzékelő"])
c("P0410", "Szekunderlevegő-befúvó rendszer hibája", ["Hibás szekunderlevegő-szivattyú", "Beragadt visszacsapó szelep", "Relé- vagy biztosítékhiba"])
c("P0411", "Szekunderlevegő-befúvó rendszer – helytelen áramlás", ["Eltömődött csatornák", "Hibás szelep vagy szivattyú"])
c("P0420", "A katalizátor hatásfoka a küszöbérték alatt (1. hengersor)",
  ["Elöregedett vagy sérült katalizátor", "Kipufogó-szivárgás", "Hibás lambdaszonda (főleg a katalizátor utáni)", "Gyújtáskimaradás vagy keverékhiba okozta károsodás"],
  ["Előbb javítsd a gyújtás- és keverékhibákat, mert ezek a katalizátort is károsítják", "Élő adatban a katalizátor utáni szonda jelének stabilnak kell lennie"])
c("P0430", "A katalizátor hatásfoka a küszöbérték alatt (2. hengersor)",
  ["Elöregedett vagy sérült katalizátor", "Kipufogó-szivárgás", "Hibás lambdaszonda"])
c("P0440", "Párolgásiemisszió-szabályozó (EVAP) rendszer hibája", ["Szivárgás", "Hibás öblítő- vagy szellőztetőszelep"])
c("P0441", "EVAP – helytelen öblítési áramlás", ["Hibás öblítőszelep", "Eltömődött vagy leesett cső"])
c("P0442", "EVAP – kis szivárgás észlelve", ["Rosszul záró tanksapka", "Repedt EVAP-cső", "Szivárgó szelep vagy aktívszén-tartály"],
  ["Először ellenőrizd a tanksapka tömítését és zárását"])
c("P0443", "EVAP öblítőszelep vezérlő áramkör", ["Vezetékezési hiba", "Hibás szelep"])
c("P0446", "EVAP szellőztetőszelep vezérlő áramkör", ["Vezetékezési hiba", "Hibás vagy elszennyeződött szelep"])
c("P0455", "EVAP – nagy szivárgás észlelve", ["Hiányzó vagy laza tanksapka", "Leesett EVAP-cső", "Hibás szelep"], ["Először ellenőrizd a tanksapkát"])
c("P0456", "EVAP – nagyon kis szivárgás észlelve", ["Tanksapka-tömítés", "Apró repedés a csövekben"], ["Füstteszttel lokalizálható"])
c("P0457", "EVAP – szivárgás észlelve (laza vagy hiányzó tanksapka)", ["Laza, hiányzó vagy rossz tanksapka"])
c("P0461", "Üzemanyagszint-érzékelő „A” – tartomány / teljesítmény", ["Hibás szintjeladó", "Vezetékezési hiba"])
c("P0480", "Hűtőventilátor 1. vezérlő áramkör", ["Hibás ventilátorrelé vagy -vezérlő", "Vezetékezési hiba", "Hibás ventilátormotor"])
c("P0500", "Járműsebesség-érzékelő „A” hiba", ["Hibás sebességjeladó", "ABS-kerékérzékelő-hiba (ha onnan származik a jel)", "Vezetékezési hiba"])
c("P0505", "Alapjárat-szabályozó rendszer hibája", ["Szennyezett fojtószelep-ház vagy alapjárati szelep", "Fals levegő"])
c("P0506", "Alapjárat-szabályozó rendszer – a fordulatszám alacsonyabb az elvártnál", ["Szennyezett fojtószelep-ház", "Eltömődött légszűrő"])
c("P0507", "Alapjárat-szabályozó rendszer – a fordulatszám magasabb az elvártnál", ["Fals levegő", "Szennyezett vagy beragadt fojtószelep"])
c("P0562", "Rendszerfeszültség alacsony", ["Gyenge akkumulátor", "Hibás generátor vagy feszültségszabályzó", "Rossz testkapcsolat"],
  ["Járó motornál a töltőfeszültség jellemzően 13,8–14,8 V"])
c("P0563", "Rendszerfeszültség magas", ["Hibás feszültségszabályzó / generátor"])
c("P0571", "Fékkapcsoló „A” áramkör", ["Hibás vagy rosszul beállított féklámpakapcsoló", "Vezetékezési hiba"])
c("P0600", "Soros kommunikációs kapcsolat hibája", ["Kommunikációs hiba a vezérlőegységek között", "Vezetékezési hiba"])
c("P0601", "Belső vezérlőmodul – memória-ellenőrzőösszeg hiba", ["Vezérlőegység szoftver- vagy hardverhibája"])
c("P0603", "Belső vezérlőmodul – állandó memória (KAM) hiba", ["Akkumulátor lekötése után átmenetileg is jelentkezhet", "Vezérlőegység-hiba"])
c("P0606", "Vezérlőmodul processzorhiba", ["Vezérlőegység-hiba", "Tápellátási vagy testhiba"])
c("P0627", "Üzemanyag-szivattyú „A” vezérlő áramkör / nyitott", ["Szivattyúrelé vagy -vezérlő hibája", "Vezetékezési hiba"])
c("P0641", "Érzékelő-referenciafeszültség „A” áramkör / nyitott", ["Zárlat egy ugyanerről a tápról járó érzékelőben", "Vezetékezési hiba"])
c("P0700", "Sebességváltó-vezérlő rendszer hibája (MIL-kérés)", ["A váltóvezérlő (TCM) saját hibakódot tárolt"],
  ["A pontos ok a TCM hibakódjaiban található – ehhez gyártóspecifikus diagnosztika szükséges"])
c("P0705", "Váltókar-helyzetérzékelő „A” áramkör (PRNDL bemenet)", ["Hibás vagy rosszul beállított érzékelő", "Vezetékezési hiba"])
c("P0715", "Bemenő/turbinatengely fordulatszám-érzékelő „A” áramkör", ["Hibás érzékelő", "Vezetékezési hiba"])
c("P0720", "Kimenő tengely fordulatszám-érzékelő áramkör", ["Hibás érzékelő", "Vezetékezési hiba"])
c("P0730", "Helytelen áttétel", ["Alacsony vagy elhasznált váltóolaj", "Csúszó tengelykapcsolók", "Hibás váltó-mágnesszelep"])
c("P0740", "Nyomatékváltó-áthidaló tengelykapcsoló (TCC) áramkör", ["Hibás TCC mágnesszelep", "Vezetékezési hiba"])
c("P0741", "Nyomatékváltó-áthidaló tengelykapcsoló (TCC) – teljesítmény / kikapcsolva ragadt", ["Elhasznált váltóolaj", "Hibás mágnesszelep", "Belső váltóhiba"])
c("P2002", "Részecskeszűrő (DPF) hatásfoka a küszöbérték alatt (1. hengersor)", ["Sérült vagy kiszerelt részecskeszűrő", "Hibás nyomáskülönbség-érzékelő"])
c("P2004", "Szívócső-csappantyú beragadt nyitott helyzetben (1. hengersor)", ["Elkokszosodott csappantyú", "Hibás állítómotor vagy vákuumdoboz"])
c("P2096", "Katalizátor utáni keverékkorrekció – túl szegény (1. hengersor)", ["Kipufogó-szivárgás", "Hibás lambdaszonda", "Szegény keverék"])
c("P2097", "Katalizátor utáni keverékkorrekció – túl dús (1. hengersor)", ["Hibás lambdaszonda", "Dús keverék", "Katalizátorhiba"])
c("P2135", "Fojtószelep / pedál helyzetérzékelő „A” és „B” feszültség-összefüggési hiba", ["Hibás fojtószelep-ház érzékelője", "Vezetékezési vagy csatlakozóhiba"],
  ["Ez a hiba vészüzemmódot (teljesítménykorlátozást) okozhat"])
c("P2138", "Pedálhelyzet-érzékelő „D” és „E” feszültség-összefüggési hiba", ["Hibás gázpedál-modul", "Vezetékezési hiba"])
c("P2187", "Alapjáraton túl szegény a rendszer (1. hengersor)", ["Fals levegő (leggyakrabban alapjáraton jelentkezik)", "PCV-hiba", "Alacsony üzemanyagnyomás"], LEAN_T)
c("P2188", "Alapjáraton túl dús a rendszer (1. hengersor)", ["Szivárgó befecskendező", "EVAP-öblítőszelep nyitva ragadt", "Hibás nyomásszabályozó"])
c("P242F", "Részecskeszűrő (DPF) korlátozás – hamu felhalmozódás (1. hengersor)", ["A DPF elérte a hamutelítettségi határt"],
  ["A hamu regenerációval nem égethető ki: a szűrő tisztítása vagy cseréje szükséges"])
c("P2452", "Részecskeszűrő nyomáskülönbség-érzékelő „A” áramkör", ["Hibás érzékelő", "Vezetékezési hiba", "Repedt vagy eldugult érzékelőcső"])
c("P2453", "Részecskeszűrő nyomáskülönbség-érzékelő „A” – tartomány / teljesítmény", ["Eldugult vagy leesett érzékelőcső", "Hibás érzékelő"])
c("P2463", "Részecskeszűrő (DPF) – koromfelhalmozódás (1. hengersor)",
  ["Gyakori rövid utak miatt elmaradó regeneráció", "Befecskendező-, EGR- vagy turbóhiba okozta fokozott koromképződés", "Hibás nyomáskülönbség-érzékelő"],
  ["Ha a jármű engedi, hosszabb, egyenletes autópályás út segítheti a regenerációt", "Kényszerregeneráció csak gyártóspecifikus eszközzel indítható"])
c("U0001", "Nagysebességű CAN kommunikációs busz hiba", ["Vezetékezési hiba vagy zárlat a buszon", "Hibás vezérlőegység terheli a buszt", "Lezáró ellenállás hibája"])
c("U0073", "Vezérlőmodul kommunikációs busz „A” – kikapcsolt (bus off)", ["Busz-zárlat", "Hibás vezérlőegység", "Tápellátási hiba"])
c("U0100", "Megszakadt a kommunikáció az ECM/PCM „A” motorvezérlővel", ["A motorvezérlő tápellátási vagy testhibája", "CAN-vezetékezési hiba", "Gyenge akkumulátor"],
  ["Ellenőrizd az akkumulátor feszültségét és a vezérlőegység biztosítékait"])
c("U0101", "Megszakadt a kommunikáció a váltóvezérlővel (TCM)", ["Tápellátási vagy testhiba", "CAN-vezetékezési hiba"])
c("U0121", "Megszakadt a kommunikáció az ABS-vezérlővel", ["Tápellátási vagy testhiba", "CAN-vezetékezési hiba", "ABS-modul hibája"])
c("U0140", "Megszakadt a kommunikáció a karosszéria-vezérlővel (BCM)", ["Tápellátási vagy testhiba", "CAN-vezetékezési hiba"])
c("U0155", "Megszakadt a kommunikáció a műszeregységgel (IPC)", ["Tápellátási vagy testhiba", "CAN-vezetékezési hiba"])

root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
path = os.path.join(root, "app/src/main/assets/dtc/hu.json")
with open(path, "w", encoding="utf-8") as f:
    json.dump(dict(sorted(D.items())), f, ensure_ascii=False, indent=1)
print(len(D), "kód ->", path)
