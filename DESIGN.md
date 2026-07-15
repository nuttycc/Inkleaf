---
version: alpha
name: Inkleaf
description: A quiet, expressive Android reading space for locally stored comics.
colors:
  soft-charcoal-ink: "#2B2B2E"
  rouge-seed: "#9D2933"
  azurite-seed: "#1685A9"
  amber-seed: "#CA6924"
  reader-black: "#000000"
  reader-white: "#FFFFFF"
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
  bottom-navigation-item:
    height: "48px"
    rounded: "{rounded.full}"
    padding: "8px 16px"
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

The system is content-first, moderately spacious, and familiar to an Android user. It rejects
feature-heavy, visually noisy interfaces that fill the library with competing entry points, labels,
recommendations, or decoration. Expressiveness must clarify state or movement; it must never become
ambient spectacle.

**Key Characteristics:**

- Dynamic Material color derived from a user-selected seed or Android wallpaper.
- Tonal, flat-at-rest surfaces with elevation reserved for true overlays.
- A compact 4dp-based rhythm with 12–16dp content spacing.
- Familiar Material controls with restrained Expressive shape and motion.
- A dedicated black reading stage that isolates comic pages from app chrome.

## Colors

The palette begins with Soft Charcoal Ink and expands through Material 3 tonal roles at runtime;
alternate seeds provide personality without changing the semantic color system.

### Primary

- **Soft Charcoal Ink:** The default seed. Its low chroma must remain neutral so generated surfaces
  do not drift into purple-gray.

### Secondary

- **Rouge:** An optional traditional red seed for a warmer, more personal theme.
- **Azurite:** An optional cyan-blue seed that stays distinct from conventional indigo app palettes.
- **Amber:** An optional warm orange-brown seed for an earthy theme.

### Neutral

- **Reader Black:** The fixed immersive reading background. It is not replaced by the app theme.
- **Reader White:** The fixed high-contrast foreground for reader controls and critical messages.
- Runtime backgrounds, surfaces, containers, outlines, and on-colors must come from
  `MaterialTheme.colorScheme`; raw colors are forbidden outside deliberately fixed reader media
  surfaces and seed previews.

### Named Rules

**The Seed, Not Swatches Rule.** Store and expose color seeds; generate the complete Material role
scheme at runtime through Material Kolor or Android Dynamic Color.

**The Neutral Ink Rule.** Soft Charcoal Ink always uses the Neutral palette style. Never allow a
chromatic generator to turn it into purple-gray.

**The Comic Owns the Stage Rule.** In the reader, the page and black stage dominate. Theme color
appears only as a controlled accent for selection, progress, and actionable state.

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
- **Label:** Use Material label roles for actions, metadata, page counts, and compact status text.
  Preserve sentence case in Chinese and localized UI strings.

### Named Rules

**The Material Role Rule.** Choose typography by semantic Material role. Never hand-pick an isolated
text size to make one screen feel more dramatic.

**The Comic Is the Display Face Rule.** App typography must not compete with cover art or page
imagery. No decorative display fonts in navigation, buttons, labels, or reader controls.

## Elevation

Inkleaf is tonal and flat by default. Depth comes from `surface`, `surfaceVariant`, and container
roles, plus spacing and occlusion. Shadows belong only to components that genuinely float above
content, including modal sheets, dialogs, menus, and transient system surfaces; ordinary comic cards
remain flat.

### Named Rules

**The Tonal First Rule.** Use Material surface roles before adding shadow. If a resting card needs a
wide decorative shadow to read as a card, the hierarchy is wrong.

**The Overlay Earns Elevation Rule.** Only an element that interrupts or overlays the current plane
may use visible elevation.

## Components

Components are quietly expressive: recognizably Material, responsive to touch and state, but never
louder than the comic content.

### Buttons

- **Shape:** Use the active Material 3 Expressive theme shapes; keep custom compact controls on the
  established 8–12dp content radius scale.
- **Primary:** Filled buttons are reserved for the single clearest next action, such as granting
  access or recovering from a reader failure.
- **Focus / Pressed / Disabled:** Use Material-provided state layers and semantics. Do not invent
  decorative hover behavior for a touch-first Android interface.
- **Secondary:** Use outlined or text buttons for reversible, secondary, and dialog actions.

### Segmented Controls

- **Style:** Use Material segmented buttons for mutually exclusive layout, aspect-ratio, theme-mode,
  and palette-style choices.
- **State:** Selection must be visible through the Material container, content color, and selected
  icon treatment; never rely on color alone when the component already supports an icon.

### Cards / Containers

- **Comic cards:** Flat, image-led containers with 8dp corners, a 4dp gap before metadata, and no
  decorative border-shadow pairing.
- **Thumbnails:** Reader thumbnails use 4dp corners, an exact 1dp accent outline when selected, and
  restrained scale feedback contained by the list spacing.
- **Reader overlays:** Black translucent utility surfaces use 12dp corners and compact 4dp
  vertical / 12dp horizontal padding.
- **Internal padding:** Use the established scale: 4dp for micro-labels, 8dp for compact controls,
  12dp for grids and compact groups, 16dp for standard screen/sheet content, and 24–32dp only for
  spacious states.

### Inputs / Fields

- **Style:** Use Material outlined text fields for names and hexadecimal color input. Keep labels,
  helper text, error state, keyboard action, and enabled state in the component API.
- **Focus / Error / Disabled:** Use Material semantic colors and indicators. Never replace
  validation with a raw red border or color-only message.

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

- **Surface:** Pure black behind comic pages, independent of light or dark app theme.
- **Controls:** Controls appear as transient chrome with high-contrast white content and a
  theme-derived accent whose luminance remains visible on black.
- **Motion:** Page, control, and thumbnail motion must preserve cause and spatial direction. Honor
  the system animation preference with reduced or immediate alternatives.

## Do's and Don'ts

### Do:

- **Do** keep the comic visually dominant and reveal controls in response to reader intent.
- **Do** use `MaterialTheme.colorScheme` roles for app surfaces and generate them from the selected
  seed or Android wallpaper.
- **Do** build spacing from 4, 8, 12, 16, 24, and 32dp, using 12–16dp for most content layout.
- **Do** use standard Material 3 Expressive components and state behavior before creating custom
  controls.
- **Do** preserve at least 48×48dp touch targets even when the visible icon or swatch is smaller.
- **Do** use motion to explain page direction, selection, entry, exit, and transient control state.

### Don't:

- **Don't** create feature-heavy, visually noisy interfaces that fill the library with competing
  entry points, labels, recommendations, or decoration.
- **Don't** let secondary tools distract from choosing a comic and reading it.
- **Don't** use Expressive motion, shape, or color as ambient decoration; every expressive choice
  must communicate state or movement.
- **Don't** hard-code app surface colors when a Material color role exists.
- **Don't** add decorative shadows to resting comic cards or pair a thin border with a wide soft
  shadow.
- **Don't** add nested cards, oversized corner radii, gradient text, glassmorphism, decorative
  grids, or striped backgrounds.
- **Don't** port iOS navigation, dialogs, switches, or back behavior into the Android interface.
