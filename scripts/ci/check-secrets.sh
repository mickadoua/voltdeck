#!/usr/bin/env bash
# Grep anti-secret (VOL-1 §8, R8) : le dépôt deviendra public.
#  - 32 caractères hexadécimaux d'affilée (forme d'une clé Victron Instant Readout) ;
#  - adresse MAC complète (6 octets séparés par « : »).
# Les fichiers qui contiennent la mention « TEST ONLY » (vecteurs de test publics) sont ignorés.
# Usage : check-secrets.sh [racine-du-dépôt]
set -euo pipefail

root="${1:-.}"
cd "$root"

status=0
check() {
  local label="$1" pattern="$2" file
  while IFS= read -r file; do
    if grep -q 'TEST ONLY' "$file"; then continue; fi
    echo "ECHEC secret ($label) dans $file :" >&2
    grep -nE "$pattern" "$file" | sed 's/^/    /' >&2
    status=1
  done < <(grep -rlIE "$pattern" . \
    --exclude-dir=build --exclude-dir=.git --exclude-dir=.gradle --exclude-dir=.kotlin --exclude-dir=.idea || true)
}

check "32 caractères hexadécimaux" '\b[0-9A-Fa-f]{32}\b'
check "adresse MAC" '\b([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}\b'

if [ "$status" -eq 0 ]; then echo "OK secrets : rien de suspect"; fi
exit "$status"
