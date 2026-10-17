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

KT=ble/src/commonMain/kotlin/app/voltdeck/ble/KableTransport.kt

fresh() {
  rm -rf "$tmp/repo"
  mkdir -p "$tmp/repo/ble/src/commonMain/kotlin/app/voltdeck/ble" "$tmp/repo/drivers/src" "$tmp/repo/drivers/src/commonTest"
  printf 'import com.juul.kable.Peripheral\nclass T { fun f(p: P) { ledger.requireConnectable(i); p.%s(1) } }\n' write > "$tmp/repo/$KT"
  printf 'dependencies { implementation(libs.kable.core) }\n' > "$tmp/repo/ble/build.gradle.kts"
  printf 'class D\n' > "$tmp/repo/drivers/src/D.kt"
}

# put <fichier relatif> <contenu printf> [arguments printf]
put() {
  local f="$tmp/repo/$1"; shift
  mkdir -p "$(dirname "$f")"
  printf "$@" > "$f"
}

hex32="$(printf 'ab%.0s' $(seq 1 16))"
hex64="$hex32$hex32"
mac="$(printf '%s:' AA BB CC DD EE FF)"; mac="${mac%:}"
bytes16_plain="$(printf '%02x ' $(seq 1 16))"
bytes16_kotlin="$(printf '0x%02x, ' $(seq 1 16))"

W=write

fresh
RO="$here/check-readonly.sh"; SE="$here/check-secrets.sh"
expect 0 "readonly : dépôt propre" "$RO" "$tmp/repo"
put drivers/src/W.kt 'class W { fun f(p: P) { p.%s(1) } }\n' $W
expect 1 "readonly : write( dans drivers/" "$RO" "$tmp/repo"
put drivers/src/W.kt 'class W { fun f(p: P) { p\n    .%s(1) } }\n' $W
expect 1 "readonly : write( en début de ligne" "$RO" "$tmp/repo"
put drivers/src/W.kt 'val x = p.%s\n' writeWithoutResponse
expect 1 "readonly : writeWithoutResponse" "$RO" "$tmp/repo"
put drivers/src/W.kt 'fun f(p: P) { p.%s(c, d, t) } // ./%s:\n' $W "$KT"
expect 1 "readonly : écriture avec le chemin autorisé en commentaire sur la ligne" "$RO" "$tmp/repo"
put drivers/src/kotlin/build/W.kt 'class W { fun f(p: P) { p.%s(1) } }\n' $W
expect 1 "readonly : write( dans un package source nommé build" "$RO" "$tmp/repo"
rm -rf "$tmp/repo/drivers/src/kotlin"
put drivers/src/W.kt 'fun f(p: P) { p.run { %s(c, d, t) } }\n' $W
expect 1 "readonly : write( dans un bloc run" "$RO" "$tmp/repo"
put drivers/src/W.kt 'fun f(p: P) { with(p) { %s (c, d, t) } }\n' $W
expect 1 "readonly : write ( avec espace dans un bloc with" "$RO" "$tmp/repo"
put drivers/src/W.kt 'fun f(p: P) { p.`%s`(c, d, t) }\n' $W
expect 1 "readonly : write entre accents graves" "$RO" "$tmp/repo"
put drivers/src/W.kt 'val f = p::%s\n' $W
expect 1 "readonly : référence ::write" "$RO" "$tmp/repo"
put drivers/src/W.kt 'import com.juul.kable.Peripheral\n'
expect 1 "readonly : import Kable hors du transport" "$RO" "$tmp/repo"
put drivers/src/W.kt 'import android.bluetooth.BluetoothGatt\n'
expect 1 "readonly : API android.bluetooth hors du transport" "$RO" "$tmp/repo"
put drivers/build.gradle.kts 'dependencies { implementation(libs.kable.core) }\n'
rm -f "$tmp/repo/drivers/src/W.kt"
expect 1 "readonly : dépendance Kable déclarée hors de ble/" "$RO" "$tmp/repo"
rm -f "$tmp/repo/drivers/build.gradle.kts"
put drivers/src/W.kt 'class Sink { suspend fun %s(frame: ByteArray) {} }\n' write
expect 0 "readonly : une déclaration fun write( n'est pas un appel" "$RO" "$tmp/repo"
put drivers/build/generated/G.kt 'val x = p.%s(1)\n' $W
expect 0 "readonly : le dossier build/ d'un module est ignoré" "$RO" "$tmp/repo"
put drivers/src/W.kt 'val x = 1\n'
expect 0 "readonly : retour à un dépôt propre" "$RO" "$tmp/repo"
printf 'class T { fun f(p: P) { p.%s(1) } }\n' write > "$tmp/repo/$KT"
expect 1 "readonly : le transport réel n'appelle plus la garde de connexion" "$RO" "$tmp/repo"

