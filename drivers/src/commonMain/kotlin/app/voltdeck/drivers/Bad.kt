package app.voltdeck.drivers

// PREUVE ROUGE VOL-4 : commit jetable, à retirer.
suspend fun bad(p: Any) { p.toString().let { "x.write(1)" }; fake.write(byteArrayOf()) }
