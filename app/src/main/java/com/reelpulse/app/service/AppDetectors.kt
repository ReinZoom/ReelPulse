package com.reelpulse.app.service

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Two different counting strategies live here, chosen per-app:
 *
 * 1. CONTENT-SIGNATURE apps (Instagram, YouTube Shorts): the app exposes
 *    identifiable text (author, video title) inside the reel player, so we
 *    can fingerprint "which reel is this" and count on fingerprint change.
 *
 * 2. GESTURE-COUNTED apps (Snapchat, TikTok, and anything added later
 *    without known resource IDs): the video surface exposes little or no
 *    accessible text, so instead we verify the current screen genuinely IS
 *    a full-screen vertical video pager, then count a debounced scroll
 *    event as "one reel", rather than trying (and failing) to diff content.
 */
interface ReelDetector {
    val packageName: String
    val gestureCounted: Boolean get() = false
    
    /** 
     * Returns a signature identifying "which reel is on screen".
     * Returns null if NOT on a reel surface at all.
     */
    fun currentReelSignature(root: AccessibilityNodeInfo): String? = null
    
    fun isFullScreenReelSurface(root: AccessibilityNodeInfo): Boolean = false

    /**
     * Returns true if a sub-view like comments or profile is currently covering the reel.
     */
    fun isSubViewActive(root: AccessibilityNodeInfo): Boolean = false
}

private fun AccessibilityNodeInfo.safeGetText(maxDepth: Int = 5): String? {
    val t = text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    if (t != null) return t
    val cd = contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    return cd ?: run {
        if (maxDepth <= 0) return null
        for (i in 0 until childCount) {
            val child = getChild(i) ?: continue
            val found = child.safeGetText(maxDepth - 1)
            @Suppress("DEPRECATION")
            child.recycle()
            if (found != null) return found
        }
        null
    }
}

private fun AccessibilityNodeInfo.findNodeById(id: String): AccessibilityNodeInfo? {
    val exact = findAccessibilityNodeInfosByViewId(id)
    val match = exact.firstOrNull()
    exact.filter { it != match }.forEach { 
        @Suppress("DEPRECATION")
        it.recycle() 
    }
    if (match != null) return match
    val shortId = id.substringAfterLast("/")
    return findNode { it.viewIdResourceName?.contains(shortId) == true }
}

private fun AccessibilityNodeInfo.findNode(
    maxDepth: Int = 20,
    predicate: (AccessibilityNodeInfo) -> Boolean,
): AccessibilityNodeInfo? {
    @Suppress("DEPRECATION")
    if (predicate(this)) return AccessibilityNodeInfo.obtain(this)
    if (maxDepth <= 0) return null
    for (i in 0 until childCount) {
        val child = getChild(i) ?: continue
        val found = child.findNode(maxDepth - 1, predicate)
        @Suppress("DEPRECATION")
        child.recycle()
        if (found != null) return found
    }
    return null
}

/**
 * Fingerprint a node tree by collecting only significant text.
 */
private fun AccessibilityNodeInfo.fingerprint(maxNodes: Int = 30): String {
    val sb = StringBuilder()
    collectFingerprintData(this, sb, IntArray(1) { maxNodes })
    return sb.toString().hashCode().toString()
}

private val UI_KEYWORDS = setOf(
    "comment", "like", "share", "reply", "follow", "views", "likes", 
    "more", "send", "save", "audio", "remix", "add a comment",
    "pause", "play", "video player", "reels", "shorts", "profile",
    "music", "original", "sound", "song", "camera", "filter", "effect"
)

