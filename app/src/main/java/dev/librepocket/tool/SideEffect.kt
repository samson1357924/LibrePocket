package dev.librepocket.tool

/**
 * Tool side-effect tier (ARCHITECTURE §5.2, CAPABILITY_MATRIX §4).
 *
 * - [READ]: read-only (list calendar, query notifications). Retryable.
 * - [WRITE]: mutates external state (create alarm, write file).
 *   Never auto-retried; needs a fresh instruction for a new round.
 * - [PRIVILEGED]: telecom / highly sensitive (DIAL prefill, SMS prefill,
 *   screenshot, automation). Always confirmed by the user or handed to a
 *   system app for the final step; never executed silently.
 */
enum class SideEffect {
    READ,
    WRITE,
    PRIVILEGED,
}
