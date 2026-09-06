package ie.napkin.supertasks.domain

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import ie.napkin.supertasks.App
import ie.napkin.supertasks.MainActivity
import ie.napkin.supertasks.R
import ie.napkin.supertasks.ui.theme.ThemeMode
import ie.napkin.supertasks.ui.theme.loadThemeController
import ie.napkin.supertasks.ui.theme.resolve
import ie.napkin.supertasks.widget.ListWidgetProvider
import ie.napkin.supertasks.widget.WidgetIntents
import kotlinx.coroutines.launch

/**
 * The running session, on the lock screen.
 *
 * A session is the one thing the app is doing *while you are not looking at it*, so it is the one
 * thing that has to be reachable from outside it. Stopping what you are working on should not cost
 * an unlock, a launch and a navigation — by the time that is done the thing you actually meant to
 * do has been displaced by the app.
 *
 * **The clock is the system's, not ours.** [NotificationCompat.Builder.setUsesChronometer] renders
 * it from a reference time and ticks it itself, so the clock costs nothing to keep true and this is
 * posted on state *transitions* rather than on a timer.
 *
 * The one exception is the progress bar a promoted session carries, which has no such trick —
 * `setProgress` is a number, not a rule — and which [FocusSessionService] therefore re-posts every
 * half minute. That is not the rule being abandoned: it was written to stop a dead process being
 * woken to move pixels, and the service holds this one open on purpose.
 *
 * Which *direction* it ticks is the session's own distinction, and this is the surface that used to
 * lose it. [FocusTimer] holds two instruments: a committed session counts down to a promise, an open
 * one counts up and promises nothing. The widget has always drawn that difference; the notification
 * drew every session as a count-up, so a 25-minute pomodoro reported the one number its whole point
 * is not about. A countdown gets [NotificationCompat.Builder.setChronometerCountDown] and a `when`
 * in the future; a stopwatch keeps the old count-up from its start.
 *
 * **A paused session must not tick.** There is no way to freeze a system chronometer, so a pause
 * drops it entirely and writes the frozen reading into the text instead. A clock that keeps running
 * on a timer you have paused is not a cosmetic flaw — it is the notification disagreeing with the
 * app about whether you are working.
 *
 * Its own channel, at low importance. A reminder interrupts you on purpose; this is a status, and a
 * status that buzzes is a status you turn off. The *end* of a committed session is the exception and
 * has a channel of its own — see [showCompleted].
 */
object SessionNotification {
    const val CHANNEL_ID = "session"

    /**
     * The running session, on a channel the system will consider promoting.
     *
     * A status-bar chip is attention by definition, and Android will not lift a notification out of
     * a channel whose whole declaration is "do not draw attention" — the low channel [CHANNEL_ID]
     * exists precisely to say that. A second channel at default importance is the only way to offer
     * the choice, because a channel's importance belongs to the user once it exists and an app
     * cannot raise it afterwards.
     *
     * Still silent, and that is not a contradiction: importance governs whether the system may
     * surface this, sound governs whether it interrupts, and a focus timer wants the first without
     * the second. The channel carries no sound and every post sets `setSilent`.
     */
    const val CHANNEL_LIVE_ID = "session_live"

    /**
     * The end of a committed session, which is the one moment a focus timer exists for and the one
     * thing the status channel cannot say. Separate because it needs to alert: silencing the
     * running status is a reasonable thing to want, and it must not also silence the bell.
     */
    const val CHANNEL_DONE_ID = "session_done"

    const val ACTION_STOP = "ie.napkin.supertasks.action.SESSION_STOP"
    const val ACTION_DONE = "ie.napkin.supertasks.action.SESSION_DONE"
    const val ACTION_PAUSE = "ie.napkin.supertasks.action.SESSION_PAUSE"
    const val ACTION_RESUME = "ie.napkin.supertasks.action.SESSION_RESUME"

    /**
     * Which task the button was drawn for.
     *
     * The live session is the better answer while there *is* one, because a notification can outlive
     * the session it describes and finishing whatever is running now is what "Done" means on a
     * running session. But the completion bell has no live session by definition — that is what it
     * is announcing — so it has to carry its own subject or its one button does nothing.
     */
    const val EXTRA_NODE_ID = "ie.napkin.supertasks.SESSION_NODE_ID"

    /**
     * Fixed: there is only ever one session, so its notification replaces itself. Public because
     * [FocusSessionService] posts under the same id — the foreground notification and this one are
     * the same notification, not two that happen to look alike.
     */
    const val ID = 0x5E5

