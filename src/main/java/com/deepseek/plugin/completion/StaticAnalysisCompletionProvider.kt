package com.deepseek.plugin.completion

import com.deepseek.plugin.settings.DeepSeekSettings
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext
import javax.swing.Icon

/**
 * Basic completion Provider based on static analysis and type inference.
 *
 * [Principles]
 * 1. AST construction — parse source code in real time via IntelliJ PSI to understand code structure, scope, and dependencies
 * 2. Semantic indexing — retrieve all classes/methods/fields in the project through the global index, filtering inaccessible items based on the current context
 * 3. Rule matching — use predefined language syntax rules and live templates for deterministic matching, ensuring completion items conform to syntax rules
 *
 * [Behavior]
 * Acts as a pre-filter before AI completion:
 * - When static analysis can determine valid candidates → add candidates and block the AI request (saving tokens)
 * - When static analysis candidates are insufficient → allow DeepSeekCompletionProvider to trigger the AI API
 *
 * Exposes the decision via the [satisfies] method for [DeepSeekCompletionProvider] to query.
 */
class StaticAnalysisCompletionProvider : CompletionProvider<CompletionParameters>() {

    companion object {
        private val LOG = Logger.getInstance(StaticAnalysisCompletionProvider::class.java)

        const val MIN_CANDIDATES_THRESHOLD = 2
        private const val HIGH_CONFIDENCE_THRESHOLD = 0.9f
    }

    private val annotationAware = AnnotationAwareCompletion()
    private val commentAware = CommentAwareCompletion()

    private val isAnnotationAwareEnabled: Boolean
        get() = DeepSeekSettings.instance.annotationAwareEnabled

    private val isCommentAwareEnabled: Boolean
        get() = DeepSeekSettings.instance.commentAwareEnabled

    /**
     * Data carrier for a single analysis result.
     */
    data class AnalysisResult(
        val candidates: List<CompletionCandidate>,
        val contextType: CompletionContext
    ) {
        /** Whether static analysis has sufficiently satisfied the completion needs */
        val isSatisfied: Boolean
            get() = when {
                candidates.isEmpty() -> false
                candidates.any { it.relevanceScore >= HIGH_CONFIDENCE_THRESHOLD } -> true
                candidates.size >= MIN_CANDIDATES_THRESHOLD -> true
                else -> false
            }
    }

    data class CompletionCandidate(
        val displayName: String,
        val insertText: String,
        val typeText: String?,
        val icon: Icon?,
        val description: String? = null,
        /** Match confidence 0.0~1.0 */
        val relevanceScore: Float = 0.5f
    )

    enum class CompletionContext {
        /** Member access: expr.xxx */
        MEMBER_ACCESS,
        /** Type reference: new Xxx, extends Xxx, generic parameters */
        TYPE_REFERENCE,
        /** Variable/expression position */
        EXPRESSION,
        /** Keyword position */
        KEYWORD,
        /** Annotation decoration position: @xxx */
        ANNOTATION_DECORATOR,
        /** Unrecognized */
        UNKNOWN
    }

    // ==================== Entry point ====================

    override fun addCompletions(
        parameters: CompletionParameters,
        context: ProcessingContext,
        result: CompletionResultSet
    ) {
        val project = parameters.editor.project ?: return
        val file = parameters.originalFile ?: return
        if (file !is PsiJavaFile) return // Java files only (extension point: Kotlin support later)
        val offset = parameters.offset

        try {
            val analysis = analyze(project, file, offset)
            for (c in analysis.candidates) {
                result.addElement(toLookupElement(c))
            }
            if (analysis.candidates.isNotEmpty()) {
                LOG.debug("StaticAnalysis: ${analysis.candidates.size} candidates, context=${analysis.contextType}, satisfied=${analysis.isSatisfied}")
            }
        } catch (e: Exception) {
            LOG.warn("StaticAnalysis completion error", e)
        }
    }

