package com.bloemstudios.admin.streams

import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamPresentationTest {
    private fun session(
        method: String = "direct",
        paused: Boolean = false,
        position: Double = 0.0,
        duration: Double? = null,
        block: AdminSession.() -> AdminSession = { this },
    ) = AdminSession(
        session_id = "s1", user_id = "u1", username = "alex", profile_id = "p1", media_title = "Film",
        media_type = "movie", play_method = method, position_seconds = position, file_duration = duration,
        is_paused = paused,
    ).block()

    // ---- decisions and method -------------------------------------------------------------

    @Test fun decisionsFollowTheWebsNormalisation() {
        assertEquals(StreamDecision.Direct, streamDecisionOf("direct"))
        assertEquals(StreamDecision.Copy, streamDecisionOf("copy"))
        assertEquals(StreamDecision.Copy, streamDecisionOf("remux"))
        assertEquals(StreamDecision.Transcode, streamDecisionOf(" transcode "))
        assertEquals(StreamDecision.Unknown, streamDecisionOf("hls"))
        assertEquals(StreamDecision.Unknown, streamDecisionOf(null))
        assertEquals(StreamDecision.Direct, streamDecisionOf("direct play"))
    }

    @Test fun componentDecisionsFallBackToThePlayMethodAndTheAudioFlag() {
        val bare = session(method = "transcode")
        assertEquals(StreamDecision.Transcode, videoDecisionOf(bare))
        assertEquals(StreamDecision.Transcode, audioDecisionOf(bare))
        val audioOnly = session(method = "direct").copy(transcode_audio = true)
        assertEquals(StreamDecision.Direct, videoDecisionOf(audioOnly))
        assertEquals(StreamDecision.Transcode, audioDecisionOf(audioOnly))
        val explicit = session(method = "transcode").copy(video_decision = "copy", audio_decision = "transcode")
        assertEquals(StreamDecision.Copy, videoDecisionOf(explicit))
        assertEquals(StreamDecision.Transcode, audioDecisionOf(explicit))
    }

    @Test fun theServersEffectiveMethodWins() {
        fun method(effective: String?) = streamMethodOf(session(method = "direct").copy(effective_play_method = effective))
        assertEquals(StreamMethod.Direct, method("direct"))
        assertEquals(StreamMethod.Remux, method("remux"))
        assertEquals(StreamMethod.DirectStream, method("direct_stream"))
        assertEquals(StreamMethod.DirectStream, method("audio"))
        assertEquals(StreamMethod.Transcode, method("transcode"))
        assertEquals(StreamMethod.Unknown, method("something-new"))
    }

    @Test fun withoutAnEffectiveMethodItIsReducedFromTheComponents() {
        fun method(video: String?, audio: String?, playMethod: String = "direct") =
            streamMethodOf(session(method = playMethod).copy(video_decision = video, audio_decision = audio))
        assertEquals(StreamMethod.Transcode, method("transcode", "direct"))
        assertEquals(StreamMethod.DirectStream, method("direct", "transcode"))
        assertEquals(StreamMethod.DirectStream, method("copy", "transcode"))
        assertEquals(StreamMethod.Direct, method("direct", "direct"))
        assertEquals(StreamMethod.Remux, method("copy", "direct"))
        assertEquals(StreamMethod.Remux, method("direct", "copy"))
        assertEquals(StreamMethod.Unknown, method(null, null, playMethod = ""))
        // An unknown video decision must not turn a bare audio flag into a direct stream.
        assertEquals(StreamMethod.Unknown, streamMethodOf(session(method = "").copy(transcode_audio = true)))
    }

    // ---- titles, people, client -----------------------------------------------------------

    @Test fun anEpisodeReadsSeriesEpisodeNumberAndName() {
        val episode = session().copy(series_name = "Severance", season_number = 2, episode_number = 3, episode_name = "Who Is Alive?", media_title = "ignored")
        assertEquals("Severance · S2E3 · Who Is Alive?", streamTitle(episode))
    }

    @Test fun anEpisodeWithoutAnEpisodeNameFallsBackToTheMediaTitleOrLeavesItOut() {
        val withTitle = session().copy(series_name = "Severance", season_number = 1, episode_number = 1, media_title = "Good News About Hell")
        assertEquals("Severance · S1E1 · Good News About Hell", streamTitle(withTitle))
        val titleIsSeries = withTitle.copy(media_title = "Severance")
        assertEquals("Severance · S1E1", streamTitle(titleIsSeries))
    }

    @Test fun aMovieOrIncompleteEpisodeUsesTheMediaTitle() {
        assertEquals("Film", streamTitle(session()))
        assertEquals("Film", streamTitle(session().copy(series_name = "Show", season_number = 1)))
        assertEquals("Film", streamTitle(session().copy(episode_number = 4, season_number = 1)))
    }

    @Test fun userAndProfileJoinAndSkipARepeatedOrMissingProfile() {
        assertEquals("alex · Living room", streamUserLabel(session().copy(profile_name = "Living room")))
        assertEquals("alex", streamUserLabel(session().copy(profile_name = "ALEX")))
        assertEquals("alex", streamUserLabel(session().copy(profile_name = "  ")))
        assertEquals("alex", streamUserLabel(session()))
    }

    @Test fun theClientLabelPrefersTheServersOwn() {
        assertEquals("Silo Android 1.4", streamClientLabel(session().copy(client_label = " Silo Android 1.4 ", client_name = "x")))
        assertEquals("Silo Android 1.4.2", streamClientLabel(session().copy(client_name = "Silo Android", client_version = "1.4.2")))
        assertEquals("Silo Android", streamClientLabel(session().copy(client_name = "Silo Android")))
        assertNull(streamClientLabel(session()))
        assertNull(streamClientLabel(session().copy(client_name = " ", client_version = "2")))
    }

    @Test fun theFullClientLabelFallsBackToTheCompactOne() {
        assertEquals("Silo Android 1.4.2 (88) beta", streamClientLabelFull(session().copy(client_label = "Silo Android 1.4", client_label_full = "Silo Android 1.4.2 (88) beta")))
        assertEquals("Silo Android 1.4", streamClientLabelFull(session().copy(client_label = "Silo Android 1.4")))
    }

    @Test fun deviceNamesAreReadableAndUnknownPlatformsPassThrough() {
        assertEquals("Android", streamDeviceLabel(session().copy(device_platform = "android")))
        assertEquals("iOS", streamDeviceLabel(session().copy(device_platform = "IOS")))
        assertEquals("Fridge", streamDeviceLabel(session().copy(device_platform = "Fridge")))
        assertNull(streamDeviceLabel(session()))
    }

    @Test fun theClientLineJoinsAppAndDeviceOrIsNull() {
        assertEquals("Silo Android 1.4 · Android", streamClientLine(session().copy(client_label = "Silo Android 1.4", device_platform = "android")))
        assertEquals("Android", streamClientLine(session().copy(device_platform = "android")))
        assertNull(streamClientLine(session()))
    }

    // ---- time -----------------------------------------------------------------------------

    @Test fun theClockShowsMinutesAndHours() {
        assertEquals("0:00", formatPlaybackClock(0.0))
        assertEquals("0:09", formatPlaybackClock(9.9))
        assertEquals("12:34", formatPlaybackClock(754.0))
        assertEquals("59:59", formatPlaybackClock(3599.0))
        assertEquals("1:00:00", formatPlaybackClock(3600.0))
        assertEquals("1:02:03", formatPlaybackClock(3723.0))
        assertEquals("12:00:00", formatPlaybackClock(43_200.0))
    }

    @Test fun theClockTreatsBadValuesAsZero() {
        assertEquals("0:00", formatPlaybackClock(-5.0))
        assertEquals("0:00", formatPlaybackClock(Double.NaN))
        assertEquals("0:00", formatPlaybackClock(Double.POSITIVE_INFINITY))
    }

    @Test fun remainingTimeIsCompact() {
        assertEquals("0s", formatRemainingTime(0.0))
        assertEquals("1s", formatRemainingTime(0.2))
        assertEquals("45s", formatRemainingTime(45.0))
        assertEquals("1m", formatRemainingTime(60.0))
        assertEquals("32m", formatRemainingTime(32 * 60 + 40.0))
        assertEquals("1h", formatRemainingTime(3600.0))
        assertEquals("1h 5m", formatRemainingTime(3600.0 + 5 * 60 + 30))
        assertEquals("0s", formatRemainingTime(-3.0))
        assertEquals("0s", formatRemainingTime(Double.NaN))
    }

    @Test fun remainingNeedsAKnownLength() {
        assertEquals(60.0, remainingSeconds(40.0, 100.0)!!, 0.0)
        assertEquals(0.0, remainingSeconds(120.0, 100.0)!!, 0.0)
        assertNull(remainingSeconds(40.0, null))
        assertNull(remainingSeconds(40.0, 0.0))
    }

    @Test fun aPlayingStreamMovesForwardAndAPausedOneStaysPut() {
        assertEquals(15.0, projectedPosition(10.0, 100.0, paused = false, elapsedMillis = 5_000), 0.0001)
        assertEquals(10.0, projectedPosition(10.0, 100.0, paused = true, elapsedMillis = 5_000), 0.0)
        assertEquals(10.0, projectedPosition(10.0, 100.0, paused = false, elapsedMillis = -50), 0.0)
        assertEquals(10.0, projectedPosition(10.0, 100.0, paused = false, elapsedMillis = 0), 0.0)
    }

    @Test fun aProjectedPositionStopsAtTheEndOfTheFile() {
        assertEquals(100.0, projectedPosition(98.0, 100.0, paused = false, elapsedMillis = 30_000), 0.0)
        assertEquals(500.0, projectedPosition(100.0, null, paused = false, elapsedMillis = 400_000), 0.0)
        assertEquals(0.0, projectedPosition(-4.0, 100.0, paused = true, elapsedMillis = 0), 0.0)
    }

    @Test fun progressIsAFractionOfAKnownLength() {
        assertEquals(0.25f, progressFraction(25.0, 100.0)!!, 0.0001f)
        assertEquals(1f, progressFraction(150.0, 100.0)!!, 0f)
        assertEquals(0f, progressFraction(-1.0, 100.0)!!, 0f)
        assertNull(progressFraction(25.0, null))
        assertNull(progressFraction(25.0, 0.0))
    }

    @Test fun serverTimestampsShowInTheDevicesZone() {
        assertEquals("Nov 13, 2026, 2:02 PM",
            formatSessionInstant("2026-11-13T14:02:30.123Z", ZoneId.of("UTC"), Locale.US)?.replace(' ', ' '))
        assertNull(formatSessionInstant(null))
        assertNull(formatSessionInstant(""))
        assertNull(formatSessionInstant("yesterday"))
    }

    // ---- media ----------------------------------------------------------------------------

    @Test fun codecsUseTheWebsLabels() {
        assertEquals("HEVC", codecLabel("hevc"))
        assertEquals("HEVC", codecLabel("h265"))
        assertEquals("H.264", codecLabel("h264"))
        assertEquals("H.264", codecLabel(" H264 "))
        assertEquals("TrueHD", codecLabel("truehd"))
        assertEquals("DTS-HD", codecLabel("dtshd"))
        assertEquals("VP9", codecLabel("vp9"))
        assertNull(codecLabel(null))
        assertNull(codecLabel("  "))
    }

    @Test fun resolutionsNameTheSizeClass() {
        assertEquals("4K", resolutionLabel("2160p"))
        assertEquals("4K", resolutionLabel("3840x2160"))
        assertEquals("4K", resolutionLabel("3840x1600"))
        assertEquals("4K", resolutionLabel("4k"))
        assertEquals("8K", resolutionLabel("4320p"))
        assertEquals("1080p", resolutionLabel("1080p"))
        assertEquals("1080p", resolutionLabel("1920x1080"))
        assertEquals("720p", resolutionLabel("1280x720"))
        assertEquals("576p", resolutionLabel("576P"))
        assertEquals("SD", resolutionLabel("SD"))
        assertNull(resolutionLabel(null))
        assertNull(resolutionLabel(""))
    }

    @Test fun channelLayoutsUseTheUsualNames() {
        assertEquals("1.0", channelLayoutLabel(1))
        assertEquals("2.0", channelLayoutLabel(2))
        assertEquals("5.1", channelLayoutLabel(6))
        assertEquals("7.1", channelLayoutLabel(8))
        assertEquals("4ch", channelLayoutLabel(4))
        assertEquals("10ch", channelLayoutLabel(10))
        assertNull(channelLayoutLabel(0))
        assertNull(channelLayoutLabel(-2))
        assertNull(channelLayoutLabel(null))
    }

    @Test fun bitratesUseMbpsFromAThousandKbps() {
        assertEquals("8.2 Mbps", formatBitrate(8200, Locale.US))
        assertEquals("1.0 Mbps", formatBitrate(1000, Locale.US))
        assertEquals("850 kbps", formatBitrate(850, Locale.US))
        assertEquals("999 kbps", formatBitrate(999, Locale.US))
        assertEquals("8,2 Mbps", formatBitrate(8200, Locale.GERMANY))
        assertNull(formatBitrate(0))
        assertNull(formatBitrate(-5))
        assertNull(formatBitrate(null))
    }

    @Test fun audioLanguagesAreReadable() {
        assertEquals("English", audioLanguageLabel("en", Locale.ENGLISH))
        assertEquals("Japanese", audioLanguageLabel("ja", Locale.ENGLISH))
        assertEquals("English", audioLanguageLabel("en-GB", Locale.ENGLISH))
        assertEquals("zzzz-weird", audioLanguageLabel("zzzz-weird", Locale.ENGLISH))
        assertNull(audioLanguageLabel(null))
        assertNull(audioLanguageLabel(" "))
    }

    // ---- flows ----------------------------------------------------------------------------

    @Test fun aTranscodeShowsSourceToTargetVideo() {
        val s = session(method = "transcode").copy(
            video_decision = "transcode", source_video_codec = "hevc", source_video_resolution = "2160p",
            target_video_codec = "h264", target_resolution = "1080p",
        )
        assertEquals(MediaFlow("HEVC 4K", "H.264 1080p"), videoFlow(s))
        assertEquals("HEVC 4K → H.264 1080p", videoFlow(s).text())
    }

    @Test fun aFullHeightTranscodeKeepsTheSourcesSizeClass() {
        val s = session(method = "transcode").copy(
            video_decision = "transcode", source_video_codec = "hevc", source_video_resolution = "2160p",
            source_video_height = 1600, target_video_codec = "h264", target_resolution = "1600p",
        )
        assertEquals("HEVC 4K → H.264 4K", videoFlow(s).text())
        assertEquals("HEVC 4K → H.264 1200p", videoFlow(s.copy(target_resolution = "1200p")).text())
    }

    @Test fun aDirectVideoShowsOnlyTheSource() {
        val s = session().copy(video_decision = "direct", source_video_codec = "h264", source_video_resolution = "1920x1080", target_video_codec = "hevc")
        assertEquals(MediaFlow("H.264 1080p", null), videoFlow(s))
        assertEquals("H.264 1080p", videoFlow(s).text())
    }

    @Test fun aTranscodeWithoutATargetShowsTheSourceOnly() {
        val s = session(method = "transcode").copy(video_decision = "transcode", source_video_codec = "hevc")
        assertEquals(MediaFlow("HEVC", null), videoFlow(s))
        assertNull(videoFlow(session(method = "transcode")).text())
    }

    @Test fun audioUsesTheTargetChannelsOnlyWhenReported() {
        val s = session(method = "direct").copy(
            audio_decision = "transcode", source_audio_codec = "truehd", source_audio_channels = 8,
            target_audio_codec = "aac",
        )
        assertEquals("TrueHD 7.1 → AAC", audioFlow(s).text())
        assertEquals("TrueHD 7.1 → AAC 5.1", audioFlow(s.copy(target_audio_channels = 6)).text())
    }

    @Test fun aTranscodedAudioWithoutATargetCodecAssumesAac() {
        val s = session().copy(audio_decision = "transcode", source_audio_codec = "dts", source_audio_channels = 6)
        assertEquals("DTS 5.1 → AAC", audioFlow(s).text())
    }

    @Test fun aDirectAudioShowsTheSourceFormat() {
        val s = session().copy(audio_decision = "direct", source_audio_codec = "eac3", source_audio_channels = 6, target_audio_codec = "aac")
        assertEquals("EAC3 5.1", audioFlow(s).text())
        assertNull(audioFlow(session()).text())
    }

    // ---- transcode details ----------------------------------------------------------------

    @Test fun hardwareAccelerationIsNamedLikeTheWeb() {
        fun mode(accel: String?) = transcodeModeOf(session(method = "transcode").copy(video_decision = "transcode", transcode_hw_accel = accel))
        assertEquals(TranscodeMode.Hardware("NVENC"), mode("nvenc"))
        assertEquals(TranscodeMode.Hardware("VAAPI"), mode("vaapi"))
        assertEquals(TranscodeMode.Hardware("QSV"), mode("qsv"))
        assertEquals(TranscodeMode.Hardware("VideoToolbox"), mode("videotoolbox"))
        assertEquals(TranscodeMode.Software, mode("none"))
        assertEquals(TranscodeMode.Pending, mode("auto"))
        assertEquals(TranscodeMode.Unknown, mode(null))
        assertEquals(TranscodeMode.Unknown, mode(" "))
    }

    @Test fun anAudioOnlyTranscodeHasNoEncoderMode() {
        val s = session().copy(video_decision = "direct", audio_decision = "transcode", transcode_hw_accel = "nvenc")
        assertEquals(TranscodeMode.AudioOnly, transcodeModeOf(s))
    }

    @Test fun nothingTranscodedHasNoMode() {
        assertNull(transcodeModeOf(session().copy(video_decision = "direct", audio_decision = "direct", transcode_hw_accel = "nvenc")))
    }

    @Test fun toneMappingNeedsATranscodedVideoAndAConfirmedExecutor() {
        fun tone(mode: String?, video: String = "transcode") = toneMapOf(session().copy(video_decision = video, tone_map_mode = mode))
        assertEquals(ToneMap.Hardware, tone("hardware"))
        assertEquals(ToneMap.Software, tone(" Software "))
        assertNull(tone("other"))
        assertNull(tone(null))
        assertNull(tone("hardware", video = "direct"))
    }

    // ---- delivery and route ---------------------------------------------------------------

    @Test fun aDirectPlayDeliversTheSourceContainer() {
        val s = session(method = "direct").copy(source_container = "mkv", output_container = "fmp4")
        assertEquals("MKV", sourceContainerLabel(s))
        assertEquals("MKV", deliveredContainerLabel(s))
    }

    @Test fun aTranscodeDeliversWhatTheNodeReported() {
        val base = session(method = "transcode").copy(source_container = "mkv")
        assertEquals("fMP4", deliveredContainerLabel(base.copy(output_container = "fmp4")))
        assertEquals("HLS · MPEG-TS", deliveredContainerLabel(base.copy(output_container = "mpegts", output_protocol = "hls")))
        assertEquals("HLS · WEBM", deliveredContainerLabel(base.copy(output_container = "webm", output_protocol = "hls")))
        assertEquals("HLS", deliveredContainerLabel(base.copy(output_protocol = "hls")))
        assertNull(deliveredContainerLabel(base))
    }

    @Test fun withoutRoutingFieldsTheLegacyNodeIsShown() {
        assertEquals(listOf(RouteNode(RouteRole.Node, "edge-1")), routeNodesOf(session().copy(node_display_name = "edge-1", reporting_node = "n1")))
        assertEquals(listOf(RouteNode(RouteRole.Node, "n1")), routeNodesOf(session().copy(reporting_node = "n1")))
        assertEquals(listOf(RouteNode(RouteRole.Node, null)), routeNodesOf(session().copy(reporting_node = "local")))
        assertEquals(listOf(RouteNode(RouteRole.Node, null)), routeNodesOf(session()))
    }

    @Test fun aTranscodeNodeAndAProxyEgressAreListedWorkFirst() {
        val s = session().copy(
            routing_workload = "video_transcode", routing_execution = "transcode", routing_execution_node_name = "gpu-1",
            routing_egress = "proxy", routing_egress_node_name = "edge-eu",
        )
        assertEquals(listOf(RouteNode(RouteRole.Transcode, "gpu-1"), RouteNode(RouteRole.Proxy, "edge-eu")), routeNodesOf(s))
    }

    @Test fun apiEgressNamesTheReportingServer() {
        val s = session().copy(routing_execution = "api", routing_egress = "api", reporting_node = "main")
        assertEquals(listOf(RouteNode(RouteRole.Server, "main")), routeNodesOf(s))
        assertEquals(listOf(RouteNode(RouteRole.Server, null)), routeNodesOf(s.copy(reporting_node = "Local")))
    }

    @Test fun aProxyThatBothRunsAndServesIsListedOnce() {
        val s = session().copy(
            routing_execution = "proxy", routing_execution_node_name = "edge-eu", routing_egress = "proxy", routing_egress_node_name = "edge-eu",
        )
        assertEquals(listOf(RouteNode(RouteRole.Proxy, "edge-eu")), routeNodesOf(s))
    }
}
