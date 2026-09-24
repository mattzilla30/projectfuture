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

## HTTP/3 (QUIC): attempted further, here's exactly how far it got

All networking in this project is still HTTP/1.1 over `Socket`/`SSLSocket`
(`net/Url.kt`) - there is no HTTP/2 either. HTTP/3 was investigated properly
rather than dismissed outright, and a real chunk of it now lives under
`net/quic/`:

- **QUIC transport (real)**: raw UDP via `DatagramSocket`, QUIC long-header
  packet framing for Initial/Handshake packets (`QuicPacket.kt`), the
  variable-length integer encoding QUIC uses throughout (`QuicVarInt.kt`),
  and the frame types a basic request/response needs - PADDING, PING, ACK,
  CRYPTO, STREAM (`QuicFrame.kt`). Loss detection is a simple fixed-timeout
  retransmit (`Http3Client.RETRANSMIT_TIMEOUT_MS`/`MAX_RETRANSMITS`), not
  RFC 9002's real congestion controller - documented as a deliberate
  simplification in `Http3Client`'s class doc, acceptable because (see
  below) this client never gets far enough into a connection to need real
  congestion control anyway.
- **RFC 9001 Initial-packet crypto (real)**: QUIC's one packet-number space
  whose keys don't depend on a completed TLS handshake - derived via HKDF
  (RFC 5869/RFC 8446 `HKDF-Expand-Label`) from the client's destination
  connection ID and a version-fixed salt. AES-128-GCM packet payload
  protection and AES-128-ECB-based header protection are both implemented
  against `javax.crypto` (`QuicInitialSecrets.kt`) and unit-tested via
  encrypt/protect-then-decrypt/unprotect round trips.
- **QPACK (real, deliberately scoped down)**: the full 99-entry RFC 9204
  Appendix A static table, plus literal field-line encoding with no
  dynamic table at all (`Required Insert Count`/`Delta Base` always zero -
  RFC 9204 explicitly allows an encoder to never use the dynamic table).
  This is spec-legal QPACK; it just forgoes QPACK's main compression win
  for headers that repeat across requests, which this client's one-shot
  use never benefits from anyway. Huffman string coding is optional per
  the spec and isn't implemented (`Qpack.kt`).
- **TLS 1.3 over QUIC: not real, and deliberately not faked.** This is the
  genuinely hard part, and it does not have a working implementation:
  - `javax.net.ssl.SSLEngine`/`SSLSocket` always own the TLS *record*
    layer - `wrap()`/`unwrap()` produce and consume TLS records, not the
    bare handshake-message bytes QUIC's CRYPTO frames need, and there is
    no supported Android/OpenJDK API to extract the derived handshake
    traffic secrets QUIC needs at each encryption level (RFC 9001 section
    5). Real QUIC stacks solve this with a specially-built TLS library
    (BoringSSL's `SSL_set_quic_method` and friends) - there's no
    equivalent hook in `javax.net.ssl`.
  - No pure-Kotlin/Java TLS 1.3 handshake was vendored either. Unlike the
    Brotli decoder (small, self-contained, no security surface), a TLS 1.3
    client handshake is security-critical - transcript hashing, the key
    schedule, and certificate validation all need to be exactly right, and
    getting them subtly wrong produces a connection that *looks*
    encrypted but isn't. That's a level of scrutiny (real test-vector
    coverage, security review) this change did not have, so nothing was
    shipped there rather than shipping something unreviewed and calling it
    secure.
  - See `Http3TlsHandshake`'s class doc for the full reasoning.
  `Http3Client.request()` builds a real, padded, header-protected client
  Initial packet up to the point it would need an actual TLS 1.3
  ClientHello, then throws `UnsupportedOperationException` rather than
  sending meaningless CRYPTO-frame bytes to a real server and calling that
  "HTTP/3".
- **Not wired into `Url.kt`**: since the handshake can never complete,
  adding "try QUIC on port 443, fall back to HTTP/1.1" logic to the real
  request path would just mean every HTTPS request pays for a UDP attempt
  guaranteed to fail before falling back - not real negotiation, just
  overhead. `net/quic/` is therefore a standalone, tested package,
  reachable from `Http3Client` directly, not from `Url.fetch()`. Real
  `Alt-Svc`/HTTPS-record discovery was not attempted for the same reason:
  there is nothing on the other side of a successful discovery yet.

Unit tests in `app/src/test/java/com/projectfuture/browser/net/quic/`
cover QUIC varint/frame/packet encode-decode round trips, the RFC 9001
Initial-secret derivation and AEAD/header-protection round trip, and QPACK
static-table encode/decode - i.e. everything up to the TLS boundary
described above.

## Building

Requires JDK 17 and the Android SDK (compileSdk/targetSdk 36, minSdk 24).
Open the project root in Android Studio, or from the command line:

```
./gradlew :app:assembleDebug
```

The debug APK lands in `app/build/outputs/apk/debug/`.
