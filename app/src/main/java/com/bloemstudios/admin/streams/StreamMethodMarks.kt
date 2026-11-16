package com.bloemstudios.admin.streams

import androidx.annotation.StringRes
import com.bloemstudios.admin.R

/**
 * How a stream is delivered, named and keyed the same way everywhere: the session card on the Now playing page and the Now
 * playing widget both read these, so a stream never has one color or word in the app and another on the home screen.
 */

/** Which of the chart's fixed colors marks the method (the web chart's order): direct play, direct stream (a remux is one too), transcode. Null: no mark color, use muted. */
val StreamMethod.chartIndex: Int?
    get() = when (this) {
        StreamMethod.Direct -> 0
        StreamMethod.Remux, StreamMethod.DirectStream -> 1
        StreamMethod.Transcode -> 2
        StreamMethod.Unknown -> null
    }

@get:StringRes
val StreamMethod.labelRes: Int
    get() = when (this) {
        StreamMethod.Direct -> R.string.streams_method_direct
        StreamMethod.Remux -> R.string.streams_method_remux
        StreamMethod.DirectStream -> R.string.streams_method_direct_stream
        StreamMethod.Transcode -> R.string.streams_method_transcode
        StreamMethod.Unknown -> R.string.streams_method_unknown
    }
