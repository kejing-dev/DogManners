package io.embodit.dogmanners.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.SegmentControl
import com.pico.spatial.ui.design.SegmentControlDefaults
import com.pico.spatial.ui.design.SegmentItem
import com.pico.spatial.ui.design.Switch
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import io.embodit.dogmanners.BuildConfig
import io.embodit.dogmanners.R
import io.embodit.dogmanners.domain.Coach
import io.embodit.dogmanners.domain.Goal
import io.embodit.dogmanners.domain.LessonId
import io.embodit.dogmanners.domain.Mood
import io.embodit.dogmanners.domain.NearCause
import io.embodit.dogmanners.domain.NearMiss
import io.embodit.dogmanners.domain.Outcome
import io.embodit.dogmanners.domain.Personality
import io.embodit.dogmanners.domain.Phase
import io.embodit.dogmanners.domain.ResultText
import io.embodit.dogmanners.ui.DogUiState
import io.embodit.dogmanners.ui.EyeState
import io.embodit.dogmanners.ui.Lang
import io.embodit.dogmanners.ui.Screen
import io.embodit.dogmanners.ui.Ui
import io.embodit.dogmanners.ui.debug.probe
import io.embodit.dogmanners.ui.theme.DogColors
import io.embodit.dogmanners.ui.theme.DogType

private val GlassShape = RoundedCornerShape(28.dp)

/** Glass panel root (SpatialUI Material.Regular + Rehearsal Hall's glass refresh). */
@Composable
private fun Glass(w: Float, h: Float, padding: Int = 32, content: @Composable () -> Unit) {
    Box(modifier = Modifier.size(w.dp, h.dp).clip(GlassShape).backgroundMaterial(enable = true, style = Material.Regular).then(glassRefresh())) {
        Box(Modifier.fillMaxSize().padding(padding.dp)) { content() }
    }
}

@Composable
private fun Key(
    text: String,
    name: String,
    icon: Int? = null,
    primary: Boolean = false,
    danger: Boolean = false,
    height: Dp = 52.dp,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    // primary / danger: a filled state colour with the dark ink (8.8:1 / 6.3:1); never coloured text on the glass,
    // whose brightness follows the park behind it (QA K1: error ink on glass measured 1.84:1)
    val container = when {
        primary -> DogColors.Relaxed
        danger -> DogColors.Error
        else -> PicoTheme.colorScheme.fillTertiary
    }
    val ink = if (primary || danger) DogColors.OnState else PicoTheme.colorScheme.labelPrimary
    Button(
        onClick = onClick,
        modifier = modifier.probe(name).height(height),
        size = ButtonDefaults.Regular.copy(height = height),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = ink),
    ) {
        if (icon != null) {
            Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(22.dp), tint = ink)
            Spacer(Modifier.width(8.dp))
        }
        Text(text = text, maxLines = 1, color = ink, style = DogType.button)
    }
}

@Composable
private fun segColors() =
    SegmentControlDefaults.colors(
        itemBackgroundColor = Color.Transparent,
        itemContentColor = PicoTheme.colorScheme.labelPrimary,
        selectedItemBackgroundColor = PicoTheme.colorScheme.fillSecondary,
        selectedItemContentColor = PicoTheme.colorScheme.labelPrimary,
    )

@Composable
private fun <T> Segments(options: List<Pair<T, String>>, selected: T, name: String, itemWidth: Int, onPick: (T) -> Unit) {
    SegmentControl(modifier = Modifier.width((itemWidth * options.size + 8).dp).height(48.dp), backgroundColor = PicoTheme.colorScheme.fillTertiary) {
        options.forEach { (v, label) ->
            SegmentItem(
                selected = v == selected,
                onClick = { onPick(v) },
                modifier = Modifier.width(itemWidth.dp).fillMaxHeight().probe("${name}_$v"),
                textStyle = DogType.button,
                colors = segColors(),
                title = { Text(text = label, maxLines = 1, color = PicoTheme.colorScheme.labelPrimary) },
            )
        }
    }
}

private fun moodColor(m: Mood) = when (m) { Mood.WARY -> DogColors.Wary; Mood.RELAXED -> DogColors.Relaxed; Mood.PLAY -> DogColors.Play }