private fun collectFingerprintData(node: AccessibilityNodeInfo, sb: StringBuilder, remaining: IntArray) {
    if (remaining[0] <= 0) return
    remaining[0]--
    
    val txt = node.text?.toString() ?: node.contentDescription?.toString()
    if (!txt.isNullOrBlank()) {
        val lower = txt.lowercase()
        val isUiLabel = UI_KEYWORDS.any { lower.contains(it) }
        
        // Filtering for Fingerprint: 
        // 1. Must be longer than 5 chars
        // 2. Must not contain UI keywords
        if (!isUiLabel && (txt.length > 5)) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            // Ignore nodes at the very top/bottom where nav bars usually live.
            if (bounds.width() > 30 && bounds.height() > 20 && 
                bounds.top > 200 && bounds.bottom < 2200) {
                sb.append(txt.take(15))
            }
        }
    }
    
    for (i in 0 until node.childCount) {
        val child = node.getChild(i) ?: continue
        collectFingerprintData(child, sb, remaining)
        @Suppress("DEPRECATION")
        child.recycle()
    }
}

object InstagramDetector : ReelDetector {
    override val packageName = "com.instagram.android"

    private val reelsContainerIds = setOf(
        "com.instagram.android:id/clips_viewer_view_pager",
        "com.instagram.android:id/clips_swipe_refresh_container",
        "com.instagram.android:id/reels_viewer_view_pager",
        "com.instagram.android:id/clips_author_container",
        "com.instagram.android:id/view_pager"
    )

    override fun currentReelSignature(root: AccessibilityNodeInfo): String? {
        if (isSubViewActive(root)) return null

        val container = reelsContainerIds.firstNotNullOfOrNull { id -> root.findNodeById(id) }
            ?: FallbackDetector.findFullScreenPager(root)
        
        if (container != null) {
            return try {
                // Try to find the username - it's the most stable anchor
                val authorNode = container.findNode { 
                    it.viewIdResourceName?.contains("username") == true ||
                    it.viewIdResourceName?.contains("profile_name") == true
                }
                val author = authorNode?.safeGetText()
                @Suppress("DEPRECATION")
                authorNode?.recycle()

                if (!author.isNullOrBlank()) {
                    "ig_user:$author"
                } else {
                    // Stricter fallback fingerprint
                    "ig_fp:${container.fingerprint()}"
                }
            } finally {
                container.recycle()
            }
        }
        return null
    }

    override fun isSubViewActive(root: AccessibilityNodeInfo): Boolean {
        return root.findNodeById("com.instagram.android:id/comment_thread_view_container") != null ||
               root.findNodeById("com.instagram.android:id/layout_comment_thread_child_container") != null ||
               root.findNodeById("com.instagram.android:id/profile_header_container") != null ||
               root.findNodeById("com.instagram.android:id/bottom_sheet_container") != null
    }
}

object YouTubeShortsDetector : ReelDetector {
    override val packageName = "com.google.android.youtube"

    private val shortsPlayerIds = setOf(
        "com.google.android.youtube:id/reel_player_page_container",
        "com.google.android.youtube:id/reel_recycler",
        "com.google.android.youtube:id/shorts_player_view",
        "com.google.android.youtube:id/reel_player_overlay_container"
    )

    override fun currentReelSignature(root: AccessibilityNodeInfo): String? {
        if (isSubViewActive(root)) return null

        val player = shortsPlayerIds.firstNotNullOfOrNull { id -> root.findNodeById(id) }
            ?: FallbackDetector.findFullScreenPager(root)
        
        if (player != null) {
            return try {
                // Try to find the channel name - most stable identifier
                val channelNode = player.findNode { 
                    it.viewIdResourceName?.contains("channel_name") == true ||
                    it.viewIdResourceName?.contains("channel_title") == true
                }
                val channel = channelNode?.safeGetText()
                @Suppress("DEPRECATION")
                channelNode?.recycle()

                if (!channel.isNullOrBlank()) {
                    "yt_channel:$channel"
                } else {
                    // Stricter fallback fingerprint
                    "yt_fp:${player.fingerprint()}"
                }
            } finally {
                player.recycle()
            }
        }
        return null
    }

    override fun isSubViewActive(root: AccessibilityNodeInfo): Boolean {
        return root.findNodeById("com.google.android.youtube:id/comments_container") != null ||
               root.findNodeById("com.google.android.youtube:id/panel_container") != null ||
               root.findNodeById("com.google.android.youtube:id/bottom_sheet_container") != null
    }
}

