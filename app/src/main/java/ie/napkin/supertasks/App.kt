package ie.napkin.supertasks

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import ie.napkin.supertasks.data.db.AppDatabase
import ie.napkin.supertasks.data.repo.InkRepository
import ie.napkin.supertasks.data.repo.LabelRepository
import ie.napkin.supertasks.data.repo.NodeRepository
import ie.napkin.supertasks.data.repo.PomodoroRepository
import ie.napkin.supertasks.data.repo.PropertyRepository
import ie.napkin.supertasks.data.repo.SmartListRepository
import ie.napkin.supertasks.data.seed.Seeder
import ie.napkin.supertasks.domain.PomodoroTimer
import ie.napkin.supertasks.reminders.ReminderManager
import ie.napkin.supertasks.reminders.ReminderScheduler
import ie.napkin.supertasks.reminders.Reminders
import ie.napkin.supertasks.ui.theme.LauncherIcon
import ie.napkin.supertasks.ui.theme.loadThemeController
import ie.napkin.supertasks.widget.PomodoroFinalizeWorker
import ie.napkin.supertasks.widget.PomodoroWidget
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class App : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(Reminders.CHANNEL_ID, "Reminders", NotificationManager.IMPORTANCE_HIGH)
        )
        // Component enabled-states are package state, not app data: a reinstall resets them to the
        // manifest defaults while the stored accent survives in prefs, which would leave a coral
        // icon on an indigo app. Cheap to check and a no-op whenever they already agree.
        container.appScope.launch {
            LauncherIcon.apply(this@App, loadThemeController(this@App).accent)
        }
    }
}

/** Plain-manual DI: one graph for the whole app. */
class AppContainer(app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val db: AppDatabase = AppDatabase.build(app)
    val nodes = NodeRepository(db)
    val properties = PropertyRepository(db)
    val labels = LabelRepository(db)
    val smartLists = SmartListRepository(db)
    val pomodoro = PomodoroRepository(db)
    val ink = InkRepository(db)
    val timer = PomodoroTimer(pomodoro, appScope)
    val reminderScheduler = ReminderScheduler(app)
    val reminders = ReminderManager(db, reminderScheduler, appScope)

    /** First-run seeding; the splash joins this before resolving the Today smart list. */
    val seeding: Job = appScope.launch {
        Seeder.seedIfEmpty(db)
        // A list holds tasks; anything else that got onto one is gathered onto a task there.
        nodes.tidyListsToTasksOnly()
    }

    init {
        // Any process wake (widget tap, worker, activity) revives a live focus session.
        appScope.launch { timer.restoreIfNeeded() }
        // Pomodoro widget re-renders on state *transitions* only — never per tick (the widget's
        // Chronometer handles the live countdown). Also (de)schedules the dead-process finalizer.
        appScope.launch {
            timer.state
                .map { s -> s?.let { Triple(it.sessionId, it.isRunning, it.isFinished) } }
                .distinctUntilChanged()
                .collect {
                    val s = timer.state.value
                    if (s != null && s.isRunning && !s.isFinished) {
                        PomodoroFinalizeWorker.schedule(app, s.remainingSecs)
                    } else if (s == null || s.isFinished) {
                        PomodoroFinalizeWorker.cancel(app)
                    }
                    PomodoroWidget().updateAll(app)
                }
        }
    }
}