/** A status icon on its own filled disc: the icon reads against the disc, not against the glass (D13, QA K1). */
@Composable
private fun Badge(icon: Int, fill: Color, size: Int = 26, modifier: Modifier = Modifier) {
    Box(modifier.size(size.dp).clip(CircleShape).background(fill), contentAlignment = Alignment.Center) {
        Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size((size - 2).dp), tint = DogColors.OnState)
    }
}

private fun nearText(n: NearMiss) = when (n.cause) { NearCause.FACE -> R.string.near_face; NearCause.LEAN -> R.string.near_lean }

/** D27: what was a little off, said like a friend would; one more line when the judging just got easier. */
@Composable
private fun NearHint(n: NearMiss) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(DogColors.Wary).padding(horizontal = 14.dp, vertical = 8.dp).probe("near_hint"), verticalAlignment = Alignment.CenterVertically) {
        Icon(painter = painterResource(R.drawable.ic_info), contentDescription = null, modifier = Modifier.size(22.dp), tint = DogColors.OnState)
        Spacer(Modifier.width(10.dp))
        Text(
            text = rs(nearText(n)) + if (n.loosened) " " + rs(R.string.near_loosened) else "",
            style = DogType.note, color = DogColors.OnState, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun moodTag(m: Mood) = when (m) { Mood.WARY -> R.string.tag_wary; Mood.RELAXED -> R.string.tag_relaxed; Mood.PLAY -> R.string.tag_play }

private fun lessonName(id: LessonId) = when (id) { LessonId.L1 -> R.string.l1_name; LessonId.L2 -> R.string.l2_name; LessonId.L3 -> R.string.l3_name; LessonId.L4 -> R.string.l4_name; LessonId.L5 -> R.string.l5_name; LessonId.L6 -> R.string.l6_name }

private fun lessonTag(id: LessonId) = when (id) { LessonId.L1 -> R.string.l1_tag; LessonId.L2 -> R.string.l2_tag; LessonId.L3 -> R.string.l3_tag; LessonId.L4 -> R.string.l4_tag; LessonId.L5 -> R.string.l5_tag; LessonId.L6 -> R.string.l6_tag }

private fun intro(id: LessonId): List<Int> =
    when (id) {
        LessonId.L1 -> listOf(R.string.l1_i1, R.string.l1_i2, R.string.l1_i3)
        LessonId.L2 -> listOf(R.string.l2_i1, R.string.l2_i2, R.string.l2_i3)
        LessonId.L3 -> listOf(R.string.l3_i1, R.string.l3_i2, R.string.l3_i3)
        LessonId.L4 -> listOf(R.string.l4_i1, R.string.l4_i2, R.string.l4_i3)
        LessonId.L5 -> listOf(R.string.l5_i1, R.string.l5_i2, R.string.l5_i3)
        LessonId.L6 -> listOf(R.string.l6_i1, R.string.l6_i2, R.string.l6_i3)
    }

private fun question(id: LessonId) = when (id) { LessonId.L2 -> R.string.l2_q; LessonId.L3 -> R.string.l3_q; LessonId.L4 -> R.string.l4_q; LessonId.L5 -> R.string.l5_q; LessonId.L6 -> R.string.l6_q; else -> R.string.l1_tag }

private fun choices(id: LessonId): List<Int> =
    when (id) {
        LessonId.L2 -> listOf(R.string.l2_c1, R.string.l2_c2, R.string.l2_c3)
        LessonId.L3 -> listOf(R.string.l3_c1, R.string.l3_c2)
        LessonId.L4 -> listOf(R.string.l4_c1, R.string.l4_c2, R.string.l4_c3)
        LessonId.L5 -> listOf(R.string.l5_c1, R.string.l5_c2)
        LessonId.L6 -> listOf(R.string.l6_c1, R.string.l6_c2)
        LessonId.L1 -> emptyList()
    }

private fun coachText(c: Coach): Int? =
    when (c) {
        Coach.NONE, Coach.HOME, Coach.GUIDE -> null
        Coach.APPROACH -> R.string.c_approach
        Coach.FREE -> R.string.c_approach_dog
        Coach.WARY -> R.string.c_wary
        Coach.RELAXED -> R.string.c_relaxed
        Coach.SNIFF -> R.string.c_sniff
        Coach.PLAY -> R.string.c_play
        Coach.TOO_CLOSE -> R.string.c_too_close
        Coach.LEFT -> R.string.c_left
        Coach.LEFT_WELL -> R.string.c_left_well
        Coach.OWNER_OK -> R.string.c_owner_ok
        Coach.OWNER_HELD -> R.string.c_owner_held
        Coach.L2_RUSH -> R.string.c_l2_rush
        Coach.L2_RUN -> R.string.c_l2_run
        Coach.EATING -> R.string.c_eating
        Coach.L3_WAIT -> R.string.c_l3_wait
        Coach.L3_WARY -> R.string.c_l3_wary
        Coach.L3_PAT -> R.string.c_l3_pat
        Coach.SLEEPING -> R.string.c_sleeping
        Coach.L4_WOKE -> R.string.c_l4_woke
        Coach.L4_SLEEP -> R.string.c_l4_sleep
        Coach.MOM -> R.string.c_mom
        Coach.L5_WAIT -> R.string.c_l5_wait
        Coach.L5_WARY -> R.string.c_l5_wary
        Coach.L5_CLOSE -> R.string.c_l5_close
        Coach.FENCE -> R.string.c_fence
        Coach.L6_REACH -> R.string.c_l6_reach
        Coach.L6_WAIT -> R.string.c_l6_wait
    }

private fun resultText(r: ResultText) =
    when (r) {
        ResultText.L1_OK -> R.string.r_l1_ok
        ResultText.L1_LEFT -> R.string.r_l1_left
        ResultText.L2_OK -> R.string.r_l2_ok
        ResultText.L2_RUSH -> R.string.r_l2_rush
        ResultText.L2_RUN -> R.string.r_l2_run
        ResultText.L3_OK -> R.string.r_l3_ok
        ResultText.L3_PAT -> R.string.r_l3_pat
        ResultText.L3_STARE -> R.string.r_l3_stare
        ResultText.L4_OK -> R.string.r_l4_ok
        ResultText.L4_BAD -> R.string.r_l4_bad
        ResultText.L5_OK -> R.string.r_l5_ok
        ResultText.L5_BAD -> R.string.r_l5_bad
        ResultText.L6_OK -> R.string.r_l6_ok
        ResultText.L6_BAD -> R.string.r_l6_bad
    }

private fun goalText(g: Goal) =
    when (g) {
        Goal.NO_STARE -> R.string.g_no_stare
        Goal.SIDE -> R.string.g_side
        Goal.STILL -> R.string.g_still
        Goal.NO_LEAN -> R.string.g_no_lean
        Goal.ASKED -> R.string.g_asked
        Goal.WAITED -> R.string.g_waited
        Goal.CHOICE -> R.string.g_choice
    }

// ================================================================================================= main window pages
// v0.1.2 (D8 / D21): the old P1 main panel and P3 card are pages of ONE system window (ui/window/DogWindow.kt). The
// window brings its own glass and 32 dp corners, so these pages draw no background of their own.

@Composable
private fun WindowPage(state: DogUiState, onEvent: (Ui) -> Unit, footer: Boolean = true, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val showEye = state.eye != EyeState.ON && state.eye != EyeState.UNKNOWN && state.screen !in setOf(Screen.STARTING, Screen.DISCLAIMER, Screen.SETTINGS)
        if (showEye) EyeBar(state, onEvent)
        Box(Modifier.fillMaxWidth().weight(1f)) { content() }
        if (footer) Footer(state, onEvent)
    }
}

/** 小场面: start-up, disclaimer, home, intro, the running lesson with its choice / result card. */
@Composable
internal fun LessonsPage(state: DogUiState, onEvent: (Ui) -> Unit) {
    WindowPage(state, onEvent, footer = state.screen !in setOf(Screen.STARTING, Screen.DISCLAIMER)) {
        when (state.screen) {
            Screen.STARTING -> Starting()
            Screen.DISCLAIMER -> Disclaimer(onEvent)
            Screen.INTRO -> Intro(state, onEvent)
            Screen.LESSON -> Lesson(state, onEvent)
            else -> Home(state, onEvent)
        }
    }
}

@Composable
internal fun GuidePage(state: DogUiState, onEvent: (Ui) -> Unit) = WindowPage(state, onEvent) { Guide(state, onEvent) }

@Composable
internal fun FreePage(state: DogUiState, onEvent: (Ui) -> Unit) = WindowPage(state, onEvent) { Free(state, onEvent) }

@Composable
internal fun SettingsPageContent(state: DogUiState, onEvent: (Ui) -> Unit) = Settings(state, onEvent)

/** Stage chip that opens the main window again after its × (native-window-move §3.3); shown only while it is closed. */
@Composable
internal fun OpenChip(shown: Boolean, onEvent: (Ui) -> Unit) {
    Glass(PanelSizes.CHIP_W, PanelSizes.CHIP_H, padding = 16) {
        if (!shown) return@Glass
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Key(rs(R.string.open_panel), "open_panel", icon = R.drawable.ic_home, primary = true, height = 56.dp) { onEvent(Ui.OpenPanel) }
        }
    }
}

