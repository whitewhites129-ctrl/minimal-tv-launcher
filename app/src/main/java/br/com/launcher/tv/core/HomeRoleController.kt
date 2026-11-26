package br.com.launcher.tv.core

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock

/** Read-only view of Android's HOME role. Never starts an Activity. */
enum class HomeRoleState {
    HELD_BY_THIS_APP,
    AVAILABLE_NOT_HELD,
    NOT_AVAILABLE,
    UNKNOWN
}

data class HomeRoleSnapshot(
    val state: HomeRoleState,
    val packageName: String,
    val checkedAtElapsedRealtime: Long,
    val detail: String? = null
) {
    val isHeldByThisApp: Boolean
        get() = state == HomeRoleState.HELD_BY_THIS_APP
}

/**
 * Centralizes HOME-role checks without attempting to correct Android's
 * selection or steal foreground focus.
 */
class HomeRoleController(context: Context) {
    private val appContext = context.applicationContext

    fun snapshot(): HomeRoleSnapshot {
        val checkedAt = SystemClock.elapsedRealtime()
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val manager = appContext.getSystemService(RoleManager::class.java)
                    ?: return HomeRoleSnapshot(
                        HomeRoleState.UNKNOWN, appContext.packageName, checkedAt,
                        "RoleManager indisponível"
                    )

                when {
                    !manager.isRoleAvailable(RoleManager.ROLE_HOME) ->
                        HomeRoleSnapshot(
                            HomeRoleState.NOT_AVAILABLE, appContext.packageName, checkedAt,
                            "A função HOME não está disponível neste dispositivo"
                        )
                    manager.isRoleHeld(RoleManager.ROLE_HOME) ->
                        HomeRoleSnapshot(
                            HomeRoleState.HELD_BY_THIS_APP, appContext.packageName, checkedAt
                        )
                    else ->
                        HomeRoleSnapshot(
                            HomeRoleState.AVAILABLE_NOT_HELD, appContext.packageName, checkedAt
                        )
                }
            } else {
                legacySnapshot(checkedAt)
            }
        } catch (error: Exception) {
            HomeRoleSnapshot(
                HomeRoleState.UNKNOWN, appContext.packageName, checkedAt,
                error.javaClass.simpleName
            )
        }
    }

    private fun legacySnapshot(checkedAt: Long): HomeRoleSnapshot {
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val ownActivityExists = appContext.packageManager
            .queryIntentActivities(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
            .any { it.activityInfo?.packageName == appContext.packageName }

        if (!ownActivityExists) {
            return HomeRoleSnapshot(
                HomeRoleState.NOT_AVAILABLE, appContext.packageName, checkedAt,
                "Nenhuma Activity HOME deste aplicativo foi encontrada"
            )
        }

        val selectedPackage = appContext.packageManager
            .resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName

        return if (selectedPackage == appContext.packageName) {
            HomeRoleSnapshot(
                HomeRoleState.HELD_BY_THIS_APP, appContext.packageName, checkedAt
            )
        } else {
            HomeRoleSnapshot(
                HomeRoleState.AVAILABLE_NOT_HELD, appContext.packageName, checkedAt,
                selectedPackage?.let { "HOME selecionada: $it" }
            )
        }
    }
}
