package com.github.prologdb.runtime

import com.github.prologdb.runtime.exception.PrologStackTraceElement

/**
 * An exception related to, but not limited to, parsing and interpreting prolog programs.
 */
open class PrologException(message: String, override val cause: Throwable? = null) : RuntimeException(message) {
    private val _prologStackTrace = mutableListOf<PrologStackTraceElement>()

    fun addPrologStackFrame(frameInfo: PrologStackTraceElement) {
        _prologStackTrace.add(frameInfo)
    }

    val prologStackTrace: List<PrologStackTraceElement> = _prologStackTrace
}

/**
 * Thrown when errors or warnings occur during the interpretation of a prolog program.
 */
open class PrologRuntimeException(message: String, cause: Throwable? = null) : PrologException(message, cause)

open class PredicateNotDynamicException(val indicator: FullyQualifiedClauseIndicator, cause: Throwable? = null) : PrologRuntimeException("Predicate $indicator is not dynamic", cause)

open class PrologPermissionError(message: String, cause: Throwable? = null) : PrologRuntimeException(message, cause)
