package com.github.prologdb.runtime.builtin.dynamic

import com.github.prologdb.runtime.HasPrologSource
import com.github.prologdb.runtime.NullSourceInformation
import com.github.prologdb.runtime.PrologRuntimeException
import com.github.prologdb.runtime.builtin.nativeRule
import com.github.prologdb.runtime.query.PredicateInvocationQuery
import com.github.prologdb.runtime.term.Atom
import com.github.prologdb.runtime.term.CompoundTerm
import com.github.prologdb.runtime.term.PrologList
import com.github.prologdb.runtime.unification.VariableBucket

val BuiltinApply2 = nativeRule("apply", 2) { args, ctxt ->
    val arguments = args[1] as? PrologList
        ?: throw PrologRuntimeException("Argument 2 to apply/2 must be a list, got ${args[1].prologTypeName}")
    if (arguments.tail != null) {
        throw PrologRuntimeException("Argument 2 to apply/2 must not have a tail.")
    }

    val targetInput = args[0]
    val targetInputAsLambda = targetInput.tryCastToLambda()

    if (targetInputAsLambda != null) {
        targetInputAsLambda.fulfill(this, arguments.elements.toTypedArray(), ctxt)
    } else {
        val sourceInformation = (targetInput as? HasPrologSource)?.sourceInformation ?: NullSourceInformation

        when (targetInput) {
            is CompoundTerm -> {
                val actualGoal = CompoundTerm(targetInput.functor, (targetInput.arguments.asList() + arguments.elements).toTypedArray())
                ctxt.fulfillAttach(this, PredicateInvocationQuery(actualGoal, sourceInformation), VariableBucket())
            }
            is Atom -> {
                val actualGoal = CompoundTerm(targetInput.name, arguments.elements.toTypedArray())
                ctxt.fulfillAttach(this, PredicateInvocationQuery(actualGoal, sourceInformation), VariableBucket())
            }
            else -> throw PrologRuntimeException("Argument 1 to apply/2 must be a compound term or an atom, got ${targetInput.prologTypeName}")
        }
    }
}
