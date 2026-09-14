package app.bumppay.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.bumppay.core.solana.Amounts
import app.bumppay.core.solana.BumpPayDemo
import app.bumppay.session.SessionPhase
import app.bumppay.ui.theme.BumpPayPalette
import app.bumppay.ui.theme.BumpPayType
import app.bumppay.ui.theme.Motion
import app.bumppay.ui.theme.Space
import kotlin.math.ceil

/**
 * The five screens.
 *
 * The design brief's philosophy is "invisible execution": the app's value happens in the
 * background during a physical tap, and the UI is five small moments rather than a product
 * surface. The concrete rule that keeps this honest — **if a screen needs to scroll, it has
 * grown something that does not belong.**
 *
 * Navigation is `AnimatedContent` over a derived screen value rather than a NavHost, because
 * the destination is genuinely a function of session state (settled -> success, disarmed ->
 * bump) and letting two independent routers disagree about where the user is would be a
 * needless source of bugs in a 400ms interaction.
 */
private enum class Screen { Connect, Setup, Bump, Success }

@Composable
fun BumpPayApp(viewModel: BumpPayViewModel) {
    val phase by viewModel.phase.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    val walletAddress by viewModel.walletAddress.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val pendingApproval by viewModel.pendingApproval.collectAsStateWithLifecycle()
    val nfcReadiness by viewModel.nfcReadiness.collectAsStateWithLifecycle()
    val lastSignature by viewModel.lastSignature.collectAsStateWithLifecycle()

    var showRevokeDialog by remember { mutableStateOf(false) }

    val screen = when {
        walletAddress == null && session == null -> Screen.Connect
        session == null -> Screen.Setup
        phase is SessionPhase.Settled -> Screen.Success
        else -> Screen.Bump
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = BumpPayPalette.Ink0,
    ) {
        AnimatedContent(
            targetState = screen,
            // One spring spec for every transition, so the app has a single motion
            // signature instead of each screen inventing its own.
            transitionSpec = {
                (fadeIn(animationSpec = Motion.Transition) +
                    scaleIn(animationSpec = Motion.Transition, initialScale = 0.97f))
                    .togetherWith(
                        fadeOut(animationSpec = Motion.Transition) +
                            scaleOut(animationSpec = Motion.Transition, targetScale = 1.02f),
                    )
            },
            label = "screen",
        ) { target ->
            when (target) {
                Screen.Connect -> ConnectWalletScreen(busy = busy) { viewModel.connectWallet() }

                Screen.Setup -> SessionSetupScreen(
                    busy = busy,
                    onConfirm = { merchant, limit, alwaysAsk ->
                        viewModel.setupSession(merchant, limit, alwaysAsk)
                    },
                )

                Screen.Bump -> BumpStateScreen(
                    phase = phase,
                    session = session,
                    nfcReady = nfcReadiness.allGood,
                    signature = lastSignature,
                    onRevokeRequested = { showRevokeDialog = true },
                )

                Screen.Success -> SuccessScreen(
                    phase = phase,
                    signature = lastSignature,
                    onDismiss = { viewModel.acknowledgeSettlement() },
                )
            }
        }

        // Transient messages. Deliberately a small inline banner rather than a Snackbar:
        // a Snackbar would cover the idle glyph, which is the one thing that must always
        // stay visible.
        if (message != null) {
            LaunchedEffect(message) {
                kotlinx.coroutines.delay(4_000)
                viewModel.consumeMessage()
            }
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                MessageBanner(
                    text = message!!.text,
                    isError = message!!.isError,
                    modifier = Modifier.padding(bottom = Space.xl),
                )
            }
        }

        // Phase 4: an over-limit tap must raise a real approval path, never fail silently.
        if (pendingApproval != null && session != null) {
            OverLimitDialog(
                amountText = Amounts.format(
                    pendingApproval!!.amountBaseUnits,
                    pendingApproval!!.decimals,
                    "USDC",
                ),
                busy = busy,
                onApprove = { viewModel.approveOverLimitPayment() },
                onDismiss = { viewModel.dismissOverLimitPrompt() },
            )
        }

        if (showRevokeDialog) {
            RevokeDialog(
                busy = busy,
                onConfirm = {
                    showRevokeDialog = false
                    viewModel.revokeSession()
                },
                onDismiss = { showRevokeDialog = false },
            )
        }
    }
}

