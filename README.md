# DefconWatch Android v3.1

Installeerbare Android OSINT-app met openbare incidentfeeds.

## Functies
- USGS significante aardbevingen
- GDACS rampenmeldingen
- Live ISS-positie
- Interactieve wereldkaart
- Zoek- en filterfuncties
- Wereldrisicoscore en Mission Brief
- Offline cache en meldingsdrempel

## APK via GitHub Actions
1. Upload de volledige inhoud van deze map naar de hoofdmap van de GitHub-repository.
2. Open **Actions**.
3. Kies **Build DefconWatch APK**.
4. Kies **Run workflow**.
5. Open de geslaagde build en download **DefconWatch-v3.1-APK** onder Artifacts.

De workflow gebruikt zelf Gradle 8.9. Daardoor zijn `gradlew` en de Gradle Wrapper niet nodig voor de GitHub-build.

## Android Studio
Open deze projectmap in Android Studio en laat Gradle synchroniseren. Daarna kan via **Build > Build APK(s)** lokaal een APK worden gemaakt.
