/*
 * Copyright 2016-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

@file:JvmMultifileClass
@file:JvmName("FlowKt")

package kotlinx.coroutines.flow

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.flow.internal.*
import kotlinx.coroutines.internal.*
import kotlinx.coroutines.internal.Symbol
import kotlinx.coroutines.selects.*
import kotlin.jvm.*
import kotlin.time.*

/* Scaffolding for Knit code examples
<!--- TEST_NAME FlowDelayTest -->
<!--- PREFIX .*-duration-.*
@file:OptIn(ExperimentalTime::class)
----- INCLUDE .*-duration-.*
import kotlin.time.*
----- INCLUDE .*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

fun main() = runBlocking {
----- SUFFIX .*
.toList().joinToString().let { println(it) } }
-->
*/

/**
 * Returns a flow that mirrors the original flow, but filters out values
 * that are followed by the newer values within the given [timeout][timeoutMillis].
 * The latest value is always emitted.
 *
 * Example:
 *
 * ```kotlin
 * flow {
 *     emit(1)
 *     delay(90)
 *     emit(2)
 *     delay(90)
 *     emit(3)
 *     delay(1010)
 *     emit(4)
 *     delay(1010)
 *     emit(5)
 * }.debounce(1000)
 * ```
 * <!--- KNIT example-delay-01.kt -->
 *
 * produces the following emissions
 *
 * ```text
 * 3, 4, 5
 * ```
 * <!--- TEST -->
 *
 * Note that the resulting flow does not emit anything as long as the original flow emits
 * items faster than every [timeoutMillis] milliseconds.
 */
@FlowPreview
public fun <T> Flow<T>.debounce(timeoutMillis: Long): Flow<T> {
    require(timeoutMillis >= 0L) { "Debounce timeout should not be negative" }
    if (timeoutMillis == 0L) return this
    return debounceInternal { timeoutMillis }
}

/**
 * Returns a flow that mirrors the original flow, but filters out values
 * that are followed by the newer values within the given [timeout][timeoutMillis].
 * The latest value is always emitted.
 *
 * A variation of [debounce] that allows specifying the timeout value dynamically.
 *
 * Example:
 *
 * ```kotlin
 * flow {
 *     emit(1)
 *     delay(90)
 *     emit(2)
 *     delay(90)
 *     emit(3)
 *     delay(1010)
 *     emit(4)
 *     delay(1010)
 *     emit(5)
 * }.debounce {
 *     if (it == 1) {
 *         0L
 *     } else {
 *         1000L
 *     }
 * }
 * ```
 * <!--- KNIT example-delay-02.kt -->
 *
 * produces the following emissions
 *
 * ```text
 * 1, 3, 4, 5
 * ```
 * <!--- TEST -->
 *
 * Note that the resulting flow does not emit anything as long as the original flow emits
 * items faster than every [timeoutMillis] milliseconds.
 *
 * @param timeoutMillis [T] is the emitted value and the return value is timeout in milliseconds.
 */
@FlowPreview
@OptIn(kotlin.experimental.ExperimentalTypeInference::class)
@OverloadResolutionByLambdaReturnType
public fun <T> Flow<T>.debounce(timeoutMillis: (T) -> Long): Flow<T> =
    debounceInternal(timeoutMillis)

/**
 * Returns a flow that mirrors the original flow, but filters out values
 * that are followed by the newer values within the given [timeout].
 * The latest value is always emitted.
 *
 * Example:
 *
 * ```kotlin
 * flow {
 *     emit(1)
 *     delay(90.milliseconds)
 *     emit(2)
 *     delay(90.milliseconds)
 *     emit(3)
 *     delay(1010.milliseconds)
 *     emit(4)
 *     delay(1010.milliseconds)
 *     emit(5)
 * }.debounce(1000.milliseconds)
 * ```
 * <!--- KNIT example-delay-duration-01.kt -->
 *
 * produces the following emissions
 *
 * ```text
 * 3, 4, 5
 * ```
 * <!--- TEST -->
 *
 * Note that the resulting flow does not emit anything as long as the original flow emits
 * items faster than every [timeout] milliseconds.
 */
@ExperimentalTime
@FlowPreview
public fun <T> Flow<T>.debounce(timeout: Duration): Flow<T> =
    debounce(timeout.toDelayMillis())

/**
 * Returns a flow that mirrors the original flow, but filters out values
 * that are followed by the newer values within the given [timeout].
 * The latest value is always emitted.
 *
 * A variation of [debounce] that allows specifying the timeout value dynamically.
 *
 * Example:
 *
 * ```kotlin
 * flow {
 *     emit(1)
 *     delay(90.milliseconds)
 *     emit(2)
 *     delay(90.milliseconds)
 *     emit(3)
 *     delay(1010.milliseconds)
 *     emit(4)
 *     delay(1010.milliseconds)
 *     emit(5)
 * }.debounce {
 *     if (it == 1) {
 *         0.milliseconds
 *     } else {
 *         1000.milliseconds
 *     }
 * }
 * ```
 * <!--- KNIT example-delay-duration-02.kt -->
 *
 * produces the following emissions
 *
 * ```text
 * 1, 3, 4, 5
 * ```
 * <!--- TEST -->
 *
 * Note that the resulting flow does not emit anything as long as the original flow emits
 * items faster than every [timeout] unit.
 *
 * @param timeout [T] is the emitted value and the return value is timeout in [Duration].
 */
