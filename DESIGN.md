---
version: alpha
name: Inkleaf
description: A quiet Android reading space where local files and online comic sources share one reader.
colors:
  soft-charcoal-ink: "#2B2B2E"
  rouge-seed: "#9D2933"
  azurite-seed: "#1685A9"
  amber-seed: "#CA6924"
  reader-black: "#000000"
  reader-white: "#FFFFFF"
  ocr-signal-cyan: "#00E5FF"
  ocr-signal-glow: "#00B0FF"
typography:
  body-large:
    fontFamily: "Roboto, Android system sans-serif"
    fontSize: "16px"
    fontWeight: 400
    lineHeight: "24px"
    letterSpacing: "0.5px"
rounded:
  thumbnail: "4px"
  content: "8px"
  overlay: "12px"
  panel: "24px"
  dock: "28px"
  full: "9999px"
spacing:
  micro: "4px"
  compact: "8px"
  content: "12px"
  standard: "16px"
  section: "24px"
  spacious: "32px"
components:
  comic-card:
    rounded: "{rounded.content}"
    padding: "0px"
  reader-overlay:
    backgroundColor: "{colors.reader-black}"
    textColor: "{colors.reader-white}"
    rounded: "{rounded.overlay}"
    padding: "4px 12px"
  reader-dock:
    backgroundColor: "{colors.reader-black}"
    textColor: "{colors.reader-white}"
    rounded: "{rounded.dock}"
    height: "60px"
    padding: "4px 6px"
  reader-panel:
    rounded: "{rounded.panel}"
    padding: "6px 12px"
  bottom-navigation-item:
    height: "48px"
    rounded: "{rounded.full}"
  bottom-navigation-indicator:
    width: "56px"
    height: "32px"
    rounded: "{rounded.full}"
  color-seed-swatch:
    width: "44px"
    height: "44px"
    rounded: "{rounded.full}"
---

# Design System: Inkleaf

## Overview

**Creative North Star: "Ink in Motion"**

Inkleaf should feel like ink moving only when the reader asks it to move. The interface is quiet at
rest, then becomes expressive through page changes, gestures, selection feedback, and transitions
that preserve spatial continuity. Material 3 Expressive supplies the shape and motion vocabulary,
but the comic remains visually dominant.

### The three surfaces

Every screen in Inkleaf belongs to exactly one of three surfaces, and the surface decides the rules.
This is the spine of the system: when a new screen, sheet, or control needs a decision, name its
surface first and most of the answer follows.

- **The Stage** (舞台) — the comic itself and anything drawn directly over it: the reader, the
  favorite-page viewer, the reader dock and its panels, the OCR overlay. Pure black, no app theme,
  no decoration. Content is the only thing that belongs here by default.
- **The Study** (书房) — where the user browses and chooses: shelf, history, favorites, saved
  albums, and source browsing. Fully themed, flat, image-led. Local and online content are neighbors
  here, not strangers.
- **The Workbench** (工作台) — where the user configures: settings, theme editor, source and plugin
  management, OCR model downloads. Denser and more verbose than the Study, still flat, still themed.

**How to use this document.** The direction is normative; the numbers are where the direction
currently lands. When a value and a rule disagree, the rule wins and the value gets corrected. When
something genuinely new appears, derive it from the surface it lives on rather than inventing a
parallel vocabulary for it.

**Key Characteristics:**

- Dynamic Material color derived from a user-selected seed or Android wallpaper.
- Tonal, flat-at-rest surfaces with elevation reserved for things that truly float.
- A compact 4dp-based rhythm with 12–16dp content spacing.
- Familiar Material controls with restrained Expressive shape and motion.
- One reading Stage, black and unthemed, shared by local files and online sources alike.

## Colors

The palette begins with Soft Charcoal Ink and expands through Material 3 tonal roles at runtime;
alternate seeds provide personality without changing the semantic color system. Which colors a
component may use is decided by its surface, not by taste.

### Primary

- **Soft Charcoal Ink:** The default seed. Its low chroma must remain neutral so generated surfaces
  do not drift into purple-gray.

### Secondary

