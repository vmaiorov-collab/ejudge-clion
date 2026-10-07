package com.ejudge.clion

import com.intellij.ide.BrowserUtil
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.LocalDate

/**
 * Once a day asks GitHub for the latest release and tells the user when it is newer than the installed plugin.
 * Also registers the plugin repository of the project, so CLion itself offers the update in Settings | Plugins.
 */
class UpdateCheck : ProjectActivity {
    override suspend fun execute(project: Project) {
        registerRepository()
        val state = EjudgeSettings.getInstance().state
        val today = LocalDate.now().toString()
        if (state.lastUpdateCheck == today) return
        try {
            val request = HttpRequest.newBuilder(URI.create("https://api.github.com/repos/$REPO/releases/latest"))
                .header("Accept", "application/vnd.github+json").timeout(Duration.ofSeconds(10)).GET().build()
            val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) return
            state.lastUpdateCheck = today
            val tag = Regex("\"tag_name\"\\s*:\\s*\"v?([^\"]+)\"").find(response.body())?.groupValues?.get(1) ?: return
            val current = PluginManagerCore.getPlugin(PluginId.getId("com.ejudge.clion"))?.version ?: return
            if (!isNewer(tag, current) || state.notifiedVersion == tag) return
            state.notifiedVersion = tag
            NotificationGroupManager.getInstance().getNotificationGroup("Ejudge")
                .createNotification("Вышла новая версия плагина Ejudge: $tag (у вас $current)", NotificationType.INFORMATION)
                .addAction(NotificationAction.createSimple("Что нового и как обновить") { BrowserUtil.browse("https://github.com/$REPO/releases/latest") })
                .notify(project)
        } catch (_: Exception) {
            // offline: try again on the next start
        }
    }

    private fun registerRepository() {
        try {
            val hosts = com.intellij.openapi.updateSettings.impl.UpdateSettings.getInstance().storedPluginHosts
            if (REPOSITORY !in hosts) hosts.add(REPOSITORY)
        } catch (_: Throwable) {
            // the notification above still works
        }
    }

    companion object {
        const val REPO = "vmaiorov-collab/ejudge-clion"
        const val REPOSITORY = "https://vmaiorov-collab.github.io/ejudge-clion/updatePlugins.xml"

        fun isNewer(latest: String, current: String): Boolean {
            fun parts(v: String) = v.split('.', '-').map { it.toIntOrNull() ?: 0 }
            val a = parts(latest)
            val b = parts(current)
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }
}
