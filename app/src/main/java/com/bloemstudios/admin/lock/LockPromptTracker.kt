package com.bloemstudios.admin.lock

/**
 * When the lock screen opens the system prompt by itself, and when it must not. Pure Kotlin, so the
 * rules are unit tested on the JVM; [AppLockViewModel] holds one and [AppLockScreen] asks it.
 *
 *  - A lock is "new" at cold start and each time the app is left and come back to. A new lock
 *    gets one automatic prompt, taken with [takeAutoPrompt] once the screen is resumed and focused.
 *  - A cancel, a dismissal or an error ends that prompt and nothing re-arms it: the Unlock button
 *    ([takeManualPrompt]) is the retry.
 *  - Rotation or a fold recreates the activity, not the lock: the tracker lives in the ViewModel, a
 *    stop caused by a configuration change is ignored, and a prompt still showing blocks a second
 *    automatic one.
 *  - A stop while the prompt is up is not "leaving" by itself: on API 26 to 29 the screen-lock
 *    check is an activity of its own, and backing out of it must not raise the prompt again. When
 *    the system closes the prompt because the app went away ([onPromptEnded] with
 *    `cancelledBySystem`), that stop counts and the next return is a new lock.
 *
 * Main thread only.
 */
class LockPromptTracker {
    private var autoPending = true
    private var inFlight = false
    private var stoppedWhileInFlight = false

    /** The activity stopped. [changingConfigurations] is `Activity.isChangingConfigurations`. */
    fun onStopped(changingConfigurations: Boolean) {
        if (changingConfigurations) return
        if (inFlight) stoppedWhileInFlight = true else autoPending = true
    }

    /** The lock screen is resumed and focused: true once per new lock, when the prompt should open. */
    fun takeAutoPrompt(): Boolean {
        if (!autoPending || inFlight) return false
        begin()
        return true
    }

    /** The Unlock button: always opens the prompt (the library ignores a second while one is up). */
    fun takeManualPrompt() {
        begin()
    }

    /** The prompt answered with an error or a dismissal (not a success). */
    fun onPromptEnded(cancelledBySystem: Boolean) {
        inFlight = false
        if (stoppedWhileInFlight && cancelledBySystem) autoPending = true
        stoppedWhileInFlight = false
    }

    /** The user got in. */
    fun onUnlocked() {
        autoPending = false
        inFlight = false
        stoppedWhileInFlight = false
    }

    private fun begin() {
        autoPending = false
        inFlight = true
        stoppedWhileInFlight = false
    }
}
