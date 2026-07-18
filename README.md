# DefconWatch Android v2.1

Een native Android-dashboard voor openbare wereldwijde incidentinformatie.

## Nieuw in v2.1
- Incidentfilters: alles, kritiek, aardbevingen en rampen
- Incidentkaarten zijn aanklikbaar en openen de originele bron
- Lokale offline cache van de laatst gesynchroniseerde incidenten
- Verbeterde readiness-berekening en regionale tellingen
- Kritieke notificatie opent direct het bronbericht
- Vernieuwde kaartmarkeringen en statusmeldingen
- GitHub Actions-workflow bijgewerkt voor AGP 9.2 en Gradle 9.4.1

## Belangrijk
DefconWatch heeft **geen toegang tot officiële, geclassificeerde DEFCON-niveaus**. De getoonde readiness-index is een transparante, niet-officiële OSINT-indicatie op basis van openbare incidentfeeds.

## Live bronnen
- USGS Earthquake Hazards Program
- GDACS (Global Disaster Alert and Coordination System)

## Automatische APK-build
Na iedere push naar `main` start GitHub Actions automatisch een debugbuild.

1. Open **Actions** in de repository.
2. Open de nieuwste groene run.
3. Download onder **Artifacts**: `DefconWatch-v2.1-debug-apk`.
4. Pak de artifact-ZIP uit en installeer `app-debug.apk`.

## Privacy
De app vraagt geen locatie en verzendt geen gebruikersgegevens. Alleen openbare feeds worden opgehaald. De incidentcache blijft lokaal op het apparaat.
