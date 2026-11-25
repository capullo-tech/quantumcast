package tech.capullo.quantumcast.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tech.capullo.quantumcast.data.playlists.DetectedTrack
import tech.capullo.quantumcast.data.playlists.PlaylistRepository
import tech.capullo.quantumcast.data.playlists.PlaylistStationEntity
import tech.capullo.quantumcast.data.playlists.StationPlaylist
import javax.inject.Inject

/** The Playlists screens: one playlist per station, built from its detection history. */
@HiltViewModel
class PlaylistsViewModel @Inject constructor(
    private val repo: PlaylistRepository,
) : ViewModel() {

    val playlists: StateFlow<List<StationPlaylist>> = repo.playlists
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun tracks(stationId: Long): Flow<List<DetectedTrack>> = repo.tracks(stationId)

    /** The playlists as stored right now, for the adb debug dump (no UI subscribes there). */
    suspend fun currentPlaylists(): List<StationPlaylist> = repo.playlists.first()

    fun station(stationId: Long): Flow<PlaylistStationEntity?> = repo.station(stationId)

    fun deletePlaylist(stationId: Long) {
        viewModelScope.launch { repo.deletePlaylist(stationId) }
    }

    fun deleteTrack(trackId: Long) {
        viewModelScope.launch { repo.deleteTrack(trackId) }
    }
}