@Composable
private fun EyeBar(state: DogUiState, onEvent: (Ui) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(16.dp)).background(DogColors.Wary).padding(horizontal = 16.dp).probe("eye_bar"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painter = painterResource(R.drawable.ic_eye_off), contentDescription = null, modifier = Modifier.size(24.dp), tint = DogColors.OnState)
        Spacer(Modifier.width(10.dp))
        val denied = state.eye == EyeState.DENIED
        Text(
            text = rs(if (denied) R.string.eye_denied else if (state.eye == EyeState.UNSUPPORTED) R.string.eye_unsupported else R.string.eye_off),
            style = DogType.note, color = DogColors.OnState, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        if (state.eye == EyeState.NO_PERMISSION) {
            Button(
                onClick = { onEvent(Ui.AskEye) },
                modifier = Modifier.probe("eye_turn_on").height(40.dp),
                size = ButtonDefaults.Regular.copy(height = 40.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = DogColors.OnState, contentColor = Color.White),
            ) { Text(text = rs(R.string.eye_turn_on), maxLines = 1, color = Color.White, style = DogType.note) }
        }
    }
}

@Composable
private fun Footer(state: DogUiState, onEvent: (Ui) -> Unit) {
    val zh = rs(R.string.lang_code) == "zh"
    Row(modifier = Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Segments(listOf(Lang.ZH to "中", Lang.EN to "EN"), if (zh) Lang.ZH else Lang.EN, "lang", 64) { onEvent(Ui.SetLang(it)) }
        Spacer(Modifier.weight(1f))
        Key(rs(if (state.soundOn) R.string.sound_on else R.string.sound_off), "sound", icon = if (state.soundOn) R.drawable.ic_volume_up else R.drawable.ic_volume_off) { onEvent(Ui.ToggleSound) }
        Key(rs(R.string.bring), "bring", icon = R.drawable.ic_recenter) { onEvent(Ui.Bring) }
    }
}

