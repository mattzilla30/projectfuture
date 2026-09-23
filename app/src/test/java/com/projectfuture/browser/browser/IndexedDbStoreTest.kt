package com.projectfuture.browser.browser

import com.projectfuture.browser.js.JsNumber
import com.projectfuture.browser.js.JsString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IndexedDbStoreTest {

    @Test fun firstOpenIsTrueOnceThenFalse() {
        val store = IndexedDbStore()
        assertTrue(store.isFirstOpen("mydb"))
        assertTrue(!store.isFirstOpen("mydb"))
        assertTrue(store.isFirstOpen("other-db"))
    }

    @Test fun putGetDeleteRoundTrip() {
        val store = IndexedDbStore()
        store.createObjectStore("db1", "items")
        store.put("db1", "items", "a", JsString("hello"))
        assertEquals(JsString("hello"), store.get("db1", "items", "a"))

        store.delete("db1", "items", "a")
        assertNull(store.get("db1", "items", "a"))
    }

    @Test fun getAllAndClear() {
        val store = IndexedDbStore()
        store.put("db1", "items", "a", JsNumber(1.0))
        store.put("db1", "items", "b", JsNumber(2.0))
        assertEquals(2, store.getAll("db1", "items").size)

        store.clear("db1", "items")
        assertEquals(0, store.getAll("db1", "items").size)
    }

    @Test fun separateDatabasesAndStoresDoNotLeak() {
        val store = IndexedDbStore()
        store.put("db1", "items", "k", JsString("db1-value"))
        store.put("db2", "items", "k", JsString("db2-value"))
        assertEquals(JsString("db1-value"), store.get("db1", "items", "k"))
        assertEquals(JsString("db2-value"), store.get("db2", "items", "k"))
        assertNull(store.get("db1", "other-store", "k"))
    }
}
