#!/usr/bin/env python3
"""A magyar strings.xml generálása. Futtatás: python3 tools/gen_strings.py"""
import os, re

UI = {
"app_name": "OBD Diagnosztika",
"back": "Vissza", "cancel": "Mégse", "ok": "OK", "save": "Mentés", "delete": "Törlés", "edit": "Szerkesztés", "done": "Kész",
"rename": "Átnevezés", "share": "Megosztás", "clear": "Törlés", "send": "Küldés", "scan": "Keresés", "name": "Név", "notes": "Megjegyzés",
"yes": "Igen", "no": "Nem", "on": "BE", "off": "KI", "all": "Mind", "active": "Aktív", "paired": "Párosítva", "favorites": "Kedvencek",
"connect": "Csatlakozás", "disconnect": "Leválasztás", "retry_vehicle": "Újra a járművel", "not_available": "Nem elérhető",
"unknown_device": "Ismeretlen eszköz", "export_csv": "CSV-export", "search_pid": "Keresés név vagy PID szerint",
"passed": "MEGFELELT", "failed": "NEM FELELT MEG", "protocol": "Protokoll", "version": "Verzió: %1$s",
"tab_home": "Főoldal", "tab_dtc": "Hibakódok", "tab_live": "Élő adatok", "tab_dash": "Műszerfal", "tab_more": "Továbbiak",
"simulated_badge": "SZIMULÁLT", "simulated_banner": "SZIMULÁCIÓS MÓD – a megjelenített adatok nem valós járműből származnak",
"not_connected": "Nincs járműkapcsolat", "not_connected_hint": "Koppints ide az adapter kiválasztásához és a csatlakozáshoz.",
"conn_disconnected": "Nincs kapcsolat", "conn_connecting_adapter": "Kapcsolódás az adapterhez…", "conn_initializing": "Adapter inicializálása…",
"conn_connecting_vehicle": "Kapcsolódás a járműhöz…", "conn_adapter_only": "Az adapter csatlakozik, a jármű nem válaszol",
"conn_connected": "Csatlakoztatva", "conn_reconnecting": "Újracsatlakozás (%1$d/%2$d)…", "conn_failed": "A kapcsolódás nem sikerült",
"home_no_vehicle": "Nincs kiválasztott járműprofil", "home_connect_hint": "Dugd be az adaptert, kapcsold be a gyújtást, majd csatlakozz.",
"home_voltage": "Akkumulátor", "home_dtc_count": "Hibakód", "home_mil": "Motorhiba-lámpa", "home_quick_actions": "Gyors műveletek",
"home_read_dtc": "Hibakódok olvasása", "home_recent_sessions": "Korábbi diagnosztikák", "home_no_sessions": "Még nincs mentett hibakód-olvasás.",
"home_adapter_ok": "Adapter OK", "home_ecu_ok": "ECU OK", "home_ecu_missing": "ECU nem válaszol",
"adapter_choose": "Adapter", "session_cleared": "Hibakódok törölve", "session_scan": "Hibakód-olvasás: %1$d kód",
"dtc_title": "Hibakódok", "dtc_last_read": "Utolsó olvasás: %1$s", "dtc_read": "Olvasás", "dtc_clear": "Törlés",
"dtc_read_error": "A hibakódok olvasása nem sikerült", "dtc_empty_title": "Még nem olvastál hibakódot",
"dtc_empty_message": "Csatlakozás után az Olvasás gomb lekérdezi a tárolt, a függőben lévő és az állandó hibakódokat.",
"dtc_stored": "Tárolt", "dtc_pending": "Függőben", "dtc_permanent": "Állandó",
"dtc_none_title": "Nincs hibakód", "dtc_none_message": "Ebben a kategóriában a jármű nem jelzett hibakódot.",
"dtc_not_supported": "A jármű nem támogatja: %1$s", "dtc_summary": "Tárolt: %1$d · Függőben: %2$d · Állandó: %3$d",
"mil_on": "A motorhiba-visszajelző BE van kapcsolva", "mil_off": "A motorhiba-visszajelző nem világít", "mil_unknown": "A MIL állapota ismeretlen",
"dtc_no_description": "Ehhez a kódhoz nincs megbízható leírás az adatbázisban.", "dtc_manufacturer_specific": "gyártóspecifikus kód",
"dtc_causes": "Lehetséges okok", "dtc_tips": "Diagnosztikai javaslatok",
"dtc_no_causes": "A lehetséges okokhoz a gyártói dokumentáció szükséges.",
"dtc_disclaimer": "A leírás tájékoztató jellegű; a javítás előtt végezz méréseket, és vedd figyelembe a gyártói előírásokat.",
"dtc_clear_confirm_title": "Hibakódok törlése?",
"dtc_clear_confirm_text": "A törlés (Mode 04) a hibakódok mellett a freeze frame adatokat, a Mode 06 teszteredményeket és a készenléti (readiness) monitorok állapotát is törli. A monitoroknak újra le kell futniuk, ami több menetciklusig tarthat, és addig a jármű nem felel meg a műszaki vizsga OBD-ellenőrzésének. Az állandó hibakódok így nem törölhetők. A törléshez a gyújtás legyen bekapcsolva, a motor álljon.",
"dtc_clear_confirm": "Törlés", "dtc_clear_ok": "A hibakódok törlése sikeres.", "dtc_clear_rejected": "A jármű elutasította a törlést: %1$s",
"dtc_clear_failed": "A törlés nem sikerült: %1$s",
"freeze_frame": "Freeze Frame (pillanatkép)", "freeze_frame_hint": "A hiba keletkezésekor a vezérlő által rögzített üzemi adatok.",
"freeze_frame_load": "Freeze Frame betöltése", "freeze_frame_none": "A jármű nem tárolt freeze frame adatot.", "freeze_frame_trigger": "Kiváltó hibakód: %1$s",
"live_title": "Élő adatok", "live_rate": "%1$d jel · %2$.1f kör/s", "live_connect_hint": "Az élő adatokhoz csatlakozz egy OBD-II adapterhez.",
"live_no_pids_title": "Nincs támogatott PID", "live_no_pids_message": "A jármű nem jelzett támogatott szabványos Mode 01 PID-et.",
"live_monitored_count": "Figyelt: %1$d / %2$d", "monitor_all": "Mind figyelése", "monitor_none": "Egyik sem",
"view_list": "Lista", "view_cards": "Kártyák", "view_chart": "Grafikon", "reset_min_max": "Min/max nullázása",
"pid_live": "Élő", "pid_waiting": "Várakozás", "pid_stale": "Elavult", "pid_no_response": "Nincs válasz", "pid_not_supported": "Nem támogatott", "pid_paused": "Szünetel",
"chart_now": "most", "chart_no_data": "Nincs adat a megjelenített időszakban", "chart_window": "Időablak: %1$d s", "chart_scale": "Y-tengely skálázása",
"chart_auto_scale": "Automatikus skálázás", "chart_manual_note": "A kézi skála csak azonos mértékegységű görbéknél érvényes.",
"chart_select_hint": "Válassz ki figyelt jeleket a lista vagy a kártya nézetben (legfeljebb 6 görbe).",
"chart_gestures": "Csippentés: nagyítás · Húzás: visszanézés · Dupla koppintás: élő nézet",
"chart_gestures_saved": "Csippentés: nagyítás · Húzás: görgetés az időben",
"chart_max_series": "Egyszerre legfeljebb 6 görbe jelenik meg.",
"dash_title": "Műszerfal", "dash_new": "Új profil", "dash_add_widget": "Műszer hozzáadása", "dash_edit_widget": "Műszer szerkesztése",
"dash_empty_title": "Üres műszerfal", "dash_empty_message": "Adj hozzá műszereket, és rendeld hozzájuk a kívánt PID-et.",
"dash_not_connected": "Nincs járműkapcsolat – a műszerek csatlakozás után mutatnak értéket.",
"dash_delete_title": "Törlöd a(z) „%1$s” profilt?", "dash_unknown_signal": "Ismeretlen jel: %1$s",
"dash_widget_type": "Műszer típusa", "dash_widget_size": "Méret", "dash_widget_signal": "Jel", "dash_thresholds": "Figyelmeztetés és skála (%1$s)",
"warn_low": "Alsó határ", "warn_high": "Felső határ", "scale_min": "Skála min.", "scale_max": "Skála max.",
"size_small": "Kicsi", "size_wide": "Széles", "size_full": "Teljes sor", "move_back": "Előre mozgatás", "move_forward": "Hátra mozgatás", "resize": "Átméretezés",
"gauge_analog": "Analóg műszer", "gauge_digital": "Digitális kijelző", "gauge_temperature": "Hőmérséklet", "gauge_pressure": "Nyomás",
"gauge_round": "Kör alakú", "gauge_bar": "Mérősáv", "gauge_number": "Számérték", "gauge_sparkline": "Mini grafikon",
"more_diagnostics": "Diagnosztika", "more_setup": "Beállítás és eszközök",
"logging_title": "Adatnaplózás", "logging_desc": "Élő adatok rögzítése, visszanézése és CSV-exportja",
"logging_active": "Naplózás folyamatban", "logging_start": "Rögzítés indítása", "logging_stop": "Rögzítés leállítása",
"logging_intro": "A kiválasztott jeleket időbélyeggel együtt rögzíti az alkalmazás. A felvétel az aktív járműprofilhoz kerül.",
"logging_running": "%1$s · %2$d jel · %3$d minta", "logging_sessions": "Mérési munkamenetek",
"logging_empty_title": "Nincs mentett mérés", "logging_empty_message": "Indíts egy rögzítést élő járműkapcsolat mellett.",
"logging_samples": "%1$d minta", "logging_default_name": "Mérés %1$s", "logging_choose_signals": "Rögzítendő jelek (%1$d)",
"logging_rate_note": "Minél több jelet választasz, annál ritkábban frissül egy-egy érték – ezt az adapter és a jármű sebessége korlátozza.",
"logging_no_samples": "Ebben a munkamenetben nincs rögzített minta.", "logging_delete_title": "Törlöd a mérést?",
"advanced_title": "Haladó diagnosztika", "advanced_desc": "Readiness monitorok, Mode 06, ECU-azonosítók, támogatott PID-ek",
"advanced_connect_hint": "A haladó funkciókhoz járműkapcsolat szükséges.",
"protocol_info": "Kapcsolat és protokoll", "adapter_version": "Adapter", "adapter_description": "Adapterleírás", "responding_ecus": "Válaszoló ECU-k",
"multi_pid": "Több PID egy kérésben",
"readiness": "OBD készenléti monitorok", "readiness_load": "Monitorállapot lekérdezése", "readiness_all_ready": "Minden monitor kész",
"readiness_not_ready": "Van befejezetlen monitor", "engine_spark": "Szikragyújtású (benzines) motor", "engine_diesel": "Kompressziógyújtású (dízel) motor",
"monitor_complete": "Kész", "monitor_incomplete": "Nem kész", "monitor_na": "Nem támogatott", "not_supported_by_vehicle": "A jármű nem támogatja.",
"ecu_info": "ECU-azonosítás (Mode 09)", "ecu_info_load": "ECU-adatok lekérdezése", "ecu_name": "ECU neve",
"ecu_info_partial": "A jármű a kalibrációs azonosítókat és az ECU-nevet nem adta meg (régebbi járműveknél gyakori).",
"mode06_title": "Mode 06 teszteredmények", "mode06_desc": "A fedélzeti monitorok mért értékei és határértékei (csak CAN protokollon).",
"mode06_load": "Teszteredmények lekérdezése", "mode06_none": "A jármű nem adott vissza Mode 06 eredményt.",
"mode06_can_only": "A Mode 06 megjelenítése jelenleg csak CAN protokollú járműveken támogatott.",
"supported_pids": "Támogatott PID-ek (Mode 01)", "supported_pids_count": "%1$d támogatott PID",
"supported_pids_legend": "Kiemelve: az alkalmazás által dekódolt PID-ek. Szürke: a jármű támogatja, de az alkalmazás még nem értelmezi.",
"terminal_title": "Szakértői terminál", "terminal_desc": "Nyers AT/OBD parancsok küldése",
"terminal_warning_title": "Figyelmeztetés",
"terminal_warning_text": "A szakértői terminál közvetlenül az adapternek küldi a parancsokat. Az alkalmazás csak az olvasó diagnosztikai szolgáltatásokat engedi; az író, vezérlő, programozó és biztonsági hozzáférést kérő parancsokat letiltja. A terminál használata alatt az élő lekérdezés szünetel. Csak akkor használd, ha tudod, mit csinálsz.",
"terminal_accept": "Megértettem", "terminal_placeholder": "pl. 010C vagy ATRV", "terminal_blocked": "LETILTVA:", "terminal_invalid": "ÉRVÉNYTELEN:",
"terminal_connect_hint": "A terminál használatához csatlakozz egy adapterhez.",
"vehicles_title": "Járműprofilok", "vehicles_desc": "Járművek, VIN és a hozzájuk tartozó előzmények",
"vehicles_empty_title": "Nincs járműprofil", "vehicles_empty_message": "Hozz létre profilt, vagy csatlakozz: a kiolvasott VIN alapján automatikusan létrejön.",
"vehicle_add": "Jármű hozzáadása", "vehicle_edit": "Jármű szerkesztése", "vehicle_make": "Gyártó", "vehicle_model": "Modell", "vehicle_year": "Évjárat",
"vehicle_engine": "Motorizáció", "vehicle_engine_code": "Motorkód", "vehicle_unnamed": "Névtelen jármű",
"vehicle_delete_title": "Törlöd a járműprofilt?", "vehicle_delete_text": "A mentett diagnosztikák és mérések megmaradnak, de már nem kapcsolódnak profilhoz.",
"vin_read": "VIN kiolvasása a járműből", "vin_read_failed": "A jármű nem adta meg a VIN-t (Mode 09 nem támogatott).",
"adapter_title": "Adapterkezelés", "adapter_desc": "Bluetooth, BLE, Wi-Fi adapterek és kapcsolatbeállítások",
"adapter_last": "Utoljára használt: %1$s", "adapter_options": "Kapcsolatbeállítások",
"auto_reconnect": "Automatikus újracsatlakozás", "auto_reconnect_desc": "Megszakadt kapcsolat esetén legfeljebb 5 próbálkozás, növekvő várakozással",
"auto_connect": "Csatlakozás indításkor", "auto_connect_desc": "Az alkalmazás indulásakor kapcsolódás az utoljára használt adapterhez",
"bt_permission_text": "Az adapterek kereséséhez és a kapcsolódáshoz Bluetooth-engedély szükséges.", "bt_permission_grant": "Engedély megadása",
"bt_disabled": "A Bluetooth ki van kapcsolva", "bt_disabled_hint": "Kapcsold be a Bluetooth-t a telefon beállításaiban.",
"bt_paired_devices": "Párosított eszközök", "bt_no_paired": "Nincs párosított Bluetooth-eszköz.", "bt_nearby": "Közeli eszközök",
"bt_pair_hint": "A klasszikus Bluetooth-adaptereket először a telefon Bluetooth-beállításaiban párosítsd (gyakori PIN: 1234 vagy 0000).",
"ble_devices": "BLE-eszközök", "ble_hint": "Indíts keresést. Az OBD-adapterek felismerése név és szolgáltatásazonosító alapján történik.",
"wifi_text": "Csatlakoztasd a telefont az adapter Wi-Fi hálózatához (pl. „WiFi_OBDII”), majd add meg az adapter címét. A legtöbb adapter alapértéke 192.168.0.10:35000.",
"wifi_host": "IP-cím", "wifi_port": "Port", "simulation": "Szimuláció",
"simulation_text": "A szimulációs mód egy virtuális ELM327 adaptert és egy demó járművet indít a felület kipróbálásához. Minden ebből származó adat SZIMULÁLT jelölést kap.",
"simulation_start": "Szimuláció indítása",
"commlog_title": "Kommunikációs napló", "commlog_desc": "Adapterparancsok, válaszok és hibák diagnosztikához",
"commlog_errors_only": "Csak hibák és események", "commlog_empty_title": "A napló üres", "commlog_empty_message": "A kapcsolódás után itt jelennek meg az adapterrel váltott üzenetek.",
"settings_title": "Beállítások", "settings_desc": "Téma, mértékegységek, lekérdezési gyakoriság",
"settings_appearance": "Megjelenés", "settings_theme": "Téma", "theme_dark": "Sötét", "theme_light": "Világos", "theme_system": "Rendszer",
"settings_units": "Mértékegységek", "unit_temperature": "Hőmérséklet", "unit_speed": "Sebesség és távolság", "unit_pressure": "Nyomás",
"units_note": "A mentett adatok mindig SI-egységben (°C, km/h, kPa) kerülnek tárolásra, csak a megjelenítés változik.",
"settings_polling": "Lekérdezés", "poll_interval": "Minimális szünet két lekérdezési kör között", "poll_max": "Leggyorsabb",
"poll_note": "Hosszabb szünet kíméli az adaptert és a telefon akkumulátorát. Ha az alkalmazás a háttérbe kerül, az élő lekérdezés szünetel (kivéve naplózás közben).",
"keep_screen_on": "Kijelző ébren tartása", "keep_screen_on_desc": "Járműkapcsolat és naplózás közben nem kapcsol ki a kijelző",
"commlog_traffic": "Forgalom naplózása", "commlog_traffic_desc": "A parancsok és válaszok rögzítése a kommunikációs naplóba",
"about_title": "Névjegy és funkcióállapot", "about_desc": "Mi működik, és mihez kell járműspecifikus támogatás",
"about_text": "Szabványos OBD-II (SAE J1979 / ISO 15031) diagnosztika ELM327- és STN-kompatibilis adapterekkel, Bluetooth Classic, BLE és Wi-Fi kapcsolattal.",
"features_working": "Működő funkciók (szabványos OBD-II)",
"f_conn": "Bluetooth Classic, BLE és Wi-Fi adapterkapcsolat, automatikus protokollfelismerés és újracsatlakozás",
"f_dtc": "Tárolt, függőben lévő és állandó hibakódok (Mode 03/07/0A), törlés (Mode 04), MIL állapot",
"f_freeze": "Freeze frame adatok (Mode 02)",
"f_live": "Támogatott PID-ek automatikus felderítése, több PID egy kérésben (CAN), élő értékek min/max-szal",
"f_chart": "Többgörbés valós idejű grafikon nagyítással, visszanézéssel és CSV-exporttal",
"f_dash": "Testreszabható műszerfalprofilok határértékekkel, fekvő módban is",
"f_log": "Adatnaplózás Room adatbázisba, visszanézés, CSV-export",
"f_vehicles": "Járműprofilok, VIN kiolvasása és automatikus profilhoz rendelés",
"f_readiness": "OBD készenléti monitorok (benzines és dízel)",
"f_mode06": "Mode 06 teszteredmények (CAN)",
"f_mode09": "VIN, kalibrációs azonosítók, CVN, ECU-név (Mode 09)",
"f_terminal": "Szakértői terminál, csak olvasó parancsokra korlátozva",
"f_sim": "Szimulációs mód egyértelmű jelöléssel",
"features_limited": "Bővítésre váró, járműspecifikus területek",
"l_manufacturer": "Gyártóspecifikus PID-ek (pl. tényleges/cél turbónyomás, DPF-regeneráció, váltóadatok): az architektúra kész rá, de járműenkénti adatbázis kell. Addig csak a szabványos 0x70/0x7A/0x7C PID-ek jelennek meg, ha a jármű támogatja.",
"l_dtc_db": "Gyártóspecifikus (P1xxx, B1xxx, C1xxx, U1xxx) hibakódok leírása: a kód kiolvasása működik, a leíráshoz gyártói adatbázis szükséges.",
"l_stn": "STN11xx/21xx adapterek: felismerés és szabványos ELM-parancskészlet; az STN-specifikus gyorsítások még nincsenek kihasználva.",
"l_mode06_legacy": "Mode 06 a régi (nem CAN) protokollokon nincs értelmezve.",
"l_background": "Hosszú, kikapcsolt kijelzős naplózáshoz előtérszolgáltatás még nincs; naplózás közben a kijelző ébren marad.",
"about_disclaimer": "Az alkalmazás nem helyettesíti a gyártói diagnosztikai eszközöket. A hibakódok törlése és a nyers parancsok használata a felhasználó felelőssége.",
}