@Composable
private fun Title(text: String, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(modifier = Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(text = text, style = DogType.title, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
private fun Body(text: String, maxLines: Int = 3, modifier: Modifier = Modifier) {
    Text(text = text, style = DogType.body, color = PicoTheme.colorScheme.labelPrimary, maxLines = maxLines, overflow = TextOverflow.Ellipsis, modifier = modifier)
}

@Composable
private fun Note(text: String, maxLines: Int = 2) {
    Text(text = text, style = DogType.note, color = PicoTheme.colorScheme.labelSecondary, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun Starting() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text = rs(R.string.app_name), style = DogType.title, color = PicoTheme.colorScheme.labelPrimary)
            Text(text = rs(R.string.starting), style = DogType.body, color = PicoTheme.colorScheme.labelSecondary)
        }
    }
}

@Composable
private fun Disclaimer(onEvent: (Ui) -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Title(rs(R.string.app_name))
        Body(rs(R.string.disclaimer), maxLines = 5)
        Row(verticalAlignment = Alignment.Top) {
            Icon(painter = painterResource(R.drawable.ic_info), contentDescription = null, modifier = Modifier.size(22.dp).padding(top = 2.dp), tint = PicoTheme.colorScheme.labelSecondary)
            Spacer(Modifier.width(8.dp))
            Note(rs(R.string.disclaimer_eye), maxLines = 3)
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { Key(rs(R.string.start), "start", icon = R.drawable.ic_arrow_forward, primary = true, height = 56.dp) { onEvent(Ui.Start) } }
    }
}

@Composable
private fun Home(state: DogUiState, onEvent: (Ui) -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Title(rs(R.string.app_name))
        Body(rs(R.string.home_one), maxLines = 2)
        Spacer(Modifier.height(2.dp))
        for (id in LessonId.entries) LessonRow(id, id.n in state.completed) { onEvent(Ui.OpenLesson(id)) }
    }
}

