package app.forkcast.di

import app.forkcast.BuildConfig
import app.forkcast.core.data.backup.APP_VERSION
import app.forkcast.core.data.di.GITHUB_REPO
import app.forkcast.core.data.di.USER_AGENT
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    /** Open Food Facts asks apps to identify themselves; no personal email (DECISIONS.md). */
    @Provides
    @Named(USER_AGENT)
    fun provideUserAgent(): String = "Forkcast/${BuildConfig.VERSION_NAME} ($GITHUB_URL)"

    /** Where releases are published (the Release workflow), for About's link and "Check for updates". */
    @Provides
    @Named(GITHUB_REPO)
    fun provideGitHubRepo(): String = GITHUB_REPO_NAME

    @Provides
    @Named(APP_VERSION)
    fun provideAppVersion(): String = BuildConfig.VERSION_NAME
}

/** The project on GitHub (private): source, releases, and where "Check for updates" looks. */
const val GITHUB_REPO_NAME = "djemes/forkcast"
const val GITHUB_URL = "https://github.com/$GITHUB_REPO_NAME"

/** About › Report a problem: the issue form chooser (bug report or feature idea; D4, issues on). */
const val GITHUB_ISSUES_URL = "$GITHUB_URL/issues/new/choose"

/**
 * The Ko-fi page (decision D2) that the Settings card and About's "Support on Ko-fi" open. Empty
 * would make them say "Coming soon". Only the github distribution shows them
 * ([app.forkcast.Distribution.hasDonationLink]).
 */
const val DONATION_URL = "https://ko-fi.com/gabemomp"
