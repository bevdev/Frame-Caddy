package com.framecaddy.app

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

class CaptureActivity : AppCompatActivity() {

    private lateinit var tvTrimInfo: TextView
    private lateinit var tvFramePreview: TextView
    private lateinit var tvProgress: TextView
    private lateinit var etInterval: EditText
    private lateinit var btnExtract: Button
    private lateinit var btnExport: Button
    private lateinit var framesGrid: RecyclerView
    private lateinit var frameAdapter: FrameAdapter

    private val prefs by lazy { getSharedPreferences("framecaddy", MODE_PRIVATE) }

    private var trimStart = 0L
    private var trimEnd = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_capture)

        tvTrimInfo = findViewById(R.id.tvCaptureTrimInfo)
        tvFramePreview = findViewById(R.id.tvFramePreview)
        tvProgress = findViewById(R.id.tvProgress)
        etInterval = findViewById(R.id.etInterval)
        btnExtract = findViewById(R.id.btnExtract)
        btnExport = findViewById(R.id.btnExport)
        framesGrid = findViewById(R.id.framesGrid)

        val videoUriStr = intent.getStringExtra("videoUri")
        trimStart = intent.getLongExtra("trimStart", 0L)
        trimEnd = intent.getLongExtra("trimEnd", 0L)

        tvTrimInfo.text = "Trim: ${trimStart}ms  →  ${trimEnd}ms  (${trimEnd - trimStart}ms)"

        etInterval.setText(prefs.getString("interval", "100"))
        updateFramePreview()

        etInterval.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = updateFramePreview()
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        frameAdapter = FrameAdapter(
            onFrameClick = { /* no player here */ },
            onSelectionChanged = { count ->
                btnExport.isEnabled = count > 0
                btnExport.text = if (count > 0) "Export Selected ($count)" else "Export Selected"
                btnExport.backgroundTintList = android.content.res.ColorStateList.valueOf(
                    if (count > 0) Color.parseColor("#4CAF50") else Color.parseColor("#9E9E9E")
                )
            }
        )
        framesGrid.layoutManager = GridLayoutManager(this, 2)
        framesGrid.adapter = frameAdapter
        framesGrid.isNestedScrollingEnabled = false

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }

        btnExtract.setOnClickListener {
            if (videoUriStr == null) {
                Toast.makeText(this, "No video URI", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (trimEnd <= trimStart) {
                Toast.makeText(this, "Invalid trim range — set Start and End on the player first", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            extractFrames(Uri.parse(videoUriStr))
        }

        btnExport.setOnClickListener { exportSelected() }
    }

    private fun updateFramePreview() {
        val intervalMs = etInterval.text.toString().trim().toLongOrNull()?.coerceAtLeast(1) ?: 100L
        val range = trimEnd - trimStart
        if (range <= 0) {
            tvFramePreview.text = "Set trim range on the player first"
            return
        }
        val count = (range / intervalMs) + 1
        tvFramePreview.text = "~$count frames from ${trimStart}ms → ${trimEnd}ms"
    }

    private fun extractFrames(uri: Uri) {
        val intervalText = etInterval.text.toString().trim()
        val intervalMs = intervalText.toLongOrNull()?.coerceAtLeast(1) ?: 100L
        prefs.edit().putString("interval", intervalText).apply()

        val timestamps = generateSequence(trimStart) { it + intervalMs }
            .takeWhile { it <= trimEnd }
            .toList()

        if (timestamps.isEmpty()) {
            Toast.makeText(this, "No frames to extract", Toast.LENGTH_SHORT).show()
            return
        }

        btnExtract.isEnabled = false
        btnExtract.text = "Extracting…"
        tvProgress.visibility = View.VISIBLE
        tvProgress.text = "Starting…"
        frameAdapter.clearFrames()

        val total = timestamps.size
        val done = AtomicInteger(0)

        lifecycleScope.launch {
            val frames = coroutineScope {
                val semaphore = Semaphore(4) // max 4 concurrent decoders
                timestamps.map { t ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            val retriever = MediaMetadataRetriever()
                            try {
                                retriever.setDataSource(applicationContext, uri)
                                val bmp = retriever.getFrameAtTime(
                                    t * 1000L,
                                    MediaMetadataRetriever.OPTION_CLOSEST
                                )
                                bmp?.let {
                                    val w = 480
                                    val h = (it.height * w.toFloat() / it.width).toInt()
                                    val scaled = Bitmap.createScaledBitmap(it, w, h, true)
                                    if (scaled !== it) it.recycle()
                                    val n = done.incrementAndGet()
                                    withContext(Dispatchers.Main) {
                                        tvProgress.text = "Extracting… $n / $total"
                                    }
                                    FrameItem(scaled, t - trimStart)
                                }
                            } catch (_: Exception) { null }
                            finally { retriever.release() }
                        }
                    }
                }.awaitAll().filterNotNull().sortedBy { it.timestampMs }
            }

            frameAdapter.setFrames(frames)
            btnExtract.isEnabled = true
            btnExtract.text = "Extract Frames"
            tvProgress.visibility = View.GONE

            if (frames.isEmpty())
                Toast.makeText(this@CaptureActivity, "No frames extracted", Toast.LENGTH_SHORT).show()
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
}
