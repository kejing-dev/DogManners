package io.embodit.dogmanners.ui.scene

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.Color
import com.pico.spatial.core.container.SpatialViewContent
import com.pico.spatial.core.ecs.DirectionalLightComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ImageBasedLightSource
import com.pico.spatial.core.ecs.LoadType
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.SceneUpdateContext
import com.pico.spatial.core.ecs.StageEnvironmentLightingComponent
import com.pico.spatial.core.ecs.System
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector2
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.foundation.content.SpatialViewAttachments
import io.embodit.dogmanners.domain.Act
import io.embodit.dogmanners.domain.BodyInput
import io.embodit.dogmanners.domain.BodyReader
import io.embodit.dogmanners.domain.HeadPose
import io.embodit.dogmanners.domain.LevelGate
import io.embodit.dogmanners.domain.Lineup
import io.embodit.dogmanners.domain.Mode
import io.embodit.dogmanners.domain.Mood
import io.embodit.dogmanners.domain.PanelPlaces
import io.embodit.dogmanners.domain.ParkFrame
import io.embodit.dogmanners.domain.Phase
import io.embodit.dogmanners.domain.ParkModels
import io.embodit.dogmanners.domain.Prop
import io.embodit.dogmanners.domain.Quat4
import io.embodit.dogmanners.domain.Scenario
import io.embodit.dogmanners.domain.Snapshot
import io.embodit.dogmanners.domain.Stops
import io.embodit.dogmanners.domain.Vec3
import io.embodit.dogmanners.platform.DogLog
import io.embodit.dogmanners.platform.Sound
import io.embodit.dogmanners.platform.tracking.EyeTracker
import io.embodit.dogmanners.platform.tracking.HeadTracker
import io.embodit.dogmanners.ui.DogController
import io.embodit.dogmanners.ui.DogUiState
import io.embodit.dogmanners.ui.EyeState
import io.embodit.dogmanners.ui.Screen
import io.embodit.dogmanners.ui.components.PanelSizes
import io.embodit.dogmanners.ui.theme.DogColors
import io.embodit.dogmanners.ui.window.MainWindowStore
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The scene of the open Stage (debug hooks and the frame system talk to it). Main thread only. */
internal object ActiveScene {
    var current: ParkScene? = null
}

/** The one per-frame ECS system of the app (`registerSystem` in DogScreen). */
class ParkFrameSystem : System() {
    override fun update(context: SceneUpdateContext) {
        ActiveScene.current?.onFrame(context)
    }
}

object PanelIds {
    /** [打开面板] chip where P1 used to be (the main panel is the system window now). */
    const val CHIP = "chip"
    const val TAG = "p2"
    val pool = listOf(CHIP, TAG)
}

/**
 * The 3D side: the park (sky, grass, path, trees, bench, props), the dogs, the ground ring under your feet, the gaze
 * light and the three panels. Per-frame work happens in [onFrame] only: head / eyes -> [BodyReader] -> the running
 * [Scenario] (DogController.scenario) -> dog movement and clips. The panels hear about it through
 * DogController.onSnapshot, only when what they show changes. Main thread only.
 */
class ParkScene {
    val panelShown = mutableStateMapOf<String, Boolean>()
    val bindTick = mutableIntStateOf(0)

    private val main = Handler(Looper.getMainLooper())
    private var scope: CoroutineScope? = null
    private val head = HeadTracker()
    private val eyes = EyeTracker()
    val reader = BodyReader()
    private var content: SpatialViewContent? = null
    private var stageRoot: Entity? = null
    private var parkRoot: Entity? = null
    private var dogLayer: Entity? = null
    private var rig: PanelRig? = null
    private var shared: SharedResources? = null
    private var mats: ParkMaterials? = null
    private var buildJob: Job? = null
    private var built = false
    private var attached = false
    private var paused = false

    // props
    private val propEntities = HashMap<Prop, Entity>()
    private var leash: Entity? = null
    private var owner: Entity? = null

    // ground HUD and gaze light
    private var ring: Entity? = null
    private var sector: Entity? = null
    private var sectorMats: Triple<UnlitMaterial, UnlitMaterial, UnlitMaterial>? = null
    private var dot: Entity? = null
    private var label: Entity? = null
    private var labelMat: UnlitMaterial? = null
    private val labelCache = HashMap<String, TextureResource>()
    private var labelText = ""
    private var gazeLight: Entity? = null
    private var gazeLevel = 0f
    // lights (v0.1.3, B24): the park IBL, the sun (casts shadows); debug `light ...` changes them live
    private var envLight: StageEnvironmentLightingComponent? = null
    private var sunLight: DirectionalLightComponent? = null

    // dogs
    private val templates = HashMap<String, Entity>()
    private val loading = HashSet<String>()
    private var shelf: Entity? = null
    private val dogs = ArrayList<DogView>()
    private var sceneEpoch = -1
    private var scenario: Scenario? = null
    private var lastSnap: Snapshot? = null
    private var measured = false
    private var readyMs = -1L

    // head and placement
    private var pose = HeadPose(Vec3(0f, 1.6f, 0f), Quat4.IDENTITY)
    private var hasHead = false
    private var frame: ParkFrame? = null
    private var placeEpoch = -1
    private var placedAtMs = -1L
    private var resettled = false
    private var eyeM = ParkFrame.STAND_EYE_M
    private var frames = 0L
    private var lastLogMs = 0L
    private var lastFrameMs = 0L
    private var lastErrorMs = 0L
    private var startMs = 0L
    private var startupLogs = 0
    private var minNoseSinceLog = Float.MAX_VALUE
    private var lastWary = false
    private var freeGoneReported = false
    private var leaveStartMs = -1L

    /** Debug `view DEG`: turns the park (and its panels) by DEG at the next placement, like turning your head the other
     * way; the emulator camera cannot turn, so QA uses it to bring P1 (left) or P3 (right) into the picture. */
    var debugViewYawDeg = 0f

    /** Debug: F↑ test texture on the ground label (D10 direction check). */
    var fTest = false

    /** Debug: the eyes are treated as unavailable. */
    var eyeForcedOff: Boolean
        get() = eyes.forceOff
        set(v) {
            eyes.forceOff = v
        }

    // =========================================================================================== lifecycle

    fun attach(content: SpatialViewContent, attachments: SpatialViewAttachments) {
        if (attached || stageRoot != null) detach()
        startMs = SystemClock.elapsedRealtime()
        this.content = content
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val root = Entity().named("stageRoot")
        content.addEntity(root)
        // an entity added to the SpatialView is not at the Stage origin (Spatial Ruler): move the root once
        val offset = root.convertPositionFrom(Vector3.ZERO, null)
        val rotation = root.convertRotationFrom(Quat.identity(), null)
        root.components[TransformComponent::class.java]?.apply {
            setPosition(offset)
            setQuaternion(rotation)
        }
        stageRoot = root
        val rig = PanelRig(root) { id, shown -> panelShown[id] = shown }
        this.rig = rig
        rig.world(PanelIds.CHIP, PanelSizes.CHIP_W, PanelSizes.CHIP_H, 1f).maxPitch = 0f
        rig.world(PanelIds.TAG, PanelSizes.TAG_W, PanelSizes.TAG_H, 1f).maxPitch = 0f
        head.start()
        eyes.start()
        ActiveScene.current = this
        attached = true
        bindPanels(attachments)
        buildJob =
            scope?.launch {
                try {
                    build(root)
                } catch (t: Throwable) {
                    if (t !is CancellationException) DogLog.e("SCENE build failed: $t", t)
                    throw t
                }
            }
        DogLog.i("SCENE attached root offset=${DogLog.pos(offset.domain())}")
    }

