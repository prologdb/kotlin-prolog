package com.github.prologdb.runtime.builtin.dicts

import com.github.prologdb.async.LazySequence
import com.github.prologdb.async.mapRemainingNotNull
import com.github.prologdb.runtime.ArgumentNotInstantiatedError
import com.github.prologdb.runtime.ArgumentTypeError
import com.github.prologdb.runtime.builtin.nativeRule
import com.github.prologdb.runtime.term.Atom
import com.github.prologdb.runtime.term.PrologDictionary
import com.github.prologdb.runtime.term.Variable

val BuiltinGetDict3 = nativeRule("get_dict", 3) {
    { args, ctxt ->
        val keyArg = args[0]
        val dictArg = args[1]
        val valueArg = args[2]

        if (keyArg !is Atom && keyArg !is Variable) {
            throw ArgumentTypeError(fqIndicator, 0, keyArg, "atom", "unbound")
        }

        if (dictArg !is PrologDictionary) {
            if (dictArg is Variable) {
                throw ArgumentNotInstantiatedError(fqIndicator, 1, "dict")
        } else {
                throw ArgumentTypeError(fqIndicator, 1, dictArg, "dict")
        }
    }

    if (keyArg is Variable) {
        yieldAllFinal(LazySequence.ofIterable(dictArg.pairs.entries, principal).mapRemainingNotNull { (dictKey, dictValue) ->
            val valueUnification = valueArg.unify(dictValue, ctxt.randomVariableScope)
            if (valueUnification != null) {
                if (valueUnification.variableValues.isInstantiated(keyArg)) {
                    if (valueUnification.variableValues[keyArg] == dictKey) {
                        return@mapRemainingNotNull valueUnification
                    }
                } else {
                    valueUnification.variableValues.instantiate(keyArg, dictKey)
                    return@mapRemainingNotNull valueUnification
                }
            }

            return@mapRemainingNotNull null
        })
    } else {
        keyArg as Atom
        val valueForArg = dictArg.pairs[keyArg]

        if (valueForArg != null) {
            valueArg.unify(valueForArg, ctxt.randomVariableScope)
        } else {
            null
        }
    }
    }
}