@Composable
private fun LessonRow(id: LessonId, done: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(48.dp).probe("lesson_${id.n}"),
        size = ButtonDefaults.Regular.copy(height = 48.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.fillTertiary, contentColor = PicoTheme.colorScheme.labelPrimary),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(text = "L${id.n}", style = DogType.button, color = PicoTheme.colorScheme.labelSecondary, modifier = Modifier.width(40.dp), maxLines = 1)
            Text(text = rs(lessonName(id)), style = DogType.body, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1)
            Spacer(Modifier.width(14.dp))
            Text(text = rs(lessonTag(id)), style = DogType.note, color = PicoTheme.colorScheme.labelSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (done) Badge(R.drawable.ic_check_circle, DogColors.Relaxed, 26)
        }
    }
}

@Composable
private fun Intro(state: DogUiState, onEvent: (Ui) -> Unit) {
    val id = state.lesson ?: return
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Title(rs(R.string.lesson_n, id.n) + " · " + rs(lessonName(id)))
        // QA K1: the teal tagline on glass was 1.45:1; the text takes the label role, the paw disc keeps the colour
        Row(verticalAlignment = Alignment.CenterVertically) {
            Badge(R.drawable.ic_paw, DogColors.Relaxed, 30)
            Spacer(Modifier.width(12.dp))
            Text(text = rs(lessonTag(id)), style = DogType.heading, color = PicoTheme.colorScheme.labelPrimary, maxLines = 2, modifier = Modifier.probe("tagline"))
        }
        for (b in intro(id)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(44.dp)) {
                Icon(painter = painterResource(R.drawable.ic_paw), contentDescription = null, modifier = Modifier.size(22.dp), tint = PicoTheme.colorScheme.labelSecondary)
                Spacer(Modifier.width(12.dp))
                Body(rs(b), maxLines = 2)
            }
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Key(rs(R.string.back), "intro_back", icon = R.drawable.ic_chevron_left) { onEvent(Ui.Back) }
            Spacer(Modifier.weight(1f))
            if (id.n in state.introsSeen) Key(rs(R.string.skip_intro), "skip_intro", icon = R.drawable.ic_skip_next) { onEvent(Ui.SkipIntro) }
            Key(rs(R.string.start), "intro_start", icon = R.drawable.ic_arrow_forward, primary = true) { onEvent(Ui.BeginLesson) }
        }
    }
}

