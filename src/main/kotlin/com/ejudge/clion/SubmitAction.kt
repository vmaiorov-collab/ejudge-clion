package com.ejudge.clion

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path

class SubmitAction : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.getData(CommonDataKeys.VIRTUAL_FILE)?.let { findMarker(Path.of(it.path)) } != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val vf = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        submitFile(project, Path.of(vf.path))
    }

    companion object {
        fun findMarker(file: Path): Path? {
            var dir: Path? = file.parent
            while (dir != null) {
                val m = dir.resolve(MARKER)
                if (Files.isRegularFile(m)) return m
                dir = dir.parent
            }
            return null
        }

        fun saveDocuments() = com.intellij.openapi.application.WriteIntentReadAction.run {
            FileDocumentManager.getInstance().saveAllDocuments()
        }

        fun submitFile(project: Project, file: Path) {
            saveDocuments()
            val marker = findMarker(file)
            if (marker == null) {
                notify(project, "File is not inside an imported problem folder", NotificationType.WARNING)
                return
            }
            val lines = Files.readAllLines(marker)
            val id = lines[0]
            val name = lines[1]
            val contest = lines.getOrNull(2)?.toIntOrNull()
            val hint = when (file.fileName.toString().substringAfterLast('.', "").lowercase()) {
                "cpp", "cc", "cxx" -> "g++"
                "c" -> "gcc"
                "py" -> "python"
                "java" -> "java"
                "kt" -> "kotlin"
                "rs" -> "rust"
                else -> file.fileName.toString().substringAfterLast('.', "")
            }
            val s = EjudgeSettings.getInstance()
            object : Task.Backgroundable(project, "Submitting to ejudge", true) {
                override fun run(indicator: ProgressIndicator) {
                    try {
                        val client = EjudgeClient(s.state.serverUrl, contest ?: s.state.contestId)
                        client.login(s.state.login, s.password)
                        val before = client.runs().maxOfOrNull { it.id } ?: 0
                        val result = client.submit(Problem(name, "", id), file, hint)
                        indicator.text = "Waiting for the verdict ($result)"
                        notify(project, "$name sent ($result). Waiting for the verdict…")
                        val run = client.awaitRun(before) { indicator.text2 = it.describe() }
                        when {
                            run == null -> {
                                val dump = s.contestsPath().resolve("debug-runs.html")
                                Files.createDirectories(dump.parent)
                                Files.writeString(dump, client.lastRunsHtml)
                                notify(project, "Submitted $name, but the run did not appear in the runs table (page saved to $dump)", NotificationType.WARNING)
                            }
                            run.inProgress -> notify(project, "$name: still judging after timeout. ${run.describe()}", NotificationType.WARNING)
                            else -> notify(
                                project, "$name — run #${run.id}: ${run.describe()}",
                                if (run.result.contains(Regex("^(OK|Accepted|Полное|Принято|Зачтено)", RegexOption.IGNORE_CASE))) NotificationType.INFORMATION else NotificationType.ERROR
                            )
                        }
                        EjudgeEvents.fireChanged()
                    } catch (ex: Exception) {
                        notify(project, "Ejudge submit failed: ${ex.message}", NotificationType.ERROR)
                    }
                }
            }.queue()
        }
    }
}