    private suspend fun frameDelay() = delay(16)

    /** Ground, path, trees, bench, props, HUD; then the dog templates (async). One mesh per step (ANR rule). */
    private suspend fun build(root: Entity) {
        delay(200)
        val sh = SharedResources()
        shared = sh
        val m = ParkMaterials(sh)
        mats = m
        val park = Entity().named("park")
        root.addChild(park)
        parkRoot = park
        park.enabled = false
        // sky: an inside-out sphere around you, unlit gradient
        val sky = model(sh.keep(MeshResource.createSphere(SKY_R)), m.unlit(Color.White, m.texture(skyBitmap())), "sky", park) // gate:allow geometry 天空穹顶：包住人四周的内翻球，只画天空渐变，不是可见物件
        sky.setLocal(scale = Vec3(-1f, 1f, 1f))
        frameDelay()
        // v0.1.3 (B24): light = the park IBL (soft sky over a lawn, tools/make_ibl.py; the Stage's environment light, Full
        // Stage) + one warm sun that casts shadows. No fill light and no emission: the sky fills the shadows.
        val iblTex = runCatching { withContext(Dispatchers.IO) { TextureResource.load(IBL_ASSET, LoadType.FROM_ASSETS) } }
            .onFailure { DogLog.e("LIGHT ibl $IBL_ASSET failed: $it") }.getOrNull()
        if (iblTex != null) {
            sh.keep(iblTex)
            val env = StageEnvironmentLightingComponent(ImageBasedLightSource.Single(iblTex), IBL_EXP)
            root.addChild(Entity().named("parkIbl").also { it.components.set(env) })
            envLight = env
        }
        val sun = Entity().named("sun")
        val sunLight = DirectionalLightComponent(Color4(1f, 0.95f, 0.86f, 1f), SUN_LUX, true)
        sun.components.set(sunLight)
        this.sunLight = sunLight
        park.addChild(sun)
        sun.setLocal(rotation = Quat4.fromTo(Vec3(0f, 0f, -1f), SUN_DIR.normalized()))
        DogLog.i(lightLine())
        val sf = Entity().named("shelf")
        root.addChild(sf)
        sf.enabled = false
        shelf = sf
        // grass and path: painted, tileable textures (tools/make_park.py) on unit-UV tiles; plain grass beyond 20 m
        model(sh.keep(Shapes.disc("ground", 64)), m.pbr(DogColors.Grass, 0.95f), "ground", park).also { it.groundShadow(false, true) }.setLocal(rotation = Quat4.axisAngle(Vec3.RIGHT, -(PI / 2).toFloat()), scale = Vec3(60f, 60f, 1f))
        frameDelay()
        val grassTex = assetTexture("park/grass.jpg")
        val pathTex = assetTexture("park/path.jpg")
        val grass = if (grassTex != null) m.pbrTex(grassTex, 0.95f) else m.pbr(DogColors.Grass, 0.95f)
        model(sh.keep(tiles(-20f, 20f, -20f, 20f, 2.5f, "grassTiles")), grass, "grass", park).also { it.groundShadow(false, true) }.setLocal(position = Vec3(0f, 0.002f, 0f))
        frameDelay()
        val path = if (pathTex != null) m.pbrTex(pathTex, 0.9f) else m.pbr(DogColors.Path, 0.9f)
        model(sh.keep(tiles(-0.65f, 0.65f, -27f, 3f, 1.3f, "pathTiles")), path, "path", park).also { it.groundShadow(false, true) }.setLocal(position = Vec3(0f, 0.006f, 0f))
        frameDelay()
        val cube = sh.keep(Shapes.cube("cube"))
        val cyl = sh.keep(Shapes.cylinder("cyl"))
        val ball = sh.keep(Shapes.ball("ball"))
        val trunkMat = m.pbr(DogColors.Bark, 0.9f)
        // park models in the dog's style (Tripo, calibrated by tools/make_park.py: one root node, metres, face +Z)
        for (pm in ParkModels.ALL) {
            loadPropTemplate(pm)
            frameDelay()
        }
        // trees along both sides of the path (none on the dog's line), linden and maple in turn
        for ((i, t) in TREES.withIndex()) {
            val tr = Entity().named("tree$i")
            park.addChild(tr)
            tr.setLocal(position = Vec3(t.x, 0f, t.z), rotation = Quat4.axisAngle(Vec3.UP, i * 1.7f))
            val pm = if (i % 2 == 0) ParkModels.TREE_LINDEN else ParkModels.TREE_MAPLE
            if (propInstance(pm, tr, "tree$i") == null) primitiveTree(tr, i, t.y, cyl, ball, trunkMat, m)
            tr.groundShadow(true, false)
            frameDelay()
        }
        for ((i, b) in BUSHES.withIndex()) {
            val bu = Entity().named("bush$i")
            park.addChild(bu)
            bu.setLocal(position = Vec3(b.x, 0f, b.z), rotation = Quat4.axisAngle(Vec3.UP, b.y))
            if (propInstance(ParkModels.BUSH, bu, "bush$i") == null) bu.enabled = false else bu.groundShadow(true, false)
        }
        // bench on the left, behind the window's line of sight
        val bench = Entity().named("bench")
        park.addChild(bench)
        bench.setLocal(position = Vec3(-2.3f, 0f, -3.6f), rotation = Quat4.axisAngle(Vec3.UP, (PI / 2).toFloat()))
        if (propInstance(ParkModels.BENCH, bench, "bench") == null) {
            model(cube, trunkMat, "benchSeat", bench).setLocal(position = Vec3(0f, 0.45f, 0f), scale = Vec3(1.5f, 0.06f, 0.45f))
            model(cube, trunkMat, "benchBack", bench).setLocal(position = Vec3(0f, 0.75f, -0.2f), scale = Vec3(1.5f, 0.35f, 0.05f))
            for (sx in listOf(-0.65f, 0.65f)) model(cube, trunkMat, "benchLeg", bench).setLocal(position = Vec3(sx, 0.22f, 0f), scale = Vec3(0.07f, 0.45f, 0.4f))
        }
        bench.groundShadow(true, false)
        frameDelay()
        buildProps(park, m, cube, cyl, ball, trunkMat)
        frameDelay()
        buildHud(park, m)
        frameDelay()
        val layer = Entity().named("dogs")
        park.addChild(layer)
        dogLayer = layer
        built = true
        DogLog.i("SCENE built ms=${SystemClock.elapsedRealtime() - startMs}")
        // the dog every scene needs first; the rest load in the background
        loadTemplate(Lineup.MEDIUM.asset)
        loadTemplate(Lineup.SMALL.asset)
        loadTemplate(Lineup.LARGE.asset)
    }

