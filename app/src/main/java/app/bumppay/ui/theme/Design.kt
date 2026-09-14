package app.bumppay.ui.theme

import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * BumpPay's design system.
 *
 * ## Palette and why it is not what a crypto app usually looks like
 *
 * The brief's brand direction is "hardware-native, not generic-crypto". The default
 * recommendation for a Solana payments app is a purple/pink gradient on near-black, and
 * that was rejected deliberately: it reads as a wrapped web app, and worse, it fights a
 * dark demo stage. What is used instead is an instrument palette — a near-black cool
 * graphite, a warm off-white for text, and exactly one saturated colour (a soft aqua
 * "signal") reserved for the tap moment.
 *
 * The rule that makes it hold together: **the signal colour appears only when the radio is
 * doing something.** It is on the Bump State pulse, the tap ripple, and nothing else. A
 * palette with one meaningfully-used accent reads as considered; the same palette with the
 * accent sprinkled on buttons, headers and borders reads as a template.
 *
 * Anti-patterns explicitly avoided (see docs/DESIGN-SYSTEM.md):
 *  - purple/indigo → pink gradients, the "AI crypto app" default
 *  - neon green on black for "success"
 *  - cyan-on-dark "hacker terminal" styling
 *  - more than one accent hue
 *  - drop shadows; depth comes from 1px hairlines and value steps instead
 */
object BumpPayPalette {

    /** Background, in three ascending value steps. Depth without shadows. */
    val Ink0 = Color(0xFF08090B)
    val Ink1 = Color(0xFF101216)
    val Ink2 = Color(0xFF1A1D23)

    /** Structural separation. One pixel, low contrast, never a "border colour". */
    val Hairline = Color(0xFF2A2F38)

    val TextPrimary = Color(0xFFF2F4F7)
    val TextSecondary = Color(0xFF9AA3AF)
    val TextTertiary = Color(0xFF646C7A)

    /** The single accent. Radio/signal semantics only. */
    val Signal = Color(0xFF6FE3D2)
    val SignalDim = Color(0xFF2E6E66)
    val SignalWash = Color(0x1A6FE3D2)

    /** Settled. A desaturated mint — a settled payment should feel calm, not triumphant. */
    val Settled = Color(0xFF7BE0A8)

    /** Over-limit / needs attention. Amber, not red: it is a prompt, not a failure. */
    val Attention = Color(0xFFE8C07A)

    /** Revoke. Terracotta rather than fire-engine red — serious, not alarming. */
    val Revoke = Color(0xFFE5776B)
}

/**
 * Typography.
 *
 * Two families, both of which ship with Android, so there is no binary font asset in the
 * repository and nothing that can render as a fallback on a judge's device:
 *
 *  - `SansSerif` for prose and labels. Human voice.
 *  - `Monospace` for every amount, address and countdown. Machine voice.
 *
 * The monospace choice is functional, not stylistic. Amounts that shift horizontally as
 * digits change look broken in a payments app, and a monospaced face gives tabular figures
 * for free — the same reason a bank statement is set that way.
 */
object BumpPayType {

    val Display = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Light,
        fontSize = 44.sp,
        lineHeight = 48.sp,
        letterSpacing = (-1.2).sp,
    )

    val Title = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.2).sp,
    )

    val Body = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    )

    val Label = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.3.sp,
    )

    /** Amounts, addresses, transaction signatures. Always this. */
    val Money = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 32.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.5).sp,
    )

    val MoneySmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    )
}

/**
 * Motion.
 *
 * Three springs, applied consistently rather than tuned per animation. The brief asks for
 * physics-based motion and explicitly warns against defaulting to `tween()` fades, which
 * is the single most recognisable "generated template" tell.
 *
 * Reusing three named specs is what makes the app feel like it has one physical
 * personality rather than five screens each doing their own thing.
 */
object Motion {

    /**
     * Idle and rewarding motion. The Bump State pulse and the Success state both use this.
     * A low damping ratio produces visible overshoot, which is what reads as "alive".
     */
    val Playful: SpringSpec<Float> = spring(dampingRatio = 0.42f, stiffness = 180f)

    /** Tap feedback. Fast enough to feel instantaneous at ~150ms to settle. */
    val Snappy: SpringSpec<Float> = spring(dampingRatio = 0.75f, stiffness = 900f)

    /**
     * The committing action. Damping ratio 1.0 means critically damped — no overshoot, no
     * bounce. Revoking a session should not be playful, and encoding that in the motion
     * rather than in a warning dialog is a small, deliberate signal.
     */
    val Firm: SpringSpec<Float> = spring(dampingRatio = 1.0f, stiffness = 1400f)

    /** Screen-to-screen transitions. Shared by every route so navigation is coherent. */
    val Transition: SpringSpec<Float> = spring(dampingRatio = 0.6f, stiffness = 380f)

    /** One full breath of the idle pulse. Slow on purpose: "listening", not "loading". */
    const val PULSE_PERIOD_MS = 2000

    /** How long the success state lingers before returning to Bump State. */
    const val SUCCESS_DWELL_MS = 1600L
}

/** Spacing scale. Deliberately generous — the brief asks for low visual density. */
object Space {
    val xs = 4.dp
    val sm = 8.dp
    val md = 16.dp
    val lg = 24.dp
    val xl = 40.dp
}

private val BumpPayColorScheme = darkColorScheme(
    primary = BumpPayPalette.Signal,
    onPrimary = BumpPayPalette.Ink0,
    primaryContainer = BumpPayPalette.SignalDim,
    onPrimaryContainer = BumpPayPalette.TextPrimary,
    secondary = BumpPayPalette.TextSecondary,
    onSecondary = BumpPayPalette.Ink0,
    tertiary = BumpPayPalette.Settled,
    onTertiary = BumpPayPalette.Ink0,
    background = BumpPayPalette.Ink0,
    onBackground = BumpPayPalette.TextPrimary,
    surface = BumpPayPalette.Ink1,
    onSurface = BumpPayPalette.TextPrimary,
    surfaceVariant = BumpPayPalette.Ink2,
    onSurfaceVariant = BumpPayPalette.TextSecondary,
    outline = BumpPayPalette.Hairline,
    outlineVariant = BumpPayPalette.Hairline,
    error = BumpPayPalette.Revoke,
    onError = BumpPayPalette.Ink0,
    scrim = Color(0xCC000000),
)

@Composable
fun BumpPayTheme(
    // The app is dark-only by design; the parameter exists so a preview can force it and so
    // the intent is explicit rather than an accident of the color scheme being dark.
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = BumpPayColorScheme,
        typography = Typography(
            displayLarge = BumpPayType.Display,
            titleMedium = BumpPayType.Title,
            bodyMedium = BumpPayType.Body,
            labelMedium = BumpPayType.Label,
        ),
        content = content,
    )
}
