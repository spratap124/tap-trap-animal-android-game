package com.spratap.taptrapanimal

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.text.TextPaint
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import kotlin.math.abs
import kotlin.random.Random

class GameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        const val INITIAL_SPEED = 0.9f
        const val LEVEL_SPEED_BONUS = 0.12f
        const val LEVEL_TRAP_BONUS = 0.22f
        const val CATCHES_PER_LEVEL = 10
    }

    enum class TapResult { TRAP_HIT, FOOD_HIT, MISS }

    data class Particle(
        var x: Float,
        var y: Float,
        var vx: Float,
        var vy: Float,
        var life: Int
    )

    // Emojis
    var animalEmoji = "🐭"
    var foodEmoji = "🧀"
    private val trapEmoji = "🪤"

    // Game state (all positions in pixels)
    var pos = 0f
    var dir = 1f
    var trapPos = 0f
    var trapDir = 1f
    var speed = INITIAL_SPEED
    var trapMoveSpeed = 0f
    var foodX = 0f
    var gameRunning = false

    private val density get() = context.resources.displayMetrics.density

    // Collision thresholds (in dp, converted to px on use)
    private val trapThresholdDp = 55f
    private val foodThresholdDp = 44f
    private val emojiSizeDp = 52f

    val particles = mutableListOf<Particle>()

    private val animalPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
    }
    private val foodPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
    }
    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFD54F.toInt()
    }
    private val bgPaint = Paint()
    private val trackRectF = RectF()
    private val clipPath = Path()

    private var bgColors = intArrayOf(0xFF3A3A3A.toInt(), 0xFF2C2C2C.toInt())

    // Callback for game events
    var onTrapHit: (() -> Unit)? = null
    var onFoodHit: (() -> Unit)? = null
    var onMiss: (() -> Unit)? = null

    private val choreographer = Choreographer.getInstance()
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (gameRunning) {
                update()
                invalidate()
                choreographer.postFrameCallback(this)
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateBgShader()
        val radius = h * 0.28f
        trackRectF.set(0f, 0f, w.toFloat(), h.toFloat())
        clipPath.reset()
        clipPath.addRoundRect(trackRectF, radius, radius, Path.Direction.CW)
        animalPaint.textSize = h * 0.38f
        foodPaint.textSize = h * 0.30f
        if (foodX == 0f) spawnFood()
        if (trapPos == 0f) centerTrap()
    }

    private fun updateBgShader() {
        if (height == 0) return
        bgPaint.shader = LinearGradient(
            0f, 0f, 0f, height.toFloat(),
            bgColors, null, Shader.TileMode.CLAMP
        )
        trackRectF.set(0f, 0f, width.toFloat(), height.toFloat())
    }

    fun startLoop() {
        if (!gameRunning) {
            gameRunning = true
            choreographer.postFrameCallback(frameCallback)
        }
    }

    fun stopLoop() {
        gameRunning = false
        choreographer.removeFrameCallback(frameCallback)
        invalidate()
    }

    private fun maxPos(): Float = (width - emojiSizeDp * density).coerceAtLeast(0f)

    private fun update() {
        val max = maxPos()
        pos += speed * density * dir
        if (pos > max) { pos = max; dir = -1f }
        if (pos < 0f) { pos = 0f; dir = 1f }

        if (trapMoveSpeed > 0f) {
            trapPos += trapDir * trapMoveSpeed * density
            if (trapPos >= max) { trapPos = max; trapDir = -1f }
            if (trapPos <= 0f) { trapPos = 0f; trapDir = 1f }
        }

        val iter = particles.iterator()
        while (iter.hasNext()) {
            val p = iter.next()
            p.x += p.vx
            p.y += p.vy
            p.life--
            if (p.life <= 0) iter.remove()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val radius = height * 0.28f
        canvas.save()
        canvas.clipPath(clipPath)

        // Rounded background
        canvas.drawRoundRect(trackRectF, radius, radius, bgPaint)

        val cy = height * 0.66f

        // Food
        canvas.drawText(foodEmoji, foodX, cy, foodPaint)
        // Trap
        canvas.drawText(trapEmoji, trapPos, cy, animalPaint)
        // Animal (drawn last, on top) — flip horizontally when moving right
        if (dir > 0f) {
            canvas.save()
            val ew = animalPaint.measureText(animalEmoji)
            canvas.translate(pos + ew, 0f)
            canvas.scale(-1f, 1f)
            canvas.drawText(animalEmoji, 0f, cy, animalPaint)
            canvas.restore()
        } else {
            canvas.drawText(animalEmoji, pos, cy, animalPaint)
        }

        // Particles
        val ps = 3f * density
        for (p in particles) {
            canvas.drawRect(p.x, p.y, p.x + ps, p.y + ps, particlePaint)
        }

        canvas.restore()
    }

    fun checkTap(): TapResult {
        val trapPx = trapThresholdDp * density
        val foodPx = foodThresholdDp * density
        return when {
            abs(pos - trapPos) < trapPx -> TapResult.TRAP_HIT
            abs(pos - foodX) < foodPx -> TapResult.FOOD_HIT
            else -> TapResult.MISS
        }
    }

    fun spawnFood() {
        val max = maxPos()
        if (max <= 0f) return
        var x: Float
        val minDistPx = 70f * density
        do {
            x = Random.nextFloat() * max
        } while (abs(x - trapPos) < minDistPx)
        foodX = x
        invalidate()
    }

    fun centerTrap() {
        trapPos = maxPos() / 2f
        trapDir = 1f
        invalidate()
    }

    fun sparkle(x: Float = width / 2f, y: Float = height / 2f) {
        repeat(12) {
            particles.add(
                Particle(
                    x, y,
                    (Random.nextFloat() - 0.5f) * 5f * density,
                    (Random.nextFloat() - 0.5f) * 5f * density,
                    25
                )
            )
        }
    }

    fun setTrackBackground(vararg colors: Int) {
        bgColors = colors
        updateBgShader()
        invalidate()
    }

    fun applyLevel(level: Int) {
        speed = INITIAL_SPEED + (level - 1) * LEVEL_SPEED_BONUS
        trapMoveSpeed = if (level <= 1) 0f else (level - 1) * LEVEL_TRAP_BONUS
    }

    fun resetSpeed() {
        speed = INITIAL_SPEED
        trapMoveSpeed = 0f
    }
}
