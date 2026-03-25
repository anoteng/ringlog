package no.ringlog.app.ui.log

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import no.ringlog.app.data.api.Flock
import no.ringlog.app.data.api.FlocksResponse
import no.ringlog.app.data.api.LogEntry
import no.ringlog.app.data.repository.FlockRepository
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject

@HiltViewModel
class LogViewModel @Inject constructor(private val repo: FlockRepository) : ViewModel() {

    sealed class State { object Loading : State()
        data class Ready(val flocks: List<Flock>, val entries: Map<Int, LogEntry>) : State()
        data class Error(val msg: String) : State() }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state = _state.asStateFlow()

    var selectedDate: LocalDate = LocalDate.now()
        private set

    private var allFlocks: FlocksResponse? = null

    fun init() {
        viewModelScope.launch {
            repo.getFlocks().fold(
                onSuccess = { resp ->
                    allFlocks = resp
                    loadDate(selectedDate)
                },
                onFailure = { _state.value = State.Error(it.message ?: "Error") },
            )
        }
    }

    fun loadDate(date: LocalDate) {
        val resp = allFlocks ?: return
        selectedDate = date
        val dateStr = date.format(DateTimeFormatter.ISO_DATE)
        val editableFlocks = (resp.owned + resp.shared.filter { it.can_edit })
        viewModelScope.launch {
            val entries = mutableMapOf<Int, LogEntry>()
            for (flock in editableFlocks) {
                repo.getLog(flock.id, dateStr, dateStr).onSuccess { list ->
                    list.firstOrNull()?.let { entries[flock.id] = it }
                }
            }
            _state.value = State.Ready(editableFlocks, entries)
        }
    }

    fun save(flockId: Int, eggs: Int?, light: Float?, bedding: Boolean, notes: String?) {
        viewModelScope.launch {
            val dateStr = selectedDate.format(DateTimeFormatter.ISO_DATE)
            repo.upsertLog(flockId, dateStr, eggs, light, bedding, notes)
            loadDate(selectedDate)
        }
    }
}
