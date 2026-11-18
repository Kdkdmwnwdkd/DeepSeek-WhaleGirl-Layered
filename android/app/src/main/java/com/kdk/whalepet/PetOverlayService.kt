package com.kdk.whalepet

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast

class PetOverlayService : Service() {

    private lateinit var wm: WindowManager
    private var web: WebView? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val chan = NotificationChannel("pet", "鲸鱼娘桌宠", NotificationManager.IMPORTANCE_MIN)
        getSystemService(NotificationManager::class.java).createNotificationChannel(chan)
        val n: Notification = Notification.Builder(this, "pet")
            .setContentTitle("鲸鱼娘正在值班")
            .setSmallIcon(android.R.drawable.ic_menu_myplaces)
            .build()
        startForeground(11, n)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (web != null) return START_STICKY
        main.post { show() }
        return START_STICKY
    }

    @SuppressLint("ClickableViewAccessibility", "SetJavaScriptEnabled")
    private fun show() {
        val metrics = resources.displayMetrics
        val wPx = (metrics.widthPixels * 0.30f).toInt().coerceAtLeast(280)
        val hPx = (metrics.heightPixels * 0.66f).toInt().coerceAtLeast(560)

        val params = WindowManager.LayoutParams(
            wPx, hPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            android.graphics.PixelFormat.TRANSLUCENT
        )
        params.x = metrics.widthPixels - wPx
        params.y = (metrics.heightPixels * 0.22f).toInt()

        val v = WebView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setZOrderMediaOverlay(false)
            settings.javaScriptEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            setOnTouchListener(PetTouch(params))
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    evaluateJavascript(
                        "(function(){" +
                                "var s=document.createElement('style');" +
                                "s.textContent='.brand,.topright,.dock,.gear,.drawer{display:none!important};" +
                                "#boot{background:transparent!important};" +
                                "body{background:transparent!important;overflow:hidden};" +
                                "#rig{filter:none!important;height:100vh!important}';" +
                                "document.head.appendChild(s);" +
                                "setInterval(function(){try{if(typeof S!=='undefined'&&S.act==='idle'){" +
                                "var r=Math.random();if(r<0.5)setAct('blink');else if(r<0.68)setAct('sway');}}catch(e){}},9000);" +
                        "})();", null)
                }
            }
            loadUrl("file:///android_asset/pet.html")
        }
        web = v
        wm.addView(v, params)
    }

    private inner class PetTouch(private val params: WindowManager.LayoutParams) :
        View.OnTouchListener {

        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var moved = false

        private val gestures = GestureDetector(
            this@PetOverlayService,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    web?.evaluateJavascript("try{setAct('hello')}catch(e){}", null)
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    web?.evaluateJavascript(
                        "try{document.getElementById('hatBtn').click()}catch(e){}", null)
                    return true
                }

                override fun onLongPress(e: MotionEvent) {
                    Toast.makeText(this@PetOverlayService, "鲸鱼娘下班了", Toast.LENGTH_SHORT).show()
                    stopSelf()
                }
            })

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            gestures.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = params.x; startY = params.y
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt()
                    val dy = (e.rawY - downY).toInt()
                    if (Math.abs(dx) > 6 || Math.abs(dy) > 6) moved = true
                    params.x = startX + dx
                    params.y = startY + dy
                    try { wm.updateViewLayout(web, params) } catch (_: Exception) {}
                }
            }
            return true
        }
    }

    override fun onDestroy() {
        main.post {
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
