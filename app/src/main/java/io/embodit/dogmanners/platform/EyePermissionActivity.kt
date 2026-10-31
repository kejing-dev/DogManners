package io.embodit.dogmanners.platform

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle

/**
 * Transparent helper for `com.picovr.permission.EYE_TRACKING` (SDK doc "Eye tracking": runtime permission, Full Space
 * only). Refused or no dialog: the stare falls back to the head direction and P1 says so (S4). From Rehearsal Hall.
 */
class EyePermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        if (granted(this)) {
            onResult?.invoke(true, false)
            finish()
            return
        }
        requestPermissions(arrayOf(PERMISSION), REQUEST)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val ok = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        // no rationale after a refusal = the system will not show the dialog again ("don't ask again")
        val blocked = !ok && !shouldShowRequestPermissionRationale(PERMISSION)
        DogLog.i("EYE permission granted=$ok blocked=$blocked")
        onResult?.invoke(ok, blocked)
        finish()
    }

    companion object {
        const val PERMISSION = "com.picovr.permission.EYE_TRACKING"
        private const val REQUEST = 53

        /** (granted, blocked) on the main thread. */
        @Volatile var onResult: ((Boolean, Boolean) -> Unit)? = null

        fun granted(context: Context): Boolean = context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

        fun open(context: Context): Boolean = runCatching { context.startActivity(Intent(context, EyePermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    }
}
