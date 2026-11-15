# GHBprontoncharge - boucle de debug rapide

- Package / applicationId : `com.example.ghbprontoncharge`
- Appareil : Pixel 10 (Android 17) en USB. Si plusieurs appareils sont branches (ex. la TV Fire OS en ADB Wi-Fi),
  definir `ANDROID_SERIAL` dans le terminal avant de lancer Claude Code (numero donne par `adb devices`).

## Cycle standard
1. Compiler + installer : `./gradlew installDebug`
2. Ouvrir l'app (lance le service) : `adb shell am start -n com.example.ghbprontoncharge/.MainActivity`
   ChargingActivity n'est PAS exportee : `am start` dessus est refuse. Pour l'afficher, simuler un branchement
   (section plus bas) au lieu de modifier le manifeste.
3. Logs de l'app seulement :
   `adb logcat -c` puis `adb logcat --pid=$(adb shell pidof -s com.example.ghbprontoncharge)`
   Plantage : `adb logcat -b crash -d`
4. Capture d'ecran : `adb shell screencap -p /sdcard/cap.png` puis `adb pull /sdcard/cap.png`

## Simuler le branchement sans toucher au cable
- Debrancher : `adb shell dumpsys battery unplug`
- Brancher (secteur) : `adb shell dumpsys battery set ac 1`
- Niveau de batterie pour tester la power cell : `adb shell dumpsys battery set level 42`
- TOUJOURS remettre a la fin : `adb shell dumpsys battery reset`

## Regles
- Ne pas modifier minSdk / compileSdk / targetSdk ni libs.versions.toml sans demander.
- Ne jamais toucher aux autres projets du dossier AndroidStudioProjects.
- Pour verifier un rendu visuel, utiliser le MCP mobile-mcp (capture + tap) plutot que de deviner.
