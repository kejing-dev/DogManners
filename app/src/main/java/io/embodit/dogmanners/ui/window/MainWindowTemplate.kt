package io.embodit.dogmanners.ui.window

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.design.windows.TabBar
import com.pico.spatial.ui.design.windows.Toolbar
import com.pico.spatial.ui.foundation.geometry.DpOffset3D
import com.pico.spatial.ui.foundation.geometry.NormalizedPoint3D
import com.pico.spatial.ui.foundation.window.Augment
import com.pico.spatial.ui.foundation.window.AugmentContentAlignment
import com.pico.spatial.ui.platform.ViewPoint
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import com.pico.spatial.ui.platform.containers.SpatialNavigator
import kotlinx.coroutines.launch
import kotlin.system.exitProcess

/*
 * 主窗口模板（taste D8「合成一个窗口模板」+ D21 拖动交给系统 + D26 设置里退出应用）。
 * 用法：把这个文件整份拷进应用（改 package），在 mainApp 里声明一个 Planar WindowContainer，内容写
 *   MainWindow(appName = "小乌龟", pages = listOf(...), settings = { ...设置行... }, onExit = { 释放音频 / 追踪 / 锚点 })
 * 规矩：
 *  - 应用全部 2D 内容都在这一个系统窗口里；页面用系统 TabBar 切（TabBar 是系统挂在窗口外的独立小窗，模拟器上从正面看在窗口上方，不叠在窗口前后）。
 *  - 切页只用 SpatialUI 原生 TabBar（com.pico.spatial.ui.design.windows.TabBar，用户 2026-11-02「用原生 tab bar 组件」）；
 *    不要用 SegmentControl / 一排 Button / 自画 Tab 切页面，SegmentControl 只用来切同一页里的选项。
 *  - 「设置」固定是最后一页，最底下固定「退出应用」，在同一窗口里二次确认（不弹第二块面板）。
 *  - 页面自己的快捷操作放 page.toolbar：系统 Toolbar 出现在窗口底边下方。
 *  - 真要第二块内容，用 SideAugment（挂在窗口右侧，间距 56 dp）或系统多窗口 + Placement；不要在 Stage 里再挂一块面板。
 *  - 拖动 / 关闭 / 最小化是系统窗口控件（窗口下方的小圆点），这里不写任何拖动代码。
 *
 * 别盯着狗 v0.1.2 拷入（prompts/snippets/MainWindowTemplate.kt），结构没改，只加了四个可选参数，默认值 = 原模板行为：
 *  - current / onSelect：页面由应用的状态机决定（DogController 的 screen），点 TabBar 走应用事件，调试广播走同一条路；
 *  - settingsLabel：「设置」的中英文；
 *  - showPageTitle：页面自己画标题时关掉模板的 28 sp 标题；
 *  - settingsPage：应用自己的设置页（里面已有同窗二次确认的「退出应用」，走 exitApp 同一顺序）；
 *  - theme：应用自己的 PicoTheme 包装（DogTheme 带 Noto 字体和角色色），默认 PicoTheme。
 */

private const val TAG = "MainWindow"

/** One page of the main window. [icon]: a Material Symbols vector (taste D23); [toolbar]: this page's quick actions. */
class MainWindowPage(
    val id: String,
    val label: String,
    @DrawableRes val icon: Int,
    val toolbar: (@Composable RowScope.() -> Unit)? = null,
    val content: @Composable () -> Unit,
)

/**
 * The app's single main window. [pages] are the app's own pages (at most 6: the TabBar holds 7 and the last one is
 * Settings). [settings] are the app's settings rows (volume, recall, reset, about); the exit row is added by the
 * template. [onExit] releases the app's resources before the process ends.
 */
