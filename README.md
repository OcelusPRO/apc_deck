# APC Deck

Cadre à plugins pour l'AKAI APC Key 25 mk2 : l'application tourne en arrière-plan (icône de la zone de
notification) et son interface s'ouvre dans le navigateur par défaut.

## Modules

| Module | Rôle |
|---|--- |
| `api` | API publique des plugins (événements, LED, configuration, données, interface web) |
| `core` | Moteur : MIDI, routage, chargement des jars, serveur web local, interface (`src/main/resources/ui`) |
| `app` | Point d'entrée, icône de notification, packaging |
| `plugins/macros` | Plugin Macros (scripts shell sur les pads), livré avec l'application |

## Développement

```bash
./gradlew :app:run                         # lance l'app (données dans ./run) et ouvre l'interface
./gradlew :app:run --args="--no-open"      # sans ouvrir le navigateur (l'adresse est dans le journal)
./gradlew build                            # compilation + tests
```

## Exécutables

`jpackage` ne fait pas de compilation croisée : chaque OS produit ses propres exécutables (Java embarqué,
rien à installer pour l'utilisateur). Résultats dans `app/build/dist/`.

| Commande | Résultat |
|---|---|
| `./gradlew :app:packageApp` | Archive portable de l'OS courant : `.zip` (Windows) ou `.tar.gz` (Linux, macOS) |
| `./gradlew :app:packageInstaller` | Installeur : `.exe` (Windows, [WiX](https://wixtoolset.org) requis), `.deb` (Linux), `.dmg` (macOS) |
| `./gradlew :app:packageJar` | Jar unique pour les trois OS : `java -jar APCDeck-1.0.0-all.jar` (Java 25 requis) |

Configuration et données (dossier créé au premier lancement) : `%APPDATA%\.APC_Deck` (Windows),
`~/Library/Application Support/.APC_Deck` (macOS), `~/.config/.APC_Deck` (Linux). Au premier lancement d'une
version packagée, les plugins livrés (Macros) y sont copiés dans `plugins/`.

## Releases et mises à jour

- **Branche `dev`** : développement. Chaque push lance la compilation et les tests (`.github/workflows/ci.yml`).
- **Branche `main` (ou `master`)** : chaque commit construit Windows, Linux et macOS sur des machines de chaque OS
  et publie une release GitHub (`.github/workflows/package.yml`). La version est `appVersion` de
  `gradle.properties` dont le dernier nombre est remplacé par le numéro du build (`1.0.0` -> `1.0.42`) ; pour passer
  en 1.1, modifier `appVersion=1.1.0`.
- **Application installée** : elle consulte les releases de [OcelusPRO/apc_deck](https://github.com/OcelusPRO/apc_deck)
  au démarrage puis toutes les 6 heures (ou via « Rechercher des mises à jour » dans l'icône de notification, ou
  le numéro de version en haut de l'interface). Une version plus récente est signalée par une notification et un
  bandeau ; « Installer » télécharge l'installeur de l'OS, le lance et ferme l'application. Lancée depuis les
  sources (`gradlew :app:run`), elle ne cherche pas de mises à jour.
- **Plugins** : un plugin peut déclarer son dépôt GitHub dans son `plugin.json`
  (`"repository": "https://github.com/<owner>/<repo>"`). L'application compare la version de la dernière release
  du dépôt (tag `v1.2.0` ou `1.2.0`) à celle du plugin et propose de le mettre à jour, à chaud, sans redémarrer.
  La release doit contenir le jar sous le nom `<id>.jar`.
- **Plugins officiels** (Macros, Synthé) : même numéro de version que l'application (`"version": "${version}"` dans
  leur `plugin.json`, remplacé au build par `appVersion`), joints à chaque release sous le nom `<id>.jar`
  (`./gradlew :app:packagePlugins`) et mis à jour avec elle.
