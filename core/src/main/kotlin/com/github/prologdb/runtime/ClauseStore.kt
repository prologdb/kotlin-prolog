package com.github.prologdb.runtime

import com.github.prologdb.async.LazySequence
import com.github.prologdb.runtime.proofsearch.Rule
import com.github.prologdb.runtime.term.CompoundTerm
import com.github.prologdb.runtime.term.Term
import com.github.prologdb.runtime.term.Variable
import com.github.prologdb.runtime.unification.Unification
import com.github.prologdb.runtime.util.ArityMap

interface ClauseStore {
    /**
     * All clauses defined in this store. Used mainly for `listing/0`.
     */
    val clauses: Iterable<Clause>

    /**
     * Finds entries within [clauses] that possibly unify with the given [CompoundTerm] (facts or rule heads). This
     * method may just return [clauses] but may also implement sophisticated indexing or involve persistent storage.
     *
     * The default implementation of this method uses the kotlin stdlib [filter] method.
     */
    fun findFor(goal: CompoundTerm): Iterable<Clause> = clauses.filter { it.arity == goal.arity && it.functor == goal.functor }
}

interface MutableClauseStore : ClauseStore {
    /**
     * Adds the given entry to the library; acts like `assertz/1`: adds the entry to the **end** of the knowledge base.
     */
    fun assertz(entry: Clause)

    /**
     * Removes facts that unify with the given [CompoundTerm] from the entry store.
     *
     * *Does not affect rules.*.
     *
     * @return Unifications of the given [CompoundTerm] with the removed facts. Every invocation of [LazySequence.tryAdvance]
     *         attempts to remove another entry from the store.
     */
    fun retractFact(fact: CompoundTerm): LazySequence<Unification>

    /**
     * Removes facts (which unify with the given [CompoundTerm]) **or rules (whichs heads unify with
     * the given compound term)** from this store. Acts like `retract/1`.
     *
     * @return The unifications of the given [CompoundTerm] with the removed facts resp. rule heads. Every invocation
     *         of [LazySequence.tryAdvance] attempts to remove another entry from the store.
     */
    fun retract(unifiesWith: CompoundTerm): LazySequence<Unification>

    /**
     * Removes all facts that unify with the given [CompoundTerm] from the entry store. **Does not affect rules**.
     */
    fun retractAllFacts(fact: CompoundTerm) {
        retractFact(fact).consumeAll()
    }

    /**
     * Removes all facts (which unify with the given [CompoundTerm]) **and rules (whichs heads unify with the given
     * [CompoundTerm])** from this store. Acts like `retractAll/1`.
     */
    fun retractAll(unifiesWith: CompoundTerm) {
        retract(unifiesWith).consumeAll()
    }

    /**
     * Removes all facts with the given functor and arity. **Does not affect rules.**
     */
    fun abolishFacts(functor: String, arity: Int) {
        retractAllFacts(CompoundTerm(functor, Array<Term>(arity, { Variable.ANONYMOUS })))
    }

    /**
     * Removes all facts **and rules** with the given functor and arity. Acts like `abolish/1` and `abolish/2`.
     */
    fun abolish(functor: String, arity: Int) {
        retractAll(CompoundTerm(functor, Array<Term>(arity, { Variable.ANONYMOUS })))
    }
}

private typealias ArityMapToLibraryEntries = ArityMap<MutableList<Clause>>

/**
 * The most simple implementation of [MutableClauseStore] possible: is
 * based on a plain [MutableList] and uses the default implementations
 * declared in [ClauseStore] and [MutableClauseStore]
 */
class SimpleClauseStore(givenEntries: Iterable<Clause> = emptyList()) : MutableClauseStore {
    private val entries = ArrayList(givenEntries.toList())

    override val clauses = entries

    override fun assertz(entry: Clause) {
        entries.add(entry)
    }

    override fun retractFact(fact: CompoundTerm): LazySequence<Unification> {
        return LazySequence.fromGenerator {
            for (index in 0 until entries.size) {
                val entry = entries[index]
                if (entry is CompoundTerm) {
                    val unification = entry.unify(fact, RandomVariableScope())
                    if (unification != null) {
                        entries.removeAt(index)
                        return@fromGenerator unification
                    }
                }
            }

            null
        }
    }

