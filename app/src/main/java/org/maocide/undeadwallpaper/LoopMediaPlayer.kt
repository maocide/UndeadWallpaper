package org.maocide.undeadwallpaper

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import android.view.Surface

class LoopMediaPlayer private constructor(private val mContext: Context, private val mUri: Uri) {
    companion object {
        private val TAG = LoopMediaPlayer::class.java.simpleName

        fun create(context: Context, uri: Uri): LoopMediaPlayer {
            return LoopMediaPlayer(context, uri)
        }
    }

    private var mCounter = 1
    private var mCurrentPlayer: MediaPlayer? = MediaPlayer.create(mContext, mUri)
    private var mNextPlayer: MediaPlayer? = null
    private var stop = false
    private var mSurface: Surface? = null

    init {
        mCurrentPlayer?.setOnPreparedListener { mCurrentPlayer?.start() }
        createNextMediaPlayer()
    }

    private fun createNextMediaPlayer() {
        val next = MediaPlayer.create(mContext, mUri)
        mNextPlayer = next
       //mCurrentPlayer?.setNextMediaPlayer(mNextPlayer)
        mCurrentPlayer?.setOnCompletionListener(MediaPlayer.OnCompletionListener { mediaPlayer ->
                Log.d(TAG, "onCompletionListener -> CALLED Create next or stop")
                //mediaPlayer.reset()
                //mediaPlayer.release()
                if(!stop) {
                    Log.d(TAG, "onCompletionListener -> NEXT")
                    //mCurrentPlayer = mNextPlayer
                    //createNextMediaPlayer()
                    mediaPlayer.start()
                    Log.d(TAG, "Loop #${++mCounter}")
                }
            }
        )
    }

    public fun setSurface(surface: Surface?) {
        mCurrentPlayer?.setSurface(surface)
        mNextPlayer?.setSurface(mSurface)
        mSurface = surface
    }

    public fun scheduleStop() {
        stop = true
    }
}
