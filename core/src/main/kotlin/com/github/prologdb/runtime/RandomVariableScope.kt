package com.github.prologdb.runtime

import com.github.prologdb.runtime.term.CompoundTerm
import com.github.prologdb.runtime.term.RandomVariable
import com.github.prologdb.runtime.term.Term
import com.github.prologdb.runtime.term.Variable
import java.lang.ref.WeakReference
import kotlin.math.max
import kotlin.math.min

/**
 * Handles the remapping of user-specified variables to random variables (that avoids name collisions).
 */
class RandomVariableScope {
    /**
     * This is essentially the most simple PRNG possible: this int will just be incremented until it hits Long.MAX_VALUE
     * and will then throw an exception. That number is then used to generate variable names.
     */
    private var counter: Long = 0

    /**
     * Replaces all the variables in the given term with random instances; the mapping gets stored
     * in the given map.
     */
    fun withRandomVariables(term: Term, mapping: VariableMapping): Term {
        return term.substituteVariables { originalVariable ->
            if (originalVariable == Variable.ANONYMOUS) {
                createNewRandomVariable()
            }
            else {
                if (!mapping.hasOriginal(originalVariable)) {
                    val randomVariable = createNewRandomVariable()
                    mapping.storeSubstitution(originalVariable, randomVariable)
                }

                mapping.getSubstitution(originalVariable)!!
            }
        }
    }

    fun withRandomVariables(term: CompoundTerm, mapping: VariableMapping) = withRandomVariables(term as Term, mapping) as CompoundTerm

    fun createNewRandomVariable(): Variable {
        val localCounter = ++counter
        if (localCounter == Long.MAX_VALUE) {
            throw PrologInternalError("Out of random variables (reached $localCounter random variables in one proof search).")
        }

        if (localCounter >= Integer.MAX_VALUE.toLong()) {
            return RandomVariable(localCounter)
        }

        val localCounterInt = localCounter.toInt()

        val localPool = pool
        if (localCounterInt < localPool.size) {
            var variable = localPool[localCounterInt]?.get()
            if (variable == null) {
                variable = RandomVariable(localCounter)
                localPool[localCounterInt] = WeakReference(variable)
            }
            return variable
        }

        val newPool = localPool.copyOf(
            newSize = min(
                max(
                    localCounter + 1L, localPool.size.toLong() * 2L
                ),
                Integer.MAX_VALUE.toLong()
            ).toInt()
        )
        val variable = RandomVariable(localCounter)
        newPool[localCounterInt] = WeakReference(variable)
        pool = newPool

        return variable
    }

    companion object {
        /**
         * Shared cache for random variables. The index corresponds to [RandomVariableScope.counter]
         */
        @JvmStatic
        @Volatile
        private var pool: Array<WeakReference<RandomVariable>?> = Array(100) { counter -> WeakReference(RandomVariable(counter.toLong())) }
    }
}
