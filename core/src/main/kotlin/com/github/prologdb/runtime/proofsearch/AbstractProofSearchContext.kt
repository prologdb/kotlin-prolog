package com.github.prologdb.runtime.proofsearch

import com.github.prologdb.async.LazySequence
import com.github.prologdb.async.LazySequenceBuilder
import com.github.prologdb.async.buildLazySequence
import com.github.prologdb.async.flatMapRemaining
import com.github.prologdb.async.mapRemainingNotNull
import com.github.prologdb.runtime.exception.PrologStackTraceElement
import com.github.prologdb.runtime.exception.prologTry
import com.github.prologdb.runtime.query.AndQuery
import com.github.prologdb.runtime.query.OrQuery
import com.github.prologdb.runtime.query.PredicateInvocationQuery
import com.github.prologdb.runtime.unification.Unification
import com.github.prologdb.runtime.unification.VariableBucket

/**
 * Defines boilerplate code for correctly handling [AndQuery]s and [OrQuery]s as well checking read access
 * for every predicate invocation.
 */
abstract class AbstractProofSearchContext : ProofSearchContext {
    override val fulfillAttach: ProofSearch = { q, variables ->
        when (q) {
            is AndQuery -> fulfillAndQuery(q, variables)
            is OrQuery -> fulfillOrQuery(q, variables)
            is PredicateInvocationQuery -> invokePredicate(q, variables)
        }
    }

    protected suspend fun LazySequenceBuilder<Unification>.fulfillAndQuery(query: AndQuery, initialVariables: VariableBucket): Unification? {
        if (query.goals.isEmpty()) {
            return Unification(initialVariables)
        }
        if (query.goals.size == 1) {
            return fulfillAttach(query.goals.first(), initialVariables)
        }

        var sequence = LazySequence.singleton(Unification(initialVariables.copy()))

        for (goalIndex in query.goals.indices) {
            sequence = sequence.flatMapRemaining { stateBefore ->
                val goalSequence = buildLazySequence<Unification>(principal) {
                    fulfillAttach(
                        query.goals[goalIndex].substituteVariables(stateBefore.variableValues),
                        stateBefore.variableValues.copy()
                    )
                }
                return@flatMapRemaining yieldAllFinal(goalSequence.mapRemainingNotNull { goalUnification ->
                    val stateCombined = stateBefore.variableValues.copy()
                    for ((variable, value) in goalUnification.variableValues.values) {
                        if (value != null) {
                            // substitute all instantiated variables for simplicity and performance
                            val substitutedValue = value.substituteVariables(stateCombined.asSubstitutionMapper())
                            if (stateCombined.isInstantiated(variable)) {
                                if (stateCombined[variable] != substitutedValue && stateCombined[variable] != value) {
                                    // instantiated to different value => no unification
                                    return@mapRemainingNotNull null
                                }
                            }
                            else {
                                stateCombined.instantiate(variable, substitutedValue)
                            }
                        }
                    }
                    Unification(stateCombined)
                })
            }
        }

        return yieldAllFinal(sequence)
    }

    protected suspend inline fun LazySequenceBuilder<Unification>.fulfillOrQuery(query: OrQuery, initialVariables: VariableBucket): Unification? {
        if (query.goals.size == 1) {
            return fulfillAttach(this, query.goals[0], initialVariables)
        }

        for (goalIndex in 0..query.goals.size - 2) {
            fulfillAttach(query.goals[goalIndex], initialVariables)?.let { yield(it) }
        }

        return fulfillAttach(query.goals.last(), initialVariables)
    }

    private suspend fun LazySequenceBuilder<Unification>.invokePredicate(query: PredicateInvocationQuery, variables: VariableBucket): Unification? {
        return prologTry({ getStackTraceElementOf(query) }) {
            doInvokePredicate(query, variables)
        }
    }

    protected abstract suspend fun LazySequenceBuilder<Unification>.doInvokePredicate(query: PredicateInvocationQuery, variables: VariableBucket): Unification?

    protected open fun getStackTraceElementOf(query: PredicateInvocationQuery): PrologStackTraceElement = PrologStackTraceElement(
        query.goal,
        query.goal.sourceInformation.orElse(query.sourceInformation)
    )
}
