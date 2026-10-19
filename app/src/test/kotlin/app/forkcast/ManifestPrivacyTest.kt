package app.forkcast

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import app.forkcast.core.data.health.HealthPermissions
import com.google.common.truth.Truth.assertThat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

/** Merged-manifest checks: no telemetry backend; Health Connect permissions and privacy screen. */
@RunWith(AndroidJUnit4::class)
class ManifestPrivacyTest {

    @Test fun datatransportHasNoBackend() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val discovery = ComponentName(context, "com.google.android.datatransport.runtime.backends.TransportBackendDiscovery")
        assertThrows(PackageManager.NameNotFoundException::class.java) {
            context.packageManager.getServiceInfo(discovery, PackageManager.GET_META_DATA)
        }
        // The scheduler stays (JobScheduler rejects jobs for undeclared services); this also proves
        // the merged manifest is the one being checked.
        val scheduler = ComponentName(context, "com.google.android.datatransport.runtime.scheduling.jobscheduling.JobInfoSchedulerService")
        context.packageManager.getServiceInfo(scheduler, 0)
    }

    /** Every Health Connect permission the app requests is declared, and nothing else health-related. */
    @Test fun healthPermissionsMatchTheCode() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val declared = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().filter { it.startsWith("android.permission.health.") }
        assertThat(declared).containsExactlyElementsIn(HealthPermissions.ALL)
    }

    /** Health Connect's privacy-policy link (Android 13 and older) reaches our privacy screen. */
    @Test fun healthRationaleOpensPrivacyScreen() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = Intent("androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE").setPackage(context.packageName)
        val match = context.packageManager.queryIntentActivities(intent, 0).map { it.activityInfo.name }
        assertThat(match).containsExactly("app.forkcast.ui.health.HealthPrivacyActivity")
    }
}
