package com.github.prologdb.runtime.builtin.dynamic

import com.github.prologdb.runtime.builtin.getInvocationStackFrame
import com.github.prologdb.runtime.builtin.nativeModule
import com.github.prologdb.runtime.builtin.prologSourceInformation
import com.github.prologdb.runtime.proofsearch.Rule
import com.github.prologdb.runtime.query.AndQuery
import com.github.prologdb.runtime.query.OrQuery
import com.github.prologdb.runtime.query.PredicateInvocationQuery
import com.github.prologdb.runtime.query.Query
import com.github.prologdb.runtime.term.CompoundTerm
import com.github.prologdb.runtime.term.Term

val DynamicsModule = nativeModule("dynamics") {
    add(BuiltinFindAll)
    add(BuiltinFindAllOptimized)
    add(BuiltinFindNSols4)
    add(BuiltinCompilePredicates)
    add(BuiltinCall1)
    add(BuiltinCompoundNameArguments3)
    add(BuiltinApply2)
    add(BuiltinTermVariables2)
    add(BuiltinDistinct1)
    add(BuiltinDistinct2)
}

/**
 * Converts compound terms (instances of `,/2` and `;/2` to
 * queries).
 */
fun compoundToQuery(compoundTerm: CompoundTerm): Query {
    val sourceInformation = compoundTerm.sourceInformation.orElse { getInvocationStackFrame().prologSourceInformation }

    if (compoundTerm.arity != 2) {
        return PredicateInvocationQuery(compoundTerm, sourceInformation)
    }

    if (compoundTerm.functor == "," || compoundTerm.functor == ";") {
        val allArgumentsCompound = compoundTerm.arguments.all { it is CompoundTerm }
        if (!allArgumentsCompound) {
            return PredicateInvocationQuery(compoundTerm, sourceInformation)
        }

        val argumentsConverted = compoundTerm.arguments.map { compoundToQuery(it as CompoundTerm) }.toTypedArray()
        return when (compoundTerm.functor) {
            "," -> AndQuery(argumentsConverted)
            ";" -> OrQuery(argumentsConverted)
            else -> throw IllegalStateException()
        }
    }
    // else:
    return PredicateInvocationQuery(compoundTerm, sourceInformation)
}

fun Term.tryCastToLambda(): Rule? {
    if (this !is CompoundTerm || this.functor != ":-" || this.arity != 2) {
        return null
    }

    val argumentList = this.arguments[0].commaCompoundToList().toTypedArray()
    val query = (this.arguments[1] as? CompoundTerm ?: return null)?.let {
        try {
            compoundToQuery(it)
        } catch (ex: IllegalArgumentException) {
            return null
        }
    }

    return Rule(CompoundTerm("_lambda", argumentList), query)
}

private fun Term.commaCompoundToList(): List<Term> {
    val list = mutableListOf<Term>()
    var pivot = this
    while (pivot is CompoundTerm && pivot.arity == 2 && pivot.functor == ",") {
        list.add(pivot.arguments[0])
        pivot = pivot.arguments[1]
    }

    list.add(pivot)

    return list
}
