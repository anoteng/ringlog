package no.ringlog.app.data.api

data class LoginRequest(val username: String, val password: String, val device_name: String = "Android")
data class RegisterRequest(val username: String, val password: String, val email: String? = null, val device_name: String = "Android")
data class LoginResponse(val token: String, val username: String, val user_id: Int)
data class MeResponse(val id: Int, val username: String, val lang: String)

data class Flock(
    val id: Int,
    val name: String,
    val description: String?,
    val owner_username: String?,
    val can_edit: Boolean,
    val bird_count: Int?,
    val birds: List<Bird>? = null,
)

data class FlocksResponse(val owned: List<Flock>, val shared: List<Flock>)

data class Bird(
    val id: Int,
    val flock_id: Int,
    val ring_number: String,
    val name: String?,
    val species: String,
    val breed: String?,
    val breed_mix: String?,
    val sex: String,
    val birth_date: String?,
    val birth_approximate: Boolean,
    val notes: String?,
    val is_dead: Boolean,
    val death_date: String?,
    val is_sold: Boolean,
    val sold_date: String?,
    val has_image: Boolean,
    val notes_list: List<BirdNote>? = null,
)

data class BirdNote(val id: Int, val bird_id: Int? = null, val note_date: String, val content: String)

data class LogEntry(
    val log_date: String,
    val eggs_collected: Int?,
    val light_hours: Float?,
    val bedding_changed: Boolean,
    val notes: String?,
)

data class LogUpsertRequest(
    val eggs_collected: Int?,
    val light_hours: Float?,
    val bedding_changed: Boolean,
    val notes: String?,
)

data class Hatch(
    val id: Int,
    val name: String?,
    val species: String,
    val start_datetime: String,
    val incubation_days: Int,
    val lockdown_day: Int,
    val humidity_incubation: Float?,
    val humidity_lockdown: Float?,
    val egg_count: Int?,
    val eggs_brooder: Int?,
    val eggs_discarded: Int?,
    val eggs_hatched: Int?,
    val notes: String?,
    val owner_id: Int,
    val can_edit: Boolean = true,
    val timeline: HatchTimeline?,
)

data class HatchTimeline(
    val status: String,
    val days_remaining: Int?,
    val progress_pct: Int?,
    val lockdown_dt: String?,
    val hatch_dt: String?,
)

data class HatchesResponse(val owned: List<Hatch>, val shared: List<Hatch>)

data class ReportStats(
    val total_eggs: Int,
    val avg_eggs_per_day: Float,
    val best_day: Int,
    val days_logged: Int,
)

data class ReportEntry(
    val log_date: String,
    val eggs_collected: Int?,
    val light_hours: Float?,
    val bedding_changed: Boolean,
    val laying_hens: Int?,
)

data class FlockReport(
    val days: Int,
    val laying_count: Int,
    val stats: ReportStats,
    val logs: List<ReportEntry>,
)

data class HatchRequest(
    val name: String?,
    val species: String,
    val start_datetime: String,
    val incubation_days: Int,
    val lockdown_day: Int,
    val humidity_incubation: Float?,
    val humidity_lockdown: Float?,
    val egg_count: Int?,
    val eggs_brooder: Int?,
    val eggs_discarded: Int?,
    val eggs_hatched: Int?,
    val notes: String?,
)

data class HatchCreateResponse(val id: Int, val name: String?, val species: String)

data class HatchFinishRequest(
    val eggs_hatched: Int?,
    val eggs_brooder: Int?,
    val eggs_discarded: Int?,
    val flock_id: Int?,
    val new_flock_name: String?,
    val ring_prefix: String?,
    val ring_start: Int?,
    val birth_date: String?,
    val breed: String?,
)

data class HatchFinishResponse(val ok: Boolean, val flock_id: Int?, val created_bird_ids: List<Int>)

data class OkResponse(val ok: Boolean, val error: String? = null)
data class ErrorResponse(val error: String)
