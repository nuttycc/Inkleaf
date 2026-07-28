# Product

## Register

product

## Platform

android

## Users

Inkleaf is currently built primarily for its developer's personal use. It may also serve other
people who read comics on Android, but it is a personal, non-commercial project rather than a
product aimed at a broad market.

The user typically opens the app intending to choose a comic and read it. That comic may come from a
library directory on the device or from an installed comic source plugin, and the intent is
identical either way. Library organization and source browsing both support that goal but are not
the primary experience.

## Product Purpose

Inkleaf provides a calm, focused environment for opening and reading comics, whether they come from
local files or from an online comic source. Success is measured on two axes.

Reading comfort: page navigation, zooming, chapter or directory navigation, and favorites should
feel natural and unobtrusive, even when reaching the reader takes an extra step.

Consistency: where a comic came from must not change how it is read. The host owns the reading
experience end to end, so the shared reader and the capabilities attached to it — chapter and page
navigation, OCR, page favorites, bookmarks, and reading progress — belong to Inkleaf rather than to
any source. Comic source plugins supply content and nothing else.

## Positioning

A quiet reading space where local files and online comic sources flow into one reader: the source
does not change how you read. Calm at rest and open underneath — nothing sits on screen that the
moment does not need, and nothing is sealed off from a reader who wants to change it.

## Brand Personality

Quiet, focused, and approachable. The interface should recede behind the comic without becoming cold
or impersonal.

Quiet is a resting state, not a limit. Inkleaf is built for someone who enjoys taking a tool apart:
color generation, layout density, reading behavior, sources, and storage are all meant to be opened
and changed, down to details most apps never expose. The two halves fit together because the
defaults carry the calm and the options carry the depth, and depth appears when it is asked for
rather than standing around waiting to be noticed.

Online content introduces failure that local files never had: timeouts, unavailable sources, broken
or disabled plugins, stale caches. Inkleaf stays quiet there too. Prefer self-recovery and silent
degradation, and speak only when a failure genuinely blocks reading.

## Anti-references

Avoid recommendation surfaces that Inkleaf invents for itself: house-curated rankings, editorial
slots, promotional banners, achievement badges, or any entry point that competes with the user's own
comics for attention. Content flows declared by a comic source are content, not merchandising.
Presenting a source's own feeds is legitimate; building an Inkleaf recommendation layer on top of
them is not.

Avoid interfaces that spend attention the user never offered. Depth is not noise: a panel dense with
controls someone deliberately opened is welcome, while anything that occupies a resting screen
unasked is not. Do not let secondary tools distract from choosing a comic and reading it.

## Design Principles

1. Put the comic first: controls and surrounding UI should support the content rather than compete
   with it.
2. The source does not change the reading: local files and comic sources share one reader, one
   navigation model, and one set of user records. Source differences surface only where they must,
   such as content that is unavailable offline or a plugin that has stopped working.
3. Quiet at rest, open on demand: every default is a designed state good enough to live in, and a
   first run should be readable without a detour through settings — yet nothing is closed to a
   reader who goes looking. Adjustment may live on any screen; what stays constant is that a resting
   screen shows only what the moment needs.
4. Optimize for reading comfort, and accept a slightly longer path to get it: navigation, zooming,
   directory access, and favorites should feel predictable and effortless, and an extra step is
   acceptable when it produces a calmer and more reliable reading experience.
5. Stay quiet under failure: recover or degrade silently where possible, and interrupt reading only
   when it is genuinely blocked.
6. Express personality through restraint and convention: use Material 3 Expressive purposefully
   rather than as decoration, follow current Material patterns, and borrow only proven,
   task-relevant behavior from established readers.

## Accessibility & Inclusion

Accessibility is not currently a dedicated project goal. Build for ordinary Android use while
preserving sensible platform baselines such as legible contrast, adequate touch targets, system text
scaling, meaningful semantics for interactive controls, and compatibility with system animation
preferences where practical.
