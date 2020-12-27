package com.github.prologdb.runtime.exception

import com.github.prologdb.runtime.PrologSourceInformation
import com.github.prologdb.runtime.module.Module
import com.github.prologdb.runtime.term.CompoundTerm

data class PrologStackTraceElement @JvmOverloads constructor(
    val goal: CompoundTerm,
    val sourceInformation: PrologSourceInformation,
    val module: Module? = null,
    val toStringOverride: String? = null
) {
    override fun toString() = toStringOverride ?: run {
        val modulePrefix = if (module == null) "" else "module ${module.name}, "
        "$goal   $modulePrefix${sourceInformation.sourceFileName}:${sourceInformation.sourceFileLine}"
    }
}
