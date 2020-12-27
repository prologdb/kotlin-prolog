package com.github.prologdb.runtime

import com.github.prologdb.runtime.exception.PrologStackTraceElement
import com.github.prologdb.runtime.term.Term

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
abstract class PrologRuntimeException(message: String, cause: Throwable? = null) : PrologException(message, cause)

open class PrologInternalError(message: String, cause: Throwable? = null) : PrologRuntimeException(message, cause)

open class PredicateNotDynamicException(val indicator: FullyQualifiedClauseIndicator, cause: Throwable? = null) : PrologException("Predicate $indicator is not dynamic", cause)

open class PrologPermissionError(message: String, cause: Throwable? = null) : PrologException(message, cause)

open class ArgumentTypeError(
    val predicate: FullyQualifiedClauseIndicator,
    val argumentIndex: Int,
    val actualType: String,
    val expected: Array<out String>,
    message: String
) : PrologException(message) {
    constructor(
        predicate: FullyQualifiedClauseIndicator,
        argumentIndex: Int,
        actual: Term,
        vararg expected: String
    ) : this(
        predicate,
        argumentIndex,
        actual.prologTypeName,
        expected,
        "Type error: argument ${argumentIndex + 1} to $predicate must be typeof " +
            (if (expected.size > 1) "either " else "") + expected.joinToString(", ") + ", got ${actual.prologTypeName}"
    )

    init {
        require(argumentIndex >= 0)
        requireNotNull(expected.isNotEmpty())
    }
}

open class ArgumentNotInstantiatedError(
    predicate: FullyQualifiedClauseIndicator,
    argumentIndex: Int,
    vararg expected: String
) : ArgumentTypeError(
    predicate,
    argumentIndex,
    "variable",
    expected,
    "Type error: argument ${argumentIndex + 1} to $predicate is not sufficiently instantiated. Expected " +
        (if (expected.size > 1) "either of " else "") + expected.joinToString()
)