    /**
     * Pure analysis interface exposed to external callers (no side effects), used by the AI Provider to decide whether to fall back.
     */
    fun analyze(project: Project, file: PsiFile, offset: Int): AnalysisResult {
        val element = file.findElementAt(offset) ?: return AnalysisResult(emptyList(), CompletionContext.UNKNOWN)

        // Annotation awareness: detect the annotation context
        if (isAnnotationAwareEnabled && annotationAware.isAnnotationContext(element, offset)) {
            val annotationResult = analyzeAnnotationDecorator(project, element)
            if (annotationResult.candidates.isNotEmpty()) {
                return annotationResult
            }
        }

        // Comment awareness: detect comment content before the caret
        if (isCommentAwareEnabled) {
            val commentResult = analyzeCommentContext(element, offset)
            if (commentResult.candidates.isNotEmpty()) {
                return commentResult
            }
        }

        return when (val ctx = determineContext(element, file, offset)) {
            CompletionContext.MEMBER_ACCESS  -> {
                val result = analyzeMemberAccess(project, element)
                if (isAnnotationAwareEnabled) {
                    result.withSyntheticMembers(element)
                } else result
            }
            CompletionContext.TYPE_REFERENCE -> analyzeTypeReference(project, element, file)
            CompletionContext.EXPRESSION     -> {
                val result = analyzeExpression(project, element, offset)
                if (isAnnotationAwareEnabled) {
                    result.withContextSuggestions(element)
                } else result
            }
            CompletionContext.KEYWORD        -> analyzeKeyword(element)
            CompletionContext.ANNOTATION_DECORATOR -> analyzeAnnotationDecorator(project, element)
            CompletionContext.UNKNOWN        -> analyzeFallback(project, element, file, offset)
        }
    }

    // ==================== Context detection ====================

    private fun determineContext(element: PsiElement, file: PsiFile, offset: Int): CompletionContext {
        // 1) Highest priority: member access (right of a dot)
        //    findElementAt(offset) may return the dot itself or an identifier,
        //    so check whether the parent or prevSibling is a dot
        val parent = element.parent
        if (parent is PsiReferenceExpression) {
            val qualifier = parent.qualifierExpression
            if (qualifier != null && qualifier.textOffset < offset) {
                return CompletionContext.MEMBER_ACCESS
            }
        }
        // The caret is right after a dot but findElementAt returns the dot itself
        if (element.text == "." && element.parent is PsiReferenceExpression) {
            return CompletionContext.MEMBER_ACCESS
        }

        // 2) Type reference: after the new keyword
        if (element.text == "new" ||
            element.prevSibling?.text == "new" ||
            parent?.text == "new") {
            return CompletionContext.TYPE_REFERENCE
        }

        // 3) Walk up the hierarchy to check
        var walk: PsiElement = element
        while (walk !is PsiFile) {
            when {
                walk.parent is PsiTypeElement -> return CompletionContext.TYPE_REFERENCE
                walk is PsiJavaCodeReferenceElement && walk.parent !is PsiReferenceExpression ->
                    return CompletionContext.TYPE_REFERENCE
                walk is PsiExpression -> return CompletionContext.EXPRESSION
            }
            walk = walk.parent ?: break
        }

        return CompletionContext.UNKNOWN
    }

    // ==================== Annotation decorator completion ====================

    /**
     * Recommends applicable annotation names at the @ symbol position.
     * Filters available annotations based on the context (class/method/field/parameter).
     */
    private fun analyzeAnnotationDecorator(project: Project, element: PsiElement): AnalysisResult {
        val applicable = annotationAware.getApplicableAnnotations(element)
        if (applicable.isEmpty()) return AnalysisResult(emptyList(), CompletionContext.ANNOTATION_DECORATOR)

        val candidates = applicable.map { anno ->
            CompletionCandidate(
                displayName = anno,
                insertText = anno.substring(1), // Remove the @ prefix since it is already in the editor
                typeText = "annotation",
                icon = AllIcons.Nodes.Annotationtype,
                description = "Annotation",
                relevanceScore = 0.9f
            )
        }
        return AnalysisResult(candidates, CompletionContext.ANNOTATION_DECORATOR)
    }

