package com.ejudge.clion

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import org.jsoup.Jsoup
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import javax.swing.JButton
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.text.html.HTMLEditorKit

/** Problem statement shown in its own tool window on the left side of the IDE. */
class StatementToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(StatementPanel.get(project), "", false))
    }
}

class StatementPanel private constructor(private val project: Project) : JPanel(BorderLayout()) {
    var autoShow = true
    private var fontPt = 14
    private var statementHtml = ""
    private val title = JBLabel(" ").apply { border = JBUI.Borders.empty(0, 8) }
    private val html = JEditorPane().apply {
        isEditable = false
        border = JBUI.Borders.empty(8)
    }

    init {
        val smaller = JButton("A−").apply { toolTipText = "Smaller text" }
        val larger = JButton("A+").apply { toolTipText = "Larger text" }
        val close = JButton(AllIcons.Actions.Close).apply { toolTipText = "Close the statement" }
        smaller.addActionListener { fontPt = (fontPt - 1).coerceAtLeast(10); render() }
        larger.addActionListener { fontPt = (fontPt + 1).coerceAtMost(24); render() }
        close.addActionListener {
            autoShow = false
            ToolWindowManager.getInstance(project).getToolWindow(ID)?.hide()
        }
        val bar = JPanel(BorderLayout()).apply {
            add(title, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), JBUI.scale(2))).apply { add(smaller); add(larger); add(close) }, BorderLayout.EAST)
        }
        add(bar, BorderLayout.NORTH)
        add(JBScrollPane(html), BorderLayout.CENTER)
    }

    /** Shows the statement of [dir]. With [activate] the tool window is opened; otherwise only when autoShow is on. */
    fun show(dir: Path, activate: Boolean) {
        val f = dir.resolve("statement.html")
        if (!Files.exists(f)) return
        try {
            val content = statementBody(Jsoup.parse(Files.readString(f)))
            content.select(".math").forEach { it.html(TexText.toHtml(it.text())) }
            content.select("table").filter { !it.hasClass("line-table-wb") }.forEach { t ->
                t.attr("border", "1").attr("cellspacing", "0").attr("cellpadding", "6").attr("width", "100%")
                t.select("td, th").forEach { it.addClass("g") }
            }
            var remote = false
            for (img in content.select("img")) {
                val src = img.attr("src")
                val local = if (src.startsWith("http")) null else dir.resolve(src)
                if (local == null || !Files.exists(local)) {
                    remote = remote || local == null
                    img.replaceWith(org.jsoup.nodes.TextNode("[picture is loading…]"))
                    continue
                }
                img.attr("src", local.toUri().toString())
                img.removeAttr("style")
                try {
                    javax.imageio.ImageIO.read(local.toFile())?.let {
                        val w = minOf(it.width, 560)
                        img.attr("width", w.toString()).attr("height", (it.height * w / it.width).toString())
                    }
                } catch (_: Exception) {}
            }
            if (remote) fetchImages(dir)
            statementHtml = content.html()
            title.text = dir.fileName.toString()
            render()
        } catch (ex: Exception) {
            statementHtml = "<p>Could not show the statement: ${org.jsoup.nodes.Entities.escape(ex.message ?: "")}</p>"
            render()
        }
        if (activate || autoShow) ToolWindowManager.getInstance(project).getToolWindow(ID)?.show()
    }

    private val fetching = java.util.concurrent.ConcurrentHashMap.newKeySet<Path>()

    /** Old imports kept session-bound image URLs: download the pictures now and show the statement again. */
    private fun fetchImages(dir: Path) {
        if (!fetching.add(dir)) return
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val marker = Files.readAllLines(dir.resolve(MARKER))
                val id = marker[0].trim()
                val contestId = marker[2].trim().toInt()
                val s = EjudgeSettings.getInstance()
                val client = EjudgeClient(s.state.serverUrl, contestId)
                client.login(s.state.login, s.password)
                val problem = client.problems().first { it.id == id }
                val body = statementBody(client.statement(problem))
                StatementImages.localize(client, body, dir)
                Files.writeString(dir.resolve("statement.html"), "<html><head><meta charset=\"utf-8\"></head><body>${body.html()}</body></html>")
                if (body.select("img").none { it.attr("src").startsWith("http") }) {
                    com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater { show(dir, false) }
                }
            } catch (ex: Exception) {
                com.intellij.openapi.diagnostic.Logger.getInstance("Ejudge").warn("Could not download statement images: ${ex.message}", ex)
            } finally {
                fetching.remove(dir)
            }
        }
    }

    private fun render() {
        val fg = ColorUtil.toHex(UIUtil.getLabelForeground())
        val codeBg = ColorUtil.toHex(UIUtil.getPanelBackground().let { if (JBColor.isBright()) ColorUtil.darker(it, 1) else ColorUtil.brighter(it, 1) })
        html.editorKit = HTMLEditorKit().apply {
            styleSheet.addRule("body { font-family: sans-serif; font-size: ${fontPt}pt; color: #$fg; margin: 6px; }")
            styleSheet.addRule("p { margin-top: 6px; margin-bottom: 6px; }")
            styleSheet.addRule("h2 { font-size: ${fontPt + 5}pt; margin-top: 4px; margin-bottom: 8px; }")
            styleSheet.addRule("h3 { font-size: ${fontPt + 3}pt; margin-top: 16px; margin-bottom: 6px; }")
            styleSheet.addRule("h4 { font-size: ${fontPt + 1}pt; margin-top: 10px; margin-bottom: 2px; }")
            styleSheet.addRule("pre { font-family: monospace; font-size: ${fontPt}pt; background-color: #$codeBg; margin: 4px; padding: 6px; }")
            styleSheet.addRule("tt { font-family: monospace; }")
            styleSheet.addRule("td { padding: 2px 10px 2px 0px; }")
            styleSheet.addRule("td.g, th.g { border: 1px solid #${ColorUtil.toHex(JBColor.border())}; padding: 5px; }")
        }
        html.text = "<html><body>$statementHtml</body></html>"
        html.caretPosition = 0
    }

    companion object {
        const val ID = "Ejudge Statement"
        private val panels = ConcurrentHashMap<Project, StatementPanel>()

        fun get(project: Project): StatementPanel = panels.computeIfAbsent(project) {
            Disposer.register(project, Disposable { panels.remove(project) })
            StatementPanel(project)
        }
    }
}
