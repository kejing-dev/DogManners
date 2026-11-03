package io.embodit.dogmanners.ui.scene

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import com.pico.spatial.core.ecs.BoundingBox
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.OpacityControllerComponent
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshModel
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import com.pico.spatial.core.ecs.resource.Resource
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector2
import com.pico.spatial.core.math.Vector3
import io.embodit.dogmanners.domain.Quat4
import io.embodit.dogmanners.domain.Vec3
import io.embodit.dogmanners.platform.DogLog
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

// ---------------------------------------------------------------------------------------------
// domain <-> SDK types. Every entity hangs below the stage root, whose local space equals Stage space.
// ---------------------------------------------------------------------------------------------

internal fun Vec3.sdk(): Vector3 = Vector3(x, y, z)

internal fun Vector3.domain(): Vec3 = Vec3(x, y, z)

internal fun Quat4.sdk(): Quat = Quat(x, y, z, w)

internal fun Quat.domain(): Quat4 = Quat4(x, y, z, w)

internal fun Color.color4(): Color4 = Color4(red, green, blue, alpha)

internal val Entity.transformOrNull: TransformComponent?
    get() = components[TransformComponent::class.java]

internal fun Entity.setLocal(position: Vec3? = null, rotation: Quat4? = null, scale: Vec3? = null) {
    val t = transformOrNull ?: return
    if (position != null) t.setPosition(position.sdk())
    if (rotation != null) t.setQuaternion(rotation.sdk())
    if (scale != null) t.setScaleVector(scale.sdk())
}

internal fun Entity.setMaterial(material: Material) {
    components[ModelComponent::class.java]?.materials?.set(0, material)
}

/** Overall opacity of an entity and its children. */
internal fun Entity.setOpacity(opacity: Float) {
    val existing = components[OpacityControllerComponent::class.java]
    if (existing != null) existing.opacity = opacity.coerceIn(0f, 1f) else components.set(OpacityControllerComponent(opacity.coerceIn(0f, 1f)))
}

internal fun Entity.named(name: String): Entity = also { runCatching { it.setName(name) } }

/** Metres per dp of an attachment panel at scale 1 (Spatial Ruler feasibility spike). */
internal const val DP_M = 0.0008f

/**
 * Resources shared by entities that come and go. The SDK releases a resource when its last user is
 * destroyed (Spatial Ruler), so shared ones are made global and closed when the scene goes away.
 */
internal class SharedResources {
    private val owned = ArrayList<Resource>()

    fun <T : Resource> keep(resource: T): T {
        resource.toGlobal()
        owned += resource
        return resource
    }

    fun close() {
        owned.forEach { runCatching { it.close() } }
        owned.clear()
    }
}

/** Materials of the park: PBR for the props (lit by the sun and the fill), Unlit for the sky and the overlays. */
internal class ParkMaterials(private val shared: SharedResources) {
    fun pbr(color: Color, roughness: Float = 0.85f): PhysicallyBasedMaterial =
        shared.keep(
            PhysicallyBasedMaterial.create(BlendingMode.OPAQUE).apply {
                setBaseColor(color.copy(alpha = 1f).color4())
                setMetallic(0f)
                setRoughness(roughness)
                // Full Stage has no sky light: a share of the base colour as emission stands in for ambient light
                runCatching { setEmissiveColor(Color4(color.red * AMBIENT, color.green * AMBIENT, color.blue * AMBIENT, 1f)) }
                setCullingMode(MaterialCullingMode.NONE)
            }
        )

    /** Textured PBR (the painted grass / path): the texture as base colour and, at the ambient share, as emission. */
    fun pbrTex(texture: TextureResource, roughness: Float = 0.9f): PhysicallyBasedMaterial =
        shared.keep(
            PhysicallyBasedMaterial.create(BlendingMode.OPAQUE).apply {
                setBaseColor(Color4(1f, 1f, 1f, 1f))
                setBaseColorTexture(texture)
                setMetallic(0f)
                setRoughness(roughness)
                runCatching {
                    setEmissiveColor(Color4(AMBIENT, AMBIENT, AMBIENT, 1f))
                    setEmissiveTexture(texture)
                }
                setCullingMode(MaterialCullingMode.NONE)
            }
        )

    fun unlit(color: Color, texture: TextureResource? = null): UnlitMaterial =
        shared.keep(
            UnlitMaterial.create(BlendingMode.OPAQUE).apply {
                setBaseColor(color.copy(alpha = 1f).color4())
                if (texture != null) setBaseColorTexture(texture)
                runCatching { setApplyToneMapping(false) }
                setCullingMode(MaterialCullingMode.NONE)
            }
        )