fresh
expect 0 "secrets : dépôt propre" "$SE" "$tmp/repo"
put drivers/src/K.kt 'val k = "%s"\n' "$hex32"
expect 1 "secrets : 32 hex dans un fichier ordinaire" "$SE" "$tmp/repo"
put drivers/src/K.kt '// TEST ONLY\nval k = "%s"\n' "$hex32"
expect 1 "secrets : TEST ONLY n'exempte pas un fichier de production" "$SE" "$tmp/repo"
rm -f "$tmp/repo/drivers/src/K.kt"
put drivers/src/commonTest/K.kt '// TEST ONLY\nval k = "%s"\n' "$hex32"
expect 0 "secrets : 32 hex dans un fichier de test marqué TEST ONLY" "$SE" "$tmp/repo"
put drivers/src/commonTest/K.kt 'val k = "%s"\n' "$hex32"
expect 1 "secrets : 32 hex dans un fichier de test non marqué" "$SE" "$tmp/repo"
rm -f "$tmp/repo/drivers/src/commonTest/K.kt"
put drivers/src/K.kt 'sha256 = %s\n' "$hex64"
expect 0 "secrets : un condensat de 64 hex n'est pas une clé" "$SE" "$tmp/repo"
put drivers/src/K.kt 'val k = 0x%s\n' "$hex32"
expect 1 "secrets : 0x collé aux 32 hex" "$SE" "$tmp/repo"
put drivers/src/K.kt 'val key_%s = 1\n' "$hex32"
expect 1 "secrets : préfixe key_ collé aux 32 hex" "$SE" "$tmp/repo"
put drivers/src/K.kt 'val k = "%s"\n' "$bytes16_plain"
expect 1 "secrets : 16 octets séparés par des espaces" "$SE" "$tmp/repo"
put drivers/src/K.kt 'val k = byteArrayOf(%s)\n' "$bytes16_kotlin"
expect 1 "secrets : 16 octets en littéraux Kotlin 0x.." "$SE" "$tmp/repo"
put drivers/src/K.kt 'val k = byteArrayOf(0xDD.toByte(), 0xA5.toByte(), 0x03, 0x00, 0xFF.toByte(), 0xFD.toByte(), 0x77)\n'
expect 0 "secrets : une trame de 7 octets n'est pas une clé" "$SE" "$tmp/repo"
put drivers/src/K.kt 'val u = "%s"\n' "$(printf '%s-' 12345678 9abc def0 1234)56789abcdef0"
expect 1 "secrets : forme UUID 8-4-4-4-12" "$SE" "$tmp/repo"
put drivers/src/K.kt 'val u = "%s"\n' "0000ff00-0000-1000-8000-00805f9b34fb"
expect 0 "secrets : UUID Bluetooth standard autorisé" "$SE" "$tmp/repo"
put drivers/src/K.kt 'val mac = "%s"\n' "$mac"
expect 1 "secrets : adresse MAC avec deux-points" "$SE" "$tmp/repo"
put drivers/src/K.kt 'val mac = "%s"\n' "${mac//:/-}"
expect 1 "secrets : adresse MAC avec tirets" "$SE" "$tmp/repo"
printf 'binaire %s fin\n' "$hex32" > "$tmp/repo/drivers/src/blob.bin"
printf '\000\001\002' >> "$tmp/repo/drivers/src/blob.bin"
rm -f "$tmp/repo/drivers/src/K.kt"
expect 1 "secrets : fichier binaire contenant 32 hex" "$SE" "$tmp/repo"
rm -f "$tmp/repo/drivers/src/blob.bin"
put drivers/src/commonMain/kotlin/FooTest/P.kt '// TEST ONLY\nval k = "%s"\n' "$hex32"
expect 1 "secrets : un dossier FooTest hors jeu de sources de test n'est pas exempté" "$SE" "$tmp/repo"
rm -rf "$tmp/repo/drivers/src/commonMain"
put .claude/settings.local.json '{"k": "%s"}\n' "$hex32"
expect 1 "secrets : le dossier .claude/ est contrôlé" "$SE" "$tmp/repo"
rm -rf "$tmp/repo/.claude"
expect 0 "secrets : retour à un dépôt propre" "$SE" "$tmp/repo"

if [ "$failures" -ne 0 ]; then echo "$failures vérification(s) en échec" >&2; exit 1; fi
echo "selftest OK"
