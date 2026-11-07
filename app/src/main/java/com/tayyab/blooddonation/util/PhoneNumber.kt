package com.tayyab.blooddonation.util

/**
 * Pure phone-number normalisation for the Call / WhatsApp buttons.
 *
 * The app's donor and request rows store whatever a person typed: usually a
 * Pakistani mobile number such as `0300-1112233`, but sometimes `+92 300
 * 1112233`, `923001112233`, or `00923001112233`. ACTION_DIAL accepts the local
 * form, but `https://wa.me/<number>` needs a full international number with no
 * plus sign, spaces or dashes. One helper produces both from the same value,
 * so the two buttons can never disagree about who they are contacting.
 *
 * Deliberately JVM-only (no android.net.Uri, no Context): the formatting rules
 * are the part that can be wrong, so they are the part that is unit tested.
 * Callers build the Uri and check for an installed app themselves.
 *
 * A number that cannot be turned into a plausible E.164 value returns null;
 * the screens then HIDE both buttons rather than offering a dialler that would
 * dial the wrong person. No number is ever logged.
 */
object PhoneNumber {

    private const val PAKISTAN_CODE = "92"
    private const val MIN_DIGITS = 8
    private const val MAX_DIGITS = 15

    /**
     * Returns an E.164 number (`+923001112233`) or null when [raw] does not
     * contain a usable number.
     *
     * Rules, in order:
     * - `+...` is already international; keep its digits (8–15 of them).
     * - `0092...` is Pakistan with an international dialling prefix.
     * - `92XXXXXXXXXX` (12 digits) is Pakistan without the plus.
     * - `03XXXXXXXXX` (11 digits) is a Pakistani mobile: drop the trunk `0`.
     * - `3XXXXXXXXX` (10 digits) is the same mobile without the trunk `0`.
     * - anything else (landlines, extensions, too short, blank) is rejected.
     */
    fun normalize(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        // Char.isDigit() accepts Arabic-Indic digits too; a wa.me link only
        // understands ASCII ones. Restricting here avoids a silently broken URL.
        val digits = trimmed.filter { it in '0'..'9' }
        if (digits.length < MIN_DIGITS || digits.length > MAX_DIGITS) return null

        if (trimmed.startsWith("+")) return "+$digits"

        return when {
            digits.startsWith("00$PAKISTAN_CODE") && digits.length == 4 + 10 ->
                "+$PAKISTAN_CODE${digits.removePrefix("00$PAKISTAN_CODE")}"
            digits.startsWith(PAKISTAN_CODE) && digits.length == 2 + 10 ->
                "+$digits"
            digits.startsWith("03") && digits.length == 11 ->
                "+$PAKISTAN_CODE${digits.drop(1)}"
            digits.startsWith("3") && digits.length == 10 ->
                "+$PAKISTAN_CODE$digits"
            else -> null
        }
    }

    /** True when there is a number the two contact buttons may safely use. */
    fun isValid(raw: String?): Boolean = normalize(raw) != null

    /** `tel:` value for ACTION_DIAL. Only ever pass a value from [normalize]. */
    fun dialUri(e164: String): String = "tel:$e164"

    /**
     * `https://wa.me/<digits>` for a normalised number. wa.me wants the country
     * code and number with no `+`, spaces or punctuation.
     */
    fun waMeUrl(e164: String): String = "https://wa.me/${e164.removePrefix("+")}"
}
