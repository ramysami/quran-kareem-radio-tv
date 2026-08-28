package com.ramy.quranradiotv

import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player

/**
 * The player as everyone outside this app sees it: a broadcast, not a track.
 *
 * The system's media panel draws its scrubber from whatever duration the
 * session reports, and ExoPlayer answers with the length of the live window —
 * three HLS segments, half a minute of it. So the radio arrived in the shade as
 * a thirty-second song, seventeen seconds in, restarting every time the window
 * slid forward. There is no such length to report: the broadcast has no
 * beginning to be seventeen seconds into and no end to arrive at. Saying so
 * takes the scrubber away, which is the truthful picture of live audio.
 *
 * Dropping the seek commands makes the same statement to the transport
 * controls, and is what removes the next and previous buttons: the system lays
 * its own buttons out from the actions the session advertises and pays no
 * attention to the ones in our notification.
 */
class LivePlayer(player: Player) : ForwardingPlayer(player) {

    override fun getDuration(): Long = C.TIME_UNSET

    override fun getContentDuration(): Long = C.TIME_UNSET

    override fun isCurrentMediaItemSeekable(): Boolean = false

    override fun getAvailableCommands(): Player.Commands =
        super.getAvailableCommands().buildUpon().removeAll(*SEEK_COMMANDS).build()

    companion object {
        /**
         * Everything that implies a position to move to or a track to move on
         * to. There is one stream, it has one position, and it is "now".
         */
        val SEEK_COMMANDS = intArrayOf(
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_MEDIA_ITEM,
            Player.COMMAND_SEEK_BACK,
            Player.COMMAND_SEEK_FORWARD,
        )
    }
}
