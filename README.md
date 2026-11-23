# Arrow Helper — Android overlay for arrow-escape puzzles

A floating toolbar that sits over the game, reads the arrow grid from the screen,
works out the order to tap, and either shows the numbers (Hint) or taps for you (Auto).

## Build & install
1. Open this folder in **Android Studio** (Koala or newer). Let it sync Gradle — it downloads everything it needs.
2. Plug in your phone with USB debugging on and press **Run ▶**.
   Requirements: Android 8.0+ (tested target: Android 15 / API 35).

## One-time setup on the phone
1. Open **Arrow Helper**.
2. Enter the puzzle's **rows** and **columns**.
3. Tap **1. Allow drawing over other apps** → turn it on.
4. Tap **2. Turn on auto-tap** → Accessibility → Arrow Helper → On (only needed for Auto).
   On Android 13+, if the switch is greyed out: Settings → Apps → Arrow Helper → ⋮ → *Allow restricted settings*.
5. Android 13+: tap **3. Allow notification**.

## Using it
1. Tap **▶ Start overlay** and accept the screen-recording prompt (choose *entire screen*).
2. Open the game. Drag the toolbar by **⠿** so it isn't over the puzzle.
3. **Grid** → drag the cyan box's corners so it covers the puzzle exactly → **Save**. You only redo this if the layout changes.
4. **Hint** → numbered circles show the tap order (yellow = next). Tap Hint again to clear.
   The small blue arrow on each cell is what the app *thinks* the arrow is. Red **!** = an arrow that can never leave, which usually means the grid box or detection is off.
5. **Auto** → taps every arrow in order. Tap **Stop**, or touch the screen, to cancel.

## Tuning
| Problem | Fix |
|---|---|
| Arrows missed / "No arrows found" | Lower *Detection sensitivity* (e.g. 60) |
| Empty cells detected as arrows | Raise it (e.g. 130) |
| Wrong directions | Make sure the grid box lines up with the cells exactly |
| Auto taps too fast for the animation | Increase *Pause between auto-taps* |

## How it works
| File | What it does |
|---|---|
| `Puzzle.kt` | `ArrowDetector` (reads each cell's arrow from the screenshot) and `Solver` (greedy: tap any arrow with a clear path to the edge, repeat) |
| `ScreenCapturer.kt` | MediaProjection → ImageReader, keeps the latest frame |
| `OverlayService.kt` | Floating toolbar, Hint/Auto logic |
| `OverlayViews.kt` | Grid-setup box and hint drawing |
| `TapService.kt` | Accessibility service that sends taps (`dispatchGesture`) |

## Current limits
- Assumes **one straight arrow per cell** and the "flies off the board if its path is clear" rule.
  Snake-shaped arrows that span several cells, or rotating-arrow puzzles, need a different detector or solver.
- Portrait orientation; the grid box must be re-set if the board moves or zooms.
