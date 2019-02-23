package com.github.prologdb.runtime.expression

import com.github.prologdb.runtime.RandomVariable
import com.github.prologdb.runtime.knowledge.LocalKnowledgeBase
import com.github.prologdb.runtime.knowledge.Rule
import com.github.prologdb.runtime.query.PredicateInvocationQuery
import com.github.prologdb.runtime.shouldNotProve
import com.github.prologdb.runtime.shouldProve
import com.github.prologdb.runtime.suchThat
import com.github.prologdb.runtime.term.*
import io.kotlintest.specs.FreeSpec

class KnowledgeBaseTest : FreeSpec() {init {
    "f(a). f(b). ?- f(X)" {
        val kb = LocalKnowledgeBase()
        val a = Atom("a")
        val b = Atom("b")
        kb.assertz(CompoundTerm("f", arrayOf(a)))
        kb.assertz(CompoundTerm("f", arrayOf(b)))

        val X = Variable("X")
        val queryFact = CompoundTerm("f", arrayOf(X))

        kb shouldProve queryFact suchThat {
            itHasExactlyNSolutions(2)
            itHasASolutionSuchThat("X is instantiated to a") {
                it.variableValues[X] == a
            }
            itHasASolutionSuchThat("X is instantiated to b") {
                it.variableValues[X] == b
            }
        }
    }

    "separate variable scopes: f(a(X,Y),a(Y,X)). ?-f(a(m,n),X))" {
        val kb = LocalKnowledgeBase()
        val f = CompoundBuilder("f")
        val a = CompoundBuilder("a")
        val m = Atom("m")
        val n = Atom("n")
        val X = Variable("X")
        val Y = Variable("Y")

        kb.assertz(f(a(X, Y), a(Y, X)))
        kb shouldProve f(a(m, n), X) suchThat {
            itHasExactlyOneSolution()
            itHasASolutionSuchThat("X = a(n, m)") {
                it.variableValues[X] == a(n, m)
            }
        }
    }

    "lines" {
        /**
         * vertical(line(point(X,Y),point(X,Z))).
         * horizontal(line(point(X,Y),point(Z,Y))).
         * ?- vertical(line(point(a,a),point(a,c))).
         * true
         * ?- vertical(line(point(a,a),point(c,b))).
         * false
         * ?- horizontal(line(point(a,a),point(b,Y))).
         * Y = _4711
         * ?- horizontal(line(point(b,c),P)).
         * P = point(_4711, 3)
         */
        val vertical = CompoundBuilder("vertical")
        val horizontal = CompoundBuilder("horizontal")
        val line = CompoundBuilder("line")
        val point = CompoundBuilder("point")
        val a = Atom("a")
        val b = Atom("b")
        val c = Atom("c")
        val X = Variable("X")
        val Y = Variable("Y")
        val Z = Variable("Z")
        val P = Variable("P")

        val kb = LocalKnowledgeBase()
        kb.assertz(
            vertical(line(point(X,Y),point(X,Z)))
        )
        kb.assertz(
            horizontal(line(point(X,Y),point(Z,Y)))
        )

        // ASSERT
        kb shouldProve vertical(line(point(a,a),point(a,c))) suchThat {
            itHasExactlyOneSolution()
        }

        kb shouldNotProve vertical(line(point(a,a),point(c,b)))

        kb shouldProve horizontal(line(point(a,a),point(b,Y))) suchThat {
            itHasExactlyOneSolution()
            itHasASolutionSuchThat("Y = a") {
                it.variableValues[Y] == a
            }
        }

        kb shouldProve horizontal(line(point(b,c),P)) suchThat {
            itHasExactlyOneSolution()
            itHasASolutionSuchThat("P = point(_R,c)") {
                val valP = it.variableValues[P]

                valP is CompoundTerm && valP.arguments.size == 2 && valP.arguments[0] is RandomVariable && valP.arguments[1] == c
            }
        }
    }

    "g(X, X). ?- g(A, B)" {
        val kb = LocalKnowledgeBase()

        val g = CompoundBuilder("g")
        val X = Variable("X")
        val A = Variable("A")
        val B = Variable("B")
        kb.assertz(g(X, X))

        kb shouldProve g(A, B) suchThat {
            itHasExactlyOneSolution()
            itHasASolutionSuchThat("A = B") {
                it.variableValues[A] == B
            }
        }
    }

    "g(X, X). f(X, Y) :- g(X, Y). ?- f(a, V)" {
        val kb = LocalKnowledgeBase()

        val f = CompoundBuilder("f")
        val g = CompoundBuilder("g")
        val X = Variable("X")
        val Y = Variable("Y")
        val a = Atom("a")
        val V = Variable("V")

        kb.assertz(Rule(f(X, Y), PredicateInvocationQuery(g(X, Y))))
        kb.assertz(g(X, X))

        kb shouldProve f(a, V) suchThat {
            itHasExactlyOneSolution()
            itHasASolutionSuchThat("V = a") {
                it.variableValues[V] == a
            }
        }
    }