object SnapchatDetector : ReelDetector {
    override val packageName = "com.snapchat.android"
    override val gestureCounted = true
    override fun isFullScreenReelSurface(root: AccessibilityNodeInfo): Boolean {
        if (isSubViewActive(root)) return false
        val pager = FallbackDetector.findFullScreenPager(root)
        val found = pager != null
        @Suppress("DEPRECATION")
        pager?.recycle()
        return found
    }

    override fun isSubViewActive(root: AccessibilityNodeInfo): Boolean {
        return root.findNodeById("com.snapchat.android:id/spotlight_comments_container") != null ||
               root.findNodeById("com.snapchat.android:id/hova_header_container") != null
    }
}

object TikTokDetector : ReelDetector {
    override val packageName = "com.zhiliaoapp.musically"
    override val gestureCounted = true
    override fun isFullScreenReelSurface(root: AccessibilityNodeInfo): Boolean {
        if (isSubViewActive(root)) return false
        val pager = FallbackDetector.findFullScreenPager(root)
        val found = pager != null
        @Suppress("DEPRECATION")
        pager?.recycle()
        return found
    }

    override fun isSubViewActive(root: AccessibilityNodeInfo): Boolean {
        return root.findNodeById("com.zhiliaoapp.musically:id/comment_container") != null ||
               root.findNodeById("com.zhiliaoapp.musically:id/profile_container") != null ||
               root.findNodeById("com.zhiliaoapp.musically:id/action_bar_container") != null
    }
}

object FacebookDetector : ReelDetector {
    override val packageName = "com.facebook.katana"
    override val gestureCounted = true
    override fun isFullScreenReelSurface(root: AccessibilityNodeInfo): Boolean {
        if (isSubViewActive(root)) return false
        val pager = FallbackDetector.findFullScreenPager(root)
        val found = pager != null
        @Suppress("DEPRECATION")
        pager?.recycle()
        return found
    }

    override fun isSubViewActive(root: AccessibilityNodeInfo): Boolean {
        return root.findNodeById("com.facebook.katana:id/comments_container") != null ||
               root.findNodeById("com.facebook.katana:id/reels_viewer_action_bar") != null
    }
}

object FallbackDetector {

    fun findFullScreenPager(root: AccessibilityNodeInfo, depth: Int = 0): AccessibilityNodeInfo? {
        if (depth > 25) return null
        
        val name = nodeClassName(root)
        val isScrollable = (root.isScrollable || root.childCount > 1) && (
            name.contains("ViewPager") || 
            name.contains("RecyclerView") || 
            name.contains("Pager") || 
            name.contains("Carousel") ||
            name.contains("LayoutManager") ||
            name.contains("ListView")
        )

        if (isScrollable) {
            val rb = Rect().also { root.getBoundsInScreen(it) }
            val rootBounds = Rect().also { getRootBounds(root, it) }
            
            // A Reel pager must be near full screen width and significant height
            val coversWidth = rb.width() >= (rootBounds.width() * 0.75)
            val coversHeight = rb.height() >= (rootBounds.height() * 0.6)
            
            if (coversWidth && coversHeight) {
                @Suppress("DEPRECATION")
                return AccessibilityNodeInfo.obtain(root)
            }
        }
        
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findFullScreenPager(child, depth + 1)
            @Suppress("DEPRECATION")
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    private fun nodeClassName(node: AccessibilityNodeInfo): String = node.className?.toString() ?: ""
    
    private fun getRootBounds(node: AccessibilityNodeInfo, rect: Rect) {
        var curr = node
        while (true) {
            val p = curr.parent
            if (p == null) {
                curr.getBoundsInScreen(rect)
                return
            }
            if (curr != node) {
                @Suppress("DEPRECATION")
                curr.recycle()
            }
            curr = p
        }
    }
}

val ALL_DETECTORS: Map<String, ReelDetector> = listOf(
    InstagramDetector, YouTubeShortsDetector, SnapchatDetector, TikTokDetector, FacebookDetector
).associateBy { it.packageName }
