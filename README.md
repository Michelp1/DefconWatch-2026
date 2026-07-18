# DefconWatch Android v2.0

Een native Android-dashboard voor publieke wereldwijde incidentinformatie.

## Belangrijk
DefconWatch heeft **geen toegang tot officiële, geclassificeerde DEFCON-niveaus**. De getoonde readiness-index is een transparante, niet-officiële OSINT-indicatie op basis van openbare incidentfeeds.

## Ingebouwd
- Wereldkaart zonder externe kaart-API of sleutel
- Live significante aardbevingen via USGS GeoJSON
- Live rampmeldingen via GDACS RSS
- Regionale incidenttellers
- Automatische verversing elke 15 minuten terwijl de app geopend is
- Handmatige vernieuwingsknop
- Lokale waarschuwing bij incidenten met hoge ernst
- GitHub Actions die automatisch Gradle 9.1.0 installeert en een debug-APK bouwt

## Uploaden naar GitHub
Kopieer **de inhoud van deze map** naar de lokale map van jouw repository. Commit daarna in GitHub Desktop en klik op **Push origin**.

## APK downloaden
1. Open de repository op GitHub.
2. Open **Actions**.
3. Open de nieuwste groene build.
4. Download onder **Artifacts**: `DefconWatch-v2-debug-apk`.
5. Pak het ZIP-bestand uit en installeer `app-debug.apk`.

## Bronnen
- USGS Earthquake Hazards Program
- GDACS (Global Disaster Alert and Coordination System)

## Privacy
De app vraagt geen locatie en verzendt geen gebruikersgegevens.
