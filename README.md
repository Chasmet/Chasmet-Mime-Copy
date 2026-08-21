# Mime & Copie

Application Android locale qui apprend une suite d’actions effectuées par l’utilisateur puis la rejoue via `AccessibilityService`.

## Fonctionnement

1. Activer **Mime & Copie** dans les réglages d’accessibilité Android.
2. Dans l’application, donner un nom à la routine et toucher **MONTRER / ENREGISTRER**.
3. Effectuer les actions normalement dans les autres applications.
4. Utiliser la barre flottante pour enregistrer explicitement **Retour**, **Accueil** et **STOP**.
5. Revenir dans l’application et toucher **COPIER / REJOUER**.

## Actions enregistrées

- clic ;
- appui long ;
- texte hors champs mot de passe ;
- défilement ;
- Retour ;
- Accueil.

Le moteur cherche d’abord une vue Android par identifiant/texte/description, puis utilise sa position relative comme secours.

## Limites connues

- Certaines applications rendent volontairement leurs éléments invisibles aux services d’accessibilité.
- Une interface très différente entre l’apprentissage et le rejeu peut nécessiter un nouvel apprentissage.
- L’application ne contourne pas les écrans protégés ni les champs mot de passe.

## Build

```bash
./gradlew assembleDebug
```

APK : `app/build/outputs/apk/debug/app-debug.apk`
