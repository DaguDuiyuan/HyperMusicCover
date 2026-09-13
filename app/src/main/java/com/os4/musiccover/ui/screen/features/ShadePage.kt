package com.os4.musiccover.ui.screen.features

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.os4.musiccover.ModuleBridge
import com.os4.musiccover.R
import com.os4.musiccover.ui.util.PageScaffold
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import kotlin.math.roundToInt

/**
 * Everything the module does to the notification shade when it is pulled down.
 *
 * Its own screen rather than a section of the lock screen's, because it is a separate feature
 * with a separate failure surface: the cover page is about what the lock screen shows, this one
 * is about what happens over the desktop - including two switches, off by default, that take
 * over parts of SystemUI's own drawing. See [ShadeActivity][com.os4.musiccover.ShadeActivity] for
 * why it is a screen and not a page.
 *
 * The frame is [PageScaffold]'s, so this page has no opinion about the bar, the blur or the
 * insets. Every value is an Int on the wire and the module clamps it: what the number MEANS is
 * the module's business, and what this page owns is which keys exist, what to call them, and how
 * to divide them for display - see [KNOBS].
 */
@Composable
internal fun ShadePageView(
    isBlurEnabled: Boolean,
    refreshKey: Int,
    extraBottomPadding: Dp = 0.dp,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var module by remember { mutableStateOf(ModuleBridge.State()) }
    LaunchedEffect(refreshKey) { module = ModuleBridge.query(context) }
    val enabled = module.alive

    // Local first, then the module. A slider has to follow the finger immediately, and the
    // module's answer arrives a broadcast later - waiting for it would make every drag stutter.
    // A value the module clamps differently from what was asked for is corrected on the next
    // entry to the screen, which is also when a value set over adb shows up.
    val push: (String, Int) -> Unit = { key, value ->
        module = module.copy(shade = module.shade + (key to value))
        ModuleBridge.setShade(context, key, value)
    }

    PageScaffold(
        title = stringResource(R.string.features_shade_title),
        isBlurEnabled = isBlurEnabled,
        extraBottomPadding = extraBottomPadding,
        onBack = onBack,
    ) {
        item {
            Column {
                SmallTitle(text = stringResource(R.string.shade_section_behaviour))
                Card(
                    modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)
                ) {
                    BehaviourGroup(module, enabled, push)
                }

                SmallTitle(text = stringResource(R.string.shade_section_spring))
                Card(
                    modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)
                ) {
                    Column {
                        ShadeKnob(module, enabled, push, "stiffness")
                        ShadeKnob(module, enabled, push, "damping")
                        ShadeKnob(module, enabled, push, "curtainstiffness")
                        ShadeKnob(module, enabled, push, "curtaindamping")
                        ShadeKnob(module, enabled, push, "blursat")
                    }
                }

                SmallTitle(text = stringResource(R.string.shade_section_curtain))
                Card(
                    modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)
                ) {
                    Column {
                        ShadeKnob(module, enabled, push, "deadzone")
                        ShadeKnob(module, enabled, push, "alphaend")
                        ShadeKnob(module, enabled, push, "wprise")
                        ShadeKnob(module, enabled, push, "wpblur")
                        ShadeKnob(module, enabled, push, "sharpstart")
                        ShadeKnob(module, enabled, push, "contentpush")
                        ShadeKnob(module, enabled, push, "clockpush")
                        ShadeKnob(module, enabled, push, "carrierpush")
                        ShadeKnob(module, enabled, push, "shadebias")
                    }
                }
            }
        }
    }
}

/**
 * The choices that are not sliders: what the feature applies to, and the two switches that take
 * over part of SystemUI's own presentation.
 */
