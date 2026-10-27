package com.deepseek.plugin.completion

import com.intellij.openapi.diagnostic.Logger
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiUtilCore

/**
 * Python-specific PSI pre-check layer — identifies scenarios that do not need AI completion
 * and returns local candidates directly, reducing unnecessary API calls.
 *
 * In PyCharm this is the equivalent of Java's [StaticAnalysisCompletionProvider].
 * Loaded via an optional dependency in plugin.xml plus reflection (requires the Python plugin to be present).
 */
object PythonCompletionPreChecker {

    private val LOG = Logger.getInstance(PythonCompletionPreChecker::class.java)

    /** Set of Python built-in functions */
    private val PYTHON_BUILTINS = setOf(
        "print", "len", "str", "int", "float", "list", "dict", "set", "tuple",
        "range", "enumerate", "zip", "map", "filter", "sorted", "reversed",
        "open", "input", "type", "isinstance", "hasattr", "getattr", "setattr",
        "abs", "max", "min", "sum", "any", "all", "round", "pow",
        "format", "repr", "ord", "chr", "bin", "hex", "oct",
        "bool", "bytes", "bytearray", "memoryview",
        "iter", "next", "slice", "super", "object",
        "property", "staticmethod", "classmethod",
        "__init__", "__str__", "__repr__", "__len__", "__getitem__",
        "__setitem__", "__call__", "__enter__", "__exit__",
    )

    /** Set of Python keywords */
    private val PYTHON_KEYWORDS = setOf(
        "False", "None", "True", "and", "as", "assert", "async", "await",
        "break", "class", "continue", "def", "del", "elif", "else", "except",
        "finally", "for", "from", "global", "if", "import", "in", "is",
        "lambda", "nonlocal", "not", "or", "pass", "raise", "return",
        "try", "while", "with", "yield",
    )

    /** Common Python magic method prefixes */
    private val MAGIC_METHOD_PREFIXES = listOf("__", "__i", "__a")

    /**
     * Determines whether the current context is suitable for AI completion.
     * @return true = continue with AI, false = skip AI (local completion is sufficient)
     */
    fun shouldUseAI(element: PsiElement?, file: PsiFile?, prefix: String): Boolean {
        if (element == null || file == null) return true

        // Only applies to Python files
        val language = PsiUtilCore.getLanguageAtOffset(file, element.textOffset)
        if (!language.id.equals("Python", ignoreCase = true) &&
            !file.name.endsWith(".py")) return true

        // 1. Built-in functions/keywords → no AI needed
        val trimmedPrefix = prefix.trim()
        val lastWord = trimmedPrefix.split("[^a-zA-Z0-9_.]".toRegex()).lastOrNull()
            ?.trim() ?: ""
        if (lastWord.isNotEmpty()) {
            if (lastWord in PYTHON_BUILTINS) return false
            if (lastWord in PYTHON_KEYWORDS) return false
        }

        // 2. Possible magic methods → no AI needed (the IDE already has completions)
        if (lastWord.startsWith("__")) return false

        // 3. After a numeric literal or simple operator → no AI needed
        if (trimmedPrefix.matches(Regex(".*\\d$"))) return false
        if (trimmedPrefix.endsWith(".") || trimmedPrefix.endsWith(",")
            || trimmedPrefix.endsWith("(") || trimmedPrefix.endsWith("[")) {
            return true // These cases may need AI completion
        }

        return true // Default to AI
    }
}
