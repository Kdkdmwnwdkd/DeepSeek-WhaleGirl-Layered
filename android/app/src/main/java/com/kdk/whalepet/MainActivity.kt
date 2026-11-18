package com.kdk.whalepet

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var root: LinearLayout
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }
        status = TextView(this).apply {
            textSize = 15f
            setTextColor(0xFF1B2340.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 40)
        }
        val start = Button(this).apply {
            text = "把她放出来"
            setOnClickListener { startPet() }
        }
        val stop = Button(this).apply {
            text = "让她回去休息"
            setOnClickListener {
                stopService(Intent(this@MainActivity, PetOverlayService::class.java))
                refresh()
            }
        }
        val hint = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFF6B7699.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 56, 0, 0)
            text = "点她一下 = 打招呼\n连点两下 = 戴 / 摘帽子\n按住拖动 = 挪位置\n长按她 = 收工回去"
        }
        root.addView(status)
        root.addView(start)
        root.addView(stop)
        root.addView(hint)
        setContentView(root)
        askNotificationPermission()
    }

    /** Android 13 起前台服务的常驻通知也要授权，否则通知栏里看不到「值班」入口 */
    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val can = Settings.canDrawOverlays(this)
        status.text = if (can) "鲸鱼娘随时可以上班。"
        else "需要先允许「显示在其他应用上层」"
    }

    private fun startPet() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "先在接下来的页面里允许显示在上层", Toast.LENGTH_LONG).show()
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }
        startForegroundService(Intent(this, PetOverlayService::class.java))
        refresh()
        Toast.makeText(this, "鲸鱼娘上岗了，回到桌面看看她吧", Toast.LENGTH_SHORT).show()
    }
}