    private fun buildProps(park: Entity, m: ParkMaterials, cube: MeshResource, cyl: MeshResource, ball: MeshResource, wood: com.pico.spatial.core.ecs.resource.Material) {
        // bowl (L3)
        val bowl = Entity().named("prop:bowl")
        park.addChild(bowl)
        if (propInstance(ParkModels.BOWL, bowl, "bowl") == null) {
            model(cyl, m.pbr(DogColors.Bowl, 0.35f), "bowlBody", bowl).setLocal(position = Vec3(0f, 0.035f, 0f), scale = Vec3(0.22f, 0.07f, 0.22f))
            model(cyl, m.pbr(DogColors.Bark, 0.8f), "bowlFood", bowl).setLocal(position = Vec3(0f, 0.071f, 0f), scale = Vec3(0.17f, 0.004f, 0.17f))
        }
        propEntities[Prop.BOWL] = bowl
        // dog bed (L4)
        val mat = Entity().named("prop:mat")
        park.addChild(mat)
        if (propInstance(ParkModels.BED, mat, "bed") == null) model(cube, m.pbr(DogColors.Mat, 0.95f), "matPad", mat).setLocal(position = Vec3(0f, 0.015f, 0f), scale = Vec3(1.15f, 0.03f, 1.15f))
        propEntities[Prop.MAT] = mat
        // fence across the path, 1.2 m in front of you (L6): two sections of the one fence model side by side
        val fence = Entity().named("prop:fence")
        park.addChild(fence)
        val half = ParkModels.FENCE.widthM / 2f
        val sections = listOf(-half, half).mapIndexed { k, x -> Entity().named("fence$k").also { fence.addChild(it); it.setLocal(position = Vec3(x, 0f, 0f)) } }
        if (sections.withIndex().any { (k, e) -> propInstance(ParkModels.FENCE, e, "fence$k") == null }) {
            for (e in sections) e.enabled = false
            for (i in -4..4) model(cube, wood, "post$i", fence).setLocal(position = Vec3(i * 0.42f, 0.5f, 0f), scale = Vec3(0.07f, 1.0f, 0.07f))
            for (y in listOf(0.35f, 0.8f)) model(cube, wood, "rail", fence).setLocal(position = Vec3(0f, y, 0.04f), scale = Vec3(3.5f, 0.07f, 0.03f))
        }
        propEntities[Prop.FENCE] = fence
        // owner (L2): a person in the dog's style standing still; the leash runs from her hand to the collar
        val o = Entity().named("prop:owner")
        park.addChild(o)
        if (propInstance(ParkModels.OWNER, o, "owner") == null) {
            val wm = m.pbr(DogColors.Mannequin, 0.6f)
            model(cyl, wm, "legL", o).setLocal(position = Vec3(-0.1f, 0.45f, 0f), scale = Vec3(0.11f, 0.9f, 0.11f))
            model(cyl, wm, "legR", o).setLocal(position = Vec3(0.1f, 0.45f, 0f), scale = Vec3(0.11f, 0.9f, 0.11f))
            model(ball, wm, "torso", o).setLocal(position = Vec3(0f, 1.28f, 0f), scale = Vec3(0.38f, 0.55f, 0.24f))
            model(ball, wm, "head", o).setLocal(position = Vec3(0f, 1.7f, 0f), scale = Vec3(0.2f, 0.25f, 0.21f))
        }
        owner = o
        propEntities[Prop.OWNER] = o
        leash = model(cube, m.pbr(DogColors.Leash, 0.6f), "leash", park).also { it.enabled = false }
        for (e in propEntities.values) e.groundShadow(true, false)
        for (e in propEntities.values) e.enabled = false
    }

    /** v0.1.1's primitive tree, only as the stand-in for a missing park model (logged `MODEL ... missing`). */
    private fun primitiveTree(tr: Entity, i: Int, h: Float, cyl: MeshResource, ball: MeshResource, trunkMat: com.pico.spatial.core.ecs.resource.Material, m: ParkMaterials) {
        model(cyl, trunkMat, "trunk$i", tr).setLocal(position = Vec3(0f, h * 0.35f, 0f), scale = Vec3(0.22f, h * 0.7f, 0.22f))
        model(ball, m.pbr(if (i % 2 == 0) DogColors.LeafA else DogColors.LeafB, 0.9f), "crown$i", tr).setLocal(position = Vec3(0f, h * 0.78f, 0f), scale = Vec3(h * 0.55f, h * 0.5f, h * 0.55f))
    }

    // ------------------------------------------------------------------------------------------- park models (D25)

    private val propTemplates = HashMap<String, Entity>()
    private val propInstances = ArrayList<Triple<String, Entity, Entity>>()

    private suspend fun loadPropTemplate(pm: ParkModels.Model) {
        if (propTemplates.containsKey(pm.asset)) return
        val assets = DogController.activity?.assets
        if (assets == null || runCatching { assets.open(pm.asset).close() }.isFailure) {
            DogLog.w("MODEL ${pm.asset} missing: primitive stand-in")
            return
        }
        val t0 = SystemClock.elapsedRealtime()
        val e = runCatching { Entity.loadSuspend("asset://${pm.asset}") }.onFailure { DogLog.e("MODEL ${pm.asset} load failed: $it") }.getOrNull() ?: return
        if (!attached) {
            runCatching { e.destroy(true) }
            return
        }
        shelf?.addChild(e.named("template:${pm.asset}"))
        propTemplates[pm.asset] = e
        DogLog.i("MODEL ${pm.asset} loaded in ${SystemClock.elapsedRealtime() - t0} ms")
    }

    /** A clone of the calibrated model under [parent] (only the parent is ever moved / turned); null if it is missing. */
    private fun propInstance(pm: ParkModels.Model, parent: Entity, name: String): Entity? {
        val t = propTemplates[pm.asset] ?: return null
        val c = runCatching { t.clone(Entity.CloneOptions(recursive = true, shouldShareMaterialInstance = true)) }.getOrNull() ?: return null
        parent.addChild(c.named("model:$name"))
        c.enabled = true
        propInstances += Triple("$name=${pm.asset}", c, parent)
        return c
    }

    /** INSTANCE line of one park model clone: its height in its parent's space and whether anything made it see-through. */
    private fun propLine(e: Entity, p: Entity): String {
        val b = runCatching { e.getVisualBounds(p) }.getOrNull()
        val opacity = e.components[com.pico.spatial.core.ecs.OpacityControllerComponent::class.java] != null || p.components[com.pico.spatial.core.ecs.OpacityControllerComponent::class.java] != null
        return String.format(Locale.US, "heightM=%.3f minY=%.3f opacityComponent=%s shown=%s", (b?.halfExtent?.y ?: 0f) * 2f, (b?.center?.y ?: 0f) - (b?.halfExtent?.y ?: 0f), opacity, p.enabled)
    }

    fun lightLine(): String =
        String.format(Locale.US, "LIGHT ibl=%s exp=%.1f sun=%.0f lux shadows=%s", envLight != null, envLight?.intensityExponent ?: 0f, sunLight?.intensity ?: 0f, sunLight?.castsShadowEnabled)