    // ==================== Comment-aware completion ====================

    /**
     * Analyzes the comment content before the caret and returns matching code completion suggestions.
     * Supports: getter/setter, constructors, design patterns, CRUD, Spring patterns, etc.
     */
    private fun analyzeCommentContext(element: PsiElement, offset: Int): AnalysisResult {
        if (!commentAware.isAfterComment(element, offset)) {
            return AnalysisResult(emptyList(), CompletionContext.UNKNOWN)
        }
        val suggestions = commentAware.analyzeComments(element, offset)
        if (suggestions.isEmpty()) return AnalysisResult(emptyList(), CompletionContext.UNKNOWN)

        val candidates = commentAware.toCompletionCandidates(suggestions)
        LOG.debug("CommentAware: ${candidates.size} suggestions from comment")
        return AnalysisResult(candidates, CompletionContext.UNKNOWN)
    }

    // ==================== Member access completion ====================

    private fun analyzeMemberAccess(project: Project, element: PsiElement): AnalysisResult {
        val ref = PsiTreeUtil.getParentOfType(element, PsiReferenceExpression::class.java)
            ?: return AnalysisResult(emptyList(), CompletionContext.MEMBER_ACCESS)

        val qualifier = ref.qualifierExpression ?: return AnalysisResult(emptyList(), CompletionContext.MEMBER_ACCESS)
        val qualifierType = qualifier.type ?: return AnalysisResult(emptyList(), CompletionContext.MEMBER_ACCESS)

        val candidates = mutableListOf<CompletionCandidate>()
        val seen = mutableSetOf<String>()

        // Traverse the type hierarchy (class + superclasses + interfaces)
        forEachTypeInHierarchy(qualifierType, project) { psiClass ->
            val isStaticCtx = (qualifier is PsiKeyword && qualifier.text == "super") ||
                (qualifier is PsiJavaCodeReferenceElement && qualifier.resolve() is PsiClass)

            for (m in psiClass.methods) {
                if (isStaticCtx && !m.hasModifierProperty(PsiModifier.STATIC)) continue
                if (m.isConstructor || m.name in seen) continue
                if (!isAccessible(m, currentPackage(element))) continue
                seen.add(m.name)

                val params = m.parameterList.parameters.joinToString(", ") {
                    it.type?.presentableText ?: "?"
                }
                candidates.add(CompletionCandidate(
                    displayName = m.name,
                    insertText = if (m.parameterList.parametersCount == 0) "${m.name}()" else "${m.name}(${params})",
                    typeText = m.returnType?.presentableText,
                    icon = AllIcons.Nodes.Method,
                    description = "($params)",
                    relevanceScore = 0.95f
                ))
            }

            for (f in psiClass.fields) {
                if (isStaticCtx && !f.hasModifierProperty(PsiModifier.STATIC)) continue
                if (f.name in seen) continue
                if (!isAccessible(f, currentPackage(element))) continue
                seen.add(f.name)

                candidates.add(CompletionCandidate(
                    displayName = f.name,
                    insertText = f.name,
                    typeText = f.type?.presentableText,
                    icon = AllIcons.Nodes.Field,
                    relevanceScore = 0.85f
                ))
            }
        }

        // If the qualifier is this/super, instance methods are also included
        return AnalysisResult(candidates, CompletionContext.MEMBER_ACCESS)
    }

