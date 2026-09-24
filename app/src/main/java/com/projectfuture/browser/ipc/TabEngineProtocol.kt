package com.projectfuture.browser.ipc

/**
 * The Messenger/Binder message vocabulary between the UI process and a
 * sandboxed tab-engine process (see [TabEngineServiceBase]). Two-way,
 * asynchronous: the UI process binds to a `TabEngineServiceN` and sends it
 * a `MSG_REGISTER_CLIENT` carrying its own reply `Messenger`, then every
 * later message in either direction is one `what` code plus a `Bundle` of
 * primitives/byte arrays (never a live object reference) - the same
 * boundary [WireDisplayCommand] exists for.
 *
 * This is the full vocabulary needed to make [TabEngineClient] a real
 * drop-in for every method [com.projectfuture.browser.browser.Tab] method
 * MainActivity actually calls (see [com.projectfuture.browser.browser.TabHandle]),
 * not just load/tap/input/viewport-size. A handful of things that don't
 * fit a fire-and-forget/one-reply message cleanly are called out on the
 * individual message constants below and in [TabEngineClient]'s class doc.
 */
object TabEngineProtocol {
    // UI process -> engine process
    const val MSG_REGISTER_CLIENT = 1
    const val MSG_LOAD_URL = 2
    const val MSG_DISPATCH_TAP = 3
    const val MSG_DISPATCH_INPUT = 4
    const val MSG_SET_VIEWPORT_SIZE = 5
    /** Sent once, right after MSG_REGISTER_CLIENT and before any load - tells the engine process whether this tab is private (see KEY_IS_PRIVATE) so it constructs its `Tab` with the right flag from the start. */
    const val MSG_INIT = 6
    const val MSG_GO_BACK = 7
    const val MSG_GO_FORWARD = 8
    const val MSG_RELOAD = 9
    const val MSG_FOLLOW_LINK = 10
    const val MSG_TOGGLE_DESKTOP_MODE = 11
    const val MSG_TOGGLE_READER_MODE = 12
    const val MSG_SET_TEXT_SCALE = 13
    const val MSG_DISCARD = 14
    const val MSG_SET_FIELD_VALUE = 15
    const val MSG_SET_SELECT_VALUE = 16
    /** Request/reply pair with MSG_FIELD_VALUE - see that constant's doc for why this can't be synchronous like the in-process `Tab.currentFieldValue`. */
    const val MSG_REQUEST_FIELD_VALUE = 17
    /** Request/reply pair with MSG_SELECT_OPTIONS. */
    const val MSG_REQUEST_SELECT_OPTIONS = 18
    /** Request/reply pair with MSG_LOGIN_FORM_RESULT. */
    const val MSG_REQUEST_LOGIN_FORM = 19
    const val MSG_AUTOFILL_LOGIN_FORM = 20
    /** Request/reply pair with MSG_MANIFEST_INFO. */
    const val MSG_REQUEST_MANIFEST_INFO = 21
    const val MSG_TRUST_CERTIFICATE = 22

    // engine process -> UI process
    const val MSG_DISPLAY_LIST = 100
    const val MSG_IMAGE_DATA = 101
    const val MSG_STATE_LOADING = 102
    const val MSG_STATE_LOADED = 103
    const val MSG_STATE_ERROR = 104
    /** Mirrors `TabState.Updated` - a DOM mutation repainted the page without a real navigation (see Tab.kt's doc on TabState.Updated). */
    const val MSG_STATE_UPDATED = 105
    /** Mirrors `TabState.CertificateError`, kept distinct from MSG_STATE_ERROR so the UI process can show the "proceed anyway?" interstitial rather than a plain error toast. */
    const val MSG_STATE_CERTIFICATE_ERROR = 106
    /**
     * Pushed by the engine alongside (immediately after) every state
     * message plus every toggle/discard/text-scale change - the UI process
     * has no other way to learn `canGoBack`/`canGoForward`/`desktopMode`/
     * `readerModeActive`/`textScale`/`isDiscarded` without polling, since a
     * Messenger call can't return a value synchronously. See
     * [TabEngineClient]'s cached-fields doc for what this buys and what it
     * costs (a short-lived staleness window right after bind(), before the
     * first message arrives).
     */
    const val MSG_TAB_INFO = 107
    /** Pushed once per elementId the first time it's referenced by a display list command - see [com.projectfuture.browser.ipc.ElementMetaCodec]'s doc for why this exists (MainActivity's tap routing switches on `element.tag`/`attr("type")`, which a bare "shadow" ElementNode wouldn't otherwise carry). */
    const val MSG_ELEMENT_META = 108
    const val MSG_FIELD_VALUE = 109
    const val MSG_SELECT_OPTIONS = 110
    const val MSG_LOGIN_FORM_RESULT = 111
    const val MSG_MANIFEST_INFO = 112
    const val MSG_DOWNLOAD_REQUESTED = 113
    const val MSG_LOGIN_FORM_SUBMITTED = 114
    /** One web font's SFNT bytes ([KEY_FONT_FAMILY], [KEY_FONT_BYTES]), sent once per family per document before the display list that uses it. */
    const val MSG_FONT_DATA = 115

    const val KEY_URL = "url"
    const val KEY_ELEMENT_ID = "element_id"
    const val KEY_VALUE = "value"
    const val KEY_WIDTH = "width"
    const val KEY_HEIGHT = "height"
    const val KEY_PAYLOAD = "payload"
    const val KEY_CONTENT_HEIGHT = "content_height"
    const val KEY_IMAGE_ID = "image_id"
    const val KEY_IMAGE_BYTES = "image_bytes"
    const val KEY_FONT_FAMILY = "font_family"
    const val KEY_FONT_BYTES = "font_bytes"
    const val KEY_TITLE = "title"
    const val KEY_MESSAGE = "message"
    const val KEY_IS_PRIVATE = "is_private"
    const val KEY_SCALE = "scale"
    const val KEY_OPTION_ELEMENT_ID = "option_element_id"
    const val KEY_USERNAME_FIELD_ID = "username_field_id"
    const val KEY_PASSWORD_FIELD_ID = "password_field_id"
    const val KEY_USERNAME = "username"
    const val KEY_PASSWORD = "password"
    const val KEY_USERNAME_PREFILLED = "username_prefilled"
    const val KEY_SEARCH_TEMPLATE = "search_template"
}
