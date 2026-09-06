package com.latenighthack.basekit.viewmodel.codegen

import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import java.io.OutputStream

fun OutputStream.writeln(s: String = "") {
    write(s.encodeToByteArray())
    write("\n".encodeToByteArray())
}

fun KSAnnotation.qualifiedName(): String? =
    annotationType.resolve().declaration.qualifiedName?.asString()

fun KSAnnotated.hasAnnotation(fqn: String): Boolean =
    annotations.any { it.qualifiedName() == fqn }

/** Reads a String-valued annotation argument, or null if the annotation/argument is absent. */
fun KSAnnotated.stringArgument(annotationFqn: String, argumentName: String): String? =
    annotations.firstOrNull { it.qualifiedName() == annotationFqn }
        ?.arguments?.firstOrNull { it.name?.asString() == argumentName }
        ?.value as? String

/**
 * The Objective-C/Swift name Kotlin/Native exports this class under: nested classes are flattened by
 * prepending the enclosing class chain (e.g. `HomeViewModel.State` -> `HomeViewModelState`), while a
 * top-level class keeps its own name.
 */
fun KSClassDeclaration.swiftExportName(): String {
    val parts = mutableListOf(simpleName.asString())
    var parent = parentDeclaration
    while (parent is KSClassDeclaration) {
        parts.add(0, parent.simpleName.asString())
        parent = parent.parentDeclaration
    }
    return parts.joinToString("")
}

/** Splits an identifier into its words, e.g. "onOpenDetail" -> [on, Open, Detail]. */
fun String.camelWords(): List<String> =
    split(Regex("(?<!(^|[A-Z]))(?=[A-Z])|(?<!^)(?=[A-Z][a-z])"))

/**
 * The State noun a mutator targets: a leading `set` (followed by an uppercase letter) is stripped and
 * the first letter lowercased, so `setName` -> `name`; a name without that prefix is returned with its
 * first letter lowercased (`name` -> `name`). The Swift generator pairs a mutator with the State
 * property whose name equals this noun to synthesize a two-way `Binding`.
 */
fun mutatorNoun(name: String): String {
    val stripped = if (name.length > 3 && name.startsWith("set") && name[3].isUpperCase()) {
        name.substring(3)
    } else {
        name
    }
    return stripped.replaceFirstChar { it.lowercase() }
}

/** How a Kotlin type surfaces in the generated Swift wrappers: its Swift name and a zero-value default. */
data class SwiftType(val type: String, val default: String)

/**
 * Maps a Kotlin type's qualified name to its Swift wrapper representation (non-primitives erase to
 * `AnyObject?`). For a `List<E>`/`MutableList<E>` state property, pass the element's qualified name as
 * [elementQualifiedName]: a `List<String>` surfaces as Swift `[String]` (Kotlin/Native bridges it to
 * `NSArray<NSString>`); lists of any other element type keep the erased `AnyObject?` mapping.
 */
fun swiftType(qualifiedName: String, nullable: Boolean = false, elementQualifiedName: String? = null): SwiftType {
    if ((qualifiedName == "kotlin.collections.List" || qualifiedName == "kotlin.collections.MutableList") &&
        elementQualifiedName == "kotlin.String"
    ) {
        return if (nullable) SwiftType("[String]?", "nil") else SwiftType("[String]", "[]")
    }
    // A nullable primitive cannot be a Swift value type on an @objc property, so it is boxed to the
    // SKIE/Kotlin-Native NSNumber bridge (KotlinInt?, KotlinBoolean?, …); a nullable String becomes a
    // Swift optional String?. Non-primitives already erase to AnyObject? (Kotlin/Native drops the
    // exported generic), which is optional regardless.
    if (nullable) {
        return when (qualifiedName) {
            "kotlin.Int" -> SwiftType("KotlinInt?", "nil")
            "kotlin.Long" -> SwiftType("KotlinLong?", "nil")
            "kotlin.Short" -> SwiftType("KotlinShort?", "nil")
            "kotlin.Byte" -> SwiftType("KotlinByte?", "nil")
            "kotlin.Boolean" -> SwiftType("KotlinBoolean?", "nil")
            "kotlin.Float" -> SwiftType("KotlinFloat?", "nil")
            "kotlin.Double" -> SwiftType("KotlinDouble?", "nil")
            "kotlin.String" -> SwiftType("String?", "nil")
            // ByteArray bridges to a concrete Kotlin/Native class, not an erased generic.
            "kotlin.ByteArray" -> SwiftType("KotlinByteArray?", "nil")
            else -> SwiftType("AnyObject?", "nil")
        }
    }
    return when (qualifiedName) {
        "kotlin.Int" -> SwiftType("Int32", "0")
        "kotlin.Long" -> SwiftType("Int64", "0")
        "kotlin.Short" -> SwiftType("Int16", "0")
        "kotlin.Byte" -> SwiftType("Int8", "0")
        "kotlin.Boolean" -> SwiftType("Bool", "false")
        "kotlin.Float" -> SwiftType("Float", "0")
        "kotlin.Double" -> SwiftType("Double", "0")
        "kotlin.String" -> SwiftType("String", "\"\"")
        // ByteArray bridges to a concrete Kotlin/Native class, not an erased generic.
        "kotlin.ByteArray" -> SwiftType("KotlinByteArray", "KotlinByteArray(size: 0)")
        else -> SwiftType("AnyObject?", "nil")
    }
}