@Composable
fun MainWindow(
    appName: String,
    pages: List<MainWindowPage>,
    @DrawableRes settingsIcon: Int,
    settings: @Composable ColumnScope.() -> Unit,
    onExit: () -> Unit,
    startPage: String = pages.first().id,
    current: String? = null,
    onSelect: ((String) -> Unit)? = null,
    settingsLabel: String = "设置",
    showPageTitle: Boolean = true,
    settingsPage: (@Composable () -> Unit)? = null,
    theme: @Composable (@Composable () -> Unit) -> Unit = { content -> PicoTheme { content() } },
) {
    require(pages.size in 1..6) { "TabBar holds at most 7 items: 6 pages + Settings" }
    var own by rememberSaveable { mutableStateOf(startPage) }
    val selectedId = current ?: own
    val page = pages.firstOrNull { it.id == selectedId }
    fun select(id: String) {
        Log.i(TAG, "PAGE $id")
        if (onSelect != null) onSelect(id) else own = id
    }

    theme {
        Box(Modifier.fillMaxSize()) {
            if (page != null) {
                Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp)) {
                    if (showPageTitle) {
                        Text(page.label, fontSize = 28.sp)
                        Spacer(Modifier.height(16.dp))
                    }
                    Box(Modifier.fillMaxWidth().weight(1f)) { page.content() }
                }
            } else if (settingsPage != null) {
                Box(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp)) { settingsPage() }
            } else {
                SettingsPage(appName = appName, rows = settings, onExit = onExit)
            }
        }

        // system TabBar: its own small window outside this one (emulator 6.1.0, seen from the front: centred above the window)
        TabBar(followViewpoints = setOf(ViewPoint.Front, ViewPoint.Left)) {
            pages.forEach { p ->
                item(
                    selected = selectedId == p.id,
                    onClick = { select(p.id) },
                    mainContent = { Icon(painter = painterResource(p.icon), contentDescription = p.label) },
                    supportContent = { Text(p.label) },
                )
            }
            item(
                selected = selectedId == SETTINGS,
                onClick = { select(SETTINGS) },
                mainContent = { Icon(painter = painterResource(settingsIcon), contentDescription = settingsLabel) },
                supportContent = { Text(settingsLabel) },
            )
        }

        // system Toolbar under the window, only for pages that have quick actions
        page?.toolbar?.let { actions -> Toolbar { actions() } }
    }
}

/** Id of the template's settings page (the last TabBar item). */
const val SETTINGS_PAGE = "settings"

private const val SETTINGS = SETTINGS_PAGE

@Composable
private fun SettingsPage(appName: String, rows: @Composable ColumnScope.() -> Unit, onExit: () -> Unit) {
    val context = LocalContext.current
    val navigator = LocalSpatialNavigator.current
    val scope = rememberCoroutineScope()
    var confirming by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("设置", fontSize = 28.sp)
        rows()
        Spacer(Modifier.height(24.dp))
        // D26: the exit row is always last; the confirmation stays inside this window (no second panel)
        if (!confirming) {
            Button(onClick = { confirming = true }, modifier = Modifier.fillMaxWidth()) { Text("退出应用") }
        } else {
            Text("退出$appName？", fontSize = 22.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { confirming = false }) { Text("取消") }  // default colours: a tertiary fill disappears on the window glass
                Button(onClick = { scope.launch { exitApp(context, navigator, onExit) } }) { Text("退出") }
            }
        }
    }
}

/** D26 exit: release, close the Stage, remove every task, then end the process (same order as theremin v0.1.8). */
suspend fun exitApp(context: Context, navigator: SpatialNavigator, onExit: () -> Unit) {
    Log.i(TAG, "EXIT release + closeStage + finishAndRemoveTask + exitProcess")
    runCatching(onExit).onFailure { Log.e(TAG, "EXIT release failed", it) }
    runCatching { navigator.closeStage() }.onFailure { Log.w(TAG, "EXIT closeStage: $it") }
    runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.appTasks.forEach { runCatching { it.finishAndRemoveTask() } }
    }.onFailure { Log.e(TAG, "EXIT finishAndRemoveTask failed", it) }
    Handler(Looper.getMainLooper()).postDelayed({ exitProcess(0) }, 400)
}

/**
 * A second block of content beside the main window (D8: only Augment or a system window, never a panel in front of
 * or behind the window). Anchored to the window's right edge, top aligned, 56 dp gap (spatial-design_foundation_window).
 * Call it from inside MainWindow's page content.
 */
@Composable
fun SideAugment(width: Int = 360, content: @Composable () -> Unit) {
    Augment(
        anchor = NormalizedPoint3D.TopRight,
        alignment = AugmentContentAlignment.TopLeft,
        offset = DpOffset3D(56.dp, 0.dp, 0.dp),
    ) {
        PicoTheme { Box(Modifier.size(width.dp, 320.dp).padding(16.dp)) { content() } }
    }
}
