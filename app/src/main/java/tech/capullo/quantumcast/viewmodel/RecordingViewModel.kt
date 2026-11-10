package tech.capullo.quantumcast.viewmodel

import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.capullo.quantumcast.recording.ActiveRecording
import tech.capullo.quantumcast.recording.RecStation
import tech.capullo.quantumcast.recording.RecordingFile
import tech.capullo.quantumcast.recording.RecordingPrefs
import tech.capullo.quantumcast.recording.RecordingRepository
import tech.capullo.quantumcast.recording.RecordingScheduler
import tech.capullo.quantumcast.recording.RecordingService
import tech.capullo.quantumcast.recording.Schedule
import tech.capullo.source.radiobrowser.data.db.FavoriteEntity
import tech.capullo.source.radiobrowser.data.repository.RadioRepository
import javax.inject.Inject

@HiltViewModel
class RecordingViewModel @Inject constructor(
    private val recordings: RecordingRepository,
    radioRepo: RadioRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val active: StateFlow<List<ActiveRecording>> = recordings.active
        .map { m -> m.values.sortedBy { it.startedAt } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val prefs: StateFlow<RecordingPrefs> = recordings.prefs
        .stateIn(viewModelScope, SharingStarted.Eagerly, RecordingPrefs())

    val favorites: StateFlow<List<FavoriteEntity>> = radioRepo.getFavorites()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Growing files' sizes change without an event, so tick every few seconds while recording.
    private val tick = flow { while (true) { emit(Unit); delay(3_000) } }

    val files: StateFlow<List<RecordingFile>> = combine(recordings.prefs, recordings.filesVersion, recordings.active, tick) { p, _, a, _ -> p to a }
        .map { (p, a) ->
            val names = a.values.map { it.fileName }.toSet()
            withContext(Dispatchers.IO) { recordings.store().list(p.index, names) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun isRecording(uuid: String): Boolean = active.value.any { it.station.uuid == uuid }

    /** Long-press on play: start recording [station], or stop it if it is already recording. */
    fun toggleRecord(station: RecStation) {
        val cur = active.value.firstOrNull { it.station.uuid == station.uuid }
        if (cur != null) stop(cur.id)
        else ContextCompat.startForegroundService(context, RecordingService.startIntent(context, station, null, null))
    }

    fun stop(id: String) {
        context.startService(RecordingService.stopIntent(context, id))
    }

    fun delete(file: RecordingFile) = viewModelScope.launch(Dispatchers.IO) {
        if (recordings.store().delete(file)) recordings.unindexFiles(listOf(file.name))
        recordings.filesChanged()
    }

    /** "Save to…": copy a recording to a user-picked document. */
    fun saveTo(file: RecordingFile, dest: Uri, onDone: (Boolean) -> Unit) = viewModelScope.launch(Dispatchers.IO) {
        val ok = runCatching {
            context.contentResolver.openInputStream(file.uri)!!.use { i ->
                context.contentResolver.openOutputStream(dest, "w")!!.use { o -> i.copyTo(o, 64 * 1024) }
            }
        }.isSuccess
        withContext(Dispatchers.Main) { onDone(ok) }
    }

    fun setCapMb(mb: Int) = viewModelScope.launch { recordings.setCapMb(mb) }

    fun setFolder(uri: Uri?) = viewModelScope.launch { recordings.setFolder(uri) }

    fun saveSchedule(s: Schedule) = viewModelScope.launch {
        recordings.saveSchedule(s)
        RecordingScheduler.armAll(context)
    }

    fun deleteSchedule(id: String) = viewModelScope.launch {
        RecordingScheduler.cancel(context, id)
        recordings.deleteSchedule(id)
        RecordingScheduler.armAll(context)
    }
    fun markOemWarned() = viewModelScope.launch { recordings.setOemWarned() }

    /**
     * Arms the self-test alarm, then kills this process - the same state a swipe-away or vendor
     * kill leaves. Whether the alarm can bring the app back is exactly what is being tested.
     */
    fun runScheduleTest(station: RecStation, finishUi: () -> Unit) = viewModelScope.launch {
        RecordingScheduler.armTest(context, station)
        // Not delay()+kill in this scope: finishing the task clears this ViewModel and cancels
        // viewModelScope before the kill runs (device-observed false pass). The main-looper post
        // outlives the ViewModel.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
            { android.os.Process.killProcess(android.os.Process.myPid()) },
            500,
        )
        finishUi()
    }
}
