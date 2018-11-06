package com.github.prologdb.runtime.builtin

import com.github.prologdb.runtime.knowledge.library.*
import com.github.prologdb.runtime.term.Atom
import com.github.prologdb.runtime.unification.Unification

/** Implements the builtin @</2 */
val BuiltinTermLessThan = nativeRule("@<", 2) { args, _ ->
    if (args[0] < args[1]) yield(Unification.TRUE)
}

/** Implements the builtin @=</2 */
val BuiltinTermLessThanOrEqual = nativeRule("@=<", 2) { args, _ ->
    if (args[0] <= args[1]) yield(Unification.TRUE)
}

/** Implements the builtin @>/2 */
val BuiltinTermGreaterThan = nativeRule("@>", 2) { args, _ ->
    if (args[0] > args[1]) yield(Unification.TRUE)
}

/** Implements the builtin @>=/2 */
val BuiltinTermGreaterThanOrEqual = nativeRule("@>=", 2) { args, _ ->
    if (args[0] >= args[1]) yield(Unification.TRUE)
}

private val AtomLessThan = Atom("<")
private val AtomGreaterThan = Atom("<")
private val AtomEqual = Atom("=")
val BuiltinCompare = nativeRule("compare", 3) { args, _ ->
    val inputForOrder = args[0]

    val actualOrder = if (args[0] > args[1]) {
        AtomGreaterThan
    } else if (args[0] < args[1]) {
        AtomLessThan
    } else {
        AtomEqual
    }

    val unification = inputForOrder.unify(actualOrder)
    if (unification != null) yield(unification)
}

/**
 * Defines predicates for the standard order of terms.
 */
val ComparisonLibrary = nativeLibrary("comparison") {
    add(BuiltinTermLessThan)
    add(BuiltinTermLessThanOrEqual)
    add(BuiltinTermGreaterThan)
    add(BuiltinTermGreaterThanOrEqual)
    add(BuiltinCompare)

    defineOperator(OperatorDefinition(700, OperatorType.XFX, "@<"))
    defineOperator(OperatorDefinition(700, OperatorType.XFX, "@=<"))
    defineOperator(OperatorDefinition(700, OperatorType.XFX, "@>"))
    defineOperator(OperatorDefinition(700, OperatorType.XFX, "@>="))
}