PIDS = {
"mil": "Motorhiba-visszajelző (MIL)", "dtc_count": "Tárolt hibakódok száma", "fuel_system_1": "Üzemanyagrendszer állapota (1)", "fuel_system_2": "Üzemanyagrendszer állapota (2)",
"engine_load": "Számított motorterhelés", "coolant_temp": "Hűtőfolyadék-hőmérséklet", "stft_b1": "Rövid távú üzemanyag-korrekció (1. hengersor)",
"ltft_b1": "Hosszú távú üzemanyag-korrekció (1. hengersor)", "stft_b2": "Rövid távú üzemanyag-korrekció (2. hengersor)", "ltft_b2": "Hosszú távú üzemanyag-korrekció (2. hengersor)",
"fuel_pressure": "Üzemanyagnyomás", "map": "Szívócső abszolút nyomása (MAP)", "rpm": "Motorfordulatszám", "speed": "Járműsebesség",
"timing_advance": "Gyújtási előgyújtás", "intake_temp": "Szívólevegő-hőmérséklet", "maf": "Légtömegáram (MAF)", "throttle": "Fojtószelep-állás",
"secondary_air": "Szekunder levegő állapota", "o2_present": "Lambdaszondák száma",
"obd_standard": "OBD-szabvány", "run_time": "Motor üzemideje indítás óta", "distance_mil": "Megtett út MIL bekapcsolva",
"fuel_rail_pressure_rel": "Közös nyomócső nyomása (szívócsőhöz képest)", "fuel_rail_pressure": "Közös nyomócső nyomása (rail)",
"egr_commanded": "EGR előírt nyitás", "egr_error": "EGR eltérés", "evap_purge": "EVAP öblítés előírt értéke", "fuel_level": "Üzemanyagszint",
"warmups_since_clear": "Bemelegedések a törlés óta", "distance_since_clear": "Megtett út a törlés óta", "evap_vapor_pressure": "EVAP gőznyomás",
"baro": "Légköri nyomás", "cat_temp_b1s1": "Katalizátor-hőmérséklet (B1S1)", "cat_temp_b2s1": "Katalizátor-hőmérséklet (B2S1)",
"cat_temp_b1s2": "Katalizátor-hőmérséklet (B1S2)", "cat_temp_b2s2": "Katalizátor-hőmérséklet (B2S2)",
"module_voltage": "Vezérlőegység-feszültség (ECU)", "absolute_load": "Abszolút terhelés", "commanded_lambda": "Előírt lambda (keverékarány)",
"relative_throttle": "Relatív fojtószelep-állás", "ambient_temp": "Környezeti hőmérséklet", "throttle_b": "Fojtószelep-állás B", "throttle_c": "Fojtószelep-állás C",
"pedal_d": "Gázpedál-állás D", "pedal_e": "Gázpedál-állás E", "pedal_f": "Gázpedál-állás F", "throttle_commanded": "Előírt fojtószelep-állás",
"time_mil_on": "Idő MIL bekapcsolva", "time_since_clear": "Idő a hibakódtörlés óta", "fuel_type": "Üzemanyag típusa", "ethanol": "Etanoltartalom",
"evap_abs_pressure": "EVAP abszolút gőznyomás", "evap_pressure_wide": "EVAP gőznyomás (széles tartomány)",
"stft_o2_b1": "Másodlagos szonda rövid távú korrekció (B1)", "stft_o2_b3": "Másodlagos szonda rövid távú korrekció (B3)",
"ltft_o2_b1": "Másodlagos szonda hosszú távú korrekció (B1)", "ltft_o2_b3": "Másodlagos szonda hosszú távú korrekció (B3)",
"stft_o2_b2": "Másodlagos szonda rövid távú korrekció (B2)", "stft_o2_b4": "Másodlagos szonda rövid távú korrekció (B4)",
"ltft_o2_b2": "Másodlagos szonda hosszú távú korrekció (B2)", "ltft_o2_b4": "Másodlagos szonda hosszú távú korrekció (B4)",
"fuel_rail_abs_pressure": "Közös nyomócső abszolút nyomása", "relative_pedal": "Relatív gázpedál-állás", "hybrid_battery": "Hibrid akkumulátor töltöttsége",
"oil_temp": "Motorolaj-hőmérséklet", "injection_timing": "Befecskendezés időzítése", "fuel_rate": "Üzemanyag-fogyasztás",
"torque_demand": "Vezetői nyomatékigény", "torque_actual": "Tényleges motornyomaték", "torque_reference": "Referencianyomaték",
"boost_commanded_a": "Előírt töltőnyomás (A)", "boost_actual_a": "Tényleges töltőnyomás (A)", "boost_commanded_b": "Előírt töltőnyomás (B)", "boost_actual_b": "Tényleges töltőnyomás (B)",
"dpf_b1_delta": "DPF nyomáskülönbség (B1)", "dpf_b1_inlet": "DPF bemeneti nyomás (B1)", "dpf_b1_outlet": "DPF kimeneti nyomás (B1)",
"dpf_temp_b1_inlet": "DPF bemeneti hőmérséklet (B1)", "dpf_temp_b1_outlet": "DPF kimeneti hőmérséklet (B1)",
"dpf_temp_b2_inlet": "DPF bemeneti hőmérséklet (B2)", "dpf_temp_b2_outlet": "DPF kimeneti hőmérséklet (B2)",
}
for i in range(8):
    bank = 1 if i < 4 else 2; s = i % 4 + 1
    PIDS[f"o2_b{bank}s{s}_voltage"] = f"Lambdaszonda feszültség (B{bank}S{s})"
    PIDS[f"o2_b{bank}s{s}_trim"] = f"Lambdaszonda rövid távú korrekció (B{bank}S{s})"
    PIDS[f"o2wr_s{i+1}_lambda"] = f"Szélessávú szonda {i+1} – lambda"
    PIDS[f"o2wr_s{i+1}_voltage"] = f"Szélessávú szonda {i+1} – feszültség"
    PIDS[f"o2wrc_s{i+1}_lambda"] = f"Szélessávú szonda {i+1} – lambda (áram)"
    PIDS[f"o2wrc_s{i+1}_current"] = f"Szélessávú szonda {i+1} – áram"

