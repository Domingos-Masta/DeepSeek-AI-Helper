package com.deepseek.plugin.completion

import com.intellij.openapi.diagnostic.Logger
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil

/**
 * Comment-aware completion engine.
 *
 * Analyzes the comment content the user wrote before the caret, understands the code intent, and automatically recommends matching code templates.
 *
 * [Workflow]
 * 1. Detect whether there is a comment line before the caret (line, block, or documentation comment)
 * 2. Extract keywords and intent from the comment text
 * 3. Match against predefined code pattern templates
 * 4. Return the matching code completion candidates
 *
 * [Supported scenarios]
 * - A comment followed by a blank line with the caret on the blank line → generate code described by the comment
 * - Completing directly at the end of a comment line → append code after the comment
 * - Documentation comments before a class/method/field → understand intent from the context
 */
class CommentAwareCompletion {

    companion object {
        private val LOG = Logger.getInstance(CommentAwareCompletion::class.java)

        // ==================== Pattern constants ====================

        // ---- Getter / Setter ----
        private val PATTERN_GETTER     = Regex(
            "(?:getter|get|obtain|acquire|fetch).*?(?:for|variable|field|property)?\\s*(\\S+)" +
            "|(\\S+).*?(?:of)?(?:getter|get|obtain|acquire|fetch)" +
            "|obtain.*?(\\S+).*?(?:field|property|variable)",
            RegexOption.IGNORE_CASE
        )
        private val PATTERN_SETTER     = Regex(
            "(?:setter|set|assign|write|configure).*?(?:for|variable|field|property)?\\s*(\\S+)" +
            "|(\\S+).*?(?:of)?(?:setter|set|assign|write)" +
            "|assign.*?(\\S+).*?(?:field|property|variable)",
            RegexOption.IGNORE_CASE
        )
        private val PATTERN_GETSET     = Regex(
            "(?:getter\\s*(?:and|&)?\\s*setter|get/set|read-write|getter and setter)" +
            "|both.*?(?:get|read|retrieve).*?(?:set|write|assign)" +
            "|all fields.*?(?:getter|setter|read-write)",
            RegexOption.IGNORE_CASE
        )

        // ---- Constructor ----
        private val PATTERN_ALL_ARGS_CTOR = Regex("(all.arg|all-args|all parameters|full constructor)", RegexOption.IGNORE_CASE)
        private val PATTERN_NO_ARGS_CTOR  = Regex("(no.arg|no-args|empty constructor|default constructor)", RegexOption.IGNORE_CASE)
        private val PATTERN_CTOR          = Regex("(constructor|construct|class constructor|constructor function|all-args constructor|no-args constructor)", RegexOption.IGNORE_CASE)

        // ---- Design Patterns ----
        private val PATTERN_SINGLETON   = Regex("(singleton|singleton pattern)", RegexOption.IGNORE_CASE)
        private val PATTERN_BUILDER     = Regex("(builder|builder pattern)", RegexOption.IGNORE_CASE)
        private val PATTERN_FACTORY     = Regex("(factory|factory pattern|factory method)", RegexOption.IGNORE_CASE)
        private val PATTERN_STRATEGY    = Regex("(strategy|strategy pattern)", RegexOption.IGNORE_CASE)
        private val PATTERN_OBSERVER    = Regex("(observer|observer pattern|listener|subscription)", RegexOption.IGNORE_CASE)
        private val PATTERN_PROXY       = Regex("(proxy|proxy pattern)", RegexOption.IGNORE_CASE)
        private val PATTERN_TEMPLATE    = Regex("(template|template method)", RegexOption.IGNORE_CASE)

        // ---- CRUD ----
        private val PATTERN_FIND_BY_ID  = Regex(
            "(?:find|look up|query|search|fetch).*?(?:by|by\\s+id|according to|via)" +
            "|(?:according to|via).*?(?:id|primary key).*?(?:find|look up|query|search|according to|fetch)" +
            "|by.*id.*(?:look up|query|search|fetch)",
            RegexOption.IGNORE_CASE
        )
        private val PATTERN_FIND_ALL    = Regex(
            "(?:findAll|find.all|query all|all|list.all|fetch all|batch query|all records|all data)",
            RegexOption.IGNORE_CASE
        )
        private val PATTERN_SAVE        = Regex(
            "(?:save|create|insert|add|write|new)",
            RegexOption.IGNORE_CASE
        )
        private val PATTERN_UPDATE      = Regex(
            "(?:update|modify|edit|revise)",
            RegexOption.IGNORE_CASE
        )
        private val PATTERN_DELETE      = Regex(
            "(?:delete|remove).*?(?:by|by\\s+id|according to|via)?" +
            "|(?:according to|via).*?(?:id|primary key).*?(?:delete|remove)" +
            "|by.*id.*(?:delete|remove)",
            RegexOption.IGNORE_CASE
        )
        private val PATTERN_PAGE        = Regex(
            "(?:page|paging|pagination|pageable|page query|list paging)",
            RegexOption.IGNORE_CASE
        )

        // ---- Common Infrastructure ----
        private val PATTERN_LOGGER      = Regex("(logger|log|logging|record log|write log|print log)", RegexOption.IGNORE_CASE)
        private val PATTERN_AUTOWIRED   = Regex("(autowired|inject|injection|dependency injection|wire|auto-inject)", RegexOption.IGNORE_CASE)
        private val PATTERN_MAPPER      = Regex("(mapper|map|convert|mapping|entity.*dto|dto.*entity|object conversion|type conversion|converter)", RegexOption.IGNORE_CASE)
        private val PATTERN_VALIDATE    = Regex("(validate|check|validation|parameter validation|data validation)", RegexOption.IGNORE_CASE)
        private val PATTERN_CONFIG      = Regex("(config|configuration|@Bean|bean|register)", RegexOption.IGNORE_CASE)
        private val PATTERN_CACHE       = Regex("(cache|cacheable|@Cacheable)", RegexOption.IGNORE_CASE)
        private val PATTERN_ASYNC       = Regex("(async|@Async|parallel)", RegexOption.IGNORE_CASE)
        private val PATTERN_SCHEDULE    = Regex("(schedule|scheduled|cron|@Scheduled|task)", RegexOption.IGNORE_CASE)
        private val PATTERN_THREAD_SAFE = Regex("(thread.safe|thread-safe|synchronized|concurrency-safe|lock)", RegexOption.IGNORE_CASE)
        private val PATTERN_UTILITY     = Regex("(util|utility|helper|tool|auxiliary|static method)", RegexOption.IGNORE_CASE)
        private val PATTERN_DTO         = Regex("(dto|vo|data transfer|view object|data.transfer)", RegexOption.IGNORE_CASE)
        private val PATTERN_REST_API    = Regex("(rest|api|endpoint|interface|REST|web|controller)", RegexOption.IGNORE_CASE)
        private val PATTERN_EXCEPTION   = Regex("(exception|error|custom exception)", RegexOption.IGNORE_CASE)
        private val PATTERN_ENUM        = Regex("(enum|constant|enum class)", RegexOption.IGNORE_CASE)
        private val PATTERN_TEST        = Regex("(test|unit.test|unit test|@Test)", RegexOption.IGNORE_CASE)
        private val PATTERN_STREAM      = Regex("(stream|lambda|functional|filter|map|collect)", RegexOption.IGNORE_CASE)
    }

