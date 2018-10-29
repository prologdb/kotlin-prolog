package com.github.prologdb.async

import java.util.*
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.coroutines.experimental.*

class WorkableFutureImpl<T>(override val principal: Any, code: suspend WorkableFutureBuilder.() -> T) : WorkableFuture<T> {

    private val onComplete = object : Continuation<T> {
        override val context: CoroutineContext = EmptyCoroutineContext

        override fun resume(value: T) {
            synchronized(mutex) {
                result = value!!
                state = State.COMPLETED
            }
        }

        override fun resumeWithException(exception: Throwable) {
            synchronized(mutex) {
                error = exception
                state = State.COMPLETED
            }

            tearDown()
        }
    }

    private val mutex = Any()

    @Volatile
    private var result: T? = null
    @Volatile
    private var error: Throwable? = null
        set(value) {
            field = value
        }

    @Volatile
    private var state: State = State.RUNNING

    @Volatile
    private var currentWaitingFuture: Future<*>? = null

    @Volatile
    private var currentFoldingSequence: LazySequence<*>? = null
    @Volatile
    private var currentFoldingCarry: Any? = null
    @Volatile
    private var currentFoldingAccumulator: ((Any, Any) -> Any)? = null

    /**
     * Teardown code, in the reverse order as it was added using [WorkableFutureBuilder.finally]; see
     * [LinkedList.addFirst].
     * Is set on the first call to [WorkableFutureBuilder.finally] because it is expected
     * that most futures will not have teardown logic.
     */
    private var teardownLogic: LinkedList<() -> Any?>? = null

    override fun isDone(): Boolean = state == State.COMPLETED || state == State.CANCELLED

    override fun isCancelled(): Boolean = state == State.CANCELLED

    override fun step(): Boolean {
        synchronized(mutex) {
            when (state) {
                State.RUNNING -> continuation.resume(Unit)
                State.WAITING_ON_FUTURE -> {
                    val future = currentWaitingFuture!!

                    if (future.isDone) {
                        state = State.RUNNING
                        currentWaitingFuture = null
                        continuation.resume(Unit)
                    }
                    else if (future is WorkableFuture) {
                        if (future.step()) {
                            state = State.RUNNING
                            currentWaitingFuture = null
                            continuation.resume(Unit)
                        }
                    }
                }
                State.FOLDING_SEQUENCE -> {
                    val sequence = currentFoldingSequence!!
                    var seqState = sequence.state
                    if (seqState == LazySequence.State.PENDING) {
                        seqState = sequence.step()
                    }

                    when (seqState) {
                        LazySequence.State.DEPLETED -> {
                            // great, accumulation is done
                            val result = currentFoldingCarry!!

                            state = State.RUNNING
                            sequence.close()
                            currentFoldingSequence = null
                            currentFoldingCarry = null
                            currentFoldingCarry = null

                            continuation.resume(result)
                        }
                        LazySequence.State.RESULTS_AVAILABLE -> {
                            val element = sequence.tryAdvance()!!
                            currentFoldingCarry = currentFoldingAccumulator!!(currentFoldingCarry!!, element)
                        }
                        LazySequence.State.FAILED -> {
                            val exception = try {
                                sequence.tryAdvance()
                                null
                            } catch (ex: Throwable) {
                                ex
                            } ?: throw IllegalStateException("Subsequence failed but did not throw the execption when tryAdvance() was called")

                            state = State.RUNNING
                            currentFoldingCarry = null
                            currentFoldingSequence = null
                            currentFoldingAccumulator = null

                            continuation.resumeWithException(exception)
                        }
                        LazySequence.State.PENDING -> { /* cannot do anything */ }
                    }
                }
                State.COMPLETED, State.CANCELLED -> { }
            }
        }

        return isDone
    }

    override fun get(): T {
        do {
            step()
            when(state) {
                State.COMPLETED -> return result ?: throw error!!
                State.CANCELLED -> throw error as CancellationException
                State.RUNNING -> { /* do nothing, keep looping */ }
                State.WAITING_ON_FUTURE -> {
                    try {
                        currentWaitingFuture!!.get()
                    }
                    catch (handleLater: Exception) {
                        /* calling get() will complete the future
                         * the next iteration will call step()
                         * step() will detect that the future is
                         * completed and update the state accordingly
                         * the CANCELLED branch of this when() will
                         * pick that up
                         */
                    }
                }
                State.FOLDING_SEQUENCE -> {
                    // the suspend fun will call tryAdvance() and thus do
                    // all work necessary.
                    continuation.resume(Unit)
                }
            }
        } while (true)
    }