    /** Debug `light ibl EXP | sun LUX | shadows on|off`: tune the lights live (QA before / after shots). */
    fun debugLight(what: String, arg: String): String {
        when (what) {
            "ibl" -> arg.toFloatOrNull()?.let { envLight?.intensityExponent = it.coerceIn(-24f, 24f) }
            "sun" -> arg.toFloatOrNull()?.let { sunLight?.intensity = it.coerceAtLeast(0f) }
            "shadows" -> sunLight?.castsShadowEnabled = arg == "on"
        }
        return lightLine().also { DogLog.i(it) }
    }

    private fun assetTexture(path: String): TextureResource? {
        val a = DogController.activity?.assets ?: return null
        val bmp = runCatching { a.open(path).use { BitmapFactory.decodeStream(it) } }.onFailure { DogLog.w("TEXTURE $path: $it") }.getOrNull() ?: return null
        return mats?.texture(bmp)
    }

    /** Floor tiles x0..x1 × z0..z1 of [tile] metres, each carrying the whole texture (no reliance on UV wrapping). */
    private fun tiles(x0: Float, x1: Float, z0: Float, z1: Float, tile: Float, name: String): MeshResource {
        val nx = kotlin.math.ceil((x1 - x0) / tile - 1e-3f).toInt().coerceAtLeast(1)
        val nz = kotlin.math.ceil((z1 - z0) / tile - 1e-3f).toInt().coerceAtLeast(1)
        val b = MeshBuilder(nx * nz * 4)
        val n = Vec3.UP
        for (i in 0 until nx) for (j in 0 until nz) {
            val ax = x0 + i * tile
            val bx = minOf(x1, ax + tile)
            val az = z0 + j * tile
            val bz = minOf(z1, az + tile)
            val u1 = (bx - ax) / tile
            val v1 = (bz - az) / tile
            b.quad(
                b.vertex(Vec3(ax, 0f, bz), n, Vector2(0f, v1)),
                b.vertex(Vec3(bx, 0f, bz), n, Vector2(u1, v1)),
                b.vertex(Vec3(bx, 0f, az), n, Vector2(u1, 0f)),
                b.vertex(Vec3(ax, 0f, az), n, Vector2(0f, 0f)),
            )
        }
        return b.build(name)
    }

    private fun buildHud(park: Entity, m: ParkMaterials) {
        val sh = shared ?: return
        val ringEnt = model(sh.keep(Shapes.ring("hudRing", 0.94f)), m.sheer(Color.White, 0.55f), "hudRing", park)
        ringEnt.setLocal(rotation = Quat4.axisAngle(Vec3.RIGHT, -(PI / 2).toFloat()), scale = Vec3(0.9f, 0.9f, 1f))
        ring = ringEnt
        val amber = m.sheer(DogColors.Wary, 0.5f)
        val teal = m.sheer(DogColors.Relaxed, 0.5f)
        val grey = m.sheer(Color(0xFFB8BEC6), 0.4f) // design-style: fixed-figma-color neutral half-turned
        sectorMats = Triple(amber, teal, grey)
        sector = model(sh.keep(wedge(0.42f, 0.7f)), grey, "hudSector", park)
        dot = model(sh.keep(Shapes.disc("hudDot", 20)), m.sheer(Color.White, 0.9f), "hudDot", park)
        labelMat = m.sheer(Color.White, 0.95f)
        label = model(sh.keep(quad(0.32f, 0.11f)), labelMat!!, "hudLabel", park)
        gazeLight = model(sh.keep(Shapes.disc("gaze", 40)), m.sheer(DogColors.Gaze, 0.6f, m.texture(ParkMaterials.radial(128, 1.4))), "gazeLight", park).also { it.enabled = false }
    }

    /** A flat wedge (sector) of radius [r] and half-angle [half] radians in local XZ, pointing along -Z, on the floor. */
    private fun wedge(r: Float, half: Float, seg: Int = 16): MeshResource {
        val b = MeshBuilder(seg + 2)
        val n = Vec3.UP
        val c = b.vertex(Vec3.ZERO, n)
        for (i in 0..seg) {
            val a = -half + 2 * half * i / seg
            b.vertex(Vec3(-sin(a) * r, 0f, -cos(a) * r), n)
        }
        for (i in 1..seg) b.tri(c, i + 1, i)
        return b.build("wedge")
    }

    /** A unit-UV quad lying flat on the floor, text up = away from you (-Z), bottom towards you. */
    private fun quad(w: Float, h: Float): MeshResource {
        val b = MeshBuilder(4)
        val n = Vec3.UP
        b.quad(
            b.vertex(Vec3(-w / 2, 0f, h / 2), n, Vector2(0f, 1f)),
            b.vertex(Vec3(w / 2, 0f, h / 2), n, Vector2(1f, 1f)),
            b.vertex(Vec3(w / 2, 0f, -h / 2), n, Vector2(1f, 0f)),
            b.vertex(Vec3(-w / 2, 0f, -h / 2), n, Vector2(0f, 0f)),
        )
        return b.build("label")
    }

    private fun skyBitmap(): Bitmap {
        val w = 8
        val h = 256
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val top = android.graphics.Color.rgb(92, 140, 206)
        val mid = android.graphics.Color.rgb(141, 179, 224)
        val hor = android.graphics.Color.rgb(214, 228, 236)
        for (y in 0 until h) {
            val v = y / (h - 1f) // 0 = top of the sphere
            val c =
                if (v < 0.5f) lerpColor(top, mid, v / 0.5f)
                else if (v < 0.52f) lerpColor(mid, hor, (v - 0.5f) / 0.02f)
                else hor
            for (x in 0 until w) bmp.setPixel(x, y, c)
        }
        return bmp
    }

    private fun lerpColor(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        fun ch(s: Int) = (((a shr s) and 255) * (1 - k) + ((b shr s) and 255) * k).toInt()
        return android.graphics.Color.rgb(ch(16), ch(8), ch(0))
    }

    private fun loadTemplate(asset: String) {
        if (templates.containsKey(asset) || asset in loading) return
        loading += asset
        scope?.launch {
            val t0 = SystemClock.elapsedRealtime()
            val r = runCatching { Entity.loadSuspend("asset://$asset") }
            loading -= asset
            val e = r.getOrNull()
            if (e == null) {
                DogLog.e("MODEL $asset load failed: ${r.exceptionOrNull()}")
                return@launch
            }
            if (!attached) {
                runCatching { e.destroy(true) }
                return@launch
            }
            shelf?.addChild(e.named("template:$asset"))
            templates[asset] = e
            DogLog.i("MODEL $asset loaded in ${SystemClock.elapsedRealtime() - t0} ms")
        }
    }

    private fun cloneOf(asset: String): Entity? = templates[asset]?.clone(Entity.CloneOptions(recursive = true, shouldShareMaterialInstance = true))

    fun pause() {
        if (paused || !attached) return
        paused = true
        head.stop()
        eyes.stop()
        DogController.soundOrNull?.pause()
        DogLog.i("SCENE paused")
    }

    fun resume() {
        if (!paused || !attached) return
        paused = false
        head.start()
        eyes.start()
        lastFrameMs = 0L
        DogController.soundOrNull?.resume()
        DogLog.i("SCENE resumed")
    }

    fun bindPanels(attachments: SpatialViewAttachments) {
        val rig = rig ?: return
        if (!rig.bind(attachments, PanelIds.pool, SystemClock.elapsedRealtime())) {
            main.removeCallbacks(rebind)
            main.postDelayed(rebind, 250L)
        }
    }

