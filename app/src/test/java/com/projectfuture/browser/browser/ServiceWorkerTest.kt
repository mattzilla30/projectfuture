package com.projectfuture.browser.browser

import com.projectfuture.browser.js.InMemoryStorageBacking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the Service Worker pieces that have no Android dependency
 * (registration persistence, Cache Storage persistence, and the
 * install/activate/fetch lifecycle in [ServiceWorkerHost]) directly on the
 * host JVM - see ServiceWorkerHost's class doc for exactly what its
 * "re-run the script per event" model does and doesn't preserve.
 */
class ServiceWorkerTest {
    private val origin = "https://example.com:443"

    @Test fun registrationSurvivesANewRegistryInstanceOverTheSameBacking() {
        val backing = InMemoryStorageBacking() // stands in for the real, persisted SharedPrefs backing
        val registry1 = ServiceWorkerRegistry(backing)
        registry1.register(origin, "/", "https://example.com/sw.js")

        // A brand new ServiceWorkerRegistry instance over the same backing simulates the app being
        // killed and reopened (a fresh Kotlin object, but the same underlying persisted store).
        val registry2 = ServiceWorkerRegistry(backing)
        val regs = registry2.all(origin)
        assertEquals(1, regs.size)
        assertEquals("/", regs[0].scope)
        assertEquals("https://example.com/sw.js", regs[0].scriptUrl)
    }

    @Test fun installedFlagPersistsAcrossRegistryInstances() {
        val backing = InMemoryStorageBacking()
        val registry1 = ServiceWorkerRegistry(backing)
        registry1.register(origin, "/", "https://example.com/sw.js")
        registry1.markInstalled(origin, "/")

        val registry2 = ServiceWorkerRegistry(backing)
        assertTrue(registry2.all(origin).single().installed)
    }

    @Test fun findControllingPicksTheLongestMatchingScope() {
        val registry = ServiceWorkerRegistry(InMemoryStorageBacking())
        registry.register(origin, "/", "https://example.com/sw-root.js")
        registry.register(origin, "/app/", "https://example.com/sw-app.js")

        assertEquals("https://example.com/sw-app.js", registry.findControlling(origin, "/app/page.html")?.scriptUrl)
        assertEquals("https://example.com/sw-root.js", registry.findControlling(origin, "/other.html")?.scriptUrl)
    }

    @Test fun unregisterRemovesTheRegistrationForFutureLookups() {
        val registry = ServiceWorkerRegistry(InMemoryStorageBacking())
        registry.register(origin, "/", "https://example.com/sw.js")
        assertTrue(registry.unregister(origin, "/"))
        assertNull(registry.findControlling(origin, "/index.html"))
    }

    @Test fun cacheStoragePersistsPutEntriesAcrossInstances() {
        val backing = InMemoryStorageBacking()
        val cache1 = CacheStorageStore(backing)
        cache1.open(origin, "v1")
        cache1.put(origin, "v1", "https://example.com/index.html", 200, "OK", "<h1>cached</h1>")

        val cache2 = CacheStorageStore(backing)
        val entry = cache2.match(origin, "v1", "https://example.com/index.html")
        assertEquals("<h1>cached</h1>", entry?.body)
        assertEquals(listOf("v1"), cache2.cacheNames(origin))
    }

    @Test fun matchAnySearchesEveryCache() {
        val store = CacheStorageStore(InMemoryStorageBacking())
        store.put(origin, "v1", "https://example.com/a.html", 200, "OK", "a")
        store.put(origin, "v2", "https://example.com/b.html", 200, "OK", "b")
        assertEquals("a", store.matchAny(origin, "https://example.com/a.html")?.body)
        assertEquals("b", store.matchAny(origin, "https://example.com/b.html")?.body)
        assertNull(store.matchAny(origin, "https://example.com/missing.html"))
    }

    @Test fun deleteCacheRemovesItsEntriesToo() {
        val store = CacheStorageStore(InMemoryStorageBacking())
        store.put(origin, "v1", "https://example.com/a.html", 200, "OK", "a")
        assertTrue(store.deleteCache(origin, "v1"))
        assertNull(store.match(origin, "v1", "https://example.com/a.html"))
        assertTrue(store.cacheNames(origin).isEmpty())
    }