@ExperimentalTime
@FlowPreview
@JvmName("debounceDuration")
@OptIn(kotlin.experimental.ExperimentalTypeInference::class)
@OverloadResolutionByLambdaReturnType
public fun <T> Flow<T>.debounce(timeout: (T) -> Duration): Flow<T> =
    debounceInternal { emittedItem ->
        timeout(emittedItem).toDelayMillis()
    }

private fun <T> Flow<T>.debounceInternal(timeoutMillisSelector: (T) -> Long) : Flow<T> =
    scopedFlow { downstream ->
        // Produce the values using the default (rendezvous) channel
        // Note: the actual type is Any, KT-30796
        val values = produce<Any?> {
            collect { value -> send(value ?: NULL) }
        }
        // Now consume the values
        var lastValue: Any? = null
        while (lastValue !== DONE) {
            var timeoutMillis = 0L // will be always computed when lastValue != null
            // Compute timeout for this value
            if (lastValue != null) {
                timeoutMillis = timeoutMillisSelector(NULL.unbox(lastValue))
                require(timeoutMillis >= 0L) { "Debounce timeout should not be negative" }
                if (timeoutMillis == 0L) {
                    downstream.emit(NULL.unbox(lastValue))
                    lastValue = null // Consume the value
                }
            }
            // assert invariant: lastValue != null implies timeoutMillis > 0
            assert { lastValue == null || timeoutMillis > 0 }
            // wait for the next value with timeout
            select<Unit> {
                // Set timeout when lastValue exists and is not consumed yet
                if (lastValue != null) {
                    onTimeout(timeoutMillis) {
                        downstream.emit(NULL.unbox(lastValue))
                        lastValue = null // Consume the value
                    }
                }
                // Should be receiveOrClosed when boxing issues are fixed
                values.onReceiveOrNull { value ->
                    if (value == null) {
                        if (lastValue != null) downstream.emit(NULL.unbox(lastValue))
                        lastValue = DONE
                    } else {
                        lastValue = value
                    }
                }
            }
        }
    }

/**
 * Returns a flow that emits only the latest value emitted by the original flow during the given sampling [period][periodMillis].
 *
 * Example:
 *
 * ```kotlin
 * flow {
 *     repeat(10) {
 *         emit(it)
 *         delay(110)
 *     }
 * }.sample(200)
 * ```
 * <!--- KNIT example-delay-03.kt -->
 *
 * produces the following emissions
 *
 * ```text
 * 1, 3, 5, 7, 9
 * ```
 * <!--- TEST -->
 *
 * Note that the latest element is not emitted if it does not fit into the sampling window.
 */
@FlowPreview
public fun <T> Flow<T>.sample(periodMillis: Long): Flow<T> {
    require(periodMillis > 0) { "Sample period should be positive" }
    return scopedFlow { downstream ->
        val values = produce<Any?>(capacity = Channel.CONFLATED) {
            // Actually Any, KT-30796
            collect { value -> send(value ?: NULL) }
        }
        var lastValue: Any? = null
        val ticker = fixedPeriodTicker(periodMillis)
        while (lastValue !== DONE) {
            select<Unit> {
                values.onReceiveOrNull {
                    if (it == null) {
                        ticker.cancel(ChildCancelledException())
                        lastValue = DONE
                    } else {
                        lastValue = it
                    }
                }

                // todo: shall be start sampling only when an element arrives or sample aways as here?
                ticker.onReceive {
                    val value = lastValue ?: return@onReceive
                    lastValue = null // Consume the value
                    downstream.emit(NULL.unbox(value))
                }
            }
        }
    }
}

/*
 * TODO this design (and design of the corresponding operator) depends on #540
 */
internal fun CoroutineScope.fixedPeriodTicker(delayMillis: Long, initialDelayMillis: Long = delayMillis): ReceiveChannel<Unit> {
    require(delayMillis >= 0) { "Expected non-negative delay, but has $delayMillis ms" }
    require(initialDelayMillis >= 0) { "Expected non-negative initial delay, but has $initialDelayMillis ms" }
    return produce(capacity = 0) {
        delay(initialDelayMillis)
        while (true) {
            channel.send(Unit)
            delay(delayMillis)
        }
    }
}

/**
 * Returns a flow that emits only the latest value emitted by the original flow during the given sampling [period].
 *
 * Example:
 *
 * ```kotlin
 * flow {
 *     repeat(10) {
 *         emit(it)
 *         delay(110.milliseconds)
 *     }
 * }.sample(200.milliseconds)
 * ```
 * <!--- KNIT example-delay-duration-03.kt -->
 *
 * produces the following emissions
 *
 * ```text
 * 1, 3, 5, 7, 9
 * ```
 * <!--- TEST -->
 *
 * Note that the latest element is not emitted if it does not fit into the sampling window.
 */
