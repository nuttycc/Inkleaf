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
- **The Workbench** (工作台) — where the user configures things that have no single place to be
  seen: the app theme, sources and plugins, storage and caching. Denser and more verbose than the
  Study, still flat, still themed.

The surfaces describe what the user is doing, not where controls are allowed to live. Adjustment is
not confined to the Workbench; see the Adjustment rules under Components for where a control belongs
and how it is expected to rest.

### How to use this document

**The prose is direction; it deliberately carries no measurements.** Every rule below is written so
it can be applied without looking a number up, because sizes, radii, and spacing will keep changing
while the direction should not. Exact values live in two places instead: the token block at the top
of this file, and the code itself. Colors are the exception, because the seeds are identity rather
than implementation.

When a rule and an implementation disagree, the rule wins and the implementation gets corrected.
When something genuinely new appears, derive it from the surface it lives on rather than inventing a
parallel vocabulary for it. When editing this document, do not reintroduce measurements into the
prose; if a number feels necessary to make a rule decidable, the rule is not yet written well
enough.

**Key Characteristics:**

- Dynamic Material color derived from a user-selected seed or Android wallpaper.
- Tonal, flat-at-rest surfaces with elevation reserved for things that truly float.
- A compact, consistent spacing rhythm that stays tight through content areas.
- Familiar Material controls with restrained Expressive shape and motion.
- One reading Stage, black and unthemed, shared by local files and online sources alike.
- Deep configurability that is reachable from anywhere and resident nowhere.

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
- **Body:** Use body roles for instructions, settings descriptions, and supporting content.
  `bodyLarge` is the one role Inkleaf sets explicitly instead of inheriting.
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

Inkleaf is tonal and flat by default. Depth comes from Material surface and container roles, plus
spacing and occlusion. The surface a component lives on decides whether it may cast a shadow at all:
the Study and the Workbench are flat planes, and only the Stage's floating chrome, along with
genuine modals anywhere (dialogs, bottom sheets, menus), leaves the plane.

### Shadow Vocabulary

- **Stage float:** the strongest separation in the system, reserved for the reader dock, which
  hovers free of every screen edge over arbitrary artwork and must never be mistaken for part of the
  page.
- **Stage panel:** a lighter lift for panels that slide over the page — clearly above the content,
  clearly below the dock.
- **Everything at rest:** no shadow at all. Comic cards, list rows, and grouped settings containers
  stay on the plane.

### Named Rules

**The Tonal First Rule.** Use Material surface roles before adding shadow. If a resting card needs a
wide decorative shadow to read as a card, the hierarchy is wrong.

**The Overlay Earns Elevation Rule.** Only an element that interrupts or overlays the current plane
may use visible elevation.

**The Outline Groups, The Shadow Floats Rule.** Wherever settings are grouped, they are grouped with
an outline, a tonal container, or a divider — never by lifting a resting card off the page. Shadow
means "this is above the plane you were on", and a settings group is not.

## Components

Components are quietly expressive: recognizably Material, responsive to touch and state, but never
louder than the comic content.

### Adjustment

Inkleaf is meant to be taken apart, and no surface is off-limits to a control. What keeps that from
becoming noise is not where a control lives but how it rests.

**The Adjust Where You See It Rule.** A control belongs where its effect is visible. Something that
changes the whole app belongs where the whole app is the subject; something that changes how a page
is read belongs in the reader; something that changes how a grid reflows belongs on that grid. Do
not exile a control to a settings screen only because it is a setting, and do not scatter one
concern across several screens because each felt convenient at the time.

**The Rest State Rule.** Depth is reached, not displayed. A resting screen shows what the moment
needs; controls arrive when summoned and leave when finished. A control that must stay visible in
order to be usable has been put in the wrong place, not given the wrong size.

**The Default Is a Design Rule.** Every default is a designed state that someone should be able to
live in without opening anything, and a first run must be usable without a detour through
configuration. Defaults are never placeholders waiting for the user to make the app work. An option
exists to let a reader disagree with a good decision, not to avoid making one.

### Shape

Corner radius is derived, not chosen: it grows with the component's distance from the artwork.
Something clipped tight to an image takes the smallest radius; a container that holds content in the
Study takes the next step up; a small utility surface floating over the page takes more; a panel
sliding over content more still; and the dock, which floats free of every edge, takes the largest.
Radii that are geometric consequences rather than choices — the corner of a pill-shaped progress bar
is simply half its height — are not exceptions to the scale. A radius that sits between steps for no
reason is drift, and should move to the nearest step.

### Comic entries

