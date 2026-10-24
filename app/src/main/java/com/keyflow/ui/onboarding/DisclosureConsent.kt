package com.keyflow.ui.onboarding

/** Versione o texto sempre que o escopo da divulgação mudar; versão antiga exige novo aceite. */
const val DISCLOSURE_VERSION = 1

data class DisclosureConsent(val acceptedVersion: Int) {
    fun isAcceptedForCurrentVersion(): Boolean = acceptedVersion == DISCLOSURE_VERSION

    companion object {
        fun fromStoredVersion(version: Int): DisclosureConsent = DisclosureConsent(version)
    }
}
