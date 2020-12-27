package com.github.prologdb.runtime.exception

import com.github.prologdb.runtime.PrologException

/**
 * Runs the code; if it throws a [PrologException], amends the [PrologException.stackTrace] with
 * the given [PrologStackTraceElement].
 */
inline fun <T> prologTry(crossinline onErrorStackTraceElement: () -> PrologStackTraceElement, code: () -> T): T {
    try {
        return code()
    } catch (ex: PrologException) {
        ex.addPrologStackFrame(onErrorStackTraceElement())
        throw ex
    }
}
