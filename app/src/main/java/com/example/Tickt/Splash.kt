package com.example.Tickt

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.animation.LinearInterpolator
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class Splash : AppCompatActivity() {
    private val TOTAL_MS = 3600L
    private val FADE_IN_MS = 450L
    private val FADE_OUT_MS = 500L
    private val handler = Handler(Looper.getMainLooper())
    private var rotAnim: ObjectAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_splash)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val root = findViewById<android.view.View>(R.id.main)
        val bodyImage = findViewById<android.widget.ImageView>(R.id.bodyImage)

        root.alpha = 0f
        root.animate().alpha(1f).setDuration(FADE_IN_MS).start()

        rotAnim = ObjectAnimator.ofFloat(bodyImage, "rotation", 0f, 360f).apply {
            duration = 2400L
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            start()
        }

        handler.postDelayed({
            root.animate().alpha(0f).setDuration(FADE_OUT_MS).withEndAction {
                try { rotAnim?.cancel() } catch (_: Exception) {}
                startActivity(Intent(this@Splash, MainActivity::class.java))
                finish()
            }.start()
        }, TOTAL_MS)
    }

    override fun onDestroy() {
        try { rotAnim?.cancel() } catch (_: Exception) {}
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
