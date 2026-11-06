#!/usr/bin/env bash
# Construit l'AAB de release signé par la clé d'envoi et le publie sur une piste Play, depuis
# le NAS (pas GitHub Actions : minutes de CI comptées). Modèle : template-android (DMC-325/330).
#
# Usage : scripts/play-publish.sh [internal|alpha|beta|production] [--release-status draft|completed] [--listing]
#   internal (par défaut) = test interne, alpha = test fermé. Les agents y publient seuls.
#   beta (test ouvert) et production sont publics : il faut MICKA_APPROVED_ISSUE=<ID>, la
#   tâche qui porte l'accord écrit de Micka pour cette version.
#   --release-status : draft par défaut (Play ne prend que des brouillons tant que l'app n'est
#   pas publiée).
#   --listing : publie aussi la fiche du store (composeApp/src/main/play) ; public, donc
#   MICKA_APPROVED_ISSUE=<ID> exigé aussi.
#
# Secrets lus dans l'environnement (secrets Paperclip), jamais affichés :
#   <PREFIXE>_UPLOAD_KEYSTORE_B64, <PREFIXE>_UPLOAD_KEYSTORE_PASSWORD, <PREFIXE>_UPLOAD_KEY_ALIAS,
#   <PREFIXE>_UPLOAD_KEY_PASSWORD (PREFIXE = $PLAY_SECRET_PREFIX, sinon VOLTDECK), et
#   PLAY_SERVICE_ACCOUNT_JSON (contenu de la clé du compte de service). Keystore et clé vont
#   dans un dossier temporaire privé, effacé à la fin.
set -euo pipefail
cd "$(dirname "$0")/.."

APP_SECRET_PREFIX=VOLTDECK

die() { echo "play-publish: $2" >&2; exit "$1"; }

TRACK=internal; STATUS=draft; LISTING=0
while [ $# -gt 0 ]; do
    case $1 in
        internal|alpha|beta|production) TRACK=$1 ;;
        --release-status) [ $# -ge 2 ] || die 2 "--release-status exige une valeur"; STATUS=$2; shift ;;
        --listing) LISTING=1 ;;
        *) die 2 "argument inconnu '$1'" ;;
    esac
    shift
done
case $STATUS in draft|completed) ;; *) die 2 "--release-status vaut draft ou completed" ;; esac

if [ "$TRACK" = beta ] || [ "$TRACK" = production ]; then
    [[ ${MICKA_APPROVED_ISSUE:-} =~ ^[A-Z]+-[0-9]+$ ]] ||
        die 3 "la piste '$TRACK' est publique : MICKA_APPROVED_ISSUE=<ID>, la tâche avec l'accord écrit de Micka"
    echo "play-publish: piste $TRACK, accord sur $MICKA_APPROVED_ISSUE"
fi

# La fiche du store est publique, quelle que soit la piste.
if [ "$LISTING" = 1 ]; then
    [[ ${MICKA_APPROVED_ISSUE:-} =~ ^[A-Z]+-[0-9]+$ ]] ||
        die 3 "--listing est public : MICKA_APPROVED_ISSUE=<ID>, la tâche avec l'accord écrit de Micka"
    ls composeApp/src/main/play/listings/*/title.txt >/dev/null 2>&1 ||
        die 6 "--listing : aucune fiche dans composeApp/src/main/play/listings/<langue>/ (rien ne serait publié)"
fi

P=${PLAY_SECRET_PREFIX:-$APP_SECRET_PREFIX}
KS_B64=${P}_UPLOAD_KEYSTORE_B64 KS_PASS=${P}_UPLOAD_KEYSTORE_PASSWORD
KEY_ALIAS=${P}_UPLOAD_KEY_ALIAS KEY_PASS=${P}_UPLOAD_KEY_PASSWORD
missing=()
for v in "$KS_B64" "$KS_PASS" "$KEY_ALIAS" "$KEY_PASS" PLAY_SERVICE_ACCOUNT_JSON; do
    [ -n "${!v:-}" ] || missing+=("$v")
done
[ ${#missing[@]} -eq 0 ] || die 4 "il manque ${missing[*]}"

# Publier un commit, pas un arbre de travail : le SHA affiché doit être ce qui est parti.
[ -z "$(git status --porcelain -- composeApp core ble drivers simulator gradle build.gradle.kts settings.gradle.kts gradle.properties)" ] ||
    die 5 "modifications non commitées dans le code ou les fichiers de build"

# Seulement un commit de main : les fusions sont en squash, un SHA de branche disparaîtrait.
git fetch -q origin main || die 5 "git fetch origin main a échoué"
git merge-base --is-ancestor HEAD origin/main || die 5 "HEAD n'est pas un commit de origin/main"

umask 077
TMP=$(mktemp -d "${PAPERCLIP_RUN_SCRATCH_DIR:-${TMPDIR:-/tmp}}/play-publish.XXXXXX")
trap 'rm -rf "$TMP"' EXIT
printf '%s' "${!KS_B64}" | base64 -d > "$TMP/upload.keystore" 2>/dev/null ||
    die 4 "$KS_B64 n'est pas du base64 valide"
printf '%s' "$PLAY_SERVICE_ACCOUNT_JSON" > "$TMP/play.json"
python3 -I - "$TMP/play.json" <<'PY' || die 4 "PLAY_SERVICE_ACCOUNT_JSON n'est pas une clé de compte de service (JSON)"
import json, sys
try:
    key = json.load(open(sys.argv[1]))
except ValueError:
    sys.exit(1)
sys.exit(0 if isinstance(key, dict) and key.get("type") == "service_account" else 1)
PY

export UPLOAD_KEYSTORE_PATH="$TMP/upload.keystore"
export UPLOAD_KEYSTORE_PASSWORD="${!KS_PASS}" UPLOAD_KEY_ALIAS="${!KEY_ALIAS}" UPLOAD_KEY_PASSWORD="${!KEY_PASS}"
export PLAY_SERVICE_ACCOUNT_FILE="$TMP/play.json"
export JAVA_HOME=${JAVA_HOME:-/opt/android/jdk}
# Gradle n'a besoin que des chemins ci-dessus, pas des secrets bruts.
unset "$KS_B64" PLAY_SERVICE_ACCOUNT_JSON

tasks=(:composeApp:publishReleaseBundle --track "$TRACK" --release-status "$STATUS")
[ "$LISTING" = 0 ] || tasks+=(:composeApp:publishReleaseListing)
echo "play-publish: $(git rev-parse --short HEAD) -> $TRACK ($STATUS)"
sh ./gradlew --no-daemon --console=plain "${tasks[@]}"

AAB=composeApp/build/outputs/bundle/release/composeApp-release.aab
CODES=$(find composeApp/build/intermediates -path '*release*' -name available-version-codes.txt 2>/dev/null | head -1)
echo "play-publish: fait. commit $(git rev-parse HEAD), piste $TRACK, statut $STATUS"
[ -z "$CODES" ] || echo "play-publish: versionCode $(head -1 "$CODES")"
sha256sum "$AAB"
