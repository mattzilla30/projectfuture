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

### WebRTC data channels (`rtc/`)

`RTCPeerConnection`/`RTCDataChannel` are implemented from scratch too -
real protocol stacks, not stubs, for everything except media:

- **SDP** (`Sdp.kt`) - real RFC 4566/8866-shaped offer/answer generation
  and parsing for the one `m=application ... webrtc-datachannel` section
  this engine supports.
- **ICE** (`IceAgent.kt`, `Stun.kt`) - a real RFC 5389 STUN client/message
  codec (binding requests/responses, `XOR-MAPPED-ADDRESS`, HMAC-SHA1
  `MESSAGE-INTEGRITY`, CRC-32 `FINGERPRINT`) doing genuine server-reflexive
  candidate gathering and real RFC 8445 peer-to-peer connectivity checks
  with RFC 8445 priority/pairing math, nominating a working candidate
  pair. Trickle ICE isn't supported (full candidate sets must be known up
  front) and nomination uses the spec-legal "aggressive" algorithm rather
  than "regular". **No TURN/relay candidates**: this environment has no
  TURN server reachable to verify a client against, so none was written -
  see `IceAgent`'s class doc.
- **DTLS** (`DtlsTransport.kt`, `SelfSignedCert.kt`) - a real DTLS 1.2
  handshake via the platform's `SSLContext.getInstance("DTLS")`/
  `SSLEngine`, authenticated the way WebRTC actually does it: each peer's
  self-signed certificate (a real hand-built X.509 DER cert, no external
  crypto library) is hashed and compared against the SDP `a=fingerprint`
  line, not validated against a CA. Data channel bytes flow encrypted
  over this. Android only supports `SSLEngine` DTLS from API 29 (this
  project's `minSdk` is 24); below that (and wherever else the platform
  lacks a `"DTLS"` provider) it falls back to a small plaintext ARQ
  channel instead - real and reliable, but unencrypted. See
  `PeerConnectionCore`'s class doc for exactly when that fallback runs.
- **SCTP** (`SctpChunk.kt`, `SctpAssociation.kt`) - a real (if
  congestion-control-free) RFC 4960 association: INIT/INIT-ACK-with-
  state-cookie/COOKIE-ECHO/COOKIE-ACK setup, CRC-32C-checksummed packets,
  TSN-numbered DATA chunks acknowledged by cumulative-TSN SACKs, and a
  genuine SCTP stream ID per `RTCDataChannel` rather than a hand-rolled
  label-multiplexing hack. Simplified deliberately: one DATA chunk in
  flight at a time (no congestion window/pipelining), no partial
  reliability or unordered delivery modes.
- **Media is not implemented**: no `getUserMedia`, no audio/video `m=`
  sections, no codecs, no RTP/SRTP. A from-scratch codec/RTP stack is its
  own multi-month project and was not attempted.

## Building

Requires JDK 17 and the Android SDK (compileSdk/targetSdk 36, minSdk 24).
Open the project root in Android Studio, or from the command line:

```
./gradlew :app:assembleDebug
```

The debug APK lands in `app/build/outputs/apk/debug/`.
