#!/usr/bin/env bash
# Prouve que les contrôles CI échouent quand ils le doivent (et seulement alors), sur un dépôt factice.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
failures=0

expect() { # expect <0|1> <description> <commande...>
  local want="$1" desc="$2"; shift 2
  local got=0
  "$@" >/dev/null 2>&1 || got=$?
  if { [ "$want" = 0 ] && [ "$got" = 0 ]; } || { [ "$want" = 1 ] && [ "$got" != 0 ]; }; then
    echo "ok   - $desc"
  else
    echo "FAIL - $desc (code $got, attendu $want)"; failures=$((failures + 1))
  fi
}

fresh() {
  rm -rf "$tmp/repo"
  mkdir -p "$tmp/repo/ble/src/commonMain/kotlin/app/voltdeck/ble" "$tmp/repo/drivers/src"
  printf 'class T { fun f(p: P) { p.%s(1) } }\n' write > "$tmp/repo/ble/src/commonMain/kotlin/app/voltdeck/ble/KableTransport.kt"
  printf 'class D\n' > "$tmp/repo/drivers/src/D.kt"
}

hex32="$(printf 'ab%.0s' $(seq 1 16))"
hex64="$hex32$hex32"

fresh
expect 0 "readonly : dépôt propre" "$here/check-readonly.sh" "$tmp/repo"
printf 'class W { fun f(p: P) { p.%s(1) } }\n' write > "$tmp/repo/drivers/src/W.kt"
expect 1 "readonly : write( dans drivers/" "$here/check-readonly.sh" "$tmp/repo"
printf 'class W { fun f(p: P) { p\n    .%s(1) } }\n' write > "$tmp/repo/drivers/src/W.kt"
expect 1 "readonly : write( en début de ligne" "$here/check-readonly.sh" "$tmp/repo"
printf 'val x = p.%s\n' writeWithoutResponse > "$tmp/repo/drivers/src/W.kt"
expect 1 "readonly : writeWithoutResponse" "$here/check-readonly.sh" "$tmp/repo"
printf 'val x = 1\n' > "$tmp/repo/drivers/src/W.kt"
expect 0 "readonly : retour à un dépôt propre" "$here/check-readonly.sh" "$tmp/repo"

fresh
expect 0 "secrets : dépôt propre" "$here/check-secrets.sh" "$tmp/repo"
printf 'val k = "%s"\n' "$hex32" > "$tmp/repo/drivers/src/K.kt"
expect 1 "secrets : 32 hex dans un fichier ordinaire" "$here/check-secrets.sh" "$tmp/repo"
printf '// TEST ONLY\nval k = "%s"\n' "$hex32" > "$tmp/repo/drivers/src/K.kt"
expect 0 "secrets : 32 hex dans un fichier marqué TEST ONLY" "$here/check-secrets.sh" "$tmp/repo"
printf 'sha256 = %s\n' "$hex64" > "$tmp/repo/drivers/src/K.kt"
expect 0 "secrets : un condensat de 64 hex n'est pas une clé" "$here/check-secrets.sh" "$tmp/repo"
printf 'val mac = "%s"\n' "AA:BB:CC:DD:EE:FF" > "$tmp/repo/drivers/src/K.kt"
expect 1 "secrets : adresse MAC" "$here/check-secrets.sh" "$tmp/repo"

if [ "$failures" -ne 0 ]; then echo "$failures vérification(s) en échec" >&2; exit 1; fi
echo "selftest OK"
