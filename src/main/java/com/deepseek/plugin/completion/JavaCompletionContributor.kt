package com.deepseek.plugin.completion

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.patterns.PlatformPatterns

/**
 * Java code completion contributor — registered only when the Java plugin is present.
 *
 * Includes static analysis pre-filtering based on PSI/AST,
 * as well as annotation-aware / comment-aware completion.
 */
class JavaCompletionContributor : CompletionContributor() {

    init {
        // Static analysis Provider — runs first, skips AI when there are enough candidates
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement(),
            StaticAnalysisCompletionProvider()
        )
    }
}