- **Rouge:** An optional traditional red seed for a warmer, more personal theme.
- **Azurite:** An optional cyan-blue seed that stays distinct from conventional indigo app palettes.
- **Amber:** An optional warm orange-brown seed for an earthy theme.

### Neutral

- **Reader Black:** The fixed Stage background. It is not replaced by the app theme, light or dark.
- **Reader White:** The fixed high-contrast foreground for Stage controls and critical messages.
- In the Study and the Workbench, every background, surface, container, outline, and on-color comes
  from `MaterialTheme.colorScheme`. Raw color values are forbidden there.

### Tertiary

- **OCR Signal Cyan** and its glow: the fixed high-chroma pair that marks recognized and selected
  text regions on the Stage. It is a signal, not an accent, and appears only while OCR selection is
  active.

### Named Rules

**The Seed, Not Swatches Rule.** Store and expose color seeds; generate the complete Material role
scheme at runtime through Material Kolor or Android Dynamic Color.

**The Neutral Ink Rule.** Soft Charcoal Ink always uses the Neutral palette style. Never allow a
chromatic generator to turn it into purple-gray.

**The Comic Owns the Stage Rule.** On the Stage, the page dominates. Theme color appears only as a
controlled accent for selection, progress, and actionable state.

**The Chrome and the Mark Rule.** This is why the Stage has two color systems, and it resolves every
future question of the same shape. Stage *chrome* sits on known black, so it may use the theme
accent with a luminance guard: dock progress, thumbnail selection, slider fill. A *mark* drawn on
top of the artwork sits on unknown imagery, where no theme-derived color can be trusted to stay
legible, so it uses a fixed high-contrast signal instead. Signal colors are permitted only for marks
on artwork, only while their tool is active, and never as ambient decoration.

## Typography

**Display Font:** Android system sans-serif through the Material 3 type scale  
**Body Font:** Roboto / Android system sans-serif  
**Label Font:** Android system sans-serif

**Character:** Typography is familiar, quiet, and functional. A single system family keeps controls
predictable and lets comic artwork carry the visual personality.

### Hierarchy

- **Display:** Use Material display roles only for exceptional empty or failure states; never for
  routine screen chrome.
- **Headline:** Use Material headline roles for prominent counts or focused empty-state messages,
  not decorative hero text.
- **Title:** Use Material title roles for top app bars, sheets, dialog headings, and item titles.
- **Body:** `bodyLarge` is explicitly set to regular 16sp with 24sp line height and 0.5sp tracking;
  use body roles for instructions, settings descriptions, and supporting content.
- **Label:** Use Material label roles for actions, metadata, page counts, source names, plugin
  status, and compact status text. Preserve sentence case in Chinese and localized UI strings.

### Named Rules

**The Material Role Rule.** Choose typography by semantic Material role. Never hand-pick an isolated
text size to make one screen feel more dramatic.

**The Comic Is the Display Face Rule.** App typography must not compete with cover art or page
imagery. No decorative display fonts in navigation, buttons, labels, or reader controls.

**The Metadata Stays Small Rule.** Everything a source supplies about a comic — author, update time,
tags, chapter counts, availability — is label-weight supporting text. A title is a title whether it
came from a file name or from a plugin; metadata never grows to compete with it.

## Elevation

Inkleaf is tonal and flat by default. Depth comes from `surface`, `surfaceVariant`, and container
roles, plus spacing and occlusion. The surface a component lives on decides whether it may cast a
shadow at all: the Study and the Workbench are flat planes, and only the Stage's floating chrome,
along with genuine modals anywhere (dialogs, bottom sheets, menus), leaves the plane.

### Shadow Vocabulary

- **Stage float** (tonal 8dp + shadow 12dp): the reader dock, which hovers free of every screen edge
  over arbitrary artwork and needs unambiguous separation from it.
- **Stage panel** (tonal 6dp + shadow 8dp): reader panels that slide over the page.
- **Everything at rest** (no shadow): comic cards, list rows, grouped settings containers.

### Named Rules

**The Tonal First Rule.** Use Material surface roles before adding shadow. If a resting card needs a
wide decorative shadow to read as a card, the hierarchy is wrong.

**The Overlay Earns Elevation Rule.** Only an element that interrupts or overlays the current plane
may use visible elevation.

