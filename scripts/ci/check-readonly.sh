#!/usr/bin/env bash
# Verrou de lecture seule (VOL-1 §4.3, verrou 3) : aucune écriture BLE hors du transport Kable.
# Trois contrôles :
#  1. aucun appel d'écriture (forme `.write(`, `write(`, `` `write` ``, `::write`, writeWithoutResponse…) hors de ALLOWED ;
#  2. Kable, android.bluetooth et CoreBluetooth ne sont référencés que par ALLOWED : sans accès au
#     périphérique, `p.run { write(…) }` ou `with(p) { write(…) }` ne peuvent pas exister ailleurs ;
#  3. la dépendance Kable n'est déclarée que dans ble/build.gradle.kts et gradle/libs.versions.toml.
# Le filtre porte sur le chemin du fichier, jamais sur le contenu de la ligne.
# Usage : check-readonly.sh [racine-du-dépôt]
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=lib.sh
. "$here/lib.sh"

root="${1:-.}"
ALLOWED="./ble/src/commonMain/kotlin/app/voltdeck/ble/KableTransport.kt"
KABLE_GRADLE_ALLOWED="./ble/build.gradle.kts ./gradle/libs.versions.toml"

# PCRE : une déclaration `fun write(` n'écrit rien ; tout autre appel est refusé.
WRITE_PATTERN='(?<![[:alnum:]_])(?<!fun )`?write`?[[:space:]]*\(|::[[:space:]]*`?write`?(?![[:alnum:]_])|writeWithoutResponse|writeCharacteristic|writeDescriptor|writeValue'
BLE_API_PATTERN='com\.juul\.kable|android\.bluetooth|CoreBluetooth|CBPeripheral|CBCharacteristic|BluetoothGatt'
KABLE_DEP_PATTERN='kable'

cd "$root"
status=0

# scan <libellé> <motif> <chemins autorisés séparés par des espaces> <fichiers…>
scan() {
  local label="$1" pattern="$2" allowed="$3" file hits
  shift 3
  for file in "$@"; do
    case " $allowed " in *" $file "*) continue ;; esac
    hits=$(grep -naP "$pattern" -- "$file" | cut -d: -f1 | paste -sd, - || true)
    if [ -n "$hits" ]; then
      echo "ECHEC lecture seule ($label) : $file, ligne(s) $hits" >&2
      status=1
    fi
  done
}

mapfile -t sources < <(repo_files | grep -E '\.(kt|kts|java|swift|m|h)$' || true)
mapfile -t gradle_files < <(repo_files | grep -E '(\.gradle\.kts|\.gradle|\.toml)$' || true)

scan "écriture BLE" "$WRITE_PATTERN" "$ALLOWED" "${sources[@]}"
scan "API Bluetooth hors du transport" "$BLE_API_PATTERN" "$ALLOWED" "${sources[@]}"
scan "dépendance Kable hors de ble/" "$KABLE_DEP_PATTERN" "$KABLE_GRADLE_ALLOWED" "${gradle_files[@]}"

# Le transport réel doit passer par le registre des annonces avant toute connexion (refus Victron, profil connu).
if [ -f "$ALLOWED" ] && ! grep -qF 'ledger.requireConnectable(' "$ALLOWED"; then
  echo "ECHEC lecture seule (garde de connexion) : $ALLOWED n'appelle plus ledger.requireConnectable(" >&2
  status=1
fi

if [ "$status" -ne 0 ]; then
  echo "Seul $ALLOWED peut écrire ou toucher l'API Bluetooth." >&2
  exit 1
fi
echo "OK lecture seule : aucune écriture BLE ni API Bluetooth hors de $ALLOWED"
