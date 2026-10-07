package com.ejudge.clion

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.LocalDate

/**
 * Anonymous usage counter. It sends no identifier, no login and no contest data:
 * only two public counters are incremented, "installs" (once per installation)
 * and "active-<date>" (once per day per installation). Can be turned off in the settings.
 */
class UsageStats : ProjectActivity {
    override suspend fun execute(project: Project) {
        val settings = EjudgeSettings.getInstance()
        val state = settings.state
        if (!state.sendStats) return
        val today = LocalDate.now().toString()
        if (state.lastStatsDay == today) return
        try {
            if (!state.installCounted) {
                hit("installs")
                state.installCounted = true
            }
            hit("active-$today")
            state.lastStatsDay = today
        } catch (_: Exception) {
            // offline or the counter is down: try again on the next start
        }
    }

    private fun hit(key: String) {
        val request = HttpRequest.newBuilder(URI.create("$BASE/hit/$NAMESPACE/$key"))
            .timeout(Duration.ofSeconds(10)).GET().build()
        val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding())
        if (response.statusCode() !in 200..299) throw IllegalStateException("HTTP ${response.statusCode()}")
    }

    companion object {
        const val BASE = "https://abacus.jasoncameron.dev"
        const val NAMESPACE = "ejudge-clion-vmaiorov"
    }
}
