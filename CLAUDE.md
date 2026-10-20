# Voltdeck

Le dépôt est **public**. Tout ce qui y entre est lu par n'importe qui : code, documents, messages de commit, titres et descriptions de PR, commentaires et revues. Écrire pour un contributeur extérieur qui ne connaît ni l'équipe ni ses outils.

## Contenu public

- **Aucun nom de personne ni de rôle interne** : ni prénom, ni « le fondateur », ni nom d'agent. Écrire « en revue », « à vérifier sur téléphone », « décision du 2026-10-16 ».
- **Aucune référence à l'outil d'orchestration** : pas de nom d'outil, pas d'URL interne, pas de lien vers un ticket. Une clé de ticket (`VOL-12`) peut finir un titre de PR, jamais le remplacer : le texte doit se comprendre sans elle.
- **Aucune attribution d'outil** : pas de trailer `Co-Authored-By` vers un assistant, pas de pied de page « généré par », pas de lien de session.
- **Aucune donnée du matériel réel** : ni adresse MAC, ni clé Victron, ni numéro de série, ni lieu. Les captures viennent du mode démo. `scripts/ci/check-secrets.sh` bloque les clés et les adresses, pas le reste.
- **Aucune information sur l'organisation du travail** : quotas, facturation, minutes de CI, qui relit quoi. Seule compte la raison technique d'un choix.

## Commits et PR

- Branches `type/sujet` (`feat/vue-annee`, `fix/tests-ios-storage`), jamais de travail direct sur `main`.
- Messages en français, à l'impératif, titre sous 72 caractères ; le corps dit pourquoi, pas comment.
- PR : ce qui change et pourquoi, ce qui a été vérifié, ce qui reste. Pas de tiret cadratin.

## Tester en local, puis pousser

- Chaque modification est testée en local avant d'être poussée : `./gradlew check` et les trois scripts de `scripts/ci/`. Si le code partagé ou iOS change, `./gradlew iosSimulatorArm64Test` sur un Mac ; sans Mac, la PR l'écrit (« tests iOS non lancés »).
- La PR liste les commandes lancées et leur résultat. Elle n'attend pas la CI pour être fusionnée.
- La CI automatique ne lance que les contrôles rapides (`scripts/ci/`). S'ils sont rouges, c'est un vrai défaut (écriture BLE, secret) : on corrige avant de fusionner.
- Tests Android et iOS en CI : à la demande seulement (Actions > CI > Run workflow, case « ios » pour le simulateur iOS).

## Lecture seule

Seul `ble/.../KableTransport.kt` a le droit d'écrire en Bluetooth (`scripts/ci/check-readonly.sh`). Aucune permission `INTERNET` (contrôlée au build).
