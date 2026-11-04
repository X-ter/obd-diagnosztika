# OBDb ↔ PSA-RE keresztellenőrzés (Peugeot)

Az OBDb autótípusonként egy skálát ad, a PSA-RE vezérlőmodellenként. Ahol a táblázat eltérést mutat, ott az OBDb-érték csak bizonyos motorvezérlőkön lenne helyes – ezért az app a PSA-RE vezérlőmodell-szintű adatait használja, azonosított vezérlő esetén.

- minden modellel egyezik: 2385
- nincs a PSA-RE-ben: 825
- csak egyes modellekkel egyezik: 695
- más bitpozíció: 520
- egyik modellel sem egyezik: 104

| OBDb modell | Cím | Kérés | Jel | OBDb skála | Eltérő PSA-RE vezérlőmodellek |
|---|---|---|---|---|---|
| Peugeot-107 | 7E0 | 21 01 | Calculated load | ×0.392157 +0 | CMM_4A92: ×0.0732601 +0; HDI_4N13: ×1 +0; MIVEC: ×0.07326 +0 |
| Peugeot-107 | 7E0 | 21 01 | Manifold absolute pressure | ×1 +0 | CMM_4A92: ×7.8125 +0; HDI_4N13: ×7.8125 +0; MIVEC: ×7.8125 +0 |
| Peugeot-107 | 7E0 | 21 03 | Short term fuel trim, bank 1 | ×0.78125 -100 | CMM_4A92: ×19.5312 +0; MIVEC: ×19.5312 +0 |
| Peugeot-107 | 7E0 | 21 03 | Short term fuel trim, bank 2 | ×0.78125 -100 | CMM_4A92: ×0.625 +0 |
| Peugeot-107 | 7E0 | 21 0E | Ignition advance | ×0.5 -64 | CMM_4A92: ×0.392157 +0; MIVEC: ×0.392157 +0 |
| Peugeot-107 | 7E0 | 21 0F | Intake air temperature | ×1 -40 | CMM_4A92: ×0.392157 +0; MIVEC: ×0.392157 +0 |
| Peugeot-107 | 7E0 | 21 11 | Throttle position | ×0.392157 +0 | CMM_4A92: ×1 +0; EDC16CP39_E5: ×1 +0; MIVEC: ×1 +0 |
| Peugeot-107 | 7E0 | 21 42 | Battery current | ×0.00381476 -125 | HDI_4N13: ×1 +0 |
| Peugeot-107 | 7E0 | 21 52 | Idle spark advance control 1 | ×0.0625 +0 | HDI_4N13: ×1 +0 |
| Peugeot-107 | 7E0 | 21 B2 | Knock adjust | ×0.0312505 -64 | HDI_4N13: ×0.001 -0.5 |
| Peugeot-107 | 7E0 | 21 B2 | Knock feedback | ×0.0312505 -64 | HDI_4N13: ×0.001 -0.5 |
| Peugeot-2008 | 6A2 | 22 D402 | Vehicle speed | ×0.1 +0 | E_VCU: ×0.0078125 +0 |
| Peugeot-2008 | 6A6 | 22 D407 | Accelerator pedal position | ×1 +0 | MCU_PHEV: ×1 -40 |
| Peugeot-2008 | 6A6 | 22 DCB6 | Electric machine torque | ×0.1 +0 | MCU_ECMP: ×0.03125 +0 |
| Peugeot-2008 | 6A8 | 22 D405 | Accelerator pedal signal 1 voltage | ×0.0002 +0 | CMM_DCM71: ×0.005 +0; CMM_MG1CS032: ×0.005 +0; CMM_V56: ×0.00488281 +0; CMM_VD56: ×0.00488281 +0; DCM62: ×4 +0; EDC17C60: ×0.2 +0; MED17_4_4: ×0.2 +0; MED17_4_4_EP8: ×0.2 +0; MEVD17_4_4: ×0.2 +0; VD46: ×1.2207 +0 |
| Peugeot-2008 | 6A8 | 22 D406 | Accelerator pedal signal 2 voltage | ×0.0002 +0 | CMM_DCM71: ×0.005 +0; CMM_MG1CS032: ×0.005 +0; CMM_V56: ×0.00488281 +0; CMM_VD56: ×0.00488281 +0; DCM62: ×4 +0; EDC17C60: ×0.2 +0; MED17_4_4: ×0.2 +0; MED17_4_4_EP8: ×0.2 +0; MEVD17_4_4: ×0.2 +0; VD46: ×1.2207 +0 |
| Peugeot-2008 | 6A8 | 22 D40A | Coolant temperature, corrected | ×1 +0 | DCM62: ×1 -40 |
| Peugeot-2008 | 6A8 | 22 D40D | Coolant temperature target | ×1 +0 | DCM62: ×1 -40 |
| Peugeot-2008 | 6A8 | 22 D411 | Boost air temperature | ×0.1 -273.14 | CMM_DCM71: ×1 -40 |
| Peugeot-2008 | 6A8 | 22 D414 | Outside air temperature | ×1 +0 | DCM62: ×1 -50 |
| Peugeot-2008 | 6A8 | 22 D415 | Coolant temperature | ×0.1 -273.14 | CMM_DCM71: ×1 +0; CMM_MG1CS032: ×1 +0; DCM62: ×1 -40 |
| Peugeot-2008 | 6A8 | 22 D41E | Engine oil level sensor voltage | ×0.2 +0 | CMM_DCM71: ×1 +0; CMM_MG1CS032: ×0.001 +0; CMM_VD56: ×1.2207 +0; DCM62: ×4 +0; VD46: ×1.2207 +0 |
| Peugeot-2008 | 6A8 | 22 D41F | Fuel pressure | ×0.001 +0 | CMM_DCM71: ×0.0078125 +0; DCM62: ×0.0078125 +0 |
| Peugeot-2008 | 6A8 | 22 D421 | Air conditioning pressure | ×0.001 +0 | CMM_DCM71: ×0.032 +1; CMM_MD1CS003: ×0.002 +0; CMM_MG1CS032: ×1 +0; CMM_MG1CS042: ×0.0006 +0; CMM_MG1CS042_PHEV: ×0.0006 +0; CMM_V56: ×0.00064 +0; CMM_VD56: ×0.00064 +0; DCM62: ×0.032 +1; EDC17C60: ×0.002 +0; MED17_4_4: ×0.0006 +0; MED17_4_4_EP8: ×0.0006 +0; MEVD17_4_4: ×0.0006 +0; VD46: ×0.00064 +0 |
| Peugeot-2008 | 6A8 | 22 D422 | Engine control computer supply voltage | ×0.01 +0 | DCM62: ×0.0078125 +0 |
| Peugeot-2008 | 6A8 | 22 D429 | Oil pressure | ×0.001 +0 | CMM_DCM71: ×0.0078125 +0; CMM_MG1CS032: ×0.01 +0; CMM_VD56: ×0.00016 +0; DCM62: ×0.0078125 +0; MED17_4_4: ×0.0078125 +0; MED17_4_4_EP8: ×0.0078125 +0; MEVD17_4_4: ×0.0078125 +0; VD46: ×0.00016 +0 |
| Peugeot-2008 | 6A8 | 22 D42A | Fuel temperature | ×0.1 -273.14 | CMM_DCM71: ×0.0625 -50; DCM62: ×0.0625 -50 |
| Peugeot-2008 | 6A8 | 22 D435 | Computer temperature | ×1 +0 | DCM62: ×1 -40 |
| Peugeot-2008 | 6A8 | 22 D43B | Oil pressure regulation control | ×0.1 +0 | CMM_MG1CS042: ×0.012207 +0; CMM_MG1CS042_PHEV: ×0.012207 +0; CMM_VD56: ×0.001 +0; DCM62: ×0.0625 +0 |
| Peugeot-2008 | 6A8 | 22 D445 | Desired high pressure common rail fuel pressure | ×1 +0 | MED17_4_4: ×0.1 +0; MED17_4_4_EP8: ×0.1 +0; MEVD17_4_4: ×5e-05 +0; VD46: ×0.0078125 +0 |
| Peugeot-2008 | 6A8 | 22 D446 | High fuel pressure measured | ×0.1 +0 | DCM62: ×1 -1000; VD46: ×0.0078125 +0 |
| Peugeot-2008 | 6A8 | 22 D460 | Air metering valve, target position | ×0.01 +0 | CMM_DCM71: ×0.0078125 +0; DCM62: ×0.00390625 -100; MED17_4_4: ×0.0015259 +0; MED17_4_4_EP8: ×0.0015259 +0; VD46: ×0.0878906 +0 |
| Peugeot-2008 | 6A8 | 22 D461 | Air metering valve duty cycle | ×0.01 +0 | DCM62: ×0.0078125 -100 |
| Peugeot-2008 | 6A8 | 22 D462 | Crankshaft speed | ×0.5 +0 | CMM_DCM71: ×1 +0; CMM_MG1CS032: ×1 +0; CMM_V56: ×1 +0; CMM_VD56: ×1 +0; DCM62: ×1 +0; VD46: ×1 +0 |
| Peugeot-2008 | 6A8 | 22 D464 | Oil pressure reference value | ×0.001 +0 | CMM_VD56: ×0.01 +0 |
| Peugeot-2008 | 6A8 | 22 D465 | Atmospheric pressure | ×1 +0 | CMM_V56: ×0.08 +0; CMM_VD56: ×0.08 +0; DCM62: ×0.976562 +0; VD46: ×0.08 +0 |
| Peugeot-2008 | 6A8 | 22 D469 | Clutch pedal position value | ×0.1 +0 | CMM_DCM71: ×0.0078125 +0; CMM_MG1CS032: ×0.01 +0 |
| Peugeot-2008 | 6A8 | 22 D472 | Low pressure fuel pump actuator duty cycle | ×0.01 +0 | CMM_VD56: ×0.1 +0; DCM62: ×0.0625 +0; MED17_4_4: ×0.1 +0; MED17_4_4_EP8: ×0.1 +0; MEVD17_4_4: ×0.1 +0; VD46: ×0.1 +0 |
| Peugeot-2008 | 6A8 | 22 D475 | Cruise control set speed | ×1 +0 | EDC17C60: ×0.0078125 +0 |
| Peugeot-2008 | 6A8 | 22 D478 | Starter duty cycle | ×0.1 +0 | CMM_DCM71: ×0.001 +0; CMM_V56: ×0.01 +0; CMM_VD56: ×0.01 +0 |
| Peugeot-2008 | 6A8 | 22 D47B | Turbocharger, target position | ×0.01 +0 | CMM_DCM71: ×0.0078125 +0 |
| Peugeot-2008 | 6A8 | 22 D47D | Oil temperature | ×0.1 -273.14 | CMM_DCM71: ×0.0625 -50; CMM_MG1CS042: ×0.0234375 -273.15; DCM62: ×0.0625 -50; EDC17C60: ×0.0625 -50; MED17_4_4: ×0.1 +0; MED17_4_4_EP8: ×0.1 +0 |
| Peugeot-2008 | 6A8 | 22 D489 | Air flow | ×0.1 +0 | CMM_DCM71: ×0.0625 +0; MED17_4_4: ×0.0549316 +0; MED17_4_4_EP8: ×0.0549316 +0; MEVD17_4_4: ×0.0549316 +0 |
| Peugeot-2008 | 6A8 | 22 D48A | Turbocharger control duty cycle | ×0.00390625 +0 | MED17_4_4: ×0.00152588 +0; MED17_4_4_EP8: ×0.00152588 +0; MEVD17_4_4: ×0.00152588 +0; VD46: ×0.00152588 +0 |
| Peugeot-2008 | 6A8 | 22 D48E | Air flow target | ×0.1 +0 | DCM62: ×0.0625 +0; MED17_4_4: ×0.0549316 +0; MED17_4_4_EP8: ×0.0549316 +0; MEVD17_4_4: ×0.0549316 +0; VD46: ×0.0549316 +0 |
| Peugeot-2008 | 6A8 | 22 D491 | Accelerator pedal position, track 1 | ×0.012207 +0 | CMM_DCM71: ×0.01 +0; CMM_MG1CS032: ×0.01 +0; CMM_MG1CS042: ×0.0015259 +0; CMM_MG1CS042_PHEV: ×0.0015259 +0; CMM_V56: ×0.0976562 +0; CMM_VD56: ×0.0976562 +0; DCM62: ×0.0078125 +0; VD46: ×0.0976562 +0 |
| Peugeot-2008 | 6A8 | 22 D492 | Accelerator pedal position, track 2 | ×0.012207 +0 | CMM_DCM71: ×0.01 +0; CMM_MG1CS032: ×0.01 +0; CMM_MG1CS042: ×0.0015259 +0; CMM_MG1CS042_PHEV: ×0.0015259 +0; CMM_V56: ×0.0976562 +0; CMM_VD56: ×0.0976562 +0; DCM62: ×0.0078125 +0; VD46: ×0.0976562 +0 |
| Peugeot-2008 | 6A8 | 22 D496 | Service battery temperature | ×0.5 +0 | DCM62: ×0.25 -30 |
| Peugeot-2008 | 6A8 | 22 D499 | Crankshaft speed determined by camshaft sensor | ×0.5 +0 | CMM_MG1CS032: ×1 +0; CMM_V56: ×1 +0; CMM_VD56: ×1 +0; DCM62: ×0.25 +0; VD46: ×1 +0 |
| Peugeot-2008 | 6A8 | 22 D4D2 | Indicated engine torque | ×0.0625 +0 | MED17_4_4: ×0.1 +0; MED17_4_4_EP8: ×0.1 +0; MEVD17_4_4: ×0.1 +0 |
| Peugeot-2008 | 6A8 | 22 D4D9 | Turbocharger pressure measured | ×1 +0 | CMM_DCM71: ×0.001 +0; CMM_MG1CS042: ×0.078125 +0; CMM_MG1CS042_PHEV: ×0.078125 +0; CMM_V56: ×10 +0; MED17_4_4: ×0.078125 +0; MED17_4_4_EP8: ×0.078125 +0 |
| Peugeot-2008 | 6A8 | 22 D4EA | Turbocharger position information | ×0.01 +0 | CMM_VD56: ×0.0078125 +0 |
| Peugeot-2008 | 6A8 | 22 D4EC | Approximate position of turbocharger actuator | ×0.0078125 +0 | CMM_MG1CS032: ×0.01 +0; DCM62: ×1 +0; EDC17C60: ×1 +0 |
| Peugeot-2008 | 6A8 | 22 D509 | Exhaust gas recirculation valve position | ×0.01 +0 | CMM_DCM71: ×0.0625 +0; DCM62: ×0.0625 +0 |
| Peugeot-2008 | 6A8 | 22 D50C | Preheating relay control | ×0.01 +0 | CMM_DCM71: ×0.0078125 +0 |
| Peugeot-2008 | 6A8 | 22 D510 | Exhaust gas recirculation valve set position | ×0.01 +0 | CMM_DCM71: ×0.0078125 +0 |
| Peugeot-2008 | 6A8 | 22 D511 | Exhaust gas recirculation valve duty cycle | ×0.01 +0 | CMM_DCM71: ×0.0078125 -100; DCM62: ×0.0078125 -100 |
| Peugeot-2008 | 6A8 | 22 D519 | Distance traveled since the last regeneration | ×0.0625 +0 | DCM62: ×1 +0 |
| Peugeot-2008 | 6A8 | 22 D525 | Average mileage for the last 10 regenerations | ×0.0625 +0 | DCM62: ×1 +0 |
| Peugeot-2008 | 6A8 | 22 D52E | Exhaust gas temperature before upstream catalytic converter | ×0.1 -273.14 | CMM_DCM71: ×0.1 -50; DCM62: ×0.0625 -50 |
| Peugeot-2008 | 6A8 | 22 D534 | Vehicle mileage since the last oil change under severe vehicle operating conditions | ×0.1 +0 | CMM_DCM71: ×0.0625 +0; DCM62: ×1 +0 |
| Peugeot-2008 | 6A8 | 22 D545 | Nitrogen oxide content measured at the inlet of the catalytic converter, ppm | ×0.1 +0 | DCM62: ×1 +0 |
| Peugeot-2008 | 6A8 | 22 D54E | Air temperature by air flow meter | ×0.1 -273.14 | DCM62: ×1 -50; EDC17C60: ×1 +0 |
| Peugeot-2008 | 6A8 | 22 D54F | Air metering valve position | ×0.01 +0 | DCM62: ×0.004 +0; EDC17C60: ×0.004 +0 |
| Peugeot-2008 | 6A8 | 22 D555 | Exhaust gas temperature after upstream catalytic converter | ×0.1 -273.14 | CMM_DCM71: ×1 -50; DCM62: ×1 -50 |
| Peugeot-2008 | 6A8 | 22 D556 | Turbocharger pressure reference | ×1 +0 | CMM_DCM71: ×1 +300; EDC17C60: ×0.001 +0 |
| Peugeot-2008 | 6A8 | 22 D559 | Upstream catalytic converter aging | ×0.1 +0 | CMM_DCM71: ×0.0976562 +0; EDC17C60: ×0.001 +0 |
| Peugeot-2008 | 6A8 | 22 D563 | Calculated fuel injection amount | ×0.1 +0 | DCM62: ×0.0625 +0 |
| Peugeot-2008 | 6A8 | 22 D56C | Amount of fuel additive remaining in the additive tank | ×0.1 +0 | CMM_DCM71: ×0.0534066 +0 |
| Peugeot-2008 | 6A8 | 22 D713 | Reversible generator voltage | ×0.0625 +0 | DCM62: ×0.025 +10.6 |
| Peugeot-2008 | 6A8 | 22 D721 | Temperature of the energy accumulator | ×0.5 +0 | DCM62: ×0.5 -30; EDC17C60: ×0.5 -30 |
| Peugeot-2008 | 6A8 | 22 D72D | Evaluation of the total resistance of the voltage maintaining device | ×7.81e-06 +0 | DCM62: ×0.0078125 +0 |
| Peugeot-2008 | 6A8 | 22 D730 | Temperature of the electronic power unit of the voltage maintenance device | ×0.5 +0 | DCM62: ×0.5 -30; EDC17C60: ×0.5 -30 |
| Peugeot-2008 | 6A8 | 22 D734 | Set voltage of energy storage | ×0.03125 +0 | CMM_DCM71: ×0.05 +0 |
| Peugeot-2008 | 6A8 | 22 D73B | Vacuum circuit vacuum | ×1 +0 | DCM62: ×1 +20 |
| Peugeot-2008 | 6A8 | 22 D78A | Service battery voltage | ×0.0078125 +0 | DCM62: ×0.0078125 +5 |
| Peugeot-2008 | 6A9 | 21 C3 | Accelerator pedal position | ×0.39 +0 | BVA_AM6: ×10 +0; BVA_AM6_AT6_EURO5: ×10 +0 |
| Peugeot-2008 | 6A9 | 21 C5 | Commanded oil pressure | ×0.1 +0 | BVA_AL4: ×0.05 +0 |
| Peugeot-2008 | 6A9 | 21 C5 | Actual oil pressure | ×0.05 +0 | BVA_AL4: ×0.4 +0 |
| Peugeot-2008 | 6A9 | 22 D530 | Average front wheel speed | ×0.04 +0 | DCT_6DCT200: ×0.125 +0 |
| Peugeot-2008 | 6A9 | 22 D531 | Rear left wheel speed | ×0.01 +0 | DCT_6DCT200: ×0.5 +0 |
| Peugeot-2008 | 6A9 | 22 D532 | Rear right wheel speed | ×0.01 +0 | DCT_6DCT200: ×0.5 +0 |
| Peugeot-2008 | 6A9 | 22 D533 | Average speed front wheels | ×0.01 +0 | DCT_6DCT200: ×0.125 +0 |
| Peugeot-2008 | 6A9 | 22 D553 | Engine operating mode | ×0.125 +0 | DCT_6DCT200: ×0.5 +0 |
| Peugeot-2008 | 6A9 | 22 D554 | Real engine torque | ×2 -100 | BVA_EATN8: ×1 -200 |
| Peugeot-2008 | 6A9 | 22 D650 | Automatic transmission input shaft mode, measured | ×2 +0 | DCT_6DCT200: ×0.5 +0 |
| Peugeot-2008 | 6A9 | 22 D651 | Automatic transmission input shaft mode, calculated | ×2 +0 | DCT_6DCT200: ×0.5 +0 |
| Peugeot-2008 | 6A9 | 22 D653 | Automatic transmission output shaft mode, calculated | ×64 -8128 | BVA_EATN8: ×1 +0; DCT_6DCT200: ×0.125 +0 |
| Peugeot-2008 | 6A9 | 22 D801 | Vehicle hour meter | ×0.1 +0 | BVA_AM6_AT6_UDS: ×1 +0; BVMP_MCP_UDS: ×1 +0 |
| Peugeot-2008 | 6B4 | 22 D43B | Highest voltage cell number | ×1 +1 | BMU_CTE1: ×1 +0 |
| Peugeot-2008 | 6B4 | 22 D43C | Lowest voltage cell number | ×1 +1 | BMU_CTE1: ×1 +0 |
| Peugeot-2008 | 6B4 | 22 D445 | Battery max unit temperature number | ×1 +1 | BMU_CTE1: ×1 +0 |
| Peugeot-2008 | 6B4 | 22 D446 | Battery min unit temperature number | ×1 +1 | BMU_CTE1: ×1 +0 |
| Peugeot-2008 | 6B4 | 22 D49C | Odometer | ×0.001 +0 | BMU_CTE1: ×0.000976562 +0; TBMU_PHEV: ×1 +0 |
| Peugeot-2008 | 6B4 | 22 D810 | High voltage battery charge | ×0.00195312 +0 | TBMU_PHEV: ×0.25 +0 |
| Peugeot-2008 | 6B4 | 22 D815 | High voltage battery voltage | ×0.0625 +0 | TBMU_PHEV: ×0.25 +0 |
| Peugeot-2008 | 6B4 | 22 D817 | Battery max unit temperature | ×1 -40 | TBMU_PHEV: ×1 +0 |
| Peugeot-2008 | 6B4 | 22 D822 | Service cell voltage value | ×0.001 +0 | TBMU_PHEV: ×0.01 +0 |
| Peugeot-2008 | 6B4 | 22 D865 | HV battery energy | ×0.015625 +0 | TBMU_PHEV: ×16000 +0 |
| Peugeot-2008 | 6B4 | 22 D86F | High voltage minimum voltage | ×1 +0 | BMU_CTE1: ×0.001 +0 |
| Peugeot-2008 | 6B4 | 22 D870 | High voltage maximum voltage | ×1 +0 | BMU_CTE1: ×0.001 +0 |
| Peugeot-2008 | 6B4 | 22 D871 | Maximum power the battery can withstand for 10 seconds | ×0.007812 -500 | TBMU_PHEV: ×100 -150000 |
| Peugeot-2008 | 6B4 | 22 D872 | Maximum power the battery can withstand for 30 seconds | ×0.007812 -500 | TBMU_PHEV: ×250 -150000 |
| Peugeot-2008 | 6B4 | 22 D873 | Maximum power the battery can provide for 10 seconds | ×0.007812 -500 | TBMU_PHEV: ×100 +0 |
| Peugeot-2008 | 6B4 | 22 D874 | Maximum power the battery can provide for 30 seconds | ×0.007812 -500 | TBMU_PHEV: ×200 +0 |
| Peugeot-2008 | 6B4 | 22 D87D | Battery min unit temperature | ×1 -40 | TBMU_PHEV: ×1 +0 |
| Peugeot-208 | 6A2 | 22 D8FF | Air conditioning high side pressure | ×0.01 +0 | E_VCU: ×0.1 +0 |
| Peugeot-208 | 6A6 | 22 D49C | Odometer | ×1 +0 | MCU_ECMP: ×0.1 +0 |
| Peugeot-208 | 6A8 | 22 D472 | Low pressure fuel pump actuator duty cycle raw | ×1 +0 | CMM_DCM71: ×0.01 +0; CMM_MD1CS003: ×0.01 +0; CMM_MG1CS032: ×0.01 +0; CMM_MG1CS042: ×0.01 +0; CMM_MG1CS042_PHEV: ×0.01 +0; CMM_VD56: ×0.1 +0; DCM62: ×0.0625 +0; EDC17C60: ×0.01 +0; MED17_4_4: ×0.1 +0; MED17_4_4_EP8: ×0.1 +0; MEVD17_4_4: ×0.1 +0; VD46: ×0.1 +0 |
| Peugeot-3008 | 6A8 | 22 D435 | Computer temperature raw | ×1 +0 | DCM62: ×1 -40 |
| Peugeot-308 | 6A8 | 22 D472 | Low pressure fuel pump actuator duty cycle, raw | ×1 +0 | CMM_DCM71: ×0.01 +0; CMM_MD1CS003: ×0.01 +0; CMM_MG1CS032: ×0.01 +0; CMM_MG1CS042: ×0.01 +0; CMM_MG1CS042_PHEV: ×0.01 +0; CMM_VD56: ×0.1 +0; DCM62: ×0.0625 +0; EDC17C60: ×0.01 +0; MED17_4_4: ×0.1 +0; MED17_4_4_EP8: ×0.1 +0; MEVD17_4_4: ×0.1 +0; VD46: ×0.1 +0 |
| Peugeot-308 | 6B4 | 22 D804 | High voltage bus voltage | ×0.1 +0 | TBMU_PHEV: ×0.25 +0 |
| Peugeot-308 | 6B4 | 22 D815 | Traction battery voltage | ×0.1 +0 | BMU_CTE1: ×0.0625 +0; TBMU_PHEV: ×0.25 +0 |
| Peugeot-308 | 6B4 | 22 D865 | Traction battery state of charge, high resolution | ×0.01 +0 | BMU_CTE1: ×0.015625 +0; TBMU_PHEV: ×16000 +0 |
| Peugeot-308 | 6B5 | 22 D405 | Power steering module temperature | ×1 -40 | GEP_UDS: ×1 +0 |
| Peugeot-308 | 752 | 22 DB28 | Speed setpoint step | ×0.1 +0 | BSI2010: ×1 +0; BSI2010_EV: ×1 +0 |
| Peugeot-308 | 752 | 22 DB83 | Front passenger seatbelt | ×1 +0 | BSI2010: ×1 -40; BSI2010_EV: ×1 -40 |
| Peugeot-508 | 6A8 | 22 D517 | Remaining urea solution in the urea tank | ×0.976562 +0 | CMM_DCM71: ×0.000976562 +0; EDC17C60: ×0.000976562 +0 |
| Peugeot-508 | 6A8 | 22 D518 | Diesel particulate filter differential pressure sensor signal deviation from reference value | ×1 +0 | DCM62: ×4 -2500; EDC17C60: ×0.054025 -52.3 |
| Peugeot-508 | 6A8 | 22 D520 | Particulate filter differential pressure | ×1 +0 | CMM_DCM71: ×0.001 +0; DCM62: ×0.001 +0 |
| Peugeot-508 | 6A8 | 22 D527 | Total mass of additive accumulated in the diesel particulate filter | ×0.03125 +0 | CMM_DCM71: ×0.030518 +0; DCM62: ×1 +0 |
| Peugeot-508 | 6A8 | 22 D531 | Mileage since last diesel particulate filter replacement | ×8 +0 | CMM_DCM71: ×2 +0 |
| Peugeot-508 | 6A8 | 22 D532 | Assessment of thermal aging of the particulate filter | ×0.195312 +0 | CMM_DCM71: ×0.0976562 +0 |
| Peugeot-508 | 6A8 | 22 D53C | Power spent on heating the urea injection system pipes, percentage ratio | ×0.00610352 +0 | DCM62: ×1 +0 |
| Peugeot-508 | 6A8 | 22 D53F | Calculated nox content at the inlet of the nox catalytic converter | ×0.000976562 +0 | CMM_DCM71: ×0.001 +0 |
| Peugeot-508 | 6A8 | 22 D541 | Evaluation of the thermal aging of the NOx catalytic converter | ×0.00152588 +0 | EDC17C60: ×1.52588e-05 +0 |
| Peugeot-508 | 6A8 | 22 D543 | Temperature of the nox catalytic converter | ×0.0625 +0 | DCM62: ×0.0625 -50 |
| Peugeot-508 | 6A8 | 22 D544 | Exhaust gas pressure at the outlet of the particulate filter | ×0.001 +0 | DCM62: ×1 +0 |
