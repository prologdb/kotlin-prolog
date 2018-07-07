package com.github.prologdb.runtime.term

import com.github.prologdb.runtime.RandomVariableScope
import com.github.prologdb.runtime.unification.Unification
import com.github.prologdb.runtime.unification.VariableDiscrepancyException

open class PrologDictionary(givenPairs: Map<Atom, Term>, givenTail: Term? = null) : Term {

    val tail: Variable?

    open val pairs: Map<Atom, Term>

    init {
        if (givenTail !is Variable? && givenTail !is PrologDictionary?) {
            throw IllegalArgumentException("The tail must be a dict, variable or absent")
        }

        if (givenTail is PrologDictionary) {
            val combinedPairs = givenPairs as? MutableMap ?: givenPairs.toMutableMap()
            var pivot: Variable? = givenTail as Variable?
            while (pivot is PrologDictionary) {
                pivot.pairs.forEach { combinedPairs[it.key] = it.value }
                pivot = pivot.tail
            }

            pairs = combinedPairs
            tail = pivot
        }
        else {
            pairs = givenPairs
            tail = givenTail as Variable?
        }
    }

    override fun unify(rhs: Term, randomVarsScope: RandomVariableScope): Unification? {
        if (rhs is Variable) return rhs.unify(this)

        if (rhs !is PrologDictionary) return Unification.FALSE

        val carryUnification = Unification()
        val commonKeys = this.pairs.keys.intersect(rhs.pairs.keys)

        if (this.pairs.size > commonKeys.size) {
            // LHS has more pairs than are common; those will have to go into RHSs tail
            if (rhs.tail == null) {
                // impossible => no unification
                return Unification.FALSE
            }
            else {
                val subDict = PrologDictionary(pairs.filterKeys { it !in commonKeys })
                try {
                    carryUnification.variableValues.incorporate(
                        rhs.tail.unify(subDict).variableValues
                    )
                }
                catch (ex: VariableDiscrepancyException) {
                    return Unification.FALSE
                }
            }
        }

        if (rhs.pairs.size > commonKeys.size) {
            // RHS has more pairs than are common; those will have to go into this' tail
            if (this.tail == null) {
                // impossible => no unification
                return Unification.FALSE
            }
            else {
                val subDict = PrologDictionary(rhs.pairs.filterKeys { it !in commonKeys })
                try {
                    carryUnification.variableValues.incorporate(
                        this.tail.unify(subDict).variableValues
                    )
                }
                catch (ex: VariableDiscrepancyException) {
                    return Unification.FALSE
                }
            }
        }

        for (commonKey in commonKeys) {
            val thisValue = this.pairs[commonKey]!!
            val rhsValue = rhs.pairs[commonKey]!!
            val keyUnification = thisValue.unify(rhsValue)

            if (keyUnification == null) {
                // common key did not unify => we're done
                return Unification.FALSE
            }

            try
            {
                carryUnification.variableValues.incorporate(keyUnification.variableValues)
            } catch (ex: VariableDiscrepancyException) {
                return Unification.FALSE
            }
        }

        return carryUnification
    }

    override val variables: Set<Variable> = {
            var variables = pairs.values.flatMap { it.variables }
            val tail = this.tail // invoke override getter only once
            if (tail != null) {
                if (variables !is MutableList) variables = variables.toMutableList()
                variables.add(tail)
            }

            variables.toSet()
        }()

    override fun substituteVariables(mapper: (Variable) -> Term): Term
        = PrologDictionary(pairs.mapValues { it.value.substituteVariables(mapper) }, tail?.substituteVariables(mapper))

    override val prologTypeName = "dict"

    override fun compareTo(other: Term): Int {
        if (other is Variable || other is PrologNumber || other is PrologString || other is Atom || other is PrologList) {
            // these are by category lesser than compound terms
            return 1
        }

        // dicts are not compared by content, neither against predicates nor against other dicts
        return 0
    }

    override fun toString(): String {
        var str = pairs.entries
            .joinToString(
                separator = ", ",
                transform = { it.key.toString() + ": " + it.value.toString() }
            )

        if (tail != null) {
            str += "|$tail"
        }

        return "{$str}"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PrologDictionary) return false

        if (tail != other.tail) return false
        if (pairs != other.pairs) return false

        return true
    }

    override fun hashCode(): Int {
        var result = tail?.hashCode() ?: 0
        result = 31 * result + pairs.hashCode()
        return result
    }
}