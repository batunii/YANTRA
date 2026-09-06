package ie.shoonya.yantra.domain

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import ie.shoonya.yantra.App
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The process, held open for the length of a focus session.
 *
 * Everything else about the timer was built to survive not existing: the session row is on disk the
 * moment it starts, the widget's countdown is drawn by the launcher, and a worker closes a session
 * whose end passed while we were dead. That design is still here and still right — it is what makes
 * a widget keep ticking through a force-stop.
 *
 * What it could not do is be *interactive*. A notification is only as good as the process behind its
 * buttons, and Android will kill a backgrounded app at any moment: Pause on a dead process restored
 * nothing, paused nothing, and reported nothing. Worse, since Android 14 an `ongoing` notification
 * is no longer undismissable — only a foreground service's is — so the one control surface a running
 * session has could be swiped away by accident and not come back until the next state change.
 *
 * So the session gets a service. It owns exactly one thing, the notification, and it exists for
 * exactly as long as there is a live session to describe.
 *
 * **It is not the clock.** [FocusTimer] still holds the ticker on the app scope and still restores
 * itself from disk; this only keeps the process that owns it alive and up. Moving the tick in here
 * would make the timer depend on a service that cannot always be started — see [sync].
 */
class FocusSessionService : Service() {

    private var scope: CoroutineScope? = null

    override fun onBind(intent: Intent?): IBinder? = null

    private var foreground = false

    override fun onCreate() {
        super.onCreate()
        // Within five seconds of the start request, and before anything that can suspend: reading
        // the session back off disk is exactly the kind of work that blows that budget.
        //
        // But usually there is nothing to read — the process is already up and the timer already
        // holds the session, in which case the real notification can be built here and the
        // placeholder never seen. That is not just cosmetic. `startForeground` hands the
        // notification to the system to post, which it does asynchronously, while the collector
        // below posts directly; when the state is already known the collector wins that race and
        // the placeholder lands *after* it, replacing a correct notification with a generic one
        // that stayed until the next pause or resume.
        val known = runCatching { (application as App).container.timer.state.value }
            .getOrNull()
            ?.takeIf { !it.isFinished }
        foreground = startForeground(
            if (known != null) SessionNotification.build(this, known)
            else SessionNotification.placeholder(this)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foreground) {
            // Never made it to the foreground, so there is nothing to keep alive and holding a
            // started service would only invite the system to complain about it. Fall back to the
            // plain notification, exactly as a refusal at the call site does — a session the user
            // cannot see is worse than one they cannot swipe away.
            (application as App).container.timer.state.value
                ?.takeIf { !it.isFinished }
                ?.let { SessionNotification.show(this, it) }
            stopSelf()
            return START_NOT_STICKY
        }
        if (scope != null) {
            // Already running, and being started again — which means someone wants the notification
            // re-posted rather than a second collector. The collector only fires on state
            // *transitions*, so it cannot help here: the usual reason for a re-sync is that the
            // last post did not land, and a session that is merely continuing produces no
            // transition to hang a retry on. See [refresh].
            (application as App).container.timer.state.value
                ?.takeIf { !it.isFinished }
                ?.let { startForeground(SessionNotification.build(this, it)) }
            return START_STICKY
        }
        run {
            val container = (application as App).container
            val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            scope = s
            s.launch {
                // A restarted service (START_STICKY, below) comes back with no memory of what it
                // was showing, and the app scope's own restore may not have run yet.
                container.timer.restoreIfNeeded()
                container.timer.state
                    // Per-second ticks are the system's job — the chronometer in the notification
                    // ticks itself. Only the shape of the notification changes here.
                    .map { st -> st?.let { Triple(it.sessionId, it.isRunning, it.isFinished) } }
                    .distinctUntilChanged()
                    .collect {
                        val state = container.timer.state.value
                        if (state == null || state.isFinished) {
                            // The session is over. Whoever ended it has already written the row and
                            // rung the bell if one was owed; there is nothing left to hold open.
                            stop()
                            return@collect
                        }
                        // Updated through startForeground rather than notify: it is the same id
                        // and the same notification, and posting it by two routes means two
                        // queues and no guaranteed order between them. Re-calling startForeground
                        // on a service already in the foreground is the documented way to update
                        // its notification, and it keeps what the system holds as the foreground
                        // notification identical to what is on screen, by construction.
                        startForeground(SessionNotification.build(this@FocusSessionService, state))
                    }
            }
            // The meter, moved.
            //
            // Everything else in this notification ticks itself: the chronometer is handed a
            // reference time and the system counts from it, which is why the rest of the file
            // posts on state *transitions* only. A progress bar has no such trick — `setProgress`
            // is a number, not a rule — so a bar left alone sits at zero for the whole of a
            // twenty-five minute session while the clock beside it counts down. That is worse than
            // having no bar, because it is a bar that is wrong.
            //
            // The rule it breaks was written to stop a *dead* process being woken to move pixels.
            // This process is deliberately alive for exactly as long as the session lasts — that
            // is what the service is for — so the objection does not apply. Half a minute moves a
            // 25-minute bar about two percent, at a cost of fifty posts across a session.
            //
            // The bar is the whole of the reason. Every other moving thing here ticks itself: the
            // shade's clock and the status-bar chip both run off `when`, so neither needs this.
            //
            // Only for a promise. A stopwatch has no bar, because it has nothing to be a fraction
            // of, so it is left entirely to the chronometer.
            s.launch {
                while (true) {
                    delay(30_000)
                    val st = container.timer.state.value ?: continue
                    if (st.isRunning && !st.isFinished && !st.isOpen && !st.isSpent) {
                        startForeground(SessionNotification.build(this@FocusSessionService, st))
                    }
                }
            }
        }
        // Sticky: if the system kills us mid-session it should bring us back, and the collector
        // above restores from disk on the way in. A session that genuinely ended in the meantime
        // restores as nothing and the service stops itself immediately.
        return START_STICKY
    }