/**
 * Swift keywords that are invalid as a bare identifier. Using one as a `var`/`func`/parameter name is a
 * syntax error; referencing a Kotlin-exported member with one of these names needs backtick escaping.
 */
private val SWIFT_KEYWORDS = setOf(
    "associatedtype", "class", "deinit", "enum", "extension", "fileprivate", "func", "import", "init",
    "inout", "internal", "let", "open", "operator", "private", "protocol", "public", "rethrows", "static",
    "struct", "subscript", "typealias", "var", "break", "case", "continue", "default", "defer", "do",
    "else", "fallthrough", "for", "guard", "if", "in", "repeat", "return", "switch", "where", "while",
    "as", "catch", "false", "is", "nil", "super", "self", "Self", "throw", "throws", "true", "try",
)

/**
 * Names a generated wrapper must not declare a State property / action / mutator under: the Swift
 * keywords plus members it would collide with. `Kvo{Vm}` is an `NSObject` subclass and `Observable{Vm}`
 * an `ObservableObject`, so their inherited members (`description`, `hash`, `objectWillChange`, …) clash;
 * and both wrappers already declare `viewModel`, `onError`, `observe`, and the `KvoViewModel` base API.
 * Unlike a keyword, these cannot be backtick-escaped into working code — the declaration must be renamed.
 */
private val SWIFT_RESERVED_MEMBERS = SWIFT_KEYWORDS + setOf(
    // NSObject / ObservableObject inherited members.
    "description", "debugDescription", "hash", "hashValue", "superclass", "isEqual", "isProxy",
    "objectWillChange",
    // Members the generated wrappers already declare.
    "viewModel", "onError", "onActionError", "observe", "runAction", "startObserving", "unbind",
)

/**
 * Disambiguates an identifier the generator *declares* (a State `var`, a `{noun}Binding`, an
 * action/mutator method, a mutator's parameter): a name that would collide with a Swift keyword or an
 * inherited/already-declared member gets a trailing `_` (`default` -> `default_`, `description` ->
 * `description_`). A `_`-suffixed name is never itself reserved, so one pass always yields a safe name.
 * The read side of an assignment keeps the original name via [swiftSourceRef].
 */
fun String.swiftDeclName(): String = if (this in SWIFT_RESERVED_MEMBERS) this + "_" else this

/**
 * Escapes a reference to a *Kotlin-exported* member or argument label (the right side of `self.x =
 * initial.x`, a `viewModel.action()` call, a `param:` label). Only Swift keywords break member-access
 * syntax, so only they are backtick-wrapped (`default` -> `` `default` ``); every other name — including
 * inherited-member names like `description`, which legitimately read the State's own field — is
 * returned unchanged.
 */
fun String.swiftSourceRef(): String = if (this in SWIFT_KEYWORDS) "`$this`" else this

/** "feed_item" -> "FeedItem"; also uppercases the first letter of an already-camel identifier. */
fun String.toUpperCamelCase(): String {
    val parts = if (contains('_')) split("_") else listOf(this)
    return parts.filter { it.isNotEmpty() }.joinToString("") { it[0].uppercase() + it.substring(1) }
}

/** `IncomingMessageItemViewModel` -> `incomingMessageItem`; used for generated closed Swift cases. */
fun String.toListCaseName(): String =
    removeSuffix("ViewModel").replaceFirstChar { it.lowercase() }
