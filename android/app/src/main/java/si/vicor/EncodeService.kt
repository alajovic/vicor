package si.vicor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.MediaStore
import android.text.format.Formatter
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Codec
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Re-encodes one video with the device's hardware H.264 encoder (via Media3 Transformer)
 * and stores the result in Movies/vicor. Runs in the foreground so encoding continues
 * with the screen off and the phone in a pocket.
 */
@UnstableApi
class EncodeService : Service() {

    companion object {
        private const val EXTRA_NAME = "name"
        private const val EXTRA_SIZE = "size"
        private const val EXTRA_SMALL = "small"
        private const val EXTRA_MAX_SIZE = "max_size"
        private const val EXTRA_KEEP_AUDIO = "keep_audio"
        private const val ACTION_CANCEL = "si.vicor.CANCEL"

        private const val CHANNEL_PROGRESS = "progress"
        private const val CHANNEL_RESULT = "result"
        private const val NOTIF_PROGRESS = 1
        private const val NOTIF_RESULT = 2

        const val OUTPUT_DIR = "Movies/vicor"

        // Bits per pixel per frame; roughly what x264 at CRF 23 needs, a bit
        // generous because phone hardware encoders are less efficient.
        private const val BITS_PER_PIXEL = 0.12
        private const val SMALL_AUDIO_BITRATE = 96_000

        fun start(context: Context, video: Video, small: Boolean, maxSize: Int, keepAudio: Boolean) {
            val intent = Intent(context, EncodeService::class.java)
                .setData(video.uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putExtra(EXTRA_NAME, video.name)
                .putExtra(EXTRA_SIZE, video.size)
                .putExtra(EXTRA_SMALL, small)
                .putExtra(EXTRA_MAX_SIZE, maxSize)
                .putExtra(EXTRA_KEEP_AUDIO, keepAudio)
            context.startForegroundService(intent)
        }

        fun cancel(context: Context) {
            context.startService(Intent(context, EncodeService::class.java).setAction(ACTION_CANCEL))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var transformer: Transformer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var notifications: NotificationManager
    private lateinit var tmpFile: File
    private var name = ""
    private var oldSize = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
        )
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_RESULT, getString(R.string.done), NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            if (transformer != null) {
                transformer?.cancel()
                transformer = null
                tmpFile.delete()
                EncodeStatus.set(EncodeState.Idle)
            }
            finish()
            return START_NOT_STICKY
        }

        val uri = intent?.data
        if (uri == null || transformer != null) {
            // Nothing to do, or already busy: the UI never offers a second encode.
            if (transformer == null) finish()
            return START_NOT_STICKY
        }

        name = intent.getStringExtra(EXTRA_NAME) ?: "video"
        oldSize = intent.getLongExtra(EXTRA_SIZE, 0)
        val type = if (Build.VERSION.SDK_INT >= 35) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        startForeground(NOTIF_PROGRESS, progressNotification(-1), type)

        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vicor:encode")
            .apply { acquire(6 * 60 * 60 * 1000L) }

        try {
            encode(
                uri,
                small = intent.getBooleanExtra(EXTRA_SMALL, false),
                maxSize = intent.getIntExtra(EXTRA_MAX_SIZE, 720),
                keepAudio = intent.getBooleanExtra(EXTRA_KEEP_AUDIO, true),
            )
        } catch (e: Exception) {
            fail(e.message ?: e.toString())
        }
        return START_NOT_STICKY
    }