    // ==================== Pattern definitions ====================

    /** Comment pattern: matching words → code template generator function */
    private val patterns: List<CommentPattern> = listOf(
        // ---- Logger ----
        CommentPattern(PATTERN_LOGGER) { match, ctx ->
            val loggerType = if (match.value.contains("log4j", ignoreCase = true)) "org.apache.log4j.Logger" else "org.slf4j.Logger"
            val factoryMethod = if (match.value.contains("log4j", ignoreCase = true)) "Logger.getLogger" else "LoggerFactory.getLogger"
            listOf(CodeSuggestion(
                name = "Logger",
                text = "private static final $loggerType log = $factoryMethod(${ctx.className}.class);",
                type = "logging",
                description = "SLF4J Logger"
            ))
        },

        // ---- Getter ----
        CommentPattern(PATTERN_GETTER) { match, ctx ->
            // The field name may be in groups[1], groups[2], or groups[3], depending on which branch matched
            val fieldName = match.groups[1]?.value
                ?: match.groups[2]?.value
                ?: match.groups[3]?.value
                ?: return@CommentPattern emptyList()
            val fieldType = ctx.findFieldType(fieldName) ?: "String"
            listOf(CodeSuggestion(
                name = "get$fieldName",
                text = "public $fieldType get${fieldName.replaceFirstChar { it.uppercase() }}() {\n    return $fieldName;\n}",
                type = "getter",
                description = "Getter for $fieldName"
            ))
        },

        // ---- Setter ----
        CommentPattern(PATTERN_SETTER) { match, ctx ->
            // The field name may be in groups[1], groups[2], or groups[3]
            val fieldName = match.groups[1]?.value
                ?: match.groups[2]?.value
                ?: match.groups[3]?.value
                ?: return@CommentPattern emptyList()
            val fieldType = ctx.findFieldType(fieldName) ?: "String"
            listOf(CodeSuggestion(
                name = "set$fieldName",
                text = "public void set${fieldName.replaceFirstChar { it.uppercase() }}($fieldType $fieldName) {\n    this.$fieldName = $fieldName;\n}",
                type = "setter",
                description = "Setter for $fieldName"
            ))
        },

        // ---- Getter + Setter ----
        CommentPattern(PATTERN_GETSET) { _, ctx ->
            if (ctx.psiClass == null) return@CommentPattern emptyList()
            val fields = ctx.psiClass!!.fields.filter { !it.hasModifierProperty(PsiModifier.STATIC) }
            fields.flatMap { f ->
                val name = f.name ?: return@flatMap emptyList()
                val type = f.type?.presentableText ?: "String"
                val capName = name.replaceFirstChar { it.uppercase() }
                listOf(
                    CodeSuggestion(name = "get$capName", text = "public $type get$capName() {\n    return $name;\n}", type = "getter"),
                    CodeSuggestion(name = "set$capName", text = "public void set$capName($type $name) {\n    this.$name = $name;\n}", type = "setter")
                )
            }
        },

        // ---- All-args Constructor ----
        CommentPattern(PATTERN_ALL_ARGS_CTOR) { _, ctx ->
            if (ctx.psiClass == null) return@CommentPattern emptyList()
            val fields = ctx.psiClass!!.fields.filter { !it.hasModifierProperty(PsiModifier.STATIC) }
            if (fields.isEmpty()) return@CommentPattern emptyList()
            val params = fields.joinToString(", ") { "${it.type?.presentableText ?: "Object"} ${it.name}" }
            val assignments = fields.joinToString("\n    ") { "this.${it.name} = ${it.name};" }
            listOf(CodeSuggestion(
                name = "AllArgsConstructor",
                text = "public ${ctx.className}($params) {\n    $assignments\n}",
                type = "constructor",
                description = "All-args constructor"
            ))
        },

        // ---- No-args Constructor ----
        CommentPattern(PATTERN_NO_ARGS_CTOR) { _, ctx ->
            listOf(CodeSuggestion(
                name = "NoArgsConstructor",
                text = "public ${ctx.className}() {\n    \n}",
                type = "constructor",
                description = "No-arg constructor"
            ))
        },

        // ---- Constructor (generic) ----
        CommentPattern(PATTERN_CTOR) { _, ctx ->
            if (ctx.psiClass == null) return@CommentPattern emptyList()
            val fields = ctx.psiClass!!.fields.filter { !it.hasModifierProperty(PsiModifier.STATIC) }
            if (fields.isEmpty()) return@CommentPattern emptyList()
            val params = fields.joinToString(", ") { "${it.type?.presentableText ?: "Object"} ${it.name}" }
            val assignments = fields.joinToString("\n    ") { "this.${it.name} = ${it.name};" }
            listOf(CodeSuggestion(
                name = "Constructor",
                text = "public ${ctx.className}($params) {\n    $assignments\n}",
                type = "constructor",
                description = "Constructor with all fields"
            ))
        },

        // ---- Singleton ----
        CommentPattern(PATTERN_SINGLETON) { _, ctx ->
            listOf(CodeSuggestion(
                name = "Singleton",
                text = "private static ${ctx.className} instance;\n\nprivate ${ctx.className}() {}\n\npublic static ${ctx.className} getInstance() {\n    if (instance == null) {\n        instance = new ${ctx.className}();\n    }\n    return instance;\n}",
                type = "design-pattern",
                description = "Singleton pattern (lazy)"
            ), CodeSuggestion(
                name = "Singleton (enum)",
                text = "enum ${ctx.className} {\n    INSTANCE;\n    \n}",
                type = "design-pattern",
                description = "Singleton pattern (enum)"
            ))
        },

        // ---- Builder ----
        CommentPattern(PATTERN_BUILDER) { _, ctx ->
            val innerBuilder = """
public static class Builder {
    ${(ctx.psiClass?.fields?.filter { !it.hasModifierProperty(PsiModifier.STATIC) }?.joinToString("\n    ") { "private ${it.type?.presentableText ?: "Object"} ${it.name};" } ?: "")}

    public Builder() {}

    ${(ctx.psiClass?.fields?.filter { !it.hasModifierProperty(PsiModifier.STATIC) }?.joinToString("\n    ") { f ->
        val name = f.name ?: return@joinToString ""
        val type = f.type?.presentableText ?: "Object"
        "public Builder $name($type $name) {\n        this.$name = $name;\n        return this;\n    }"
    } ?: "")}

    public ${ctx.className} build() {
        return new ${ctx.className}(this);
    }
}
""".trimIndent()
            listOf(CodeSuggestion(
                name = "Builder",
                text = innerBuilder,
                type = "design-pattern",
                description = "Builder pattern (inner class)"
            ))
        },

        // ---- Factory ----
        CommentPattern(PATTERN_FACTORY) { _, ctx ->
            listOf(CodeSuggestion(
                name = "Factory Method",
                text = "public static ${ctx.className} create() {\n    return new ${ctx.className}();\n}",
                type = "design-pattern",
                description = "Simple factory method"
            ))
        },

        // ---- CRUD: find by id ----
        CommentPattern(PATTERN_FIND_BY_ID) { _, ctx ->
            val entityName = ctx.className.removeSuffix("Repository").removeSuffix("Dao").removeSuffix("Service")
                .removeSuffix("Controller").removeSuffix("Impl")
            listOf(CodeSuggestion(
                name = "findById",
                text = "public $entityName findById(Long id) {\n    return ${entityName.replaceFirstChar { it.lowercase() }}Repository.findById(id)\n        .orElseThrow(() -> new RuntimeException(\"$entityName not found: \" + id));\n}",
                type = "crud",
                description = "Find by ID"
            ))
        },

        // ---- CRUD: findAll ----
        CommentPattern(PATTERN_FIND_ALL) { _, ctx ->
            val entityName = ctx.className.removeSuffix("Repository").removeSuffix("Dao").removeSuffix("Service")
            listOf(CodeSuggestion(
                name = "findAll",
                text = "public List<$entityName> findAll() {\n    return ${entityName.replaceFirstChar { it.lowercase() }}Repository.findAll();\n}",
                type = "crud",
                description = "Find all"
            ))
        },

        // ---- CRUD: save ----
        CommentPattern(PATTERN_SAVE) { _, ctx ->
            val entityName = ctx.className.removeSuffix("Repository").removeSuffix("Dao").removeSuffix("Service")
            listOf(CodeSuggestion(
                name = "save",
                text = "public $entityName save($entityName entity) {\n    return ${entityName.replaceFirstChar { it.lowercase() }}Repository.save(entity);\n}",
                type = "crud",
                description = "Save entity"
            ))
        },

        // ---- CRUD: update ----
        CommentPattern(PATTERN_UPDATE) { _, ctx ->
            val entityName = ctx.className.removeSuffix("Repository").removeSuffix("Dao").removeSuffix("Service")
            listOf(CodeSuggestion(
                name = "update",
                text = "public $entityName update(Long id, $entityName entity) {\n    $entityName existing = findById(id);\n    // copy properties\n    return ${entityName.replaceFirstChar { it.lowercase() }}Repository.save(existing);\n}",
                type = "crud",
                description = "Update entity"
            ))
        },

        // ---- CRUD: delete ----
        CommentPattern(PATTERN_DELETE) { _, ctx ->
            val entityName = ctx.className.removeSuffix("Repository").removeSuffix("Dao").removeSuffix("Service")
            listOf(CodeSuggestion(
                name = "deleteById",
                text = "public void deleteById(Long id) {\n    ${entityName.replaceFirstChar { it.lowercase() }}Repository.deleteById(id);\n}",
                type = "crud",
                description = "Delete by ID"
            ))
        },

        // ---- CRUD: pagination ----
        CommentPattern(PATTERN_PAGE) { _, ctx ->
            val entityName = ctx.className.removeSuffix("Repository").removeSuffix("Dao").removeSuffix("Service")
            listOf(CodeSuggestion(
                name = "findAll(Pageable)",
                text = "public Page<$entityName> findAll(Pageable pageable) {\n    return ${entityName.replaceFirstChar { it.lowercase() }}Repository.findAll(pageable);\n}",
                type = "crud",
                description = "Paginated query"
            ))
        },

        // ---- Autowired / Injection ----
        CommentPattern(PATTERN_AUTOWIRED) { _, ctx ->
            listOf(CodeSuggestion(
                name = "@Autowired",
                text = "@Autowired\nprivate ${"$1"} ${"$2"};",
                type = "spring",
                description = "Autowired field injection"
            ), CodeSuggestion(
                name = "constructor injection",
                text = "private final ${"$1"} ${"$2"};\n\npublic ${ctx.className}(final ${"$1"} ${"$2"}) {\n    this.${"$2"} = ${"$2"};\n}",
                type = "spring",
                description = "Constructor injection"
            ))
        },

        // ---- Mapper / Convert ----
        CommentPattern(PATTERN_MAPPER) { _, ctx ->
            val sourceType = if (ctx.className.contains("DTO", ignoreCase = true) || ctx.className.contains("Vo", ignoreCase = true))
                ctx.className.removeSuffix("DTO").removeSuffix("Vo") else "${ctx.className}DTO"
            val targetType = if (sourceType == ctx.className) "${ctx.className}DTO" else ctx.className
            listOf(CodeSuggestion(
                name = "toDTO",
                text = "public $targetType to${targetType.replaceFirstChar { it.uppercase() }}($sourceType source) {\n    $targetType target = new $targetType();\n    // TODO: map fields\n    return target;\n}",
                type = "conversion",
                description = "Entity → DTO converter"
            ), CodeSuggestion(
                name = "toEntity",
                text = "public $sourceType to${sourceType.replaceFirstChar { it.uppercase() }}($targetType dto) {\n    $sourceType entity = new $sourceType();\n    // TODO: map fields\n    return entity;\n}",
                type = "conversion",
                description = "DTO → Entity converter"
            ))
        },

        // ---- Validate ----
        CommentPattern(PATTERN_VALIDATE) { _, ctx ->
            listOf(CodeSuggestion(
                name = "validate",
                text = "public void validate(${"$1"} input) {\n    if (input == null) {\n        throw new IllegalArgumentException(\"input must not be null\");\n    }\n    // TODO: add validation rules\n}",
                type = "validation",
                description = "Validation method"
            ))
        },

        // ---- Configuration / @Bean ----
        CommentPattern(PATTERN_CONFIG) { _, ctx ->
            listOf(CodeSuggestion(
                name = "@Bean",
                text = "@Bean\npublic ${"$1"} ${"$2"}() {\n    return new ${"$1"}();\n}",
                type = "spring",
                description = "Bean definition"
            ))
        },

        // ---- Cache ----
        CommentPattern(PATTERN_CACHE) { _, ctx ->
            listOf(CodeSuggestion(
                name = "@Cacheable",
                text = "@Cacheable(value = \"${"$1"}\", key = \"#${"$2"}\")\npublic ${"$3"} ${"$4"}(${"$5"} ${"$6"}) {\n    return null;\n}",
                type = "spring",
                description = "Cachable method"
            ))
        },

        // ---- Async ----
        CommentPattern(PATTERN_ASYNC) { _, ctx ->
            listOf(CodeSuggestion(
                name = "@Async",
                text = "@Async\npublic void executeAsync() {\n    // TODO: async logic\n}",
                type = "spring",
                description = "Async method"
            ))
        },

        // ---- Scheduled ----
        CommentPattern(PATTERN_SCHEDULE) { _, ctx ->
            listOf(CodeSuggestion(
                name = "@Scheduled(cron)",
                text = "@Scheduled(cron = \"0 0/5 * * * ?\")\npublic void scheduledTask() {\n    // TODO: scheduled logic\n}",
                type = "spring",
                description = "Scheduled task"
            ), CodeSuggestion(
                name = "@Scheduled(fixedRate)",
                text = "@Scheduled(fixedRate = 5000)\npublic void scheduledTask() {\n    // TODO: scheduled logic\n}",
                type = "spring",
                description = "Scheduled task (fixed rate)"
            ))
        },

        // ---- Thread-safe ----
        CommentPattern(PATTERN_THREAD_SAFE) { _, ctx ->
            listOf(CodeSuggestion(
                name = "synchronized",
                text = "public synchronized void execute() {\n    // TODO: thread-safe logic\n}",
                type = "concurrency",
                description = "Synchronized method"
            ), CodeSuggestion(
                name = "ReentrantLock",
                text = "private final java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();\n\npublic void execute() {\n    lock.lock();\n    try {\n        // TODO: thread-safe logic\n    } finally {\n        lock.unlock();\n    }\n}",
                type = "concurrency",
                description = "ReentrantLock"
            ))
        },

        // ---- Utility / Helper ----
        CommentPattern(PATTERN_UTILITY) { _, ctx ->
            listOf(CodeSuggestion(
                name = "Static utility",
                text = "private ${ctx.className}() {}\n\npublic static ${"$1"} ${"$2"}(${"$3"} ${"$4"}) {\n    // TODO: utility logic\n    return null;\n}",
                type = "utility",
                description = "Static utility method"
            ))
        },

        // ---- DTO / VO ----
        CommentPattern(PATTERN_DTO) { _, ctx ->
            listOf(CodeSuggestion(
                name = "DTO class",
                text = "public class ${ctx.className}DTO {\n    \n}",
                type = "dto",
                description = "Inner DTO class"
            ), CodeSuggestion(
                name = "VO class",
                text = "public class ${ctx.className}VO {\n    \n}",
                type = "dto",
                description = "Inner VO class"
            ))
        },

        // ---- REST API ----
        CommentPattern(PATTERN_REST_API) { _, ctx ->
            listOf(CodeSuggestion(
                name = "GET endpoint",
                text = "@GetMapping\npublic ResponseEntity<Object> getAll() {\n    return ResponseEntity.ok();\n}",
                type = "rest",
                description = "GET endpoint"
            ), CodeSuggestion(
                name = "POST endpoint",
                text = "@PostMapping\npublic ResponseEntity<Object> create(@Valid @RequestBody Object body) {\n    return ResponseEntity.ok(body);\n}",
                type = "rest",
                description = "POST endpoint"
            ), CodeSuggestion(
                name = "DELETE endpoint",
                text = "@DeleteMapping(\"/{id}\")\npublic ResponseEntity<Void> delete(@PathVariable Long id) {\n    // TODO: delete logic\n    return ResponseEntity.noContent().build();\n}",
                type = "rest",
                description = "DELETE endpoint"
            ))
        },

        // ---- Custom Exception ----
        CommentPattern(PATTERN_EXCEPTION) { _, ctx ->
            listOf(CodeSuggestion(
                name = "Exception class",
                text = "public class ${ctx.className}Exception extends RuntimeException {\n    public ${ctx.className}Exception(String message) {\n        super(message);\n    }\n    public ${ctx.className}Exception(String message, Throwable cause) {\n        super(message, cause);\n    }\n}",
                type = "exception",
                description = "Custom exception"
            ))
        },

        // ---- Enum ----
        CommentPattern(PATTERN_ENUM) { _, ctx ->
            listOf(CodeSuggestion(
                name = "Enum",
                text = "public enum ${ctx.className} {\n    VALUE1,\n    VALUE2,\n    VALUE3;\n}",
                type = "enum",
                description = "Enum class"
            ))
        },

        // ---- Test ----
        CommentPattern(PATTERN_TEST) { _, ctx ->
            listOf(CodeSuggestion(
                name = "@Test",
                text = "@Test\npublic void test${"$1"}() {\n    // given\n    \n    // when\n    \n    // then\n    \n}",
                type = "test",
                description = "JUnit test method"
            ))
        },

        // ---- Stream / Lambda ----
        CommentPattern(PATTERN_STREAM) { _, ctx ->
            listOf(CodeSuggestion(
                name = "Stream filter",
                text = "list.stream()\n    .filter(item -> item != null)\n    .map(item -> item.toString())\n    .collect(Collectors.toList());",
                type = "stream",
                description = "Stream filter/map/collect"
            ))
        },
    )

