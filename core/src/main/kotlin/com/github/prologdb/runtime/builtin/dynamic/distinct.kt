package com.github.prologdb.runtime.builtin.dynamic

import com.github.prologdb.async.buildLazySequence
import com.github.prologdb.async.filterRemaining
import com.github.prologdb.runtime.PrologRuntimeException
import com.github.prologdb.runtime.builtin.V
import com.github.prologdb.runtime.builtin.invoke
import com.github.prologdb.runtime.builtin.nativeRule
import com.github.prologdb.runtime.term.CompoundTerm
import com.github.prologdb.runtime.term.Term
import com.github.prologdb.runtime.unification.Unification
import com.github.prologdb.runtime.unification.VariableBucket

internal val BuiltinDistinct1 = "distinct"(V("Goal")).invoke {
    compoundToQuery("distinct"(V("Goal"), V("Goal")))
}

internal val BuiltinDistinct2 = nativeRule("distinct", 2) { args, ctxt ->
    val witness = args[0]
    val goal = args[1]

    if (witness.variables.isEmpty()) {
        throw PrologRuntimeException("Argument 1 to distinct/2 must be nonground")
    }
    goal as? CompoundTerm ?: throw PrologRuntimeException("Argument 2 to distinct/2 must be a compound term, got " + args[1].prologTypeName)
    val query = compoundToQuery(goal)

    val seenSolutions = mutableSetOf<Term>()
    yieldAll(buildLazySequence<Unification>(ctxt.principal) {
        ctxt.fulfillAttach(this, query, VariableBucket())
    }.filterRemaining { solution ->
        val instantiated = witness.substituteVariables(solution.variableValues.asSubstitutionMapper())
        return@filterRemaining seenSolutions.add(instantiated)
    })
}