- **One vocabulary, two densities.** A comic entry looks the same whether it came from a library
  directory or from a comic source: a flat, image-led container on the content radius, a small gap
  before its metadata, and no border-and-shadow pairing. What legitimately differs is *density*,
  because the tasks differ — the shelf is for picking from what you already have, and source
  browsing is for scanning many results. Density is therefore a user setting per surface (grid
  columns, cover aspect, crop, grid-versus-list), and the two surfaces share the same setting
  vocabulary while storing their own values.
- **Availability, not origin.** Online entries may show that content is unavailable, cached, or
  stale. They must not advertise which plugin produced them as a visual style; source identity is
  text, never a different card shape, border, or accent.
- **Thumbnails:** Reader thumbnails take the smallest radius, a hairline accent outline when
  selected, and restrained scale feedback contained by the list spacing.

### Buttons

- **Shape:** Use the active Material 3 Expressive theme shapes; keep custom compact controls on the
  established content radius steps rather than inventing their own.
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
- **Workbench containers:** outlined or tonal groups, flat at rest, on the standard content padding
  step.
- **Internal padding** steps with the density of what it holds: tightest around micro-labels, tight
  around compact controls, moderate in grids and compact groups, standard for ordinary screen and
  sheet content, and generous only in deliberately spacious states such as empty screens.

### Inputs / Fields

- **Style:** Filled fields for human-readable names and labels; outlined fields for exact values
  such as hexadecimal colors and numeric limits. Keep labels, helper text, error state, keyboard
  action, and enabled state in the component API.
- **Focus / Error / Disabled:** Use Material semantic colors and indicators. Never replace validation
  with a raw red border or a color-only message.

### Navigation

- **Primary navigation:** The compact layout uses a low bottom navigation surface with a pill
  selection indicator that is smaller than the row it sits in, while the row itself stays at or
  above the platform's minimum touch target.
- **Screen context:** Use transparent Material top app bars so theme switches remain visually
  synchronized with the underlying background.
- **Back behavior:** Every nested screen and immersive reader must honor Android system Back and
  predictive Back behavior.
- **Expanded width:** When tablet layouts are introduced, replace the phone bottom navigation with
  an appropriate rail or drawer rather than stretching it unchanged.

### Reader Stage

- **Surface:** Pure black behind comic pages, independent of light or dark app theme, and identical
  for local files and online chapters.
- **Dock:** Reading controls collect in one floating dock — near-opaque black, on the system's
  largest radius — that hovers over the page rather than docking to an edge. Its destinations are
  reading tools, and a destination appears only when the comic actually needs it; a single-chapter
  comic has no chapter navigation to offer.
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
- **Adaptive bounds:** The complete mark stays inside Android's guaranteed safe zone on the adaptive
  icon canvas.
- **Themed icon:** The monochrome layer simplifies the mark to the leaf silhouette, central ink cut,
  and stem so it remains legible when tinted by the launcher.
- **Splash:** The foreground mark and Soft Charcoal Ink icon background are supplied separately to
  the splash theme, preventing adaptive-icon recropping on the Android versions that would otherwise
  recrop it.

## Do's and Don'ts

### Do:

- **Do** name a screen's surface — Stage, Study, or Workbench — before deciding how it should look.
- **Do** keep the comic visually dominant and reveal controls in response to reader intent.
- **Do** put a control where its effect is visible, and let it leave when it is not in use.
- **Do** treat every default as a state someone will live in without ever changing it.
- **Do** use `MaterialTheme.colorScheme` roles for Study and Workbench surfaces, generated from the
  selected seed or Android wallpaper.
- **Do** give local and online comics the same entry vocabulary, and let density differ per surface
  because the task differs.
- **Do** build spacing from the one shared scale, staying in its tighter steps for most content.
- **Do** use standard Material 3 Expressive components and state behavior before creating custom
  controls.
- **Do** preserve the platform's minimum touch target even when the visible icon or swatch is
  smaller.
- **Do** use motion to explain page direction, selection, entry, exit, and transient control state.
- **Do** show source, plugin, and cache state as plain text where the user is already looking.

### Don't:

- **Don't** build recommendation surfaces Inkleaf invents for itself: house-curated rankings,
  editorial slots, promotional banners, achievement badges, or any entry point that competes with
  the user's own comics for attention. Presenting a source's declared content flows is legitimate;
  dressing them up as Inkleaf's own merchandising is not.
- **Don't** spend attention the user never offered. A panel dense with controls someone deliberately
  opened is welcome; anything that occupies a resting screen unasked is not.
- **Don't** leave a control resident on a resting screen just because it is useful, and don't let
  secondary tools distract from choosing a comic and reading it.
- **Don't** ship a default that only makes sense after the user has configured something.
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
