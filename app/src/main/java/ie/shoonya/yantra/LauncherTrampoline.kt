package ie.shoonya.yantra

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * What the launcher icon opens: MainActivity in its own task, then nothing.
 *
 * The icon is whichever accent alias is enabled (see `LauncherIcon`), and a task remembers the
 * component it was started through. When a component is disabled, Android removes every task whose
 * root intent names it — `DONT_KILL_APP` keeps the process, not the task. So while the aliases
 * opened MainActivity directly, picking a new accent disabled the alias the app had been opened
 * through and the app vanished from under the tap: it looked exactly like a crash, every time.
 *
 * Starting MainActivity by class with `NEW_TASK` puts it in the app's usual task, found by the
 * package affinity, whose root intent is MainActivity and survives any alias being switched off.
 * This activity has an empty affinity and no history, so the only task that ever names an alias is
 * the empty one it leaves behind. Tapping the icon with the app already open finds that usual task
 * and brings it forward, since the intent below matches the one it was started with.
 */
class LauncherTrampoline : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(this, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        )
        // Theme.NoDisplay: finishing before onResume is the contract, or the system throws.
        finish()
    }
}