    override fun retract(unifiesWith: CompoundTerm): LazySequence<Unification> {
        return LazySequence.fromGenerator {
            for (index in 0 until entries.size) {
                val entry = entries[index]
                if (entry is CompoundTerm) {
                    val unification = entry.unify(unifiesWith, RandomVariableScope())
                    if (unification != null) {
                        entries.removeAt(index)
                        return@fromGenerator unification
                    }
                } else if (entry is Rule) {
                    val headUnification = entry.head.unify(unifiesWith, RandomVariableScope())
                    if (headUnification != null) {
                        entries.removeAt(index)
                        return@fromGenerator headUnification
                    }
                } else {
                    throw PrologInternalError("Cannot test whether to retract an entry: is neither a fact nor a rule")
                }
            }

            null
        }
    }

    override fun abolishFacts(functor: String, arity: Int) {
        entries.removeAll(entries.filter { it.arity == arity && it is CompoundTerm && it.functor == functor  })
    }

    override fun abolish(functor: String, arity: Int) {
        entries.removeAll(entries.filter { it.arity == arity && it.functor == functor })
    }
}

/**
 * Indexes entries by arity and functor in maps.
 */
class DoublyIndexedClauseStore : MutableClauseStore {
    /**
     * The index. The key in the outer map corresponds to [Clause.functor] and the
     * key of the inner map corresponds to [Clause.arity].
     */
    private val index = mutableMapOf<String, ArityMapToLibraryEntries>()

    override fun findFor(goal: CompoundTerm): Iterable<Clause> {
        val arityMap = index[goal.functor] ?: return emptyList()
        return arityMap[goal.arity] ?: emptyList()
    }

    override fun assertz(entry: Clause) {
        val arityIndex: ArityMapToLibraryEntries

        if (entry.functor !in index) {
            arityIndex = ArityMap()
            index[entry.functor] = arityIndex
        } else {
            arityIndex = index[entry.functor]!!
        }

        val entryList: MutableList<Clause>

        if (entry.arity !in arityIndex) {
            entryList = mutableListOf()
            arityIndex[entry.arity] = entryList
        } else {
            entryList = arityIndex[entry.arity]!!
        }

        entryList.add(entry)
        indexChangedSinceLastExportsCalculation = true
    }

    private var indexChangedSinceLastExportsCalculation = true
    private var cachedExports: Iterable<Clause>? = null
    override val clauses: Iterable<Clause>
        get() {
            if (indexChangedSinceLastExportsCalculation) {
                cachedExports = index.flatMap { it.value.arities.flatMap { arity -> it.value[arity]!! }}
                indexChangedSinceLastExportsCalculation = false
            }

            return cachedExports!!
        }

    override fun retractFact(fact: CompoundTerm): LazySequence<Unification> {
        val arityIndex = index[fact.functor] ?: return Unification.NONE
        val entryList = arityIndex[fact.arity] ?: return Unification.NONE

        return LazySequence.fromGenerator {
            for (index in 0 until entryList.size) {
                val entry = entryList[index]
                if (entry is CompoundTerm) {
                    val unification = entry.unify(fact, RandomVariableScope())
                    if (unification != null) {
                        entryList.removeAt(index)
                        return@fromGenerator unification
                    }
                }
            }

            null
        }
    }

    override fun retract(unifiesWith: CompoundTerm): LazySequence<Unification> {
        val arityIndex = index[unifiesWith.functor] ?: return Unification.NONE
        val entryList = arityIndex[unifiesWith.arity] ?: return Unification.NONE

        return LazySequence.fromGenerator {
            for (index in 0 until entryList.size) {
                val entry = entryList[index]
                if (entry is CompoundTerm) {
                    val unification = entry.unify(unifiesWith, RandomVariableScope())
                    if (unification != null) {
                        entryList.removeAt(index)
                        return@fromGenerator unification
                    }
                } else if (entry is Rule) {
                    val headUnification = entry.head.unify(unifiesWith, RandomVariableScope())
                    if (headUnification != null) {
                        entryList.removeAt(index)
                        return@fromGenerator headUnification
                    }
                } else {
                    throw PrologInternalError("Cannot determine whether entry should be retracted: is neither a fact nor a rule.")
                }
            }

            null
        }
    }

    override fun abolish(functor: String, arity: Int) {
        index[functor]?.remove(arity)
    }

    override fun abolishFacts(functor: String, arity: Int) {
        val arityMap = index[functor] ?: return
        val entryList = arityMap[arity] ?: return

        entryList.removeAll(entryList.filter { it is CompoundTerm })
    }
}
