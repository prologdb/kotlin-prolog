package com.github.prologdb.runtime.module

import com.github.prologdb.async.LazySequenceBuilder
import com.github.prologdb.async.Principal
import com.github.prologdb.runtime.ClauseIndicator
import com.github.prologdb.runtime.FullyQualifiedClauseIndicator
import com.github.prologdb.runtime.PrologPermissionError
import com.github.prologdb.runtime.PrologRuntimeException
import com.github.prologdb.runtime.RandomVariableScope
import com.github.prologdb.runtime.exception.PrologStackTraceElement
import com.github.prologdb.runtime.proofsearch.AbstractProofSearchContext
import com.github.prologdb.runtime.proofsearch.Authorization
import com.github.prologdb.runtime.proofsearch.PrologCallable
import com.github.prologdb.runtime.proofsearch.ProofSearchContext
import com.github.prologdb.runtime.query.PredicateInvocationQuery
import com.github.prologdb.runtime.unification.Unification
import com.github.prologdb.runtime.unification.VariableBucket

/**
 * All code declared inside a module only has access to predicates declared in the same module and
 * imported into that module explicitly **but not** to predicates visible in the scope where the
 * module is being imported into. This [ProofSearchContext] ensures that isolation: running code of
 * module within the proper [ModuleScopeProofSearchContext] achieves that behaviour.
 */
class ModuleScopeProofSearchContext(
    internal val module: Module,
    /**
     * Predicates declared in [module], including private ones.
     */
    private val modulePredicates: Map<ClauseIndicator, PrologCallable>,

    override val principal: Principal,
    override val randomVariableScope: RandomVariableScope,
    override val authorization: Authorization,
    override val rootAvailableModules: Map<String, Module>
) : ProofSearchContext, AbstractProofSearchContext() {

    override val operators = module.localOperators

    override suspend fun LazySequenceBuilder<Unification>.doInvokePredicate(query: PredicateInvocationQuery, variables: VariableBucket): Unification? {
        val simpleIndicator = ClauseIndicator.of(query.goal)
        val (fqIndicator, callable) = resolveCallable(simpleIndicator)
            ?: throw PrologRuntimeException("Predicate $simpleIndicator not defined in context of module ${module.name}")

        if (!authorization.mayRead(fqIndicator)) throw PrologPermissionError("Not allowed to read/invoke $fqIndicator")

        return callable.fulfill(this, query.goal.arguments, this@ModuleScopeProofSearchContext)
    }

    override fun getStackTraceElementOf(query: PredicateInvocationQuery) = PrologStackTraceElement(
        query.goal,
        query.goal.sourceInformation.orElse(query.sourceInformation),
        module
    )

    private val importLookupCache: Map<ClauseIndicator, Pair<ModuleReference, PrologCallable>> = mutableMapOf<ClauseIndicator, Pair<ModuleReference, PrologCallable>>().also { importLookupCache ->
        module.imports.asSequence()
            .forEach { import ->
                val referencedModule = rootAvailableModules[import.moduleReference.moduleName]
                    ?: throw PrologRuntimeException("Imported module ${import.moduleReference} not loaded in proof search context")

                val visiblePredicates: Map<ClauseIndicator, Pair<ModuleReference, PrologCallable>> = when (import) {
                    is ModuleImport.Full -> referencedModule.exportedPredicates
                        .mapValues { (_, callable) ->
                            Pair(import.moduleReference, callable)
                        }
                    is ModuleImport.Selective -> import.imports
                        .map { (exportedIndicator, alias) ->
                            val callable = referencedModule.exportedPredicates[exportedIndicator]
                                ?: throw PrologRuntimeException("Predicate $exportedIndicator not exported by module ${import.moduleReference}")
                            if (exportedIndicator.functor == alias) {
                                exportedIndicator to Pair(import.moduleReference, callable)
                            } else {
                                ClauseIndicator.of(alias, exportedIndicator.arity) to Pair(import.moduleReference, callable)
                            }
                        }
                        .toMap()
                    is ModuleImport.Except -> referencedModule.exportedPredicates
                        .filterKeys { it !in import.excluded }
                        .mapValues { (_, callable) ->
                            Pair(import.moduleReference, callable)
                        }
                }

                importLookupCache.putAll(visiblePredicates)
            }
    }

    private fun findImport(indicator: ClauseIndicator): Pair<ModuleReference, PrologCallable>? = importLookupCache[indicator]

    override fun resolveCallable(simpleIndicator: ClauseIndicator): Pair<FullyQualifiedClauseIndicator, PrologCallable>? {
        // attempt modules own scope
        modulePredicates[simpleIndicator]?.let { callable ->
            val fqIndicator = FullyQualifiedClauseIndicator(module.name, simpleIndicator)
            return Pair(fqIndicator, callable)
        }

        // attempt imported predicate
        findImport(simpleIndicator)?.let { (sourceModule, callable) ->
            val fqIndicator = FullyQualifiedClauseIndicator(sourceModule.moduleName, ClauseIndicator.of(callable))

            return Pair(fqIndicator, callable)
        }

        return null
    }
}