@Composable
private fun BehaviourGroup(
    module: ModuleBridge.State,
    enabled: Boolean,
    push: (String, Int) -> Unit,
) {
    Column {
        val modes = listOf(
            stringResource(R.string.shade_mode_cover),
            stringResource(R.string.shade_mode_always),
            stringResource(R.string.shade_mode_feel),
        )
        WindowDropdownPreference(
            title = stringResource(R.string.shade_mode),
            summary = stringResource(R.string.shade_mode_summary),
            items = modes,
            selectedIndex = (module.shade["mode"] ?: 0).coerceIn(0, modes.lastIndex),
            enabled = enabled,
            onSelectedIndexChange = { push("mode", it) },
        )
        SwitchPreference(
            title = stringResource(R.string.shade_gate),
            summary = stringResource(R.string.shade_gate_summary),
            checked = (module.shade["gate"] ?: 0) != 0,
            enabled = enabled,
            onCheckedChange = { push("gate", if (it) 1 else 0) },
        )
        SwitchPreference(
            title = stringResource(R.string.shade_cardblur),
            summary = stringResource(R.string.shade_cardblur_summary),
            checked = (module.shade["cardblur"] ?: 0) != 0,
            enabled = enabled,
            onCheckedChange = { push("cardblur", if (it) 1 else 0) },
        )
        ShadeKnob(module, enabled, push, "cardradius")
        SwitchPreference(
            title = stringResource(R.string.shade_touch),
            summary = stringResource(R.string.shade_touch_summary),
            checked = (module.shade["touch"] ?: 1) != 0,
            enabled = enabled,
            onCheckedChange = { push("touch", if (it) 1 else 0) },
        )
    }
}

/**
 * One knob, looked up in [KNOBS].
 *
 * Drawn with the same slider the cover screen uses rather than one of its own, and the table is
 * the whole of this file's knowledge about the module's settings: a key that is not in it is
 * simply not shown, which is how a setting can exist for adb before - or without - a control.
 */
@Composable
private fun ShadeKnob(
    module: ModuleBridge.State,
    enabled: Boolean,
    push: (String, Int) -> Unit,
    key: String,
) {
    val spec = KNOBS.firstOrNull { it.key == key } ?: return
    val raw = module.shade[key] ?: spec.default
    ValueSlider(
        title = stringResource(spec.title),
        summary = spec.summary?.let { stringResource(it) },
        value = raw / spec.scale,
        valueRange = spec.min / spec.scale..spec.max / spec.scale,
        enabled = enabled,
        onValueChange = { push(key, (it * spec.scale).roundToInt()) },
    )
}

/**
 * Every slider on the screen, in the order it is drawn.
 *
 * [scale] is what the module's integer is divided by for display. The module keeps these as
 * integers because the state file and the broadcast are both text; the fraction is a display
 * concern and lives here.
 */
private class Knob(
    val key: String,
    val title: Int,
    val summary: Int?,
    val min: Int,
    val max: Int,
    val default: Int,
    val scale: Float = 1f,
)

private val KNOBS = listOf(
    Knob("cardradius", R.string.shade_cardradius, null, 0, 200, 100),
    Knob("stiffness", R.string.shade_stiffness, R.string.shade_stiffness_summary, 20, 400, 100),
    Knob("damping", R.string.shade_damping, R.string.shade_damping_summary, 30, 150, 90, 100f),
    Knob(
        "curtainstiffness", R.string.shade_curtainstiffness,
        R.string.shade_curtainstiffness_summary, 20, 600, 200
    ),
    Knob("curtaindamping", R.string.shade_curtaindamping, null, 30, 150, 100, 100f),
    Knob("blursat", R.string.shade_blursat, R.string.shade_blursat_summary, 100, 1000, 650, 1000f),
    Knob("deadzone", R.string.shade_deadzone, R.string.shade_deadzone_summary, 0, 200, 45, 1000f),
    Knob("alphaend", R.string.shade_alphaend, R.string.shade_alphaend_summary, 100, 1000, 700, 1000f),
    Knob("wprise", R.string.shade_wprise, R.string.shade_wprise_summary, 0, 400, 120, 1000f),
    Knob("wpblur", R.string.shade_wpblur, R.string.shade_wpblur_summary, 0, 200, 80),
    Knob(
        "sharpstart", R.string.shade_sharpstart, R.string.shade_sharpstart_summary,
        100, 999, 700, 1000f
    ),
    Knob(
        "contentpush", R.string.shade_contentpush, R.string.shade_contentpush_summary,
        0, 1800, 0
    ),
    Knob(
        "clockpush", R.string.shade_clockpush, R.string.shade_clockpush_summary,
        -600, 600, 0
    ),
    Knob(
        "carrierpush", R.string.shade_carrierpush, R.string.shade_carrierpush_summary,
        -600, 600, 0
    ),
    Knob(
        "shadebias", R.string.shade_shadebias, R.string.shade_shadebias_summary,
        -1000, 1000, 0, 1000f
    ),
)