    fun sheer(color: Color, opacity: Float, texture: TextureResource? = null): UnlitMaterial =
        shared.keep(
            UnlitMaterial.create(BlendingMode.TRANSPARENT).apply {
                setBaseColor(color.copy(alpha = 1f).color4())
                if (texture != null) setBaseColorTexture(texture)
                setOpacity(opacity)
                runCatching { setApplyToneMapping(false) }
                setCullingMode(MaterialCullingMode.NONE)
                setDepthWrite(false)
            }
        )

    fun texture(bitmap: Bitmap): TextureResource = shared.keep(TextureResource.create(flipV(bitmap)))

    companion object {
        private const val AMBIENT = 0.38f

        /** White disc fading to transparent towards the rim (gaze light, shadow blobs). */
        fun radial(size: Int = 128, power: Double = 1.6): Bitmap {
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = (size - 1) / 2f
            for (y in 0 until size) for (x in 0 until size) {
                val r = kotlin.math.hypot(x - c, y - c) / c
                val a = if (r >= 1f) 0f else Math.pow((1.0 - r), power).toFloat()
                bmp.setPixel(x, y, android.graphics.Color.argb((a * 255).toInt(), 255, 255, 255))
            }
            return bmp
        }

        /** The engine's texture V runs from the bottom row up (Sky Dome QA q1-2): flip once before upload. */
        fun flipV(src: Bitmap): Bitmap {
            val m = android.graphics.Matrix().apply { preScale(1f, -1f) }
            return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, false)
        }
    }
}

/** Accumulates triangles and builds (or refills) a mesh. */
internal class MeshBuilder(capacity: Int = 64) {
    val positions = ArrayList<Vector3>(capacity)
    val normals = ArrayList<Vector3>(capacity)
    val uvs = ArrayList<Vector2>(capacity)
    val indices = ArrayList<Int>(capacity * 3 / 2)
    private var minX = Float.MAX_VALUE
    private var minY = Float.MAX_VALUE
    private var minZ = Float.MAX_VALUE
    private var maxX = -Float.MAX_VALUE
    private var maxY = -Float.MAX_VALUE
    private var maxZ = -Float.MAX_VALUE

    val vertexCount: Int
        get() = positions.size

    fun vertex(p: Vec3, n: Vec3, uv: Vector2 = ZERO_UV): Int {
        positions += Vector3(p.x, p.y, p.z)
        normals += Vector3(n.x, n.y, n.z)
        uvs += uv
        if (p.x < minX) minX = p.x
        if (p.y < minY) minY = p.y
        if (p.z < minZ) minZ = p.z
        if (p.x > maxX) maxX = p.x
        if (p.y > maxY) maxY = p.y
        if (p.z > maxZ) maxZ = p.z
        return positions.size - 1
    }

    fun tri(a: Int, b: Int, c: Int) {
        indices += a
        indices += b
        indices += c
    }

    fun quad(a: Int, b: Int, c: Int, d: Int) {
        tri(a, b, c)
        tri(a, c, d)
    }

    fun bounds(): BoundingBox =
        if (positions.isEmpty()) BoundingBox(center = Vector3(0f, 0f, 0f), halfExtent = Vector3(0.01f, 0.01f, 0.01f))
        else BoundingBox(center = Vector3((minX + maxX) / 2, (minY + maxY) / 2, (minZ + maxZ) / 2), halfExtent = Vector3(max(0.001f, (maxX - minX) / 2), max(0.001f, (maxY - minY) / 2), max(0.001f, (maxZ - minZ) / 2)))

    /** A degenerate triangle keeps an emptied mesh valid. */
    private fun ensureNotEmpty() {
        if (positions.isNotEmpty()) return
        val n = Vec3(0f, 0f, 1f)
        tri(vertex(Vec3.ZERO, n), vertex(Vec3(0f, 0.0001f, 0f), n), vertex(Vec3(0.0001f, 0f, 0f), n))
    }

    fun model(): MeshModel {
        ensureNotEmpty()
        return MeshModel(positions = positions, triangleIndices = indices, normals = normals, uv0 = uvs)
    }

    fun build(name: String): MeshResource = MeshResource.createWithMeshModel(model = model(), bounds = bounds(), name = name)

