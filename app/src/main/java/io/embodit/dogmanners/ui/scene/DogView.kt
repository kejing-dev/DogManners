package io.embodit.dogmanners.ui.scene

import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.OpacityControllerComponent
import com.pico.spatial.core.ecs.animation.AnimationPlayConfig
import com.pico.spatial.core.ecs.resource.AnimationResource
import io.embodit.dogmanners.domain.Act
import io.embodit.dogmanners.domain.DogCalibration
import io.embodit.dogmanners.domain.Lineup
import io.embodit.dogmanners.domain.Mood
import io.embodit.dogmanners.domain.Quat4
import io.embodit.dogmanners.domain.Vec3
import io.embodit.dogmanners.platform.DogLog
import java.util.Locale

/**
 * One dog in the park. Entity tree (D25):
 *
 *     root    park-local: the floor point between its paws, turned by [yaw] (yaw 0 = facing +Z, towards you)
 *     |- blob soft dark disc on the ground under it
 *     `- calib   the ONE calibration node: file offset (paws -> origin), file rotation (identity: the file already looks
 *        |       along +Z, Y up), uniform scale (DogCalibration.size(lineup).scale); never touched after [attach]
 *        `- model  the GLB clone (its own transform is never written)
 *
 * Moving, turning and backing off only write [root]. The clip comes from [act] / [mood]; a change cross-fades in 0.4 s
 * (AnimationPlayConfig, design dogAsset.runtime).
 */
internal class DogView(val name: String, val lineup: Lineup, parent: Entity, private val blobMaker: (Entity) -> Entity?) {
    val root: Entity = Entity().named("dog:$name")
    private val calib: Entity = Entity().named("calib:$name")
    private var model: Entity? = null
    private var animEntity: Entity? = null
    private val clips = HashMap<String, AnimationResource>()
    private var playing: String? = null
    var renderFactor = 1f
        private set
    var size = DogCalibration.size(lineup)
        private set

    /** Park-local floor position of the root and its heading (radians, 0 = facing +Z). */
    var position: Vec3 = Vec3.ZERO
    var yaw: Float = 0f
    private var opacity = 1f

    init {
        parent.addChild(root)
        root.addChild(calib)
        blobMaker(root)
        applyCalibration()
    }

    val hasModel: Boolean
        get() = model != null

    private fun applyCalibration() {
        size = DogCalibration.size(lineup, renderFactor)
        val s = size.scale
        val o = DogCalibration.fileOffset
        calib.setLocal(position = Vec3(o.x * s, o.y * s, o.z * s), rotation = DogCalibration.fileRotation, scale = Vec3(s, s, s))
    }

    /** The template clone joins the calibration node. */
    fun attach(clone: Entity) {
        if (model != null) return
        calib.addChild(clone)
        clone.enabled = true
        model = clone
        val skinned = runCatching { clone.findSkinnedMeshEntity() }.getOrNull()?.firstOrNull()
        val carrier = skinned ?: clone
        val list = runCatching { carrier.getAnimationResources() }.getOrNull()?.toList().orEmpty()
        animEntity = carrier
        for (c in list) {
            val n = runCatching { c.getName() }.getOrDefault("")
            clips[n.substringAfterLast('|')] = c
        }
        DogLog.i("DOG $name lineup=$lineup clips=${clips.keys.sorted()} scale=${fmt(size.scale)}")
    }