**The Outline Groups, The Shadow Floats Rule.** In the Workbench, related settings are grouped with
an outline, a tonal container, or a divider — never by lifting a resting card off the page. Shadow
means "this is above the plane you were on", and a settings group is not.

## Components

Components are quietly expressive: recognizably Material, responsive to touch and state, but never
louder than the comic content.

### Shape

Corner radius is derived, not chosen: it grows with the component's distance from the artwork.
Something clipped tight to an image takes the smallest radius (thumbnails, 4dp); a container that
holds content in the Study takes the content radius (comic cards, 8dp); a small utility surface
floating over the page takes the overlay radius (12dp); a panel sliding over content takes the panel
radius (24dp); the dock, which floats free of every edge, takes the largest (28dp). Radii that are
geometric consequences rather than choices — a 7dp corner on a 14dp-tall progress bar, which is
simply half its height — are not exceptions to the scale. Anything else is drift and should converge
to the nearest step.

### Comic entries

- **One vocabulary, two densities.** A comic entry looks the same whether it came from a library
  directory or from a comic source: flat image-led container, content radius (8dp), 4dp gap before
  metadata, no border-and-shadow pairing. What legitimately differs is *density*, because the tasks
  differ — the shelf is for picking from what you already have, and source browsing is for scanning
  hundreds of results. Density is therefore a user setting per surface (grid columns, cover aspect,
  crop, grid-versus-list), and the two surfaces share the same setting vocabulary while storing
  their own values.
- **Availability, not origin.** Online entries may show that content is unavailable, cached, or
  stale. They must not advertise which plugin produced them as a visual style; source identity is
  text, never a different card shape, border, or accent.
- **Thumbnails:** Reader thumbnails use 4dp corners, an exact 1dp accent outline when selected, and
  restrained scale feedback contained by the list spacing.

### Buttons

- **Shape:** Use the active Material 3 Expressive theme shapes; keep custom compact controls on the
  established 8–12dp content radius scale.
- **Primary:** Filled buttons are reserved for the single clearest next action, such as granting
  directory access, installing a source plugin, or recovering from a reader failure.
- **Focus / Pressed / Disabled:** Use Material-provided state layers and semantics. Do not invent
  decorative hover behavior for a touch-first Android interface.
- **Secondary:** Use outlined or text buttons for reversible, secondary, and dialog actions.

### Chips and segmented controls

- **Filter chips** carry source-declared filter values and content-flow selection. A chip shows what
  the source offers; it is not an editorial recommendation and must not be styled as one.
- **Segmented buttons** carry mutually exclusive layout, aspect-ratio, theme-mode, and palette-style
  choices.
- **State:** Selection must be visible through the Material container, content color, and selected
  icon treatment; never rely on color alone when the component already supports an icon.

### Cards / Containers

- **Study containers:** flat, themed, image-led. Radius by the shape rule.
- **Workbench containers:** outlined or tonal groups, flat at rest, standard 16dp internal content
  padding.
- **Internal padding:** Use the established scale — 4dp for micro-labels, 8dp for compact controls,
  12dp for grids and compact groups, 16dp for standard screen and sheet content, 24–32dp only for
  spacious states.

### Inputs / Fields

- **Style:** Filled fields for human-readable names and labels; outlined fields for exact values
  such as hexadecimal colors and numeric limits. Keep labels, helper text, error state, keyboard
  action, and enabled state in the component API.
- **Focus / Error / Disabled:** Use Material semantic colors and indicators. Never replace validation
  with a raw red border or a color-only message.

### Navigation

- **Primary navigation:** The compact layout uses a 48dp-high bottom navigation surface with 56×32dp
  pill selection indicators and 48dp minimum touch height.
- **Screen context:** Use transparent Material top app bars so theme switches remain visually
  synchronized with the underlying background.
- **Back behavior:** Every nested screen and immersive reader must honor Android system Back and
  predictive Back behavior.
- **Expanded width:** When tablet layouts are introduced, replace the phone bottom navigation with
  an appropriate rail or drawer rather than stretching it unchanged.

### Reader Stage

