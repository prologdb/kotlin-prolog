package com.github.prologdb.nativetests

import com.github.prologdb.async.LazySequenceBuilder
import com.github.prologdb.async.buildLazySequence
import com.github.prologdb.async.forEachRemaining
import com.github.prologdb.parser.*
import com.github.prologdb.parser.lexer.Lexer
import com.github.prologdb.parser.parser.ParseResult
import com.github.prologdb.parser.parser.PrologParser
import com.github.prologdb.parser.source.SourceLocation
import com.github.prologdb.parser.source.SourceUnit
import com.github.prologdb.runtime.HasPrologSource
import com.github.prologdb.runtime.RandomVariableScope
import com.github.prologdb.runtime.VariableMapping
import com.github.prologdb.runtime.knowledge.DefaultKnowledgeBase
import com.github.prologdb.runtime.knowledge.KnowledgeBase
import com.github.prologdb.runtime.knowledge.ProofSearchContext
import com.github.prologdb.runtime.knowledge.library.Library
import com.github.prologdb.runtime.knowledge.library.OperatorDefinition
import com.github.prologdb.runtime.knowledge.library.OperatorType
import com.github.prologdb.runtime.query.AndQuery
import com.github.prologdb.runtime.query.PredicateQuery
import com.github.prologdb.runtime.query.Query
import com.github.prologdb.runtime.term.*
import com.github.prologdb.runtime.unification.Unification
import com.github.prologdb.runtime.unification.VariableBucket
import io.kotlintest.matchers.fail
import io.kotlintest.matchers.shouldEqual
import io.kotlintest.specs.FreeSpec
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import java.lang.invoke.MethodHandles
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*

/** runs the tests found in the *.test.pl files in the prolog tests directory */
class PrologTest : FreeSpec() { init {
    for (prologTestFilePath in prologTestFiles) {
        "${prologTestFilePath.fileName}" - {
            // run the test
            val callback = object : TestResultCallback {
                override fun onTestSuccess(testName: String) {
                    testName.invoke {
                        // implicit success
                        true shouldEqual true // for good measure
                    }
                }

                override fun onTestFailure(testName: String, message: String) {
                    testName.invoke {
                        fail(message)
                    }
                }

                override fun onTestError(testName: String, error: Throwable) {
                    testName.invoke {
                        throw error
                    }
                }

                override fun onTestParseError(errors: Collection<Reporting>) {
                    errors.forEach(::println)

                    throw RuntimeException("Failed to parse test case file, see STDOUT for errors.")
                }
            }

            runPrologTestFile(prologTestFilePath, callback)
        }
    }
}
    private fun runPrologTestFile(path: Path, callback: TestResultCallback) {
        val parseResult = parseFile(path)

        if (!parseResult.isSuccess || parseResult.reportings.isNotEmpty()) {
            callback.onTestParseError(parseResult.reportings)
            return
        }

        val library = parseResult.item ?: throw RuntimeException("Invalid return value from parser: is success but item is null")
        val testCases = getPrologTestCases(library, callback::onTestParseError)

        testCases.forEach { it.runWith(callback) }
    }

    private fun parseFile(path: Path): ParseResult<Library> {
        val fileContent = String(Files.readAllBytes(path), Charset.forName("UTF-8"))
        val sourceUnit = SourceUnit(path.toString())
        val lexer = Lexer(fileContent.iterator(), SourceLocation(sourceUnit, 1, 0, 0))

        return PrologParser().parseLibrary(path.fileName.toString(), lexer, createFreshTestingKnowledgeBase().operators)
    }

    private fun getPrologTestCases(library: Library, parseErrorCallback: (Collection<Reporting>) -> Any?): Set<PrologTestCase> {
        val by2Results = library.findFor(Predicate("by", arrayOf(AnonymousVariable, AnonymousVariable)))
        val testCases = mutableSetOf<PrologTestCase>()
        for (by2instance in by2Results) {
            if (by2instance !is Predicate) continue
            val arg0 = by2instance.arguments[0]
            val arg1 = by2instance.arguments[1]
            if (arg0 !is Predicate) continue
            if (arg1 !is PrologList) continue

            if (arg0.name != "test") continue
            if (arg0.arity != 1) continue
            if (arg0.arguments[0] !is PrologString) continue

            val testName = (arg0.arguments[0] as PrologString).toKotlinString()

            if (by2instance !is ParsedPredicate) {
                testCases.add(PrologTestCase.erroring(testName, IllegalArgumentException("Test cases must be constructed from parsed code so failure locations can be reported.")))
            }

            val goalList = (arg1 as ParsedList).elements.map { it.asPredicate() }.toList()

            testCases.add(object : PrologTestCase {
                override val name = testName

                override fun runWith(callback: TestResultCallback) {
                    val runtimeEnv = DefaultKnowledgeBase.createWithDefaults()
                    runtimeEnv.load(library)
                    val result = runtimeEnv.runTest(goalList, callback)
                }
            })
        }

        return testCases
    }
}

