#!/usr/bin/env bash
# Grep anti-secret (VOL-1 §8, R8) : le dépôt deviendra public.
# Formes recherchées, dans tous les fichiers (binaires compris) :
#  - 32 caractères hexadécimaux d'affilée, avec ou sans « 0x », collés ou non à des lettres/« _ » (clé Victron) ;
#  - 16 octets hexadécimaux à la suite : « 01 23 … », « 0x01, 0x23, … », « 01:23:… », « 0x01.toByte(), … » ;
#  - forme UUID 8-4-4-4-12, sauf UUID Bluetooth standard (suffixe de la base 0000-1000-8000-00805f9b34fb)
#    et identifiants factices du simulateur (préfixe 00000000-) ;
#  - adresse MAC complète, séparée par « : » ou « - ».
# Exemption : un fichier de test (chemin …/src/<jeu de sources>Test/…, le nom du jeu de sources sans « / ») qui contient la mention « TEST ONLY » (vecteurs publics).
# Aucune exemption ailleurs, quel que soit le commentaire. Seul le chemin et la ligne sont affichés,
# jamais le contenu (le journal de CI peut devenir public).
# Usage : check-secrets.sh [racine-du-dépôt]
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=lib.sh
. "$here/lib.sh"

root="${1:-.}"
cd "$root"

HEX='[0-9A-Fa-f]'
BYTE="(0[xX])?${HEX}{2}(\\.toByte\\(\\))?"
SEP='[ ,:;_-]{1,3}'
BT_BASE='-0000-1000-8000-00805[fF]9[bB]34[fF][bB]'

patterns=(
  "32 caractères hexadécimaux|(^|[^0-9A-Fa-f])${HEX}{32}([^0-9A-Fa-f]|$)"
  "16 octets hexadécimaux à la suite|(^|[^0-9A-Za-z])(${BYTE}${SEP}){15}${BYTE}([^0-9A-Za-z]|$)"
  "adresse MAC|(^|[^0-9A-Fa-f])(${HEX}{2}[:-]){5}${HEX}{2}([^0-9A-Fa-f]|$)"
)
UUID="${HEX}{8}-${HEX}{4}-${HEX}{4}-${HEX}{4}-${HEX}{12}"

is_test_file() {
  [[ $1 =~ /src/[^/]*Test/ ]]
}

status=0
report() { # report <libellé> <fichier> <lignes>
  echo "ECHEC secret ($1) : $2, ligne(s) $3" >&2
  status=1
}

while IFS= read -r file; do
  if is_test_file "$file" && grep -qaF 'TEST ONLY' "$file"; then continue; fi
  for entry in "${patterns[@]}"; do
    label="${entry%%|*}"; pattern="${entry#*|}"
    lines=$(grep -naE "$pattern" -- "$file" | cut -d: -f1 | paste -sd, - || true)
    if [ -n "$lines" ]; then report "$label" "$file" "$lines"; fi
  done
  lines=$(grep -naoE "$UUID" -- "$file" | grep -aviE -e "${BT_BASE}\$" -e ':00000000-' | cut -d: -f1 | paste -sd, - || true)
  if [ -n "$lines" ]; then report "UUID (hors base Bluetooth)" "$file" "$lines"; fi
done < <(repo_files)

if [ "$status" -eq 0 ]; then echo "OK secrets : rien de suspect"; fi
exit "$status"
