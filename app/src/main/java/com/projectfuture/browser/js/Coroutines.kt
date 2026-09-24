package com.projectfuture.browser.js

import java.util.concurrent.SynchronousQueue

sealed class CoroutineOutMsg
data class Yielded(val value: JsValue) : CoroutineOutMsg()
data class Completed(val value: JsValue) : CoroutineOutMsg()
data class Failed(val error: JsValue) : CoroutineOutMsg()

private sealed class CoroutineInMsg
private data class ResumeWith(val value: JsValue) : CoroutineInMsg()
private data class ThrowWith(val value: JsValue) : CoroutineInMsg()
private data class ReturnWith(val value: JsValue) : CoroutineInMsg()

/** Thrown inside a generator/async body's own thread by [JsCoroutine.pause] when the driver calls `.return(v)`; unwinds through any `finally` blocks in the body (same as a real `return` reaching a generator's suspended `yield`), then is caught in [JsCoroutine]'s runner to complete the coroutine with `v`. */
class CoroutineReturn(val value: JsValue) : RuntimeException(null, null, false, false)

/**
 * Backs both generators and async functions with one primitive: a body
 * that runs on its own JVM thread and calls [pause] at each `yield`/
 * `await`, handing a value out to whoever is driving it (a generator's
 * `.next()` caller, or [Interpreter.runAsync]'s internal driving loop) and
 * blocking until the driver resumes it with a value, an exception
 * ([throwIn]), or an early return ([returnIn]).
 *
 * This interpreter is otherwise a plain synchronous tree-walker with no
 * continuation-passing transform, so it has no other way to suspend a
 * Kotlin call stack in the middle of walking a generator/async function's
 * body and resume it later. Real JVM threads are a deliberate, pragmatic
 * choice to get that suspend/resume for free: the driver and the body
 * thread hand off control strictly one at a time via [SynchronousQueue]
 * (whichever side is not blocked in a queue call is the only one running),
 * so despite using two threads, execution stays exactly as single-
 * threaded/deterministic as the rest of this interpreter - it's just a
 * mechanism, not real concurrency. The body thread is a daemon thread, so
 * an abandoned generator (one whose iteration is never finished, e.g. a
 * `for-of` that `break`s early) leaks a blocked thread rather than being
 * cleaned up via the iterator-closing protocol real JS has - an accepted
 * simplification given this interpreter's scripts are short-lived.
 */
class JsCoroutine(private val runBody: (JsCoroutine) -> JsValue) {
    private val outQueue = SynchronousQueue<CoroutineOutMsg>()
    private val inQueue = SynchronousQueue<CoroutineInMsg>()
    private var started = false
    var done = false
        private set

    /** Called from the body thread at a `yield`/`await` point. Blocks until the driver resumes it. */
    fun pause(value: JsValue): JsValue {
        outQueue.put(Yielded(value))
        return when (val msg = inQueue.take()) {
            is ResumeWith -> msg.value
            is ThrowWith -> throw JsException(msg.value)
            is ReturnWith -> throw CoroutineReturn(msg.value)
        }
    }

    private fun start() {
        started = true
        val t = Thread {
            val result = try {
                Completed(runBody(this))
            } catch (r: CoroutineReturn) {
                Completed(r.value)
            } catch (e: JsException) {
                Failed(e.value)
            } catch (e: Throwable) {
                Failed(jsError(e.message ?: e.toString()).value)
            }
            outQueue.put(result)
        }
        t.isDaemon = true
        t.name = "js-coroutine"
        t.start()
    }

    fun resume(value: JsValue): CoroutineOutMsg {
        if (done) return Completed(JsUndefined)
        if (!started) start() else inQueue.put(ResumeWith(value))
        val msg = outQueue.take()
        if (msg !is Yielded) done = true
        return msg
    }

    fun throwIn(value: JsValue): CoroutineOutMsg {
        if (!started || done) { done = true; throw JsException(value) }
        inQueue.put(ThrowWith(value))
        val msg = outQueue.take()
        if (msg !is Yielded) done = true
        return msg
    }

    fun returnIn(value: JsValue): CoroutineOutMsg {
        if (!started || done) { done = true; return Completed(value) }
        inQueue.put(ReturnWith(value))
        val msg = outQueue.take()
        if (msg !is Yielded) done = true
        return msg
    }
}

/** Wraps a [JsCoroutine] as a JsValue so it can live in an [Environment] under the hidden `__coroutine__` binding that `yield`/`await` look up - the same pattern `__superclassctor__` uses for `super`. */
class CoroutineRef(val coroutine: JsCoroutine) : JsObject()

/**
 * The object `function*` calls return: the iterator-protocol wrapper
 * (`.next()`/`.return()`/`.throw()`, plus `Symbol.iterator` returning
 * itself, so generators are directly usable in `for-of`/spread/
 * destructuring - see Interpreter.iterableToSequence) around a [JsCoroutine]
 * that runs the generator body.
 */
class JsGenerator(private val coroutine: JsCoroutine) : JsObject() {
    override fun get(name: String): JsValue = when (name) {
        "next" -> NativeFunction("next", 1) { _, _, args -> stepResult(coroutine.resume(args.getOrElse(0) { JsUndefined })) }
        "return" -> NativeFunction("return", 1) { _, _, args -> stepResult(coroutine.returnIn(args.getOrElse(0) { JsUndefined })) }
        "throw" -> NativeFunction("throw", 1) { _, _, args -> stepResult(coroutine.throwIn(args.getOrElse(0) { JsUndefined })) }
        SYMBOL_ITERATOR_KEY -> NativeFunction("[Symbol.iterator]", 0) { _, thisArg, _ -> thisArg }
        else -> super.get(name)
    }

    private fun stepResult(msg: CoroutineOutMsg): JsValue {
        val result = JsObject()
        when (msg) {
            is Yielded -> { result.set("value", msg.value); result.set("done", JsBoolean(false)) }
            is Completed -> { result.set("value", msg.value); result.set("done", JsBoolean(true)) }
            is Failed -> throw JsException(msg.error)
        }
        return result
    }
}
