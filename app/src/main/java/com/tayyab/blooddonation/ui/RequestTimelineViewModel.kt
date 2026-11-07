package com.tayyab.blooddonation.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tayyab.blooddonation.db.DbHelper
import com.tayyab.blooddonation.model.StatusEvent
import com.tayyab.blooddonation.util.StatusTimeline
import com.tayyab.blooddonation.util.SupabaseSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Phase 2: the first ViewModel + coroutines screen.
 *
 * Lifecycle safety is the whole reason for the pattern: [viewModelScope] is
 * cancelled when the Activity is finally destroyed, so leaving the screen
 * mid-pull cannot post a result into a dead view. All SQLite and network work
 * runs on [Dispatchers.IO] (SupabaseClient has its own connect/read timeouts),
 * and the local copy is shown first so rotation never blanks the screen.
 *
 * [message] carries a CODE ("offline" / "error"), never English wording: the
 * Activity turns it into a translated string resource.
 */
class RequestTimelineViewModel : ViewModel() {

    companion object {
        private const val TAG = "RequestTimelineViewModel"
        const val MSG_OFFLINE = "offline"
        const val MSG_ERROR = "error"
    }

    private val _events = MutableStateFlow<List<StatusEvent>>(emptyList())
    val events: StateFlow<List<StatusEvent>> = _events.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun load(db: DbHelper, requestId: Long) {
        viewModelScope.launch {
            _loading.value = true
            _message.value = null
            try {
                // Local first: an offline user still sees the history recorded
                // on this phone, and a rotation keeps it on screen.
                _events.value = StatusTimeline.ordered(
                    withContext(Dispatchers.IO) { db.statusEvents(requestId) }
                )
                if (SupabaseSync.isOnline) {
                    val ok = withContext(Dispatchers.IO) {
                        SupabaseSync.pullStatusHistory(db, requestId)
                    }
                    _events.value = StatusTimeline.ordered(
                        withContext(Dispatchers.IO) { db.statusEvents(requestId) }
                    )
                    if (!ok) _message.value = MSG_OFFLINE
                }
            } catch (e: Exception) {
                // Never swallow a background failure: log it and show a
                // visible message; the local rows above stay on screen.
                Log.e(TAG, "Timeline load failed for request $requestId", e)
                _message.value = MSG_ERROR
            } finally {
                _loading.value = false
            }
        }
    }

    fun messageShown() {
        _message.value = null
    }
}
