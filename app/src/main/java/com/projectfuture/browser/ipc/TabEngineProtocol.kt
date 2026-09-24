package com.projectfuture.browser.ipc

/**
 * The Messenger/Binder message vocabulary between the UI process and a
 * sandboxed tab-engine process (see [TabEngineServiceBase]). Two-way,
 * asynchronous: the UI process binds to a `TabEngineServiceN` and sends it
 * a `MSG_REGISTER_CLIENT` carrying its own reply `Messenger`, then every
 * later message in either direction is one `what` code plus a `Bundle` of
 * primitives/byte arrays (never a live object reference) - the same
 * boundary [WireDisplayCommand] exists for.
 */
object TabEngineProtocol {
    // UI process -> engine process
    const val MSG_REGISTER_CLIENT = 1
    const val MSG_LOAD_URL = 2
    const val MSG_DISPATCH_TAP = 3
    const val MSG_DISPATCH_INPUT = 4
    const val MSG_SET_VIEWPORT_SIZE = 5

    // engine process -> UI process
    const val MSG_DISPLAY_LIST = 100
    const val MSG_IMAGE_DATA = 101
    const val MSG_STATE_LOADING = 102
    const val MSG_STATE_LOADED = 103
    const val MSG_STATE_ERROR = 104

    const val KEY_URL = "url"
    const val KEY_ELEMENT_ID = "element_id"
    const val KEY_VALUE = "value"
    const val KEY_WIDTH = "width"
    const val KEY_HEIGHT = "height"
    const val KEY_PAYLOAD = "payload"
    const val KEY_CONTENT_HEIGHT = "content_height"
    const val KEY_IMAGE_ID = "image_id"
    const val KEY_IMAGE_BYTES = "image_bytes"
    const val KEY_TITLE = "title"
    const val KEY_MESSAGE = "message"
}