    /** Adds annotation-generated synthetic members (e.g. getters/setters generated by Lombok @Data) to a member-access result, returning a new result */
    private fun AnalysisResult.withSyntheticMembers(element: PsiElement): AnalysisResult {
        val ref = PsiTreeUtil.getParentOfType(element, PsiReferenceExpression::class.java) ?: return this
        val qualifier = ref.qualifierExpression ?: return this
        val qualifierType = qualifier.type ?: return this

        // Static calls (ClassName.method) do not add synthetic members
        if (qualifier is PsiJavaCodeReferenceElement && qualifier.resolve() is PsiClass) return this

        val cls = (qualifierType as? PsiClassType)?.resolve() ?: return this
        if (cls.annotations.isEmpty()) return this

        val synthetic = annotationAware.getSyntheticMembers(cls)
        val seenNames = candidates.map { it.displayName }.toSet()

        val extra = mutableListOf<CompletionCandidate>()
        for (sm in synthetic) {
            if (sm.name in seenNames) continue
            extra.add(CompletionCandidate(
                displayName = sm.name,
                insertText = if (sm.parameterTypes.isEmpty()) "${sm.name}()" else "${sm.name}(${sm.parameterTypes.first()})",
                typeText = sm.returnType,
                icon = when (sm.kind) {
                    AnnotationAwareCompletion.MemberKind.GETTER -> AllIcons.Nodes.PropertyRead
                    AnnotationAwareCompletion.MemberKind.SETTER -> AllIcons.Nodes.PropertyWrite
                    AnnotationAwareCompletion.MemberKind.LOGGER -> AllIcons.Nodes.Variable
                    else -> AllIcons.Nodes.Method
                },
                description = sm.description,
                relevanceScore = 0.9f
            ))
        }
        return if (extra.isEmpty()) this
        else AnalysisResult(candidates + extra, contextType)
    }

    private fun analyzeTypeReference(project: Project, element: PsiElement, file: PsiFile): AnalysisResult {
        val psiFile = file as? PsiJavaFile
        val currentPkg = psiFile?.packageName ?: ""
        val imported = getImportedShortNames(psiFile)

        val candidates = mutableListOf<CompletionCandidate>()
        val seen = mutableSetOf<String>()
        val cache = PsiShortNamesCache.getInstance(project)
        val allScope = GlobalSearchScope.allScope(project)

        // 1) Classes in the same package
        if (currentPkg.isNotEmpty()) {
            val pkg = JavaPsiFacade.getInstance(project).findPackage(currentPkg)
            if (pkg != null) {
                for (cls in pkg.getClasses(allScope)) {
                    val name = cls.name ?: continue
                    if (name !in seen) {
                        seen.add(name)
                        candidates.add(CompletionCandidate(
                            displayName = name,
                            insertText = name,
                            typeText = cls.qualifiedName,
                            icon = AllIcons.Nodes.Class,
                            relevanceScore = 0.95f
                        ))
                    }
                }
            }
        }

        // 2) Explicitly imported classes
        for (shortName in imported) {
            if (shortName in seen) continue
            for (cls in cache.getClassesByName(shortName, allScope)) {
                val name = cls.name ?: continue
                if (name !in seen) {
                    seen.add(name)
                    candidates.add(CompletionCandidate(
                        displayName = name,
                        insertText = name,
                        typeText = cls.qualifiedName,
                        icon = AllIcons.Nodes.Class,
                        relevanceScore = 0.85f
                    ))
                }
            }
        }

        // 3) Common classes in java.lang (implicitly imported)
        val javaLang = listOf(
            "String", "Integer", "Long", "Double", "Float", "Boolean",
            "Object", "Exception", "RuntimeException", "System",
            "Math", "StringBuilder", "StringBuffer", "Thread",
            "Comparable", "Iterable", "Throwable"
        )
        for (name in javaLang) {
            if (name !in seen) {
                seen.add(name)
                candidates.add(CompletionCandidate(
                    displayName = name,
                    insertText = name,
                    typeText = "java.lang.$name",
                    icon = AllIcons.Nodes.Class,
                    relevanceScore = 0.6f
                ))
            }
        }

        return AnalysisResult(candidates, CompletionContext.TYPE_REFERENCE)
    }