// =========================================================================================
// 1. Connect Wallet
// =========================================================================================

@Composable
private fun ConnectWalletScreen(busy: Boolean, onConnect: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(Space.xl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start,
    ) {
        NfcGlyph(active = false, scale = 1f, alpha = 0.5f, size = 64.dp)

        Spacer(Modifier.height(Space.lg))

        Text("BumpPay", style = BumpPayType.Display, color = BumpPayPalette.TextPrimary)
        Spacer(Modifier.height(Space.xs))
        Text("Tap. Settle. Done.", style = BumpPayType.Title, color = BumpPayPalette.Signal)

        Spacer(Modifier.height(Space.md))
        Text(
            "Settle a payment by touching two phones. One confirmation sets the limit for " +
                "the day — after that there is nothing to approve.",
            style = BumpPayType.Body,
            color = BumpPayPalette.TextSecondary,
        )

        Spacer(Modifier.height(Space.xl))

        PrimaryButton(
            text = if (busy) "Connecting…" else "Connect Wallet",
            enabled = !busy,
            onClick = onConnect,
        )

        Spacer(Modifier.height(Space.sm))
        Text(
            "Works with Seed Vault, Phantom and Solflare.",
            style = BumpPayType.Label,
            color = BumpPayPalette.TextTertiary,
        )
    }
}

// =========================================================================================
// 2. Session Setup — the one screen with real information density
// =========================================================================================

@Composable
private fun SessionSetupScreen(
    busy: Boolean,
    onConfirm: (merchant: String, limitBaseUnits: Long, alwaysAsk: Boolean) -> Unit,
) {
    // $10 to $500 in whole dollars, plus an explicit "always ask" state at zero.
    var dollars by remember { mutableStateOf(50f) }
    var alwaysAsk by remember { mutableStateOf(false) }
    var merchant by remember { mutableStateOf(BumpPayDemo.MERCHANT_ADDRESS) }

    val decimals = BumpPayDemo.USDC_DECIMALS

    Column(
        modifier = Modifier.fillMaxSize().padding(Space.xl),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Set your limit", style = BumpPayType.Title, color = BumpPayPalette.TextPrimary)
        Spacer(Modifier.height(Space.sm))
        Text(
            "Any tap up to this amount settles on its own. Above it, you approve that one payment.",
            style = BumpPayType.Body,
            color = BumpPayPalette.TextSecondary,
        )

        Spacer(Modifier.height(Space.xl))

        // The headline figure animates rather than snapping as the slider moves. A small
        // detail that does a disproportionate amount of work for how premium this feels.
        val limitDollars by animateFloatAsState(
            targetValue = if (alwaysAsk) 0f else ceil(dollars).toFloat(),
            animationSpec = Motion.Snappy,
            label = "limitDollars",
        )

        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = if (alwaysAsk) "Ask" else "$${limitDollars.toInt()}",
                style = BumpPayType.Money.copy(fontSize = 48.sp),
                color = if (alwaysAsk) BumpPayPalette.Attention else BumpPayPalette.Signal,
            )
            Spacer(Modifier.width(Space.sm))
            Text(
                text = if (alwaysAsk) "every time" else "per tap",
                style = BumpPayType.Label,
                color = BumpPayPalette.TextTertiary,
                modifier = Modifier.padding(bottom = 10.dp),
            )
        }

        Spacer(Modifier.height(Space.md))

        Slider(
            value = dollars,
            onValueChange = { dollars = it },
            valueRange = 10f..500f,
            enabled = !busy,
            colors = SliderDefaults.colors(
                thumbColor = BumpPayPalette.Signal,
                activeTrackColor = BumpPayPalette.Signal,
                inactiveTrackColor = BumpPayPalette.Ink2,
            ),
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Space.xs),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("$10", style = BumpPayType.MoneySmall, color = BumpPayPalette.TextTertiary)
            Text("$500", style = BumpPayType.MoneySmall, color = BumpPayPalette.TextTertiary)
        }

        Spacer(Modifier.height(Space.lg))

        ChoiceRow(
            label = "Always ask",
            detail = "Require a confirmation for every payment.",
            selected = alwaysAsk,
            enabled = !busy,
            onClick = { alwaysAsk = !alwaysAsk },
        )

        Spacer(Modifier.height(Space.lg))

        OutlinedTextField(
            value = merchant,
            onValueChange = { merchant = it },
            label = { Text("Merchant address", style = BumpPayType.Label) },
            singleLine = true,
            enabled = !busy,
            textStyle = BumpPayType.MoneySmall,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(Space.lg))

        PrimaryButton(
            text = if (busy) "Opening wallet…" else "Confirm limit",
            enabled = !busy && merchant.isNotBlank(),
            onClick = {
                // Dollars -> base units. No floating point past this line: the slider is a
                // UI affordance, the amount that gets signed is exact.
                val baseUnits = if (alwaysAsk) 0L else {
                    Amounts.toBaseUnits(java.math.BigDecimal(limitDollars.toInt().toString()), decimals)
                }
                onConfirm(merchant.trim(), baseUnits, alwaysAsk)
            },
        )

        Spacer(Modifier.height(Space.sm))
        Text(
            "One confirmation now. None per tap.",
            style = BumpPayType.Label,
            color = BumpPayPalette.TextTertiary,
        )
    }
}

