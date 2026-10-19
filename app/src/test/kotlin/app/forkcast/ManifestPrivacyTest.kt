package app.forkcast

import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

/** No telemetry: ML Kit's datatransport must have no upload backend in the merged manifest. */
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
}
