package com.kguard.app.live

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.kguard.app.data.model.AgeProfile
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.max

/**
 * On-device sexual-content check for K-Guard Live, the same model and preprocessing Trawl (Crane AI Labs) uses:
 * Marqo/nsfw-image-detection-384 (Apache-2.0), a ViT-tiny/16 at 384 px exported to ONNX (fp32, 22 MB) by Trawl's
 * scripts/export_private_model.py. It runs on ONNX Runtime's CPU provider; no frame leaves the phone.
 *
 * The model ships in the APK assets (`models/nsfw-384/`, with its licence) and is copied once to no-backup storage so
 * ONNX Runtime can map it from a file. Loaded on first use, released by [close].
 */
class NsfwScreenModel(private val context: Context) {
    private val lock = Any()
    private var session: OrtSession? = null
    private val pixels = FloatArray(3 * SIZE * SIZE)

    /** P(sexual content) of one screen, 0..1 (label-smoothed: never above about 0.96). Blocking; call off the main thread. */
    fun score(frame: Bitmap): Float = synchronized(lock) {
        val argb = squash(frame)
        normalise(argb, pixels)
        val s = session ?: open().also { session = it }
        val env = OrtEnvironment.getEnvironment()
        OnnxTensor.createTensor(env, FloatBuffer.wrap(pixels), longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong())).use { input ->
            s.run(mapOf(INPUT to input)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val logits = (result.get(0).value as Array<FloatArray>)[0]
                probability(logits[0], logits[1])
            }
        }
    }

    fun close() = synchronized(lock) {
        session?.close()
        session = null
    }

    /** The whole screen squashed to 384×384 (no crop, so nothing at the edge goes unseen), halving first so it is averaged. */
    private fun squash(frame: Bitmap): IntArray {
        var current = frame
        while (current.width / 2 >= SIZE && current.height / 2 >= SIZE) {
            val next = Bitmap.createScaledBitmap(current, current.width / 2, current.height / 2, true)
            if (current !== frame) current.recycle()
            current = next
        }
        val square = Bitmap.createScaledBitmap(current, SIZE, SIZE, true)
        if (current !== frame) current.recycle()
        val argb = IntArray(SIZE * SIZE)
        square.getPixels(argb, 0, SIZE, 0, 0, SIZE, SIZE)
        if (square !== frame) square.recycle()
        return argb
    }

    private fun open(): OrtSession {
        val t0 = System.nanoTime()
        val file = modelFile()
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
            setInterOpNumThreads(1)
            setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            addConfigEntry("session.intra_op.allow_spinning", "0")
            // One fixed input size: no arena or memory-pattern plan to reuse (Trawl measured ~25 MB less native memory).
            setCPUArenaAllocator(false)
            setMemoryPatternOptimization(false)
        }
        return OrtEnvironment.getEnvironment().createSession(file.absolutePath, options).also {
            Log.i(TAG, "Loaded the on-device screen model in ${(System.nanoTime() - t0) / 1_000_000} ms")
        }
    }

    /** Copies the asset to no-backup storage once (and again if the APK ships a different size). */
    private fun modelFile(): File {
        val out = File(context.noBackupFilesDir, "models/$ASSET")
        val expected = context.assets.openFd(ASSET).use { it.length }
        if (out.isFile && out.length() == expected) return out
        out.parentFile?.mkdirs()
        val tmp = File(out.parentFile, "${out.name}.tmp")
        context.assets.open(ASSET).use { input -> tmp.outputStream().use { input.copyTo(it) } }
        check(tmp.renameTo(out)) { "could not install the screen model" }
        return out
    }

    companion object {
        private const val TAG = "KGuardLiveModel"
        const val ASSET = "models/nsfw-384/nsfw-384.onnx"
        const val SIZE = 384
        private const val INPUT = "pixel_values"

        /** Channels-first RGB, `x / 127.5 - 1` (mean and deviation 0.5), as the model was evaluated. */
        fun normalise(argb: IntArray, out: FloatArray) {
            val plane = SIZE * SIZE
            for (i in 0 until plane) {
                val p = argb[i]
                out[i] = (p shr 16 and 0xFF) / 127.5f - 1f
                out[plane + i] = (p shr 8 and 0xFF) / 127.5f - 1f
                out[2 * plane + i] = (p and 0xFF) / 127.5f - 1f
            }
        }

        /** P(NSFW) from the two logits (index 0 NSFW, index 1 SFW). */
        fun probability(nsfwLogit: Float, sfwLogit: Float): Float {
            val m = max(nsfwLogit, sfwLogit)
            val a = exp((nsfwLogit - m).toDouble())
            val b = exp((sfwLogit - m).toDouble())
            return (a / (a + b)).toFloat()
        }

        /**
         * What a score means for this age group. Trawl measured (on photos) that 0.90 flags ~0.1% of ordinary face photos
         * and about one swimwear photo in six, and 0.94 keeps ordinary photos of women and men at or under ~1%; the model
         * never scores above ~0.96. Younger children get the stricter bar.
         * ponytail: thresholds come from Trawl's photo measurements; screenshots (UI chrome around the picture) score
         * lower, so re-measure on real screen captures before tuning.
         */
        fun verdictFor(p: Float, age: AgeProfile): ScreenVerdict.Level = when (age) {
            AgeProfile.KIDS_3_7, AgeProfile.PRETEENS_8_12 ->
                if (p >= 0.90f) ScreenVerdict.Level.HARMFUL else ScreenVerdict.Level.SAFE
            AgeProfile.TEENS_13_17 -> when {
                p >= 0.94f -> ScreenVerdict.Level.HARMFUL
                p >= 0.90f -> ScreenVerdict.Level.CAUTION
                else -> ScreenVerdict.Level.SAFE
            }
            AgeProfile.ADULTS_18_PLUS -> if (p >= 0.94f) ScreenVerdict.Level.CAUTION else ScreenVerdict.Level.SAFE
        }
    }
}
