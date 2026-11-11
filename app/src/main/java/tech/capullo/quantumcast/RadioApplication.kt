package tech.capullo.quantumcast

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import tech.capullo.quantumcast.recording.RecordingScheduler

@HiltAndroidApp
class RadioApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Alarms vanish on force-stop (an OEM swipe-kill can be one) and on reboot before the
        // boot broadcast; re-arming on every process start is idempotent and closes those gaps.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            RecordingScheduler.reportMissed(this@RadioApplication)
            RecordingScheduler.armAll(this@RadioApplication)
        }
    }
}
