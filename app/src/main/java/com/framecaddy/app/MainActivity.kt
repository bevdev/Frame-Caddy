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
import android.view.MotionEvent
import android.view.ScaleGestureDetector
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var videoContent: FrameLayout
    private lateinit var drawingView: DrawingOverlayView
    private lateinit var tvNoVideo: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var tvPosition: TextView
    private lateinit var tvTrimInfo: TextView
    private lateinit var btnPlay: Button
    private lateinit var btnAdjust: Button
    private lateinit var normalControls: View
    private lateinit var adjustControls: View

    private var player: ExoPlayer? = null
    private var videoUri: Uri? = null
    private var videoDurationMs: Long = 0
    private var trimStartMs: Long = 0
    private var trimEndMs: Long = 0
    private var adjustModeActive = false

    // Pan/zoom state
    private var videoScaleFactor = 1f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private lateinit var scaleDetector: ScaleGestureDetector

    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("framecaddy", MODE_PRIVATE) }

    // Hold-to-scrub
    private var scrubRunnable: Runnable? = null

    private val positionUpdater = object : Runnable {
        override fun run() {
            val p = player ?: return
            val pos = p.currentPosition
            val end = effectiveTrimEnd()
            if (p.isPlaying && end > 0 && pos >= end) {
                p.seekTo(trimStartMs)
                seekBar.progress = trimStartMs.toInt()
                tvPosition.text = "${trimStartMs}ms"
            } else {
                seekBar.progress = pos.toInt()
                tvPosition.text = "${pos}ms"
            }
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
        setupScaleDetector()
        setupPlayer()
        setupControls()
    }

    private fun bindViews() {
        playerView = findViewById(R.id.playerView)
        videoContent = findViewById(R.id.videoContent)
        drawingView = findViewById(R.id.drawingView)
        tvNoVideo = findViewById(R.id.tvNoVideo)
        seekBar = findViewById(R.id.seekBar)
        tvPosition = findViewById(R.id.tvPosition)
        tvTrimInfo = findViewById(R.id.tvTrimInfo)
        btnPlay = findViewById(R.id.btnPlay)
        btnAdjust = findViewById(R.id.btnAdjust)
        normalControls = findViewById(R.id.normalControls)
        adjustControls = findViewById(R.id.adjustControls)
    }

    private fun setupScaleDetector() {
        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                videoScaleFactor = (videoScaleFactor * detector.scaleFactor).coerceIn(0.5f, 4f)
                videoContent.scaleX = videoScaleFactor
                videoContent.scaleY = videoScaleFactor
                return true
            }
        })

        videoContent.setOnTouchListener { _, event ->
            if (!adjustModeActive || drawingView.touchEnabled) return@setOnTouchListener false
            scaleDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = event.x
                    lastTouchY = event.y
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!scaleDetector.isInProgress) {
                        videoContent.translationX += event.x - lastTouchX
                        videoContent.translationY += event.y - lastTouchY
                        lastTouchX = event.x
                        lastTouchY = event.y
                    }
                }
            }
            true
        }
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

    private fun setupControls() {
        // Load video
        findViewById<Button>(R.id.btnLoadVideo).setOnClickListener { requestVideoAccess() }

        // Capture screen
        findViewById<Button>(R.id.btnCapture).setOnClickListener { openCapture() }

        // Adjust mode toggle
        btnAdjust.setOnClickListener { toggleAdjustMode() }

        // Play/pause
        btnPlay.setOnClickListener { togglePlay() }

        // Seekbar
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

        // Step buttons with hold-to-scrub
        setupHoldStep(R.id.btnBack500, -500)
        setupHoldStep(R.id.btnBack250, -250)
        setupHoldStep(R.id.btnBack100, -100)
        setupHoldStep(R.id.btnFwd100, 100)
        setupHoldStep(R.id.btnFwd250, 250)
        setupHoldStep(R.id.btnFwd500, 500)

        // Speed buttons
        val speedMap = listOf(R.id.btnSpeed025 to 0.25f, R.id.btnSpeed05 to 0.5f, R.id.btnSpeed1 to 1.0f)
        speedMap.forEach { (id, speed) ->
            findViewById<Button>(id).setOnClickListener {
                player?.setPlaybackSpeed(speed)
                speedMap.forEach { (bid, _) ->
                    findViewById<Button>(bid).backgroundTintList =
                        android.content.res.ColorStateList.valueOf(Color.parseColor("#42A5F5"))
                }
                (it as Button).backgroundTintList =
                    android.content.res.ColorStateList.valueOf(Color.parseColor("#1E88E5"))
            }
        }

        // Save frame
        findViewById<Button>(R.id.btnSaveFrame).setOnClickListener { saveCurrentFrame() }

        // Trim buttons (in adjust mode)
        findViewById<Button>(R.id.btnSetStart).setOnClickListener {
            trimStartMs = player?.currentPosition ?: 0
            updateTrimInfo()
            Toast.makeText(this, "Start: ${trimStartMs}ms", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnSetEnd).setOnClickListener {
            trimEndMs = player?.currentPosition ?: videoDurationMs
            updateTrimInfo()
            Toast.makeText(this, "End: ${trimEndMs}ms", Toast.LENGTH_SHORT).show()
        }

        // Drawing tool buttons (in adjust mode)
        val panBtn = findViewById<Button>(R.id.btnToolPan)
        val drawBtn = findViewById<Button>(R.id.btnToolDraw)
        val lineBtn = findViewById<Button>(R.id.btnToolLine)

        fun highlightTool(active: Button) {
            listOf(panBtn, drawBtn, lineBtn).forEach {
                it.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#42A5F5"))
            }
            active.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#1E88E5"))
        }

        panBtn.setOnClickListener {
            drawingView.touchEnabled = false
            highlightTool(panBtn)
        }
        drawBtn.setOnClickListener {
            drawingView.setTool(DrawingOverlayView.Tool.FREE_DRAW)
            drawingView.touchEnabled = true
            highlightTool(drawBtn)
        }
        lineBtn.setOnClickListener {
            drawingView.setTool(DrawingOverlayView.Tool.LINE)
            drawingView.touchEnabled = true
            highlightTool(lineBtn)
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
    }

    private fun setupHoldStep(btnId: Int, stepMs: Long) {
        findViewById<Button>(btnId).setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    stepBy(stepMs)
                    val r = object : Runnable {
                        override fun run() {
                            stepBy(stepMs)
                            handler.postDelayed(this, 120)
                        }
                    }
                    scrubRunnable = r
                    handler.postDelayed(r, 350)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    scrubRunnable?.let { handler.removeCallbacks(it) }
                    scrubRunnable = null
                    true
                }
                else -> false
            }
        }
    }

    private fun toggleAdjustMode() {
        adjustModeActive = !adjustModeActive
        if (adjustModeActive) {
            normalControls.visibility = View.GONE
            adjustControls.visibility = View.VISIBLE
            btnAdjust.text = "✓ Done"
            btnAdjust.backgroundTintList =
                android.content.res.ColorStateList.valueOf(Color.parseColor("#4CAF50"))
            // Default to pan mode when entering adjust
            drawingView.touchEnabled = false
        } else {
            normalControls.visibility = View.VISIBLE
            adjustControls.visibility = View.GONE
            btnAdjust.text = "✏ Adjust"
            btnAdjust.backgroundTintList =
                android.content.res.ColorStateList.valueOf(Color.parseColor("#42A5F5"))
            drawingView.touchEnabled = false
        }
    }

    private fun openCapture() {
        val uri = videoUri ?: run {
            Toast.makeText(this, "Load a video first", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, CaptureActivity::class.java).apply {
            putExtra("videoUri", uri.toString())
            putExtra("trimStart", trimStartMs)
            putExtra("trimEnd", effectiveTrimEnd())
        }
        startActivity(intent)
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
        // Reset video transform
        videoContent.scaleX = 1f
        videoContent.scaleY = 1f
        videoContent.translationX = 0f
        videoContent.translationY = 0f
        videoScaleFactor = 1f
        drawingView.clear()
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
        val end = effectiveTrimEnd()
        if (end == 0L) return
        val newPos = (p.currentPosition + ms).coerceIn(trimStartMs, end)
        val wasPlaying = p.isPlaying
        p.seekTo(newPos)
        if (!wasPlaying) p.playWhenReady = false
        seekBar.progress = newPos.toInt()
        tvPosition.text = "${newPos}ms"
    }

    private fun effectiveTrimEnd(): Long =
        if (trimEndMs > trimStartMs) trimEndMs else videoDurationMs

    private fun updateTrimInfo() {
        val end = effectiveTrimEnd()
        tvTrimInfo.text = "Start: ${trimStartMs}ms  |  End: ${end}ms"
    }

    private fun saveCurrentFrame() {
        val uri = videoUri ?: run {
            Toast.makeText(this, "Load a video first", Toast.LENGTH_SHORT).show()
            return
        }
        val pos = player?.currentPosition ?: 0
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(applicationContext, uri)
                    val bmp = retriever.getFrameAtTime(pos * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
                    bmp?.let { saveFrameToGallery(it, pos) } != null
                } catch (_: Exception) { false }
                finally { retriever.release() }
            }
            Toast.makeText(this@MainActivity, if (saved) "Frame saved" else "Save failed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveFrameToGallery(bitmap: Bitmap, timestampMs: Long): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "framecaddy_${timestampMs}ms.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/FrameCaddy")
        }
        return try {
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            uri?.also {
                contentResolver.openOutputStream(it)?.use { s -> bitmap.compress(Bitmap.CompressFormat.JPEG, 95, s) }
            }
        } catch (_: Exception) { null }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
    }
}