@Composable
private fun Goals(goals: List<Pair<Goal, Boolean>>) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text = rs(R.string.goals_label), style = DogType.note, color = PicoTheme.colorScheme.labelSecondary, maxLines = 1)
    }
    goals.chunked(2).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            row.forEach { (g, ok) ->
                Row(Modifier.weight(1f).height(32.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (ok) {
                        Badge(R.drawable.ic_check_circle, DogColors.Relaxed, 24, Modifier.probe("goal_${g.name.lowercase()}"))
                    } else {
                        Icon(
                            painter = painterResource(R.drawable.ic_cancel),
                            contentDescription = null, modifier = Modifier.size(24.dp).probe("goal_${g.name.lowercase()}"),
                            tint = PicoTheme.colorScheme.labelSecondary,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(text = rs(goalText(g)), style = DogType.note, color = if (ok) PicoTheme.colorScheme.labelPrimary else PicoTheme.colorScheme.labelSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun Lesson(state: DogUiState, onEvent: (Ui) -> Unit) {
    val id = state.lesson ?: return
    val snap = state.snap
    // the old P3 card, inside the same window (D8): result replaces the page, a choice goes under the coach line
    if (snap != null && snap.phase == Phase.RESULT && snap.outcome != null) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Title(rs(R.string.lesson_n, id.n) + " · " + rs(lessonName(id)))
            Box(Modifier.fillMaxWidth().weight(1f)) { Result(id, snap, onEvent) }
        }
        return
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Title(rs(R.string.lesson_n, id.n) + " · " + rs(lessonName(id)))
        val c = snap?.coach?.let { coachText(it) }
        val text = when {
            state.backHint -> rs(R.string.c_back_hint)
            c != null -> rs(c)
            else -> ""
        }
        Text(text = text, style = DogType.heading, color = PicoTheme.colorScheme.labelPrimary, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.probe("coach"))
        if (snap?.waitLeftS != null) Note("⏱ ${snap.waitLeftS} s", maxLines = 1)
        if (snap?.hint == true && snap.phase == Phase.CHOICE) Note(rs(R.string.choice_hint), maxLines = 1)
        snap?.near?.let { NearHint(it) }
        if (snap?.phase == Phase.CHOICE) Choice(id, onEvent)
        Spacer(Modifier.weight(1f))
        if (snap != null && snap.goals.isNotEmpty() && snap.phase != Phase.CHOICE) Goals(snap.goals)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Key(rs(R.string.back), "lesson_back", icon = R.drawable.ic_chevron_left) { onEvent(Ui.Back) }
            Key(rs(R.string.restart), "restart", icon = R.drawable.ic_refresh) { onEvent(Ui.Restart) }
        }
    }
}

@Composable
private fun Guide(state: DogUiState, onEvent: (Ui) -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Title(rs(R.string.guide)) { Key(rs(R.string.back), "guide_back", icon = R.drawable.ic_chevron_left, height = 48.dp) { onEvent(Ui.Back) } }
        Segments(listOf(Mood.WARY to rs(R.string.tag_wary), Mood.RELAXED to rs(R.string.tag_relaxed), Mood.PLAY to rs(R.string.tag_play)), state.guideMood, "mood", 200) { onEvent(Ui.GuideMood(it)) }
        Note(rs(R.string.guide_note), maxLines = 1)
        Text(text = rs(R.string.early_title), style = DogType.heading, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (s in listOf(R.string.early_yawn, R.string.early_lick, R.string.early_whale, R.string.early_paw)) {
                Box(Modifier.weight(1f).height(64.dp).clip(RoundedCornerShape(16.dp)).background(PicoTheme.colorScheme.fillTertiary), contentAlignment = Alignment.Center) {
                    Text(text = rs(s), style = DogType.body, color = PicoTheme.colorScheme.labelPrimary, maxLines = 2, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 6.dp))
                }
            }
        }
        Note(rs(R.string.early_note), maxLines = 2)
        Note(rs(R.string.early_source), maxLines = 1)
    }
}

@Composable
private fun Free(state: DogUiState, onEvent: (Ui) -> Unit) {
    val snap = state.snap
    val p = rs(when (state.personality) { Personality.SHY -> R.string.free_shy; Personality.NORMAL -> R.string.free_normal; Personality.OUTGOING -> R.string.free_outgoing })
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Title(rs(R.string.free)) { Key(rs(R.string.free_another), "another", icon = R.drawable.ic_refresh, height = 48.dp) { onEvent(Ui.AnotherDog) } }
        Note(rs(R.string.free_mood, p), maxLines = 1)
        val c = snap?.coach?.let { coachText(it) }
        val text = when {
            state.backHint -> rs(R.string.c_back_hint)
            snap?.coach == Coach.FREE -> rs(R.string.c_free, p)
            c != null -> rs(c)
            else -> ""
        }
        Text(text = text, style = DogType.heading, color = PicoTheme.colorScheme.labelPrimary, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.probe("coach"))
        snap?.near?.let { NearHint(it) }
        Spacer(Modifier.weight(1f))
        if (snap != null) Goals(snap.goals)
        Row(Modifier.fillMaxWidth()) { Key(rs(R.string.back), "free_back", icon = R.drawable.ic_chevron_left) { onEvent(Ui.Back) } }
    }
}

