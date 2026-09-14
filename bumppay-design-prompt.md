# Agent Design Prompt: BumpPay

Paste below to your coding agent, after the build prompt has been set up. Run the skill installs first, as separate commands, before pasting the brief.

---

## 0. Install the skills (run these first, separately)

```
# Design system generator — industry-aware colors/typography/patterns, supports Jetpack Compose
/plugin marketplace add nextlevelbuilder/ui-ux-pro-max-skill
/plugin install ui-ux-pro-max@ui-ux-pro-max-skill

# Anti-slop taste enforcement for whatever UI code gets generated
npx skills add https://github.com/Leonxlnx/taste-skill --skill "design-taste-frontend"

# Optional — web-only, see note in §4 before using
/plugin marketplace add 0xdesign/design-plugin
/plugin install design-and-refine@design-plugins
```

---

## 1. Design Brief

**Product:** BumpPay — tap-to-pay Solana settlement app, Jetpack Compose, Android.

**Core design philosophy (non-negotiable — carry this through every decision):** *invisible execution*. The app's value happens in the background during a physical tap. The UI is not the product — it's five small moments: connect, set a limit, wait, confirm success, revoke. Resist the instinct to fill space or add screens. A cluttered or "featureful"-looking BumpPay is a design failure, not a win.

**Brand direction:** hardware-native, not generic-crypto. This should read as something that could only exist on Seeker — not a wrapped web app, not a generic fintech template, and not default "AI purple-gradient" crypto-app slop. Lean into precision and physicality: NFC/radio-adjacent visual language (pulse, proximity, signal), confident restraint over decoration.

**Target reviewer:** hackathon judges scoring on UX (25%) and Presentation (25%) via a 3-minute demo video — the design has to read clearly and feel premium at a glance, on camera, not just in prolonged use.

---

## 2. Step 1 — Generate the design system (ui-ux-pro-max)

Run the design-system generator targeting the closest matching category (Fintech/Crypto, or Web3/NFT if the reasoning rules distinguish payments specifically) with the Jetpack Compose stack:

```
python3 .claude/skills/ui-ux-pro-max/scripts/search.py "solana nfc tap-to-pay crypto payments mobile" --design-system -p "BumpPay" --persist
```

Then, in the same tool call, generate the Android/Compose-specific implementation guidelines:

```
python3 .claude/skills/ui-ux-pro-max/scripts/search.py "compact confirmation states, haptic feedback" --stack jetpack-compose
```

Report back the generated pattern, palette, typography pairing, and — critically — the **anti-patterns list**, before writing any Compose code. If the recommended palette leans toward generic crypto colors (purple/pink gradients, neon green), flag it against the brand direction in §1 and propose an alternative before proceeding.

---

## 3. Step 2 — Apply taste-skill dials

Do not use taste-skill's defaults blind — tune the three dials deliberately for this specific app, and state your reasoning for each before generating any screen:

- **DESIGN_VARIANCE:** low-to-mid. Five screens, each doing one job — this isn't the place for experimental asymmetric layout. Restraint should look deliberate, not empty.
- **MOTION_INTENSITY:** low baseline, with exactly two moments of real motion: the idle "pulse" on the Bump State screen (subtle, continuous, signals "ready"), and the Success State checkmark + haptic (sharp, immediate, rewarding). Everything else should be still.
- **VISUAL_DENSITY:** low. Five screens total; none of them should feel like a dashboard. If any screen needs a scroll to see everything, that's a signal you've added something that shouldn't be there.

Apply the `soft-skill` (high-end-visual-design) variant for the visual register — calm, premium, generous whitespace — rather than the brutalist or minimalist-editorial variants, since "invisible execution" reads better as understated confidence than as stark utility.

---

## 4. Animation Spec (this is a payments app judged on a 3-minute video — motion quality carries real weight)

taste-skill's canonical motion code is GSAP/web-oriented and won't transfer directly to Compose. Don't let the agent default to plain `tween()` fades — that's exactly the "generic template" outcome §1 is trying to avoid. Require physics-based motion (Compose's `spring()` / `Animatable`) throughout, not linear/ease-in-out tweens, and hold every animation to this spec:

- **Bump State idle pulse:** the signature animation — this is what's on screen for most of the demo. Use `rememberInfiniteTransition` with a slow (~2s) breathing scale/opacity cycle on the NFC icon, `spring(dampingRatio = Spring.DampingRatioLowBouncy)` rather than a robotic linear pulse. Should read as "listening," not "loading."
- **Tap detected → signing:** the moment between physical tap and success needs its own micro-state, even though it's milliseconds — a quick radial ripple or icon morph outward from the tap point, so the demo video visibly shows "something happened" the instant the phones touch, not just a jump-cut to the success screen.
- **Success State:** checkmark should draw on via `AnimatedVisibility` + a path/morph animation (not a static icon fading in), synced tightly to the haptic buzz — motion and haptic should land in the same frame, not sequentially. Auto-dismiss back to Bump State with a spring-based scale-down, not a fade.
- **Screen-to-screen transitions** (Connect → Session Setup → Bump State): use Compose's shared-element / `AnimatedContent` transitions with a consistent spring spec across all of them — same damping/stiffness values everywhere, so the app has one coherent motion signature rather than each screen inventing its own.
- **Session Setup slider:** the limit value should animate (not snap) as the slider moves — small detail, disproportionately affects "premium" feel.
- **Revoke action:** deliberately un-springy — a firmer, faster ease-out. This is the one interaction that should feel serious/final rather than playful, as a subtle signal to the user that it's a committing action.

Define one shared `SpringSpec` (or small set of 2–3: one for playful/idle motion, one firm one for committing actions like Revoke) and reuse it everywhere rather than hand-tuning each animation individually — report the chosen spring parameters back before implementing all five screens.

---

## 5. Step 3 — design-and-refine (scoped, optional)

**Do not point this at the Compose app.** It has no Android/Kotlin support and will not produce usable output for `app/ui/`.

Use it only if/when building a separate web landing page or pitch-deck microsite for the submission (judges will see this alongside the demo video). If you build one:

```
/design-and-refine:start BumpPay landing page
```

Answer its interview with the brand direction from §1. Let it generate the 5 variations, review at `/__design_lab`, and finalize to `DESIGN_PLAN.md` before implementing.

If no separate landing page is planned, skip this step entirely — the pitch deck itself covers that surface.

---

## 6. Screens to Design (Compose, via ui-ux-pro-max + taste-skill output)

In this order, each as its own acceptance checkpoint — don't move to the next until the current one matches the brief in §1:

1. **Connect Wallet** — single button, "Connect Wallet," triggers MWA session
2. **Session Setup** — spend-limit slider ($10–$500) or "always ask" ($0); this is the one screen with real information density, keep it to the slider + a single confirm action, nothing else
3. **Bump State (idle)** — the signature screen: pulsing NFC icon, minimal chrome, "ready to tap"
4. **Success State** — checkmark flash + haptic, auto-dismisses back to Bump State after a beat
5. **Revoke Session** — always accessible (not buried in a settings menu), single deliberate action

---

## 7. What to report back before implementing

- The generated design system's palette, typography, and anti-pattern list (§2)
- Your dial reasoning for VARIANCE/MOTION/DENSITY (§3)
- The shared spring spec(s) chosen for animation (§4)
- Any point where the design-system's default recommendation conflicted with §1's brand direction, and what you changed
- Whether a companion landing page is in scope (determines if §5 applies)

Wait for my sign-off on these before generating final Compose code for all five screens.