    private val rebind = Runnable { if (stageRoot != null) bindTick.intValue++ }

    fun detach() {
        if (!attached && stageRoot == null) return
        attached = false
        if (ActiveScene.current === this) ActiveScene.current = null
        main.removeCallbacksAndMessages(null)
        buildJob?.cancel()
        scope?.cancel()
        scope = null
        head.stop()
        eyes.stop()
        panelShown.clear()
        for (d in dogs) d.destroy()
        dogs.clear()
        templates.clear()
        runCatching { rig?.release() }
        runCatching { stageRoot?.destroy(true) }
        labelCache.values.forEach { runCatching { it.close() } }
        labelCache.clear()
        shared?.close()
        shared = null
        mats = null
        stageRoot = null
        parkRoot = null
        rig = null
        built = false
        DogLog.i("SCENE detached")
    }

    // =========================================================================================== per frame

    fun onFrame(context: SceneUpdateContext) {
        val root = stageRoot ?: return
        if (root.scene == null || context.scene != root.scene) return
        val now = SystemClock.elapsedRealtime()
        val dt = if (lastFrameMs == 0L) 0f else ((now - lastFrameMs) / 1000f).coerceIn(0f, MAX_DT)
        lastFrameMs = now
        frames++
        head.pose()?.let {
            // a first frame of all zeros + identity is "not tracking yet" (hand-sign teacher lesson)
            val zero = it.position.length < 1e-3f && abs(it.rotation.w) > 0.99999f
            if (!zero) {
                pose = it
                hasHead = true
            }
        }
        val rig = rig ?: return
        rig.onFrame(now)
        // D9: a window open waits for a level head; meanwhile 「平视前方」 sits on your line of sight (chip slot)
        val pitchDeg = if (hasHead && !head.lost(now)) Math.toDegrees(pose.pitch.toDouble()).toFloat() else null
        MainWindowStore.stepGate(now, pitchDeg)
        if (MainWindowStore.levelHint) showAt(PanelIds.CHIP, LevelGate.hintPosition(pose))
        rig.stowBehind(pose)
        if (!attached || paused) return
        val ui = DogController.state.value
        if (built) {
            try {
                stepPlacement(ui, now)
                if (frame != null) {
                    stepScenario(ui, dt, now)
                    stepSound(ui, now)
                    stepHud(ui, dt)
                    placePanels(ui)
                }
            } catch (t: Throwable) {
                // an engine call failing for one frame (runtime restarting) must not take the app down
                if (now - lastErrorMs > 2000) {
                    lastErrorMs = now
                    DogLog.e("FRAME step failed: $t", t)
                }
            }
        }
        rig.faceViewer(pose.position)
        stepLogs(ui, now)
    }

    private fun stepPlacement(ui: DogUiState, now: Long) {
        val wantEye = if (ui.seated) ParkFrame.SIT_EYE_M else ParkFrame.STAND_EYE_M
        val epochChanged = ui.placeEpoch != placeEpoch
        // after the first placement, re-centre once at 2 s (tracking settles), then stay put until 回到面前
        val resettle = !resettled && placedAtMs > 0 && now - placedAtMs > RESETTLE_MS
        if (!epochChanged && !resettle) return
        if (!hasHead && now - startMs < HEAD_WAIT_MS) return
        if (resettle) resettled = true
        placeEpoch = ui.placeEpoch
        eyeM = wantEye
        val f0 = ParkFrame.around(pose, eyeM, -PanelPlaces.P1_YAW_DEG)
        val f = if (debugViewYawDeg != 0f) ParkFrame(f0.origin, f0.yaw + Math.toRadians(debugViewYawDeg.toDouble()).toFloat()) else f0
        frame = f
        parkRoot?.let {
            it.setLocal(position = f.origin, rotation = Quat4.axisAngle(Vec3.UP, f.yaw))
            it.enabled = true
        }
        reader.resetBaseline()
        if (placedAtMs < 0) placedAtMs = now
        // the panels are placed from this frame on (placePanels uses the frame)
        rig?.get(PanelIds.CHIP)?.facePoint = f.toStage(Vec3(0f, eyeM, 0f))
        DogLog.i(String.format(Locale.US, "PLACE epoch=%d head=%s yaw=%.1f pitch=%.1f eye=%.2f origin=%s hasHead=%s", placeEpoch, DogLog.pos(pose.position), Math.toDegrees(f.yaw.toDouble()), Math.toDegrees(pose.pitch.toDouble()), eyeM, DogLog.pos(f.origin), hasHead))
    }

    private fun headLocal(): Pair<Vec3, Vec3> {
        val f = frame ?: return Vec3(0f, eyeM, 0f) to Vec3.AHEAD
        val h = f.toLocal(pose.position)
        // debug `lean M`: the head itself moves M towards the dog (a real lean), so BodyReader measures the lean and
        // the nose distance shrinks the same way as on a headset (QA K4: `lean 0.4` now reaches S16)
        val d = dogs.firstOrNull()
        val pushed =
            if (headPushM != 0f && d != null) {
                val dir = Vec3(d.position.x - h.x, 0f, d.position.z - h.z)
                if (dir.length > 1e-3f) h + dir.normalized() * headPushM else h
            } else h
        return pushed to f.dirToLocal(pose.forward).normalized()
    }

    /** Debug `lean M` (metres towards the dog, applied to the head position before everything else reads it). */
    var headPushM = 0f

    private fun stepScenario(ui: DogUiState, dt: Float, now: Long) {
        val layer = dogLayer ?: return
        val sc = DogController.scenario
        if (ui.sceneEpoch != sceneEpoch || sc !== scenario) {
            if (!rebuildDogs(sc, layer)) return
            sceneEpoch = ui.sceneEpoch
            scenario = sc
            lastSnap = null
            freeGoneReported = false
            leaveStartMs = -1L
            reader.reset()
        }
        val main = dogs.firstOrNull() ?: return
        if (!main.hasModel) return
        if (!measured && now - (readyMs.takeIf { it > 0 } ?: now.also { readyMs = it }) > MEASURE_AFTER_MS) {
            measured = main.measure()
            if (measured) {
                DogController.onSceneReady()
                logInstances()
            }
        }
        val (h, f) = headLocal()
        val eye = eyeRayLocal(now)
        reportEye(eye != null, now)
        val input = if (ui.screen == Screen.LESSON || ui.screen == Screen.FREE) reader.read(dt, h, f, eye, main.headCentre()) else BodyInput.CALM.also { reader.read(dt, h, f, eye, main.headCentre()) }
        val nose = BodyReader.flat(main.nose(), h)
        val snap = sc.step(dt, input, nose)
        if (snap != lastSnap) {
            lastSnap = snap
            DogController.onSnapshot(snap)
        }
        val wary = sc.act == Act.WARY
        if (wary != lastWary) {
            lastWary = wary
            DogLog.i(String.format(Locale.US, "DOG mood=%s act=%s P=%.2f nose=%.2f facing=%.0f judged=%.0f lenient=%d stare=%s lean=%.2f", sc.mood, sc.act, sc.pressure, nose, input.facingDeg, sc.judged.facingDeg, sc.leniency.level, input.staring, input.leanM))
        }
        moveDogs(sc, dt, h, now)
        // gaze light: warm pool over the dog's head while your gaze (or the head fallback) is on its face
        val on = reader.gazeOnFace && (ui.screen == Screen.LESSON || ui.screen == Screen.FREE)
        gazeLevel = (gazeLevel + (if (on) dt * 3f else -dt * 2f)).coerceIn(0f, 1f)
        gazeLight?.let { g ->
            g.enabled = gazeLevel > 0.02f
            val c = main.headCentre()
            val k = 0.32f * main.lineup.factor.coerceAtLeast(0.6f) * (0.6f + 0.4f * gazeLevel)
            g.setLocal(position = Vec3(c.x, c.y + main.size.earM * 0.25f, c.z), rotation = Quat4.axisAngle(Vec3.RIGHT, -(PI / 2).toFloat()), scale = Vec3(k, k, 1f))
        }
    }