    // ==================== Expression completion ====================

    private fun analyzeExpression(project: Project, element: PsiElement, offset: Int = element.textOffset): AnalysisResult {
        val candidates = mutableListOf<CompletionCandidate>()
        val seen = mutableSetOf<String>()

        // 1) Local variables + method parameters
        val method = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)
        if (method != null) {
            for (p in method.parameterList.parameters) {
                val pName = p.name ?: continue
                if (pName !in seen) {
                    seen.add(pName)
                    candidates.add(CompletionCandidate(
                        displayName = pName,
                        insertText = pName,
                        typeText = p.type?.presentableText,
                        icon = AllIcons.Nodes.Parameter,
                        relevanceScore = 1.0f
                    ))
                }
            }

            val body = method.body ?: return AnalysisResult(candidates, CompletionContext.EXPRESSION)
            for (v in PsiTreeUtil.collectElementsOfType(body, PsiLocalVariable::class.java)) {
                val vName = v.name ?: continue
                if (vName !in seen && v.textOffset < offset) {
                    seen.add(vName)
                    candidates.add(CompletionCandidate(
                        displayName = vName,
                        insertText = vName,
                        typeText = v.type?.presentableText,
                        icon = AllIcons.Nodes.Variable,
                        relevanceScore = 1.0f
                    ))
                }
            }
        }

        // 2) Fields of the current class
        val psiClass = PsiTreeUtil.getParentOfType(element, PsiClass::class.java)
        if (psiClass != null) {
            for (f in psiClass.fields) {
                val fName = f.name ?: continue
                if (fName !in seen) {
                    seen.add(fName)
                    candidates.add(CompletionCandidate(
                        displayName = fName,
                        insertText = fName,
                        typeText = f.type?.presentableText,
                        icon = AllIcons.Nodes.Field,
                        relevanceScore = 0.8f
                    ))
                }
            }
        }

        // 3) this / super / constants
        if (psiClass != null) {
            candidates.add(CompletionCandidate("this", "this", psiClass.qualifiedName, AllIcons.Nodes.Variable, relevanceScore = 0.9f))
            if (psiClass.superClass != null) {
                candidates.add(CompletionCandidate("super", "super", psiClass.superClass?.qualifiedName, AllIcons.Nodes.Variable, relevanceScore = 0.8f))
            }
        }
        candidates.add(CompletionCandidate("true", "true", "boolean", AllIcons.Nodes.Variable, relevanceScore = 0.5f))
        candidates.add(CompletionCandidate("false", "false", "boolean", AllIcons.Nodes.Variable, relevanceScore = 0.5f))
        candidates.add(CompletionCandidate("null", "null", null, AllIcons.Nodes.Variable, relevanceScore = 0.5f))

