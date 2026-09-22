# ProjectFuture Browser

An Android web browser built from scratch: no `WebView`, no Chromium/Blink,
no Gecko, no embedded third-party rendering engine of any kind. Every layer
between the TCP socket and the pixels on screen is hand-written for this
project.

## What "from scratch" means here

- **Networking** (`net/Url.kt`) - raw `Socket`/`SSLSocket`, a hand-rolled
  HTTP/1.1 request/response reader (status line, headers, chunked transfer
  encoding, redirects), no `URLConnection`/OkHttp/Cronet.
- **HTML parsing** (`html/`) - a small tag-soup tokenizer and tree builder
  with implicit `<html>`/`<head>`/`<body>` insertion, its own DOM (`Node`,
  `ElementNode`, `TextNode`), and its own HTML entity decoding.
- **CSS** (`css/`) - a hand-written parser for tag/class/id/descendant
  selectors and flat declarations, an approximate specificity-based
  cascade, and property inheritance - no platform CSS engine.
- **Layout** (`layout/`) - a block/inline box tree with real line-breaking
  and baseline-aligned text flow, producing an absolute-coordinate
  `DisplayCommand` list.
- **Rendering** (`view/BrowserView.kt`) - a custom `View` that paints that
  display list directly with `Canvas`/`Paint` and turns touch input into
  scrolling and link taps.

The one place this project relies on the platform is glyph shaping and
rasterization (`android.graphics.Paint`/`Typeface`/`Canvas`) - the same way
any renderer ultimately hands pixels to a display driver. Parsing, the box
model, layout, and painting logic are all original to this repo.

## Current scope (v0.1 - "core engine" MVP)

- HTTP and HTTPS (TLS via `SSLSocketFactory`), redirects, chunked bodies
- Tag-soup HTML parsing, `<link rel=stylesheet>` and `<style>` CSS
- Minimal CSS: colors, `font-size`/`font-weight`/`font-style`, basic
  selectors, inline `style="..."`
- Block + inline layout with word-wrapped, baseline-aligned text
- Clickable links, vertical scrolling, back/forward history, reload
- Address bar with a plain-text/search fallback

Not yet implemented (deliberately out of scope for this first pass):
JavaScript execution, the CSS box model (margin/padding/border, flexbox/
grid), images, forms/POST, cookies, tabs, and full HTML5-spec parsing edge
cases. These are natural next milestones.

## Building

Requires JDK 17 and the Android SDK (compileSdk/targetSdk 36, minSdk 24).
Open the project root in Android Studio, or from the command line:

```
./gradlew :app:assembleDebug
```

The debug APK lands in `app/build/outputs/apk/debug/`.
