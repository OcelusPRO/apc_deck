# APC Deck

Cadre à plugins pour l'AKAI APC Key 25 mk2 : l'application tourne en arrière-plan (icône de la zone de
notification) et son interface s'ouvre dans le navigateur par défaut.

## Modules

| Module | Rôle |
|---|--- |
| `api` | API publique des plugins (événements, LED, configuration, données, interface web, son) |
| `core` | Moteur commun au PC et à Android : protocole de l'APC, routage, plugins, serveur web local, interface (`ui/`) |
| `desktop` | Implémentations PC du cœur : MIDI (`javax.sound.midi`), son (`javax.sound.sampled`), jars JVM, mises à jour |
| `app` | Application PC : point d'entrée, icône de notification, packaging |
| `plugins/macros` | Plugin Macros (scripts shell sur les pads), livré avec l'application |
| `plugins/synth` | Plugin Synthé (synthé polyphonique joué au clavier), livré avec l'application |
| `plugins/soundboard` | Plugin Soundboard (un son par pad, sur 40 pages), livré avec l'application |

## APC virtuel

Sans APC branché, le bouton **APC virtuel** de la barre du haut transforme la vue de l'appareil en APC jouable :
pads, boutons et touches system à la souris ou au doigt (multi-touch), potars à la molette ou en glissant
verticalement, clavier de 25 touches avec octaves. Le clavier de l'ordinateur joue aussi les notes (positions
physiques : rangée du milieu pour les touches blanches, rangée du dessus pour les dièses, `W` / `X` en AZERTY pour
l'octave). Les plugins reçoivent les mêmes événements qu'avec l'appareil. Le réglage est conservé ; dès qu'un APC
réel est branché il reprend la main, et l'APC virtuel revient s'il est débranché.

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
version packagée, les plugins livrés (Macros, Synthé, Soundboard) y sont copiés dans `plugins/`.

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
- **Plugins** : un plugin peut déclarer d'où viennent ses mises à jour dans son `plugin.json` ; l'application
  propose alors de le mettre à jour, à chaud, sans redémarrer. Deux possibilités :
  - `"repository": "<adresse du dépôt git>"` : GitHub (et GitHub Enterprise), GitLab (gitlab.com ou auto-hébergé,
    sous-groupes compris), Gitea/Forgejo (Codeberg ou auto-hébergé). Formes acceptées : `https://hôte/chemin`,
    `git@hôte:chemin.git`, `owner/repo` (GitHub). La forge d'un hôte auto-hébergé est détectée toute seule. La
    version de la dernière release (tag `v1.2.0` ou `1.2.0`) est comparée à celle du plugin ; la release doit
    contenir le jar sous le nom `<id>.jar` (nom du fichier, ou fin de l'URL d'un lien de release GitLab). Les
    dépôts doivent être publics. Bitbucket ne publie pas de releases : utiliser `updateUrl`.
  - `"updateUrl": "https://…/mon-plugin.jar"` : adresse directe du jar. Il n'est retéléchargé que s'il a changé
    (ETag / Last-Modified) et la mise à jour est proposée dès que son contenu diffère du jar installé, même sans
    changement de version (jamais vers une version plus ancienne).
- **Plugins officiels** (Macros, Synthé, Soundboard) : même numéro de version que l'application (`"version": "${version}"` dans
  leur `plugin.json`, remplacé au build par `appVersion`), joints à chaque release sous le nom `<id>.jar`
  (`./gradlew :app:packagePlugins`) et mis à jour avec elle.