    @Test fun installAndFetchEventsWarmThenServeFromCache() {
        val registry = ServiceWorkerRegistry(InMemoryStorageBacking())
        val cacheStore = CacheStorageStore(InMemoryStorageBacking())
        registry.register(origin, "/", "https://example.com/sw.js")

        val script = """
            self.addEventListener('install', function(event) {
                event.waitUntil(
                    caches.open('v1').then(function(cache) {
                        cache.put('https://example.com/offline.html', new Response('offline page', { status: 200 }));
                    })
                );
            });
            self.addEventListener('fetch', function(event) {
                event.respondWith(
                    caches.match(event.request.url).then(function(cached) {
                        if (cached) { return cached; }
                        return new Response('network fallback', { status: 404 });
                    })
                );
            });
        """.trimIndent()

        val host = ServiceWorkerHost(script, origin, "/", registry, cacheStore)

        val cached = host.handleFetch("https://example.com/offline.html", "GET")
        assertEquals("offline page", cached?.body)
        assertEquals(200, cached?.status)
        assertTrue(registry.all(origin).single().installed)

        val fallback = host.handleFetch("https://example.com/missing.html", "GET")
        assertEquals("network fallback", fallback?.body)
        assertEquals(404, fallback?.status)
    }

    @Test fun installOnlyRunsOnceAcrossMultipleFetchDispatches() {
        val registry = ServiceWorkerRegistry(InMemoryStorageBacking())
        val cacheStore = CacheStorageStore(InMemoryStorageBacking())
        registry.register(origin, "/", "https://example.com/sw.js")

        // Each fetch counts an install by writing an entry into the cache with a running total;
        // if install re-ran on every fetch dispatch the cache would show more than one write.
        val script = """
            var installCount = 0;
            self.addEventListener('install', function(event) {
                installCount = installCount + 1;
                event.waitUntil(caches.open('v1').then(function(c) { c.put('/install-count', new Response(String(installCount))); }));
            });
            self.addEventListener('fetch', function(event) {
                event.respondWith(new Response('ok'));
            });
        """.trimIndent()
        val host = ServiceWorkerHost(script, origin, "/", registry, cacheStore)

        host.handleFetch("https://example.com/one.html", "GET")
        host.handleFetch("https://example.com/two.html", "GET")
        host.handleFetch("https://example.com/three.html", "GET")

        assertEquals("1", cacheStore.match(origin, "v1", "/install-count")?.body)
    }

    @Test fun aScriptThatFailsToParseIsNeverMarkedInstalled() {
        val registry = ServiceWorkerRegistry(InMemoryStorageBacking())
        registry.register(origin, "/", "https://example.com/sw.js")
        // Syntactically invalid JS - ServiceWorkerHost.prepare() must fail to parse/run this, so
        // install/activate never actually dispatch to anything.
        val brokenScript = "self.addEventListener('install', function(event) { {{{ not valid js"
        val host = ServiceWorkerHost(brokenScript, origin, "/", registry, CacheStorageStore(InMemoryStorageBacking()))

        // handleFetch (like a real fetch event) triggers ensureInstalledAndActivated() internally.
        host.handleFetch("https://example.com/index.html", "GET")

        assertTrue(
            "a service worker whose script never even parsed must not be marked installed - " +
                "it never ran its install/activate listeners at all",
            registry.all(origin).single().installed.not()
        )
    }

    @Test fun fetchEventWithNoRespondWithFallsThroughToNetwork() {
        val registry = ServiceWorkerRegistry(InMemoryStorageBacking())
        registry.register(origin, "/", "https://example.com/sw.js")
        val script = "self.addEventListener('fetch', function(event) { /* doesn't call respondWith */ });"
        val host = ServiceWorkerHost(script, origin, "/", registry, CacheStorageStore(InMemoryStorageBacking()))
        assertNull(host.handleFetch("https://example.com/anything.html", "GET"))
    }

    @Test fun nonGetRequestsAreNeverIntercepted() {
        val registry = ServiceWorkerRegistry(InMemoryStorageBacking())
        registry.register(origin, "/", "https://example.com/sw.js")
        val script = "self.addEventListener('fetch', function(event) { event.respondWith(new Response('should not be used')); });"
        val host = ServiceWorkerHost(script, origin, "/", registry, CacheStorageStore(InMemoryStorageBacking()))
        assertNull(host.handleFetch("https://example.com/submit", "POST"))
    }
}
