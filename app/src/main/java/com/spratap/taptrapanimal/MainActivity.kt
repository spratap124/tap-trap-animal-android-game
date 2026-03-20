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
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.spratap.taptrapanimal.databinding.ActivityMainBinding
import java.util.*

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    // --- Animal data — coin costs raised significantly to make unlocking hard ---
    private val animals = listOf(
        Animal("🐭", "🧀", 0,      "Rat"),
        Animal("🐶", "🦴", 0,      "Dog"),
        Animal("🐱", "🐟", 0,      "Cat"),
        Animal("🐰", "🥕", 0,      "Rabbit"),
        Animal("🐼", "🎋", 2000,   "Panda"),
        Animal("🐵", "🍌", 3500,   "Monkey"),
        Animal("🐮", "🌽", 5000,   "Cow"),
        Animal("🐷", "🍎", 7000,   "Pig"),
        Animal("🐔", "🌾", 9000,   "Chicken"),
        Animal("🐸", "🪰", 12000,  "Frog"),
        Animal("🐻", "🍯", 15000,  "Bear"),
        Animal("🐯", "🍖", 20000,  "Tiger"),
        Animal("🦁", "🍖", 25000,  "Lion"),
        Animal("🐨", "🌿", 32000,  "Koala"),
        Animal("🦊", "🍗", 40000,  "Fox"),
        Animal("🦄", "🍭", 50000,  "Unicorn"),
        Animal("🐧", "🐟", 65000,  "Penguin"),
        Animal("🐙", "🦐", 80000,  "Octopus"),
        Animal("🦉", "🪱", 100000, "Owl"),
        Animal("🦈", "🐠", 125000, "Shark"),
        Animal("🦒", "🍃", 150000, "Giraffe"),
        Animal("🦓", "🌿", 180000, "Zebra"),
        Animal("🐘", "🍉", 220000, "Elephant"),
        Animal("🐲", "🔥", 300000, "Dragon")
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

    // ── Frenzy mode ────────────────────────────────────────────────────────────
    private var frenzyActive = false
    private val FRENZY_TRIGGER_STREAK = 10
    private val FRENZY_DURATION_MS = 5000L
    private val frenzyEndRunnable = Runnable { endFrenzy() }

    // ── Slow-mo power-up ───────────────────────────────────────────────────────
    private var slowMoActive = false
    private val SLOWMO_DURATION_MS = 4000L
    private val SLOWMO_SPAWN_EVERY = 15
    private val SLOWMO_EXPIRE_MS = 3500L
    private val slowMoEndRunnable = Runnable { endSlowMo() }
    private val slowMoExpireRunnable = Runnable { binding.gameView.clearSlowMo() }

    // ── Combo decay ───────────────────────────────────────────────────────────
    private val COMBO_DECAY_DELAY_MS = 3500L
    private val comboDecayRunnable:  Runnable = Runnable { decayCombo() }
    private val bonusExpireRunnable: Runnable = Runnable {
        binding.gameView.clearBonus()
    }
    private var unlockedIndices = mutableListOf(0, 1, 2, 3)
    // index → expiry epoch millis; entries expire automatically
    private var tempUnlockedAnimals = mutableMapOf<Int, Long>()
    private val TEMP_UNLOCK_DURATION_MS = 2 * 60 * 60 * 1000L  // 2 hours
    private var currentAnimalIdx = 0
    private var soundOn = true
    private var announceOn = true

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

    // --- Ads ---
    private lateinit var adManager: AdManager
    private var gameOverCount = 0          // lifetime counter for frequency cap
    private var streakOfferShownAt = 0     // trapStreak value when offer was last shown
    // True while a rewarded ad is on screen — prevents onPause() from wiping game state
    private var isShowingContinueAd = false

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
        installSplashScreen()
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

        adManager = AdManager(this)
        adManager.init()

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
        announceOn = prefs.getBoolean("announceOn", true)
        val saved = prefs.getString("unlocked", "0,1,2,3") ?: "0,1,2,3"
        unlockedIndices = saved.split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .toMutableList()
            .ifEmpty { mutableListOf(0, 1, 2, 3) }
        // Load temp unlocks and immediately drop any that have expired
        val now = System.currentTimeMillis()
        val savedTemp = prefs.getString("tempUnlocked", "") ?: ""
        tempUnlockedAnimals = savedTemp.split(",")
            .mapNotNull { entry ->
                val parts = entry.split(":")
                if (parts.size == 2) {
                    val idx    = parts[0].toIntOrNull() ?: return@mapNotNull null
                    val expiry = parts[1].toLongOrNull() ?: return@mapNotNull null
                    if (expiry > now) idx to expiry else null
                } else null
            }
            .toMap().toMutableMap()
    }

    private fun savePrefs() {
        val tempStr = tempUnlockedAnimals.entries
            .filter { it.value > System.currentTimeMillis() }
            .joinToString(",") { "${it.key}:${it.value}" }
        prefs.edit()
            .putInt("coins", coins)
            .putInt("high", high)
            .putInt("bestLevel", bestLevel)
            .putBoolean("soundOn", soundOn)
            .putBoolean("announceOn", announceOn)
            .putString("unlocked", unlockedIndices.joinToString(","))
            .putString("tempUnlocked", tempStr)
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
        if (!soundOn || !announceOn || !ttsReady) return
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
        binding.watchAdShieldBtn.setOnClickListener { watchAdForShield() }
        binding.settingsBtn.setOnClickListener { openSettings() }
    }

    private fun startGame() {
        binding.gameView.startLoop()
        setControlsState()
    }

    private fun pauseGame() {
        binding.gameView.stopLoop()
        setControlsState()
    }

    private fun stopGame() {
        binding.gameView.stopLoop()
        binding.gameView.resetSpeed()
        binding.gameView.clearBonus()
        binding.gameView.clearSlowMo()
        level = 1
        catches = 0
        shieldCount = 0
        bonusCoinsActive = false
        bonusCatchCount = 0
        frenzyActive = false
        slowMoActive = false
        mainHandler.removeCallbacks(bonusExpireRunnable)
        mainHandler.removeCallbacks(comboDecayRunnable)
        mainHandler.removeCallbacks(frenzyEndRunnable)
        mainHandler.removeCallbacks(slowMoEndRunnable)
        mainHandler.removeCallbacks(slowMoExpireRunnable)
        setControlsState()
        updateLevelUI()
        updatePowerUpBar()
    }

    private fun setControlsState() {
        val running = binding.gameView.gameRunning
        binding.playPauseBtn.text = if (running) "⏹" else "▶"
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
            GameView.TapResult.SLOWMO_HIT -> onSlowMoHit()
            GameView.TapResult.MISS -> {
                if (frenzyActive) {
                    onFrenzyTap()
                } else if (shieldCount > 0) {
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
        if (trapStreak == 4 && shieldCount < MAX_SHIELDS) {
            shieldCount++
            showToast("🛡️ Shield earned for 4-streak!")
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

        // Spawn slow-mo clock periodically
        if (level >= 10 && catches % SLOWMO_SPAWN_EVERY == 0 && binding.gameView.slowMoX < 0f && !slowMoActive) {
            binding.gameView.spawnSlowMo()
            mainHandler.removeCallbacks(slowMoExpireRunnable)
            mainHandler.postDelayed(slowMoExpireRunnable, SLOWMO_EXPIRE_MS)
        }

        // Frenzy mode on big streak
        if (trapStreak == FRENZY_TRIGGER_STREAK && !frenzyActive) {
            startFrenzy()
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

        // Streak protection offer: once when combo hits 6 and no shield available
        if (combo == 6 && shieldCount == 0 && streakOfferShownAt != combo
            && adManager.isRewardedReady) {
            streakOfferShownAt = combo
            showStreakProtectionOffer()
        }

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
        playSound(soundGameOver)
        flashGameOver()
        binding.gameView.stopLoop()
        setControlsState()
        showGameOverDialog(nearMiss = binding.gameView.isNearMiss())
    }

    // ── Game Over dialog ─────────────────────────────────────────────────
    private fun showGameOverDialog(nearMiss: Boolean) {
        val dialog = android.app.Dialog(this, R.style.ShopDialogTheme)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_game_over)
        dialog.setCancelable(false)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.88).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )

        dialog.findViewById<TextView>(R.id.gameOverScore).text = score.toString()
        dialog.findViewById<TextView>(R.id.gameOverLevel).text = level.toString()
        dialog.findViewById<TextView>(R.id.gameOverCombo).text = "x$combo"

        if (nearMiss) {
            dialog.findViewById<TextView>(R.id.gameOverSubtitle).visibility = View.VISIBLE
            dialog.findViewById<TextView>(R.id.gameOverIcon).text = "😱"
        }

        val continueBtn = dialog.findViewById<android.widget.Button>(R.id.btnContinueAd)
        if (!adManager.isRewardedReady) {
            continueBtn.isEnabled = false
            continueBtn.text = "📺 Ad Loading…"
        }

        continueBtn.setOnClickListener {
            dialog.dismiss()
            isShowingContinueAd = true
            var rewardEarned = false
            adManager.showRewardedAd(
                onRewarded = {
                    rewardEarned = true
                    continueGame()
                },
                onDismissed = {
                    isShowingContinueAd = false
                    // Only reset if the player skipped the ad without earning the reward
                    if (!rewardEarned) doFullReset()
                }
            )
        }

        dialog.findViewById<android.widget.Button>(R.id.btnRestart).setOnClickListener {
            dialog.dismiss()
            doFullReset()
        }

        dialog.show()
    }

    /** Resume after watching a Continue ad — nothing is reset. */
    private fun continueGame() {
        // Re-apply level in case onPause() touched speed (shouldn't happen now, but safety net)
        binding.gameView.applyLevel(level)
        binding.gameView.startLoop()
        setControlsState()
        updateUI()
        updateLevelUI()
        showToast("✅ Streak saved! Keep going!")
        binding.gameView.sparkle(big = true)
    }

    /** Full reset after choosing Restart (or skipping the Continue ad). */
    private fun doFullReset() {
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
        streakOfferShownAt = 0
        frenzyActive = false
        slowMoActive = false
        binding.gameView.clearBonus()
        binding.gameView.clearSlowMo()
        mainHandler.removeCallbacks(bonusExpireRunnable)
        mainHandler.removeCallbacks(comboDecayRunnable)
        mainHandler.removeCallbacks(frenzyEndRunnable)
        mainHandler.removeCallbacks(slowMoEndRunnable)
        mainHandler.removeCallbacks(slowMoExpireRunnable)
        binding.gameView.applyLevel(level)
        binding.gameView.centerTrap()
        updateLevelUI()
        updatePowerUpBar()
        updateUI()
        savePrefs()

        // Interstitial only every 3rd game over, never on the very first
        gameOverCount++
        if (gameOverCount > 1 && gameOverCount % 3 == 0) {
            adManager.showInterstitial {}
        }
    }

    // ── Watch Ad for free shield ─────────────────────────────────────────
    private fun watchAdForShield() {
        if (shieldCount >= MAX_SHIELDS) {
            showToast("🛡️ Already at max shields!")
            return
        }
        if (!adManager.isRewardedReady) {
            showToast("📺 Ad not ready yet…")
            return
        }
        adManager.showRewardedAd(
            onRewarded = {
                if (shieldCount < MAX_SHIELDS) {
                    shieldCount++
                    updatePowerUpBar()
                    showToast("🛡️ Free shield earned!")
                    binding.gameView.sparkle(big = true)
                    savePrefs()
                }
            }
        )
    }

    // ── Streak protection offer ──────────────────────────────────────────
    private fun showStreakProtectionOffer() {
        val dialog = android.app.AlertDialog.Builder(this, R.style.ShopDialogTheme)
            .setMessage("🔥 Hot streak! Protect it with a shield?\nWatch a short ad for a free 🛡️")
            .setPositiveButton("📺 Yes, protect!") { _, _ ->
                adManager.showRewardedAd(
                    onRewarded = {
                        if (shieldCount < MAX_SHIELDS) {
                            shieldCount++
                            updatePowerUpBar()
                            showToast("🛡️ Streak protected!")
                            binding.gameView.sparkle(big = true)
                            savePrefs()
                        }
                    }
                )
            }
            .setNegativeButton("Not now", null)
            .create()
        dialog.show()
    }

    // ── Watch Ad for coins ───────────────────────────────────────────────
    fun watchAdForCoins() {
        if (!adManager.isRewardedReady) {
            showToast("📺 Ad not ready yet…")
            return
        }
        adManager.showRewardedAd(
            onRewarded = {
                coins += 1000
                showToast("🪙 +1000 coins!")
                updateUI()
                savePrefs()
            }
        )
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

    // ── Temp-unlock helpers ────────────────────────────────────────────────────

    private fun isTempUnlocked(idx: Int): Boolean {
        val expiry = tempUnlockedAnimals[idx] ?: return false
        return System.currentTimeMillis() < expiry
    }

    /** Minutes remaining for a temp-unlocked animal (0 if expired/not temp). */
    private fun tempMinutesLeft(idx: Int): Long {
        val expiry = tempUnlockedAnimals[idx] ?: return 0L
        return ((expiry - System.currentTimeMillis()) / 60_000L).coerceAtLeast(0L)
    }

    /** Map of idx → minutes remaining, for active temp unlocks only. */
    private fun buildTempUnlockMap(): Map<Int, Long> {
        val now = System.currentTimeMillis()
        return tempUnlockedAnimals
            .filter { it.value > now }
            .mapValues { ((it.value - now) / 60_000L).coerceAtLeast(1L) }
    }

    /** All indices available for gameplay (permanent + active temp unlocks). */
    private fun availableAnimals(): List<Int> {
        val now = System.currentTimeMillis()
        val temp = tempUnlockedAnimals.filter { it.value > now }.keys
        return (unlockedIndices + temp).distinct()
    }

    // ── Animal randomizer ─────────────────────────────────────────────────────

    private fun randomAnimal(exceptIdx: Int = -1): Int {
        val available = availableAnimals()
        val pool = if (exceptIdx != -1 && available.size > 1)
            available.filter { it != exceptIdx }.ifEmpty { available }
        else available
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
        // Sound state is shown inside the settings popup — nothing to update in the top bar
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

    // ── Frenzy mode ──────────────────────────────────────────────────────────

    private fun startFrenzy() {
        frenzyActive = true
        binding.gameView.frenzyActive = true
        playSound(soundMilestone)
        vibrateStrong()
        showToast("🔥🔥 FRENZY! TAP FAST! 🔥🔥")
        binding.gameView.sparkle(big = true)
        binding.gameView.sparkle(binding.gameView.width * 0.2f, binding.gameView.height / 2f, big = true)
        binding.gameView.sparkle(binding.gameView.width * 0.8f, binding.gameView.height / 2f, big = true)
        mainHandler.removeCallbacks(frenzyEndRunnable)
        mainHandler.postDelayed(frenzyEndRunnable, FRENZY_DURATION_MS)
    }

    private fun endFrenzy() {
        frenzyActive = false
        binding.gameView.frenzyActive = false
        trapStreak = 0
        showToast("🔥 Frenzy over!")
    }

    /** During frenzy, every tap scores — no misses. */
    private fun onFrenzyTap() {
        score += combo
        coins += 2
        vibrateStrong()
        binding.gameView.sparkle(big = false)
    }

    // ── Slow-mo power-up ──────────────────────────────────────────────────────

    private fun onSlowMoHit() {
        mainHandler.removeCallbacks(slowMoExpireRunnable)
        slowMoActive = true
        binding.gameView.slowMoActive = true
        binding.gameView.clearSlowMo()
        playSound(soundMilestone)
        vibrateStrong()
        showToast("⏱️ SLOW MOTION!")
        binding.gameView.sparkle(big = true)
        updatePowerUpBar()
        mainHandler.removeCallbacks(slowMoEndRunnable)
        mainHandler.postDelayed(slowMoEndRunnable, SLOWMO_DURATION_MS)
    }

    private fun endSlowMo() {
        slowMoActive = false
        binding.gameView.slowMoActive = false
        updatePowerUpBar()
    }

    // ── Power-up bar ─────────────────────────────────────────────────────────

    private fun updatePowerUpBar() {
        val parts = mutableListOf<String>()
        if (shieldCount > 0) parts.add("🛡️×$shieldCount")
        if (bonusCoinsActive) {
            val secsLeft = ((bonusEndTime - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L)
            parts.add("🌟×2 ${secsLeft}s")
        }
        if (frenzyActive) parts.add("🔥 FRENZY")
        if (slowMoActive) parts.add("⏱️ SLOW")
        if (parts.isEmpty()) {
            binding.powerUpBar.visibility = View.GONE
        } else {
            binding.powerUpBar.text = parts.joinToString("  ")
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

    // ── Settings popup ────────────────────────────────────────────────────────

    private fun openSettings() {
        val dialog = Dialog(this, R.style.ShopDialogTheme)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_settings)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.88).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val soundToggle = dialog.findViewById<TextView>(R.id.soundToggle)
        val announceToggle = dialog.findViewById<TextView>(R.id.announceToggle)

        fun refreshToggles() {
            soundToggle.text = if (soundOn) "ON" else "OFF"
            soundToggle.setBackgroundResource(
                if (soundOn) R.drawable.settings_toggle_on else R.drawable.settings_toggle_off
            )
            announceToggle.text = if (announceOn) "ON" else "OFF"
            announceToggle.setBackgroundResource(
                if (announceOn) R.drawable.settings_toggle_on else R.drawable.settings_toggle_off
            )
        }
        refreshToggles()

        dialog.findViewById<View>(R.id.soundRow).setOnClickListener {
            soundOn = !soundOn
            if (!soundOn) tts?.stop()
            savePrefs()
            refreshToggles()
        }

        dialog.findViewById<View>(R.id.announceRow).setOnClickListener {
            announceOn = !announceOn
            if (!announceOn) tts?.stop()
            savePrefs()
            refreshToggles()
        }

        dialog.findViewById<View>(R.id.settingsHowToPlayBtn).setOnClickListener {
            dialog.dismiss()
            openInstructions()
        }

        dialog.findViewById<View>(R.id.settingsCloseBtn).setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    // ── Shop ──────────────────────────────────────────────────────────────────

    private fun openInstructions() {
        val dialog = Dialog(this, R.style.ShopDialogTheme)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_instructions)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92).toInt(),
            (resources.displayMetrics.heightPixels * 0.80).toInt()
        )
        dialog.findViewById<android.view.View>(R.id.instrCloseBtn).setOnClickListener {
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun openShop() {
        val wasRunning = binding.gameView.gameRunning
        if (wasRunning) pauseGame()

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

        lateinit var adapter: ShopAdapter

        val onBuy: (Int) -> Unit = { idx ->
            if (coins >= animals[idx].cost) {
                coins -= animals[idx].cost
                unlockedIndices.add(idx)
                savePrefs()
                updateUI()
                shopCoinsText.text = "$coins 🪙"
                adapter.updateCoins(coins)
            }
        }

        val onWatchAd: (Int) -> Unit = { idx ->
            if (!adManager.isRewardedReady) {
                showToast("📺 Ad loading, try again shortly")
            } else {
                adManager.showRewardedAd(
                    onRewarded = {
                        // Temp unlock for 2 hours — not permanent
                        val expiry = System.currentTimeMillis() + TEMP_UNLOCK_DURATION_MS
                        tempUnlockedAnimals[idx] = expiry
                        savePrefs()
                        adapter.updateTempUnlocks(buildTempUnlockMap())
                        adapter.updateAdReady(adManager.isRewardedReady)
                        val hrs = TEMP_UNLOCK_DURATION_MS / 3_600_000L
                        showToast("⏱ ${animals[idx].name} unlocked for ${hrs}h!")
                    },
                    onDismissed = {
                        adapter.updateAdReady(adManager.isRewardedReady)
                    }
                )
            }
        }

        adapter = ShopAdapter(
            animals, unlockedIndices, coins,
            adManager.isRewardedReady,
            buildTempUnlockMap(),
            onBuy, onWatchAd
        )
        recycler.adapter = adapter

        // Watch Ad → +100 coins strip
        val watchAdCoinsBtn = dialog.findViewById<android.widget.Button>(R.id.shopWatchAdCoinsBtn)
        watchAdCoinsBtn.isEnabled = adManager.isRewardedReady
        watchAdCoinsBtn.setOnClickListener {
            adManager.showRewardedAd(
                onRewarded = {
                coins += 1000
                updateUI()
                shopCoinsText.text = "$coins 🪙"
                adapter.updateCoins(coins)
                savePrefs()
                showToast("🪙 +1000 coins!")
                },
                onDismissed = {
                    watchAdCoinsBtn.isEnabled = adManager.isRewardedReady
                }
            )
        }

        dialog.findViewById<View>(R.id.shopCloseBtn).setOnClickListener {
            dialog.dismiss()
            if (wasRunning) startGame()
        }

        dialog.show()
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onPause() {
        super.onPause()
        // Don't wipe game state when a continue-ad is on screen —
        // the ad takes over the Activity which triggers onPause(), but the
        // player hasn't lost yet and expects to resume after the ad.
        if (!isShowingContinueAd) stopGame()
    }

    override fun onDestroy() {
        super.onDestroy()
        tts?.shutdown()
        soundPool?.release()
        mainHandler.removeCallbacksAndMessages(null)
        adManager.destroy()
    }
}