    private fun encode(uri: Uri, small: Boolean, maxSize: Int, keepAudio: Boolean) {
        val info = probe(uri)
        var outWidth = info.width
        var outHeight = info.height
        val videoEffects = mutableListOf<androidx.media3.common.Effect>()
        if (small && max(info.width, info.height) > maxSize) {
            // Fit inside maxSize x maxSize, like vicor's ffmpeg scale filter.
            val scale = maxSize.toDouble() / max(info.width, info.height)
            outWidth = (info.width * scale).roundToInt() and 1.inv()
            outHeight = (info.height * scale).roundToInt() and 1.inv()
            // A uniform scale doesn't care whether the frame is rotated or not.
            videoEffects += ScaleAndRotateTransformation.Builder().setScale(scale.toFloat(), scale.toFloat()).build()
        }

        var bitrate = (outWidth.toLong() * outHeight * info.frameRate * BITS_PER_PIXEL).toInt()
        if (info.bitrate > 0) bitrate = min(bitrate, (info.bitrate * 0.7).toInt())
        bitrate = max(bitrate, 300_000)

        val defaultFactory = DefaultEncoderFactory.Builder(this)
            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
            .apply {
                if (small) {
                    setRequestedAudioEncoderSettings(
                        AudioEncoderSettings.Builder().setBitrate(SMALL_AUDIO_BITRATE).build()
                    )
                }
            }
            .setEnableFallback(true)
            .build()
        // Requested audio settings alone don't make Transformer re-encode audio; it would be
        // passed through untouched. Small mode shrinks the audio too, so force it.
        val encoderFactory = if (small) {
            object : Codec.EncoderFactory by defaultFactory {
                override fun audioNeedsEncoding() = true
            }
        } else {
            defaultFactory
        }

        val item = EditedMediaItem.Builder(MediaItem.fromUri(uri))
            .setRemoveAudio(!keepAudio)
            .setEffects(Effects(listOf(), videoEffects))
            .build()

        tmpFile = File(cacheDir, "encode.mp4").apply { delete() }
        val t = Transformer.Builder(this)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(encoderFactory)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    transformer = null
                    thread { publish() }
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    transformer = null
                    tmpFile.delete()
                    fail(exportException.message ?: exportException.errorCodeName)
                }
            })
            .build()
        transformer = t
        EncodeStatus.set(EncodeState.Running(name, -1))
        t.start(item, tmpFile.path)
        handler.post(progressPoller)
    }

    private val progressPoller = object : Runnable {
        private val holder = ProgressHolder()
        private var lastPercent = -2

        override fun run() {
            val t = transformer ?: return
            val percent = if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) holder.progress else -1
            if (percent != lastPercent) {
                lastPercent = percent
                EncodeStatus.set(EncodeState.Running(name, percent))
                notifications.notify(NOTIF_PROGRESS, progressNotification(percent))
            }
            handler.postDelayed(this, 500)
        }
    }

    private data class SourceInfo(val width: Int, val height: Int, val frameRate: Double, val bitrate: Int)

    private fun probe(uri: Uri): SourceInfo {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(this, uri)
            fun int(key: Int) = r.extractMetadata(key)?.toIntOrNull() ?: 0
            val frames = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
            val durationMs = int(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val fps = if (frames > 0 && durationMs > 0) frames * 1000.0 / durationMs else 30.0
            return SourceInfo(
                width = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH),
                height = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT),
                frameRate = fps.coerceIn(1.0, 240.0),
                bitrate = int(MediaMetadataRetriever.METADATA_KEY_BITRATE),
            )
        } finally {
            r.release()
        }
    }

    /** Copies the finished file into the shared Movies/vicor collection. Background thread. */
    private fun publish() {
        try {
            val displayName = name.substringBeforeLast('.') + ".mp4"
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, OUTPUT_DIR)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val out = contentResolver.insert(collection, values) ?: error("cannot create output file")
            try {
                contentResolver.openOutputStream(out)!!.use { os -> tmpFile.inputStream().use { it.copyTo(os) } }
            } catch (e: Exception) {
                contentResolver.delete(out, null, null)
                throw e
            }
            contentResolver.update(out, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            val newSize = tmpFile.length()
            tmpFile.delete()
            handler.post { succeed(out, newSize) }
        } catch (e: Exception) {
            tmpFile.delete()
            handler.post { fail(e.message ?: e.toString()) }
        }
    }

    private fun succeed(output: Uri, newSize: Long) {
        EncodeStatus.set(EncodeState.Done(name, output, oldSize, newSize))
        val text = "${Formatter.formatShortFileSize(this, oldSize)} → ${Formatter.formatShortFileSize(this, newSize)}"
        notifyResult(getString(R.string.notif_done, name), text)
        vibrate()
        finish()
    }

    private fun fail(message: String) {
        EncodeStatus.set(EncodeState.Failed(name, message))
        notifyResult(getString(R.string.notif_failed, name), message)
        vibrate()
        finish()
    }

    private fun finish() {
        handler.removeCallbacks(progressPoller)
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        transformer?.cancel()
        handler.removeCallbacks(progressPoller)
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun progressNotification(percent: Int): Notification {
        val cancel = PendingIntent.getService(
            this, 1,
            Intent(this, EncodeService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(getString(R.string.encoding, name))
            .setProgress(100, max(percent, 0), percent < 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .addAction(Notification.Action.Builder(null, getString(R.string.cancel), cancel).build())
            .build()
    }

    private fun notifyResult(title: String, text: String) {
        notifications.notify(
            NOTIF_RESULT,
            Notification.Builder(this, CHANNEL_RESULT)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(openAppIntent())
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun vibrate() {
        val vibrator = if (Build.VERSION.SDK_INT >= 31) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300), -1))
    }
}
