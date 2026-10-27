package com.deepseek.plugin.completion

import com.intellij.openapi.diagnostic.Logger
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil

/**
 * Annotation-aware completion engine.
 *
 * Analyzes annotations in the user's code (Lombok, Spring, JPA, Jakarta, custom, etc.)
 * and provides the completion system with:
 * - Synthetic members generated from annotations (e.g. @Data → getters/setters)
 * - Annotation-driven code pattern suggestions (e.g. @RequestMapping → URL mapping skeleton)
 * - Annotation-based candidate ranking/filtering (e.g. @Deprecated downranking, @Nullable marking)
 * - @ annotation name completion (recommending applicable annotations at class/method/field decoration positions)
 */
class AnnotationAwareCompletion {

    companion object {
        private val LOG = Logger.getInstance(AnnotationAwareCompletion::class.java)

        // ============== Known annotation constants ==============

        // ---- Lombok ----
        const val LOMBOK_DATA        = "lombok.Data"
        const val LOMBOK_GETTER      = "lombok.Getter"
        const val LOMBOK_SETTER      = "lombok.Setter"
        const val LOMBOK_TOSTRING    = "lombok.ToString"
        const val LOMBOK_EQUALS_HASH = "lombok.EqualsAndHashCode"
        const val LOMBOK_ALL_ARGS    = "lombok.AllArgsConstructor"
        const val LOMBOK_NO_ARGS     = "lombok.NoArgsConstructor"
        const val LOMBOK_REQ_ARGS    = "lombok.RequiredArgsConstructor"
        const val LOMBOK_BUILDER     = "lombok.Builder"
        const val LOMBOK_VALUE       = "lombok.Value"
        const val LOMBOK_SLF4J       = "lombok.extern.slf4j.Slf4j"
        const val LOMBOK_LOG4J       = "lombok.extern.log4j.Log4j"

        // ---- Spring ----
        const val SPRING_AUTOWIRED        = "org.springframework.beans.factory.annotation.Autowired"
        const val SPRING_SERVICE          = "org.springframework.stereotype.Service"
        const val SPRING_CONTROLLER       = "org.springframework.stereotype.Controller"
        const val SPRING_REST_CONTROLLER  = "org.springframework.web.bind.annotation.RestController"
        const val SPRING_REPOSITORY       = "org.springframework.stereotype.Repository"
        const val SPRING_COMPONENT        = "org.springframework.stereotype.Component"
        const val SPRING_CONFIGURATION    = "org.springframework.context.annotation.Configuration"
        const val SPRING_BEAN             = "org.springframework.context.annotation.Bean"
        const val SPRING_VALUE            = "org.springframework.beans.factory.annotation.Value"
        const val SPRING_REQUEST_MAPPING  = "org.springframework.web.bind.annotation.RequestMapping"
        const val SPRING_GET_MAPPING      = "org.springframework.web.bind.annotation.GetMapping"
        const val SPRING_POST_MAPPING     = "org.springframework.web.bind.annotation.PostMapping"
        const val SPRING_PUT_MAPPING      = "org.springframework.web.bind.annotation.PutMapping"
        const val SPRING_DEL_MAPPING      = "org.springframework.web.bind.annotation.DeleteMapping"
        const val SPRING_PATH_VARIABLE    = "org.springframework.web.bind.annotation.PathVariable"
        const val SPRING_REQUEST_PARAM    = "org.springframework.web.bind.annotation.RequestParam"
        const val SPRING_REQUEST_BODY     = "org.springframework.web.bind.annotation.RequestBody"
        const val SPRING_TRANSACTIONAL    = "org.springframework.transaction.annotation.Transactional"
        const val SPRING_CACHEABLE        = "org.springframework.cache.annotation.Cacheable"
        const val SPRING_ASYNC            = "org.springframework.scheduling.annotation.Async"
        const val SPRING_SCHEDULED        = "org.springframework.scheduling.annotation.Scheduled"

        // ---- JPA / Jakarta ----
        const val JPA_ENTITY       = "jakarta.persistence.Entity"
        const val JPA_TABLE        = "jakarta.persistence.Table"
        const val JPA_COLUMN       = "jakarta.persistence.Column"
        const val JPA_ID           = "jakarta.persistence.Id"
        const val JPA_GENERATED    = "jakarta.persistence.GeneratedValue"
        const val JPA_ONE_TO_MANY  = "jakarta.persistence.OneToMany"
        const val JPA_MANY_TO_ONE  = "jakarta.persistence.ManyToOne"
        const val JPA_JOIN_COLUMN  = "jakarta.persistence.JoinColumn"
        const val JPA_TRANSACTIONAL = "jakarta.transaction.Transactional"

        // ---- Legacy javax JPA ----
        const val JAVAX_ENTITY       = "javax.persistence.Entity"
        const val JAVAX_TABLE        = "javax.persistence.Table"
        const val JAVAX_COLUMN       = "javax.persistence.Column"

        // ---- Jackson ----
        const val JACKSON_JSON_PROPERTY  = "com.fasterxml.jackson.annotation.JsonProperty"
        const val JACKSON_JSON_IGNORE    = "com.fasterxml.jackson.annotation.JsonIgnore"
        const val JACKSON_JSON_FORMAT    = "com.fasterxml.jackson.annotation.JsonFormat"

        // ---- Jakarta / Java EE Validation ----
        const val VALID_NOT_NULL    = "jakarta.validation.constraints.NotNull"
        const val VALID_NOT_BLANK   = "jakarta.validation.constraints.NotBlank"
        const val VALID_SIZE        = "jakarta.validation.constraints.Size"
        const val VALID_EMAIL       = "jakarta.validation.constraints.Email"
        const val VALID_PATTERN     = "jakarta.validation.constraints.Pattern"

        // ---- IntelliJ / JetBrains annotations ----
        const val INT_NULLABLE  = "org.jetbrains.annotations.Nullable"
        const val INT_NOT_NULL  = "org.jetbrains.annotations.NotNull"

        // ---- Annotation application targets ----
        private val CLASS_TARGET   = setOf(Target.CLASS)
        private val METHOD_TARGET  = setOf(Target.METHOD)
        private val FIELD_TARGET   = setOf(Target.FIELD)
        private val CM_TARGET      = setOf(Target.CLASS, Target.METHOD)
        private val CF_TARGET      = setOf(Target.CLASS, Target.FIELD)
        private val ALL_TARGET     = Target.values().toSet()
    }

