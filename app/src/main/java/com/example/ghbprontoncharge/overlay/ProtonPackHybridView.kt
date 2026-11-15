package com.example.ghbprontoncharge.overlay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.BatteryManager
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import com.example.ghbprontoncharge.R
import com.example.ghbprontoncharge.util.PreferencesManager
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sin

enum class PowerCellMode { FIXED, CHARGE_RATE }

/**
 * Configuration des coordonnées normalisées (0.0 - 1.0) relatives à l'image d'origine
 * proton_pack.png (860 x 1096 pixels).
 */
object PackLayout {
    const val IMAGE_ORIGINAL_WIDTH = 860f
    const val IMAGE_ORIGINAL_HEIGHT = 1096f

    // Zone réservée en bas pour le texte (~18 % de la hauteur)
    const val RESERVED_BOTTOM_RATIO = 0.18f

    // CYCLOTRON (4 lumières, ordre de rotation horaire : 1 haut-gauche, 2 haut-droite, 3 bas-droite, 4 bas-gauche)
    const val CYCLOTRON_1_X = 0.381f
    const val CYCLOTRON_1_Y = 0.590f

    const val CYCLOTRON_2_X = 0.535f
    const val CYCLOTRON_2_Y = 0.589f

    const val CYCLOTRON_3_X = 0.515f
    const val CYCLOTRON_3_Y = 0.689f

    const val CYCLOTRON_4_X = 0.398f
    const val CYCLOTRON_4_Y = 0.689f

    // Rayon relatif à la LARGEUR affichée de l'image (scale * IMAGE_ORIGINAL_WIDTH)
    const val CYCLOTRON_RADIUS = 0.023f

    // POWER CELL (barre verticale de LED)
    const val POWER_CELL_LEFT = 0.223f
    const val POWER_CELL_TOP = 0.247f
    const val POWER_CELL_RIGHT = 0.255f
    const val POWER_CELL_BOTTOM = 0.357f
    const val POWER_CELL_NUM_SEGMENTS = 15

    // Activer/désactiver les contours de débogage et les tags de coordonnées
    const val DEBUG_LAYOUT = false

    // Vitesse et mode du chenillard Power Cell
    val POWER_CELL_MODE = PowerCellMode.CHARGE_RATE
    const val FIXED_STEP_MS = 70L          // durée d'un segment en mode FIXED (~1 s par cycle complet de 15)
    const val MIN_STEP_MS = 35L            // le plus rapide (charge rapide)
    const val MAX_STEP_MS = 140L           // le plus lent (charge faible)
    const val MAX_CURRENT_MA = 3000        // courant considéré comme "charge maximale"
}