@Composable
private fun Settings(state: DogUiState, onEvent: (Ui) -> Unit) {
    val zh = rs(R.string.lang_code) == "zh"
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Title(rs(R.string.settings)) { Key(rs(R.string.back), "settings_back", icon = R.drawable.ic_chevron_left, height = 48.dp) { onEvent(Ui.Back) } }
        Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Segments(listOf(false to rs(R.string.stand), true to rs(R.string.sit)), state.seated, "stance", 120) { onEvent(Ui.SetSeated(it)) }
            Spacer(Modifier.weight(1f))
            Text(text = rs(if (state.tagOn) R.string.tag_on else R.string.tag_off), style = DogType.body, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1)
            Switch(checked = state.tagOn, onCheckedChange = { onEvent(Ui.ToggleTag) }, modifier = Modifier.probe("tag_switch"))
        }
        Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Segments(listOf(Lang.ZH to "中", Lang.EN to "EN"), if (zh) Lang.ZH else Lang.EN, "set_lang", 64) { onEvent(Ui.SetLang(it)) }
            Spacer(Modifier.weight(1f))
            Text(text = rs(if (state.soundOn) R.string.sound_on else R.string.sound_off), style = DogType.body, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1)
            Switch(checked = state.soundOn, onCheckedChange = { onEvent(Ui.ToggleSound) }, modifier = Modifier.probe("sound_switch"))
        }
        // v0.1.2 (B12): far birds are decoration only, here is their switch
        Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Spacer(Modifier.weight(1f))
            Text(text = rs(if (state.birdsOn) R.string.birds_on else R.string.birds_off), style = DogType.body, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1)
            Switch(checked = state.birdsOn, onCheckedChange = { onEvent(Ui.ToggleBirds) }, modifier = Modifier.probe("birds_switch"))
        }
        Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val on = state.eye == EyeState.ON
            Text(
                text = rs(if (on) R.string.eye_on_state else R.string.eye_off_state) + if (state.eye == EyeState.DENIED) "  " + rs(R.string.eye_denied) else "",
                style = DogType.note, color = PicoTheme.colorScheme.labelPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (!on && state.eye != EyeState.DENIED && state.eye != EyeState.UNSUPPORTED) Key(rs(R.string.eye_turn_on), "settings_eye", icon = R.drawable.ic_eye_off, height = 48.dp) { onEvent(Ui.AskEye) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Key(rs(R.string.bring), "settings_bring", icon = R.drawable.ic_recenter, height = 48.dp) { onEvent(Ui.Bring) }
            Key(rs(R.string.reset_progress), "reset", icon = R.drawable.ic_refresh, height = 48.dp) { onEvent(Ui.ResetProgress) }
            if (state.resetDone) Note(rs(R.string.reset_done), maxLines = 1)
        }
        Note(rs(R.string.about) + "  " + rs(R.string.version, BuildConfig.VERSION_NAME), maxLines = 3)
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth().height(56.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!state.quitConfirm) {
                Key(rs(R.string.quit), "quit", icon = R.drawable.ic_logout, danger = true) { onEvent(Ui.QuitAsk) }
            } else {
                Text(text = rs(R.string.quit_confirm), style = DogType.body, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, modifier = Modifier.weight(1f))
                Key(rs(R.string.cancel), "quit_cancel") { onEvent(Ui.QuitCancel) }
                Key(rs(R.string.quit_yes), "quit_yes", icon = R.drawable.ic_logout, danger = true) { onEvent(Ui.QuitYes) }
            }
        }
    }
}

// ================================================================================================= P2 signal tag

