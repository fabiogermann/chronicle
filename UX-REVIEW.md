# Chronicle — UX & Visual Review

*Review date: 2026-07-06. Companion document: [CODE-REVIEW-FINDINGS.md](CODE-REVIEW-FINDINGS.md) (technical/code findings). This document covers user-facing UX, visual design, accessibility, and platform-polish improvements.*

## Context

- **Theme:** `Theme.Material3.Dark.NoActionBar` — Material 3 base, but forced dark only. No DayNight, no `values-night`, no light theme, no dynamic color (`DynamicColors` never applied).
- **Navigation:** custom `Navigator` + manual fragment transactions (no Jetpack Navigation). Bottom nav: Home / Library / Collections (conditional) / Settings. Player is a bottom-sheet overlay; onboarding is a 4-screen flow (login → server → user → library).
- **Image loading:** Fresco (Glide is dead weight — see code findings H9).
- **Large screens:** `values-land` only; no `sw600dp` resources.

---

## Quick wins (small changes, high visibility)

### 1. The selected bottom-nav tab is invisible

`res/layout/activity_main.xml:36-37` overrides the style's state-aware color selector with the flat `@color/icon` (= `textPrimary`), so **every tab renders identically regardless of selection**. The existing `Widget.BottomNavigationView` style (`styles.xml:147-148`) already uses the correct `state_checked` selector.

**Fix:** delete the two `app:itemIconTint`/`app:itemTextColor` overrides in the layout.

### 2. Player transport buttons are below the 48dp accessibility minimum

`res/layout/fragment_currently_playing.xml`: `skip_to_previous` (:42), `rewind_button` (:59), `skip_forward_button` (:91), `skip_to_next` (:107), and `sleep_timer_button` (:141) are all `@dimen/list_icon_size` = **32dp**. This fails WCAG/Material touch-target guidance on the app's core screen.

**Fix:** make the views 48dp (or set `minWidth`/`minHeight`), keeping the 32dp glyph via padding.

### 3. Mini-player: add a progress line and a rewind button

The mini-player (`activity_main.xml:63-125`) shows thumb + titles + a single play/pause. Users can't see position at a glance, and the single most common audiobook action — jump back — requires expanding the full sheet.

**Fix:** add a thin (2dp) progress indicator along the mini-player's top edge and at least a jump-back button (the Pocket Casts/Spotify pattern). Consider swipe-up-to-expand in addition to tap.

### 4. The now-playing screen hides its own titles

`fragment_currently_playing.xml:229,244` — `chapter_title` and `book_title` are `visibility="gone"`; the screen relies entirely on the collapsing toolbar. The current chapter is the primary orientation cue in an audiobook.

**Fix:** show book + chapter prominently under the artwork.

### 5. Replace Toasts with Snackbars

29 `Toast.makeText` call sites, 0 `Snackbar`. Toasts can't offer actions (Retry/Undo), don't anchor above the mini-player, and are easy to miss. The inline red connection banner on the details screen (`fragment_audiobook_details.xml:254`, tappable to retry) is the right pattern.

**Fix:** anchored Snackbars (above bottom nav/mini-player) for transient errors, with Retry actions where a retry exists; keep/extend the inline banner pattern for persistent connection state.

---

## Per-screen observations

### Home (`fragment_home.xml`)

Structure: toolbar with search; a `ScrollView` of three equal-weight horizontal carousels (Available Offline / Recently Listened / Recently Added); full-screen search results overlay.

- **"Continue listening" should be the hero.** It's currently just another 96dp carousel. Make it a large card — cover, progress bar, remaining time, resume button — at the top. It is the single most-used action in the app.
- **No first-load skeletons.** Cold start shows an empty screen, then content pops in. Add shimmer/skeleton placeholders for the carousels.
- **Empty state is a bare TextView** ("No books found", :48) with no illustration, explanation, or CTA. Give it an icon + guidance ("Connect a library", "Pull to refresh"). The offline empty state (:57) is better (has a button) but should match the same component.
- Search results replace the whole screen abruptly; consider the Material 3 `SearchBar`/`SearchView` expanding pattern.
- Technical: the plain `ScrollView` + nested RecyclerViews (:99) would be leaner as a single vertical RecyclerView with `ConcatAdapter`; `app:spanCount="3"` on LinearLayoutManager rows (:134, :181) is a dead attribute.

### Library (`fragment_library.xml`)

Structure: toolbar + grid in SwipeRefresh; rich hand-rolled filter/sort bottom sheet (sort chips, direction, hide-played, view style).

- **Distinguish "library is empty" from "filters hid everything"** — the current empty state (:101) is an error icon + "No books found" with no next step. Offer a "Clear filters" action when filters are active.
- The filter sheet ships with visible scaffolding: a `TODO` comment in the layout (:146) and ~120 lines of commented-out chips (:258-376). Finish or remove.
- `SwitchMaterial` at :274-278 uses `match_parent` + `layout_weight` inside a ConstraintLayout — params that don't apply; brittle.
- The grid item (`grid_item_audiobook.xml`) is good (rounded cover, progress bar, dog-ear, library color bar). Consider adding remaining time/duration to the card.

### Book details (`fragment_audiobook_details.xml`)

- The connection-failure banner (:254) is the app's best error pattern — keep it.
- Download state is a single icon that swaps to an indeterminate spinner (:107-123); use a **determinate progress ring** with queued/percent state.
- "More/Less" summary toggle is a bare button with no chevron affordance.
- Metadata is thin: no narrator, series, year, rating, or a visible "mark as finished". Worth surfacing what Plex provides.
- Loading uses a spinner (:222) — a skeleton of the chapter list would feel faster.

### Currently Playing (`fragment_currently_playing.xml`)