@SuppressLint("ClickableViewAccessibility")
class ProtonPackHybridView @JvmOverloads constructor(
    context: Context,
    private val playStartupSound: Boolean = true,
    private val onDismissRequest: (() -> Unit)? = null
) : View(context) {

    private val density = resources.displayMetrics.density
    private var mediaPlayer: MediaPlayer? = null
    private var animator: ValueAnimator? = null
    private var startTimeMs: Long = 0L

    // Chargement du bitmap sans mise à l'échelle automatique (inScaled = false)
    private val packBitmap: Bitmap? = try {
        val opts = BitmapFactory.Options().apply { inScaled = false }
        BitmapFactory.decodeResource(resources, R.drawable.proton_pack, opts)
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }

    // Niveau de batterie, relu chaque seconde dans onDraw (avant : lu une seule fois -> restait fige)
    private var batteryLevel: Int = getBatteryLevel(context)
    private var lastInfoUpdateMs = 0L

    // Ligne d'info (GeV + estimation), construite 1 fois/s pour ne rien allouer dans onDraw
    private var infoLine: String? = null

    // Calculs de courant de charge (mode CHARGE_RATE)
    private var lastCurrentReadTimeMs = 0L
    private val currentSamples = IntArray(3)
    private var sampleIndex = 0
    private var sampleCount = 0
    private var smoothedCurrentMa = -1
    private var cachedStepMs = PackLayout.FIXED_STEP_MS

    // Calculs de disposition (mis à jour dans onSizeChanged)
    private var drawnWidth = 0f
    private var drawnHeight = 0f
    private var imageLeft = 0f
    private var imageTop = 0f
    private var scale = 0f
    private val imageRect = RectF()

    // Coordonnées calculées des 4 feux du Cyclotron
    private val cyclotronX = FloatArray(4)
    private val cyclotronY = FloatArray(4)
    private var cyclotronRadiusPx = 0f

    // Bounding box calculée de la Power Cell et son Halo
    private val powerCellRect = RectF()
    private val powerCellHaloRect = RectF()
    private val powerCellSegRect = RectF()

    // Paints pré-allouées (aucune allocation dans onDraw)
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    // Paints Cyclotron
    private val cycloOffPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 0x1A, 0x00, 0x00) // #1A0000 alpha ~200
        style = Paint.Style.FILL
    }

    private val cycloActivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF2020")
        style = Paint.Style.FILL
    }

    private val cycloHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val cycloHaloShaders = arrayOfNulls<RadialGradient>(4)

    // Paints Power Cell
    private val powerCellMaskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#05051A") // Masque sombre sur les LED de la photo
        style = Paint.Style.FILL
    }

    private val powerCellSegPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1E64FF")
        style = Paint.Style.FILL
    }

    private val powerCellGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4D8CFF")
        style = Paint.Style.FILL
    }

    private val powerCellHeadSegPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8CC4FF")
        style = Paint.Style.FILL
    }

    private val powerCellHeadGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#C2E0FF")
        style = Paint.Style.FILL
    }

    private val powerCellHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4B4BFF")
        style = Paint.Style.FILL
        maskFilter = BlurMaskFilter(10f * density, BlurMaskFilter.Blur.OUTER)
    }

    // Paints Texte zone du bas
    private val batteryTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00FFFF")
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 36f, resources.displayMetrics)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFCC00")
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 20f, resources.displayMetrics)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val currentTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00AAFF")
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, resources.displayMetrics)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val warningTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF3333")
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 22f, resources.displayMetrics)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    // Paints Débogage
    private val debugOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }

    private val debugTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GREEN
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10f, resources.displayMetrics)
    }

    init {
        setBackgroundColor(Color.BLACK)

        // 1. Initialisation audio si autorisée
        setupAudioPlayer()

        // 2. Fermeture au toucher
        setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                onDismissRequest?.invoke()
                true
            } else {
                false
            }
        }
    }

    private fun getBatteryLevel(context: Context): Int {
        val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { filter ->
            context.registerReceiver(null, filter)
        }
        val level: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) {
            (level * 100 / scale.toFloat()).toInt()
        } else {
            100
        }
    }

    private fun updateCurrentReading() {
        try {
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val rawMicroAmps = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            val rawAbs = abs(rawMicroAmps)

            // Conversion microampères (µA) -> milliampères (mA)
            val ma = if (rawAbs > 1000) rawAbs / 1000 else rawAbs

            if (rawMicroAmps != 0 && rawMicroAmps != Int.MIN_VALUE && ma in 50..10000) {
                currentSamples[sampleIndex] = ma
                sampleIndex = (sampleIndex + 1) % 3
                if (sampleCount < 3) sampleCount++

                var sum = 0
                for (i in 0 until sampleCount) {
                    sum += currentSamples[i]
                }
                smoothedCurrentMa = sum / sampleCount

                val currentClamped = smoothedCurrentMa.coerceIn(0, PackLayout.MAX_CURRENT_MA)
                val t = currentClamped.toFloat() / PackLayout.MAX_CURRENT_MA
                cachedStepMs = (PackLayout.MAX_STEP_MS - t * (PackLayout.MAX_STEP_MS - PackLayout.MIN_STEP_MS)).toLong()
            } else {
                smoothedCurrentMa = -1
                cachedStepMs = PackLayout.FIXED_STEP_MS
            }
        } catch (e: Exception) {
            smoothedCurrentMa = -1
            cachedStepMs = PackLayout.FIXED_STEP_MS
        }
    }

    private fun setupAudioPlayer() {
        if (!playStartupSound) return

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val isRingerNormal = audioManager.ringerMode == AudioManager.RINGER_MODE_NORMAL
        val isSoundAllowed = isRingerNormal && PreferencesManager.isSoundEnabled(context)

        if (isSoundAllowed) {
            try {
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()

                mediaPlayer = MediaPlayer.create(context, R.raw.proton_powerup)?.apply {
                    setAudioAttributes(audioAttributes)
                    setVolume(1.0f, 1.0f)
                    isLooping = false
                    setOnCompletionListener { mp ->
                        try {
                            mp.setVolume(0f, 0f)
                            mp.stop()
                            mp.release()
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                        if (mediaPlayer == mp) {
                            mediaPlayer = null
                        }
                    }
                    start()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        val totalW = w.toFloat()
        val totalH = h.toFloat()
        if (totalW <= 0f || totalH <= 0f) return

        // Réserver 18 % de la hauteur en bas pour le texte
        val reservedBottom = totalH * PackLayout.RESERVED_BOTTOM_RATIO
        val availableH = totalH - reservedBottom
        val availableW = totalW

        // Calcul Fit Center dans la zone disponible
        val scaleX = availableW / PackLayout.IMAGE_ORIGINAL_WIDTH
        val scaleY = availableH / PackLayout.IMAGE_ORIGINAL_HEIGHT
        scale = minOf(scaleX, scaleY)

        drawnWidth = PackLayout.IMAGE_ORIGINAL_WIDTH * scale
        drawnHeight = PackLayout.IMAGE_ORIGINAL_HEIGHT * scale

        imageLeft = (availableW - drawnWidth) / 2f
        imageTop = (availableH - drawnHeight) / 2f
        imageRect.set(imageLeft, imageTop, imageLeft + drawnWidth, imageTop + drawnHeight)

        // Calcul des coordonnées du Cyclotron
        cyclotronRadiusPx = PackLayout.CYCLOTRON_RADIUS * drawnWidth

        cyclotronX[0] = imageLeft + PackLayout.CYCLOTRON_1_X * drawnWidth
        cyclotronY[0] = imageTop + PackLayout.CYCLOTRON_1_Y * drawnHeight

        cyclotronX[1] = imageLeft + PackLayout.CYCLOTRON_2_X * drawnWidth
        cyclotronY[1] = imageTop + PackLayout.CYCLOTRON_2_Y * drawnHeight

        cyclotronX[2] = imageLeft + PackLayout.CYCLOTRON_3_X * drawnWidth
        cyclotronY[2] = imageTop + PackLayout.CYCLOTRON_3_Y * drawnHeight

        cyclotronX[3] = imageLeft + PackLayout.CYCLOTRON_4_X * drawnWidth
        cyclotronY[3] = imageTop + PackLayout.CYCLOTRON_4_Y * drawnHeight

        val haloRadius = cyclotronRadiusPx * 2.5f
        for (i in 0..3) {
            if (haloRadius > 0f) {
                cycloHaloShaders[i] = RadialGradient(
                    cyclotronX[i],
                    cyclotronY[i],
                    haloRadius,
                    intArrayOf(Color.argb(204, 255, 32, 32), Color.argb(0, 255, 32, 32)),
                    floatArrayOf(0f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
        }

        // Calcul de la Bounding Box de la Power Cell et son Halo
        powerCellRect.set(
            imageLeft + PackLayout.POWER_CELL_LEFT * drawnWidth,
            imageTop + PackLayout.POWER_CELL_TOP * drawnHeight,
            imageLeft + PackLayout.POWER_CELL_RIGHT * drawnWidth,
            imageTop + PackLayout.POWER_CELL_BOTTOM * drawnHeight
        )

        val pcMargin = 3f * density
        powerCellHaloRect.set(
            powerCellRect.left - pcMargin,
            powerCellRect.top - pcMargin,
            powerCellRect.right + pcMargin,
            powerCellRect.bottom + pcMargin
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startTimeMs = SystemClock.elapsedRealtime()
        startAnimator()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        releaseResources()
    }

    private fun startAnimator() {
        if (animator == null) {
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1000L
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener {
                    invalidate()
                }
                start()
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val totalW = width.toFloat()
        val totalH = height.toFloat()
        if (totalW <= 0f || totalH <= 0f) return

        val nowMs = SystemClock.elapsedRealtime()
        val elapsedMs = nowMs - startTimeMs
        val elapsedSec = elapsedMs / 1000f

        // Mise à jour périodique (toutes les 1000 ms) : niveau, courant, estimation
        if (nowMs - lastInfoUpdateMs >= 1000L || lastInfoUpdateMs == 0L) {
            lastInfoUpdateMs = nowMs
            batteryLevel = getBatteryLevel(context)
            if (PackLayout.POWER_CELL_MODE == PowerCellMode.CHARGE_RATE) {
                lastCurrentReadTimeMs = nowMs
                updateCurrentReading()
            }
            infoLine = buildInfoLine()
        }

        // 1. Fond noir
        canvas.drawColor(Color.BLACK)

        // 2. Dessiner le bitmap du Proton Pack (Fit Center)
        packBitmap?.let { bmp ->
            canvas.drawBitmap(bmp, null, imageRect, bitmapPaint)
        }

        // 3. CYCLOTRON
        // Accélération de la fréquence de 1,0 s/cycle à 0,4 s/cycle en 3s
        val phase: Float = if (elapsedSec <= 3f) {
            elapsedSec + 0.25f * elapsedSec * elapsedSec
        } else {
            5.25f + 2.5f * (elapsedSec - 3f)
        }
        val lightPosition = (phase * 4f) % 4f

        for (i in 0..3) {
            val cx = cyclotronX[i]
            val cy = cyclotronY[i]

            // Disque sombre éteint (#1A0000, alpha ~200)
            canvas.drawCircle(cx, cy, cyclotronRadiusPx, cycloOffPaint)

            // Calcul de l'intensité relative avec fondu croisé
            var diff = i.toFloat() - lightPosition
            while (diff < -2f) diff += 4f
            while (diff > 2f) diff -= 4f
            val intensity = (1f - abs(diff)).coerceIn(0f, 1f)

            if (intensity > 0f) {
                // Halo de débordement RadialGradient
                cycloHaloShaders[i]?.let { shader ->
                    cycloHaloPaint.shader = shader
                    cycloHaloPaint.alpha = (255 * intensity * 0.8f).toInt().coerceIn(0, 255)
                    canvas.drawCircle(cx, cy, cyclotronRadiusPx * 2.5f, cycloHaloPaint)
                }

                // Disque rouge vif allumé (#FF2020)
                cycloActivePaint.alpha = (255 * intensity).toInt().coerceIn(0, 255)
                canvas.drawCircle(cx, cy, cyclotronRadiusPx, cycloActivePaint)
            }
        }

        // 4. POWER CELL - EFFET CHENILLARD (Ghostbusters 1984)
        // B. Lueur bleue douce et pulsée (dessinée AVANT le masque : seul le flou déborde autour)
        val haloAlpha = 0.45f + 0.30f * (0.5f + 0.5f * sin((elapsedSec * 2f * Math.PI / 2.0).toFloat()))
        powerCellHaloPaint.alpha = (255 * haloAlpha).toInt().coerceIn(0, 255)
        canvas.drawRoundRect(powerCellHaloRect, 4f * density, 4f * density, powerCellHaloPaint)

        // A. Masque sombre pour recouvrir les LED statiques de la photo
        canvas.drawRect(powerCellRect, powerCellMaskPaint)

        // C. Chenillard des segments (0 -> segment cible de bas en haut, puis pause 80ms)
        val totalSegments = PackLayout.POWER_CELL_NUM_SEGMENTS
        val targetSegments = ceil(batteryLevel * totalSegments / 100.0).toInt().coerceIn(1, totalSegments)

        val currentStepMs = if (PackLayout.POWER_CELL_MODE == PowerCellMode.CHARGE_RATE) cachedStepMs else PackLayout.FIXED_STEP_MS
        val pauseMs = 80L
        val cycleDurationMs = targetSegments * currentStepMs + pauseMs

        val timeInCycle = elapsedMs % cycleDurationMs
        val fillDurationMs = targetSegments * currentStepMs

        val isPausePhase = timeInCycle >= fillDurationMs
        val activeLitCount = if (isPausePhase) 0 else ((timeInCycle / currentStepMs) + 1).toInt().coerceIn(1, targetSegments)

        val pcHeight = powerCellRect.height()
        val segH = pcHeight / totalSegments
        val gap = 2f * density

        for (i in 0 until totalSegments) {
            val syBottom = powerCellRect.bottom - (i * segH) - gap / 2f
            val syTop = powerCellRect.bottom - ((i + 1) * segH) + gap / 2f

            val isLit = i < activeLitCount
            val isHead = (i == activeLitCount - 1) && !isPausePhase

            if (isLit) {
                powerCellSegRect.set(
                    powerCellRect.left + 1f * density,
                    syTop,
                    powerCellRect.right - 1f * density,
                    syBottom
                )

                if (isHead) {
                    // Segment tête (plus brillant : #B0B0FF + glow #D0D0FF)
                    canvas.drawRoundRect(powerCellSegRect, 2f * density, 2f * density, powerCellHeadGlowPaint)
                    canvas.drawRoundRect(powerCellSegRect, 2f * density, 2f * density, powerCellHeadSegPaint)
                } else {
                    // Segments corps (#4B4BFF + glow #8080FF)
                    canvas.drawRoundRect(powerCellSegRect, 2f * density, 2f * density, powerCellGlowPaint)
                    canvas.drawRoundRect(powerCellSegRect, 2f * density, 2f * density, powerCellSegPaint)
                }
            }
        }

        // 5. TEXTE DANS LA ZONE DU BAS (~18 %)
        val centerX = totalW / 2f
        val line = infoLine

        if (line != null) {
            canvas.drawText("$batteryLevel%", centerX, totalH * 0.85f, batteryTextPaint)
            canvas.drawText("NUCLEAR POWER CELL", centerX, totalH * 0.89f, subTextPaint)
            canvas.drawText(line, centerX, totalH * 0.925f, currentTextPaint)
            canvas.drawText("DON'T CROSS THE STREAMS!", centerX, totalH * 0.965f, warningTextPaint)
        } else {
            canvas.drawText("$batteryLevel%", centerX, totalH * 0.86f, batteryTextPaint)
            canvas.drawText("NUCLEAR POWER CELL", centerX, totalH * 0.91f, subTextPaint)
            canvas.drawText("DON'T CROSS THE STREAMS!", centerX, totalH * 0.96f, warningTextPaint)
        }

        // 6. DÉBOGAGE (Si PackLayout.DEBUG_LAYOUT == true)
        if (PackLayout.DEBUG_LAYOUT) {
            // Contour rectangle image
            canvas.drawRect(imageRect, debugOutlinePaint)
            canvas.drawText(
                "IMG: ${imageRect.width().toInt()}x${imageRect.height().toInt()} scale=%.3f".format(scale),
                imageRect.left + 8f * density,
                imageRect.top + 16f * density,
                debugTextPaint
            )

            // Contours Cyclotron
            for (i in 0..3) {
                val cx = cyclotronX[i]
                val cy = cyclotronY[i]
                canvas.drawCircle(cx, cy, cyclotronRadiusPx, debugOutlinePaint)
                val label = when (i) {
                    0 -> "C1 (0.381, 0.590)"
                    1 -> "C2 (0.535, 0.589)"
                    2 -> "C3 (0.515, 0.689)"
                    else -> "C4 (0.398, 0.689)"
                }
                canvas.drawText(label, cx - cyclotronRadiusPx, cy - cyclotronRadiusPx - 4f, debugTextPaint)
            }

            // Contour Power Cell
            canvas.drawRect(powerCellRect, debugOutlinePaint)
            canvas.drawText(
                "PC (0.223, 0.247, 0.255, 0.357)",
                powerCellRect.left,
                powerCellRect.top - 4f,
                debugTextPaint
            )
        }
    }

    /** "⚡ 1.45 GeV  ·  PLEIN DANS 1 h 05" ; chaque partie n'apparait que si elle est connue. */
    private fun buildInfoLine(): String? {
        val gev = if (PackLayout.POWER_CELL_MODE == PowerCellMode.CHARGE_RATE && smoothedCurrentMa > 0) {
            String.format(Locale.US, "⚡ %.2f GeV", smoothedCurrentMa / 100.0)
        } else null

        val minutes = estimateMinutesToFull()
        val eta = when {
            batteryLevel >= 100 -> "CHARGE COMPLÈTE"
            minutes <= 0 -> null
            minutes < 60 -> "PLEIN DANS $minutes MIN"
            else -> String.format(Locale.US, "PLEIN DANS %d h %02d", minutes / 60, minutes % 60)
        }

        return listOfNotNull(gev, eta).takeIf { it.isNotEmpty() }?.joinToString("  ·  ")
    }

    /** Minutes avant 100 %, ou -1 si inconnu. */
    private fun estimateMinutesToFull(): Int {
        if (batteryLevel >= 100) return 0
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return -1

        // 1. Estimation officielle d'Android (tient compte du ralentissement en fin de charge)
        val ms = try { bm.computeChargeTimeRemaining() } catch (e: Exception) { -1L }
        if (ms > 0) return ceil(ms / 60000.0).toInt()

        // 2. Repli : capacite restante / courant mesure
        val counterUah = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        if (smoothedCurrentMa > 0 && counterUah > 0 && batteryLevel > 0) {
            val currentMah = counterUah / 1000.0
            val fullMah = currentMah * 100.0 / batteryLevel
            val remainingMah = fullMah - currentMah
            if (remainingMah > 0) return ceil(remainingMah / smoothedCurrentMa * 60.0).toInt()
        }
        return -1
    }

    fun releaseResources() {
        animator?.cancel()
        animator = null

        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