    "list append" - {
        val kb = LocalKnowledgeBase()

        val app = CompoundBuilder("app")
        val L = Variable("L")
        val H = Variable("H")
        val T = Variable("T")
        val L3 = Variable("L3")
        val L2 = Variable("L2")
        val R = Variable("R")

        val a = Atom("a")
        val b = Atom("b")
        val c = Atom("c")
        val d = Atom("d")


        // append([],L,L).
        kb.assertz(app(PrologList(emptyList()),L,L))

        // append([H|T],L2,[H|L3]) :- append(T,L2,L3).)
        kb.assertz(Rule(app(PrologList(listOf(H),T),L2, PrologList(listOf(H),L3)), PredicateInvocationQuery(app(T,L2,L3))))

        "simple append" {
            kb shouldProve app(PrologList(listOf(a, b)), PrologList(listOf(c,d)),R) suchThat {
                itHasExactlyOneSolution()
                itHasASolutionSuchThat("R = [a,b,c,d]") {
                    it.variableValues[R] == PrologList(listOf(a,b,c,d))
                }
            }
        }

        "what needs to be appended?" {
            kb shouldProve app(PrologList(listOf(a, b)), L, PrologList(listOf(a, b, c, d))) suchThat {
                itHasExactlyOneSolution()
                itHasASolutionSuchThat("L = [c, d]") {
                    it.variableValues[L] == PrologList(listOf(c, d))
                }
            }
        }

        "what needs to be prepended?" {
            kb shouldProve app(L, PrologList(listOf(c, d)), PrologList(listOf(a, b, c, d))) suchThat {
                itHasExactlyOneSolution()
                itHasASolutionSuchThat("L = [a, b]") {
                    it.variableValues[L] == PrologList(listOf(a, b))
                }
            }
        }

        "what combinations are possible?" {
            val A = Variable("A")
            val B = Variable("B")

            kb shouldProve app(A, B, PrologList(listOf(a, b))) suchThat {
                itHasExactlyNSolutions(3)

                itHasASolutionSuchThat("A = [], B = [a, b]") {
                    it.variableValues[A] == PrologList(emptyList())
                    &&
                    it.variableValues[B] == PrologList(listOf(a, b))
                }

                itHasASolutionSuchThat("A = [a], B = [b]") {
                    it.variableValues[A] == PrologList(listOf(a))
                    &&
                    it.variableValues[B] == PrologList(listOf(b))
                }

                itHasASolutionSuchThat("A = [a, b], B = []") {
                    it.variableValues[A] == PrologList(listOf(a, b))
                    &&
                    it.variableValues[B] == PrologList(emptyList())
                }
            }
        }
    }

    "retain values of random variables" {
        val X = Variable("X")
        val a = CompoundBuilder("a")
        val H = Variable("H")
        val T = Variable("T")

        val u = Atom("u")
        val v = Atom("v")

        val kb = LocalKnowledgeBase()
        kb.assertz(a(PrologList(listOf(H), T), PrologList(listOf(H), T)))

        kb shouldProve a(X, PrologList(listOf(u, v))) suchThat {
            itHasExactlyOneSolution()
            itHasASolutionSuchThat("X = [u,v]") {
                val valX = it.variableValues[X] as PrologList

                valX.elements[0] == u
                &&
                valX.elements[1] == v
            }
        }
    }

    "anonymous variable" - {
        val kb = LocalKnowledgeBase()
        val f = CompoundBuilder("f")
        val _A = Variable.ANONYMOUS
        val X = Variable("X")
        val a = Atom("a")
        val b = Atom("b")

        "case 1" {
            kb.assertz(f(_A))

            kb shouldProve f(a) suchThat {
                itHasExactlyOneSolution()
                itHasASolutionSuchThat("it is empty") {
                    it.variableValues.isEmpty
                }
            }
        }

        "case 2" {
            kb.assertz(f(_A, _A))

            kb shouldProve f(a, b) suchThat {
                itHasExactlyOneSolution()
                itHasASolutionSuchThat("it is empty") {
                    it.variableValues.isEmpty
                }
            }
        }

        "case 3" {
            kb.assertz(f(_A, b))

            kb shouldProve f(a, X) suchThat {
                itHasExactlyOneSolution()
                itHasASolutionSuchThat("X = b") {
                    it.variableValues[X] == b
                }
            }
        }

        "case 4" {
            kb.assertz(f(_A))

            kb shouldProve f(X) suchThat {
                itHasExactlyOneSolution()
                itHasASolutionSuchThat("X = _") {
                    it.variableValues.isEmpty
                }
            }
        }
    }
}}
