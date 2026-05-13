package ai.openclaw.app.node

import ai.openclaw.app.gateway.GatewaySession
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class UIAutomationHandler(
  private val appContext: Context,
) {
  companion object {
    @Volatile var instance: UIAutomationHandler? = null
    var accessibilityService: AccessibilityService? = null
    
    const val TAP_DURATION_MS = 100
    const val SWIPE_DURATION_MS = 300
    const val LONG_PRESS_DURATION_MS = 500
  }

  fun handleReadScreen(paramsJson: String?): GatewaySession.InvokeResult {
    val service = accessibilityService ?: return errorResult("READ_SCREEN_NO_ACCESSIBILITY", "Accessibility service not available")
    val rootNode = service.rootInActiveWindow ?: return errorResult("READ_SCREEN_NO_WINDOW", "No active window found")
    
    val elements = mutableListOf<Map<String, Any?>>()
    traverseNodes(rootNode, elements, 0, 5, true)
    rootNode.recycle()
    
    val json = buildJsonObject {
      put("focused", JsonPrimitive(rootNode.isFocused))
      put("packageName", JsonPrimitive(rootNode.packageName?.toString() ?: ""))
      put("totalElements", JsonPrimitive(elements.size))
      put("elements", kotlinx.serialization.json.JsonArray(elements.map { elem ->
        buildJsonObject {
          elem["index"]?.let { put("index", JsonPrimitive(it as Int)) }
          elem["text"]?.let { put("text", JsonPrimitive(it as String)) }
          elem["desc"]?.let { put("desc", JsonPrimitive(it as String)) }
          elem["clickable"]?.let { put("clickable", JsonPrimitive(it as Boolean)) }
          elem["bounds"]?.let { put("bounds", JsonPrimitive(it as String)) }
        }
      }))
    }
    return GatewaySession.InvokeResult.ok(json.toString())
  }

  private fun traverseNodes(node: android.view.accessibility.AccessibilityNodeInfo, elements: MutableList<Map<String, Any?>>, depth: Int, maxDepth: Int, includeBounds: Boolean) {
    if (depth > maxDepth) return
    val bounds = Rect()
    node.getBoundsInScreen(bounds)
    val element = mutableMapOf<String, Any?>()
    element["index"] = elements.size
    element["className"] = node.className?.toString()
    element["text"] = node.text?.toString()?.take(500)
    element["desc"] = node.contentDescription?.toString()?.take(500)
    element["clickable"] = node.isClickable
    element["scrollable"] = node.isScrollable
    element["editable"] = node.isEditable
    if (includeBounds) {
      element["bounds"] = "[${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}]"
      element["centerX"] = (bounds.left + bounds.right) / 2
      element["centerY"] = (bounds.top + bounds.bottom) / 2
    }
    elements.add(element)
    for (i in 0 until node.childCount) {
      node.getChild(i)?.let { child ->
        traverseNodes(child, elements, depth + 1, maxDepth, includeBounds)
        child.recycle()
      }
    }
  }

  fun handleClick(paramsJson: String?): GatewaySession.InvokeResult {
    val service = accessibilityService ?: return errorResult("CLICK_NO_ACCESSIBILITY", "Accessibility service not available")
    val params = parseParams(paramsJson)
    val x = params["x"]?.toIntOrNull()
    val y = params["y"]?.toIntOrNull()
    val text = params["text"]
    val longPress = params["longPress"]?.toBoolean() ?: false
    
    val point = if (x != null && y != null) {
      Pair(x, y)
    } else if (text != null) {
      findElementCenterByText(text) ?: return errorResult("CLICK_TEXT_NOT_FOUND", "Text '$text' not found")
    } else {
      return errorResult("CLICK_MISSING_PARAMS", "provide x/y or text")
    }
    
    val success = performClick(point.first, point.second, longPress)
    if (success) {
      return GatewaySession.InvokeResult.ok(buildJsonObject {
        put("success", JsonPrimitive(true))
        put("x", JsonPrimitive(point.first))
        put("y", JsonPrimitive(point.second))
      }.toString())
    }
    return errorResult("CLICK_FAILED", "gesture dispatch failed")
  }

  private fun findElementCenterByText(text: String): Pair<Int, Int>? {
    val rootNode = accessibilityService?.rootInActiveWindow ?: return null
    var result: Pair<Int, Int>? = null
    findElementRecursive(rootNode, text) { node ->
      val bounds = Rect()
      node.getBoundsInScreen(bounds)
      result = Pair((bounds.left + bounds.right) / 2, (bounds.top + bounds.bottom) / 2)
    }
    rootNode.recycle()
    return result
  }

  private fun findElementRecursive(node: android.view.accessibility.AccessibilityNodeInfo, text: String, callback: (android.view.accessibility.AccessibilityNodeInfo) -> Unit) {
    val nodeText = node.text?.toString() ?: ""
    val nodeDesc = node.contentDescription?.toString() ?: ""
    if (nodeText.contains(text, ignoreCase = true) || nodeDesc.contains(text, ignoreCase = true)) {
      callback(node)
      return
    }
    for (i in 0 until node.childCount) {
      node.getChild(i)?.let { child ->
        findElementRecursive(child, text, callback)
        child.recycle()
      }
    }
  }

  private fun performClick(x: Int, y: Int, longPress: Boolean): Boolean {
    val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
    val duration = if (longPress) LONG_PRESS_DURATION_MS else TAP_DURATION_MS
    val stroke = GestureDescription.StrokeDescription(path, 0, duration.toLong())
    val gesture = GestureDescription.Builder().addStroke(stroke).build()
    val callback = object : AccessibilityService.GestureResultCallback() {
      override fun onCompleted(gestureDescription: GestureDescription?) {}
      override fun onCancelled(gestureDescription: GestureDescription?) {}
    }
    return accessibilityService?.dispatchGesture(gesture, callback, null) ?: false
  }

  fun handleSwipe(paramsJson: String?): GatewaySession.InvokeResult {
    val service = accessibilityService ?: return errorResult("SWIPE_NO_ACCESSIBILITY", "Accessibility service not available")
    val params = parseParams(paramsJson)
    val startX = params["startX"]?.toIntOrNull() ?: params["x"]?.toIntOrNull() ?: return errorResult("SWIPE_MISSING_START", "need startX")
    val startY = params["startY"]?.toIntOrNull() ?: params["y"]?.toIntOrNull() ?: return errorResult("SWIPE_MISSING_START", "need startY")
    val direction = params["direction"]
    val endX = params["endX"]?.toIntOrNull()
    val endY = params["endY"]?.toIntOrNull()
    val duration = params["duration"]?.toIntOrNull() ?: SWIPE_DURATION_MS
    
    val (toX, toY) = if (endX != null && endY != null) {
      Pair(endX, endY)
    } else if (direction != null) {
      val dist = 500
      when (direction.lowercase()) {
        "up" -> Pair(startX, startY - dist)
        "down" -> Pair(startX, startY + dist)
        "left" -> Pair(startX - dist, startY)
        "right" -> Pair(startX + dist, startY)
        else -> return errorResult("SWIPE_INVALID_DIR", "direction must be up/down/left/right")
      }
    } else {
      return errorResult("SWIPE_MISSING_END", "need endX/endY or direction")
    }
    
    val path = Path().apply { moveTo(startX.toFloat(), startY.toFloat()); lineTo(toX.toFloat(), toY.toFloat()) }
    val stroke = GestureDescription.StrokeDescription(path, 0, duration.toLong())
    val gesture = GestureDescription.Builder().addStroke(stroke).build()
    val callback = object : AccessibilityService.GestureResultCallback() {
      override fun onCompleted(gestureDescription: GestureDescription?) {}
      override fun onCancelled(gestureDescription: GestureDescription?) {}
    }
    val success = accessibilityService?.dispatchGesture(gesture, callback, null) ?: false
    return if (success) {
      GatewaySession.InvokeResult.ok(buildJsonObject {
        put("success", JsonPrimitive(true))
        put("from", JsonPrimitive("[$startX,$startY]"))
        put("to", JsonPrimitive("[$toX,$toY]"))
      }.toString())
    } else {
      errorResult("SWIPE_FAILED", "gesture dispatch failed")
    }
  }

  fun handleInputText(paramsJson: String?): GatewaySession.InvokeResult {
    val params = parseParams(paramsJson)
    val text = params["text"] ?: return errorResult("INPUT_TEXT_MISSING", "text parameter required")
    
    val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    val oldClip = clipboard.primaryClip
    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("input", text))
    
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      accessibilityService?.performGlobalAction(android.view.accessibility.AccessibilityManager.ACTION_PASTE)
    }
    
    Thread.sleep(50)
    if (oldClip != null) clipboard.setPrimaryClip(oldClip)
    
    return GatewaySession.InvokeResult.ok(buildJsonObject {
      put("success", JsonPrimitive(true))
      put("textLength", JsonPrimitive(text.length))
    }.toString())
  }

  fun handleLaunchApp(paramsJson: String?): GatewaySession.InvokeResult {
    val params = parseParams(paramsJson)
    val packageName = params["packageName"] ?: return errorResult("LAUNCH_APP_MISSING_PACKAGE", "packageName required")
    
    return try {
      val launchIntent = appContext.packageManager.getLaunchIntentForPackage(packageName)
        ?: return errorResult("LAUNCH_APP_NOT_FOUND", "no launchable activity for $packageName")
      launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      appContext.startActivity(launchIntent)
      GatewaySession.InvokeResult.ok(buildJsonObject {
        put("success", JsonPrimitive(true))
        put("packageName", JsonPrimitive(packageName))
      }.toString())
    } catch (e: Exception) {
      errorResult("LAUNCH_APP_FAILED", e.message ?: "unknown error")
    }
  }

  fun handleScreenshot(paramsJson: String?): GatewaySession.InvokeResult {
    return errorResult("SCREENSHOT_NOT_IMPLEMENTED", "Screenshot requires MediaProjection - see FULL_PATCH_GUIDE.md")
  }

  fun handleFindElement(paramsJson: String?): GatewaySession.InvokeResult {
    val service = accessibilityService ?: return errorResult("FIND_ELEMENT_NO_ACCESSIBILITY", "Accessibility service not available")
    val params = parseParams(paramsJson)
    val text = params["text"]
    val description = params["description"]
    
    val rootNode = service.rootInActiveWindow ?: return errorResult("FIND_ELEMENT_NO_WINDOW", "No active window")
    var foundElement: Map<String, Any?>? = null
    var foundIndex = 0
    
    findElementRecursive(rootNode, text, description) { node, index ->
      val bounds = Rect()
      node.getBoundsInScreen(bounds)
      foundElement = mapOf(
        "found" to true,
        "index" to index,
        "text" to (node.text?.toString() ?: ""),
        "desc" to (node.contentDescription?.toString() ?: ""),
        "clickable" to node.isClickable,
        "bounds" to "[${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}]"
      )
    }
    rootNode.recycle()
    
    return if (foundElement != null) {
      val json = buildJsonObject {
        foundElement?.forEach { (k, v) ->
          when (v) {
            is Boolean -> put(k, JsonPrimitive(v))
            is Int -> put(k, JsonPrimitive(v))
            is String -> put(k, JsonPrimitive(v))
          }
        }
      }
      GatewaySession.InvokeResult.ok(json.toString())
    } else {
      errorResult("FIND_ELEMENT_NOT_FOUND", "no matching element")
    }
  }

  private fun findElementRecursive(node: android.view.accessibility.AccessibilityNodeInfo, text: String?, desc: String?, callback: (android.view.accessibility.AccessibilityNodeInfo, Int) -> Unit) {
    var index = 0
    for (i in 0 until node.childCount) {
      val child = node.getChild(i) ?: continue
      val nodeText = child.text?.toString() ?: ""
      val nodeDesc = child.contentDescription?.toString() ?: ""
      val matches = (text == null || nodeText.contains(text, ignoreCase = true) || nodeDesc.contains(text, ignoreCase = true)) &&
                   (desc == null || nodeDesc.contains(desc, ignoreCase = true))
      if (matches) callback(child, index++)
      findElementRecursive(child, text, desc) { c, idx -> callback(c, index + idx) }
      child.recycle()
    }
  }

  fun handleWaitForElement(paramsJson: String?): GatewaySession.InvokeResult {
    val service = accessibilityService ?: return errorResult("WAIT_FOR_ELEMENT_NO_ACCESS", "Accessibility service not available")
    val params = parseParams(paramsJson)
    val text = params["text"]
    val timeoutMs = params["timeoutMs"]?.toLongOrNull() ?: 5000
    val startTime = System.currentTimeMillis()
    
    while (System.currentTimeMillis() - startTime < timeoutMs) {
      val rootNode = service.rootInActiveWindow
      if (rootNode != null) {
        var found = false
        findElementRecursive(rootNode, text, null) { _, _ -> found = true }
        rootNode.recycle()
        if (found) return GatewaySession.InvokeResult.ok(buildJsonObject { put("found", JsonPrimitive(true)) }.toString())
      }
      Thread.sleep(200)
    }
    return errorResult("WAIT_FOR_ELEMENT_TIMEOUT", "element not found within ${timeoutMs}ms")
  }

  private fun parseParams(paramsJson: String?): Map<String, String?> {
    if (paramsJson.isNullOrBlank()) return emptyMap()
    return try {
      val element = kotlinx.serialization.json.Json.parseToJsonElement(paramsJson)
      if (element is kotlinx.serialization.json.JsonObject) {
        element.entries.associate { it.key to (it.value as? JsonPrimitive)?.content }
      } else emptyMap()
    } catch (_: Throwable) { emptyMap() }
  }

  private fun errorResult(code: String, message: String) = GatewaySession.InvokeResult.error(code, message)
}