    // ==================== Type definitions ====================

    /** The pattern matched by a comment */
    data class CommentPattern(
        val regex: Regex,
        val generator: (MatchResult, CommentContext) -> List<CodeSuggestion>
    )

    /** Code suggestion */
    data class CodeSuggestion(
        val name: String,
        val text: String,
        val type: String,
        val description: String = ""
    )

    /** Comment analysis context */
    data class CommentContext(
        val commentText: String,
        val psiClass: PsiClass?,
        val psiMethod: PsiMethod?,
        val psiField: PsiField?,
        val project: com.intellij.openapi.project.Project
    ) {
        val className: String get() = psiClass?.name ?: "Unknown"

        /** Finds a field type in the PSI class */
        fun findFieldType(fieldName: String): String? {
            val cls = psiClass ?: return null
            // Direct match
            val field = cls.fields.find { it.name.equals(fieldName, ignoreCase = true) }
            if (field != null) return field.type?.presentableText
            // Fuzzy match
            val fuzzy = cls.fields.find { it.name?.contains(fieldName, ignoreCase = true) == true }
            return fuzzy?.type?.presentableText
        }
    }

    // ==================== Core logic ====================

    /**
     * Analyzes the comment text before the caret and returns matching code suggestions.
     */
    fun analyzeComments(element: PsiElement, offset: Int): List<CodeSuggestion> {
        val commentText = extractCommentBefore(element, offset) ?: return emptyList()
        if (commentText.isBlank()) return emptyList()

        val ctx = buildContext(element)
        LOG.debug("Comment detected: \"$commentText\" in class ${ctx.className}")

        val allSuggestions = mutableListOf<CodeSuggestion>()

        for (pattern in patterns) {
            val match = pattern.regex.find(commentText)
            if (match != null) {
                try {
                    val suggestions = pattern.generator(match, ctx)
                    allSuggestions.addAll(suggestions)
                } catch (e: Exception) {
                    LOG.warn("Comment pattern '${pattern.regex}' generated error", e)
                }
            }
        }

        // If patterns matched but produced no suggestions (e.g. getter but the field does not exist), return empty
        return allSuggestions
    }