// =========================================================================================
// 3. Bump State — the signature screen
// =========================================================================================

@Composable
private fun BumpStateScreen(
    phase: SessionPhase,
    session: app.bumppay.session.SessionRecord?,
    nfcReady: Boolean,
    signature: String?,
    onRevokeRequested: () -> Unit,
) {
    val signing = phase is SessionPhase.Signing

    // The idle pulse. An infinite transition needs a finite spec per iteration, so this is
    // the one place a tween is correct: springs govern every *state change* in the app, and
    // the breathing cycle is not a state change. Everything that responds to an event still
    // uses the shared springs.
    val pulse = rememberInfiniteTransition(label = "idle-pulse")
    val pulseScale by pulse.animateFloat(
        initialValue = 1f,
        targetValue = if (signing) 1.18f else 1.10f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (signing) 320 else Motion.PULSE_PERIOD_MS / 2,
                easing = LinearOutSlowInEasing,
            ),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseScale",
    )
    val pulseAlpha by pulse.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (signing) 320 else Motion.PULSE_PERIOD_MS / 2,
                easing = LinearOutSlowInEasing,
            ),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseAlpha",
    )

    // The tap ripple: fires the instant a tap is detected, so the video shows something
    // happened at the moment of contact rather than cutting straight to success.
    val ripple = remember { Animatable(0f) }
    LaunchedEffect(signing) {
        if (signing) {
            ripple.snapTo(0f)
            ripple.animateTo(1f, animationSpec = Motion.Snappy)
        }
    }

    val armed = phase is SessionPhase.Armed

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.Center) {
            NfcGlyph(
                active = armed || signing,
                scale = pulseScale,
                alpha = if (armed || signing) pulseAlpha else 0.2f,
                size = 180.dp,
            )

            if (ripple.value > 0f && ripple.value < 1f) {
                Canvas(modifier = Modifier.size(180.dp)) {
                    val radius = size.minDimension / 2f * (0.4f + ripple.value)
                    drawCircle(
                        color = BumpPayPalette.Signal.copy(alpha = (1f - ripple.value) * 0.6f),
                        radius = radius,
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
            }
        }

        Spacer(Modifier.height(Space.xl))

        Text(
            text = when {
                signing -> "Signing…"
                armed -> "Ready to tap"
                else -> "Session not active"
            },
            style = BumpPayType.Title,
            color = if (armed || signing) BumpPayPalette.TextPrimary else BumpPayPalette.Attention,
        )

        Spacer(Modifier.height(Space.xs))

        Text(
            text = when {
                !nfcReady -> "NFC is turned off"
                signing -> "Settling now"
                armed -> "Hold your phone against the terminal"
                else -> "Unlock and reopen BumpPay to arm the session"
            },
            style = BumpPayType.Body,
            color = BumpPayPalette.TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = Space.xl),
        )

        if (session != null && session.limitBaseUnits > 0) {
            Spacer(Modifier.height(Space.lg))
            Text(
                text = Amounts.format(session.remainingBaseUnits, session.decimals, "USDC"),
                style = BumpPayType.Money,
                color = BumpPayPalette.TextPrimary,
            )
            Text(
                text = "remaining this session",
                style = BumpPayType.Label,
                color = BumpPayPalette.TextTertiary,
            )
        }

        Spacer(Modifier.height(Space.xl))

        // Revoke is always visible, never buried. It is the one action a nervous user
        // reaches for, and hiding it behind a menu signals the opposite of what the
        // product claims about control.
        TextButton(onClick = onRevokeRequested) {
            Text(
                "Revoke session",
                style = BumpPayType.Label,
                color = BumpPayPalette.Revoke,
            )
        }
    }
}

