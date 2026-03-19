package com.spratap.taptrapanimal

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.Dialog
import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.*
import android.speech.tts.TextToSpeech
import android.view.View
import android.view.Window
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.spratap.taptrapanimal.databinding.ActivityMainBinding
import java.util.*

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    // --- Animal data (matches index.html) ---
    private val animals = listOf(
        Animal("🐭", "🧀", 0, "Rat"),
        Animal("🐶", "🦴", 0, "Dog"),
        Animal("🐱", "🐟", 0, "Cat"),
        Animal("🐰", "🥕", 0, "Rabbit"),
        Animal("🐼", "🎋", 500, "Panda"),
        Animal("🐵", "🍌", 750, "Monkey"),
        Animal("🐮", "🌽", 1000, "Cow"),
        Animal("🐷", "🍎", 1250, "Pig"),
        Animal("🐔", "🌾", 1500, "Chicken"),
        Animal("🐸", "🪰", 1750, "Frog"),
        Animal("🐻", "🍯", 2000, "Bear"),
        Animal("🐯", "🍖", 2500, "Tiger"),
        Animal("🦁", "🍖", 3000, "Lion"),
        Animal("🐨", "🌿", 3500, "Koala"),
        Animal("🦊", "🍗", 4000, "Fox"),
        Animal("🦄", "🍭", 5000, "Unicorn"),
        Animal("🐧", "🐟", 6000, "Penguin"),
        Animal("🐙", "🦐", 7000, "Octopus"),
        Animal("🦉", "🪱", 8000, "Owl"),
        Animal("🦈", "🐠", 9000, "Shark"),
        Animal("🦒", "🍃", 10000, "Giraffe"),
        Animal("🦓", "🌿", 11000, "Zebra"),
        Animal("🐘", "🍉", 12500, "Elephant"),
        Animal("🐲", "🔥", 15000, "Dragon")
    )

    // --- Game state ---
    private var score = 0
    private var combo = 1
    private var coins = 0
    private var high = 0
    private var catches = 0
    private var level = 1
    private var bestLevel = 1
    private var lastMilestone = 0
    private var trapStreak = 0
    private var lastTrapHitAt = 0L
    private val COMBO_WINDOW_MS = 900L
    private val MILESTONE_INTERVAL = 50

    // ── Power-up state ────────────────────────────────────────────────────────
    private var shieldCount = 0
    private var bonusCoinsActive = false
    private var bonusEndTime = 0L
    private var bonusCatchCount = 0
    private val BONUS_SPAWN_EVERY = 7      // spawn 🌟 every N trap catches
    private val BONUS_DURATION_MS = 8000L  // double-coins lasts 8 s
    private val BONUS_EXPIRE_MS   = 3000L  // star disappears if not collected in 3 s
    private val MAX_SHIELDS = 3
    private val SHIELD_EVERY_N_CATCHES = 20

    // ── Combo decay ───────────────────────────────────────────────────────────
    private val COMBO_DECAY_DELAY_MS = 3500L
    private val comboDecayRunnable:  Runnable = Runnable { decayCombo() }
    private val bonusExpireRunnable: Runnable = Runnable {
        binding.gameView.clearBonus()
    }
    private var unlockedIndices = mutableListOf(0, 1, 2, 3)
    private var currentAnimalIdx = 0
    private var soundOn = true

    // --- TTS ---
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    // --- Sound ---
    private var soundPool: SoundPool? = null
    private var soundTrap = 0
    private var soundGameOver = 0
    private var soundMilestone = 0
    private var soundFood = 0

    // --- Vibration ---
    private lateinit var vibrator: Vibrator

    // --- View binding ---
    private lateinit var binding: ActivityMainBinding

    // --- Handler ---
    private val mainHandler = Handler(Looper.getMainLooper())
    private val toastHideRunnable = Runnable {
        binding.toastText.visibility = View.GONE
    }

    // --- Shared prefs ---
    private val prefs by lazy { getSharedPreferences("TapTrapAnimal", Context.MODE_PRIVATE) }

    // --- Track background themes ---
    private val themes = listOf(
        intArrayOf(0xFF3A3A3A.toInt(), 0xFF2C2C2C.toInt()),
        intArrayOf(0xFF1E3C72.toInt(), 0xFF2A5298.toInt()),
        intArrayOf(0xFF355C7D.toInt(), 0xFF6C5B7B.toInt()),
        intArrayOf(0xFF134E5E.toInt(), 0xFF71B280.toInt()),
        intArrayOf(0xFF3B0F2A.toInt(), 0xFF7B1B5A.toInt()),
        intArrayOf(0xFF2B3A0F.toInt(), 0xFF6F8A1A.toInt())
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Edge-to-edge
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        )

        loadPrefs()
        initSound()
        initVibrator()
        tts = TextToSpeech(this, this)

        setupGame()
        setupListeners()
        updateUI()
        updateLevelUI()
        updateSoundBtn()
    }

    // ── Persistence ──────────────────────────────────────────────────────────

    private fun loadPrefs() {
        coins = prefs.getInt("coins", 0)
        high = prefs.getInt("high", 0)
        bestLevel = prefs.getInt("bestLevel", 1)
        soundOn = prefs.getBoolean("soundOn", true)
        val saved = prefs.getString("unlocked", "0,1,2,3") ?: "0,1,2,3"
        unlockedIndices = saved.split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .toMutableList()
            .ifEmpty { mutableListOf(0, 1, 2, 3) }
    }

    private fun savePrefs() {
        prefs.edit()
            .putInt("coins", coins)
            .putInt("high", high)
            .putInt("bestLevel", bestLevel)
            .putBoolean("soundOn", soundOn)
            .putString("unlocked", unlockedIndices.joinToString(","))
            .apply()
    }

    // ── Sound ─────────────────────────────────────────────────────────────────

    private fun initSound() {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        soundPool = SoundPool.Builder()
            .setMaxStreams(4)
            .setAudioAttributes(attrs)
            .build()
        soundTrap = loadRawSound("sound_trap")
        soundGameOver = loadRawSound("sound_game_over")
        soundMilestone = loadRawSound("sound_milestone")
        soundFood = loadRawSound("sound_food")
    }

    private fun loadRawSound(name: String): Int {
        val resId = resources.getIdentifier(name, "raw", packageName)
        return if (resId != 0) soundPool?.load(this, resId, 1) ?: 0 else 0
    }

    private fun playSound(soundId: Int) {
        if (!soundOn || soundId == 0) return
        soundPool?.play(soundId, 1f, 1f, 1, 0, 1f)
    }

    // ── Vibration ─────────────────────────────────────────────────────────────

    private fun initVibrator() {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
    }

    private fun vibrateStrong() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, 30, 40, 30), -1)
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(longArrayOf(0, 30, 40, 30), -1)
        }
    }

    private fun vibrateSoft() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(
                VibrationEffect.createOneShot(15, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(15)
        }
    }

    // ── TTS ──────────────────────────────────────────────────────────────────

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            ttsReady = true
        }
    }

    private fun announceAnimal(text: String) {
        if (!soundOn || !ttsReady) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts_${System.currentTimeMillis()}")
    }

    // ── Game setup ────────────────────────────────────────────────────────────

    private fun setupGame() {
        binding.gameView.applyLevel(level)
        binding.gameView.centerTrap()
        currentAnimalIdx = randomAnimal()
        setControlsState()
    }

    private fun setupListeners() {
        binding.playPauseBtn.setOnClickListener {
            if (binding.gameView.gameRunning) stopGame() else startGame()
        }
        binding.tapBtn.setOnClickListener { handleTap() }
        binding.shopBtn.setOnClickListener { openShop() }
        binding.soundBtn.setOnClickListener {
            soundOn = !soundOn
            savePrefs()
            updateSoundBtn()
            if (!soundOn) tts?.stop()
        }
    }

    private fun startGame() {
        binding.gameView.startLoop()
        setControlsState()
    }

    private fun stopGame() {
        binding.gameView.stopLoop()
        binding.gameView.resetSpeed()
        binding.gameView.clearBonus()
        level = 1
        catches = 0
        shieldCount = 0
        bonusCoinsActive = false
        bonusCatchCount = 0
        mainHandler.removeCallbacks(bonusExpireRunnable)
        mainHandler.removeCallbacks(comboDecayRunnable)
        setControlsState()
        updateLevelUI()
        updatePowerUpBar()
    }

    private fun setControlsState() {
        val running = binding.gameView.gameRunning
        binding.playPauseBtn.text = if (running) "Stop" else "Start"
        binding.playPauseBtn.setBackgroundResource(
            if (running) R.drawable.btn_danger_bg else R.drawable.btn_primary_bg
        )
        binding.tapBtn.alpha = if (running) 1f else 0.55f
        if (!running) binding.gameView.centerTrap()
    }

    // ── Core tap logic ────────────────────────────────────────────────────────

    private fun handleTap() {
        if (!binding.gameView.gameRunning) return

        val now = System.currentTimeMillis()
        val result = binding.gameView.checkTap()

        // Grace period after trap hit — avoids an immediate miss penalty
        // right after a catch (especially at level-ups when trap starts moving).
        if (result == GameView.TapResult.MISS && now - lastTrapHitAt < 400L) return

        when (result) {
            GameView.TapResult.TRAP_HIT -> onTrapHit()
            GameView.TapResult.FOOD_HIT -> onFoodHit()
            GameView.TapResult.BONUS_HIT -> onBonusHit()
            GameView.TapResult.MISS -> {
                if (shieldCount > 0) {
                    shieldCount--
                    updatePowerUpBar()
                    showToast("🛡️ Shield blocked the miss!")
                    vibrateStrong()
                    binding.gameView.sparkle(big = true)
                } else {
                    onMiss()
                }
            }
        }

        if (score > high) high = score
        updateUI()
        savePrefs()
    }

    private fun onTrapHit() {
        score += combo
        combo++
        catches++

        val now = System.currentTimeMillis()
        if (now - lastTrapHitAt <= COMBO_WINDOW_MS) trapStreak++ else trapStreak = 1
        lastTrapHitAt = now

        val comboCoins = if (trapStreak >= 2) minOf(10, trapStreak - 1) else 0
        coins += if (bonusCoinsActive) (comboCoins + 1) * 2 else comboCoins

        // Award shield on first time reaching a 5-streak
        if (trapStreak == 5 && shieldCount < MAX_SHIELDS) {
            shieldCount++
            showToast("🛡️ Shield earned for 5-streak!")
            updatePowerUpBar()
        }

        // Award shield every SHIELD_EVERY_N_CATCHES catches
        if (catches % SHIELD_EVERY_N_CATCHES == 0 && shieldCount < MAX_SHIELDS) {
            shieldCount++
            showToast("🛡️ Shield earned!")
            updatePowerUpBar()
        }

        // Spawn bonus star periodically, auto-expire after 3 s if not collected
        bonusCatchCount++
        if (bonusCatchCount % BONUS_SPAWN_EVERY == 0 && binding.gameView.bonusX < 0f) {
            binding.gameView.spawnBonus()
            mainHandler.removeCallbacks(bonusExpireRunnable)
            mainHandler.postDelayed(bonusExpireRunnable, BONUS_EXPIRE_MS)
        }

        playSound(soundTrap)

        val currentMilestone = score / MILESTONE_INTERVAL
        if (currentMilestone > lastMilestone) {
            lastMilestone = currentMilestone
            playSound(soundMilestone)
        }

        vibrateStrong()
        binding.gameView.sparkle(big = combo > 5)

        if (trapStreak >= 2) showComboPop()

        // Reset combo decay countdown after every successful hit
        mainHandler.removeCallbacks(comboDecayRunnable)
        mainHandler.postDelayed(comboDecayRunnable, COMBO_DECAY_DELAY_MS)

        currentAnimalIdx = randomAnimal(currentAnimalIdx)
        announceAnimal(animals[currentAnimalIdx].name)
        checkLevelUp()
        updateLevelUI()
    }

    private fun onFoodHit() {
        coins += if (bonusCoinsActive) 4 else 2
        combo = 1
        trapStreak = 0
        lastTrapHitAt = 0
        playSound(soundFood)
        binding.gameView.spawnFood()
        vibrateSoft()
    }

    private fun onMiss() {
        combo = 1
        trapStreak = 0
        lastTrapHitAt = 0
        score = 0
        lastMilestone = 0
        catches = 0
        level = 1
        shieldCount = 0
        bonusCoinsActive = false
        bonusCatchCount = 0
        binding.gameView.clearBonus()
        mainHandler.removeCallbacks(bonusExpireRunnable)
        mainHandler.removeCallbacks(comboDecayRunnable)
        binding.gameView.applyLevel(level)
        playSound(soundGameOver)
        flashGameOver()
        binding.gameView.centerTrap()
        updateLevelUI()
        updatePowerUpBar()
    }

    // ── Level system ──────────────────────────────────────────────────────────

    private fun checkLevelUp() {
        val newLevel = 1 + catches / GameView.CATCHES_PER_LEVEL
        if (newLevel > level) {
            level = newLevel
            if (level > bestLevel) bestLevel = level
            binding.gameView.applyLevel(level)
            coins += 25 * level
            playSound(soundMilestone)
            vibrateStrong()
            // Three big sparkle bursts across the track
            binding.gameView.sparkle(big = true)
            binding.gameView.sparkle(binding.gameView.width * 0.25f, binding.gameView.height / 2f, big = true)
            binding.gameView.sparkle(binding.gameView.width * 0.75f, binding.gameView.height / 2f, big = true)
            // Award a shield for reaching a new level
            if (shieldCount < MAX_SHIELDS) {
                shieldCount++
                updatePowerUpBar()
            }
            showToast("🚀 LEVEL UP!  +${25 * level} 🪙")
            applyRandomTheme()
            updateUI()
            updateLevelUI()
            savePrefs()
            announceAnimal("Level $level")
        }
    }

    // ── Animal randomizer ─────────────────────────────────────────────────────

    private fun randomAnimal(exceptIdx: Int = -1): Int {
        val pool = if (exceptIdx != -1 && unlockedIndices.size > 1)
            unlockedIndices.filter { it != exceptIdx }.ifEmpty { unlockedIndices }
        else unlockedIndices
        val idx = pool.random()
        val a = animals[idx]
        binding.gameView.animalEmoji = a.emoji
        binding.gameView.foodEmoji = a.food
        binding.animalNameText.text = "${a.emoji}  ${a.name.uppercase()}"
        return idx
    }

    // ── UI updates ────────────────────────────────────────────────────────────

    private fun updateUI() {
        binding.scoreText.text = score.toString()
        binding.comboText.text = "x$combo"
        binding.coinsText.text = coins.toString()
        binding.highText.text = "High $high"
        updatePowerUpBar()
    }

    private fun updateLevelUI() {
        binding.levelText.text = "🏁 Level $level"
        binding.bestLevelText.text = "🏆 Best $bestLevel"
        val progress = (catches % GameView.CATCHES_PER_LEVEL) * 100 / GameView.CATCHES_PER_LEVEL
        binding.levelProgressBar.progress = progress
    }

    private fun updateSoundBtn() {
        binding.soundBtn.text = if (soundOn) "🔊" else "🔇"
    }

    private fun showToast(msg: String) {
        binding.toastText.text = msg
        binding.toastText.visibility = View.VISIBLE
        mainHandler.removeCallbacks(toastHideRunnable)
        mainHandler.postDelayed(toastHideRunnable, 900)
    }

    private fun showComboPop() {
        val pop = binding.comboPop
        val bigText = binding.comboPopBig
        val smallText = binding.comboPopSmall
        bigText.text = "COMBO: $trapStreak"
        smallText.text = "$trapStreak quick hits in a row"
        pop.visibility = View.VISIBLE
        pop.alpha = 0f
        pop.scaleX = 0.3f
        pop.scaleY = 0.3f

        val scaleX = ObjectAnimator.ofFloat(pop, "scaleX", 0.3f, 1.1f, 1f)
        val scaleY = ObjectAnimator.ofFloat(pop, "scaleY", 0.3f, 1.1f, 1f)
        val alpha = ObjectAnimator.ofFloat(pop, "alpha", 0f, 1f)

        AnimatorSet().apply {
            playTogether(scaleX, scaleY, alpha)
            duration = 300
            start()
        }

        mainHandler.postDelayed({
            ObjectAnimator.ofFloat(pop, "alpha", 1f, 0f).apply {
                duration = 300
                start()
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        pop.visibility = View.GONE
                    }
                })
            }
        }, 1200)
    }

    private fun flashGameOver() {
        val root = binding.mainRoot
        root.setBackgroundColor(0xFF5A0000.toInt())
        mainHandler.postDelayed({ root.setBackgroundColor(0xFF111111.toInt()) }, 300)
        mainHandler.postDelayed({ root.setBackgroundColor(0xFF5A0000.toInt()) }, 500)
        mainHandler.postDelayed({ root.setBackgroundColor(0xFF111111.toInt()) }, 700)
    }

    // ── Bonus star hit ────────────────────────────────────────────────────────

    private fun onBonusHit() {
        mainHandler.removeCallbacks(bonusExpireRunnable) // player collected it in time
        bonusCoinsActive = true
        bonusEndTime = System.currentTimeMillis() + BONUS_DURATION_MS
        binding.gameView.clearBonus()
        coins += 10
        playSound(soundMilestone)
        vibrateStrong()
        binding.gameView.sparkle(big = true)
        binding.gameView.sparkle(binding.gameView.width * 0.8f, binding.gameView.height / 2f, big = true)
        showToast("🌟 ×2 COINS for 8s! +10 🪙")
        updatePowerUpBar()
        mainHandler.postDelayed({
            if (bonusCoinsActive) {
                bonusCoinsActive = false
                updatePowerUpBar()
            }
        }, BONUS_DURATION_MS)
    }

    // ── Power-up bar ─────────────────────────────────────────────────────────

    private fun updatePowerUpBar() {
        val parts = mutableListOf<String>()
        if (shieldCount > 0) parts.add("🛡️ ×$shieldCount")
        if (bonusCoinsActive) {
            val secsLeft = ((bonusEndTime - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L)
            parts.add("🌟 ×2 COINS ${secsLeft}s")
        }
        if (parts.isEmpty()) {
            binding.powerUpBar.visibility = View.GONE
        } else {
            binding.powerUpBar.text = parts.joinToString("   ")
            binding.powerUpBar.visibility = View.VISIBLE
        }
    }

    // ── Combo decay ───────────────────────────────────────────────────────────

    private fun decayCombo() {
        if (binding.gameView.gameRunning && combo > 1) {
            combo--
            updateUI()
            if (combo > 1) mainHandler.postDelayed(comboDecayRunnable, COMBO_DECAY_DELAY_MS)
        }
    }

    private fun applyRandomTheme() {
        val theme = themes.random()
        binding.gameView.setTrackBackground(*theme)
    }

    // ── Shop ──────────────────────────────────────────────────────────────────

    private fun openShop() {
        stopGame()

        val dialog = Dialog(this, R.style.ShopDialogTheme)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_shop)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.94).toInt(),
            (resources.displayMetrics.heightPixels * 0.82).toInt()
        )

        val shopCoinsText = dialog.findViewById<TextView>(R.id.shopCoinsText)
        shopCoinsText.text = "$coins 🪙"

        val recycler = dialog.findViewById<RecyclerView>(R.id.shopRecycler)
        recycler.layoutManager = GridLayoutManager(this, 2)

        val adapter = ShopAdapter(animals, unlockedIndices, coins) { idx ->
            if (coins >= animals[idx].cost) {
                coins -= animals[idx].cost
                unlockedIndices.add(idx)
                savePrefs()
                updateUI()
                shopCoinsText.text = "$coins 🪙"
                recycler.adapter?.notifyDataSetChanged()
            }
        }
        recycler.adapter = adapter

        dialog.findViewById<View>(R.id.shopCloseBtn).setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onPause() {
        super.onPause()
        stopGame()
    }

    override fun onDestroy() {
        super.onDestroy()
        tts?.shutdown()
        soundPool?.release()
        mainHandler.removeCallbacksAndMessages(null)
    }
}