    // ==================== Type definitions ====================

    /** Annotation application target */
    enum class Target { CLASS, METHOD, FIELD, PARAMETER }

    /** Synthetic member kind */
    enum class MemberKind {
        GETTER, SETTER, BUILDER_METHOD, STATIC_FACTORY, LOGGER,
        ALL_ARGS_CTOR, NO_ARGS_CTOR, REQ_ARGS_CTOR,
        EQUALS, HASH_CODE, TO_STRING, WITHER
    }

    /** Context hint */
    enum class ContextHint {
        /** @Autowired field → suggests an injection point */
        INJECTION_POINT,
        /** @RequestMapping → suggests a URL mapping pattern */
        REQUEST_MAPPING,
        /** @Transactional → suggests a transactional pattern */
        TRANSACTIONAL,
        /** @Entity → suggests column mapping */
        ENTITY_MAPPING,
        /** @Configuration → suggests a @Bean method */
        CONFIGURATION_METHOD,
        /** @Service/@Component → suggests constructor injection */
        CONSTRUCTOR_INJECTION,
        /** @Scheduled → suggests a cron expression */
        SCHEDULED_TASK,
        /** @Cacheable → suggests caching usage */
        CACHING,
        /** @Async → suggests an async pattern */
        ASYNC_EXECUTION,
    }

