#!/usr/bin/env bash
# Verrou de lecture seule (VOL-1 §4.3, verrou 3) : aucune écriture BLE hors du transport Kable.
# Échoue si un appel d'écriture apparaît dans un fichier autre que ALLOWED.
# Usage : check-readonly.sh [racine-du-dépôt]
set -euo pipefail

root="${1:-.}"
ALLOWED="./ble/src/commonMain/kotlin/app/voltdeck/ble/KableTransport.kt"
PATTERN='\.write[[:space:]]*\(|::write\b|writeWithoutResponse|writeCharacteristic|writeDescriptor|writeValue[[:space:]]*\('

cd "$root"
hits=$(grep -rnE "$PATTERN" . \
  --include='*.kt' --include='*.kts' --include='*.java' --include='*.swift' --include='*.m' --include='*.h' \
  --exclude-dir=build --exclude-dir=.git --exclude-dir=.gradle --exclude-dir=.kotlin --exclude-dir=.idea \
  | grep -v -F "$ALLOWED:" || true)

if [ -n "$hits" ]; then
  echo "ECHEC lecture seule : écriture BLE interdite hors de $ALLOWED" >&2
  echo "$hits" >&2
  exit 1
fi
echo "OK lecture seule : aucune écriture BLE hors de $ALLOWED"
