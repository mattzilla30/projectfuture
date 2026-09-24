package com.projectfuture.browser.js

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises [JsStorage] (the `localStorage`/`sessionStorage` JS-facing
 * wrapper) directly against an [InMemoryStorageBacking], covering both
 * ordinary Web Storage semantics and the origin isolation the backing is
 * supposed to give every JsStorage instance keyed by a different origin.
 */
class JsStorageTest {

    @Test fun getSetRemoveClearRoundTrip() {
        val storage = JsStorage("https://example.com:443", InMemoryStorageBacking())
        assertTrue(storage.get("getItem") is NativeFunction) // reserved name resolves to the method, not a stored value
        (storage.get("setItem") as NativeFunction).call(Interpreter(), storage, listOf(JsString("a"), JsString("1")))
        assertEquals(JsString("1"), (storage.get("getItem") as NativeFunction).call(Interpreter(), storage, listOf(JsString("a"))))

        // Setting an existing key updates in place, not a duplicate.
        (storage.get("setItem") as NativeFunction).call(Interpreter(), storage, listOf(JsString("a"), JsString("2")))
        assertEquals(JsNumber(1.0), storage.get("length"))
        assertEquals(JsString("2"), (storage.get("getItem") as NativeFunction).call(Interpreter(), storage, listOf(JsString("a"))))

        (storage.get("removeItem") as NativeFunction).call(Interpreter(), storage, listOf(JsString("a")))
        assertEquals(JsNull, (storage.get("getItem") as NativeFunction).call(Interpreter(), storage, listOf(JsString("a"))))
    }

    @Test fun keyOutOfRangeReturnsNullNotThrow() {
        val storage = JsStorage("https://example.com:443", InMemoryStorageBacking())
        assertEquals(JsNull, (storage.get("key") as NativeFunction).call(Interpreter(), storage, listOf(JsNumber(5.0))))
    }

    @Test fun clearOnEmptyStoreIsSafeNoOp() {
        val storage = JsStorage("https://example.com:443", InMemoryStorageBacking())
        (storage.get("clear") as NativeFunction).call(Interpreter(), storage, emptyList())
        assertEquals(JsNumber(0.0), storage.get("length"))
    }

    @Test fun propertyStyleAccessRoutesThroughTheSameBacking() {
        val backing = InMemoryStorageBacking()
        val storage = JsStorage("https://example.com:443", backing)
        storage.set("foo", JsString("bar"))
        // Property-style get must read back the same value via the backing, not a plain JS object property.
        assertEquals(JsString("bar"), storage.get("foo"))
        assertEquals("bar", backing.get("https://example.com:443", "foo"))
    }

    /**
     * Object.keys(storage)/for-in ultimately call JsObject.ownKeys(), which
     * JsStorage never overrode - it kept using the base class's `properties`
     * map, which JsStorage never actually writes to (everything goes through
     * `backing` instead). So ownKeys() always returned empty, even with
     * items in the store.
     */
    @Test fun ownKeysEnumeratesStoredKeysForObjectKeysAndForIn() {
        val backing = InMemoryStorageBacking()
        val storage = JsStorage("https://example.com:443", backing)
        storage.set("a", JsString("1"))
        storage.set("b", JsString("2"))

        assertEquals(setOf("a", "b"), storage.ownKeys().toSet())
    }

    @Test fun crossOriginInstancesOverSameBackingDoNotLeak() {
        val backing = InMemoryStorageBacking()
        val a = JsStorage("https://example.com:443", backing)
        val b = JsStorage("https://evil.com:443", backing)
        a.set("secret", JsString("a-only"))

        assertEquals(JsUndefined, b.get("secret"))
        assertTrue(a.ownKeys().contains("secret"))
        assertFalse(b.ownKeys().contains("secret"))
    }

    @Test fun sameHostDifferentSchemeDoesNotShareStorage() {
        val backing = InMemoryStorageBacking()
        val http = JsStorage("http://example.com:80", backing)
        val https = JsStorage("https://example.com:443", backing)
        http.set("k", JsString("http-value"))

        assertEquals(JsUndefined, https.get("k"))
    }

    @Test fun sameHostDifferentPortDoesNotShareStorage() {
        val backing = InMemoryStorageBacking()
        val p1 = JsStorage("https://example.com:443", backing)
        val p2 = JsStorage("https://example.com:8443", backing)
        p1.set("k", JsString("443-value"))

        assertEquals(JsUndefined, p2.get("k"))
    }
}