    /** Synthetic member description */
    data class SyntheticMember(
        val name: String,
        val returnType: String,
        val parameterTypes: List<String> = emptyList(),
        val kind: MemberKind,
        val description: String = "",
        /** Whether it is static */
        val isStatic: Boolean = false,
        /** Generic type parameters */
        val typeParams: List<String> = emptyList()
    )

    /** Annotation analysis result */
    data class AnnotationContext(
        /** Set of class-level annotation FQNs */
        val classAnnotations: Set<String> = emptySet(),
        /** Set of method-level annotation FQNs (current method) */
        val methodAnnotations: Set<String> = emptySet(),
        /** Set of field-level annotation FQNs (current field) */
        val fieldAnnotations: Set<String> = emptySet(),
        /** Set of parameter-level annotation FQNs (current parameter) */
        val parameterAnnotations: Set<String> = emptySet(),
        /** Context hints */
        val hints: Set<ContextHint> = emptySet(),
        /** Whether this annotation context activates synthetic members */
        val hasSyntheticMembers: Boolean = false
    )

    // ==================== Annotation registry ====================

    /** Known annotation → semantic behavior */
    private val knownAnnotations: Map<String, AnnotationBehavior> = mapOf(
        // ---- Lombok ----
        LOMBOK_DATA to AnnotationBehavior(
            category = "Lombok", target = CLASS_TARGET,
            generatesMembers = true, hints = emptySet()
        ),
        LOMBOK_GETTER to AnnotationBehavior(
            category = "Lombok", target = CLASS_TARGET,
            generatesMembers = true, hints = emptySet()
        ),
        LOMBOK_SETTER to AnnotationBehavior(
            category = "Lombok", target = CLASS_TARGET,
            generatesMembers = true, hints = emptySet()
        ),
        LOMBOK_BUILDER to AnnotationBehavior(
            category = "Lombok", target = CLASS_TARGET,
            generatesMembers = true, hints = emptySet()
        ),
        LOMBOK_ALL_ARGS to AnnotationBehavior(
            category = "Lombok", target = CLASS_TARGET,
            generatesMembers = true, hints = emptySet()
        ),
        LOMBOK_SLF4J to AnnotationBehavior(
            category = "Lombok", target = CLASS_TARGET,
            generatesMembers = true, hints = emptySet()
        ),

        // ---- Spring ----
        SPRING_REST_CONTROLLER to AnnotationBehavior(
            category = "Spring", target = CLASS_TARGET,
            generatesMembers = false, hints = setOf(ContextHint.REQUEST_MAPPING)
        ),
        SPRING_CONTROLLER to AnnotationBehavior(
            category = "Spring", target = CLASS_TARGET,
            generatesMembers = false, hints = setOf(ContextHint.REQUEST_MAPPING)
        ),
        SPRING_SERVICE to AnnotationBehavior(
            category = "Spring", target = CLASS_TARGET,
            generatesMembers = false, hints = setOf(ContextHint.CONSTRUCTOR_INJECTION)
        ),
        SPRING_REPOSITORY to AnnotationBehavior(
            category = "Spring", target = CLASS_TARGET,
            generatesMembers = false, hints = emptySet()
        ),
        SPRING_COMPONENT to AnnotationBehavior(
            category = "Spring", target = CLASS_TARGET,
            generatesMembers = false, hints = setOf(ContextHint.CONSTRUCTOR_INJECTION)
        ),
        SPRING_CONFIGURATION to AnnotationBehavior(
            category = "Spring", target = CLASS_TARGET,
            generatesMembers = false, hints = setOf(ContextHint.CONFIGURATION_METHOD)
        ),
        SPRING_AUTOWIRED to AnnotationBehavior(
            category = "Spring", target = FIELD_TARGET,
            generatesMembers = false, hints = setOf(ContextHint.INJECTION_POINT)
        ),
        SPRING_REQUEST_MAPPING to AnnotationBehavior(
            category = "Spring", target = METHOD_TARGET,
            generatesMembers = false, hints = setOf(ContextHint.REQUEST_MAPPING)
        ),
        SPRING_GET_MAPPING to AnnotationBehavior(
            category = "Spring", target = METHOD_TARGET,
            generatesMembers = false, hints = setOf(ContextHint.REQUEST_MAPPING)
        ),
        SPRING_POST_MAPPING to AnnotationBehavior(
            category = "Spring", target = METHOD_TARGET,
            generatesMembers = false, hints = setOf(ContextHint.REQUEST_MAPPING)
        ),
        SPRING_PUT_MAPPING to AnnotationBehavior(
            category = "Spring", target = METHOD_TARGET,
            generatesMembers = false, hints = setOf(ContextHint.REQUEST_MAPPING)
        ),
        SPRING_DEL_MAPPING to AnnotationBehavior(
            category = "Spring", target = METHOD_TARGET,
            generatesMembers = false, hints = setOf(ContextHint.REQUEST_MAPPING)
        ),
        SPRING_TRANSACTIONAL to AnnotationBehavior(
            category = "Spring", target = CM_TARGET,
            generatesMembers = false, hints = setOf(ContextHint.TRANSACTIONAL)
        ),
        SPRING_CACHEABLE to AnnotationBehavior(
            category = "Spring", target = METHOD_TARGET,
            generatesMembers = false, hints = setOf(ContextHint.CACHING)
        ),
        SPRING_ASYNC to AnnotationBehavior(
            category = "Spring", target = METHOD_TARGET,
            generatesMembers = false, hints = setOf(ContextHint.ASYNC_EXECUTION)
        ),
        SPRING_SCHEDULED to AnnotationBehavior(
            category = "Spring", target = METHOD_TARGET,
            generatesMembers = false, hints = setOf(ContextHint.SCHEDULED_TASK)
        ),

        // ---- JPA ----
        JPA_ENTITY to AnnotationBehavior(
            category = "JPA", target = CLASS_TARGET,
            generatesMembers = false, hints = setOf(ContextHint.ENTITY_MAPPING)
        ),
        JAVAX_ENTITY to AnnotationBehavior(
            category = "JPA", target = CLASS_TARGET,
            generatesMembers = false, hints = setOf(ContextHint.ENTITY_MAPPING)
        ),
    )

