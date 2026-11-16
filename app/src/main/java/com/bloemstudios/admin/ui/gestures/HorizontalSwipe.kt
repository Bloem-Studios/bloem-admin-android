package com.bloemstudios.admin.ui.gestures

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/** How far a finger must travel sideways before a swipe counts. */
private val SwipeDistance = 56.dp

/**
 * Calls [onSwipe] with +1 after a swipe to the left (go to the next item) or -1 after a swipe to the
 * right (go to the previous one). The drag is consumed, so an enclosing swipe (the shell's page swipe)
 * does not also fire; anything inside that scrolls sideways (tab rows, charts) takes the gesture first,
 * because it consumes the drag before this ancestor sees it. Pass the values the callback depends on as
 * [keys] so the gesture is restarted when they change.
 */
fun Modifier.horizontalSwipe(vararg keys: Any?, enabled: Boolean = true, onSwipe: (step: Int) -> Unit): Modifier =
    pointerInput(enabled, *keys) {
        if (!enabled) return@pointerInput
        val distance = SwipeDistance.toPx()
        var travelled = 0f
        detectHorizontalDragGestures(
            onDragStart = { travelled = 0f },
            onDragCancel = { travelled = 0f },
            onDragEnd = {
                val step = when {
                    travelled <= -distance -> 1
                    travelled >= distance -> -1
                    else -> 0
                }
                travelled = 0f
                if (step != 0) onSwipe(step)
            },
        ) { change, dragAmount ->
            change.consume()
            travelled += dragAmount
        }
    }
