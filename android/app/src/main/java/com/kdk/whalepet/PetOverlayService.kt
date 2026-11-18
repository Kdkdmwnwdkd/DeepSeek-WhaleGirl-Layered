package com.kdk.whalepet

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import kotlin.math.abs

/**
 * 悬浮桌宠。
 *
 * 窗口拆成两层：
 *   render —— WebView，FLAG_NOT_TOUCHABLE，整块穿透，只负责画
 *   touch  —— 透明 View，只盖住头部到腰这一段，负责点击 / 拖动 / 长按
 *
 * 这样人物周围的空白区域是真正穿透的（不是靠 View 返回 false —— 那不会把
 * 事件交给下层应用），屏幕上大部分图标都能正常点到。要连身体下面也穿透，
 * 只能走 TYPE_ACCESSIBILITY_OVERLAY，需要用户单独开无障碍权限，不在默认路径里。
 */
class PetOverlayService : Service() {

    private companion object {
        const val RATIO = 1024f / 1980f      // 素材宽高比，窗口必须照这个走
        const val MASK_COLS = 48
        const val MASK_ROWS = 93
        const val PREFS = "pet"
        const val TAG_NOTIFICATION = "pet"

        // 触控层相对渲染层的比例（头部 → 腰部）
        const val TOUCH_X = 0.18f
        const val TOUCH_Y = 0.02f
        const val TOUCH_W = 0.64f
        const val TOUCH_H = 0.72f
    }

    private lateinit var wm: WindowManager
    private var web: WebView? = null
    private var touchView: View? = null
    private var renderParams: WindowManager.LayoutParams? = null
    private var touchParams: WindowManager.LayoutParams? = null

    private var winW = 0
    private var winH = 0
    private var touchOffX = 0
    private var touchOffY = 0

