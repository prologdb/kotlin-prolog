package com.github.prologdb.runtime

import com.github.prologdb.runtime.module.Module
import com.github.prologdb.runtime.term.CompoundTerm

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

data class PrologStackTraceElement @JvmOverloads constructor(
    val goal: CompoundTerm,
    val sourceInformation: PrologSourceInformation,
    val module: Module? = null,
    val toStringOverride: String? = null
){
    override fun toString() = toStringOverride ?: run {
        val modulePrefix = if (module == null) "" else "module ${module.name}, "
        "$goal   $modulePrefix${sourceInformation.sourceFileName}:${sourceInformation.sourceFileLine}"
    }
}

/**
 * Runs the code; if it throws a [PrologException], amends the [PrologException.stackTrace] with
 * the given [PrologStackTraceElement].
 */
inline fun <T> prologTry(crossinline onErrorStackTraceElement: () -> PrologStackTraceElement, code: () -> T): T {
    try {
        return code()
    }
    catch (ex: PrologException) {
        ex.addPrologStackFrame(onErrorStackTraceElement())
        throw ex
    }
}