STATES = {
"fuel_sys_open_temp": "Nyílt hurok – nem elég meleg a motor", "fuel_sys_closed": "Zárt hurok (lambdaszabályozás)",
"fuel_sys_open_load": "Nyílt hurok – terhelés vagy motorfék", "fuel_sys_open_fault": "Nyílt hurok – rendszerhiba miatt",
"fuel_sys_closed_fault": "Zárt hurok – szondahibával", "fuel_sys_unknown": "Ismeretlen állapot",
"sec_air_upstream": "Katalizátor előtt", "sec_air_downstream": "Katalizátor után", "sec_air_atmosphere": "Légkörbe / kikapcsolva", "sec_air_diag": "Diagnosztika alatt",
}
OBD_STD = {1: "OBD-II (CARB)", 2: "OBD (EPA)", 3: "OBD és OBD-II", 4: "OBD-I", 5: "Nem OBD-kompatibilis", 6: "EOBD (Európa)", 7: "EOBD és OBD-II",
           8: "EOBD és OBD", 9: "EOBD, OBD és OBD-II", 10: "JOBD (Japán)", 11: "JOBD és OBD-II", 12: "JOBD és EOBD", 13: "JOBD, EOBD és OBD-II",
           17: "EMD", 18: "EMD+", 19: "HD OBD-C", 20: "HD OBD", 21: "WWH OBD", 23: "HD EOBD-I", 24: "HD EOBD-I N", 25: "HD EOBD-II",
           26: "HD EOBD-II N", 28: "OBDBr-1", 29: "OBDBr-2", 30: "KOBD (Korea)", 31: "IOBD I (India)", 32: "IOBD II (India)", 33: "HD EOBD-VI"}