    /** The completion bell. Its own id so it can outlive the running notification it replaces. */
    private const val DONE_ID = 0x5E6

    /**
     * Side of the drawn mark, in pixels.
     *
     * Fixed rather than density-scaled: a large icon is handed to the system as a bitmap and scaled
     * to whatever the current template wants, so the only thing that matters is having enough
     * resolution for the largest of them. 192 covers it on every density this app runs at, and the
     * bitmap is built on state transitions, not on a clock.
     */
    private const val MARK_PX = 192

    /**
     * Whether the system would actually promote an ongoing notification for this app.
     *
     * Worth asking every time rather than caching: these are switches the person can flip while a
     * session is running, and the next transition should honour whatever they now say.
     */
    fun canPromote(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 36 &&
            runCatching {
                context.getSystemService(android.app.NotificationManager::class.java)
                    ?.canPostPromotedNotifications() == true
            }.getOrDefault(false) &&
            oemHonoursPromotion(context)

    /**
     * One UI's own gate, which the platform's answer does not include.
     *
     * `canPostPromotedNotifications` reports `true` on Samsung whether or not anything will be
     * drawn, so on its own it is not a question worth asking there. Measured on a Galaxy S24 running
     * One UI 8: the permission granted, the notification carrying `FLAG_PROMOTED_ONGOING`, and no
     * chip anywhere — until the user flipped *Developer options → Live notifications for all apps*,
     * which writes `enable_notification_nowbar_test`, after which it appeared immediately.
     *
     * That matters because promotion is not free. The shape it demands forbids a custom view, so
     * taking it costs the transport keys — a good trade for a chip and a plain loss without one, and
     * without this check every Samsung user on a current build pays it for nothing.
     *
     * `key_now_bar_<package>` is checked too but is not a route in: it is Samsung's own preloaded
     * list of legacy integrations, keyed for apps that are not installed and missing for third-party
     * apps that are. It costs one lookup to honour it should Samsung ever add an entry.
     *
     * A no-op everywhere else, and a no-op on Samsung the day they default the flag on. Reading
     * fails closed: on One UI, an unreadable setting keeps the keys rather than gambling them.
     */
    private fun oemHonoursPromotion(context: Context): Boolean {
        if (!Build.MANUFACTURER.equals("samsung", ignoreCase = true)) return true
        val resolver = context.contentResolver
        val appKey = "key_now_bar_" + context.packageName.replace('.', '_')
        return runCatching {
            android.provider.Settings.Global.getInt(resolver, "enable_notification_nowbar_test", 0) == 1 ||
                android.provider.Settings.Secure.getInt(resolver, appKey, 0) == 1
        }.getOrDefault(false)
    }

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /** The app's ink, read from the same prefs the app and the widgets read. */
    private fun accentArgb(context: Context): Int {
        val theme = loadThemeController(context)
        val systemDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        return theme.accent.ink(theme.mode.resolve(systemDark) != ThemeMode.LIGHT).toArgb()
    }

    /**
     * What the clock should read, and which way it should run.
     *
     * Pulled out of the builder because it is the whole of the fix and none of the Android: a
     * session has two instruments and three states between them, and getting that wrong is what
     * made a 25-minute promise report the one number it is not about. Pure, so it can be tested
     * rather than looked at.
     */
    internal sealed interface Face {
        /** A promise, arriving in [secs]. The chronometer runs backwards to it. */
        data class Countdown(val secs: Int) : Face

        /** A stopwatch, [secs] into it. The chronometer runs forwards from its start. */
        data class CountUp(val secs: Int) : Face

        /** Paused. No chronometer exists that can be stopped, so there is a number instead. */
        data class Frozen(val text: String) : Face
    }

    internal fun face(state: FocusTimer.State): Face = when {
        // Paused freezes whichever number that instrument was showing — remaining for a promise,
        // elapsed for a stopwatch. Freezing the other one would be a different session's reading.
        !state.isRunning -> Face.Frozen(
            if (state.isOpen) "Paused · ${clock(state.elapsedSecs)}"
            else "Paused · ${clock(state.remainingSecs)} left"
        )
        state.isOpen -> Face.CountUp(state.elapsedSecs)
        else -> Face.Countdown(state.remainingSecs)
    }

    /** `M:SS`, or `H:MM:SS` once there is an hour to report. Matches the chronometer's own shape. */
    internal fun clock(secs: Int): String {
        val s = secs.coerceAtLeast(0)
        val h = s / 3600
        return if (h > 0) String.format("%d:%02d:%02d", h, (s % 3600) / 60, s % 60)
        else String.format("%d:%02d", s / 60, s % 60)
    }

    private fun openIntent(context: Context, nodeId: String) =
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(ListWidgetProvider.EXTRA_OPEN_NODE, nodeId)
            putExtra(ListWidgetProvider.EXTRA_OPEN_SMART, false)
            data = Uri.parse("yantra://open/$nodeId")
        }