    /**
     * Checks whether the caret is after a comment (a comment line followed immediately by a blank line, with the caret on the blank line).
     */
    fun isAfterComment(element: PsiElement, offset: Int): Boolean {
        val doc = (element.containingFile as? PsiJavaFile)?.viewProvider?.document ?: return false
        val lineNum = doc.getLineNumber(offset)
        if (lineNum <= 0) return false

        // Check whether the previous line is a comment
        for (i in lineNum - 1 downTo maxOf(0, lineNum - 5)) {
            val lineStart = doc.getLineStartOffset(i)
            val lineEnd = doc.getLineEndOffset(i)
            val lineText = doc.getText(com.intellij.openapi.util.TextRange(lineStart, lineEnd)).trim()
            if (lineText.isEmpty()) continue // Skip blank lines
            if (lineText.startsWith("//") || lineText.startsWith("*") || lineText.startsWith("/*") || lineText.startsWith("/**")) {
                return true
            }
            // Stop searching upward when a non-comment, non-blank line is encountered
            return false
        }
        return false
    }

    /**
     * Gets the last block of comment text before the caret.
     * Supports: line comments, block comments, and documentation comments
     */
    private fun extractCommentBefore(element: PsiElement, offset: Int): String? {
        val file = element.containingFile
        val doc = (file as? PsiJavaFile)?.viewProvider?.document ?: return null
        val lineNum = doc.getLineNumber(offset)
        if (lineNum <= 0) return null

        val sb = StringBuilder()

        // Scan upward from the caret line, collecting comment content
        for (i in lineNum - 1 downTo 0) {
            val lineStart = doc.getLineStartOffset(i)
            val lineEnd = doc.getLineEndOffset(i)
            val lineText = doc.getText(com.intellij.openapi.util.TextRange(lineStart, lineEnd)).trim()

            if (lineText.isEmpty()) {
                // On a blank line: continue if it is the first blank line, otherwise stop
                if (sb.isNotEmpty()) break
                continue
            }

            if (lineText.startsWith("//")) {
                sb.insert(0, lineText.removePrefix("//").trim() + " ")
            } else if (lineText.startsWith("*/") || lineText.startsWith("**/")) {
                // End of a block comment; collect the block contents
                collectBlockComment(doc, i, sb)
                break
            } else if (lineText.startsWith("*")) {
                // Javadoc or middle line of a block comment
                sb.insert(0, lineText.removePrefix("*").trim() + " ")
            } else if (lineText.startsWith("/*") || lineText.startsWith("/**")) {
                // Start line of a block comment
                val content = lineText.removePrefix("/**").removePrefix("/*").trimEnd('*').trimEnd('/').trim()
                if (content.isNotEmpty()) sb.insert(0, content + " ")
                break
            } else {
                // Non-comment code line; stop
                break
            }
        }

        val result = sb.toString().trim()
        return result.ifEmpty { null }
    }