        return AnalysisResult(candidates, CompletionContext.EXPRESSION)
    }

    /** Adds annotation-driven code pattern suggestions to an expression result, returning a new result */
    private fun AnalysisResult.withContextSuggestions(element: PsiElement): AnalysisResult {
        val ctx = annotationAware.analyzeAnnotations(element)
        if (ctx.hints.isEmpty()) return this
        val suggestions = annotationAware.getContextSuggestions(element, ctx)
        if (suggestions.isEmpty()) return this
        return AnalysisResult(candidates + suggestions, contextType)
    }

    // ==================== Keyword completion ====================

    private fun analyzeKeyword(element: PsiElement): AnalysisResult {
        val snippets = mapOf(
            "if"     to "if () {\n}",
            "else"   to "else {\n}",
            "for"    to "for () {\n}",
            "while"  to "while () {\n}",
            "do"     to "do {\n} while ();",
            "try"    to "try {\n} catch () {\n}",
            "catch"  to "catch () {\n}",
            "finally" to "finally {\n}",
            "switch" to "switch () {\n\tcase :\n\t\tbreak;\n}",
            "case"   to "case ",
            "return" to "return ",
            "throw"  to "throw ",
            "new"    to "new ",
            "break"  to "break;",
            "continue" to "continue;",
            "synchronized" to "synchronized () {\n}"
        )
        val candidates = snippets.map { (kw, snippet) ->
            CompletionCandidate(kw, snippet, "keyword", AllIcons.Nodes.Variable, relevanceScore = 0.4f)
        }
        return AnalysisResult(candidates, CompletionContext.KEYWORD)
    }

    // ==================== Fallback ====================

    private fun analyzeFallback(project: Project, element: PsiElement, file: PsiFile, offset: Int): AnalysisResult {
        // Check the annotation context first
        if (isAnnotationAwareEnabled) {
            val annoResult = analyzeAnnotationDecorator(project, element)
            if (annoResult.candidates.isNotEmpty()) return annoResult
        }
        // Try each strategy in turn
        listOf(
            { analyzeMemberAccess(project, element) },
            { analyzeTypeReference(project, element, file) },
            { analyzeExpression(project, element, offset) },
            { analyzeKeyword(element) }
        ).forEach { strategy ->
            val r = strategy()
            if (r.candidates.isNotEmpty()) return r
        }
        return AnalysisResult(emptyList(), CompletionContext.UNKNOWN)
    }

    // ==================== Utility methods ====================

    /** Traverses the type hierarchy (the class itself + superclasses + interfaces), going at most 10 levels deep to prevent StackOverflow */
    private fun forEachTypeInHierarchy(type: PsiType, project: Project, action: (PsiClass) -> Unit) {
        val seen = mutableSetOf<String>()

        fun walk(psiClass: PsiClass?, depth: Int = 0) {
            if (psiClass == null) return
            if (depth > 10) return // Depth limit to prevent cyclic inheritance
            val qName = psiClass.qualifiedName ?: return
            if (qName in seen) return
            seen.add(qName)

            // Object's members are meaningless for completion
            if (qName == "java.lang.Object") return

            action(psiClass)
            walk(psiClass.superClass, depth + 1)
            psiClass.interfaces.forEach { walk(it, depth + 1) }
        }

        val cls = (type as? PsiClassType)?.resolve()
        walk(cls)
    }

    private fun currentPackage(element: PsiElement): String? {
        return (element.containingFile as? PsiJavaFile)?.packageName
    }

    private fun isAccessible(member: PsiModifierListOwner, currentPackage: String?): Boolean {
        if (member.hasModifierProperty(PsiModifier.PUBLIC)) return true
        if (member.hasModifierProperty(PsiModifier.PROTECTED)) return true
        if (member.hasModifierProperty(PsiModifier.PRIVATE)) return false
        // package-private
        val memberFile = member.containingFile as? PsiJavaFile ?: return false
        return memberFile.packageName == currentPackage
    }

    private fun getImportedShortNames(psiFile: PsiJavaFile?): Set<String> {
        if (psiFile == null) return emptySet()
        val imports = mutableSetOf<String>()
        for (stmt in psiFile.importList?.allImportStatements ?: emptyArray()) {
            val ref = stmt.importReference ?: continue
            val qn = ref.qualifiedName ?: continue
            if (stmt.isOnDemand) {
                // star import — not expanded; covered by global search fallback
                continue
            }
            imports.add(qn.substringAfterLast('.'))
        }
        return imports
    }

    private fun toLookupElement(c: CompletionCandidate): LookupElementBuilder {
        val builder = LookupElementBuilder.create(c.insertText)
            .withPresentableText(c.displayName)
            .withIcon(c.icon ?: AllIcons.Nodes.Variable)
        var b = builder
        if (c.typeText != null) b = b.withTypeText(c.typeText)
        if (c.description != null) b = b.withTailText(c.description)
        return b
    }
}
