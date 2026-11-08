/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.guests

import java.util.Locale

/** The setup steps, in order. The password step is skipped for open networks. */
enum class GuestStep(val title: String) {
    NETWORK("Wi-Fi network"),
    PASSWORD("Password"),
    BASICS("House basics"),
    WELCOME("Welcome message"),
    PREVIEW("Preview & finish"),
}

/** What the guest card will show, line by line — shared by the live preview and the finish step. */
data class GuestPreview(
    val welcome: String,
    val ssid: String,
    val password: String,
    val checkout: String?,
    val notes: List<String>,
    val doorCode: String?,
    val contact: List<String>,
)

/**
 * The step-by-step setup's state. Immutable: every edit is a copy. [password] blank keeps the
 * Portal's saved password; [doorCodeInput] blank keeps a saved door code. [toString] hides both.
 */
data class GuestDraft(
    val card: GuestCard,
    val hasPassword: Boolean,
    val hasDoorCode: Boolean,
    val password: String = "",
    val showDoorCode: Boolean = hasDoorCode,
    val doorCodeInput: String = "",
) {
    val steps: List<GuestStep> get() = GuestStep.entries.filter { it != GuestStep.PASSWORD || !card.isOpen }

    fun next(step: GuestStep): GuestStep? = steps.getOrNull(steps.indexOf(step) + 1)
    fun previous(step: GuestStep): GuestStep? = steps.indexOf(step).takeIf { it > 0 }?.let { steps[it - 1] }

    fun withNetwork(ssid: String, security: String, hidden: Boolean): GuestDraft =
        copy(card = card.copy(ssid = ssid.take(GuestCard.MAX_SSID), security = security, hiddenNetwork = hidden))

    val doorCodeChange: DoorCodeChange get() = when {
        !showDoorCode -> if (hasDoorCode) DoorCodeChange.Clear else DoorCodeChange.Keep
        doorCodeInput.isEmpty() -> DoorCodeChange.Keep
        else -> DoorCodeChange.Set(doorCodeInput)
    }

    /** The first reason [step] can't continue, or null. PREVIEW checks every step. */
    fun problem(step: GuestStep): String? = when (step) {
        GuestStep.NETWORK -> networkProblem()
        GuestStep.PASSWORD -> if (card.isOpen) null else passwordProblem()
        GuestStep.BASICS -> doorCodeProblem()
        GuestStep.WELCOME -> null
        GuestStep.PREVIEW -> steps.filter { it != GuestStep.PREVIEW }.firstNotNullOfOrNull(::problem)
    }

    private fun networkProblem(): String? = when {
        card.ssid.isBlank() -> "Choose your Wi-Fi network."
        card.ssid.any { it.isISOControl() } -> "Use one line for the network name."
        else -> null
    }

    private fun passwordProblem(): String? = when {
        password.length > GuestCard.MAX_PASSWORD || password.any { it.isISOControl() } -> "The password is too long."
        password.isEmpty() -> if (hasPassword) null else "Add the Wi-Fi password."
        card.security != GuestCard.SECURITY_WEP && password.length < GuestCard.MIN_WPA_PASSWORD ->
            "Wi-Fi passwords are at least 8 characters."
        else -> null
    }

    private fun doorCodeProblem(): String? = when {
        !showDoorCode -> null
        doorCodeInput.isEmpty() -> if (hasDoorCode) null else "Type the door code."
        !GuestDoorCode.isValid(doorCodeInput) -> "Door codes are 4 to 8 digits."
        else -> null
    }

    fun preview(use24: Boolean, locale: Locale = Locale.getDefault()): GuestPreview = GuestPreview(
        welcome = card.welcome.trim(),
        ssid = card.ssid,
        password = when {
            card.isOpen -> "No password needed"
            card.hidePassword -> TAP_TO_SHOW
            password.isNotEmpty() -> password
            else -> if (hasPassword) "Saved on the Portal" else ""
        },
        checkout = GuestCheckout.line(card.checkout, use24, locale),
        notes = card.goodToKnow.mapNotNull(GuestBasics::presetLabel) + card.customNotes,
        doorCode = if (doorCodeChange is DoorCodeChange.Clear || !showDoorCode) null else TAP_TO_SHOW,
        contact = listOfNotNull(GuestBasics.contactLine(card.contact), GuestBasics.nameLine(card.contactName)),
    )

    override fun toString(): String =
        "GuestDraft(ssid=${card.ssid}, security=${card.security}, newPassword=${password.isNotEmpty()}, door=$doorCodeChange)"

    companion object {
        const val TAP_TO_SHOW = "Tap to show"

        fun from(snapshot: GuestSnapshot): GuestDraft =
            GuestDraft(snapshot.card, hasPassword = snapshot.hasPassword, hasDoorCode = snapshot.hasDoorCode)
    }
}