// =========================================================================================
// 4. Success
// =========================================================================================

@Composable
private fun SuccessScreen(
    phase: SessionPhase,
    signature: String?,
    onDismiss: () -> Unit,
) {
    val settled = phase as? SessionPhase.Settled
    val haptics = LocalHapticFeedback.current

    // Motion and haptic land in the same frame. Firing the haptic after the animation, or
    // from a separate effect, is the difference between "rewarding" and "laggy".
    val draw = remember { Animatable(0f) }
    LaunchedEffect(settled) {
        if (settled != null) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            draw.snapTo(0f)
            draw.animateTo(1f, animationSpec = Motion.Playful)
        }
    }

    LaunchedEffect(settled) {
        if (settled != null) {
            kotlinx.coroutines.delay(Motion.SUCCESS_DWELL_MS)
            onDismiss()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(Space.xl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Checkmark(progress = draw.value)

        Spacer(Modifier.height(Space.lg))

        Text(
            text = settled?.let {
                Amounts.format(it.amountBaseUnits, it.decimals, "USDC")
            } ?: "Settled",
            style = BumpPayType.Money.copy(fontSize = 40.sp),
            color = BumpPayPalette.Settled,
        )

        Spacer(Modifier.height(Space.xs))
        Text("Settled", style = BumpPayType.Label, color = BumpPayPalette.TextSecondary)

        if (signature != null) {
            Spacer(Modifier.height(Space.lg))
            Text(
                text = signature.take(24) + "…",
                style = BumpPayType.MoneySmall,
                color = BumpPayPalette.TextTertiary,
            )
        }
    }
}

/**
 * The checkmark, drawn rather than faded in.
 *
 * Implemented as two stroked segments whose end points are interpolated by [progress],
 * which sidesteps `PathMeasure` entirely and gives exact control over the "draw" feel: the
 * short stroke completes first, then the long one. A static icon fading in reads as a
 * placeholder; a path drawing on reads as confirmation.
 */
@Composable
private fun Checkmark(progress: Float) {
    Canvas(modifier = Modifier.size(120.dp)) {
        val w = size.width
        val h = size.height

        val color = BumpPayPalette.Settled
        val stroke = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)

        // Short arm: 0 -> 0.55 of the animation.
        val shortArm = (progress / 0.55f).coerceIn(0f, 1f)
        val shortStart = Offset(w * 0.20f, h * 0.52f)
        val shortEnd = Offset(w * 0.42f, h * 0.72f)
        drawLine(
            color = color,
            start = shortStart,
            end = Offset(
                shortStart.x + (shortEnd.x - shortStart.x) * shortArm,
                shortStart.y + (shortEnd.y - shortStart.y) * shortArm,
            ),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round,
        )

        // Long arm: 0.45 -> 1.0, so the two overlap slightly and never look disjointed.
        val longArm = ((progress - 0.45f) / 0.55f).coerceIn(0f, 1f)
        val longStart = shortEnd
        val longEnd = Offset(w * 0.80f, h * 0.30f)
        if (longArm > 0f) {
            drawLine(
                color = color,
                start = longStart,
                end = Offset(
                    longStart.x + (longEnd.x - longStart.x) * longArm,
                    longStart.y + (longEnd.y - longStart.y) * longArm,
                ),
                strokeWidth = stroke.width,
                cap = StrokeCap.Round,
            )
        }
    }
}

// =========================================================================================
// Shared pieces
// =========================================================================================

/**
 * The NFC / proximity glyph: a contact point emitting three arcs.
 *
 * Drawn rather than loaded from a drawable so the pulse can drive arc opacity and radius
 * directly, and so there is no raster asset to look soft on a judge's screen.
 */