    data class AnnotationBehavior(
        val category: String,            // "Lombok", "Spring", "JPA", "Jackson", "Validation"
        val target: Set<Target>,         // Application targets
        val generatesMembers: Boolean,   // Whether synthetic members are generated
        val hints: Set<ContextHint>      // Completion context hints
    )

    // ==================== Public API ====================

    /**
     * Analyzes annotations on a PSI element and returns the annotation context.
     */
    fun analyzeAnnotations(element: PsiElement): AnnotationContext {
        val clsAnnotations = mutableSetOf<String>()
        val methodAnnotations = mutableSetOf<String>()
        val fieldAnnotations = mutableSetOf<String>()
        val paramAnnotations = mutableSetOf<String>()
        val hints = mutableSetOf<ContextHint>()
        var hasSynthetic = false

        // Class annotations
        val psiClass = PsiTreeUtil.getParentOfType(element, PsiClass::class.java)
        if (psiClass != null) {
            for (anno in psiClass.annotations) {
                val fqn = anno.qualifiedName ?: continue
                clsAnnotations.add(fqn)
                val behavior = knownAnnotations[fqn]
                if (behavior != null) {
                    if (behavior.generatesMembers) hasSynthetic = true
                    hints.addAll(behavior.hints)
                }
            }
        }

        // Method annotations
        val method = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)
        if (method != null) {
            for (anno in method.annotations) {
                val fqn = anno.qualifiedName ?: continue
                methodAnnotations.add(fqn)
                val behavior = knownAnnotations[fqn]
                if (behavior != null) {
                    hints.addAll(behavior.hints)
                }
            }
        }

