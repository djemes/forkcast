package app.forkcast.lint

import com.android.tools.lint.client.api.UElementHandler
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import org.jetbrains.uast.UBinaryExpression
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.ULiteralExpression
import org.jetbrains.uast.UPolyadicExpression
import org.jetbrains.uast.UQualifiedReferenceExpression
import org.jetbrains.uast.USimpleNameReferenceExpression
import org.jetbrains.uast.UastBinaryOperator
import org.jetbrains.uast.skipParenthesizedExprDown

/**
 * Words in Kotlin where the user will read them (roadmap 2.0, task 1.6): a string literal with
 * letters passed as text, a label, a title, a content description, or to a notification. Every
 * user-facing string is a resource so a language is a files-only addition. Numbers, symbols and
 * punctuation ("+${kcal}", "${i + 1}.") are fine; words are not.
 */
class HardcodedUiTextDetector : Detector(), SourceCodeScanner {

    override fun getApplicableUastTypes(): List<Class<out UElement>> = listOf(UCallExpression::class.java, UBinaryExpression::class.java)

    override fun createUastHandler(context: JavaContext): UElementHandler = object : UElementHandler() {
        override fun visitCallExpression(node: UCallExpression) {
            val method = node.resolve() ?: return
            // Exception messages are for logs and tests; the UI maps errors to its own strings.
            if (method.isConstructor && context.evaluator.extendsClass(method.containingClass, "java.lang.Throwable", false)) return
            // error()/require() messages, and Compose animations' debug labels, aren't shown to users.
            val owner = method.containingClass?.qualifiedName.orEmpty()
            if (NOT_UI_OWNERS.any { owner.startsWith(it) }) return
            val mapping = context.evaluator.computeArgumentMapping(node, method)
            for ((argument, parameter) in mapping) {
                val textual = parameter.name in TEXT_PARAMETERS ||
                    (method.name in NOTIFICATION_METHODS && parameter.type.canonicalText == "java.lang.CharSequence")
                if (textual) report(context, argument)
            }
        }

        // semantics { contentDescription = "..." } and the like.
        override fun visitBinaryExpression(node: UBinaryExpression) {
            if (node.operator != UastBinaryOperator.ASSIGN) return
            val target = when (val left = node.leftOperand) {
                is USimpleNameReferenceExpression -> left.identifier
                is UQualifiedReferenceExpression -> left.resolvedName
                else -> null
            }
            if (target in SEMANTIC_PROPERTIES) report(context, node.rightOperand)
        }
    }

    private fun report(context: JavaContext, argument: UExpression) {
        val words = literalParts(argument.skipParenthesizedExprDown() ?: return).firstOrNull { WORD.containsMatchIn(it) } ?: return
        context.report(
            ISSUE,
            argument,
            context.getLocation(argument),
            "Text a user reads (\"${words.trim().take(40)}\") belongs in a string resource, so it can be translated",
        )
    }

    /** The literal pieces of [e]: itself, or the constant parts of a string template or `+` chain (not nested calls). */
    private fun literalParts(e: UExpression): List<String> = when (e) {
        is ULiteralExpression -> listOfNotNull(e.value as? String)
        is UPolyadicExpression -> e.operands.flatMap { op -> (op.skipParenthesizedExprDown() as? ULiteralExpression)?.let { lit -> listOfNotNull(lit.value as? String) } ?: emptyList() }
        else -> emptyList()
    }

    companion object {
        /** Two letters in a row (any script): a word, not "g", "%", "×" or "·". */
        private val WORD = Regex("""\p{L}{2,}""")

        private val TEXT_PARAMETERS = setOf(
            "text", "contentDescription", "label", "title", "subtitle", "message", "body", "placeholder",
            "supportingText", "confirmText", "dismissText", "actionLabel", "onClickLabel",
        )
        private val NOTIFICATION_METHODS = setOf("setContentTitle", "setContentText", "setTicker", "setSubText", "bigText", "makeText")
        private val NOT_UI_OWNERS = listOf("kotlin.", "androidx.compose.animation.")
        private val SEMANTIC_PROPERTIES = setOf("contentDescription", "stateDescription", "paneTitle")

        val ISSUE: Issue = Issue.create(
            id = "ForkcastHardcodedText",
            briefDescription = "User-facing text not in a string resource",
            explanation = "Every string a user can see (screens, notifications, widgets, errors) is a string resource, " +
                "so adding a language only means adding translation files (roadmap 2.0, task 1.6). " +
                "Use stringResource()/getString() with a resource, and plurals or format arguments instead of building sentences.",
            category = Category.I18N,
            priority = 7,
            severity = Severity.ERROR,
            implementation = Implementation(HardcodedUiTextDetector::class.java, Scope.JAVA_FILE_SCOPE),
        )
    }
}