    override fun get(timeout: Long, unit: TimeUnit?): T {
        TODO("Implement only when needed.")
    }

    override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
        synchronized(mutex) {
            when (state) {
                State.RUNNING, State.WAITING_ON_FUTURE -> {
                    state = State.CANCELLED
                    error = CancellationException()
                    currentFoldingSequence?.close()
                    currentWaitingFuture?.cancel(true)
                    currentWaitingFuture = null

                    // tear down will happen on the next suspend

                    return true
                }
                State.FOLDING_SEQUENCE -> {
                    state = State.CANCELLED
                    error = CancellationException()
                    currentWaitingFuture?.cancel(true)
                    currentFoldingSequence?.close()
                    currentFoldingSequence = null

                    tearDown()

                    return true
                }
                State.COMPLETED, State.CANCELLED -> {
                    return false
                }
            }
        }
    }

    private fun tearDown() {
        teardownLogic?.let {
            var errors: ArrayList<Throwable>? = null

            it.forEach { tearDown ->
                try {
                    tearDown()
                }
                catch (ex: Throwable) {
                    if (errors == null) errors = ArrayList()
                    errors!!.add(ex)
                }
            }

            if (errors != null && errors!!.isNotEmpty()) {
                val topEx = RuntimeException("TearDown code errored after cancel", errors!!.first())
                val errorIt = errors!!.iterator()
                errorIt.next() // skip first, is set as cause
                errorIt.forEachRemaining(topEx::addSuppressed)

                throw topEx
            }
        }
    }

    private val Builder = object : WorkableFutureBuilder {
        override suspend fun <E> await(future: Future<E>): E {
            if (future is WorkableFuture && future.principal != principal) {
                throw PrincipalConflictException(principalInError = principal, violatedPrincipal = future.principal)
            }

            synchronized(mutex) {
                if (state == State.CANCELLED) {
                    // this has been cancelled, shut it down right here

                    teardownLogic?.let {
                        // the issue is: if there is teardown to be executed it depends on the future being completed
                        // possibly reversing what that future does. So we have to wait for completion before we
                        // can run tearDown() and then finally put the coroutine to rest

                        try {
                            future.get()
                        }
                        catch (swalloed: Throwable) {}

                        tearDown()
                    }

                    suspendCoroutine<Unit> { /* not picking up the continuation effectively aborts the coroutine. */ }
                    throw Exception("This should never have been thrown")
                }

                if (state != State.RUNNING) {
                    throw IllegalStateException("Future is in state $state, cannot wait for a future.")
                }

                if (future.isDone) {
                    return try {
                        future.get()
                    }
                    catch (ex: ExecutionException) {
                        throw ex.cause ?: ex
                    }
                }

                currentWaitingFuture = future
                state = State.WAITING_ON_FUTURE
            }

            suspendCoroutine<Any> { continuation = it }
            return try {
                future.get()
            }
            catch (ex: ExecutionException) {
                throw ex.cause ?: ex
            }
        }

        override suspend fun <E, C> foldRemaining(sequence: LazySequence<E>, initial: C, accumulator: (C, E) -> C): C {
            if (sequence.principal != principal) {
                throw PrincipalConflictException(principalInError = principal, violatedPrincipal = sequence.principal)
            }

            synchronized(mutex) {
                if (state == State.CANCELLED) {
                    // this has been cancelled, shut it down right here
                    suspendCoroutine<Unit> { /* not picking up the continuation aborts the coroutine. */ }
                    throw Exception("This should never have been thrown")
                }

                if (state != State.RUNNING) {
                    throw IllegalStateException("Future is in state $state, cannot fold a sequence")
                }

                currentFoldingSequence = sequence
                currentFoldingCarry = initial
                currentFoldingAccumulator = accumulator as (Any, Any) -> Any
                state = State.FOLDING_SEQUENCE
            }

            return suspendCoroutine<C> { continuation = it as Continuation<Any> }
        }

        override fun finally(code: () -> Any?) {
            if (teardownLogic == null) {
                teardownLogic = LinkedList()
            }
            teardownLogic!!.addFirst(code)
        }
    }

    @Volatile
    private var continuation: Continuation<Any> = code.createCoroutine(Builder, onComplete) as Continuation<Any>

    private enum class State {
        RUNNING,
        COMPLETED,
        CANCELLED,
        WAITING_ON_FUTURE,
        FOLDING_SEQUENCE
    }
}