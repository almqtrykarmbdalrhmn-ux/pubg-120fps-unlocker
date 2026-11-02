package com.example.pubgunlocker

import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import rikka.shizuku.Shizuku
import java.io.File
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var applyButton: Button

    private companion object {
        const val REQUEST_CODE = 1001
        const val PUBG_PACKAGE = "com.tencent.ig"
        const val ASSET_NAME = "GamePatch"
        const val TARGET_DIR =
            "/storage/emulated/0/Android/data/com.tencent.ig/files/UE4Game/" +
            "ShadowTrackerExtra/ShadowTrackerExtra/Saved/Paks"
    }

    private val binderReceived = Shizuku.OnBinderReceivedListener { runOnUiThread { refreshState() } }
    private val binderDead = Shizuku.OnBinderDeadListener {
        runOnUiThread { setStatus("Shizuku service stopped. Start it and reopen the app.") }
    }
    private val permissionResult =
        Shizuku.OnRequestPermissionResultListener { code, result ->
            if (code == REQUEST_CODE) {
                runOnUiThread {
                    if (result == PackageManager.PERMISSION_GRANTED) refreshState()
                    else setStatus("Shizuku permission denied.")
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusText = findViewById(R.id.statusText)
        applyButton = findViewById(R.id.applyButton)

        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)

        applyButton.setOnClickListener { onApplyClicked() }
        refreshState()
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        Shizuku.removeRequestPermissionResultListener(permissionResult)
        super.onDestroy()
    }

    private fun setStatus(msg: String) { statusText.text = msg }

    private fun isPubgInstalled(): Boolean = try {
        packageManager.getPackageInfo(PUBG_PACKAGE, 0); true
    } catch (e: PackageManager.NameNotFoundException) { false }

    private fun refreshState() {
        when {
            !Shizuku.pingBinder() -> setStatus("Shizuku is not running. Start it first.")
            !isPubgInstalled() -> setStatus("PUBG Mobile (com.tencent.ig) not found.")
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED ->
                setStatus("Shizuku permission required. Tap Apply to grant.")
            else -> setStatus("Ready.")
        }
    }

    private fun onApplyClicked() {
        if (!Shizuku.pingBinder()) { toast("Shizuku is not running"); refreshState(); return }
        if (!isPubgInstalled()) { toast("PUBG Mobile not installed"); refreshState(); return }
        if (Shizuku.isPreV11()) { toast("Shizuku version too old"); return }

        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                setStatus("Permission was denied permanently. Grant it in Shizuku.")
            } else {
                Shizuku.requestPermission(REQUEST_CODE)
            }
            return
        }

        applyButton.isEnabled = false
        setStatus("Applying patch…")
        thread {
            val result = runCatching { installPatch() }
            runOnUiThread {
                applyButton.isEnabled = true
                result.onSuccess { ok ->
                    if (ok) { setStatus("Patch installed. Restart PUBG Mobile."); toast("Success") }
                }.onFailure {
                    setStatus("Error: ${it.message}")
                    toast("Failed: ${it.message}")
                }
            }
        }
    }

    /** Stage asset in external cache, then copy it to the game dir via Shizuku shell. */
    private fun installPatch(): Boolean {
        val staged = File(externalCacheDir ?: cacheDir, ASSET_NAME)
        assets.open(ASSET_NAME).use { input ->
            staged.outputStream().use { input.copyTo(it) }
        }
        staged.setReadable(true, false)

        val cmd = "mkdir -p '$TARGET_DIR' && cp -f '${staged.absolutePath}' '$TARGET_DIR/$ASSET_NAME'"
        val process = newShizukuProcess(arrayOf("sh", "-c", cmd))

        // Drain stderr on its own thread so the process never blocks on a full buffer.
        val err = StringBuilder()
        val errThread = thread { process.errorStream.bufferedReader().forEachLine { err.appendLine(it) } }
        process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        errThread.join()

        if (exit != 0) {
            runOnUiThread { setStatus("Copy failed (exit $exit)\n${err.toString().trim()}") }
        }
        return exit == 0
    }

    /** Shizuku.newProcess is private since API 13, so call it via reflection. */
    private fun newShizukuProcess(cmd: Array<String>): Process {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java, Array<String>::class.java, String::class.java
        )
        m.isAccessible = true
        return m.invoke(null, cmd, null, null) as Process
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
