package si.vicor

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.format.DateUtils
import android.text.format.Formatter
import android.util.LruCache
import android.util.Size
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.util.UnstableApi
import java.util.Locale
import java.util.concurrent.Executors

data class Video(val uri: Uri, val name: String, val size: Long, val durationMs: Long, val dateAdded: Long)

@UnstableApi
class MainActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val thumbs = object : LruCache<Uri, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: Uri, value: Bitmap) = value.byteCount
    }

    private val prefs by lazy { getSharedPreferences("vicor", MODE_PRIVATE) }

    private var videos = listOf<Video>()
    private var shared: Video? = null

    private lateinit var list: ListView
    private lateinit var empty: TextView
    private lateinit var permissionPanel: View
    private lateinit var optionsPanel: View
    private lateinit var progressPanel: View
    private lateinit var resultPanel: View
    private lateinit var mode: RadioGroup
    private lateinit var maxSizeRow: View
    private lateinit var maxSize: EditText
    private lateinit var keepAudio: CheckBox
    private lateinit var progressText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var resultText: TextView
    private lateinit var resultActions: View

    private val videoPermissions = when {
        // Android 14+ may grant access to only some videos; that's fine, list those.
        Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private val stateListener: (EncodeState) -> Unit = ::render

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        list = findViewById(R.id.videos)
        empty = findViewById(R.id.empty)
        permissionPanel = findViewById(R.id.permission_panel)
        optionsPanel = findViewById(R.id.options_panel)
        progressPanel = findViewById(R.id.progress_panel)
        resultPanel = findViewById(R.id.result_panel)
        mode = findViewById(R.id.mode)
        maxSizeRow = findViewById(R.id.max_size_row)
        maxSize = findViewById(R.id.max_size)
        keepAudio = findViewById(R.id.keep_audio)
        progressText = findViewById(R.id.progress_text)
        progress = findViewById(R.id.progress)
        resultText = findViewById(R.id.result_text)
        resultActions = findViewById(R.id.result_actions)

        list.adapter = adapter

        mode.check(if (prefs.getBoolean("small", true)) R.id.mode_small else R.id.mode_original)
        maxSize.setText(String.format(Locale.getDefault(), "%d", prefs.getInt("max_size", 720)))
        keepAudio.isChecked = prefs.getBoolean("keep_audio", true)
        maxSizeRow.visibility = if (mode.checkedRadioButtonId == R.id.mode_small) View.VISIBLE else View.GONE
        mode.setOnCheckedChangeListener { _, id ->
            maxSizeRow.visibility = if (id == R.id.mode_small) View.VISIBLE else View.GONE
        }

        findViewById<Button>(R.id.grant).setOnClickListener { requestPermissions(videoPermissions, 1) }
        findViewById<Button>(R.id.encode).setOnClickListener { encode() }
        findViewById<Button>(R.id.cancel).setOnClickListener { EncodeService.cancel(this) }
        findViewById<Button>(R.id.view).setOnClickListener { doneState()?.let { view(it.output) } }
        findViewById<Button>(R.id.send).setOnClickListener { doneState()?.let { send(it.output) } }
        findViewById<Button>(R.id.remove).setOnClickListener { doneState()?.let { remove(it.output) } }
        findViewById<Button>(R.id.done).setOnClickListener { EncodeStatus.set(EncodeState.Idle) }

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }

        handleIntent(intent)
        EncodeStatus.observe(stateListener)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshVideos()
    }

    override fun onDestroy() {
        EncodeStatus.remove(stateListener)
        io.shutdownNow()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) refreshVideos()
    }

    /** A video shared to vicor (or opened with it) goes to the top of the list, preselected. */
    private fun handleIntent(intent: Intent?) {
        val uri = when (intent?.action) {
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            Intent.ACTION_VIEW -> intent.data
            else -> null
        } ?: return

        var name = uri.lastPathSegment ?: "video"
        var size = 0L
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    size = c.getLong(1)
                }
            }
        } catch (_: Exception) {
        }
        shared = Video(uri, name, size, 0, System.currentTimeMillis() / 1000)
        showVideos(videos)
    }

    private fun refreshVideos() {
        if (videoPermissions.none { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            permissionPanel.visibility = View.VISIBLE
            showVideos(emptyList())
            return
        }
        permissionPanel.visibility = View.GONE
        io.execute {
            val found = queryVideos()
            main.post { showVideos(found) }
        }
    }

    private fun queryVideos(): List<Video> {
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.DATE_ADDED,
        )
        // Don't offer our own output for re-encoding.
        val selection = "${MediaStore.Video.Media.RELATIVE_PATH} NOT LIKE ?"
        val args = arrayOf("${EncodeService.OUTPUT_DIR}/%")
        val result = mutableListOf<Video>()
        contentResolver.query(collection, projection, selection, args, "${MediaStore.Video.Media.DATE_ADDED} DESC")?.use { c ->
            while (c.moveToNext()) {
                result += Video(
                    uri = ContentUris.withAppendedId(collection, c.getLong(0)),
                    name = c.getString(1) ?: "?",
                    size = c.getLong(2),
                    durationMs = c.getLong(3),
                    dateAdded = c.getLong(4),
                )
            }
        }
        return result
    }

    private fun showVideos(found: List<Video>) {
        val selected = selectedVideo()?.uri
        videos = found
        adapter.notifyDataSetChanged()
        val all = allVideos()
        empty.visibility = if (all.isEmpty() && permissionPanel.visibility != View.VISIBLE) View.VISIBLE else View.GONE
        if (all.isNotEmpty()) {
            // Keep the previous selection if it's still there, otherwise pick the latest video.
            val index = if (shared != null && selected == shared?.uri) 0
            else all.indexOfFirst { it.uri == selected }.takeIf { it >= 0 } ?: 0
            list.setItemChecked(index, true)
        }
    }

    private fun allVideos(): List<Video> = listOfNotNull(shared) + videos

    private fun selectedVideo(): Video? = allVideos().getOrNull(list.checkedItemPosition)

    private fun encode() {
        val video = selectedVideo() ?: return
        val small = mode.checkedRadioButtonId == R.id.mode_small
        val size = maxSize.text.toString().toIntOrNull()?.coerceIn(64, 4096) ?: 720
        prefs.edit()
            .putBoolean("small", small)
            .putInt("max_size", size)
            .putBoolean("keep_audio", keepAudio.isChecked)
            .apply()
        EncodeService.start(this, video, small, size, keepAudio.isChecked)
    }

    private fun render(state: EncodeState) {
        optionsPanel.visibility = if (state is EncodeState.Idle) View.VISIBLE else View.GONE
        progressPanel.visibility = if (state is EncodeState.Running) View.VISIBLE else View.GONE
        resultPanel.visibility = if (state is EncodeState.Done || state is EncodeState.Failed) View.VISIBLE else View.GONE
        list.isEnabled = state is EncodeState.Idle

        when (state) {
            is EncodeState.Idle -> {}
            is EncodeState.Running -> {
                progressText.text = getString(R.string.encoding, state.name)
                progress.isIndeterminate = state.percent < 0
                progress.progress = state.percent.coerceAtLeast(0)
            }
            is EncodeState.Done -> {
                val percent = if (state.oldSize > 0) (state.newSize * 100 / state.oldSize).toInt() else 0
                resultText.text = getString(
                    R.string.result,
                    Formatter.formatShortFileSize(this, state.oldSize),
                    Formatter.formatShortFileSize(this, state.newSize),
                    percent,
                )
                resultActions.visibility = View.VISIBLE
            }
            is EncodeState.Failed -> {
                resultText.text = getString(R.string.failed, state.message)
                resultActions.visibility = View.GONE
            }
        }
    }

    private fun doneState() = EncodeStatus.state as? EncodeState.Done

    private fun view(uri: Uri) {
        startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
    }

    private fun send(uri: Uri) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("video/mp4")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, getString(R.string.send)))
    }

    private fun remove(uri: Uri) {
        try {
            contentResolver.delete(uri, null, null)
            Toast.makeText(this, R.string.removed, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
        }
        EncodeStatus.set(EncodeState.Idle)
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = allVideos().size
        override fun getItem(position: Int) = allVideos()[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_video, parent, false)
            val video = getItem(position)
            view.findViewById<TextView>(R.id.name).text = video.name
            view.findViewById<TextView>(R.id.details).text = listOfNotNull(
                Formatter.formatShortFileSize(this@MainActivity, video.size).takeIf { video.size > 0 },
                DateUtils.formatElapsedTime(video.durationMs / 1000).takeIf { video.durationMs > 0 },
                DateUtils.getRelativeTimeSpanString(video.dateAdded * 1000).toString(),
            ).joinToString(" · ")
            bindThumb(view.findViewById(R.id.thumb), video.uri)
            return view
        }
    }

    private fun bindThumb(image: ImageView, uri: Uri) {
        image.tag = uri
        val cached = thumbs.get(uri)
        image.setImageBitmap(cached)
        if (cached != null) return
        io.execute {
            val bitmap = try {
                contentResolver.loadThumbnail(uri, Size(192, 108), null)
            } catch (_: Exception) {
                null
            } ?: return@execute
            main.post {
                thumbs.put(uri, bitmap)
                if (image.tag == uri) image.setImageBitmap(bitmap)
            }
        }
    }
}