    /**
     * A broadcast to [SessionReceiver].
     *
     * The distinct `data` per action is load-bearing: `PendingIntent` identity ignores extras, so
     * four actions sharing a request code and a bare component would collapse into one and every
     * button would do whatever the last one built did.
     */
    private fun action(context: Context, act: String, target: String, nodeId: String) =
        PendingIntent.getBroadcast(
            context, 0,
            Intent(context, SessionReceiver::class.java).apply {
                action = act
                data = Uri.parse("yantra://session/$target/$nodeId")
                putExtra(EXTRA_NODE_ID, nodeId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /**
     * The expanded body: the task, the clock, and three buttons that look like buttons.
     *
     * The standard template stopped drawing action icons on phones at API 24, so the actions built
     * below render as the words "Pause  Stop  Done" — a row of labels where a transport belongs. A
     * decorated custom view is the supported way to get real controls back: the system keeps the
     * header and the chrome, and this fills the body.
     *
     * The clock here is the same bargain as everywhere else in this file. A `Chronometer` in a
     * `RemoteViews` is ticked by the process that hosts the shade, so it stays live with nothing
     * posted per second; and because there is no way to *stop* one mid-flight, a paused session
     * hides it and shows a frozen reading in its place rather than leaving a clock running on a
     * timer that is not.
     */
    private fun transport(context: Context, state: FocusTimer.State, accent: Int): RemoteViews {
        val nodeId = state.nodeId
        return RemoteViews(context.packageName, R.layout.notification_focus).apply {
            setTextViewText(R.id.focus_title, state.nodeTitle.ifBlank { "Untitled" })
            // The mark lives in the body rather than in the large-icon slot, which the shade
            // reserves space for on the right and which was squeezing this row into a column.
            setImageViewBitmap(
                R.id.focus_mark,
                SessionMark.bhupura(MARK_PX, accent, filledBindu = state.isRunning),
            )

            if (state.isRunning) {
                setViewVisibility(R.id.focus_chrono, android.view.View.VISIBLE)
                setViewVisibility(R.id.focus_frozen, android.view.View.GONE)
                setChronometerCountDown(R.id.focus_chrono, !state.isOpen)
                setChronometer(
                    R.id.focus_chrono,
                    // Elapsed-realtime based, like the widget's: a Chronometer counts against the
                    // monotonic clock, not the wall one.
                    if (state.isOpen) android.os.SystemClock.elapsedRealtime() - state.elapsedSecs * 1000L
                    else android.os.SystemClock.elapsedRealtime() + state.remainingSecs * 1000L,
                    null,
                    true,
                )
            } else {
                setViewVisibility(R.id.focus_chrono, android.view.View.GONE)
                setViewVisibility(R.id.focus_frozen, android.view.View.VISIBLE)
                setTextViewText(
                    R.id.focus_frozen,
                    clock(if (state.isOpen) state.elapsedSecs else state.remainingSecs),
                )
            }

            // One button that is whichever of the two the session is not currently doing.
            setImageViewResource(
                R.id.focus_toggle,
                if (state.isRunning) R.drawable.ic_notif_pause else R.drawable.ic_notif_play,
            )
            setContentDescription(R.id.focus_toggle, if (state.isRunning) "Pause" else "Resume")

            // The glyphs are white-on-transparent vectors; the ink is applied here so the mark and
            // the controls are the same colour the app is drawn in.
            for (id in intArrayOf(R.id.focus_toggle, R.id.focus_stop, R.id.focus_done)) {
                setInt(id, "setColorFilter", accent)
            }

            setOnClickPendingIntent(
                R.id.focus_toggle,
                if (state.isRunning) action(context, ACTION_PAUSE, "pause", nodeId)
                else action(context, ACTION_RESUME, "resume", nodeId),
            )
            setOnClickPendingIntent(R.id.focus_stop, action(context, ACTION_STOP, "stop", nodeId))
            setOnClickPendingIntent(R.id.focus_done, action(context, ACTION_DONE, "done", nodeId))
        }
    }

    /**
     * The running session as a **Live Update** — Android 16's promoted ongoing notification, which
     * the system lifts out of the shade and into a chip beside the clock.
     *
     * This is the pill, and it is not something an app switches on. `hasPromotableCharacteristics`
     * decides, and it only says yes to a notification shaped a particular way: ongoing, coloured,
     * and styled as `ProgressStyle` or `CallStyle`. A decorated custom view — the transport with the
     * bhupura keys — disqualifies it outright, which is the whole trade being made here. The chip is
     * worth more than buttons the shade already renders as words: it is the difference between a
     * session you have to pull the shade down to see and one that is simply present.
     *
     * The progress bar is what `ProgressStyle` is for, and a committed session has exactly the
     * denominator it needs. An open stopwatch has none, so it says so — indeterminate — rather than
     * inventing a fraction.
     *
     * `setShortCriticalText` is the chip's own text, and it has room for about half a dozen
     * characters. Not the clock: the system renders the countdown in the chip itself from `when`.
     */
    @androidx.annotation.RequiresApi(36)
    private fun buildPromoted(context: Context, state: FocusTimer.State, accent: Int): Notification {
        val nodeId = state.nodeId
        val b = android.app.Notification.Builder(context, CHANNEL_LIVE_ID)
            .setSmallIcon(R.drawable.ic_notif_reminder)
            .setColor(accent)
            // Deliberately *not* colorized. A colorized notification is disqualified from
            // promotion outright — which is the opposite of what it looks like, since colorizing
            // is what a foreground service does to own its row in the shade. The chip takes its
            // tint from setColor regardless.
            .setContentTitle(state.nodeTitle.ifBlank { "Untitled" })
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            // Asking is the part that was missing. Shape alone does not earn the chip: the app
            // declares POST_PROMOTED_NOTIFICATIONS and then requests promotion per notification,
            // and the system grants it if the shape qualifies and the user has not refused.
            .setRequestPromotedOngoing(true)
            // No setSilent on the platform builder — that is a NotificationCompat convenience.
            // Silence comes from the channel, which carries no sound and no vibration.
            .setCategory(android.app.Notification.CATEGORY_STOPWATCH)
            .setVisibility(android.app.Notification.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(android.app.Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(
                PendingIntent.getActivity(
                    context, 0, openIntent(context, nodeId),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )

        // A bar only where there is something to be a fraction of.
        //
        // A stopwatch was getting an *indeterminate* ProgressStyle, on the reasoning that it has no
        // denominator — but indeterminate does not mean "unmeasured", it means "waiting", and it
        // draws the endless sliding bar every app uses to say it is loading something. On a running
        // stopwatch that is a lie about the app's state, and an ugly one.
        //
        // The standard style is promotable too, so an open session keeps the chip and simply has no
        // bar. Only a promise gets one, because only a promise has an end to be measured against.
        if (!state.isOpen) {
            b.setStyle(
                android.app.Notification.ProgressStyle()
                    .setProgressSegments(
                        listOf(
                            android.app.Notification.ProgressStyle.Segment(state.plannedSecs)
                                .setColor(accent)
                        )
                    )
                    .setProgress(state.elapsedSecs.coerceIn(0, state.plannedSecs))
            )
        }

        // The chip's text has two sources and they are alternatives, not layers: a short critical
        // string, or the chronometer the system runs from `when`. Setting the string wins — which
        // is why the pill sat at "24m" for half a minute at a time, refreshing only when something
        // re-posted it. A timer whose own chip does not move is the one thing this feature exists
        // to avoid, so a running session sets no critical text and lets the system tick it: down
        // to the promise, or up from the start of a stopwatch.
        //
        // Paused is the exception and the reason the string is still worth having. There is no
        // chronometer to run when the clock is stopped, and a chip reading nothing at all would
        // say less than one reading "Paused".
        when (val f = face(state)) {
            is Face.Frozen -> b
                .setUsesChronometer(false)
                .setShowWhen(false)
                .setContentText(f.text)
                .setShortCriticalText("Paused")
            is Face.Countdown -> b
                .setWhen(System.currentTimeMillis() + f.secs * 1000L)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setContentText("Focusing · ${(state.plannedSecs + 59) / 60} min")
            is Face.CountUp -> b
                .setWhen(System.currentTimeMillis() - f.secs * 1000L)
                .setUsesChronometer(true)
                .setChronometerCountDown(false)
                .setContentText("Stopwatch")
        }

        fun act(icon: Int, label: String, action: String, target: String) =
            android.app.Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(context, icon),
                label,
                action(context, action, target, nodeId),
            ).build()

        if (state.isRunning) b.addAction(act(R.drawable.ic_notif_pause, "Pause", ACTION_PAUSE, "pause"))
        else b.addAction(act(R.drawable.ic_notif_play, "Resume", ACTION_RESUME, "resume"))
        b.addAction(act(R.drawable.ic_notif_stop, "Stop", ACTION_STOP, "stop"))
        b.addAction(act(R.drawable.ic_notif_done, "Done", ACTION_DONE, "done"))
        return b.build()
    }

    /**
     * The running session as the lock screen draws it.
     *
     * Built rather than posted, because [FocusSessionService] needs the object itself to hand to
     * `startForeground` — the service *is* the notification's owner now, and a second posting path
     * would race it under the same id.
     */
    fun build(context: Context, state: FocusTimer.State): Notification {
        val nodeId = state.nodeId
        val accent = accentArgb(context)
        // The chip, but only when there is actually a chip.
        //
        // Android 16 can lift an ongoing session into a status-bar pill, and the shape it demands
        // costs the custom transport: a decorated custom view is disqualified outright, so the
        // bhupura keys become three words in the system's own row. That is a good trade for a chip
        // and a bad one for nothing — and whether there is a chip is not the app's decision. It is
        // a per-app grant the person makes, which `canPostPromotedNotifications` reports and an
        // app-op behind it enforces. Ungranted, this would have quietly swapped working buttons for
        // a promotion that never came.
        if (Build.VERSION.SDK_INT >= 36 && canPromote(context)) {
            return buildPromoted(context, state, accent)
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif_reminder)
            .setColor(accent)
            // No large icon. It is the obvious place for the app's mark and it cost more than it
            // gave: the shade reserves the right-hand side of an *expanded* notification for it,
            // which left the transport below a third of the width and wrapped the clock down the
            // page one character at a time. The mark is drawn inside the body instead, and the
            // collapsed row loses nothing — the launcher icon beside it is already a bhupura.
            .setContentTitle(state.nodeTitle.ifBlank { "Untitled" })
            // No subText. It was carrying the promised length, which is worth saying — but the
            // collapsed row gives the header, the title, the time and the large icon one line
            // between them, and a fifth thing cost the task's own name half its width: the shade
            // read "Try openi…" for a subtitle nobody needs at a glance. The promise moved down to
            // the content line, which has a row to itself.
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomBigContentView(transport(context, state, accent))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            // The whole point: actionable without unlocking.
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // A focus session is something the user just asked for, so its notification should
            // appear with it rather than after the system's ten-second grace period.
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(
                PendingIntent.getActivity(
                    context, 0, openIntent(context, nodeId),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )

        val now = System.currentTimeMillis()
        when (val f = face(state)) {
            // No chronometer at all, and the frozen reading moves into the text. See the class
            // note — there is no way to stop a system chronometer, only to not have one.
            is Face.Frozen -> builder
                .setUsesChronometer(false)
                .setShowWhen(false)
                .setContentText(f.text)

            is Face.Countdown -> builder
                .setWhen(now + f.secs * 1000L)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
                // The promise, which the countdown cannot state: eight minutes left of twenty-five
                // is a different afternoon from eight minutes left of ten.
                .setContentText("Focusing · ${(state.plannedSecs + 59) / 60} min")

            is Face.CountUp -> builder
                .setWhen(now - f.secs * 1000L)
                .setUsesChronometer(true)
                .setChronometerCountDown(false)
                .setContentText("Stopwatch")
        }

        // No builder actions. Under DecoratedCustomViewStyle the system draws its own action row
        // *below* the custom view, so keeping them put a second "Pause  Stop  Done" underneath the
        // transport that already does exactly that — the same three controls twice, in two visual
        // languages. The buttons in [transport] are the ones the person sees and presses.
        return builder.build()
    }

    /**
     * The notification the service shows before it has read any state.
     *
     * `startForeground` has about five seconds from the service starting, and reading the session
     * back off disk can take longer than that — so the service posts this first and replaces it the
     * moment it knows what is running. It is a placeholder that is *true*: something is running, we
     * are about to say what.
     */
    fun placeholder(context: Context): Notification {
        val accent = accentArgb(context)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif_reminder)
            .setColor(accent)
            .setLargeIcon(SessionMark.bhupura(MARK_PX, accent, filledBindu = true))
            .setContentTitle("Focus session")
            .setSubText("Focus")
            .setContentText("Running")
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(
                PendingIntent.getActivity(
                    context, 0,
                    Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        putExtra(WidgetIntents.EXTRA_OPEN_FOCUS, true)
                        data = Uri.parse("yantra://focus")
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .build()
    }

    /**
     * Posts the running session directly.
     *
     * The fallback path. [FocusSessionService] owns this notification whenever it can be started,
     * but a foreground service cannot be started from the background — a restore inside a worker,
     * say — and a session that is genuinely running must still be visible and stoppable. So the
     * plain notification remains, under the same id, and the service replaces it when one can next
     * be started.
     */
    fun show(context: Context, state: FocusTimer.State) {
        if (!canNotify(context)) return
        NotificationManagerCompat.from(context).notify(ID, build(context, state))
    }

    fun clear(context: Context) = NotificationManagerCompat.from(context).cancel(ID)

    /**
     * Dismisses the completion bell.
     *
     * `setAutoCancel` covers a tap on the body but not on an action button, so acting on the bell
     * has to take it down explicitly — otherwise "Done" leaves behind a notification still offering
     * to do the thing it has just done.
     */
    fun clearCompleted(context: Context) = NotificationManagerCompat.from(context).cancel(DONE_ID)

    /**
     * The bell at the end of a committed session.
     *
     * Until now this moment was silent: the running notification was simply cleared, and a pomodoro
     * that ran out while the phone was in a pocket told nobody. Worse, the honest case — the process
     * died and [ie.napkin.supertasks.widget.FocusFinalizeWorker] closed the row later — reported
     * nothing at all, so the session most in need of an ending got none.
     *
     * Only for sessions that *arrived*. Stopping early is something you did, on purpose, with the
     * phone in your hand; being told about it afterwards would be the app repeating you back.
     */
    fun showCompleted(context: Context, title: String, nodeId: String, elapsedSecs: Int) {
        if (!canNotify(context)) return
        val accent = accentArgb(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_DONE_ID)
            .setSmallIcon(R.drawable.ic_notif_reminder)
            .setColor(accent)
            // Filled, because the bindu landing is what the app does when something completes.
            .setLargeIcon(SessionMark.bhupura(MARK_PX, accent, filledBindu = true))
            .setContentTitle(title.ifBlank { "Untitled" })
            .setSubText("Focus")
            .setContentText("Focus complete · ${clock(elapsedSecs)}")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    context, 0, openIntent(context, nodeId),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .addAction(R.drawable.ic_notif_done, "Done", action(context, ACTION_DONE, "done", nodeId))
            .build()
        NotificationManagerCompat.from(context).notify(DONE_ID, notification)
    }
}

/**
 * Session controls from the lock screen.
 *
 * All four go through the same places the in-app buttons do, so the ledger cannot tell which surface
 * ended a session. Done ends it as well as finishing the task, because a task you have just marked
 * finished is not still being worked on.
 *
 * **Restore first, and this is the whole reason the buttons used to do nothing.** A broadcast can
 * arrive at a process that has just been created to receive it, where [FocusTimer] has not yet read
 * the live session back off disk — `AppContainer` kicks that off asynchronously, and it had not
 * finished by the time this receiver looked. So the state was null, the receiver returned, and Stop
 * on a running session was a button that reported success by doing nothing, exactly in the case a
 * lock-screen control exists for. [FocusTimer.restoreIfNeeded] is idempotent and mutex-guarded, so
 * awaiting it here costs nothing when the app is already awake.
 */
class SessionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as App).container
        val action = intent.action ?: return
        val pending = goAsync()
        container.appScope.launch {
            try {
                container.timer.restoreIfNeeded()
                // The live session while there is one; the button's own subject otherwise, which is
                // the only thing the completion bell can offer. See [EXTRA_NODE_ID].
                val nodeId = container.running.timingId
                    ?: intent.getStringExtra(SessionNotification.EXTRA_NODE_ID)
                    ?: return@launch
                when (action) {
                    // Stop ends the session and leaves the task marked — you stopped timing, not
                    // working. Done finishes the task, which clears the mark on its own.
                    SessionNotification.ACTION_STOP -> container.running.stopTiming()
                    SessionNotification.ACTION_DONE -> {
                        container.running.stopTiming()
                        container.nodes.setDone(nodeId, true)
                        SessionNotification.clearCompleted(context)
                    }
                    SessionNotification.ACTION_PAUSE -> container.timer.pause()
                    SessionNotification.ACTION_RESUME -> container.timer.resume()
                }
            } finally {
                pending.finish()
            }
        }
    }
}
