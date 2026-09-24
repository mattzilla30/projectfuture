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
- **Rendering** (`view/BrowserView.kt`) - the display list is painted across
  two independently GPU-composited hardware layers, one for scrolling
  content and one for `fixed` content that never repaints on scroll, and
  turns touch input into scrolling and link taps.

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
- HTTP/2 (`net/http2/`) over ALPN-negotiated TLS - real binary framing,
  HPACK header compression (static + dynamic table, Huffman coding),
  and genuinely multiplexed request streams on one connection, falling
  back to HTTP/1.1 wherever a server doesn't offer `h2` (including every
  plain `http://` URL, and pre-API-29 devices - see `Alpn.kt`)
- Brotli (`Content-Encoding: br`) response decompression, via a vendored
  copy of Google's reference Java decoder (see `BrotliDecoder.kt` for why
  vendoring rather than a from-scratch decoder was the right call here)
- Tag-soup HTML parsing with implied end tags and quote-safe tag scanning
- `<link rel=stylesheet>` and `<style>` CSS, box model, flexbox, grid,
  tables, floats, positioning, z-index
- CJK line-breaking, basic RTL paragraph direction, `text-align`
- `<img>` rendering (via `BitmapFactory`), baseline-aligned inline sizing
- Clickable links, vertical scrolling, back/forward history, reload
- Address bar with a plain-text/search fallback
- JavaScript execution (a from-scratch interpreter), the DOM/window bridge,
  `fetch`/`setTimeout`/`setInterval`
- Modern JS language features: `async`/`await` and generators
  (`function*`/`yield`/`yield*`, both built on one coroutine primitive in
  `js/Coroutines.kt`), `Symbol` (including well-known `Symbol.iterator`),
  `Proxy`/`Reflect`, typed arrays (`Int8Array` through `Float64Array`) over
  `ArrayBuffer`, and `super.method()` calls in class methods - see each
  file's doc comment in `js/` for the handful of named simplifications
  (e.g. no `Proxy` `apply`/`construct` traps, no async generators)
- Service Workers (`browser/ServiceWorkerRegistry.kt`,
  `browser/ServiceWorkerHost.kt`): `register`/`unregister`, `install`/
  `activate` run-once-ever semantics, `fetch` event interception via
  `respondWith`, and a Cache Storage API - registrations and cached
  responses are persisted and genuinely survive killing and reopening the
  app. Each dispatch re-runs the worker script fresh rather than keeping a
  long-lived worker context alive between calls (see `ServiceWorkerHost`'s
  class doc) - fine for cache-on-install/serve-from-cache, not full parity
  with a persistent worker's own state.
- WebRTC (`rtc/`): real SDP offer/answer generation and parsing, and a
  working `RTCDataChannel` that exchanges ordered, reliably-delivered
  messages between two peers over plain UDP. No ICE/STUN/TURN (only a
  single local candidate - both peers must already be reachable), no
  DTLS/SRTP (data channel traffic is unencrypted on the wire), no real
  SCTP framing (a hand-written reliable-UDP protocol stands in for it),
  and no media (`getUserMedia`, audio/video, codecs) at all - see
  `PeerConnectionCore`'s class doc for exactly what's real versus
  API-surface-only and what a full implementation would still need.
- Web fonts (`@font-face`), SVG, `<canvas>` 2D, CSS `calc()`/custom
  properties/media queries
- Forms: `<input>`/`<textarea>`/`<select>`/`<button>` render as real
  controls (tap to edit/toggle/pick), and submit via GET or POST
- Cookies, gzip response decoding, multiple tabs, bookmarks/history
- An in-browser encrypted password manager (`browser/CredentialStore.kt`,
  `browser/CredentialCipher.kt`, `browser/LoginFormDetector.kt`): detects a
  login form (a `<form>` with a password field and a preceding username-
  like field) on submit, offers to save it, and offers to autofill it the
  next time a matching form appears on the same origin. Credentials are
  encrypted at rest with AES-256/GCM under a key generated inside
  `AndroidKeyStore` and never exported - see `CredentialStore`'s class doc
  for the exact threat model this defends (disk/backup-level, not
  intra-app) and what it doesn't.
- A real, working per-tab process sandbox for one demonstration entry
  point (menu -> "Sandboxed tab (experimental)", `SandboxedTabActivity`):
  a tab's whole engine - fetch/parse/JS/DOM/layout, the actual `Tab` class
  - runs in one of 4 pooled separate OS processes (`ipc/
  TabEngineServiceBase.kt`), reachable only over Messenger/Binder in
  `ipc/TabEngineProtocol.kt`'s vocabulary; the display list crosses back
  as a flat, reference-free wire format (`ipc/WireDisplayCommand.kt`,
  round-trip tested in `DisplayListCodecTest`). This is **not** wired into
  the default multi-tab browsing flow - see `TabEngineClient`'s class doc
  for exactly why (most of `Tab`'s ~40-method surface isn't proxied) and
  what would still be needed to make it the default.
- Two independently GPU-composited hardware layers in `BrowserView`
  (`ScrollingContentLayer`/`FixedContentLayer`) instead of one CPU-painted
  Canvas: scrolling only invalidates the scrolling layer, so `position:
  fixed` content's cached texture is left untouched on every scroll frame
  instead of being re-rasterized every time, and dark mode is a GPU-
  composited `ColorMatrixColorFilter` on each layer (`View.setLayerPaint`)
  rather than a per-frame CPU `Canvas.saveLayer`. See `BrowserView`'s class
  doc for the honest limits (no tiling, so the scrolling layer's visible
  slice still repaints every scroll tick; no on-device timing numbers -
  no emulator/device was available in the environment this was built in).

Not yet implemented: CSS transitions/animations, `transform: scale()`/
`rotate()`/`skew()`, `multipart/form-data` file uploads, and a real text
cursor/in-place typing (form field edits go through a dialog rather than
on-canvas text editing). These are natural next milestones - see the
project roadmap for the fuller list.

**HTTP/3 (QUIC) is deliberately not implemented.** Unlike HTTP/2 (a framing
layer over the TLS/TCP stack this project already has), HTTP/3 replaces the
transport itself: it needs a from-scratch QUIC implementation over raw UDP
`DatagramSocket`s, including its own packet-number-based loss detection and
retransmission, a TLS 1.3 handshake carried inside QUIC frames rather than a
normal `SSLSocket` handshake (the JDK/Android TLS stack has no supported way
to drive a handshake over an arbitrary transport instead of a `Socket`, so
this alone means vendoring or writing a TLS 1.3 state machine), stream
multiplexing with its own flow control independent of TCP's, and a real
congestion controller (at minimum a NewReno/CUBIC-style implementation) to
be a good network citizen. That's multiple independent, substantial
subsystems - realistically a multi-week project on its own even before
QPACK (HTTP/3's HPACK equivalent, itself different from HPACK because it
has to tolerate out-of-order delivery) or connection migration. Rather than
ship a partial QUIC stack that silently falls over on real servers, or a
"HTTP/3 support" that's actually just HTTP/2 or HTTP/1.1 underneath, this
project sticks to HTTP/2 with an HTTP/1.1 fallback for now.

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