- **Surface:** Pure black behind comic pages, independent of light or dark app theme, and identical
  for local files and online chapters.
- **Dock:** Reading controls collect in one floating dock (28dp radius, near-opaque black, 60dp
  tall) that hovers over the page rather than docking to an edge. Its destinations are reading
  tools — pages, chapters, bookmarks, tools — and the chapter destination appears only when the
  comic actually has chapters to navigate.
- **Controls:** Transient chrome with high-contrast white content and a theme-derived accent whose
  luminance remains visible on black.
- **Failure:** Online reading fails in ways local files never do. Recover or degrade on the Stage
  without leaving it: keep the page and controls in place, show the failure where the missing
  content would be, and reserve dialogs for failures that genuinely end the reading session.
- **Motion:** Page, control, and thumbnail motion must preserve cause and spatial direction. Honor
  the system animation preference with reduced or immediate alternatives.

### App Icon

- **Mark:** A white leaf drawn with a dark ink-like vein and a short Rouge stem. The leaf expresses
  both halves of the Inkleaf name without adding text or a generic reader badge.
- **Background:** Soft Charcoal Ink fills the adaptive icon background layer.
- **Adaptive bounds:** The complete mark stays inside Android's 66dp guaranteed safe zone on the
  108dp adaptive icon canvas.
- **Themed icon:** The monochrome layer simplifies the mark to the leaf silhouette, central ink cut,
  and stem so it remains legible when tinted by the launcher.
- **Splash:** The foreground mark and Soft Charcoal Ink icon background are supplied separately to
  the splash theme, preventing adaptive-icon recropping on Android 10 and 11.

### Known deviations

Current implementation points where the code and the direction disagree. They are recorded, not
endorsed; converge them when the surrounding code is next touched.

- **Source browsing cards** use a 12dp Material `Card` while shelf cards are flat 8dp containers.
  The density difference between the two surfaces is intentional; this shape and container
  difference is not.
- **Source detail** groups settings with `ElevatedCard` alongside `OutlinedCard`, against the
  Outline Groups rule. `OutlinedCard` is the direction.
- **Stray radii** (2.5dp, 6dp, 16dp, 20dp) sit off the scale without a geometric reason.

## Do's and Don'ts

### Do:

- **Do** name a screen's surface — Stage, Study, or Workbench — before deciding how it should look.
- **Do** keep the comic visually dominant and reveal controls in response to reader intent.
- **Do** use `MaterialTheme.colorScheme` roles for Study and Workbench surfaces, generated from the
  selected seed or Android wallpaper.
- **Do** give local and online comics the same entry vocabulary, and let density differ per surface
  because the task differs.
- **Do** build spacing from 4, 8, 12, 16, 24, and 32dp, using 12–16dp for most content layout.
- **Do** use standard Material 3 Expressive components and state behavior before creating custom
  controls.
- **Do** preserve at least 48×48dp touch targets even when the visible icon or swatch is smaller.
- **Do** use motion to explain page direction, selection, entry, exit, and transient control state.
- **Do** show source, plugin, and cache state as plain text where the user is already looking.

### Don't:

- **Don't** build recommendation surfaces Inkleaf invents for itself: house-curated rankings,
  editorial slots, promotional banners, achievement badges, or any entry point that competes with
  the user's own comics for attention. Presenting a source's declared content flows is legitimate;
  dressing them up as Inkleaf's own merchandising is not.
- **Don't** create feature-heavy, visually noisy interfaces, and don't let secondary tools distract
  from choosing a comic and reading it.
- **Don't** style an online comic differently from a local one. Availability may show; origin may
  not.
- **Don't** interrupt reading for a failure that can be recovered or degraded silently.
- **Don't** use Expressive motion, shape, or color as ambient decoration; every expressive choice
  must communicate state or movement.
- **Don't** hard-code app surface colors when a Material color role exists, and don't use a signal
  color anywhere except as a mark on artwork.
- **Don't** add decorative shadows to resting cards or pair a thin border with a wide soft shadow.
- **Don't** add nested cards, oversized corner radii, gradient text, glassmorphism, decorative
  grids, or striped backgrounds.
- **Don't** port iOS navigation, dialogs, switches, or back behavior into the Android interface.
