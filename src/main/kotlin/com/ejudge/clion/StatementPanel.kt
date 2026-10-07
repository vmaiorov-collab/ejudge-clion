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
            statementHtml = content.html()
            title.text = dir.fileName.toString()
            render()
        } catch (ex: Exception) {
            statementHtml = "<p>Could not show the statement: ${org.jsoup.nodes.Entities.escape(ex.message ?: "")}</p>"
            render()
        }
        if (activate || autoShow) ToolWindowManager.getInstance(project).getToolWindow(ID)?.show()
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
