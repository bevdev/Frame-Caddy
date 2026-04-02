package com.framecaddy.app

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var drawingView: DrawingOverlayView
    private lateinit var tvNoVideo: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var tvPosition: TextView
    private lateinit var tvTrimInfo: TextView
    private lateinit var etInterval: EditText
    private lateinit var framesGrid: RecyclerView
    private lateinit var btnExtract: Button
    private lateinit var btnExport: Button
    private lateinit var btnPlay: Button

    private var player: ExoPlayer? = null
    private var videoUri: Uri? = null
    private var videoDurationMs: Long = 0
    private var trimStartMs: Long = 0
    private var trimEndMs: Long = 0

    private lateinit var frameAdapter: FrameAdapter
    private val handler = Handler(Looper.getMainLooper())

    private val prefs by lazy { getSharedPreferences("framecaddy", MODE_PRIVATE) }

    private val positionUpdater = object : Runnable {
        override fun run() {
            val pos = player?.currentPosition ?: return
            seekBar.progress = pos.toInt()
            tvPosition.text = "${pos}ms"
            handler.postDelayed(this, 100)
        }
    }

    private val pickVideo = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { loadVideo(it) }
    }

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) pickVideo.launch("video/*")
        else Toast.makeText(this, "Permission needed to load videos", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        setupPlayer()
        setupFrameGrid()
        setupControls()
        etInterval.setText(prefs.getString("interval", "100"))
    }

    private fun bindViews() {
        playerView = findViewById(R.id.playerView)
        drawingView = findViewById(R.id.drawingView)
        tvNoVideo = findViewById(R.id.tvNoVideo)
        seekBar = findViewById(R.id.seekBar)
        tvPosition = findViewById(R.id.tvPosition)
        tvTrimInfo = findViewById(R.id.tvTrimInfo)
        etInterval = findViewById(R.id.etInterval)
        framesGrid = findViewById(R.id.framesGrid)
        btnExtract = findViewById(R.id.btnExtract)
        btnExport = findViewById(R.id.btnExport)
        btnPlay = findViewById(R.id.btnPlay)
    }

    private fun setupPlayer() {
        player = ExoPlayer.Builder(this).build().also { exo ->
            playerView.player = exo
            exo.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY && videoDurationMs == 0L) {
                        videoDurationMs = exo.duration.coerceAtLeast(0)
                        trimEndMs = videoDurationMs
                        seekBar.max = videoDurationMs.toInt()
                        updateTrimInfo()
                        tvNoVideo.visibility = View.GONE
                    }
                }
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    btnPlay.text = if (isPlaying) "⏸" else "▶"
                    if (isPlaying) handler.post(positionUpdater)
                    else handler.removeCallbacks(positionUpdater)
                }
            })
        }
    }

    private fun setupFrameGrid() {
        frameAdapter = FrameAdapter(
            onFrameClick = { ms ->
                player?.seekTo(trimStartMs + ms)
                val pos = player?.currentPosition ?: 0
                seekBar.progress = pos.toInt()
                tvPosition.text = "${pos}ms"
            },
            onSelectionChanged = { count ->
                btnExport.isEnabled = count > 0
                btnExport.text = if (count > 0) "Export Selected ($count)" else "Export Selected"
                btnExport.backgroundTintList = android.content.res.ColorStateList.valueOf(
                    if (count > 0) Color.parseColor("#4CAF50") else Color.parseColor("#2D2D2D")
                )
            }
        )
        framesGrid.layoutManager = GridLayoutManager(this, 2)
        framesGrid.adapter = frameAdapter
        framesGrid.isNestedScrollingEnabled = false
    }

    private fun setupControls() {
        findViewById<Button>(R.id.btnLoadVideo).setOnClickListener { requestVideoAccess() }
        btnPlay.setOnClickListener { togglePlay() }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    player?.seekTo(progress.toLong())
                    tvPosition.text = "${progress}ms"
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        // Step controls
        findViewById<Button>(R.id.btnBack1s).setOnClickListener { stepBy(-1000) }
        findViewById<Button>(R.id.btnBack100).setOnClickListener { stepBy(-100) }
        findViewById<Button>(R.id.btnFwd100).setOnClickListener { stepBy(100) }
        findViewById<Button>(R.id.btnFwd1s).setOnClickListener { stepBy(1000) }

        // Speed
        val speedBtns = listOf(
            R.id.btnSpeed025 to 0.25f,
            R.id.btnSpeed05 to 0.5f,
            R.id.btnSpeed1 to 1.0f
        )
        speedBtns.forEach { (id, speed) ->
            findViewById<Button>(id).setOnClickListener {
                player?.setPlaybackSpeed(speed)
                speedBtns.forEach { (bid, _) ->
                    findViewById<Button>(bid).backgroundTintList =
                        android.content.res.ColorStateList.valueOf(Color.parseColor("#2D2D2D"))
                }
                it.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(Color.parseColor("#4CAF50"))
            }
        }

        // Trim
        findViewById<Button>(R.id.btnSetStart).setOnClickListener {
            trimStartMs = player?.currentPosition ?: 0
            updateTrimInfo()
            Toast.makeText(this, "Start set: ${trimStartMs}ms", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnSetEnd).setOnClickListener {
            trimEndMs = player?.currentPosition ?: videoDurationMs
            updateTrimInfo()
            Toast.makeText(this, "End set: ${trimEndMs}ms", Toast.LENGTH_SHORT).show()
        }

        // Drawing tools
        val drawBtn = findViewById<Button>(R.id.btnToolDraw)
        val lineBtn = findViewById<Button>(R.id.btnToolLine)
        drawBtn.setOnClickListener {
            drawingView.setTool(DrawingOverlayView.Tool.FREE_DRAW)
            drawBtn.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#4CAF50"))
            lineBtn.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#2D2D2D"))
        }
        lineBtn.setOnClickListener {
            drawingView.setTool(DrawingOverlayView.Tool.LINE)
            lineBtn.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#4CAF50"))
            drawBtn.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#2D2D2D"))
        }
        findViewById<Button>(R.id.btnUndo).setOnClickListener { drawingView.undo() }
        findViewById<Button>(R.id.btnClearDraw).setOnClickListener { drawingView.clear() }

        listOf(
            R.id.btnColorWhite to Color.WHITE,
            R.id.btnColorRed to Color.RED,
            R.id.btnColorYellow to Color.YELLOW,
            R.id.btnColorGreen to Color.GREEN
        ).forEach { (id, color) ->
            findViewById<View>(id).setOnClickListener { drawingView.setColor(color) }
        }

        // Extract + export
        btnExtract.setOnClickListener { extractFrames() }
        btnExport.setOnClickListener { exportSelected() }
    }

    private fun requestVideoAccess() {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_VIDEO
        else
            Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED)
            pickVideo.launch("video/*")
        else
            requestPermission.launch(perm)
    }

    private fun loadVideo(uri: Uri) {
        videoUri = uri
        videoDurationMs = 0
        trimStartMs = 0
        trimEndMs = 0
        drawingView.clear()
        frameAdapter.clearFrames()
        player?.apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            play()
        }
    }

    private fun togglePlay() {
        player?.apply { if (isPlaying) pause() else play() }
    }

    private fun stepBy(ms: Long) {
        val p = player ?: return
        val end = if (trimEndMs > 0) trimEndMs else videoDurationMs
        val newPos = (p.currentPosition + ms).coerceIn(0, end)
        p.seekTo(newPos)
        seekBar.progress = newPos.toInt()
        tvPosition.text = "${newPos}ms"
    }

    private fun updateTrimInfo() {
        val end = if (trimEndMs > 0) trimEndMs else videoDurationMs
        tvTrimInfo.text = "Start: ${trimStartMs}ms  |  End: ${end}ms"
    }

    private fun extractFrames() {
        val uri = videoUri ?: run {
            Toast.makeText(this, "Load a video first", Toast.LENGTH_SHORT).show()
            return
        }
        val intervalText = etInterval.text.toString().trim()
        val intervalMs = intervalText.toLongOrNull()?.coerceAtLeast(1) ?: 100L
        prefs.edit().putString("interval", intervalText).apply()

        val start = trimStartMs
        val end = if (trimEndMs > start) trimEndMs else videoDurationMs
        if (end <= start) {
            Toast.makeText(this, "Set trim start before end", Toast.LENGTH_SHORT).show()
            return
        }

        btnExtract.isEnabled = false
        btnExtract.text = "Extracting..."
        frameAdapter.clearFrames()

        lifecycleScope.launch {
            val frames = withContext(Dispatchers.IO) {
                val retriever = MediaMetadataRetriever()
                val result = mutableListOf<FrameItem>()
                try {
                    retriever.setDataSource(applicationContext, uri)
                    var t = start
                    while (t <= end) {
                        val bmp = retriever.getFrameAtTime(
                            t * 1000L,
                            MediaMetadataRetriever.OPTION_CLOSEST
                        )
                        bmp?.let {
                            // Scale down to save memory
                            val scaled = Bitmap.createScaledBitmap(it, 480,
                                (it.height * 480f / it.width).toInt(), true)
                            if (scaled !== it) it.recycle()
                            result.add(FrameItem(scaled, t - start))
                        }
                        t += intervalMs
                    }
                } catch (_: Exception) {
                } finally {
                    retriever.release()
                }
                result
            }
            frameAdapter.setFrames(frames)
            btnExtract.isEnabled = true
            btnExtract.text = "Extract Frames"
            if (frames.isEmpty())
                Toast.makeText(this@MainActivity, "No frames extracted", Toast.LENGTH_SHORT).show()
        }
    }

    private fun exportSelected() {
        val selected = frameAdapter.getSelectedFrames()
        if (selected.isEmpty()) return
        lifecycleScope.launch {
            val uris = withContext(Dispatchers.IO) {
                selected.mapNotNull { saveFrame(it.bitmap, it.timestampMs) }
            }
            if (uris.isNotEmpty()) {
                val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "image/jpeg"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, "Export Frames"))
            }
        }
    }

    private fun saveFrame(bitmap: Bitmap, timestampMs: Long): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "framecaddy_${timestampMs}ms_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/FrameCaddy")
        }
        return try {
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            uri?.also {
                contentResolver.openOutputStream(it)?.use { s -> bitmap.compress(Bitmap.CompressFormat.JPEG, 90, s) }
            }
        } catch (_: Exception) { null }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(positionUpdater)
        player?.release()
        player = null
    }
}
