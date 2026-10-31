package io.embodit.dogmanners.domain

/**
 * D25: the one place where the external dog model is calibrated. Numbers in FILE UNITS come from `tools/make_dog.py`
 * (written to `tools/dog_metrics.json`, measured on the skinned straight-head standing pose): Y up, head towards +Z,
 * the dog's left = +X. The calibration node puts the floor point between the four paws on the origin, keeps the face
 * on +Z (the app's dog-forward) and scales to true size. Walking, turning and the stops only move the PARENT of that
 * node; nothing writes the model's own transform.
 *
 * Scale: the medium dog is scaled so that the ear tips stand at 0.58 m (design dogAsset.calibration check value). The
 * design's other rule (nose-to-tail-root 0.72 m) cannot hold at the same time on this file: the model is short in the
 * body (0.72 m length would put the ears at 0.75 m, outside the ±3 cm check), see deviations. The other dogs keep the
 * design's shoulder-height ratios to the medium dog (lineup 0.57 / 0.39 / 0.27 / 0.18).
 */
object DogCalibration {
    // ---- file units (tools/dog_metrics.json)
    const val FILE_FLOOR_Y = -0.038f
    const val FILE_EAR_TIP_Y = 85.528f
    const val FILE_ORIGIN_X = -0.021f
    const val FILE_ORIGIN_Z = -4.669f
    const val FILE_NOSE_TIP_Z = 60.990f
    const val FILE_TAIL_ROOT_Z = -20.575f
    const val FILE_WITHERS_Y = 65.041f
    const val FILE_NOSE_Y = 66.005f
    const val FILE_HEAD_Y = 69.895f
    const val FILE_HEAD_Z = 35.076f

    const val TARGET_EAR_M = 0.58f
    const val EAR_TOLERANCE_M = 0.03f

    /** Metres per file unit for the medium dog (scale of the calibration node, before the lineup factor). */
    const val MEDIUM_SCALE = TARGET_EAR_M / (FILE_EAR_TIP_Y - FILE_FLOOR_Y)

    /** Offset of the calibration node, file units: floor point between the paws -> origin. */
    val fileOffset: Vec3 = Vec3(-FILE_ORIGIN_X, -FILE_FLOOR_Y, -FILE_ORIGIN_Z)

    /** Rotation of the calibration node: the file already looks along +Z with +Y up. */
    val fileRotation: Quat4 = Quat4.IDENTITY

    /** App convention: a dog at yaw 0 faces +Z (towards you, who stand at +Z of it on the path). */
    val dogForward: Vec3 = Vec3(0f, 0f, 1f)

    /** Size facts of a dog of [lineup] at true size, metres from its calibration origin. */
    data class Size(val scale: Float, val earM: Float, val withersM: Float, val noseZ: Float, val noseY: Float, val headZ: Float, val headY: Float, val lengthM: Float)

    fun size(lineup: Lineup, renderFactor: Float = 1f): Size {
        val s = MEDIUM_SCALE * lineup.factor / renderFactor
        val k = MEDIUM_SCALE * lineup.factor
        return Size(
            scale = s,
            earM = (FILE_EAR_TIP_Y - FILE_FLOOR_Y) * k,
            withersM = (FILE_WITHERS_Y - FILE_FLOOR_Y) * k,
            noseZ = (FILE_NOSE_TIP_Z - FILE_ORIGIN_Z) * k,
            noseY = (FILE_NOSE_Y - FILE_FLOOR_Y) * k,
            // the stare sphere sits between the eyes: 60 % of the way from the head joint to the nose
            headZ = ((FILE_HEAD_Z + 0.6f * (FILE_NOSE_TIP_Z - 4f - FILE_HEAD_Z)) - FILE_ORIGIN_Z) * k,
            headY = ((FILE_HEAD_Y + 0.6f * (FILE_NOSE_Y - FILE_HEAD_Y)) - FILE_FLOOR_Y) * k,
            lengthM = (FILE_NOSE_TIP_Z - FILE_TAIL_ROOT_Z) * k,
        )
    }

    /**
     * The engine renders a skinned file in its own units (LifeSize: the shiba renders 1 : 1 in file units, the T. rex
     * at 1 : 100). The scene measures the rendered height of the standing pose once and divides by the file height.
     */
    fun renderFactor(measuredHeightFileSpace: Float): Float {
        val fileH = FILE_EAR_TIP_Y - FILE_FLOOR_Y
        if (!measuredHeightFileSpace.isFinite() || measuredHeightFileSpace <= 1e-6f) return 1f
        val k = measuredHeightFileSpace / fileH
        // only accept clean powers of ten (unit mix-ups), else trust the file
        for (p in listOf(0.01f, 0.1f, 1f, 10f, 100f)) if (k in p * 0.8f..p * 1.25f) return p
        return 1f
    }
}

/** Design dogAsset.lineup: one base model, four coats / sizes. */
enum class Lineup(val coat: String, val shoulderM: Float) {
    LARGE("black_tan", 0.57f),
    MEDIUM("red", 0.39f),
    SMALL("cream", 0.27f),
    PUPPY("red", 0.18f);

    /** Uniform scale relative to the medium dog. */
    val factor: Float
        get() = shoulderM / MEDIUM.shoulderM

    val asset: String
        get() = "dogs/dog_$coat.glb"
}