    private fun eyeRayLocal(now: Long): Pair<Vec3, Vec3>? {
        val f = frame ?: return null
        val e = eyes.pose(now) ?: return null
        return f.toLocal(e.position) to f.dirToLocal(e.forward).normalized()
    }

    private var lastEyeReport: EyeState? = null

    private fun reportEye(using: Boolean, now: Long) {
        val st =
            if (using) EyeState.ON
            else when (eyes.unavailableReason(now)) {
                "off" -> EyeState.FORCED_OFF
                "permission" -> if (DogController.state.value.eye == EyeState.DENIED) EyeState.DENIED else EyeState.NO_PERMISSION
                "unsupported" -> EyeState.UNSUPPORTED
                else -> if (DogController.state.value.eye == EyeState.DENIED) EyeState.DENIED else if (eyes.supportState() == null) EyeState.UNSUPPORTED else EyeState.NO_PERMISSION
            }
        if (st == lastEyeReport) return
        lastEyeReport = st
        DogController.onEyeSource(st)
    }

    /** New scenario: dogs (clones of the cached templates, D25 calibrated) and props. False while a template loads. */
    private fun rebuildDogs(sc: Scenario, layer: Entity): Boolean {
        for (r in sc.roles) {
            if (!templates.containsKey(r.lineup.asset)) {
                loadTemplate(r.lineup.asset)
                return false
            }
        }
        for (d in dogs) d.destroy()
        dogs.clear()
        for ((i, r) in sc.roles.withIndex()) {
            val v = DogView("$i-${r.lineup.name.lowercase(Locale.US)}", r.lineup, layer)
            val clone = cloneOf(r.lineup.asset) ?: return false
            v.attach(clone)
            v.position = Vec3(r.offsetX, 0f, -(r.startNoseM + v.size.noseZ))
            v.yaw = Math.toRadians(r.yawDeg.toDouble()).toFloat()
            v.apply()
            v.play(v.clipFor(if (i == 0) sc.act else Act.WAIT, sc.mood))
            dogs += v
        }
        // props
        for ((p, e) in propEntities) e.enabled = p in sc.props
        val mainDog = dogs.first()
        propEntities[Prop.BOWL]?.setLocal(position = mainDog.nose().let { Vec3(it.x + 0.08f, 0f, it.z) })
        // the bed (0.16 m) sits 0.07 m into the grass, so the sleeping dog (L4) lies in its hollow, not inside the cushion
        propEntities[Prop.MAT]?.setLocal(position = Vec3(mainDog.position.x, -0.07f, mainDog.position.z - 0.05f))
        propEntities[Prop.FENCE]?.setLocal(position = Vec3(0f, 0f, -FENCE_M))
        // owner on the dog's far side from the signal tag (P2 hangs at your right of the dog)
        // turned 0.9 rad towards the dog: the leash leaves her right hand (ParkModels.OWNER_HAND) in front of her
        owner?.setLocal(position = Vec3(mainDog.position.x - 0.7f, 0f, mainDog.position.z - 0.35f), rotation = Quat4.axisAngle(Vec3.UP, 0.9f))
        leash?.enabled = Prop.OWNER in sc.props
        DogLog.i("SCENE dogs=${dogs.map { it.name }} props=${sc.props} mode=${sc.mode}")
        return true
    }

    /** Walks / backs off the main dog to the scenario's nose distance; 0.8 m hard minimum every frame (D14). */
    private fun moveDogs(sc: Scenario, dt: Float, h: Vec3, now: Long) {
        val d = dogs.firstOrNull() ?: return
        val toHead = Vec3(h.x - d.position.x, 0f, h.z - d.position.z)
        val faceYaw = atan2(toHead.x, toHead.z)
        var moving = false
        val nose = BodyReader.flat(d.nose(), h)
        when (sc.act) {
            Act.LEAVE -> {
                // turn round and trot off down the path, fading out past 8 m
                d.yaw = turnTowards(d.yaw, PI.toFloat(), dt * 3f)
                if (abs(wrap(d.yaw - PI.toFloat())) < 0.6f) d.position = d.position + Vec3(0f, 0f, -Stops.WALK_MPS * 1.4f * dt)
                moving = true
                if (leaveStartMs < 0) leaveStartMs = now
                val gone = nose > Stops.LEAVE_M
                d.setFade(if (gone) 0f else 1f)
                if (gone && sc.mode is Mode.Free && !freeGoneReported) {
                    freeGoneReported = true
                    DogController.onFreeDogGone()
                }
            }
            Act.SIT, Act.SLEEP, Act.SHOW, Act.EAT -> {
                d.yaw = Math.toRadians(sc.roles[0].yawDeg.toDouble()).toFloat()
            }
            else -> {
                val target = sc.targetM
                d.yaw = turnTowards(d.yaw, faceYaw, dt * 4f)
                if (target != null && abs(nose - target) > 0.02f) {
                    val dir = toHead.normalized()
                    val step = (if (nose > target) 1f else -1f) * minOf(abs(nose - target), sc.speed * dt)
                    d.position = d.position + dir * step
                    moving = step > 0f
                }
                // L5: mom steps in front of her pup
                if (sc.momShift > 0f) d.position = Vec3(d.position.x + (sc.momShift - d.position.x) * (dt * 2f).coerceAtMost(1f), 0f, d.position.z)
            }
        }
        // hard minimum: the nose never comes within 0.8 m of your head
        val n2 = BodyReader.flat(d.nose(), h)
        if (n2 < Stops.HARD_MIN_M && sc.act != Act.LEAVE) {
            val away = Vec3(d.position.x - h.x, 0f, d.position.z - h.z).normalized()
            d.position = d.position + away * (Stops.HARD_MIN_M - n2 + 0.005f)
        }
        val n3 = BodyReader.flat(d.nose(), h)
        if (n3 < minNoseSinceLog) minNoseSinceLog = n3
        if (n3 < 0.85f) DogLog.i(String.format(Locale.US, "DIST near nose=%.3f act=%s", n3, sc.act))
        // D14: your head inside the dog -> see-through
        if (sc.act != Act.LEAVE) d.setFade(if (headInside(d, h)) 0.3f else 1f)
        dogMoving = moving && sc.act != Act.WARY
        d.play(d.clipFor(sc.act, sc.mood), dogMoving)
        d.apply()
        // second dog (pup) keeps still next to mom
        for (i in 1 until dogs.size) {
            val p = dogs[i]
            p.play(p.clipFor(if (sc.act == Act.WARY) Act.WAIT else Act.WAIT, Mood.RELAXED))
            p.apply()
        }
        // leash from the owner's hand to the dog's neck (L2)
        val l = leash
        if (l != null && l.enabled) {
            val o = owner ?: return
            val t = o.transformOrNull ?: return
            val hand = Vec3(t.position.x, t.position.y, t.position.z) + t.quaternion.domain().rotate(ParkModels.OWNER_HAND)
            val neck = d.position + Quat4.axisAngle(Vec3.UP, d.yaw).rotate(Vec3(0f, d.size.withersM * 1.0f, d.size.noseZ * 0.55f))
            l.bar(hand, neck, 0.012f)
        }
    }

