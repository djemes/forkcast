package app.forkcast.lint

import com.android.tools.lint.client.api.IssueRegistry
import com.android.tools.lint.client.api.Vendor
import com.android.tools.lint.detector.api.CURRENT_API
import com.android.tools.lint.detector.api.Issue

/** Registered in META-INF/services; :app and :core:data pull it in with `lintChecks(project(":lint-checks"))`. */
class ForkcastIssueRegistry : IssueRegistry() {
    override val issues: List<Issue> = listOf(HardcodedUiTextDetector.ISSUE)
    override val api: Int = CURRENT_API
    override val minApi: Int = 14
    override val vendor: Vendor = Vendor(vendorName = "Forkcast", identifier = "forkcast-lint")
}