    /**
     * @return whether the service is actually in the foreground.
     *
     * The start can be refused *here* as well as at the call site, and for a reason [sync] cannot
     * pre-empt: the app may have left the foreground in the moment between requesting the service
     * and this running, and Android judges the transition, not the request. An uncaught refusal is
     * a crash — for the offence of a timer still running — so it is caught, and a service that
     * cannot be in the foreground stops being a service at all. The session is untouched by any of
     * this; it lives on disk, and [SessionNotification.show] keeps it visible and stoppable.
     */
    private fun startForeground(notification: android.app.Notification): Boolean = runCatching {
        // specialUse, because there is no foreground-service type for "a timer the user started"
        // and the alternatives are worse than untyped: dataSync is a lie about the network, and
        // shortService caps at a few minutes, which is shorter than the shortest pomodoro.
        //
        // The constant is API 34, and the guard here read `>= 29` — five levels early. On Android
        // 12 and 13 that handed the framework a type bit it had never heard of, which throws, and
        // the runCatching below turned the throw into a service that simply never started: the
        // session kept its notification and lost the process that was meant to hold it open. Below
        // 34 the answer is 0, which means "whatever the manifest declared" and is exactly right.
        ServiceCompat.startForeground(
            this,
            SessionNotification.ID,
            notification,
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
    }.isSuccess

    private fun stop() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope?.cancel()
        scope = null
        super.onDestroy()
    }

    companion object {
        /**
         * Brings the service into agreement with whether a session is live.
         *
         * **The start can fail, and that is not an error.** Since Android 12 a foreground service
         * may not be started from the background, and two of the places a session comes back to life
         * are exactly that: [ie.shoonya.yantra.widget.FocusFinalizeWorker] and any restore
         * triggered by a process wake. A widget tap or a notification button carries a temporary
         * exemption; a worker does not.
         *
         * So a refused start falls back to posting the notification plainly, under the same id. The
         * session is still real, still visible and still stoppable — it is only undismissable and
         * reliably interactive once the app is next in a position to start the service, which the
         * next foreground moment does. Letting the exception escape would crash the process for the
         * crime of noticing that a timer was still running.
         */
        /**
         * Re-posts the running session's notification, if there is one.
         *
         * Posting on transitions only is almost always right, but the *permission* to show a
         * notification can arrive after the notification does. Asking for `POST_NOTIFICATIONS` at
         * the moment a session starts — which is the only honest moment to ask — means the first
         * session on a fresh install posts while the dialog is still up, is suppressed for want of
         * the grant, and then has no further transition to be re-posted by. The session would run
         * its whole length invisibly on the one install where the user had *just* said yes to
         * seeing it.
         *
         * So the permission's answer is a reason to post again, whatever it was last time.
         */
        fun refresh(context: Context) {
            val live = (context.applicationContext as App).container.timer.state.value
                ?.takeIf { !it.isFinished }
                ?: return
            sync(context, live = true, state = live)
        }

        fun sync(context: Context, live: Boolean, state: FocusTimer.State?) {
            val intent = Intent(context, FocusSessionService::class.java)
            if (!live) {
                context.stopService(intent)
                SessionNotification.clear(context)
                return
            }
            val started = runCatching { ContextCompat.startForegroundService(context, intent) }.isSuccess
            if (!started && state != null) SessionNotification.show(context, state)
        }
    }
}
