package io.parkflow.probe

enum class DestinationReadStatus { IDLE, READING, FAILED, TIMED_OUT }
enum class FlowFailure { NONE, NAVIGATION, DESTINATION_READ }
/** One bounded read window. Older timeout tasks cannot fail a newer request or a received result. */
class DestinationRead(private val timeoutMillis: Long = 8000) {
    var status = DestinationReadStatus.IDLE; private set
    private var generation = 0L
    private var deadline = 0L
    private var quiet = false
    fun begin(now: Long, quiet: Boolean = false): Long {
        this.quiet = quiet || (status == DestinationReadStatus.READING && this.quiet)
        status = DestinationReadStatus.READING; deadline = now + timeoutMillis; return ++generation
    }
    fun received(): Boolean {
        val notify = !quiet; quiet = false; ++generation; status = DestinationReadStatus.IDLE; return notify
    }
    fun failed(token: Long) { if (token == generation && status == DestinationReadStatus.READING) status = DestinationReadStatus.FAILED }
    fun expire(token: Long, now: Long): Boolean {
        if (token != generation || status != DestinationReadStatus.READING || now < deadline) return false
        status = DestinationReadStatus.TIMED_OUT; return true
    }
}