@Composable
private fun NfcGlyph(
    active: Boolean,
    scale: Float,
    alpha: Float,
    size: androidx.compose.ui.unit.Dp,
) {
    val color = if (active) BumpPayPalette.Signal else BumpPayPalette.TextTertiary

    Canvas(
        modifier = Modifier
            .size(size)
            .background(Color.Transparent),
    ) {
        val dimension = this.size.minDimension
        val scaleFactor = scale

        // Three arcs, each further out and fainter: the field at increasing distance.
        val arcs = listOf(
            Triple(0.30f, 0.30f, 1.0f),
            Triple(0.44f, 0.34f, 0.62f),
            Triple(0.58f, 0.38f, 0.34f),
        )

        arcs.forEach { (radiusFraction, _, arcAlpha) ->
            val radius = dimension * radiusFraction * scaleFactor
            drawArc(
                color = color.copy(alpha = alpha * arcAlpha),
                startAngle = -55f,
                sweepAngle = 110f,
                useCenter = false,
                topLeft = Offset(dimension / 2f - radius, dimension / 2f - radius),
                size = Size(radius * 2f, radius * 2f),
                style = Stroke(width = dimension * 0.035f, cap = StrokeCap.Round),
            )
        }

        // The contact point.
        drawCircle(
            color = color.copy(alpha = alpha),
            radius = dimension * 0.055f * scaleFactor,
            center = Offset(dimension / 2f, dimension / 2f),
        )
    }
}

@Composable
private fun PrimaryButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = BumpPayPalette.Signal,
            contentColor = BumpPayPalette.Ink0,
            disabledContainerColor = BumpPayPalette.Ink2,
            disabledContentColor = BumpPayPalette.TextTertiary,
        ),
        modifier = Modifier.fillMaxWidth().height(54.dp),
    ) {
        Text(text, style = BumpPayType.Title.copy(fontSize = 17.sp))
    }
}

@Composable
private fun ChoiceRow(
    label: String,
    detail: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(
                width = 1.dp,
                color = if (selected) BumpPayPalette.Signal else BumpPayPalette.Hairline,
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .border(
                    width = 2.dp,
                    color = if (selected) BumpPayPalette.Signal else BumpPayPalette.Hairline,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(BumpPayPalette.Signal),
                )
            }
        }

        Spacer(Modifier.width(Space.md))

        Column {
            Text(label, style = BumpPayType.Label, color = BumpPayPalette.TextPrimary)
            Text(detail, style = BumpPayType.Body.copy(fontSize = 13.sp), color = BumpPayPalette.TextTertiary)
        }
    }
}

@Composable
private fun MessageBanner(text: String, isError: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(horizontal = Space.lg)
            .clip(RoundedCornerShape(12.dp))
            .background(if (isError) BumpPayPalette.Ink2 else BumpPayPalette.SignalWash)
            .border(
                width = 1.dp,
                color = if (isError) BumpPayPalette.Revoke.copy(alpha = 0.5f) else BumpPayPalette.SignalDim,
                shape = RoundedCornerShape(12.dp),
            )
            .padding(horizontal = Space.md, vertical = Space.sm),
    ) {
        Text(
            text = text,
            style = BumpPayType.Body.copy(fontSize = 14.sp),
            color = if (isError) BumpPayPalette.Revoke else BumpPayPalette.TextPrimary,
        )
    }
}

@Composable
private fun OverLimitDialog(
    amountText: String,
    busy: Boolean,
    onApprove: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BumpPayPalette.Ink1,
        titleContentColor = BumpPayPalette.TextPrimary,
        textContentColor = BumpPayPalette.TextSecondary,
        title = { Text("Over your limit", style = BumpPayType.Title) },
        text = {
            Text(
                "This tap needs $amountText, which is above your session limit. " +
                    "Approve this one payment?",
                style = BumpPayType.Body,
            )
        },
        confirmButton = {
            TextButton(onClick = onApprove, enabled = !busy) {
                Text("Approve", color = BumpPayPalette.Signal, style = BumpPayType.Label)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text("Not now", color = BumpPayPalette.TextSecondary, style = BumpPayType.Label)
            }
        },
    )
}

@Composable
private fun RevokeDialog(busy: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BumpPayPalette.Ink1,
        titleContentColor = BumpPayPalette.TextPrimary,
        textContentColor = BumpPayPalette.TextSecondary,
        title = { Text("Revoke session?", style = BumpPayType.Title) },
        text = {
            Text(
                "This removes the delegation on-chain immediately. Taps will stop working " +
                    "until you set a new limit.",
                style = BumpPayType.Body,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !busy) {
                Text("Revoke", color = BumpPayPalette.Revoke, style = BumpPayType.Label)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text("Keep session", color = BumpPayPalette.TextSecondary, style = BumpPayType.Label)
            }
        },
    )
}
