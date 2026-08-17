package com.urufile.uruplayer.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.bumptech.glide.Glide
import com.urufile.uruplayer.R
import com.urufile.uruplayer.data.model.Layout
import com.urufile.uruplayer.data.model.MediaItem
import com.urufile.uruplayer.data.model.Region
import com.urufile.uruplayer.data.prefs.PrefsManager
import com.urufile.uruplayer.manager.FileManager
import com.urufile.uruplayer.parser.LayoutParser
import com.urufile.uruplayer.parser.ScheduleParser
import com.urufile.uruplayer.ui.SettingsActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class PlayerActivity : AppCompatActivity() {

    companion object {
        const val ACTION_SCHEDULE_UPDATED = "com.urufile.uruplayer.SCHEDULE_UPDATED"
        private const val TAG = "PlayerActivity"
    }

    private var tapCount = 0
    private var lastTapTime = 0L

    private lateinit var playerContainer: FrameLayout
    private lateinit var prefs: PrefsManager
    private lateinit var fileManager: FileManager

    private val regionJobs = mutableListOf<Job>()
    private val exoPlayers = mutableListOf<ExoPlayer>()
    private val webViews = mutableListOf<WebView>()

    // Actual screen dimensions (set once in renderLayout, reused by Glide and WebView)
    private var screenW = 0
    private var screenH = 0

    // Scale factors for mapping layout coords → screen coords
    private var scaleX = 1f
    private var scaleY = 1f

    // Receiver to refresh layout when Worker finishes download
    private val scheduleUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.i(TAG, "Schedule update received. Reloading layout.")
            loadCurrentLayout()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUI()
        setContentView(R.layout.activity_player)

        prefs = PrefsManager(this)
        fileManager = FileManager(this)
        playerContainer = findViewById(R.id.playerContainer)

        setupTapDetector()
        loadCurrentLayout()

        // Handle double back press to exit safely (prevents accidental single-press exit, allows admin/user to close the app)
        var lastBackPressedTime = 0L
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastBackPressedTime < 2000) {
                    finishAffinity() // Exit application completely
                } else {
                    lastBackPressedTime = currentTime
                    Toast.makeText(this@PlayerActivity, "Presione ATRÁS de nuevo para salir", Toast.LENGTH_SHORT).show()
                }
            }
        })

        // Listen for schedule updates from the Worker
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(scheduleUpdateReceiver, IntentFilter(ACTION_SCHEDULE_UPDATED))
    }

    private fun setupTapDetector() {
        val tapDetector = findViewById<View>(R.id.tapDetector)
        tapDetector.setOnClickListener { handleTap() }
    }

    private fun handleTap() {
        val now = System.currentTimeMillis()
        if (now - lastTapTime > 1000) {
            tapCount = 0
        }
        lastTapTime = now
        tapCount++

        if (tapCount >= 5) {
            tapCount = 0
            openSettings()
        }
    }

    private fun loadCurrentLayout() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 1. Get schedule XML from prefs (persisted by the Worker)
                val scheduleXml = prefs.lastScheduleXml
                if (scheduleXml.isBlank()) {
                    Log.w(TAG, "No schedule XML cached yet. Checking for connection errors.")
                    val syncError = prefs.lastSyncError
                    val statusMsg = when {
                        syncError.isNotBlank() ->
                            // Show the exact message from the CMS (wrong key, pending, etc.)
                            "$syncError\n\n⚙️ Configura en: 5 toques arriba-izquierda"
                        !prefs.isAuthorized ->
                            "⏳ Pantalla registrada.\n\nIngresa al panel Xibo CMS y autoriza este dispositivo."
                        else ->
                            "⏳ Esperando datos del CMS...\nDescargando contenido programado."
                    }
                    withContext(Dispatchers.Main) {
                        showStatus(statusMsg)
                    }
                    return@launch
                }

                val scheduleParser = ScheduleParser()
                val scheduleItems = scheduleParser.parse(scheduleXml)

                // 2. Determine current layout ID
                val layoutId = scheduleParser.getCurrentLayoutId(scheduleItems)
                if (layoutId == -1) {
                    Log.w(TAG, "No active layout found in schedule.")
                    withContext(Dispatchers.Main) {
                        if (!prefs.isAuthorized) {
                            showStatus("Waiting for CMS Approval...\nPlease authorize this display in the CMS.")
                        } else {
                            showStatus("No active content scheduled for this time.")
                        }
                    }
                    return@launch
                }

                // 3. Find the layout XML file in the media dir
                var layoutFile = fileManager.getLocalFile("$layoutId.xlf")
                if (!layoutFile.exists()) {
                    layoutFile = fileManager.getLocalFile("$layoutId")
                }
                
                if (!layoutFile.exists()) {
                    Log.e(TAG, "Layout file not found: ${layoutFile.absolutePath}")
                    withContext(Dispatchers.Main) {
                        showStatus("Downloading content... ($layoutId)")
                    }
                    return@launch
                }

                // 4. Parse layout
                val layoutParser = LayoutParser()
                val layout = layoutParser.parseFile(layoutFile) ?: return@launch

                prefs.currentLayoutId = layoutId

                // 5. Render layout on main thread
                withContext(Dispatchers.Main) {
                    renderLayout(layout)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load layout: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    showStatus("Error: ${e.message}")
                }
            }
        }
    }

    private fun showStatus(message: String) {
        // Remove existing overlay if any
        playerContainer.findViewById<View>(R.id.statusOverlay)?.let { playerContainer.removeView(it) }

        val tv = TextView(this).apply {
            id = R.id.statusOverlay
            text = message
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = android.view.Gravity.CENTER
            setBackgroundColor(Color.parseColor("#88000000"))
            setPadding(32, 32, 32, 32)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.CENTER
            }
        }
        playerContainer.addView(tv)
    }

    private fun hideStatus() {
        playerContainer.findViewById<View>(R.id.statusOverlay)?.let { playerContainer.removeView(it) }
    }

    private fun renderLayout(layout: Layout) {
        hideStatus()
        clearCurrentLayout()

        // Calculate scale to map layout design pixels → actual screen pixels.
        // Use currentWindowMetrics on API 30+ to get the true display bounds,
        // which avoids overscan/inset issues that affect displayMetrics.widthPixels.
        // Assign to class properties so Glide and WebView use the same source of truth
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            screenW = bounds.width()
            screenH = bounds.height()
        } else {
            screenW = resources.displayMetrics.widthPixels
            screenH = resources.displayMetrics.heightPixels
        }

        val overscanPercent = prefs.overscanPadding / 100f
        val padX = (screenW * overscanPercent).toInt()
        val padY = (screenH * overscanPercent).toInt()

        val usableW = screenW - (padX * 2)
        val usableH = screenH - (padY * 2)

        val layoutW = if (layout.width > 0) layout.width else 1920
        val layoutH = if (layout.height > 0) layout.height else 1080

        scaleX = usableW.toFloat() / layoutW.toFloat()
        scaleY = usableH.toFloat() / layoutH.toFloat()
        Log.d(TAG, "Screen: ${screenW}x${screenH}, Usable: ${usableW}x${usableH}, Layout: ${layoutW}x${layoutH}, scaleX=$scaleX scaleY=$scaleY")

        playerContainer.setPadding(padX, padY, padX, padY)

        try {
            if (layout.bgColor.equals("transparent", ignoreCase = true)) {
                playerContainer.setBackgroundColor(Color.TRANSPARENT)
            } else {
                playerContainer.setBackgroundColor(Color.parseColor(layout.bgColor))
            }
        } catch (e: Exception) {
            playerContainer.setBackgroundColor(Color.BLACK)
        }

        // Sort regions by zIndex ascending so background layers draw underneath foreground layers
        val sortedRegions = layout.regions.sortedBy { it.zIndex }
        for (region in sortedRegions) {
            val regionView = FrameLayout(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    (region.width * scaleX).toInt(),
                    (region.height * scaleY).toInt()
                ).apply {
                    leftMargin = (region.left * scaleX).toInt()
                    topMargin = (region.top * scaleY).toInt()
                }
            }
            playerContainer.addView(regionView)

            // Start region media cycle
            val job = lifecycleScope.launch {
                playRegionMedia(regionView, region.mediaItems)
            }
            regionJobs.add(job)
        }

        // Re-add tap detector on top
        addTapDetector()
    }

    private suspend fun awaitPlayerEnd(player: ExoPlayer) = suspendCancellableCoroutine<Unit> { continuation ->
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    player.removeListener(this)
                    if (continuation.isActive) continuation.resume(Unit)
                }
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                player.removeListener(this)
                if (continuation.isActive) continuation.resume(Unit)
            }
        }
        player.addListener(listener)
        if (player.playbackState == Player.STATE_ENDED || (player.playbackState == Player.STATE_IDLE && player.mediaItemCount == 0)) {
            player.removeListener(listener)
            if (continuation.isActive) continuation.resume(Unit)
        }
        continuation.invokeOnCancellation {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                player.removeListener(listener)
            } else {
                Handler(Looper.getMainLooper()).post {
                    try {
                        player.removeListener(listener)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to remove player listener on cancellation: ${e.message}")
                    }
                }
            }
        }
    }

    private suspend fun CoroutineScope.playRegionMedia(container: FrameLayout, mediaItems: List<MediaItem>) {
        if (mediaItems.isEmpty()) return

        var currentIndex = 0
        while (isActive) {
            val media = mediaItems[currentIndex]

            // Release previous resources before replacing
            withContext(Dispatchers.Main) {
                releaseContainerResources(container)
                container.removeAllViews()

                val view = createMediaView(media)
                if (view != null) {
                    container.addView(view)
                }
            }

            // Wait for duration
            if (media.duration > 0) {
                delay(media.duration * 1000L)
            } else {
                if (media.type.lowercase() == "video") {
                    val player = withContext(Dispatchers.Main) {
                        val playerView = container.getChildAt(0) as? PlayerView
                        playerView?.player as? ExoPlayer
                    }
                    if (player != null) {
                        awaitPlayerEnd(player)
                    } else {
                        delay(10000L)
                    }
                } else {
                    delay(10000L) // Default 10s for non-video elements if duration is 0
                }
            }

            currentIndex = (currentIndex + 1) % mediaItems.size
        }
    }

    /**
     * Release ExoPlayers and WebViews inside a container before clearing it.
     * This prevents memory leaks from orphaned players and JS engines.
     */
    private fun releaseContainerResources(container: FrameLayout) {
        val children = (0 until container.childCount).map { container.getChildAt(it) }
        for (child in children) {
            when (child) {
                is PlayerView -> {
                    (child.player as? ExoPlayer)?.let { ep ->
                        ep.stop()
                        ep.release()
                        exoPlayers.remove(ep)
                    }
                    child.player = null
                }
                is WebView -> {
                    container.removeView(child) // Safe to detach now since we're using a static copy
                    child.stopLoading()
                    child.loadUrl("about:blank")
                    child.destroy()
                    webViews.remove(child)
                }
                is ImageView -> {
                    Glide.with(container.context).clear(child)
                }
            }
        }
    }

    private fun createMediaView(media: MediaItem): View? {
        // Fallback file resolver: tries uri first, then mediaId
        val getFile = {
            var f = media.uri?.let { fileManager.getLocalFile(it) }
            if (f == null || !f.exists()) {
                f = fileManager.getLocalFile(media.mediaId)
            }
            if (f != null && f.exists()) f else null
        }

        return when (media.type.lowercase()) {
            "image" -> {
                val imageView = ImageView(this).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                    scaleType = ImageView.ScaleType.FIT_XY
                }
                val file = getFile()
                if (file != null) {
                    Glide.with(this)
                        .load(file)
                        .override(screenW, screenH)
                        .into(imageView)
                }
                imageView
            }
            "video" -> {
                val playerView = PlayerView(this).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
                }
                val exoPlayer = ExoPlayer.Builder(this).build()
                exoPlayers.add(exoPlayer)
                playerView.player = exoPlayer

                val file = getFile()
                if (file != null) {
                    val exoMediaItem = ExoMediaItem.fromUri(Uri.fromFile(file))
                    exoPlayer.setMediaItem(exoMediaItem)
                    exoPlayer.repeatMode = if (media.duration > 0) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                    exoPlayer.prepare()
                    exoPlayer.play()
                }
                playerView
            }
            "webpage", "html" -> {
                val webView = WebView(this).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                    setBackgroundColor(Color.TRANSPARENT)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true

                    // Counteract screen density so CSS pixels map 1:1 to design coordinates
                    val density = resources.displayMetrics.density
                    val initialScale = (100 * scaleX / density).toInt().coerceAtLeast(1)
                    setInitialScale(initialScale)

                    webViewClient = WebViewClient()
                }
                webViews.add(webView)

                if (media.rawHtml != null) {
                    webView.loadDataWithBaseURL(null, media.rawHtml, "text/html", "UTF-8", null)
                } else {
                    val file = getFile()
                    if (file != null) {
                        webView.loadUrl(Uri.fromFile(file).toString())
                    } else if (media.uri != null) {
                        webView.loadUrl(media.uri)
                    }
                }
                webView
            }
            "text", "ticker" -> {
                val textView = TextView(this).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                    setTextColor(Color.WHITE)
                    textSize = 24f
                    gravity = android.view.Gravity.CENTER
                }
                val text = media.rawHtml?.replace(Regex("<.*?>"), "") ?: media.options["text"] ?: ""
                textView.text = text
                textView
            }
            else -> null
        }
    }

    private fun clearCurrentLayout() {
        regionJobs.forEach { it.cancel() }
        regionJobs.clear()

        // Release all ExoPlayers
        exoPlayers.forEach {
            it.stop()
            it.release()
        }
        exoPlayers.clear()

        // Destroy all WebViews (detaching from their parent first)
        webViews.forEach { webView ->
            (webView.parent as? android.view.ViewGroup)?.removeView(webView)
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.destroy()
        }
        webViews.clear()

        playerContainer.removeAllViews()
    }

    private fun addTapDetector() {
        val tapDetector = View(this).apply {
            id = R.id.tapDetector
            layoutParams = FrameLayout.LayoutParams(100.dpToPx(), 100.dpToPx()).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.START
            }
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { handleTap() }
            elevation = 100f // Keep on top of all regions
        }
        playerContainer.addView(tapDetector)
    }

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()

    override fun onResume() {
        super.onResume()
        hideSystemUI()
        exoPlayers.forEach { it.play() }
    }

    override fun onPause() {
        super.onPause()
        exoPlayers.forEach { it.pause() }
    }

    override fun onDestroy() {
        LocalBroadcastManager.getInstance(this)
            .unregisterReceiver(scheduleUpdateReceiver)
        clearCurrentLayout()
        super.onDestroy()
    }

    private fun hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // API 30+ (Android 11+): usa la API moderna WindowInsetsController.
            // Cubre el caso exacto del TV box con kernel 4.14.187 / Android 11.
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            // API 21–29: fallback al método legado
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
            )
        }
    }

    private fun openSettings() {
        prefs.isSettingsOpen = true
        Toast.makeText(this, "Opening Settings...", Toast.LENGTH_SHORT).show()
        startActivity(Intent(this, SettingsActivity::class.java))
    }
}
