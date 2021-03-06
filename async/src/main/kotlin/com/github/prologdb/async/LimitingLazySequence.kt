package com.github.prologdb.async

class LimitingLazySequence<T>(
    private val nested: LazySequence<T>,
    private val limit: Long
) : LazySequence<T> {
    init {
        if (limit < 0) {
            throw IllegalArgumentException()
        }
    }

    var counter = 0L
    var closed = false
    var closedState: LazySequence.State = LazySequence.State.DEPLETED

    override val principal = nested.principal

    override fun step() = if (closed) closedState else nested.step()

    override val state
        get() = if (closed) closedState else nested.state

    override fun tryAdvance(): T? {
        val el = try {
            nested.tryAdvance()
        }
        catch (ex: Throwable) {
            closedState = nested.state
            closed = true
            throw ex
        }

        if (el == null) {
            closed = true
            closedState = LazySequence.State.DEPLETED
            return null
        }

        counter++
        if (counter >= limit) {
            closed = true
            closedState = LazySequence.State.DEPLETED
            nested.close()
        }

        return el
    }

    override fun close() {
        closed = true
        closedState = LazySequence.State.DEPLETED
        nested.close()
    }
}