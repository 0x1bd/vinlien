package org.kvxd.vinlien.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat as MediaNotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

class MusicService : Service() {

    companion object {
        const val ACTION_PLAY = "org.kvxd.vinlien.ACTION_PLAY"
        const val ACTION_PAUSE = "org.kvxd.vinlien.ACTION_PAUSE"
        const val ACTION_NEXT = "org.kvxd.vinlien.ACTION_NEXT"
        const val ACTION_PREV = "org.kvxd.vinlien.ACTION_PREV"
        const val NOTIFICATION_ID = 42
        const val CHANNEL_ID = "vinlien_playback"
    }

    interface WebCommandListener {
        fun onPlay()
        fun onMusicPause()
        fun onNext()
        fun onPrev()
        fun onSeekTo(positionSeconds: Double)
    }

    inner class LocalBinder : Binder() {
        fun getService(): MusicService = this@MusicService
    }

    private val binder = LocalBinder()
    private lateinit var mediaSession: MediaSessionCompat
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    var webListener: WebCommandListener? = null

    private var isPlaying = false
    private var currentTitle = ""
    private var currentArtist = ""
    private var currentArtwork: Bitmap? = null
    private var artworkJob: Job? = null
    private var positionMs = PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN
    private var currentDurationMs = -1L
    private var isForeground = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        setupMediaSession()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> webListener?.onPlay()
            ACTION_PAUSE -> webListener?.onMusicPause()
            ACTION_NEXT -> webListener?.onNext()
            ACTION_PREV -> webListener?.onPrev()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        mediaSession.release()
        scope.cancel()
        super.onDestroy()
    }

    fun updateMetadata(title: String, artist: String, artworkUrl: String) {
        mainHandler.post {
            currentTitle = title
            currentArtist = artist
            currentDurationMs = -1L
            positionMs = PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN

            if (currentTitle.isEmpty()) {
                artworkJob?.cancel()
                currentArtwork = null
                mediaSession.isActive = false
                if (isForeground) {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                    isForeground = false
                }
                return@post
            }

            mediaSession.setMetadata(buildMetadata(null))

            artworkJob?.cancel()
            currentArtwork = null

            if (artworkUrl.isNotEmpty()) {
                artworkJob = scope.launch {
                    val bmp = withContext(Dispatchers.IO) { fetchBitmap(artworkUrl) }
                    if (bmp != null) {
                        currentArtwork = bmp
                        mediaSession.setMetadata(buildMetadata(bmp, currentDurationMs))
                    }
                    postNotificationUpdate()
                }
            }

            if (!isForeground) {
                startForeground(NOTIFICATION_ID, buildNotification())
                isForeground = true
            } else {
                postNotificationUpdate()
            }
        }
    }

    fun updatePlayState(playing: Boolean) {
        mainHandler.post {
            if (currentTitle.isEmpty()) return@post
            isPlaying = playing
            mediaSession.isActive = true
            mediaSession.setPlaybackState(buildPlaybackState())
            postNotificationUpdate()
        }
    }

    fun updatePosition(posSeconds: Double, durSeconds: Double) {
        mainHandler.post {
            val wasUnknown = positionMs == PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN
            positionMs = (posSeconds * 1000).toLong()
            val durMs = (durSeconds * 1000).toLong()
            if (durMs > 0 && durMs != currentDurationMs) {
                currentDurationMs = durMs
                mediaSession.setMetadata(buildMetadata(currentArtwork, durMs))
            }
            mediaSession.setPlaybackState(buildPlaybackState())
            if (wasUnknown) postNotificationUpdate()
        }
    }

    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "VinlienSession").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    webListener?.onPlay()
                }

                override fun onPause() {
                    webListener?.onMusicPause()
                }

                override fun onSkipToNext() {
                    webListener?.onNext()
                }

                override fun onSkipToPrevious() {
                    webListener?.onPrev()
                }

                override fun onSeekTo(pos: Long) {
                    webListener?.onSeekTo(pos / 1000.0)
                }
            })
            setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setState(PlaybackStateCompat.STATE_NONE, 0, 1f)
                    .setActions(supportedActions())
                    .build()
            )
        }
    }

    private fun buildPlaybackState(): PlaybackStateCompat {
        val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        return PlaybackStateCompat.Builder()
            .setState(state, positionMs, 1f)
            .setActions(supportedActions())
            .build()
    }

    private fun buildMetadata(artwork: Bitmap?, durationMs: Long = -1L): MediaMetadataCompat =
        MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentArtist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, getString(R.string.app_name))
            .apply {
                if (durationMs > 0) putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs)
                artwork?.let { putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it) }
            }
            .build()

    private fun supportedActions() =
        PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_SEEK_TO

    private fun postNotificationUpdate() {
        if (!isForeground) return
        mainHandler.post {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        fun action(label: String, icon: Int, intentAction: String): NotificationCompat.Action {
            val pi = PendingIntent.getService(
                this, intentAction.hashCode(),
                Intent(this, MusicService::class.java).apply { action = intentAction },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            return NotificationCompat.Action(icon, label, pi)
        }

        val playPauseIcon = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow
        val playPauseLabel = if (isPlaying) getString(R.string.action_pause) else getString(R.string.action_play)
        val playPauseCmd = if (isPlaying) ACTION_PAUSE else ACTION_PLAY

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle(currentTitle)
            .setContentText(currentArtist)
            .setContentIntent(contentIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(isPlaying)
            .addAction(action(getString(R.string.action_previous), R.drawable.ic_skip_previous, ACTION_PREV))
            .addAction(action(playPauseLabel, playPauseIcon, playPauseCmd))
            .addAction(action(getString(R.string.action_next), R.drawable.ic_skip_next, ACTION_NEXT))
            .setStyle(
                MediaNotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .apply { currentArtwork?.let { setLargeIcon(it) } }
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun fetchBitmap(url: String): Bitmap? = try {
        val conn = URL(url).openConnection().apply {
            connectTimeout = 5_000
            readTimeout = 5_000
        }
        BitmapFactory.decodeStream(conn.getInputStream())
    } catch (_: Exception) {
        null
    }
}