@ExperimentalTime
@FlowPreview
public fun <T> Flow<T>.sample(period: Duration): Flow<T> = sample(period.toDelayMillis())

/**
 * Returns a flow that will timeout if the upstream takes too long to emit.
 *
 * Example:
 *
 * ```kotlin
 * flow {
 *     emit(1)
 *     delay(100)
 *     emit(2)
 *     delay(100)
 *     emit(3)
 *     delay(1000)
 *     emit(4)
 * }.timeout(100.milliseconds) {
 *     emit(-1) // Item to emit on timeout
 * }.onEach {
 *     delay(300) // This will not cause a timeout
 * }
 * ```
 * <!--- KNIT example-timeout-duration-01.kt -->
 *
 * produces the following emissions
 *
 * ```text
 * 1, 2, 3, -1
 * ```
 * <!--- TEST -->
 *
 * Note that delaying on the downstream doesn't trigger the timeout.
 *
 * @param timeout Timeout period
 * @param action Action to invoke on timeout. Default is to throw [FlowTimeoutException]
 */
@ExperimentalTime
public fun <T> Flow<T>.timeout(
    timeout: Duration,
    @BuilderInference action: suspend FlowCollector<T>.() -> Unit = { throw FlowTimeoutException(timeout.toDelayMillis()) }
): Flow<T> = timeout(timeout.toDelayMillis(), action)

/**
 * Returns a flow that will timeout if the upstream takes too long to emit.
 *
 * Example:
 *
 * ```kotlin
 * flow {
 *     emit(1)
 *     delay(100)
 *     emit(2)
 *     delay(100)
 *     emit(3)
 *     delay(1000)
 *     emit(4)
 * }.timeout(100) {
 *     emit(-1) // Item to emit on timeout
 * }.onEach {
 *     delay(300) // This will not cause a timeout
 * }
 * ```
 * <!--- KNIT example-timeout-duration-02.kt -->
 *
 * produces the following emissions
 *
 * ```text
 * 1, 2, 3, -1
 * ```
 * <!--- TEST -->
 *
 * Note that delaying on the downstream doesn't trigger the timeout.
 *
 * @param timeoutMillis Timeout period in millis
 * @param action Action to invoke on timeout. Default is to throw [FlowTimeoutException]
 */
@ExperimentalTime
public fun <T> Flow<T>.timeout(
    timeoutMillis: Long,
    @BuilderInference action: suspend FlowCollector<T>.() -> Unit = { throw FlowTimeoutException(timeoutMillis) }
): Flow<T> = timeoutInternal(timeoutMillis, action)

@ExperimentalTime
private fun <T> Flow<T>.timeoutInternal(
    timeoutMillis: Long,
    action: suspend FlowCollector<T>.() -> Unit
): Flow<T> = scopedFlow<T> { downStream ->
    require(timeoutMillis >= 0L) { "Timeout should not be negative" }

    // Produce the values using the default (rendezvous) channel
    // Similar to [debounceInternal]
    val values = produce<Any?> {
        var timeoutJob = launch { // Emits timeout unless cancelled
            delay(timeoutMillis)
            send(TIMEOUT)
        }
        try {
            collect {
                timeoutJob.cancel() // Upstream emitted, so cancel the job

                send(it ?: NULL)

                // We reset the job here!. The reason being is that the `flow.emit()` suspends, which in turn suspends `send()`.
                // We only want to measure a timeout if the producer took longer than `timeoutMillis`, not producer + consumer
                timeoutJob = launch {
                    delay(timeoutMillis)
                    send(TIMEOUT)
                }
            }
        } finally {
            timeoutJob.cancel()
            send(DONE) // Special signal to let flow end
        }
    }

    // Await for values from our producer now
    whileSelect {
        values.onReceiveOrNull { value ->
            if (value !== DONE) {
                if (value === TIMEOUT) {
                    throw InternalFlowTimeoutException()
                }
                downStream.emit(NULL.unbox(value))
                return@onReceiveOrNull true
            }
            return@onReceiveOrNull false // We got the DONE signal, so exit the while loop
        }
    }
}.catch { e ->
    if (e is InternalFlowTimeoutException) {
        action()
    } else {
        throw e
    }
}

/**
 * This exception is thrown by [timeout] to indicate an upstream flow timeout.
 *
 * @constructor Creates a timeout exception with the given message. This constructor is needed for exception stack-traces recovery.
 */
public class FlowTimeoutException internal constructor(message: String) : CancellationException(message), CopyableThrowable<FlowTimeoutException> {

    // message is never null in fact
    override fun createCopy(): FlowTimeoutException? =
        FlowTimeoutException(message ?: "").also { it.initCause(this) }
}

@Suppress("FunctionName")
internal fun FlowTimeoutException(time: Long) : FlowTimeoutException = FlowTimeoutException("Upstream flow timed out waiting for $time ms")

// Special timeout flag
private val TIMEOUT = Symbol("TIMEOUT")

// Special indicator exception
private class InternalFlowTimeoutException : Exception("Internal flow timeout exception")