package app.forkcast.lint

import com.android.tools.lint.checks.infrastructure.LintDetectorTest
import com.android.tools.lint.checks.infrastructure.TestFiles.kotlin
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Issue

class HardcodedUiTextDetectorTest : LintDetectorTest() {
    override fun getDetector(): Detector = HardcodedUiTextDetector()
    override fun getIssues(): List<Issue> = listOf(HardcodedUiTextDetector.ISSUE)

    private val stubs = kotlin(
        """
        package ui
        fun Text(text: String, color: Int = 0) {}
        fun Icon(contentDescription: String?) {}
        fun Chip(label: String, onClick: () -> Unit = {}) {}
        fun testTag(tag: String) {}
        fun stringResource(id: Int): String = ""
        class Semantics { var contentDescription: String = "" }
        fun semantics(block: Semantics.() -> Unit) {}
        class Builder { fun setContentTitle(title: CharSequence?): Builder = this }
        """,
    ).indented()

    fun testWordsInTextAreErrors() {
        lint().files(
            stubs,
            kotlin(
                """
                package ui
                fun screen(name: String) {
                    Text("Save backup")
                    Text(text = "Hello, ${'$'}name")
                    Icon(contentDescription = "Close")
                    Chip(label = "Daily")
                    semantics { contentDescription = "Water drop" }
                    Builder().setContentTitle("Backup didn't run")
                }
                """,
            ).indented(),
        ).run().expectErrorCount(6)
    }

    fun testNumbersSymbolsResourcesAndNonUiStringsAreFine() {
        lint().files(
            stubs,
            kotlin(
                """
                package ui
                fun screen(kcal: Int, i: Int) {
                    Text("+${'$'}kcal")
                    Text("${'$'}{i + 1}.")
                    Text("·")
                    Text("${'$'}kcal %")
                    Text(stringResource(1))
                    Icon(contentDescription = null)
                    testTag("today-ring")
                    throw IllegalStateException("not a screen text")
                }
                """,
            ).indented(),
        ).run().expectClean()
    }
}
