package app.voltdeck.core

import kotlin.jvm.JvmInline

/** Identifiant local d'un équipement enregistré (UUID v4 généré sur le téléphone, jamais une adresse MAC). */
@JvmInline
value class DeviceId(val value: String) {
    init {
        require(value.isNotBlank()) { "DeviceId ne peut pas être vide" }
    }

    override fun toString(): String = value
}