    /** Collects the full contents of a block comment / documentation comment */
    private fun collectBlockComment(doc: com.intellij.openapi.editor.Document, endLine: Int, sb: StringBuilder) {
        for (i in endLine downTo 0) {
            val lineStart = doc.getLineStartOffset(i)
            val lineEnd = doc.getLineEndOffset(i)
            val lineText = doc.getText(com.intellij.openapi.util.TextRange(lineStart, lineEnd)).trim()

            if (lineText.startsWith("/*") || lineText.startsWith("/**")) {
                val content = lineText.removePrefix("/**").removePrefix("/*").trimEnd('*').trimEnd('/').trim()
                if (content.isNotEmpty()) sb.insert(0, content + " ")
                break
            }
            if (lineText.startsWith("*")) {
                sb.insert(0, lineText.removePrefix("*").trim() + " ")
            }
        }
    }

    /** Builds the comment context (extracts class/method/field information from the PSI element) */
    private fun buildContext(element: PsiElement): CommentContext {
        return CommentContext(
            commentText = "",
            psiClass = PsiTreeUtil.getParentOfType(element, PsiClass::class.java),
            psiMethod = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java),
            psiField = PsiTreeUtil.getParentOfType(element, PsiField::class.java),
            project = element.project
        )
    }

    /**
     * Converts CodeSuggestion to CompletionCandidate.
     */
    fun toCompletionCandidates(suggestions: List<CodeSuggestion>): List<StaticAnalysisCompletionProvider.CompletionCandidate> {
        return suggestions.map { s ->
            StaticAnalysisCompletionProvider.CompletionCandidate(
                displayName = s.name,
                insertText = s.text,
                typeText = s.type,
                icon = when (s.type) {
                    "getter", "setter" -> com.intellij.icons.AllIcons.Nodes.PropertyRead
                    "constructor" -> com.intellij.icons.AllIcons.Nodes.Method
                    "design-pattern" -> com.intellij.icons.AllIcons.Nodes.Class
                    "crud" -> com.intellij.icons.AllIcons.Nodes.Method
                    "spring" -> com.intellij.icons.AllIcons.Nodes.Method
                    "conversion" -> com.intellij.icons.AllIcons.Nodes.Method
                    "validation" -> com.intellij.icons.AllIcons.Nodes.Method
                    "logging" -> com.intellij.icons.AllIcons.Nodes.Variable
                    "concurrency" -> com.intellij.icons.AllIcons.Nodes.Method
                    "rest" -> com.intellij.icons.AllIcons.Nodes.Method
                    "test" -> com.intellij.icons.AllIcons.Nodes.Method
                    "stream" -> com.intellij.icons.AllIcons.Nodes.Method
                    "dto" -> com.intellij.icons.AllIcons.Nodes.Class
                    "exception" -> com.intellij.icons.AllIcons.Nodes.Class
                    "enum" -> com.intellij.icons.AllIcons.Nodes.Enum
                    "utility" -> com.intellij.icons.AllIcons.Nodes.Method
                    else -> com.intellij.icons.AllIcons.Nodes.Variable
                },
                description = s.description.ifEmpty { s.type },
                relevanceScore = 0.95f // Comment matches have high priority
            )
        }
    }
}