- **Speed and sleep timer are on-screen (good) but under-signposted:** speed is a tiny borderless text button (:123, AppCompat style), sleep timer an unlabeled 32dp icon. Promote both to labeled Material 3 chips showing current state ("1.5×", "Sleep 20m").
- **No gesture support:** no swipe on artwork for chapter change, no double-tap-to-seek. Transport is button-only.
- Track loading is a plain spinner (:337).
- The two stacked custom `BottomSheetChooser` views (:347, :354) predate M3 sheets; migrating to `BottomSheetDialogFragment` gets M3 styling, drag handles, and predictive-back behavior for free.
- See quick wins #2 (touch targets) and #4 (hidden titles).

### Onboarding / Login (`onboarding_login.xml` + `features/login/`)

- **The first screen is a lone "Login with Plex" button on a blank dark screen** — no logo, no tagline, no value proposition. Add branding and one line of copy ("Play your Plex audiobooks"), and set the expectation that a Plex account is required.
- **Auto-advance the 4-hop flow.** Login → server → user → library is inherent to Plex, but when there is exactly one server/user/library, skip that step. For most users this collapses four screens into one.
- The login button has no pressed/disabled state during the OAuth handoff, and the spinner defaults to `invisible` (:35) — show progress the moment the Custom Tab launches.
- The Plex-orange `#F0A732` is hardcoded inline (:23) and the button uses an M2 style (:13) — move to `colors.xml` / `Widget.Material3.Button`.

### Settings (`fragment_settings.xml` + `SettingsViewModel.kt`)

- A 983-line ViewModel hand-builds one **flat, ungrouped** preference list rendered by a custom RecyclerView. Users get a long undifferentiated scroll; accessibility semantics and large-screen two-pane layouts aren't free.
- **Fix:** group into sections (Playback / Downloads / Library / Account / About) with headers at minimum; better, migrate to AndroidX `PreferenceFragmentCompat` (grouping, summaries, dependencies, and search come built in).

---

## Cross-cutting

### Theming & visual consistency

- **Add DayNight + a light theme.** `styles.xml:4` hardcodes `Theme.Material3.Dark`; `windowLightStatusBar` is hardwired false. Users who prefer light or high-contrast light get nothing.
- **Enable dynamic color (Material You).** minSdk is 33, so `DynamicColors.applyToActivitiesIfAvailable(...)` is free brand-adaptive theming on every supported device; keep the current palette as the fallback.
- **Finish the Material 3 migration.** M2 `Widget.MaterialComponents.Button` (`onboarding_login.xml:13`, `fragment_home.xml:79`, `fragment_library.xml:74` — the latter two flattened by a `selectableItemBackground` override) and `Widget.AppCompat.*` styles (`fragment_currently_playing.xml:123`, `styles.xml:64,97,111,118`) remain. Standardize on `Widget.Material3.*`.
- Hardcoded color literals in layouts: `#D8FFFFFF` (`fragment_library.xml:283`), `#F0A732` (`onboarding_login.xml:23`) — move to `colors.xml`.

### Accessibility & internationalization

- **RTL is declared but not delivered:** `supportsRtl="true"` with 287 Left/Right attributes vs 61 Start/End (offenders include `fragment_home.xml`, `fragment_audiobook_details.xml:132`, `activity_main.xml:89`, `drawableLeft` in `onboarding_login.xml:16` and `toolbar_search_view.xml:14`). Migrate to Start/End and `drawableStart`.
- **No translations:** only `values/strings.xml` exists (274 strings, plurals already in place). Add at least one locale and wire up a translation pipeline.
- Mislabeled content description: the search magnifier icon has `contentDescription="@string/cancel"` (`toolbar_search_view.xml:41`). Overall labeling coverage is otherwise good.
- Text sizes are all correctly in `sp` (verified — no `dp` text sizes).
- Touch targets: see quick win #2.

### Platform surfaces & polish

- **Home-screen widget:** none exists. A continue-listening/playback widget is a natural fit for an audiobook player (Glance makes this cheap).
- **Haptics:** zero `performHapticFeedback` usage. Add subtle feedback on play/pause, seek, speed change, and sleep-timer set.
- **Predictive back:** not opted in (`enableOnBackInvokedCallback` missing — see code findings). Users on Android 14+ get no back-gesture preview.
- **Edge-to-edge:** insets handled in exactly one place, nav bar painted a solid legacy color that API 35+ ignores — audit per-screen (see code findings, Medium/architecture).
- **Large screens:** add `sw600dp` resources — multi-column library grid, two-pane details, wider player. Today tablets and foldables get a stretched phone layout.
- **Wear OS:** no surface. Android Auto exists (basic); a Media3 `MediaLibraryService` migration (code findings H1) also improves Auto browsing quality and the media notification (rewind/forward custom actions, chapter title).
- **Dev screens ship in production layouts:** `fragment_ui_test.xml` and `dialog_debug_info.xml` live in the main source set with hardcoded English — gate to debug builds or move to a debug source set.

---

## Priority ranking

1. Bottom-nav selected state (quick win #1) — users literally can't tell which tab is active.
2. 48dp transport targets (quick win #2) — accessibility failure on the core screen.
3. Mini-player progress + rewind, and unhide the now-playing titles (quick wins #3, #4).
4. Home "continue listening" hero + skeletons + actionable empty states.
5. Onboarding: branding, loading state, auto-advance single-option steps.
6. Toast → Snackbar migration (quick win #5).
7. RTL migration + first translation (infrastructure already half-declared).
8. DayNight + dynamic color + M3 widget consistency.
9. Settings grouping/migration; determinate download progress; gesture + haptic polish.
10. Large-screen resources; widget; Wear consideration.