    /** Refills [mesh]; returns false when the engine refused (the caller keeps the old one). */
    fun replace(mesh: MeshResource): Boolean =
        try {
            val m = model()
            mesh.replaceWithMeshModel(m, bounds())
        } catch (t: Throwable) {
            DogLog.e("SCENE mesh replace failed: $t", t)
            false
        }

    companion object {
        val ZERO_UV = Vector2(0.5f, 0.5f)
    }
}

/** Programmatic meshes, built once per scene. */
internal object Shapes {
    /** A flat disc of radius 0.5 in local XY facing +Z. */
    fun disc(name: String, seg: Int = 40): MeshResource {
        val b = MeshBuilder(seg + 2)
        val n = Vec3(0f, 0f, 1f)
        // UVs map the disc onto the whole texture (the gaze pool's radial fade)
        val c = b.vertex(Vec3.ZERO, n, Vector2(0.5f, 0.5f))
        for (i in 0..seg) {
            val a = 2 * PI * i / seg
            b.vertex(Vec3((0.5 * cos(a)).toFloat(), (0.5 * sin(a)).toFloat(), 0f), n, Vector2((0.5 + 0.5 * cos(a)).toFloat(), (0.5 - 0.5 * sin(a)).toFloat()))
        }
        for (i in 1..seg) b.tri(c, i, i + 1)
        return b.build(name)
    }

    /** A flat ring (annulus) between radii [inner] and 0.5 in local XY facing +Z. */
    fun ring(name: String, inner: Float, seg: Int = 48): MeshResource {
        val b = MeshBuilder(seg * 2 + 2)
        val n = Vec3(0f, 0f, 1f)
        for (i in 0..seg) {
            val a = 2 * PI * i / seg
            b.vertex(Vec3((inner * cos(a)).toFloat(), (inner * sin(a)).toFloat(), 0f), n)
            b.vertex(Vec3((0.5 * cos(a)).toFloat(), (0.5 * sin(a)).toFloat(), 0f), n)
        }
        for (i in 0 until seg) b.quad(i * 2, i * 2 + 1, i * 2 + 3, i * 2 + 2)
        return b.build(name)
    }

    /** A unit cube centred on the origin. */
    fun cube(name: String): MeshResource = MeshResource.createBox(Vector3(1f, 1f, 1f), 0f)

    /** A sphere of diameter 1 (scaled per star). */
    fun ball(name: String): MeshResource = MeshResource.createSphere(0.5f)

    /** A cylinder of diameter 1 and height 1 about local Y (the dial disc; built here so its size is exact). */
    fun cylinder(name: String, seg: Int = 48): MeshResource {
        val b = MeshBuilder(seg * 4 + 2)
        val up = Vec3(0f, 1f, 0f)
        val down = Vec3(0f, -1f, 0f)
        val top = b.vertex(Vec3(0f, 0.5f, 0f), up)
        val bottom = b.vertex(Vec3(0f, -0.5f, 0f), down)
        for (i in 0 until seg) {
            val a0 = 2 * PI * i / seg
            val a1 = 2 * PI * (i + 1) / seg
            val p0 = Vec3((0.5 * cos(a0)).toFloat(), 0f, (0.5 * sin(a0)).toFloat())
            val p1 = Vec3((0.5 * cos(a1)).toFloat(), 0f, (0.5 * sin(a1)).toFloat())
            val h = Vec3(0f, 0.5f, 0f)
            b.tri(top, b.vertex(p1 + h, up), b.vertex(p0 + h, up))
            b.tri(bottom, b.vertex(p0 - h, down), b.vertex(p1 - h, down))
            val n = p0.normalized()
            b.quad(b.vertex(p0 - h, n), b.vertex(p1 - h, n), b.vertex(p1 + h, n), b.vertex(p0 + h, n))
        }
        return b.build(name)
    }
}

/** Places a unit-cube entity as a thin bar between two points. */
internal fun Entity.bar(from: Vec3, to: Vec3, thickness: Float) {
    val d = to - from
    val len = d.length
    if (len < 1e-5f) {
        setLocal(scale = Vec3(1e-4f, 1e-4f, 1e-4f))
        return
    }
    val dir = d / len
    setLocal(position = (from + to) * 0.5f, rotation = Quat4.fromTo(Vec3(0f, 0f, 1f), dir), scale = Vec3(thickness, thickness, len))
}

internal fun model(mesh: MeshResource, material: Material, name: String, parent: Entity): ModelEntity =
    ModelEntity(mesh, material).also {
        runCatching { it.setName(name) }
        parent.addChild(it)
    }
