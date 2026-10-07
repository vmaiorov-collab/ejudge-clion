package com.ejudge.clion

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.file.Files
import java.nio.file.Path

internal fun notify(project: Project?, text: String, type: NotificationType = NotificationType.INFORMATION) {
    NotificationGroupManager.getInstance().getNotificationGroup("Ejudge")
        .createNotification(text, type).notify(project)
}

internal const val MARKER = ".ejudge"

object EjudgeEvents {
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    fun addListener(l: () -> Unit) = listeners.add(l)
    fun fireChanged() = listeners.forEach { it() }
}

class ImportContestAction : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        start(e.project ?: return)
    }

    fun start(project: Project) {
        val base = EjudgeSettings.getInstance().contestsPath()
        if (EjudgeSettings.getInstance().state.serverUrl.isBlank()) {
            notify(project, "Configure the server in Settings | Tools | Ejudge first", NotificationType.WARNING)
            return
        }
        loadThenChoose(project, "Loading parallels", "Download all contests of the parallel", { AlgocodeCatalog.parallels() }) { parallel ->
            importAll(project, base, parallel)
        }
    }

    private fun <T> loadThenChoose(project: Project, title: String, popupTitle: String, load: () -> List<T>, onChosen: (T) -> Unit) {
        object : Task.Backgroundable(project, title, false) {
            private var items: List<T> = emptyList()
            override fun run(indicator: ProgressIndicator) {
                try {
                    items = load()
                } catch (ex: Exception) {
                    notify(project, "$title failed: ${ex.message}", NotificationType.ERROR)
                }
            }

            override fun onSuccess() {
                if (items.isEmpty()) {
                    notify(project, "Nothing found: $popupTitle", NotificationType.WARNING)
                    return
                }
                JBPopupFactory.getInstance().createPopupChooserBuilder(items)
                    .setTitle(popupTitle)
                    .setItemChosenCallback { onChosen(it) }
                    .createPopup()
                    .showCenteredInCurrentWindow(project)
            }
        }.queue()
    }

    private fun importAll(project: Project, base: Path, parallel: Parallel) {
        val s = EjudgeSettings.getInstance()
        object : Task.Backgroundable(project, "Downloading ${parallel.name}", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    indicator.text = "Loading the contest list"
                    val contests = AlgocodeCatalog.contests(parallel)
                    if (contests.isEmpty()) throw EjudgeException("No ejudge contests found for ${parallel.name}")
                    val failed = mutableListOf<String>()
                    var done = 0
                    for ((i, contest) in contests.withIndex()) {
                        indicator.checkCanceled()
                        indicator.text = "${contest.topic} (${i + 1}/${contests.size})"
                        indicator.fraction = i.toDouble() / contests.size
                        try {
                            importContest(s, base, parallel, contest, indicator)
                            done++
                        } catch (ex: com.intellij.openapi.progress.ProcessCanceledException) {
                            throw ex
                        } catch (ex: Exception) {
                            failed += "${contest.topic}: ${ex.message}"
                        }
                    }
                    CMakeGen.generate(base)
                    LocalFileSystem.getInstance().refreshAndFindFileByNioFile(base)?.refresh(true, true)
                    EjudgeEvents.fireChanged()
                    if (failed.isEmpty()) {
                        notify(project, "${parallel.name}: downloaded $done contests into $base")
                    } else {
                        notify(project, "${parallel.name}: downloaded $done of ${contests.size}. Failed: ${failed.joinToString("; ")}", NotificationType.WARNING)
                    }
                } catch (ex: com.intellij.openapi.progress.ProcessCanceledException) {
                    EjudgeEvents.fireChanged()
                    throw ex
                } catch (ex: Exception) {
                    notify(project, "Ejudge download failed: ${ex.message}", NotificationType.ERROR)
                }
            }
        }.queue()
    }

    private fun importContest(s: EjudgeSettings, base: Path, parallel: Parallel, contest: ContestInfo, indicator: ProgressIndicator) {
        val client = EjudgeClient(s.state.serverUrl, contest.id)
        client.login(s.state.login, s.password)
        val problems = client.problems()
        if (problems.isEmpty()) throw EjudgeException("no problems found")
        val root = base.resolve(folderName(parallel, contest))
        for (p in problems) {
            indicator.checkCanceled()
            indicator.text2 = "Problem ${p.name}"
            val dir = root.resolve(p.name.replace(Regex("[^\\w.-]"), "_"))
            Files.createDirectories(dir.resolve("tests"))
            Files.list(dir.resolve("tests")).use { it.toList() }.forEach { Files.deleteIfExists(it) }
            val st = client.statement(p)
            val body = statementBody(st)
            StatementImages.localize(client, body, dir)
            Files.writeString(dir.resolve("statement.html"), "<html><head><meta charset=\"utf-8\"></head><body>${body.html()}</body></html>")
            client.samples(st).forEachIndexed { n, t ->
                Files.writeString(dir.resolve("tests/${n + 1}.in"), t.input)
                Files.writeString(dir.resolve("tests/${n + 1}.out"), t.output)
            }
            Files.writeString(dir.resolve(MARKER), "${p.id}\n${p.name}\n${contest.id}\n")
            val main = dir.resolve("main.cpp")
            if (!Files.exists(main)) Files.writeString(main, TEMPLATE)
        }
    }

    private fun folderName(parallel: Parallel, contest: ContestInfo) =
        "${parallel.name}_${contest.topic}_${contest.id}".replace(Regex("[^\\p{L}\\d.-]+"), "_")

    companion object {
        fun start(project: Project) = ImportContestAction().start(project)
        const val TEMPLATE = "#include <bits/stdc++.h>\nusing namespace std;\n\nint main() {\n    return 0;\n}\n"
    }
}