    private var dogMoving = false

    /** The mix follows the dog you meet: distance and side relative to where you look (Sound.DogCue). */
    private fun stepSound(ui: DogUiState, now: Long) {
        val snd = DogController.soundOrNull ?: return
        val d = dogs.firstOrNull()
        val inScene = ui.screen == Screen.LESSON || ui.screen == Screen.FREE || ui.screen == Screen.GUIDE
        if (d == null || !d.hasModel || !inScene || paused) {
            snd.tick(now, null)
            return
        }
        val (h, f) = headLocal()
        val to = Vec3(d.position.x - h.x, 0f, d.position.z - h.z)
        val right = Vec3(-f.z, 0f, f.x)
        val pan = if (to.length > 1e-3f && right.length > 1e-3f) to.normalized().dot(right.normalized()) else 0f
        val sc = DogController.scenario
        snd.tick(now, Sound.DogCue(sc.act, dogMoving, BodyReader.flat(d.nose(), h), pan, Prop.FENCE in sc.props))
    }

    private fun headInside(d: DogView, h: Vec3): Boolean {
        val tail = d.position + Quat4.axisAngle(Vec3.UP, d.yaw).rotate(Vec3(0f, d.size.withersM * 0.8f, d.size.noseZ - d.size.lengthM))
        val nose = d.nose()
        val ab = nose - tail
        val t = ((h - tail).dot(ab) / ab.dot(ab)).coerceIn(0f, 1f)
        return (tail + ab * t - h).length < 0.4f
    }

    private fun stepHud(ui: DogUiState, dt: Float) {
        val (h, f) = headLocal()
        val d = dogs.firstOrNull()
        val show = d != null && (ui.screen == Screen.LESSON || ui.screen == Screen.FREE)
        ring?.enabled = show
        sector?.enabled = show
        dot?.enabled = show
        label?.enabled = show
        if (!show || d == null) return
        val floorAt = Vec3(h.x, 0.012f, h.z)
        ring?.setLocal(position = floorAt)
        val yawFace = atan2(-f.x, -f.z)
        sector?.setLocal(position = floorAt + Vec3(0f, 0.002f, 0f), rotation = Quat4.axisAngle(Vec3.UP, yawFace))
        val toDog = Vec3(d.position.x - h.x, 0f, d.position.z - h.z)
        val dir = if (toDog.length > 1e-3f) toDog.normalized() else Vec3.AHEAD
        val facing = BodyReader.angleDeg(Vec3(f.x, 0f, f.z), toDog)
        sectorMats?.let { (amber, teal, grey) -> sector?.setMaterial(if (facing <= BodyInput.FACE_ON_DEG) amber else if (facing >= BodyInput.SIDE_ON_DEG) teal else grey) }
        dot?.setLocal(position = floorAt + dir * 0.405f + Vec3(0f, 0.003f, 0f), rotation = Quat4.axisAngle(Vec3.RIGHT, -(PI / 2).toFloat()), scale = Vec3(0.05f, 0.05f, 1f))
        // distance label at the ring's edge towards the dog, text bottom towards you, reading left to right
        val dirYaw = atan2(-dir.x, -dir.z)
        // debug F↑ check: the same label, rotation and texture path, moved 3.2 m out and ×3 so the emulator's level camera sees it
        if (fTest) label?.setLocal(position = floorAt + dir * 3.2f + Vec3(0f, 0.004f, 0f), rotation = Quat4.axisAngle(Vec3.UP, dirYaw), scale = Vec3(3f, 1f, 3f))
        else label?.setLocal(position = floorAt + dir * 0.62f + Vec3(0f, 0.004f, 0f), rotation = Quat4.axisAngle(Vec3.UP, dirYaw), scale = Vec3(1f, 1f, 1f))
        val nose = BodyReader.flat(d.nose(), h)
        val text = if (fTest) "F↑" else if (nose > 7.5f) "" else String.format(Locale.US, "%.1f m", nose)
        if (text != labelText) {
            // one texture per distinct label, kept (≤ ~70 strings); creation can fail while the runtime restarts
            val tex = labelCache[text] ?: runCatching {
                TextureResource.create(ParkMaterials.flipV(textBitmap(text))).also { it.toGlobal() }
            }.onFailure { DogLog.w("HUD label texture failed: $it") }.getOrNull()?.also { labelCache[text] = it }
            if (tex != null) {
                labelText = text
                labelMat?.setBaseColorTexture(tex)
            }
        }
    }

    private fun textBitmap(text: String): Bitmap {
        val bmp = Bitmap.createBitmap(384, 128, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 84f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            setShadowLayer(6f, 0f, 0f, android.graphics.Color.argb(200, 0, 0, 0))
        }
        c.drawText(text, 192f, 96f, p)
        return bmp
    }

    // =========================================================================================== panels

    private fun placePanels(ui: DogUiState) {
        val f = frame ?: return
        val rig = rig ?: return
        // the main window is a system window (placed by the system in front of you); only its reopen chip lives here,
        // where P1 used to be, and only while the window is closed (×)
        if (MainWindowStore.levelHint) {
            // the chip's slot carries 「平视前方」 (placed every frame in onFrame)
        } else if (!MainWindowStore.isOpen && MainWindowStore.state != "never" && !MainWindowStore.gate.waiting) {
            showAt(PanelIds.CHIP, f.toStage(PanelPlaces.local(PanelPlaces.P1_YAW_DEG, PanelPlaces.CHIP_DIST_M, PanelPlaces.CHIP_DOWN_DEG, eyeM)))
        } else {
            rig[PanelIds.CHIP]?.hide()
        }
        val d = dogs.firstOrNull()
        val tagWanted = ui.tagOn && d != null && d.hasModel && (ui.screen == Screen.LESSON || ui.screen == Screen.FREE || ui.screen == Screen.GUIDE) && (scenario?.act != Act.LEAVE)
        if (tagWanted && d != null) {
            val (h, _) = headLocal()
            val toDog = Vec3(d.position.x - h.x, 0f, d.position.z - h.z).normalized()
            val right = Vec3(-toDog.z, 0f, toDog.x) // your right when you face the dog
            val bodyMid = d.position + Quat4.axisAngle(Vec3.UP, d.yaw).rotate(Vec3(0f, 0f, d.size.noseZ * 0.35f))
            // QA K2: the tag keeps the size it has at 1.3 m as the dog stands further off (x up to 1.6 at 2.1 m), and
            // moves out by the extra half width so it never covers the dog
            val k = PanelPlaces.p2Scale(BodyReader.flat(bodyMid, h))
            val side = PanelPlaces.P2_SIDE_M + 0.15f * (d.lineup.factor - 1f).coerceAtLeast(0f) + (k - 1f) * PanelPlaces.P2_HALF_W_M
            val at = bodyMid + right * side
            showAt(PanelIds.TAG, f.toStage(Vec3(at.x, PanelPlaces.P2_HEIGHT_M + 0.2f * (d.lineup.factor - 1f).coerceAtLeast(0f), at.z)), k)
        } else {
            rig[PanelIds.TAG]?.hide()
        }
    }