private val prologTestFiles: List<Path>
    get() {
        val classLoader = MethodHandles.lookup().javaClass.classLoader
        val resolver = PathMatchingResourcePatternResolver(classLoader)

        return resolver.getResources("classpath:**/*.test.pl").map { Paths.get(it.file.absolutePath) }
    }

private interface TestResultCallback {
    fun onTestSuccess(testName: String)

    fun onTestFailure(testName: String, message: String)

    fun onTestError(testName: String, error: Throwable)

    fun onTestParseError(errors: Collection<Reporting>)
}

private interface PrologTestCase {
    val name: String
    fun runWith(callback: TestResultCallback)

    companion object {
        fun erroring(name: String, error: Throwable): PrologTestCase = object : PrologTestCase {
            override val name: String = name

            override fun runWith(callback: TestResultCallback) {
                callback.onTestError(this.name, error)
            }
        }
    }
}

private fun createFreshTestingKnowledgeBase(): DefaultKnowledgeBase {
    val kb = DefaultKnowledgeBase.createWithDefaults()
    kb.defineOperator(OperatorDefinition(100, OperatorType.FX, "test"))
    kb.defineOperator(OperatorDefinition(800, OperatorType.XFX, "by"))

    return kb
}

private fun KnowledgeBase.defineOperator(def: OperatorDefinition) {
    fulfill(PredicateQuery(Predicate(
        ":-",
        arrayOf(Predicate("op", arrayOf(
            PrologInteger(def.precedence.toLong()),
            Atom(def.type.name.toLowerCase()),
            Atom(def.name)
        )))
    ))).consumeAll()
}

private fun KnowledgeBase.runTest(goals: List<Query>, listener: TestResultCallback) {
    suspend fun LazySequenceBuilder<Unification>.fulfillAllGoals(goals: List<Query>, context: ProofSearchContext,
                                                                         vars: VariableBucket = VariableBucket()) {
        val goal = goals[0].substituteVariables(vars)

        buildLazySequence<Unification>(context.principal) {
            context.fulfillAttach(this, goal, VariableBucket())
        }
            .forEachRemaining { goalUnification ->
                val goalVars = vars.copy()
                for ((variable, value) in goalUnification.variableValues.values) {
                    if (value != null) {
                        // substitute all instantiated variables for simplicity and performance
                        val substitutedValue = value.substituteVariables(goalVars.asSubstitutionMapper())
                        if (goalVars.isInstantiated(variable)) {
                            if (goalVars[variable] != substitutedValue && goalVars[variable] != value) {
                                // instantiated to different value => no unification
                                return@forEachRemaining
                            }
                        }
                        else {
                            goalVars.instantiate(variable, substitutedValue)
                        }
                    }
                }

                if (goals.size == 1) {
                    // this was the last goal in the list and it is fulfilled
                    // the variable bucket now holds all necessary instantiations
                    yield(Unification(goalVars))
                }
                else {
                    fulfillAllGoals(goals.subList(1, goals.size), context, goalVars)
                }
            }
    }

    val substitutedGoals = goals
        .map { it.substituteVariables(VariableBucket()) }

    val results = buildLazySequence(UUID.randomUUID()) {
        fulfillAllGoals(substitutedGoals, )
    }
}

private fun predicateToQuery(predicate: ParsedPredicate): Query {
    if (predicate.name == ",") {
        val goals = mutableListOf<Query>()
        goals.add(predicateToQuery(predicate.arguments[0].asPredicate()))

        var pivot = predicate.arguments[1].asPredicate()
        while (pivot.name == ",") {
            goals.add(predicateToQuery(pivot.arguments[0].asPredicate()))
            pivot = pivot.arguments[1].asPredicate()
        }

        goals.add(predicateToQuery(pivot))
        return ParsedAndQuery(goals.toTypedArray(), predicate.sourceInformation)
    }
    else if (predicate.name == ";") {
        val goals = mutableListOf<Query>()
        goals.add(predicateToQuery(predicate.arguments[0].asPredicate()))

        var pivot = predicate.arguments[1].asPredicate()
        while (pivot.name == ";") {
            goals.add(predicateToQuery(pivot.arguments[0].asPredicate()))
            pivot = pivot.arguments[1].asPredicate()
        }

        goals.add(predicateToQuery(pivot))
        return ParsedOrQuery(goals.toTypedArray(), predicate.sourceInformation)
    }
    else {
        return ParsedPredicateQuery(predicate)
    }
}

private fun ParsedTerm.asPredicate(): ParsedPredicate {
    if (this is ParsedPredicate) return this

    throw ReportingException(SyntaxError("Expected predicate, got $prologTypeName", sourceInformation))
}