    private var mask: BooleanArray? = null
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private val main = Handler(Looper.getMainLooper())

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> web?.evaluateJavascript("freeze()", null)
                Intent.ACTION_SCREEN_ON -> web?.evaluateJavascript("unfreeze()", null)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val chan = NotificationChannel(TAG_NOTIFICATION, "鲸鱼娘桌宠", NotificationManager.IMPORTANCE_MIN)
        getSystemService(NotificationManager::class.java).createNotificationChannel(chan)
        val n: Notification = Notification.Builder(this, TAG_NOTIFICATION)
            .setContentTitle("鲸鱼娘正在值班")
            .setSmallIcon(android.R.drawable.ic_menu_myplaces)
            .build()
        startForeground(11, n)
        registerScreenReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (web == null) main.post { show() }
        // 用户长按下班后不该被系统重新拉起
        return START_NOT_STICKY
    }

    /** 按素材比例算窗口，宽度放不下时以宽度为准反推高度 */
    private fun computeSize(): IntArray {
        val m = resources.displayMetrics
        var h = (m.heightPixels * 0.66f).toInt()
        var w = (h * RATIO).toInt()
        val maxW = (m.widthPixels * 0.92f).toInt()
        if (w > maxW) {
            w = maxW
            h = (w / RATIO).toInt()
        }
        return intArrayOf(w, h)
    }

    @SuppressLint("ClickableViewAccessibility", "SetJavaScriptEnabled")
    private fun show() {
        if (web != null) return
        val m = resources.displayMetrics
        val size = computeSize()
        winW = size[0]
        winH = size[1]

        renderParams = WindowManager.LayoutParams(
            winW, winH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("x", m.widthPixels - winW)
            y = prefs.getInt("y", (m.heightPixels * 0.22f).toInt())
        }
        clampPosition()

        touchOffX = (winW * TOUCH_X).toInt()
        touchOffY = (winH * TOUCH_Y).toInt()
        touchParams = WindowManager.LayoutParams(
            (winW * TOUCH_W).toInt(), (winH * TOUCH_H).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = renderParams!!.x + touchOffX
            y = renderParams!!.y + touchOffY
        }

        val v = WebView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            settings.javaScriptEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    requestMask(view, 0)
                }
            }
            loadUrl("file:///android_asset/pet.html")
        }

        val tv = View(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setOnTouchListener(PetTouch())
        }

        web = v
        touchView = tv
        wm.addView(v, renderParams)
        wm.addView(tv, touchParams)
    }

    /**
     * 让网页把当前姿态合成一张 48×93 的 alpha 掩码回来。
     * 触控层只用它来区分「点在人物身上」还是「点在人物边上的空白」，
     * 避免点到裙摆旁边的空气也触发动作。
     */
    private fun requestMask(view: WebView?, attempt: Int) {
        if (view == null || attempt > 6) return
        view.evaluateJavascript("hitMask($MASK_COLS,$MASK_ROWS)") { raw ->
            val s = raw?.trim()?.trim('"') ?: ""
            val ones = s.count { it == '1' }
            // 页面刚 finish 时贴图可能还没解码完，掩码会是空的；等一会儿再问
            if (s.length < MASK_COLS * MASK_ROWS || ones < 50) {
                main.postDelayed({ requestMask(view, attempt + 1) }, 800)
                return@evaluateJavascript
            }
            val arr = BooleanArray(MASK_COLS * MASK_ROWS)
            for (i in arr.indices) arr[i] = s[i] == '1'
            mask = arr
        }
    }

    private fun isOnPet(localX: Float, localY: Float): Boolean {
        val m = mask ?: return true          // 掩码还没到，先按命中处理，别把点击丢了
        if (winW <= 0 || winH <= 0) return true
        val gx = (touchOffX + localX) / winW.toFloat()
        val gy = (touchOffY + localY) / winH.toFloat()
        val col = (gx * MASK_COLS).toInt()
        val row = (gy * MASK_ROWS).toInt()
        for (dc in -1..1) {
            for (dr in -1..1) {
                val c = col + dc
                val r = row + dr
                if (c in 0 until MASK_COLS && r in 0 until MASK_ROWS && m[r * MASK_COLS + c]) return true
            }
        }
        return false
    }

    private inner class PetTouch : View.OnTouchListener {

        private var downRawX = 0f
        private var downRawY = 0f
        private var startX = 0
        private var startY = 0
        private var moved = false

        private val gestures = GestureDetector(
            this@PetOverlayService,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    if (moved) return true
                    if (!isOnPet(e.x, e.y)) return true     // 点空处不放动作
                    web?.evaluateJavascript("try{setAct('hello')}catch(e){}", null)
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    if (!isOnPet(e.x, e.y)) return true
                    web?.evaluateJavascript("try{document.getElementById('hatBtn').click()}catch(e){}", null)
                    return true
                }

                override fun onLongPress(e: MotionEvent) {
                    if (!isOnPet(e.x, e.y)) return
                    Toast.makeText(this@PetOverlayService, "鲸鱼娘下班了", Toast.LENGTH_SHORT).show()
                    stopSelf()
                }
            })

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            gestures.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = e.rawX
                    downRawY = e.rawY
                    startX = renderParams?.x ?: 0
                    startY = renderParams?.y ?: 0
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downRawX).toInt()
                    val dy = (e.rawY - downRawY).toInt()
                    if (abs(dx) > 8 || abs(dy) > 8) moved = true
                    if (moved) moveTo(startX + dx, startY + dy)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (moved) savePosition()
                }
            }
            return true
        }
    }

    private fun moveTo(x: Int, y: Int) {
        val r = renderParams ?: return
        val t = touchParams ?: return
        r.x = x
        r.y = y
        clampPosition()
        t.x = r.x + touchOffX
        t.y = r.y + touchOffY
        try {
            wm.updateViewLayout(web, r)
            wm.updateViewLayout(touchView, t)
        } catch (_: Exception) {
        }
    }

    /** 至少留三分之一在屏内，别让她被拖到看不见的地方 */
    private fun clampPosition() {
        val p = renderParams ?: return
        val m = resources.displayMetrics
        val minVisibleX = (winW * 0.34f).toInt().coerceAtLeast(48)
        val minVisibleY = (winH * 0.2f).toInt().coerceAtLeast(48)
        p.x = p.x.coerceIn(minVisibleX - winW, m.widthPixels - minVisibleX)
        p.y = p.y.coerceIn(0, m.heightPixels - minVisibleY)
    }

    private fun savePosition() {
        val r = renderParams ?: return
        prefs.edit().putInt("x", r.x).putInt("y", r.y).apply()
    }

    private fun registerScreenReceiver() {
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, f, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(screenReceiver, f)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val r = renderParams ?: return
        val t = touchParams ?: return
        val size = computeSize()
        winW = size[0]
        winH = size[1]
        r.width = winW
        r.height = winH
        touchOffX = (winW * TOUCH_X).toInt()
        touchOffY = (winH * TOUCH_Y).toInt()
        t.width = (winW * TOUCH_W).toInt()
        t.height = (winH * TOUCH_H).toInt()
        clampPosition()
        t.x = r.x + touchOffX
        t.y = r.y + touchOffY
        try {
            wm.updateViewLayout(web, r)
            wm.updateViewLayout(touchView, t)
        } catch (_: Exception) {
        }
        web?.evaluateJavascript("redraw()", null)
    }

    override fun onDestroy() {
        main.post {
            try {
                unregisterReceiver(screenReceiver)
            } catch (_: Exception) {
            }
            touchView?.let { v -> (v.parent as? ViewGroup)?.removeView(v) }
            touchView = null
            web?.let {
                it.loadUrl("about:blank")
                (it.parent as? ViewGroup)?.removeView(it)
                it.destroy()
            }
            web = null
        }
        super.onDestroy()
    }
}