    private fun showAt(id: String, at: Vec3, k: Float? = null) {
        val slot = rig?.get(id) ?: return
        if (slot.shown) slot.moveTo(at, k) else slot.show(at, k, k)
    }

    // =========================================================================================== logs / debug

    private fun stepLogs(ui: DogUiState, now: Long) {
        val since = now - startMs
        if (startupLogs < 3 && since > STARTUP_LOG_MS[startupLogs]) {
            startupLogs++
            DogLog.i("STARTUP t=${since / 1000}s " + where())
        }
        if (now - lastLogMs > LOG_MS) {
            lastLogMs = now
            val sc = scenario
            DogLog.i(
                String.format(
                    Locale.US,
                    "FRAME n=%d state=%s act=%s mood=%s P=%.2f minNose=%.3f leave=%.1f eye=%s %s",
                    frames, ui.designState, sc?.act, sc?.mood, sc?.pressure ?: 0f, if (minNoseSinceLog == Float.MAX_VALUE) -1f else minNoseSinceLog, sc?.brain?.leaveTimer ?: 0f, ui.eye, poseLine(),
                )
            )
            minNoseSinceLog = Float.MAX_VALUE
        }
    }

    /** Where the panels and the dog are, relative to your head (startup logs at 1 / 3 / 5 s, debug `where`). */
    fun where(): String {
        val f = frame
        val sb = StringBuilder()
        sb.append("head=").append(DogLog.pos(pose.position)).append(" hasHead=").append(hasHead).append(" placed=").append(f != null)
        for (id in PanelIds.pool) {
            val s = rig?.get(id) ?: continue
            val p = s.position
            val rel = p - pose.position
            sb.append(String.format(Locale.US, " %s(shown=%s pos=%s dist=%.2f yawRel=%.0f°)", id, s.shown, DogLog.pos(p), rel.length, Math.toDegrees(atan2(-rel.x, -rel.z) - pose.yaw(0f).toDouble())))
        }
        val d = dogs.firstOrNull()
        if (d != null && f != null) {
            val w = f.toStage(d.position)
            val rel = w - pose.position
            sb.append(String.format(Locale.US, " dog(model=%s stage=%s dist=%.2f nose=%.2f)", d.hasModel, DogLog.pos(w), hypot(rel.x, rel.z), BodyReader.flat(d.nose(), headLocal().first)))
        }
        return sb.toString()
    }

    fun poseLine(): String {
        val (h, fw) = headLocal()
        return String.format(Locale.US, "head=%s local=%s fwd=%s pitch=%.0f", DogLog.pos(pose.position), DogLog.pos(h), DogLog.pos(fw), Math.toDegrees(pose.pitch.toDouble()))
    }

    fun panelLines(): List<String> = rig?.dump() ?: emptyList()

    /** D24 instance list: every 3D dog, its file, its world box height, opacity component (ui-audit). */
    fun logInstances(): List<String> {
        val out = ArrayList<String>()
        for (d in dogs) {
            val b = d.boundsInRoot()
            out += String.format(Locale.US, "INSTANCE dog=%s file=%s heightM=%.3f opacityComponent=%s clip=%s", d.name, d.lineup.asset, (b?.second?.y ?: 0f) * 2f, d.hasOpacityComponent, d.clipNow)
        }
        // the park models: one calibration in the file (D25), so every instance of a model has the same height
        for ((name, e, p) in propInstances) out += "INSTANCE prop=$name " + propLine(e, p)
        out.forEach { DogLog.i(it) }
        return out
    }

    fun mainNose(): Float {
        val d = dogs.firstOrNull() ?: return -1f
        return BodyReader.flat(d.nose(), headLocal().first)
    }

    /** Debug `dist M`: puts the main dog's nose at M metres (then the scenario carries on from there). */
    fun teleportNose(m: Float) {
        val d = dogs.firstOrNull() ?: return
        val (h, _) = headLocal()
        val dir = Vec3(d.position.x - h.x, 0f, d.position.z - h.z).normalized()
        val cur = BodyReader.flat(d.nose(), h)
        d.position = d.position + dir * (m - cur)
        d.apply()
    }

    companion object {
        private const val MAX_DT = 0.1f
        private const val LOG_MS = 2000L
        private val STARTUP_LOG_MS = longArrayOf(1000L, 3000L, 5000L)
        private const val HEAD_WAIT_MS = 4000L
        private const val RESETTLE_MS = 2000L
        private const val MEASURE_AFTER_MS = 1500L
        private const val SKY_R = 45f
        private const val SUN_LUX = 6000f
        /** Sun from behind-left of the path, 53° high: the dog walking up the path is lit three-quarter, not flat. */
        private val SUN_DIR = Vec3(-0.35f, -0.8f, -0.45f)
        private const val IBL_ASSET = "park/park_ibl.ktx"
        /** park_ibl.ktx is relative (sky ~1): 2^9 puts the sky light at ~1/6 of the sun, a clear summer day. */
        private const val IBL_EXP = 9f
        const val FENCE_M = 1.2f

        /** Trees: x, height, z (park-local, metres); none within 1.6 m of the path's centre line. */
        /** Bushes: x, yaw (radians), z; along the path's edges, never on the dog's line or between you and the window. */
        private val BUSHES = listOf(Vec3(-1.7f, 0.4f, -5.2f), Vec3(1.8f, 1.9f, -6.4f), Vec3(-1.9f, 3.1f, -9.5f), Vec3(2.0f, 0.9f, -12f), Vec3(-2.1f, 2.4f, -15f), Vec3(1.9f, 5.0f, -17.5f), Vec3(3.6f, 1.2f, -3.2f), Vec3(-4.2f, 4.4f, -6.8f))

        private val TREES = listOf(Vec3(-2.6f, 4.2f, -6f), Vec3(2.8f, 4.8f, -7.5f), Vec3(-3.2f, 5.2f, -11f), Vec3(3.4f, 4.4f, -13f), Vec3(-2.4f, 4.6f, -17f), Vec3(2.6f, 5.0f, -19f), Vec3(-5.5f, 5.5f, -3f), Vec3(5.8f, 5.0f, -2.5f), Vec3(-6f, 4.8f, 3.5f), Vec3(6.2f, 5.2f, 4f), Vec3(0.5f, 5.5f, 7f))

        fun wrap(a: Float): Float {
            var d = a
            while (d > PI) d -= (2 * PI).toFloat()
            while (d < -PI) d += (2 * PI).toFloat()
            return d
        }

        fun turnTowards(cur: Float, target: Float, maxStep: Float): Float {
            val d = wrap(target - cur)
            return if (abs(d) <= maxStep) target else cur + Math.signum(d) * maxStep
        }
    }
}
