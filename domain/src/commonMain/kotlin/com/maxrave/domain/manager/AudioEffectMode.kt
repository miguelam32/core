package com.maxrave.domain.manager

/** Como decide un efecto de audio (reverb, 8D) si esta prendido. */
enum class AudioEffectMode {
    /** Se prende solo cuando hay audifonos conectados. */
    AUTO,
    OFF,
    ON,
    ;

    companion object {
        fun fromStored(raw: String?): AudioEffectMode? = entries.firstOrNull { it.name == raw }
    }
}