    /**
     * D25 check: the rendered height of the model in the calibration node's space, compared to the file height. Called
     * once the standing clip has posed it. Logs ear height and length for QA.
     */
    fun measure(): Boolean {
        val m = model ?: return false
        val b = runCatching { m.getVisualBounds(calib) }.getOrNull() ?: return false
        val h = b.halfExtent.y * 2f
        val k = DogCalibration.renderFactor(h)
        if (k != renderFactor) {
            renderFactor = k
            applyCalibration()
        }
        val world = runCatching { m.getVisualBounds(root) }.getOrNull()
        DogLog.i(
            String.format(
                Locale.US,
                "CALIB dog=%s lineup=%s fileH=%.2f renderFactor=%.3f scale=%.6f earM=%.3f withersM=%.3f lengthM=%.3f noseZ=%.3f boxRoot=c(%.3f,%.3f,%.3f) h(%.3f,%.3f,%.3f)",
                name, lineup, h, renderFactor, size.scale, size.earM, size.withersM, size.lengthM, size.noseZ,
                world?.center?.x ?: 0f, world?.center?.y ?: 0f, world?.center?.z ?: 0f,
                world?.halfExtent?.x ?: 0f, world?.halfExtent?.y ?: 0f, world?.halfExtent?.z ?: 0f,
            )
        )
        return true
    }

    /** Box of the model in the root's space (debug / instance list). */
    fun boundsInRoot(): Pair<Vec3, Vec3>? {
        val m = model ?: return null
        val b = runCatching { m.getVisualBounds(root) }.getOrNull() ?: return null
        return Vec3(b.center.x, b.center.y, b.center.z) to Vec3(b.halfExtent.x, b.halfExtent.y, b.halfExtent.z)
    }

    fun clipFor(act: Act, mood: Mood): String =
        when (act) {
            Act.SIT -> "sit"
            Act.APPROACH, Act.LEAVE -> "walk"
            Act.WAIT, Act.HELD -> "relaxed"
            Act.SNIFF -> "sniff"
            Act.PLAY -> "play"
            Act.WARY -> "wary"
            Act.SHAKE -> "shake"
            Act.EAT -> "eat"
            Act.SLEEP -> "sleep"
            Act.SHOW -> when (mood) { Mood.WARY -> "wary"; Mood.RELAXED -> "relaxed"; Mood.PLAY -> "play" }
        }

    /** Plays [clip] (looped unless it is the shake) with a 0.4 s cross-fade; no-op when it already plays. */
    fun play(clip: String, moving: Boolean = false) {
        val name2 = if (moving && clip != "walk" && clip != "wary") "walk" else clip
        if (name2 == playing) return
        val e = animEntity ?: return
        val res = clips[name2] ?: clips["relaxed"] ?: return
        val r = if (name2 == "shake") res else res.repeat(LOOPS)
        val ok = runCatching { e.playAnimation(r, AnimationPlayConfig(transitionDuration = FADE_S)) }
        if (ok.isFailure) DogLog.w("DOG $name play $name2 failed: ${ok.exceptionOrNull()}")
        playing = name2
        DogLog.i("ANIM dog=$name clip=$name2")
    }

    val clipNow: String?
        get() = playing

    fun apply() {
        root.setLocal(position = position, rotation = Quat4.axisAngle(Vec3.UP, yaw))
    }

    /** D14: see-through (<= 30 %) while your head is inside it; at 1 the component is removed (D24 lesson). */
    fun setFade(v: Float) {
        if (v == opacity) return
        opacity = v
        if (v >= 0.999f) {
            runCatching { root.components.remove(OpacityControllerComponent::class.java) }
        } else {
            root.setOpacity(v)
        }
    }

    val hasOpacityComponent: Boolean
        get() = root.components[OpacityControllerComponent::class.java] != null

    /** Nose tip, park-local. */
    fun nose(): Vec3 = position + Quat4.axisAngle(Vec3.UP, yaw).rotate(Vec3(0f, size.noseY, size.noseZ))

    /** Centre of the stare sphere (between the eyes), park-local. */
    fun headCentre(): Vec3 = position + Quat4.axisAngle(Vec3.UP, yaw).rotate(Vec3(0f, size.headY, size.headZ))

    fun destroy() {
        runCatching { animEntity?.stopAllAnimations() }
        runCatching { root.destroy(true) }
        model = null
    }

    companion object {
        const val LOOPS = 100_000
        const val FADE_S = 0.4f

        fun fmt(v: Float) = String.format(Locale.US, "%.5f", v)
    }
}