        // Field annotations
        val field = PsiTreeUtil.getParentOfType(element, PsiField::class.java)
        if (field != null) {
            for (anno in field.annotations) {
                val fqn = anno.qualifiedName ?: continue
                fieldAnnotations.add(fqn)
                val behavior = knownAnnotations[fqn]
                if (behavior != null) {
                    hints.addAll(behavior.hints)
                }
            }
        }

        // Parameter annotations
        val param = PsiTreeUtil.getParentOfType(element, PsiParameter::class.java)
        if (param != null) {
            for (anno in param.annotations) {
                val fqn = anno.qualifiedName ?: continue
                paramAnnotations.add(fqn)
            }
        }

        return AnnotationContext(
            classAnnotations = clsAnnotations,
            methodAnnotations = methodAnnotations,
            fieldAnnotations = fieldAnnotations,
            parameterAnnotations = paramAnnotations,
            hints = hints,
            hasSyntheticMembers = hasSynthetic
        )
    }

    /**
     * Gets the synthetic members generated by class annotations (e.g. Lombok @Data → getters/setters).
     */
    fun getSyntheticMembers(psiClass: PsiClass): List<SyntheticMember> {
        val members = mutableListOf<SyntheticMember>()
        val annotations = psiClass.annotations.mapNotNull { it.qualifiedName }.toSet()

        if (annotations.isEmpty()) return members

        val hasData = LOMBOK_DATA in annotations
        val hasGetter = hasData || LOMBOK_GETTER in annotations
        val hasSetter = hasData || LOMBOK_SETTER in annotations
        val hasBuilder = LOMBOK_BUILDER in annotations
        val hasAllArgs = LOMBOK_ALL_ARGS in annotations
        val hasSlf4j = LOMBOK_SLF4J in annotations

        // Field list (excluding static fields)
        val instanceFields = psiClass.fields.filter { !it.hasModifierProperty(PsiModifier.STATIC) }

        if (hasGetter) {
            for (field in instanceFields) {
                val name = field.name ?: continue
                val type = field.type?.presentableText ?: "Object"
                members.add(SyntheticMember(
                    name = "get${name.replaceFirstChar { it.uppercase() }}",
                    returnType = type,
                    kind = MemberKind.GETTER,
                    description = "Lombok: getter for $name"
                ))
            }
        }

        if (hasSetter) {
            for (field in instanceFields) {
                val name = field.name ?: continue
                val type = field.type?.presentableText ?: "Object"
                members.add(SyntheticMember(
                    name = "set${name.replaceFirstChar { it.uppercase() }}",
                    returnType = "void",
                    parameterTypes = listOf(type),
                    kind = MemberKind.SETTER,
                    description = "Lombok: setter for $name"
                ))
            }
        }

        if (hasData || LOMBOK_EQUALS_HASH in annotations) {
            members.add(SyntheticMember(
                name = "equals",
                returnType = "boolean",
                parameterTypes = listOf("Object"),
                kind = MemberKind.EQUALS,
                description = "Lombok: equals()"
            ))
            members.add(SyntheticMember(
                name = "hashCode",
                returnType = "int",
                kind = MemberKind.HASH_CODE,
                description = "Lombok: hashCode()"
            ))
        }

        if (hasData || LOMBOK_TOSTRING in annotations) {
            members.add(SyntheticMember(
                name = "toString",
                returnType = "String",
                kind = MemberKind.TO_STRING,
                description = "Lombok: toString()"
            ))
        }

        if (hasBuilder) {
            members.add(SyntheticMember(
                name = "builder",
                returnType = psiClass.name?.let { "${it}Builder" } ?: "Builder",
                isStatic = true,
                kind = MemberKind.BUILDER_METHOD,
                description = "Lombok: builder()"
            ))
        }

        if (hasAllArgs) {
            val params = instanceFields.map { it.type?.presentableText ?: "Object" }
            members.add(SyntheticMember(
                name = psiClass.name?.let { "new${it}" } ?: "constructor",
                returnType = psiClass.name ?: "?",
                parameterTypes = params,
                kind = MemberKind.ALL_ARGS_CTOR,
                isStatic = true,
                description = "Lombok: all-args constructor"
            ))
        }

        if (hasSlf4j) {
            members.add(SyntheticMember(
                name = "log",
                returnType = "org.slf4j.Logger",
                isStatic = true,
                kind = MemberKind.LOGGER,
                description = "Lombok: SLF4J log"
            ))
        }

        return members
    }

    /**
     * Generates code pattern suggestions (skeleton snippets) based on the annotation context.
     * Returns a list of CompletionCandidate for display at appropriate positions.
     */
    fun getContextSuggestions(
        element: PsiElement,
        annotationContext: AnnotationContext
    ): List<StaticAnalysisCompletionProvider.CompletionCandidate> {
        val suggestions = mutableListOf<StaticAnalysisCompletionProvider.CompletionCandidate>()
        val psiClass = PsiTreeUtil.getParentOfType(element, PsiClass::class.java) ?: return suggestions
        val className = psiClass.name ?: return suggestions

        for (hint in annotationContext.hints) {
            when (hint) {
                ContextHint.REQUEST_MAPPING -> {
                    // Suggest a REST method skeleton when writing methods in a @RestController class
                    suggestions.add(
                        StaticAnalysisCompletionProvider.CompletionCandidate(
                            displayName = "GetMapping",
                            insertText = "@GetMapping(\"/${className.lowercase().removeSuffix("controller")}\")\npublic ResponseEntity<Object> get${className}() {\n    return ResponseEntity.ok();\n}",
                            typeText = "Spring REST",
                            icon = com.intellij.icons.AllIcons.Nodes.Method,
                            description = "GET mapping method",
                            relevanceScore = 0.85f
                        )
                    )
                    suggestions.add(
                        StaticAnalysisCompletionProvider.CompletionCandidate(
                            displayName = "PostMapping",
                            insertText = "@PostMapping(\"/${className.lowercase().removeSuffix("controller")}\")\npublic ResponseEntity<Object> create${className}(@Valid @RequestBody Object body) {\n    return ResponseEntity.ok(body);\n}",
                            typeText = "Spring REST",
                            icon = com.intellij.icons.AllIcons.Nodes.Method,
                            description = "POST mapping method",
                            relevanceScore = 0.85f
                        )
                    )
                }

                ContextHint.CONFIGURATION_METHOD -> {
                    suggestions.add(
                        StaticAnalysisCompletionProvider.CompletionCandidate(
                            displayName = "@Bean method",
                            insertText = "@Bean\npublic ${className.removeSuffix("Config").removeSuffix("Configuration")} ${className.replaceFirstChar { it.lowercase() }}() {\n    return new ${className.removeSuffix("Config").removeSuffix("Configuration")}();\n}",
                            typeText = "Spring Boot",
                            icon = com.intellij.icons.AllIcons.Nodes.Method,
                            description = "@Bean definition",
                            relevanceScore = 0.85f
                        )
                    )
                }

                ContextHint.TRANSACTIONAL -> {
                    suggestions.add(
                        StaticAnalysisCompletionProvider.CompletionCandidate(
                            displayName = "Transactional",
                            insertText = "@Transactional(rollbackFor = Exception.class)\npublic void execute() {\n    \n}",
                            typeText = "Spring TX",
                            icon = com.intellij.icons.AllIcons.Nodes.Method,
                            description = "Transactional method",
                            relevanceScore = 0.8f
                        )
                    )
                }

                ContextHint.ENTITY_MAPPING -> {
                    suggestions.add(
                        StaticAnalysisCompletionProvider.CompletionCandidate(
                            displayName = "@Column field",
                            insertText = "@Column(name = \"${"$1"}\", nullable = false)\nprivate ${"$2"} ${"$3"};",
                            typeText = "JPA",
                            icon = com.intellij.icons.AllIcons.Nodes.Field,
                            description = "JPA column mapping",
                            relevanceScore = 0.8f
                        )
                    )
                }

                ContextHint.SCHEDULED_TASK -> {
                    suggestions.add(
                        StaticAnalysisCompletionProvider.CompletionCandidate(
                            displayName = "@Scheduled cron",
                            insertText = "@Scheduled(cron = \"0 0/5 * * * ?\")\npublic void scheduledTask() {\n    \n}",
                            typeText = "Spring Scheduling",
                            icon = com.intellij.icons.AllIcons.Nodes.Method,
                            description = "Scheduled task with cron",
                            relevanceScore = 0.8f
                        )
                    )
                }

                ContextHint.INJECTION_POINT -> {
                    suggestions.add(
                        StaticAnalysisCompletionProvider.CompletionCandidate(
                            displayName = "Autowired field",
                            insertText = "@Autowired\nprivate ${"$1"} ${"$2"};",
                            typeText = "Spring DI",
                            icon = com.intellij.icons.AllIcons.Nodes.Field,
                            description = "Field injection",
                            relevanceScore = 0.8f
                        )
                    )
                }

                ContextHint.CONSTRUCTOR_INJECTION -> {
                    suggestions.add(
                        StaticAnalysisCompletionProvider.CompletionCandidate(
                            displayName = "constructor injection",
                            insertText = "private final ${"$1"} ${"$2"};\n\npublic ${className}(final ${"$1"} ${"$2"}) {\n    this.${"$2"} = ${"$2"};\n}",
                            typeText = "Spring DI",
                            icon = com.intellij.icons.AllIcons.Nodes.Method,
                            description = "Constructor-based injection",
                            relevanceScore = 0.85f
                        )
                    )
                }

                else -> { /* Other hints are not handled yet */ }
            }
        }

        return suggestions
    }

    /**
     * Checks whether the current element is at an annotation decoration position (about to write, or writing, the @ symbol).
     */
    fun isAnnotationContext(element: PsiElement, offset: Int): Boolean {
        // Detect the @ symbol or its vicinity
        val text = element.text
        if (text == "@" || text.startsWith("@")) return true

        val parent = element.parent
        if (parent is PsiAnnotation) return true

        // Check whether the previous character is @
        val prev = element.prevSibling
        if (prev?.text == "@") return true

        return false
    }

    /**
     * Gets common annotation names applicable to the current target (class/method/field).
     * Used to recommend completions after @.
     */
    fun getApplicableAnnotations(element: PsiElement): List<String> {
        val parent = PsiTreeUtil.getParentOfType(element, PsiModifierListOwner::class.java) ?: return emptyList()
        val applicable = mutableListOf<String>()

        when (parent) {
            is PsiClass -> {
                applicable.addAll(listOf(
                    "@Data", "@Getter", "@Setter", "@Builder",
                    "@AllArgsConstructor", "@NoArgsConstructor",
                    "@Entity", "@Table",
                    "@Service", "@RestController", "@Controller",
                    "@Repository", "@Component",
                    "@Configuration",
                    "@Slf4j",
                    "@ToString", "@EqualsAndHashCode",
                    "@Value"
                ))
            }
            is PsiMethod -> {
                applicable.addAll(listOf(
                    "@GetMapping", "@PostMapping", "@PutMapping", "@DeleteMapping", "@RequestMapping",
                    "@Transactional", "@Cacheable", "@Async", "@Scheduled",
                    "@Override", "@Deprecated"
                ))
            }
            is PsiField -> {
                applicable.addAll(listOf(
                    "@Autowired", "@Value",
                    "@Column", "@Id", "@GeneratedValue",
                    "@JsonProperty", "@JsonIgnore",
                    "@NotNull", "@NotBlank", "@Size", "@Email",
                    "@Getter", "@Setter"
                ))
            }
            is PsiParameter -> {
                applicable.addAll(listOf(
                    "@PathVariable", "@RequestParam", "@RequestBody",
                    "@NotNull", "@NotBlank", "@Size", "@Valid",
                    "@Nullable"
                ))
            }
        }

        return applicable
    }
}
