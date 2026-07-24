package com.naomi.assistant

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Naomi's on-screen agent (an AccessibilityService).
 *
 * Two jobs:
 *  1. Auto-tap WhatsApp's "Send" button after Naomi opens a pre-filled chat (legacy behavior).
 *  2. General screen control: read whatever app is in the foreground and tap / scroll / type
 *     by voice — e.g. "place the order", "select auto", "tap checkout". The Activity sends
 *     itself to the background first so the target app is on top, then calls [perform].
 *
 * The user enables this once in Settings → Accessibility → Naomi. Fragile by nature: it depends
 * on apps labelling their buttons; payment/secure screens are intentionally invisible to it.
 */
class WhatsAppSender : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!armed) return
        if (event?.packageName != WHATSAPP_PKG) return
        if (System.currentTimeMillis() - armedAt > ARM_WINDOW_MS) { armed = false; return }

        val root = rootInActiveWindow ?: return
        val send = findSendButton(root) ?: return
        send.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        armed = false // sent — disarm so we never click again unexpectedly
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    // --- general screen control ------------------------------------------------

    private fun doAction(action: String, target: String) {
        when (action) {
            "tap" -> tapByText(target.lowercase().split('|'), attemptsLeft = 4)
            "scroll" -> {
                val root = rootInActiveWindow ?: return
                scroll(root, forward = !target.contains("up"))
            }
            "type" -> typeText(target)
        }
    }

    /** Find a clickable node whose label contains any of [targets]; scroll + retry if not found. */
    private fun tapByText(targets: List<String>, attemptsLeft: Int) {
        val root = rootInActiveWindow
        val node = root?.let { findClickableByText(it, targets) }
        if (node != null) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return
        }
        if (attemptsLeft > 0) {
            root?.let { scroll(it, forward = true) }
            handler.postDelayed({ tapByText(targets, attemptsLeft - 1) }, 550)
        }
    }

    private fun findClickableByText(root: AccessibilityNodeInfo, targets: List<String>): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val label = ((node.text?.toString() ?: "") + " " +
                (node.contentDescription?.toString() ?: "")).lowercase().trim()
            if (label.isNotBlank() && node.isVisibleToUser && targets.any { label.contains(it) }) {
                clickableAncestor(node)?.let { return it }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return null
    }

    /** The node itself if clickable, otherwise the nearest clickable parent (a few hops up). */
    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = node
        var hops = 0
        while (cur != null && hops < 6) {
            if (cur.isClickable && cur.isEnabled) return cur
            cur = cur.parent
            hops++
        }
        return null
    }

    private fun scroll(root: AccessibilityNodeInfo, forward: Boolean): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isScrollable) {
                val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                if (node.performAction(action)) return true
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return false
    }

    private fun typeText(text: String) {
        val root = rootInActiveWindow ?: return
        val field = findEditable(root) ?: return
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isEditable && node.isVisibleToUser) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return null
    }

    // --- WhatsApp send (legacy) ------------------------------------------------

    private fun findSendButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        root.findAccessibilityNodeInfosByViewId("$WHATSAPP_PKG:id/send")
            .firstOrNull { it.isEnabled }
            ?.let { return it }

        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val desc = node.contentDescription?.toString()?.trim()?.lowercase()
            if (desc == "send" && node.isEnabled) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return null
    }

    companion object {
        private const val WHATSAPP_PKG = "com.whatsapp"
        private const val ARM_WINDOW_MS = 8000L

        @Volatile var armed = false
        @Volatile private var armedAt = 0L

        /** Live service instance, set once the user has enabled the service. */
        @Volatile private var instance: WhatsAppSender? = null

        /** True if the accessibility service is running (enabled by the user). */
        val isEnabled: Boolean get() = instance != null

        /** Call right before opening a WhatsApp chat we want auto-sent. */
        fun arm() { armed = true; armedAt = System.currentTimeMillis() }

        /**
         * Perform a screen action on the current foreground app.
         * action = "tap" (target = pipe-separated label candidates), "scroll" (target up/down),
         * or "type" (target = text to enter).
         */
        fun perform(action: String, target: String) {
            instance?.doAction(action, target)
        }
    }
}
