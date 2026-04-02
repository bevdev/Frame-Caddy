package com.framecaddy.app

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CaptureActivity : AppCompatActivity() {

    private lateinit var tvTrimInfo: TextView
    private lateinit var etInterval: EditText
    private lateinit var btnExtract: Button
    private lateinit var btnExport: Button
    private lateinit var framesGrid: RecyclerView
    private lateinit var frameAdapter: FrameAdapter

    private val prefs by lazy { getSharedPreferences("framecaddy", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_capture)

        tvTrimInfo = findViewById(R.id.tvCaptureTrimInfo)
        etInterval = findViewById(R.id.etInterval)
        btnExtract = findViewById(R.id.btnExtract)
        btnExport = findViewById(R.id.btnExport)
        framesGrid = findViewById(R.id.framesGrid)

        val videoUriStr = intent.getStringExtra("videoUri")
        val trimStart = intent.getLongExtra("trimStart", 0L)
        val trimEnd = intent.getLongExtra("trimEnd", 0L)

        tvTrimInfo.text = "Trim: ${trimStart}ms  →  ${trimEnd}ms  (${trimEnd - trimStart}ms)"

        etInterval.setText(prefs.getString("interval", "100"))

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
            extractFrames(Uri.parse(videoUriStr), trimStart, trimEnd)
        }

        btnExport.setOnClickListener { exportSelected() }
    }

    private fun extractFrames(uri: Uri, start: Long, end: Long) {
        if (end <= start) {
            Toast.makeText(this, "Invalid trim range", Toast.LENGTH_SHORT).show()
            return
        }
        val intervalText = etInterval.text.toString().trim()
        val intervalMs = intervalText.toLongOrNull()?.coerceAtLeast(1) ?: 100L
        prefs.edit().putString("interval", intervalText).apply()

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
                            val scaled = Bitmap.createScaledBitmap(
                                it, 480, (it.height * 480f / it.width).toInt(), true
                            )
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
