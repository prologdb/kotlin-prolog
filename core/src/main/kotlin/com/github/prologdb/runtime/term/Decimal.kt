package com.github.prologdb.runtime.term

import com.github.prologdb.runtime.PrologRuntimeException
import com.github.prologdb.runtime.RandomVariableScope
import com.github.prologdb.runtime.unification.Unification
import kotlin.math.pow
import kotlin.math.roundToLong

open class Decimal(val value: Double) : Number {

    override val isInteger = true

    override fun plus(other: Number) =
        when(other) {
            is Decimal -> Decimal(value + other.value)
            is Integer -> Decimal(value + other.value.toDouble())
            else -> throw PrologRuntimeException("Unsupported type of number")
        }

    override fun minus(other: Number) =
        when(other) {
            is Decimal -> Decimal(value - other.value)
            is Integer -> Decimal(value - other.value.toDouble())
            else -> throw PrologRuntimeException("Unsupported type of number")
        }

    override fun times(other: Number) =
        when(other) {
            is Decimal -> Decimal(value * other.value)
            is Integer -> Decimal(value * other.value.toDouble())
            else -> throw PrologRuntimeException("Unsupported type of number")
        }

    override fun div(other: Number) =
        when(other) {
            is Decimal -> Decimal(value / other.value)
            is Integer -> Decimal(value / other.value.toDouble())
            else -> throw PrologRuntimeException("Unsupported type of number")
        }

    override fun rem(other: Number) =
        when(other) {
            is Decimal -> Decimal(value % other.value)
            is Integer -> Decimal(value % other.value.toDouble())
            else -> throw PrologRuntimeException("Unsupported type of number")
        }

    override fun toThe(other: Number): Number {
        return when(other) {
            is Decimal -> Decimal(this.value.pow(other.value))
            is Integer -> Decimal(this.value.pow(other.value.toDouble()))
            else -> throw PrologRuntimeException("Unsupported type of number")
        }
    }

    override fun unaryPlus(): Number = Decimal(+this.value)

    override fun unaryMinus(): Number = Decimal(-this.value)

    override fun toInteger(): Long = value.roundToLong()

    override fun toDecimal(): Double = value

    override fun compareTo(other: Number) =
        when(other) {
            is Integer -> this.value.compareTo(other.value)
            is Decimal -> this.value.compareTo(other.value)
            else -> throw PrologRuntimeException("Unsupported type of number")
        }

    override fun unify(rhs: Term, randomVarsScope: RandomVariableScope): Unification? {
        if (rhs is Decimal) {
            if (rhs.value == value) {
                return Unification.TRUE
            } else {
                return Unification.FALSE
            }
        } else if (rhs is Integer) {
            if (rhs.value.toDouble() == value) {
                return Unification.TRUE
            } else {
                return Unification.FALSE
            }
        } else {
            return rhs.unify(this, randomVarsScope)
        }
    }

    override val variables: Set<Variable> = emptySet()

    override fun substituteVariables(mapper: (Variable) -> Term) = this

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Decimal) return false

        if (value != other.value) return false

        return true
    }

    override fun hashCode() = value.hashCode()

    override fun compareTo(other: Term): Int {
        when(other) {
            // Variables are, by category, lesser than numbers
            is Variable -> return 1

            // ISO prolog categorically sorts decimals before integers
            // as the author of this runtime, i deem this suboptimal.
            // this behaves identical to SWI prolog

            is Decimal -> {
                return this.value.compareTo(other.value)
            }

            is Integer -> {
                // compare mixed as floating point
                val integerAsDouble = other.toDecimal()
                if (this.value == integerAsDouble) return -1 // if equal, the floating point is lesser
                if (this.value >  integerAsDouble) return 1
                return -1
            }

            // everything else is, by category, greater than numbers
            else -> return -1
        }
    }

    override fun toString() = value.toString()
}