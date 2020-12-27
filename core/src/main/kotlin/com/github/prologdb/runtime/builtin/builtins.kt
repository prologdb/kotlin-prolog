package com.github.prologdb.runtime.builtin

import com.github.prologdb.async.Principal
import com.github.prologdb.runtime.Clause
import com.github.prologdb.runtime.ClauseIndicator
import com.github.prologdb.runtime.PrologException
import com.github.prologdb.runtime.PrologRuntimeException
import com.github.prologdb.runtime.RandomVariableScope
import com.github.prologdb.runtime.exception.PrologStackTraceElement
import com.github.prologdb.runtime.module.Module
import com.github.prologdb.runtime.module.ModuleImport
import com.github.prologdb.runtime.module.ModuleReference
import com.github.prologdb.runtime.module.ModuleScopeProofSearchContext
import com.github.prologdb.runtime.proofsearch.ASTPrologPredicate
import com.github.prologdb.runtime.proofsearch.Authorization
import com.github.prologdb.runtime.proofsearch.PrologCallable
import com.github.prologdb.runtime.proofsearch.PrologCallableFulfill
import com.github.prologdb.runtime.proofsearch.ProofSearchContext
import com.github.prologdb.runtime.proofsearch.Rule
import com.github.prologdb.runtime.query.PredicateInvocationQuery
import com.github.prologdb.runtime.query.Query
import com.github.prologdb.runtime.term.CompoundTerm
import com.github.prologdb.runtime.term.Variable

internal val A = Variable("A")
internal val B = Variable("B")
internal val C = Variable("C")
internal val X = Variable("X")

/**
 * [Variable]s to be used in the "prolog"-ish representation of builtins. E.g.
 * when defining the builtin `string_chars(A, B)`, `A` and `B` are obtained from
 * this list.
 */
private val builtinArgumentVariables = arrayOf(
    Variable("_Arg0"),
    Variable("_Arg1"),
    Variable("_Arg2"),
    Variable("_Arg3"),
    Variable("_Arg4"),
    Variable("_Arg5"),
    Variable("_Arg6"),
    Variable("_Arg7"),
    Variable("_Arg8"),
    Variable("_Arg9")
)

/**
 * This query is used as a placeholder for builtins where an instance of [Query] is required
 * but the actual query never gets invoked because kotlin code implements the builtin.
 */
private val nativeCodeQuery = PredicateInvocationQuery(CompoundTerm("__nativeCode", emptyArray()))

class NativeCodeRule(name: String, arity: Int, definedAt: StackTraceElement, code: PrologCallableFulfill) : Rule(
    CompoundTerm(name, builtinArgumentVariables.sliceArray(0 until arity)),
    nativeCodeQuery
) {
    private val invocationStackFrame = definedAt
    private val stringRepresentation = """$head :- __nativeCode("${invocationStackFrame.fileName}:${invocationStackFrame.lineNumber}")"""

    private val builtinStackFrame = PrologStackTraceElement(
        head,
        invocationStackFrame.prologSourceInformation,
        null,
        "$name/$arity native implementation (${definedAt.fileName}:${definedAt.lineNumber})"
    )

    override val fulfill: PrologCallableFulfill = { arguments, context ->
        if (head.arity == arguments.size) {
            try {
                code(this, arguments, context)
            } catch (ex: PrologException) {
                ex.addPrologStackFrame(builtinStackFrame)
                throw ex
            } catch (ex: Throwable) {
                val newEx = PrologRuntimeException(ex.message ?: "", ex)
                newEx.addPrologStackFrame(builtinStackFrame)

                throw newEx
            }
        } else null
    }

    override fun toString() = stringRepresentation
}

fun nativeRule(name: String, arity: Int, code: PrologCallableFulfill): NativeCodeRule {
    val definedAt = getInvocationStackFrame()
    return NativeCodeRule(name, arity, definedAt, code)
}

fun nativeRule(name: String, arity: Int, definedAt: StackTraceElement, code: PrologCallableFulfill): NativeCodeRule {
    return NativeCodeRule(name, arity, definedAt, code)
}

fun nativeModule(name: String, initCode: NativeModuleBuilder.() -> Any?): Module = NativeModuleBuilder.build(name, initCode)

class NativeModuleBuilder(private val moduleName: String) {
    private val clauses = mutableListOf<Clause>()
    private val nativePredicates = mutableListOf<PrologCallable>()
    private val imports = mutableListOf(
        ModuleReference("essential", "\$equality")
    )

    fun add(predicate: PrologCallable) {
        nativePredicates.add(predicate)
    }

    fun add(clauses: List<Clause>) {
        this.clauses.addAll(clauses)
    }

    fun import(pathAlias: String, moduleName: String) {
        imports.add(ModuleReference(pathAlias, moduleName))
    }

    private fun build(): Module {
        return NativeModule(
            name = moduleName,
            imports = imports.map(ModuleImport::Full),
            nativePredicates = nativePredicates,
            otherClauses = clauses
        )
    }

    companion object {
        internal fun build(name: String, initCode: NativeModuleBuilder.() -> Any?): Module {
            val builder = NativeModuleBuilder(name)
            builder.initCode()
            return builder.build()
        }
    }
}

class NativeModule(
    override val name: String,
    nativePredicates: Iterable<PrologCallable>,
    otherClauses: List<Clause>,
    override val imports: List<ModuleImport>
) : Module {
    override val localOperators = ISOOpsOperatorRegistry

    override val exportedPredicates: Map<ClauseIndicator, PrologCallable>

    init {
        val _exportedPredicates = nativePredicates
            .associateBy { ClauseIndicator.of(it) }
            .toMutableMap()

        otherClauses.asSequence()
            .groupingBy { ClauseIndicator.of(it) }
            .fold(
                { indicator, _ -> ASTPrologPredicate(indicator, this) },
                { _, astPredicate, clause ->
                    astPredicate.assertz(clause)
                    astPredicate
                }
            )
            .forEach { (indicator, predicate) ->
                require(indicator !in _exportedPredicates)

                _exportedPredicates[indicator] = predicate
            }

        exportedPredicates = _exportedPredicates
    }

    override fun deriveScopedProofSearchContext(deriveFrom: ProofSearchContext): ProofSearchContext {
        if (deriveFrom is ModuleScopeProofSearchContext && deriveFrom.module == this) {
            return deriveFrom
        }

        return super.deriveScopedProofSearchContext(deriveFrom)
    }

    override fun createProofSearchContext(principal: Principal, randomVariableScope: RandomVariableScope, authorization: Authorization, rootAvailableModules: Map<String, Module>): ProofSearchContext {
        return ModuleScopeProofSearchContext(
            this,
            exportedPredicates,
            principal,
            randomVariableScope,
            authorization,
            rootAvailableModules
        )
    }
}