for k, v in OBD_STD.items(): STATES[f"obd_std_{k}"] = v
FUEL = {1: "Benzin", 2: "Metanol", 3: "Etanol", 4: "Dízel", 5: "LPG", 6: "CNG", 7: "Propán", 8: "Elektromos", 9: "Kétüzemű – benzin",
        10: "Kétüzemű – metanol", 11: "Kétüzemű – etanol", 12: "Kétüzemű – LPG", 13: "Kétüzemű – CNG", 14: "Kétüzemű – propán",
        15: "Kétüzemű – elektromos", 16: "Kétüzemű – elektromos és égésű motor", 17: "Hibrid benzin", 18: "Hibrid etanol", 19: "Hibrid dízel",
        20: "Hibrid elektromos", 21: "Hibrid – elektromos és égésű motor", 22: "Hibrid regeneratív", 23: "Kétüzemű dízel"}
for k, v in FUEL.items(): STATES[f"fuel_type_{k}"] = v

MONITORS = {
"misfire": "Gyújtáskimaradás", "fuel_system": "Üzemanyagrendszer", "components": "Átfogó alkatrészfigyelés",
"catalyst": "Katalizátor", "heated_catalyst": "Fűtött katalizátor", "evap": "Párolgási rendszer (EVAP)", "secondary_air": "Szekunder levegő",
"ac_refrigerant": "Klíma hűtőközeg", "o2_sensor": "Lambdaszonda", "o2_heater": "Lambdaszonda-fűtés", "egr": "EGR rendszer",
"nmhc_catalyst": "NMHC katalizátor", "nox_scr": "NOx / SCR utókezelés", "boost_pressure": "Töltőnyomás-rendszer",
"exhaust_sensor": "Kipufogógáz-szenzor", "pm_filter": "Részecskeszűrő (DPF)", "egr_vvt": "EGR / változó szelepvezérlés",
}
CATS = {"engine": "Motor", "fuel": "Üzemanyag", "air": "Levegő", "oxygen": "Lambda", "exhaust": "Kipufogó", "temperature": "Hőmérséklet",
        "electrical": "Elektromos", "vehicle": "Jármű", "status": "Állapot", "diesel": "Dízel / turbó"}
