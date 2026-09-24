# ProjectFuture Browser

An Android web browser built from scratch: no `WebView`, no Chromium/Blink,
no Gecko, no embedded third-party rendering engine of any kind. Every layer
between the TCP socket and the pixels on screen is hand-written for this
project.

## What "from scratch" means here

- **Networking** (`net/Url.kt`) - raw `Socket`/`SSLSocket`, a hand-rolled
  HTTP/1.1 request/response reader (status line, headers, chunked transfer
  encoding, redirects), no `URLConnection`/OkHttp/Cronet.
- **HTML parsing** (`html/`) - a tag-soup tokenizer and tree builder with
  implicit `<html>`/`<head>`/`<body>` insertion, implied end tags for
  unclosed `<p>`/`<li>`/`<tr>`/`<td>` etc., its own DOM (`Node`,
  `ElementNode`, `TextNode`), and its own HTML entity decoding.
- **CSS** (`css/`) - a hand-written parser for tag/class/id/descendant
  selectors and flat declarations, an approximate specificity-based
  cascade, property inheritance, and the box model (margin/border/padding/
  box-sizing) - no platform CSS engine.
- **Layout** (`layout/`) - block, inline (with real line-breaking,
  baseline-aligned text, CJK character-level wrapping, and a basic RTL
  paragraph direction heuristic), flexbox, a bounded CSS Grid, table
  layout, floats/clear, and CSS positioning (relative/absolute/fixed,
  z-index) - all producing an absolute-coordinate `DisplayCommand` list.
- **Rendering** (`view/BrowserView.kt`) - a custom `View` that paints that
  display list directly with `Canvas`/`Paint`, handles `fixed` content
  staying pinned through a second unscrolled paint pass, and turns touch
  input into scrolling and link taps.

This project relies on the platform for two things, the same way any
renderer ultimately hands work to lower-level system facilities: glyph
shaping/rasterization (`android.graphics.Paint`/`Typeface`/`Canvas`) and
image format decoding (`BitmapFactory`, for JPEG/PNG/WebP/GIF - writing
decoders for those from scratch would each be a project of their own).
Networking, parsing, the CSS engine, layout, and painting logic are all
original to this repo.

## Current scope

Roughly following the project's own feature roadmap (Phase 1: engine
correctness) - see commit history for the detailed limitations of each:

- HTTP and HTTPS (TLS via `SSLSocketFactory`), redirects, chunked bodies
- Tag-soup HTML parsing with implied end tags and quote-safe tag scanning
- `<link rel=stylesheet>` and `<style>` CSS, box model, flexbox, grid,
  tables, floats, positioning, z-index
- CJK line-breaking, basic RTL paragraph direction, `text-align`
- `<img>` rendering (via `BitmapFactory`), baseline-aligned inline sizing
- Clickable links, vertical scrolling, back/forward history, reload
- Address bar with a plain-text/search fallback
- JavaScript execution (a from-scratch interpreter), the DOM/window bridge,
  `fetch`/`setTimeout`/`setInterval`
- Web fonts (`@font-face`), SVG, `<canvas>` 2D, CSS `calc()`/custom
  properties/media queries
- Forms: `<input>`/`<textarea>`/`<select>`/`<button>` render as real
  controls (tap to edit/toggle/pick), and submit via GET or POST
- Cookies, gzip response decoding, multiple tabs, bookmarks/history

Not yet implemented: CSS transitions/animations, `transform: scale()`/
`rotate()`/`skew()`, `multipart/form-data` file uploads, and a real text
cursor/in-place typing (form field edits go through a dialog rather than
on-canvas text editing). These are natural next milestones - see the
project roadmap for the fuller list.

<!--
TODO(whoever merges the parallel feature branches): the rest of this
roadmap - JS engine (async/await, generators, Proxy/Reflect/Symbol, typed
arrays, super.method()), networking (HTTP/2, Brotli, possibly HTTP/3),
Service Workers/WebRTC, the encrypted password manager, per-tab process
sandboxing, and GPU compositing - is being worked on and written up
separately by other agents whose work isn't visible from this branch. The
lists above may already be stale relative to that work by the time this
merges. Do one consolidated pass over "Current scope"/"Not yet
implemented" once all branches are in, rather than trusting any single
branch's version of them (including the accessibility section just below,
which only reflects what was actually built and tested here).
-->

