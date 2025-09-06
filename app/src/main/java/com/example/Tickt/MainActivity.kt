package com.example.Tickt

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import kotlin.math.*

class MainActivity : AppCompatActivity() {
    private lateinit var body: ConstraintLayout
    private lateinit var hand: View
    private lateinit var secTv: TextView
    private lateinit var minTv: TextView
    private lateinit var hrsTv: TextView
    private lateinit var modeToggle: Button
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var resetBtn: Button
    private lateinit var soundPool: SoundPool
    private var tickWindSoundId: Int = 0
    private lateinit var vibrator: Vibrator
    private var lastUnitValue = -1

    private enum class Mode { SEC, MIN, HRS }
    private var mode = Mode.SEC

    private enum class TimerState { IDLE, RUNNING, PAUSED }
    private var timerState = TimerState.IDLE

    private val MAX_HOURS = 99

    private var secValue = 0
    private var minValue = 0
    private var hrValue = 0

    private var secAcc = 0.0
    private var minAcc = 0.0
    private var hrAcc = 0.0

    private var prevAngle = 0.0
    private var touchStarted = false
    private var grabOffset = 0.0
    private var radiusScale = 0.75

    private var remainingSeconds = 0
    private val handler = Handler(Looper.getMainLooper())
    private var tickRunnable: Runnable? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        body = findViewById(R.id.body)
        hand = findViewById(R.id.hand)
        secTv = findViewById(R.id.sec)
        minTv = findViewById(R.id.min)
        hrsTv = findViewById(R.id.hrs)
        modeToggle = findViewById(R.id.modeToggle)
        startBtn = findViewById(R.id.strt)
        stopBtn = findViewById(R.id.stop)
        resetBtn = findViewById(R.id.reset)

        updateToggleDrawable()