MIDS = {"mid_o2_sensor": "Lambdaszonda-monitor", "mid_catalyst": "Katalizátor-monitor", "mid_egr_vvt": "EGR / VVT-monitor", "mid_evap": "EVAP-monitor",
        "mid_o2_heater": "Lambdaszonda-fűtés", "mid_heated_catalyst": "Fűtött katalizátor", "mid_secondary_air": "Szekunder levegő",
        "mid_fuel_system": "Üzemanyagrendszer", "mid_boost": "Töltőnyomás", "mid_nox_adsorber": "NOx-tároló", "mid_nox_catalyst": "NOx-katalizátor",
        "mid_misfire_general": "Gyújtáskimaradás (általános)", "mid_misfire_cylinder": "Gyújtáskimaradás (henger)", "mid_pm_filter": "Részecskeszűrő", "mid_other": "Egyéb monitor"}

def esc(s):
    s = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "\\'").replace('"', '\\"')
    if s.startswith("@") or s.startswith("?"): s = "\\" + s
    return s

def needs_format(s): return re.search(r"%\d\$", s) is not None

out = ['<?xml version="1.0" encoding="utf-8"?>', '<!-- GENERÁLT FÁJL: tools/gen_strings.py -->', '<resources>']
def add(name, val, translatable=True):
    v = esc(val)
    if not needs_format(val): v = v.replace("%", "%%") if False else v
    out.append(f'    <string name="{name}"{"" if translatable else " translatable=\"false\""}>{v}</string>')
for k, v in UI.items(): add(k, v)
for k, v in PIDS.items(): add("pid_" + k, v)
for k, v in STATES.items(): add("state_" + k, v)
for k, v in MONITORS.items(): add("mon_" + k, v)
for k, v in CATS.items(): add("cat_" + k, v)
for k, v in MIDS.items(): add(k, v)
out.append('</resources>')
root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
path = os.path.join(root, "app/src/main/res/values/strings.xml")
with open(path, "w", encoding="utf-8") as f: f.write("\n".join(out) + "\n")
print("strings:", len(out) - 4, "->", path)