## Accessibility

Screen-reader support here is a from-scratch reimplementation, the same as
everything else in this project - there's no `WebView` accessibility bridge
to fall back on.

**Implemented today:**

- Overlaid form-control fields (`<input type=text/password>`, `<textarea>`)
  are real platform `EditText` views, not Canvas-painted pixels, so TalkBack
  already reads/edits them correctly for free - this comes from
  `view/BrowserView.kt`'s design, not any accessibility-specific code.
- A page-load announcement (`View.announceForAccessibility`) fires on every
  navigation, per `MainActivity.onTabStateChanged`.
- A virtual-view `AccessibilityNodeProvider`
  (`view/BrowserAccessibilityNodeProvider.kt`) exposes the rendered page's
  real structure - headings (with level), links, form controls, images with
  non-empty `alt` text, and landmarks (`nav`/`main`/`header`/`footer`/
  `aside`) - as accessibility nodes, built from the DOM plus the exact
  layout bounds the rendering engine already computed
  (`accessibility/AccessibilityTreeBuilder.kt`). This is meant to let
  TalkBack's swipe gesture move focus between these nodes in document order
  and double-tap activate the focused one (`ACTION_CLICK`, wired back to the
  same element-tap handling a real touch uses).
- The tree-building logic (`AccessibilityTreeBuilder.kt`) is pure Kotlin
  with no Android dependency and has JVM unit test coverage
  (`app/src/test/java/com/projectfuture/browser/accessibility/AccessibilityTreeBuilderTest.kt`)
  asserting correct roles, labels, bounds (including bounds unioned across
  descendant elements), and document-order traversal for representative
  sample DOM/layout trees.

**What this explicitly has NOT been verified to do:** work with real
TalkBack on a device or emulator. Neither is available in this development
environment. The provider is written defensively for exactly that reason -
a flat (non-nested) virtual hierarchy, real overlaid `EditText` children
preserved alongside the virtual nodes, and every entry point the
accessibility framework calls into fails closed (logs and returns
null/false) rather than risking a crash - but "implemented and unit-tested"
is the honest claim, not "works". A malformed accessibility tree can hang
or crash TalkBack for the exact users depending on it, which is why this
was not shipped as a verified feature; whoever can test on-device should
treat that as the single highest-priority verification task for this
feature before relying on it. See the doc comments on
`BrowserAccessibilityNodeProvider` and `AccessibilityTreeBuilder` for the
detailed reasoning.

**Known limitations even setting device verification aside:** traversal
order is document order, not true visual-geometry order, so a page that
uses CSS positioning/floats/flex `order` to visually reorder content won't
get a matching reading order; an `<img>` with no non-empty `alt` (missing
or `alt=""`) is treated as decorative and left out entirely rather than
exposed as an unlabeled node.

## Roadmap items this codebase alone can't finish

A few items on this project's roadmap aren't things a code change can
complete, regardless of how much engineering time goes into them, and are
listed here plainly rather than as "not attempted" with no explanation:

- **Sync** needs a backend service to sync to - there is nothing to build
  client-side against until one exists.
- **An extensions marketplace and DevTools** are ongoing tooling
  investments (an extension API/runtime plus a store, and a real
  inspector/debugger protocol and UI), not one-off features - they need
  sustained work, not a single implementation pass.
- **WPT (Web Platform Tests) conformance testing** needs the actual W3C
  test suite wired into this project's CI, not just spec-reading; without
  that wiring, "conformance" is unverified by construction.
- **A security response program** (a disclosure process, a triage/patch
  SLA, a published policy) is an organizational process, not code - it
  can't be "implemented" in a commit.

## Building

Requires JDK 17 and the Android SDK (compileSdk/targetSdk 36, minSdk 24).
Open the project root in Android Studio, or from the command line:

```
./gradlew :app:assembleDebug
```

The debug APK lands in `app/build/outputs/apk/debug/`.