@Composable
internal fun TagPanel(state: DogUiState?) {
    Glass(PanelSizes.TAG_W, PanelSizes.TAG_H, padding = 20) {
        if (state == null) return@Glass
        val mood = if (state.screen == Screen.GUIDE) state.guideMood else state.snap?.mood ?: Mood.RELAXED
        val dim = state.snap?.phase == Phase.CHOICE || state.snap?.phase == Phase.RESULT
        Column(Modifier.fillMaxSize().alpha(if (dim) 0.4f else 1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(22.dp).clip(CircleShape).background(moodColor(mood)))
                Spacer(Modifier.width(12.dp))
                Text(text = rs(moodTag(mood)), style = DogType.tagState, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, modifier = Modifier.probe("tag_state"))
            }
            val (e, t, m) = when (mood) {
                Mood.WARY -> Triple(R.string.wary_ears, R.string.wary_tail, R.string.wary_mouth)
                Mood.RELAXED -> Triple(R.string.relaxed_ears, R.string.relaxed_tail, R.string.relaxed_mouth)
                Mood.PLAY -> Triple(R.string.play_ears, R.string.play_tail, R.string.play_mouth)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Signal(R.drawable.ic_ears, rs(R.string.sig_ears), rs(e), Modifier.weight(1f))
                Signal(R.drawable.ic_paw, rs(R.string.sig_tail), rs(t), Modifier.weight(1f))
                Signal(R.drawable.ic_mouth, rs(R.string.sig_mouth), rs(m), Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Signal(icon: Int, label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(22.dp), tint = PicoTheme.colorScheme.labelSecondary)
            Spacer(Modifier.width(6.dp))
            Text(text = label, style = DogType.note, color = PicoTheme.colorScheme.labelSecondary, maxLines = 1)
        }
        Text(text = value, style = DogType.tagSignal, color = PicoTheme.colorScheme.labelPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

// ================================================================================================= choice / result (in the lesson page)

@Composable
private fun Choice(id: LessonId, onEvent: (Ui) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text = rs(question(id)), style = DogType.heading, color = PicoTheme.colorScheme.labelPrimary, maxLines = 3, overflow = TextOverflow.Ellipsis)
        choices(id).forEachIndexed { i, c ->
            Button(
                onClick = { onEvent(Ui.Choose(i)) },
                modifier = Modifier.fillMaxWidth().height(64.dp).probe("choice_$i"),
                size = ButtonDefaults.Regular.copy(height = 64.dp),
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.fillTertiary, contentColor = PicoTheme.colorScheme.labelPrimary),
            ) { Text(text = rs(c), style = DogType.body, color = PicoTheme.colorScheme.labelPrimary, maxLines = 2, textAlign = TextAlign.Center) }
        }
    }
}

@Composable
private fun Result(id: LessonId, snap: io.embodit.dogmanners.domain.Snapshot, onEvent: (Ui) -> Unit) {
    val outcome = snap.outcome ?: return
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val ok = outcome == Outcome.OK
            Badge(if (ok) R.drawable.ic_check_circle else R.drawable.ic_info, if (ok) DogColors.Relaxed else DogColors.Wary, 32)
            Spacer(Modifier.width(10.dp))
            Text(
                text = rs(when (outcome) { Outcome.OK -> R.string.r_ok; Outcome.BAD -> R.string.r_bad; Outcome.LEFT -> R.string.r_left }),
                style = DogType.title, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, modifier = Modifier.probe("result_title"),
            )
        }
        snap.result?.let { Body(rs(resultText(it)), maxLines = 4) }
        if (snap.showScared) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(PicoTheme.colorScheme.fillTertiary).padding(12.dp)) {
                Text(text = rs(R.string.scared_title), style = DogType.button, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1)
                Note(rs(R.string.scared), maxLines = 3)
            }
        } else if (snap.goals.isNotEmpty()) {
            Goals(snap.goals)
        }
        Spacer(Modifier.weight(1f))
        Note(rs(if (id == LessonId.L1) R.string.r_source_l1 else R.string.r_source), maxLines = 2)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
            Key(rs(R.string.again), "again", icon = R.drawable.ic_refresh) { onEvent(Ui.Restart) }
            Key(rs(R.string.next), "next", icon = R.drawable.ic_skip_next, primary = true) { onEvent(Ui.Next) }
        }
    }
}
