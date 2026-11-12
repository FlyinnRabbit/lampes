# 💡 Lampes

Contrôle des lampes LED Bluetooth (sticks ELK-BLEDOM / MELK) et du rideau Hello Fairy 20×20 (Action),
sans les applications du fabricant.

## Installer sur Android

1. Ouvre la page **[Releases](../../releases/latest)** sur ton téléphone.
2. Télécharge **Lampes.apk** et ouvre-le. Autorise « installer des applications inconnues » si Android le demande.
3. Au premier lancement, accepte les autorisations : appareils à proximité (Bluetooth) et micro (mode musique).

Chaque modification poussée sur `main` fabrique automatiquement un nouvel APK (onglet **Actions**).
Il s'installe par-dessus l'ancien sans perdre les réglages.

## Sur PC (Windows)

La même page fonctionne dans Chrome ou Edge : `web/lampes-bt.html`.
Le dessin sur le rideau n'y est pas disponible (le Bluetooth du PC limite la taille des paquets).

## Contenu

- `web/lampes-bt.html` : l'application (une seule page, PC et Android)
- `android/` : l'app Android (affiche la page, Bluetooth natif avec paquets de 512 octets, analyse du son du téléphone)
- `.github/workflows/apk.yml` : fabrication et publication de l'APK

Protocole du rideau d'après [nzrutman/hello_fairy_ble](https://github.com/nzrutman/hello_fairy_ble).
