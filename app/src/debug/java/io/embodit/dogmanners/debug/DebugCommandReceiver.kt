package io.embodit.dogmanners.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.embodit.dogmanners.platform.DogLog

/**
 * Debug builds only. Forwards adb broadcasts to the running Stage (action `io.embodit.dogmanners.DEBUG`);
 * see [DebugCommands] for the command list.
 *
 *   adb -s emulator-5568 shell "am broadcast -a io.embodit.dogmanners.DEBUG -p io.embodit.dogmanners --es cmd setTime --es s 2026-10-20T21:30"
 *
 * Extras: `cmd` = command name, `s` = text argument, `a` = comma-separated numbers. For convenience
 * the text argument may also follow the name inside `cmd` (`--es cmd "pull Ori"`).
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val raw = intent.getStringExtra("cmd") ?: return
        val parts = raw.trim().split(Regex("\\s+"), limit = 2)
        val cmd = parts[0]
        val s = intent.getStringExtra("s") ?: parts.getOrNull(1)
        val a = intent.getStringExtra("a")
        DogLog.i("CMD recv '$cmd' s='$s' a='$a'")
        // broadcasts arrive on the main thread, which is where the ViewModel and the scene live
        DebugCommands.run(context.applicationContext, cmd, s, a)
    }
}
