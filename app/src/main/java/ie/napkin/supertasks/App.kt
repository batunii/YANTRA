package ie.napkin.supertasks

import android.app.Application
import ie.napkin.supertasks.data.db.AppDatabase
import ie.napkin.supertasks.data.repo.InkRepository
import ie.napkin.supertasks.data.repo.LabelRepository
import ie.napkin.supertasks.data.repo.NodeRepository
import ie.napkin.supertasks.data.repo.PomodoroRepository
import ie.napkin.supertasks.data.repo.PropertyRepository
import ie.napkin.supertasks.data.repo.SmartListRepository
import ie.napkin.supertasks.data.seed.Seeder
import ie.napkin.supertasks.domain.PomodoroTimer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class App : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
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

    init {
        appScope.launch { Seeder.seedIfEmpty(db) }
    }
}
