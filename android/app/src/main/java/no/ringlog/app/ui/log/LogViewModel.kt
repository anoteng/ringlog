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

    sealed class State {
        object Loading : State()
        data class Ready(
            val flocks: List<Flock>,
            val entries: Map<Int, LogEntry>,
            val prevEntries: Map<Int, LogEntry>,
        ) : State()
        data class Error(val msg: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state = _state.asStateFlow()

    private val _selectedDate = MutableStateFlow(LocalDate.now().minusDays(1))
    val selectedDate = _selectedDate.asStateFlow()

    private var allFlocks: FlocksResponse? = null

    fun init() {
        viewModelScope.launch {
            repo.getFlocks().fold(
                onSuccess = { resp ->
                    allFlocks = resp
                    loadDate(_selectedDate.value)
                },
                onFailure = { _state.value = State.Error(it.message ?: "Error") },
            )
        }
    }

    fun loadDate(date: LocalDate) {
        val resp = allFlocks ?: return
        _selectedDate.value = date
        val dateStr = date.format(DateTimeFormatter.ISO_DATE)
        val prevStr  = date.minusDays(1).format(DateTimeFormatter.ISO_DATE)
        val editableFlocks = (resp.owned + resp.shared.filter { it.can_edit })
        viewModelScope.launch {
            val entries     = mutableMapOf<Int, LogEntry>()
            val prevEntries = mutableMapOf<Int, LogEntry>()
            for (flock in editableFlocks) {
                repo.getLog(flock.id, dateStr, dateStr).onSuccess { list ->
                    list.firstOrNull()?.let { entries[flock.id] = it }
                }
                repo.getLog(flock.id, prevStr, prevStr).onSuccess { list ->
                    list.firstOrNull()?.let { prevEntries[flock.id] = it }
                }
            }
            _state.value = State.Ready(editableFlocks, entries, prevEntries)
        }
    }

    fun save(flockId: Int, eggs: Int?, light: Float?, bedding: Boolean, notes: String?) {
        viewModelScope.launch {
            val dateStr = _selectedDate.value.format(DateTimeFormatter.ISO_DATE)
            repo.upsertLog(flockId, dateStr, eggs, light, bedding, notes)
            loadDate(_selectedDate.value)
        }
    }
}