        run {
            val audioAttr = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            soundPool = SoundPool.Builder().setMaxStreams(1).setAudioAttributes(audioAttr).build()
            tickWindSoundId = soundPool.load(this, R.raw.tick, 1)
            vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        hand.isClickable = false
        hand.isFocusable = false

        body.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                placeHandAtAngle(0.0)
                prevAngle = currentHandAngle()
                remainingSeconds = 0
                secValue = 0; minValue = 0; hrValue = 0
                secAcc = 0.0; minAcc = 0.0; hrAcc = 0.0
                updateDisplays()
                body.viewTreeObserver.removeOnGlobalLayoutListener(this)
            }
        })

        startBtn.applyPressAlpha()
        stopBtn.applyPressAlpha(pressedAlpha = 0.55f)
        resetBtn.applyPressAlpha()

        modeToggle.setOnClickListener {
            if (timerState != TimerState.IDLE) return@setOnClickListener
            mode = when (mode) {
                Mode.SEC -> Mode.MIN
                Mode.MIN -> Mode.HRS
                Mode.HRS -> Mode.SEC
            }
            updateToggleDrawable()
            when (mode) {
                Mode.SEC -> { secValue = 0; secAcc = 0.0 }
                Mode.MIN -> { minValue = 0; minAcc = 0.0 }
                Mode.HRS -> { hrValue = 0; hrAcc = 0.0 }
            }
            placeHandAtAngle(0.0)
            prevAngle = currentHandAngle()
            grabOffset = 0.0
            updateDisplays()
        }

        startBtn.setOnClickListener {
            if (timerState == TimerState.RUNNING) return@setOnClickListener
            if (timerState == TimerState.PAUSED) {
                val i = Intent(this, TimerService::class.java).apply { action = TimerService.ACTION_RESUME }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
            } else {
                val total = hrValue * 3600 + minValue * 60 + secValue
                if (total <= 0) return@setOnClickListener
                val i = Intent(this, TimerService::class.java).apply {
                    action = TimerService.ACTION_START
                    putExtra(TimerService.EXTRA_TOTAL_SECONDS, total)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
            }
            timerState = TimerState.RUNNING
            modeToggle.isEnabled = false
            startBtn.isEnabled = false
        }

        stopBtn.setOnClickListener {
            if (timerState != TimerState.RUNNING) return@setOnClickListener
            val i = Intent(this, TimerService::class.java).apply { action = TimerService.ACTION_PAUSE }
            startService(i)
            timerState = TimerState.PAUSED
            modeToggle.isEnabled = false
            startBtn.isEnabled = true
        }

        resetBtn.setOnClickListener {
            val i = Intent(this, TimerService::class.java).apply { action = TimerService.ACTION_STOP }
            startService(i)
            timerState = TimerState.IDLE
            modeToggle.isEnabled = true
            startBtn.isEnabled = true
            placeHandAtAngle(0.0)
            prevAngle = currentHandAngle()
            secValue = 0; minValue = 0; hrValue = 0
            updateDisplays()
        }

        body.setOnTouchListener { _, event ->
            if (timerState != TimerState.IDLE) return@setOnTouchListener true
            val x = event.x.toDouble()
            val y = event.y.toDouble()
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val handCenterX = hand.x + hand.width / 2.0
                    val handCenterY = hand.y + hand.height / 2.0
                    val dist = hypot(x - handCenterX, y - handCenterY)
                    val TOUCH_THRESHOLD = max(hand.width, hand.height) * 1.6
                    if (dist > TOUCH_THRESHOLD) {
                        touchStarted = false
                        return@setOnTouchListener true
                    }
                    val touchAngle = angleFromTouch(x, y)
                    val handAngle = currentHandAngle()
                    grabOffset = normalizeAngle(handAngle - touchAngle)
                    prevAngle = handAngle
                    touchStarted = true
                    lastUnitValue = when (mode) {
                        Mode.SEC -> secValue
                        Mode.MIN -> minValue
                        Mode.HRS -> hrValue
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!touchStarted) return@setOnTouchListener true
                    val touchAngle = angleFromTouch(x, y)
                    var desired = touchAngle + grabOffset
                    desired = ((desired % 360.0) + 360.0) % 360.0
                    var delta = desired - prevAngle
                    while (delta <= -180) delta += 360
                    while (delta > 180) delta -= 360
                    val degreesPerUnit = if (mode == Mode.HRS) 30.0 else 6.0
                    val deltaUnits = delta / degreesPerUnit
                    val overallZero = (secValue == 0 && minValue == 0 && hrValue == 0)
                    if (deltaUnits < 0 && overallZero) {
                        prevAngle = desired
                        placeHandAtAngle(desired)
                        return@setOnTouchListener true
                    }
                    when (mode) {
                        Mode.SEC -> {
                            secAcc += deltaUnits
                            var newSec = secAcc.roundToInt()
                            if (newSec < 0) newSec = 0
                            if (newSec > 59) newSec = 59
                            secValue = newSec
                            secAcc = secValue.toDouble()
                            val angle = (secValue % 60) * degreesPerUnit
                            placeHandAtAngle(angle)
                        }
                        Mode.MIN -> {
                            minAcc += deltaUnits
                            var newMin = minAcc.roundToInt()
                            if (newMin < 0) newMin = 0
                            if (newMin > 59) newMin = 59
                            minValue = newMin
                            minAcc = minValue.toDouble()
                            val angle = (minValue % 60) * degreesPerUnit
                            placeHandAtAngle(angle)
                        }
                        Mode.HRS -> {
                            hrAcc += deltaUnits
                            var newHr = hrAcc.roundToInt()
                            if (newHr < 0) newHr = 0
                            if (newHr > MAX_HOURS) newHr = MAX_HOURS
                            hrValue = newHr
                            hrAcc = hrValue.toDouble()
                            val angle = (hrValue % 12) * degreesPerUnit
                            placeHandAtAngle(angle)
                        }
                    }
                    val currentUnitValue = when (mode) {
                        Mode.SEC -> secValue
                        Mode.MIN -> minValue
                        Mode.HRS -> hrValue
                    }
                    if (currentUnitValue != lastUnitValue) {
                        try {
                            soundPool.play(tickWindSoundId, 1f, 1f, 1, 0, 1f)
                        } catch (ignored: Exception) {}
                        if (vibrator.hasVibrator()) {
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                vibrator.vibrate(VibrationEffect.createOneShot(12, 40))
                            } else {
                                @Suppress("DEPRECATION")
                                vibrator.vibrate(12)
                            }
                        }
                        lastUnitValue = currentUnitValue
                    }
                    prevAngle = currentHandAngle()
                    updateDisplays()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    touchStarted = false
                }
            }
            true
        }

    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(TimerService.ACTION_TICK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(tickReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(tickReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            unregisterReceiver(tickReceiver)
        } catch (ex: IllegalArgumentException) {
        }
    }

    override fun onDestroy() {
        try { soundPool.release() } catch (ignored: Exception) {}
        super.onDestroy()
    }

    private fun updateToggleDrawable() {
        when (mode) {
            Mode.SEC -> modeToggle.background = getDrawable(R.drawable.sec_toggle)
            Mode.MIN -> modeToggle.background = getDrawable(R.drawable.min_toggle)
            Mode.HRS -> modeToggle.background = getDrawable(R.drawable.hr_toggle)
        }
    }

    private fun View.applyPressAlpha(pressedAlpha: Float = 0.6f, releaseAnimMs: Long = 120) {
        setOnTouchListener { v, ev ->
            if (!v.isEnabled) return@setOnTouchListener true
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { v.alpha = pressedAlpha }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.animate().alpha(1f).setDuration(releaseAnimMs).start() }
            }
            false
        }
    }

    private fun updateDisplaysFromRemaining() {
        val total = remainingSeconds
        val seconds = total % 60
        val minutes = (total / 60) % 60
        val hours = total / 3600
        secTv.text = String.format("%02d", seconds)
        minTv.text = String.format("%02d", minutes)
        hrsTv.text = String.format("%02d", hours)
    }

    private fun angleFromTouch(x: Double, y: Double): Double {
        val cx = body.width / 2.0
        val cy = body.height / 2.0
        val dx = x - cx
        val dy = y - cy
        if (abs(dx) < 1e-6 && abs(dy) < 1e-6) return 0.0
        val raw = Math.toDegrees(atan2(dy, dx))
        return ((raw + 90.0 + 360.0) % 360.0)
    }

    private fun currentHandAngle(): Double {
        val cx = body.width / 2.0
        val cy = body.height / 2.0
        val handCenterX = hand.x + hand.width / 2.0
        val handCenterY = hand.y + hand.height / 2.0
        val dx = handCenterX - cx
        val dy = handCenterY - cy
        if (abs(dx) < 1e-6 && abs(dy) < 1e-6) return 0.0
        val raw = Math.toDegrees(atan2(dy, dx))
        return ((raw + 90.0 + 360.0) % 360.0)
    }

    private fun placeHandAtAngle(angleDegrees: Double) {
        val angleRad = Math.toRadians(angleDegrees - 90.0)
        val cx = body.width / 2.0
        val cy = body.height / 2.0
        val handW = hand.width.toDouble()
        val handH = hand.height.toDouble()
        val outerRadius = min(body.width, body.height) / 2.0
        val radius = (outerRadius * radiusScale) - max(handW, handH) / 2.0
        val cxOffset = radius * cos(angleRad)
        val cyOffset = radius * sin(angleRad)
        val targetCenterX = cx + cxOffset
        val targetCenterY = cy + cyOffset
        val left = (targetCenterX - handW / 2.0).toFloat()
        val top = (targetCenterY - handH / 2.0).toFloat()
        hand.x = left
        hand.y = top
    }

    private fun normalizeAngle(a: Double): Double {
        var v = a
        while (v <= -180) v += 360
        while (v > 180) v -= 360
        return v
    }

    private fun updateDisplays() {
        secTv.text = String.format("%02d", secValue)
        minTv.text = String.format("%02d", minValue)
        hrsTv.text = String.format("%02d", hrValue)
    }

    private val tickReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            if (intent.action == TimerService.ACTION_TICK) {
                val remaining = intent.getIntExtra(TimerService.EXTRA_REMAINING, 0)
                val sec = remaining % 60
                val min = (remaining / 60) % 60
                val hrs = remaining / 3600
                secTv.text = String.format("%02d", sec)
                minTv.text = String.format("%02d", min)
                hrsTv.text = String.format("%02d", hrs)
                val angle = (remaining * 6.0) % 360.0
                placeHandAtAngle(angle)
                prevAngle = angle
            }
        }
    }
}

