package com.maxrave.media3.service

import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityManager.RunningAppProcessInfo
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.content.getSystemService
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaController
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionToken
import androidx.media3.ui.DefaultMediaDescriptionAdapter
import androidx.media3.ui.PlayerNotificationManager
import com.google.common.util.concurrent.MoreExecutors
import com.maxrave.common.MEDIA_NOTIFICATION
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.logger.Logger
import com.maxrave.media3.R
import com.maxrave.media3.extension.toCommandButton
import com.maxrave.media3.utils.CoilBitmapLoader
import com.maxrave.media3.extension.LyricsUdpExporter
import com.maxrave.domain.repository.LyricsCanvasRepository
import androidx.media3.common.MediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.qualifier.named
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds
import com.maxrave.domain.utils.Resource
import com.maxrave.domain.utils.toLyricsEntity
import com.maxrave.domain.data.model.metadata.Lyrics
import kotlinx.coroutines.flow.firstOrNull

@UnstableApi
internal class SimpleMediaService :
    MediaLibraryService(),
    KoinComponent {
    private val coroutineScope by inject<CoroutineScope>(named(com.maxrave.common.Config.SERVICE_SCOPE))
    // Session-level player from DI: the ForwardingPlayer wrapped with Cast support in the
    // full build (plain ForwardingPlayer in the FOSS build).
    private val player: Player by inject<Player>(qualifier = named(com.maxrave.common.Config.MAIN_PLAYER))
    private val coilBitmapLoader: CoilBitmapLoader by inject<CoilBitmapLoader>()

    private var mediaSession: MediaLibrarySession? = null

    private val simpleMediaSessionCallback: MediaLibrarySession.Callback by inject<MediaLibrarySession.Callback>()

    private val simpleMediaServiceHandler: MediaPlayerHandler by inject<MediaPlayerHandler>()
    private val dataStoreManager: DataStoreManager by inject<DataStoreManager>()
    private val lyricsCanvasRepository: LyricsCanvasRepository by inject<LyricsCanvasRepository>()
    private var mzkLyricsJob: kotlinx.coroutines.Job? = null

    private val binder = MusicBinder()

    private lateinit var playerNotificationManager: PlayerNotificationManager

    inner class MusicBinder : Binder() {
        val service: SimpleMediaService
            get() = this@SimpleMediaService

        fun setActivitySession(
            context: Context,
            activity: Class<out Activity>,
        ) {
            mediaSession?.setSessionActivity(
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, activity),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        Logger.w("Service", "Simple Media Service Bound")
        return super.onBind(intent) ?: binder
    }

    @UnstableApi
    override fun onCreate() {
        super.onCreate()
        Logger.w("Service", "Simple Media Service Created")

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider(
                this,
                { MEDIA_NOTIFICATION.NOTIFICATION_ID },
                MEDIA_NOTIFICATION.NOTIFICATION_CHANNEL_ID,
                R.string.notification_channel_name,
            ).apply {
                setSmallIcon(R.drawable.mono)
            },
        )

        if (mediaSession == null) {
            mediaSession =
                provideMediaLibrarySession(
                    this,
                    player,
                    simpleMediaSessionCallback,
                )
        }

        // MZKXLF-HOOK: no borrar en merge - exporta letra y posicion en tiempo real por UDP a Termux-X11
        var mzkLastSongTitle = ""
        var mzkLastSongArtist = ""
        var mzkLastSongDurationMs = 0L
        var mzkSongResendTick = 0
        var mzkLyricsExportEnabled = false
        coroutineScope.launch {
            dataStoreManager.lyricsUdpEnabled.collect { value ->
                mzkLyricsExportEnabled = (value == DataStoreManager.TRUE)
            }
        }
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val videoId = mediaItem?.mediaId?.substringAfterLast("/")
                mzkLyricsJob?.cancel()
                if (videoId != null && mzkLyricsExportEnabled) {
                    val artist = mediaItem?.mediaMetadata?.artist?.toString() ?: ""
                    val title = mediaItem?.mediaMetadata?.title?.toString() ?: ""
                    val durationMsForSong = player.duration.takeIf { it != androidx.media3.common.C.TIME_UNSET } ?: 0L
                    LyricsUdpExporter.sendSong(title, artist, durationMsForSong)
                    mzkLastSongTitle = title
                    mzkLastSongArtist = artist
                    mzkLastSongDurationMs = durationMsForSong
                    mzkLyricsJob = coroutineScope.launch {
                        try {
                            LyricsUdpExporter.sendDebug("START videoId=$videoId")
                            val entity = lyricsCanvasRepository.getSavedLyrics(videoId).firstOrNull()
                            LyricsUdpExporter.sendDebug(
                                "ENTITY entity=${entity != null} lines=${entity?.lines?.size}"
                            )
                            val lines = entity?.lines
                            if (!lines.isNullOrEmpty()) {
                                lines.forEachIndexed { index, line ->
                                    LyricsUdpExporter.sendMeta(
                                        videoId,
                                        index,
                                        line.startTimeMs.toLongOrNull() ?: 0L,
                                        line.words,
                                    )
                                }
                            } else {
                                fetchAndSendLyricsFallback(videoId, artist, title)
                            }
                        } catch (e: Exception) {
                            LyricsUdpExporter.sendDebug("ERROR ${e::class.simpleName}: ${e.message}")
                        }
                    }
                }
            }

            private suspend fun fetchAndSendLyricsFallback(
                videoId: String,
                artist: String,
                title: String,
            ) {
                try {
                    LyricsUdpExporter.sendDebug("FALLBACK fetching network lyrics for $videoId")

                    var lyrics: Lyrics? = null

                    kotlinx.coroutines.withTimeoutOrNull(10000) {
                        lyricsCanvasRepository.getSimpMusicLyrics(videoId).collect { res ->
                            if (res is Resource.Success && res.data != null) {
                                lyrics = res.data
                            }
                        }
                    }

                    if (lyrics == null) {
                        val durationMs = player.duration.takeIf { it != androidx.media3.common.C.TIME_UNSET }
                        val durationSec = durationMs?.let { (it / 1000).toInt() }
                        kotlinx.coroutines.withTimeoutOrNull(10000) {
                            lyricsCanvasRepository.getLrclibLyricsData(artist, title, durationSec).collect { res ->
                                if (res is Resource.Success && res.data != null) {
                                    lyrics = res.data
                                }
                            }
                        }
                    }

                    val result = lyrics
                    if (result != null) {
                        val entity = result.toLyricsEntity(videoId)
                        lyricsCanvasRepository.insertLyrics(entity)
                        LyricsUdpExporter.sendDebug("FALLBACK success, lines=${entity.lines?.size}")
                        entity.lines?.forEachIndexed { index, line ->
                            LyricsUdpExporter.sendMeta(
                                videoId,
                                index,
                                line.startTimeMs.toLongOrNull() ?: 0L,
                                line.words,
                            )
                        }
                    } else {
                        LyricsUdpExporter.sendDebug("FALLBACK no lyrics found for $videoId")
                    }
                } catch (e: Exception) {
                    LyricsUdpExporter.sendDebug("FALLBACK ERROR ${e::class.simpleName}: ${e.message}")
                }
            }
        })

        coroutineScope.launch {
            while (isActive) {
                if (player.isPlaying && mzkLyricsExportEnabled) {
                    LyricsUdpExporter.sendPosition(player.currentPosition)
                    mzkSongResendTick++
                    if (mzkSongResendTick >= 20 && mzkLastSongTitle.isNotEmpty()) {
                        mzkSongResendTick = 0
                        LyricsUdpExporter.sendSong(mzkLastSongTitle, mzkLastSongArtist, mzkLastSongDurationMs)
                    }
                }
                delay(250)
            }
        }

        simpleMediaServiceHandler.onUpdateNotification = { list ->
            val commandButtonList = list.map { it.toCommandButton(this) }
            mediaSession?.setMediaButtonPreferences(
                commandButtonList,
            )
        }

        val sessionToken = SessionToken(this, ComponentName(this, SimpleMediaService::class.java))
        val controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture.addListener({ controllerFuture.get() }, MoreExecutors.directExecutor())

        if (runBlocking { dataStoreManager.keepServiceAlive.first() == DataStoreManager.TRUE }) {
            val notificationManager = getSystemService<NotificationManager>()
            notificationManager?.run {
                createNotificationChannel(
                    NotificationChannel(
                        "media_playback_channel",
                        "Now playing",
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply {
                        setSound(null, null)
                        enableLights(false)
                        enableVibration(false)
                    },
                )
            }
            playerNotificationManager =
                PlayerNotificationManager
                    .Builder(this, 2026, "media_playback_channel")
                    .setNotificationListener(
                        object : PlayerNotificationManager.NotificationListener {
                            override fun onNotificationPosted(
                                notificationId: Int,
                                notification: Notification,
                                ongoing: Boolean,
                            ) {
                                fun startFg() {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                        startForeground(notificationId, notification, FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                                    } else {
                                        startForeground(notificationId, notification)
                                    }
                                }
                                coroutineScope.launch {
                                    while (coroutineScope.isActive) {
                                        startFg()
                                        delay(30.seconds)
                                    }
                                }
                            }
                        },
                    ).setMediaDescriptionAdapter(DefaultMediaDescriptionAdapter(mediaSession?.sessionActivity))
                    .build()
            playerNotificationManager.setPlayer(player)
            playerNotificationManager.setSmallIcon(R.drawable.mono)
            mediaSession?.platformToken?.let { playerNotificationManager.setMediaSessionToken(it) }
        }

        simpleMediaServiceHandler.onUpdateNotification = { list ->
            val commandButtonList = list.map { it.toCommandButton(this) }
            mediaSession?.setMediaButtonPreferences(
                commandButtonList,
            )
        }
    }

    @UnstableApi
    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        Logger.w("Service", "Simple Media Service Received Action: ${intent?.action}")
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = mediaSession

    @UnstableApi
    override fun onUpdateNotification(
        session: MediaSession,
        startInForegroundRequired: Boolean,
    ) {
        super.onUpdateNotification(session, startInForegroundRequired)
    }

    @UnstableApi
    fun release() {
        Logger.w("Service", "Starting release process")
        runBlocking {
            try {
                // Release MediaSession (don't release player - CrossfadeExoPlayerAdapter manages it)
                mediaSession?.run {
                    this.player.pause()
                    this.player.playWhenReady = false
                    // Don't call this.player.release() - CrossfadeExoPlayerAdapter manages player lifecycle
                    this.release()
                }
                // Release handler (contains coroutines and jobs, which also releases the adapter)
                simpleMediaServiceHandler.release()
                mediaSession = null
                Logger.w("Service", "Simple Media Service Released")
            } catch (e: Exception) {
                Logger.e("Service", "Error during release")
            }
        }
    }

    @UnstableApi
    override fun onDestroy() {
        super.onDestroy()
        Logger.w("Service", "Simple Media Service Destroyed")
        if (simpleMediaServiceHandler.shouldReleaseOnTaskRemoved()) {
            release()
        }
    }

    override fun onTrimMemory(level: Int) {
        Logger.w("Service", "Simple Media Service Trim Memory Level: $level")
        simpleMediaServiceHandler.mayBeSaveRecentSong()
    }

    @UnstableApi
    override fun onTaskRemoved(rootIntent: Intent?) {
        Logger.w("Service", "Simple Media Service Task Removed")
        if (simpleMediaServiceHandler.shouldReleaseOnTaskRemoved()) {
            release()
            super.onTaskRemoved(rootIntent)
            exitProcess(0)
        }
    }

    // Can't inject by Koin because it depend on service
    @UnstableApi
    private fun provideMediaLibrarySession(
        service: MediaLibraryService,
        player: Player,
        callback: MediaLibrarySession.Callback,
    ): MediaLibrarySession =
        MediaLibrarySession
            .Builder(
                service,
                player,
                callback,
            ).setId(this.javaClass.name)
            .setBitmapLoader(coilBitmapLoader)
            .build()

    private fun isAppInForeground(): Boolean {
        val appProcessInfo = RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(appProcessInfo)
        return appProcessInfo.importance == RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }
}