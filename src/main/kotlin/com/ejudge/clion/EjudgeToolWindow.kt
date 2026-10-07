package com.ejudge.clion

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.ColorUtil
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import org.jsoup.Jsoup
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JMenuItem
import javax.swing.JToggleButton
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.text.html.HTMLEditorKit

class EjudgeToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(EjudgePanel(project), "", false))
    }
}

private class ContestDir(val dir: Path) {
    override fun toString() = dir.fileName.toString().replace('_', ' ')
}

private class ProblemDir(val dir: Path, val letter: String, val title: String)

private class EjudgePanel(private val project: Project) : JPanel(CardLayout()) {
    private val mainPanel = JPanel(BorderLayout())
    private val contests = ComboBox<ContestDir>()
    private val problems = DefaultListModel<ProblemDir>()
    private val problemList = JBList(problems)
    private val states = mutableMapOf<String, ProblemState>()
    private val results = DefaultListModel<TestResult>()
    private val resultList = JBList(results)
    private val details = JBTextArea().apply {
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, JBUI.scaleFontSize(12f))
        margin = JBUI.insets(8)
    }
    private val status = JBLabel(" ").apply { border = JBUI.Borders.empty(4, 8) }
    private val cards = JPanel(CardLayout())
    private val testButton = JButton("Test on examples", AllIcons.Actions.Execute)
    private val body = JBSplitter(true, 0.42f)
    private var lastInput = ""
    private val inputArea = JBTextArea().apply {
        font = Font(Font.MONOSPACED, Font.PLAIN, JBUI.scaleFontSize(12f))
        margin = JBUI.insets(6)
        emptyText.text = "Type the input here"
    }
    private val outputArea = JBTextArea().apply {
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, JBUI.scaleFontSize(12f))
        margin = JBUI.insets(6)
        emptyText.text = "The program output appears here"
    }
    private val runNowButton = JButton("Run  (⌘/Ctrl+Enter)", AllIcons.Actions.Execute)
    private val savedInputs = HashMap<Path, String>()
    private var runDir: Path? = null
    private val topButtons = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0))
    private var programmatic = 0
    private var lockUntil = 0L
    private val collapseButton = JButton(AllIcons.General.ArrowDown).apply { toolTipText = "Hide the results area" }
    private var pendingSelect: Path? = null
    private var allProblems: List<ProblemDir> = emptyList()
    private var filtering = 0
    private val search = JBTextField().apply { emptyText.text = "Search problems" }
    private val favOnly = JToggleButton(AllIcons.Nodes.Favorite).apply { toolTipText = "Show only favorite problems" }
    private val summary = JBLabel(" ").apply { foreground = UIUtil.getContextHelpForeground() }
    private var lastRunOk = false
    private val expectedArea = JBTextArea().apply {
        font = Font(Font.MONOSPACED, Font.PLAIN, JBUI.scaleFontSize(12f))
        margin = JBUI.insets(6)
        emptyText.text = "Expected answer for “Save as test” (optional: if empty, the output of the last run is used)"
    }
    private val deleteTestButton = JButton("Delete this test", AllIcons.Actions.GC).apply { isEnabled = false }
    private var lastRunInput = ""
    private val saveTestButton = JButton("Save as test", AllIcons.Actions.MenuSaveall)
    private val historyModel = DefaultListModel<Run>()
    private val historyList = JBList(historyModel)
    private val historyCode = JBTextArea().apply {
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, JBUI.scaleFontSize(12f))
        margin = JBUI.insets(8)
    }
    private val historyOpen = JButton("Open as file", AllIcons.Actions.MenuOpen).apply { isEnabled = false }
    private var historyDir: Path? = null
    private var historyClient: EjudgeClient? = null
    private var historyText = ""

    init {
        val importButton = JButton("Import contest", AllIcons.Actions.Download)
        val refreshButton = JButton(AllIcons.Actions.Refresh).apply { toolTipText = "Refresh" }
        collapseButton.addActionListener { setBottomVisible(!cards.isVisible) }
        val top = JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            border = JBUI.Borders.empty(8)
            add(JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
                add(contests, BorderLayout.CENTER)
                add(topButtons.apply { add(importButton); add(refreshButton); add(collapseButton) }, BorderLayout.EAST)
            }, BorderLayout.NORTH)
            add(JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
                add(search, BorderLayout.CENTER)
                add(favOnly, BorderLayout.EAST)
            }, BorderLayout.CENTER)
            add(summary, BorderLayout.SOUTH)
        }

        problemList.emptyText.text = "No problems yet"
        problemList.emptyText.appendLine("Press “Import contest” to download one")
        problemList.cellRenderer = object : ColoredListCellRenderer<ProblemDir>() {
            override fun customizeCellRenderer(list: JList<out ProblemDir>, value: ProblemDir, index: Int, selected: Boolean, hasFocus: Boolean) {
                border = JBUI.Borders.empty(3, 8)
                val st = states[value.letter]
                val green = JBColor(0x2E8B57, 0x62B543)
                val red = JBColor(0xD0312D, 0xFF6B68)
                icon = when {
                    st == null -> com.intellij.util.ui.EmptyIcon.ICON_16
                    st.solved -> AllIcons.RunConfigurations.TestPassed
                    else -> AllIcons.RunConfigurations.TestFailed
                }
                val letterColor = when { st == null -> null; st.solved -> green; else -> red }
                if (isFavorite(value)) append("★ ", SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, JBColor(0xE6A100, 0xF2C94C)))
                append(value.letter, if (letterColor == null) SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES else SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, letterColor))
                if (value.title.isNotBlank()) append("   ${value.title}", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                if (st != null && !st.solved) append("   ${st.verdict}", SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, red))
            }
        }
        resultList.emptyText.text = "Press “Test” to run the examples"
        resultList.cellRenderer = object : ColoredListCellRenderer<TestResult>() {
            override fun customizeCellRenderer(list: JList<out TestResult>, value: TestResult, index: Int, selected: Boolean, hasFocus: Boolean) {
                border = JBUI.Borders.empty(3, 8)
                append(if (value.custom) "My test ${value.name}" else "Test ${value.name}", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                val color = if (value.ok) JBColor(0x2E8B57, 0x62B543) else JBColor(0xD0312D, 0xFF6B68)
                append("   ${value.verdict}", SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, color))
                append("   ${value.ms} ms" + (if (value.kb > 0) " · ${memText(value.kb)}" else ""), SimpleTextAttributes.GRAYED_ATTRIBUTES)
                if (value.warn.isNotBlank()) append("   ⚠ ${value.warn}", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBColor(0xC77700, 0xE0A030)))
            }
        }

        val statementButton = JButton("Show statement", AllIcons.Actions.Preview)
        val submitButton = JButton("Submit", AllIcons.Actions.Upload)
        val runButton = JButton("Run with my input", AllIcons.Actions.Lightning)
        val testSubmitButton = JButton("Test & submit", AllIcons.Actions.Commit)
        val historyButton = JButton("My submissions", AllIcons.Vcs.History)
        val actions = JPanel(GridLayout(0, 2, JBUI.scale(6), JBUI.scale(6))).apply {
            border = JBUI.Borders.empty(8)
            add(testButton); add(testSubmitButton); add(runButton)
            add(statementButton); add(submitButton); add(historyButton)
        }
        val problemPane = JPanel(BorderLayout()).apply {
            add(JBScrollPane(problemList), BorderLayout.CENTER)
            add(actions, BorderLayout.SOUTH)
        }

        runNowButton.addActionListener { executeRun() }
        for (key in listOf("ctrl ENTER", "meta ENTER")) {
            inputArea.registerKeyboardAction({ executeRun() }, javax.swing.KeyStroke.getKeyStroke(key), javax.swing.JComponent.WHEN_FOCUSED)
        }
        cards.add(JPanel(BorderLayout()).apply {
            add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(4))).apply { add(runNowButton); add(saveTestButton); add(JBLabel("Input → Output")) }, BorderLayout.NORTH)
            add(JBSplitter(true, 0.4f).apply {
                firstComponent = JBScrollPane(inputArea)
                secondComponent = JBSplitter(true, 0.5f).apply {
                    firstComponent = JBScrollPane(outputArea)
                    secondComponent = JBScrollPane(expectedArea)
                }
            }, BorderLayout.CENTER)
        }, "run")
        cards.add(JPanel(BorderLayout()).apply {
            add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(4))).apply {
                add(deleteTestButton)
                add(JBLabel("Only your own tests can be deleted"))
            }, BorderLayout.NORTH)
            add(JBSplitter(true, 0.5f).apply {
                firstComponent = JBScrollPane(resultList)
                secondComponent = JBScrollPane(details)
            }, BorderLayout.CENTER)
        }, "tests")
        historyList.emptyText.text = "No submissions of this problem yet"
        historyList.cellRenderer = object : ColoredListCellRenderer<Run>() {
            override fun customizeCellRenderer(list: JList<out Run>, value: Run, index: Int, selected: Boolean, hasFocus: Boolean) {
                border = JBUI.Borders.empty(3, 8)
                append("#${value.id}", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                val color = when { value.inProgress -> JBColor.GRAY; value.accepted -> JBColor(0x2E8B57, 0x62B543); else -> JBColor(0xD0312D, 0xFF6B68) }
                append("   ${value.result.ifBlank { "…" }}", SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, color))
                val test = value.column("тест|test")
                if (test.isNotBlank() && !value.accepted) append("  test $test", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, color))
                val rest = listOf("язык|lang", "врем|time|дата|date").map { value.column(it) }.filter { it.isNotBlank() }.joinToString(" · ")
                if (rest.isNotBlank()) append("   $rest", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
        cards.add(JBSplitter(false, 0.38f).apply {
            firstComponent = JBScrollPane(historyList)
            secondComponent = JPanel(BorderLayout()).apply {
                add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(4))).apply {
                    add(historyOpen)
                    add(JBLabel("The code that was sent to the server"))
                }, BorderLayout.NORTH)
                add(JBScrollPane(historyCode), BorderLayout.CENTER)
            }
        }, "history")

        body.apply {
            firstComponent = problemPane
            secondComponent = cards
        }
        mainPanel.add(top, BorderLayout.NORTH)
        mainPanel.add(body, BorderLayout.CENTER)
        mainPanel.add(status, BorderLayout.SOUTH)
        add(mainPanel, "main")
        add(buildWelcome(), "welcome")
        val helpButton = JButton(AllIcons.Actions.Help).apply { toolTipText = "How does it work?" }
        helpButton.addActionListener { showCard("welcome") }
        topButtons.add(helpButton)
        importButton.toolTipText = "Download all contests of a parallel"
        testButton.toolTipText = "Compile the solution and compare it with the examples from the statement"
        runButton.toolTipText = "Type your own input and see the output of your program"
        statementButton.toolTipText = "Open the statement on the left"
        submitButton.toolTipText = "Send the solution to the ejudge server and wait for the verdict"
        if (!EjudgeSettings.getInstance().state.onboardingDone) showCard("welcome")

        importButton.addActionListener { ImportContestAction.start(project) }
        refreshButton.addActionListener { refresh() }
        contests.addActionListener { loadProblems() }
        statementButton.addActionListener { selectedDir()?.let { StatementPanel.get(project).autoShow = true; showStatement(it) } ?: info("Select a problem first") }
        runButton.addActionListener { selectedDir()?.let { showRunCard(it) } ?: info("Select a problem first") }
        testButton.addActionListener { selectedDir()?.let { runTests(it, thenSubmit = false) } ?: info("Select a problem first") }
        testSubmitButton.addActionListener { selectedDir()?.let { runTests(it, thenSubmit = true) } ?: info("Select a problem first") }
        saveTestButton.addActionListener { saveRunAsTest() }
        deleteTestButton.addActionListener { resultList.selectedValue?.takeIf { it.custom }?.let { deleteTest(it) } }
        saveTestButton.toolTipText = "Keep this input (and the current output as the expected answer) as your own test. It runs together with the examples"
        testSubmitButton.toolTipText = "Check the examples and, if they all pass, send the solution to the server"
        historyButton.toolTipText = "All submissions of this problem with their verdicts and code"
        historyButton.addActionListener { selectedDir()?.let { showHistory(it) } ?: info("Select a problem first") }
        historyOpen.addActionListener { openHistoryFile() }
        historyList.addListSelectionListener { if (!it.valueIsAdjusting) loadHistorySource() }
        search.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = applyFilter()
            override fun removeUpdate(e: DocumentEvent) = applyFilter()
            override fun changedUpdate(e: DocumentEvent) = applyFilter()
        })
        favOnly.addActionListener { applyFilter() }
        problemList.componentPopupMenu = null
        problemList.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = popup(e)
            override fun mouseReleased(e: MouseEvent) = popup(e)
            private fun popup(e: MouseEvent) {
                if (!e.isPopupTrigger) return
                val i = problemList.locationToIndex(e.point)
                if (i < 0) return
                val p = problems[i]
                problemList.selectedIndex = i
                val item = JMenuItem(if (isFavorite(p)) "Remove from favorites" else "Add to favorites", AllIcons.Nodes.Favorite)
                item.addActionListener { toggleFavorite(p) }
                JPopupMenu().apply { add(item) }.show(problemList, e.x, e.y)
            }
        })
        resultList.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = popup(e)
            override fun mouseReleased(e: MouseEvent) = popup(e)
            private fun popup(e: MouseEvent) {
                if (!e.isPopupTrigger) return
                val i = resultList.locationToIndex(e.point)
                if (i < 0) return
                val r = results[i]
                if (!r.custom) return
                resultList.selectedIndex = i
                val item = JMenuItem("Delete this test", AllIcons.Actions.GC)
                item.addActionListener { deleteTest(r) }
                JPopupMenu().apply { add(item) }.show(resultList, e.x, e.y)
            }
        })
        submitButton.addActionListener {
            selectedSource()?.let { SubmitAction.submitFile(project, it) } ?: info("Select a problem first")
        }
        problemList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) selectedSource()?.let { open(it) }
            }
        })
        problemList.addListSelectionListener {
            if (it.valueIsAdjusting || filtering > 0) return@addListSelectionListener
            saveRunInput()
            results.clear()
            details.text = ""
            (cards.layout as CardLayout).show(cards, "tests")
            selectedDir()?.let { d ->
                StatementPanel.get(project).show(d, activate = false)
                if (programmatic == 0) switchTo(d)
            }
        }
        resultList.addListSelectionListener {
            deleteTestButton.isEnabled = resultList.selectedValue?.custom == true
            val r = resultList.selectedValue ?: return@addListSelectionListener
            details.text = "Input\n${r.input}\nExpected\n${r.expected}\nYour output\n${r.actual}" +
                (if (r.note.isNotBlank()) "\n${r.note}" else "")
            details.caretPosition = 0
        }
        EjudgeEvents.addListener(project) { refresh() }
        project.messageBus.connect(project).subscribe(
            com.intellij.openapi.fileEditor.FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : com.intellij.openapi.fileEditor.FileEditorManagerListener {
                override fun selectionChanged(event: com.intellij.openapi.fileEditor.FileEditorManagerEvent) {
                    event.newFile?.let { selectByFile(Path.of(it.path)) }
                }
            }
        )
        refresh()
    }

    private fun info(text: String, good: Boolean? = null) = ApplicationManager.getApplication().invokeLater {
        status.text = text
        status.foreground = when (good) {
            true -> JBColor(0x2E8B57, 0x62B543)
            false -> JBColor(0xD0312D, 0xFF6B68)
            null -> UIUtil.getLabelForeground()
        }
    }

    private fun refresh() {
        val root = EjudgeSettings.getInstance().contestsPath()
        ApplicationManager.getApplication().executeOnPooledThread {
            try { CMakeGen.generate(root) } catch (_: Exception) {}
            val found = if (!Files.isDirectory(root)) emptyList() else Files.list(root).use { s ->
                s.filter { Files.isDirectory(it) && !it.fileName.toString().startsWith(".") }.filter { c ->
                    Files.list(c).use { p -> p.anyMatch { Files.exists(it.resolve(MARKER)) } }
                }.sorted(Comparator.reverseOrder()).toList()
            }
            ApplicationManager.getApplication().invokeLater {
                val prev = (contests.selectedItem as? ContestDir)?.dir
                contests.removeAllItems()
                found.forEach { contests.addItem(ContestDir(it)) }
                val wanted = prev ?: editorProblem()?.parent
                (0 until contests.itemCount).map { contests.getItemAt(it) }.firstOrNull { it.dir == wanted }?.let { contests.selectedItem = it }
                if (found.isEmpty()) problems.clear()
            }
        }
    }

    private fun loadProblems() {
        val contest = (contests.selectedItem as? ContestDir)?.dir ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            val list = Files.list(contest).use { s -> s.filter { Files.exists(it.resolve(MARKER)) }.sorted().toList() }.map { dir ->
                val letter = Files.readAllLines(dir.resolve(MARKER)).getOrNull(1) ?: dir.fileName.toString()
                ProblemDir(dir, letter, titleOf(dir))
            }
            ApplicationManager.getApplication().invokeLater {
                states.clear()
                allProblems = list
                applyFilter(null)
                val target = pendingSelect ?: editorProblem()
                val want = target?.let { w -> (0 until problems.size()).firstOrNull { problems[it].dir == w } } ?: -1
                pendingSelect = null
                loadStates(list)
                programmatic++
                try {
                    if (want >= 0) problemList.selectedIndex = want else if (problems.size() > 0) problemList.selectedIndex = 0
                } finally { programmatic-- }
            }
        }
    }

    /** Fetches the runs from the server and colours the problems: green if accepted, red with the verdict if not. */
    private fun loadStates(list: List<ProblemDir>) {
        val s = EjudgeSettings.getInstance()
        val first = list.firstOrNull() ?: return
        if (s.state.login.isBlank()) return
        val contestId = try { Files.readAllLines(first.dir.resolve(MARKER)).getOrNull(2)?.trim()?.toIntOrNull() } catch (_: Exception) { null } ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val client = EjudgeClient(s.state.serverUrl, contestId)
                client.login(s.state.login, s.password)
                val runs = client.runs()
                val fresh = problemStates(runs, client.tabClasses())
                ApplicationManager.getApplication().invokeLater {
                    if (allProblems.isNotEmpty() && allProblems[0].dir.parent == first.dir.parent) {
                        states.clear(); states.putAll(fresh); problemList.repaint(); updateSummary()
                    }
                }
            } catch (ex: Exception) {
                com.intellij.openapi.diagnostic.Logger.getInstance("Ejudge").warn("Could not load problem states: ${ex.message}", ex)
            }
        }
    }

    private fun isFavorite(p: ProblemDir) = p.dir.toString() in EjudgeSettings.getInstance().state.favorites

    private fun toggleFavorite(p: ProblemDir) {
        val fav = EjudgeSettings.getInstance().state.favorites
        if (!fav.remove(p.dir.toString())) fav.add(p.dir.toString())
        applyFilter()
    }

    /** Shows the problems that match the search text and the favorites switch, keeping the selection if possible. */
    private fun applyFilter(keep: Path? = selectedDir()) {
        val q = search.text.trim().lowercase()
        val shown = allProblems.filter {
            (q.isEmpty() || it.letter.lowercase().contains(q) || it.title.lowercase().contains(q)) && (!favOnly.isSelected || isFavorite(it))
        }
        filtering++
        try {
            problems.clear()
            shown.forEach { problems.addElement(it) }
            val idx = shown.indexOfFirst { it.dir == keep }
            if (idx >= 0) problemList.selectedIndex = idx
        } finally {
            filtering--
        }
        updateSummary()
    }

    private fun updateSummary() {
        val solved = allProblems.count { states[it.letter]?.solved == true }
        summary.text = when {
            allProblems.isEmpty() -> " "
            states.isEmpty() -> "${allProblems.size} problems"
            else -> "Solved $solved of ${allProblems.size}"
        }
    }

    private fun memText(kb: Long) = if (kb >= 1024) "${kb / 1024} MB" else "$kb KB"

    private fun titleOf(dir: Path): String = try {
        val doc = Jsoup.parse(Files.readString(dir.resolve("statement.html")))
        val h = doc.select("h2, h3").map { it.text().trim() }
        h.firstNotNullOfOrNull { Regex("^Сдать решение задачи\\s*[^-]{1,4}-(.*)$").find(it)?.groupValues?.get(1) }
            ?: h.firstNotNullOfOrNull { Regex("^Задача\\s*[^:]{1,4}:\\s*(.*)$").find(it)?.groupValues?.get(1) }
            ?: h.firstOrNull() ?: ""
    } catch (_: Exception) {
        ""
    }

    /** The solution file of a problem; created from the template when missing. */
    private fun solutionFile(dir: Path): Path {
        TestRunner.sourceOf(dir)?.let { return it }
        val f = dir.resolve("main.cpp")
        Files.writeString(f, ImportContestAction.TEMPLATE)
        return f
    }

    /** Keeps the panel in sync with the file open in the editor. */
    private fun selectByFile(file: Path) {
        val problem = SubmitAction.findMarker(file)?.parent ?: return
        if (problem == selectedDir()) return
        if (System.currentTimeMillis() < lockUntil) return
        val contest = problem.parent
        if ((contests.selectedItem as? ContestDir)?.dir == contest) {
            (0 until problems.size()).firstOrNull { problems[it].dir == problem }?.let { programmatic++; try { problemList.selectedIndex = it } finally { programmatic-- } }
        } else {
            val item = (0 until contests.itemCount).map { contests.getItemAt(it) }.firstOrNull { it.dir == contest } ?: return
            pendingSelect = problem
            contests.selectedItem = item
        }
    }

    /** The problem folder of the file currently open in the editor, if it belongs to an imported contest. */
    private fun editorProblem(): Path? =
        FileEditorManager.getInstance(project).selectedFiles.firstNotNullOfOrNull { SubmitAction.findMarker(Path.of(it.path))?.parent }

    /** The user picked another problem: hide the previous solution tabs and open this problem's file. */
    private fun switchTo(dir: Path) {
        lockUntil = System.currentTimeMillis() + 800
        WriteIntentReadAction.run {
            val fem = FileEditorManager.getInstance(project)
            for (f in fem.openFiles) {
                val owner = SubmitAction.findMarker(Path.of(f.path))?.parent
                if (owner != null && owner != dir) fem.closeFile(f)
            }
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(solutionFile(dir))?.let { fem.openFile(it, false) }
        }
    }

    private fun selectedDir(): Path? = problemList.selectedValue?.dir

    private fun selectedSource(): Path? = selectedDir()?.let { TestRunner.sourceOf(it) }

    private fun open(path: Path) {
        WriteIntentReadAction.run {
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)?.let { FileEditorManager.getInstance(project).openFile(it, true) }
        }
    }

    private fun showStatement(dir: Path) {
        StatementPanel.get(project).show(dir, activate = true)
    }

    private fun showCard(name: String) = (layout as CardLayout).show(this, name)

    private fun buildWelcome(): JComponent {
        val dir = EjudgeSettings.getInstance().contestsPath().toString()
        val text = JEditorPane("text/html", """
            <html><body style="font-family: sans-serif; font-size: 13pt;">
            <h2>Добро пожаловать в Ejudge</h2>
            <p>Здесь можно решать задачи Яндекс Кружка, не выходя из CLion: скачать условия, проверить решение и отправить его на сервер.</p>
            <ol>
              <li><b>Войдите.</b> Нажмите «Открыть настройки» и введите логин и пароль от ejudge.algocode.ru.</li>
              <li><b>Скачайте контесты.</b> Нажмите «Import contest» и выберите параллель. Все её контесты скачаются в папку <code>$dir</code>.</li>
              <li><b>Выберите задачу.</b> Выберите контест и задачу в списке. Файл с решением откроется в редакторе, а условие появится слева. Прошлая задача закроется сама.</li>
              <li><b>Проверьте решение.</b> «Test on examples» соберёт программу и сверит ответы с примерами из условия. «Run with my input» позволяет ввести свои данные (Ctrl+Enter запускает).</li>
              <li><b>Отправьте.</b> «Submit» отправит решение на сервер и покажет вердикт уведомлением.</li>
            </ol>
            <p><b>Автодополнение.</b> Файлы лежат вне вашего проекта. Чтобы заработали подсказки, один раз откройте папку <code>$dir</code> как проект (File → Open).</p>
            <p><b>Цвета задач.</b> Зелёная галочка — задача принята на сервере. Красный крестик и вердикт (WA, TL, RE…) — решение отправляли, но оно не прошло. Без значка — ещё не отправляли. Цвета обновляются кнопкой обновления и после каждой отправки.</p>
            <p><b>Другие кнопки.</b> «Test &amp; submit» отправляет решение, только если все тесты прошли. «Save as test» в окне своего ввода сохраняет тест (ответ можно вписать в нижнее поле), и он проверяется вместе с примерами (удалить: правая кнопка по тесту). «My submissions» показывает все ваши посылки и их код. Поиск над списком и правая кнопка по задаче (★ избранное) помогают найти нужную.</p>
            <p><b>Статистика.</b> Плагин анонимно считает, сколько людей им пользуется: не передаются ни логин, ни данные контестов. Отключить можно в настройках (Settings → Tools → Ejudge).</p>
            <p>Эту подсказку всегда можно вызвать кнопкой «?» справа вверху.</p>
            </body></html>
        """.trimIndent()).apply {
            isEditable = false
            border = JBUI.Borders.empty(12)
        }
        val settingsButton = JButton("Открыть настройки", AllIcons.General.Settings)
        val importButton = JButton("Скачать контесты", AllIcons.Actions.Download)
        val doneButton = JButton("Понятно, начать")
        settingsButton.addActionListener {
            com.intellij.openapi.options.ShowSettingsUtil.getInstance().showSettingsDialog(project, EjudgeConfigurable::class.java)
        }
        importButton.addActionListener { ImportContestAction.start(project) }
        doneButton.addActionListener {
            EjudgeSettings.getInstance().state.onboardingDone = true
            showCard("main")
        }
        return JPanel(BorderLayout()).apply {
            add(JBScrollPane(text), BorderLayout.CENTER)
            add(JPanel(GridLayout(1, 3, JBUI.scale(6), 0)).apply {
                border = JBUI.Borders.empty(8)
                add(settingsButton); add(importButton); add(doneButton)
            }, BorderLayout.SOUTH)
        }
    }

    private fun setBottomVisible(visible: Boolean) {
        cards.isVisible = visible
        collapseButton.icon = if (visible) AllIcons.General.ArrowDown else AllIcons.General.ArrowUp
        collapseButton.toolTipText = if (visible) "Hide the results area" else "Show the results area"
        body.revalidate()
        body.repaint()
    }

    private fun showRunCard(dir: Path) {
        saveRunInput()
        runDir = dir
        val saved = savedInputs[dir] ?: Files.list(dir.resolve("tests")).use { st ->
            st.filter { it.toString().endsWith(".in") }.sorted().findFirst().orElse(null)
        }?.let { Files.readString(it) } ?: ""
        inputArea.text = saved
        outputArea.text = ""
        (cards.layout as CardLayout).show(cards, "run")
        setBottomVisible(true)
        inputArea.requestFocusInWindow()
    }

    private fun saveRunInput() {
        runDir?.let { savedInputs[it] = inputArea.text }
    }

    private fun executeRun() {
        val dir = runDir ?: return
        savedInputs[dir] = inputArea.text
        val input = inputArea.text
        lastRunInput = input
        lastRunOk = false
        SubmitAction.saveDocuments()
        outputArea.text = ""
        runNowButton.isEnabled = false
        info("Compiling and running…")
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = try {
                val r = TestRunner.runCustom(dir, input)
                val failed = r.verdict == "RE" || r.verdict == "TL"
                (r.actual + (if (failed) "\n[${r.verdict}] ${r.note}" else "")) to !failed
            } catch (ex: Exception) {
                (ex.message ?: ex.toString()) to false
            }
            ApplicationManager.getApplication().invokeLater {
                runNowButton.isEnabled = true
                lastRunOk = result.second
                outputArea.text = result.first
                outputArea.caretPosition = 0
                info(if (result.second) "Finished" else "Failed", result.second)
            }
        }
    }

    private fun runTests(dir: Path, thenSubmit: Boolean) {
        SubmitAction.saveDocuments()
        results.clear()
        details.text = ""
        (cards.layout as CardLayout).show(cards, "tests")
        setBottomVisible(true)
        body.proportion = 0.42f
        testButton.isEnabled = false
        info("Compiling and running…")
        ApplicationManager.getApplication().executeOnPooledThread {
            var error: String? = null
            var okCount = 0
            var total = 0
            try {
                error = TestRunner.run(dir) { r ->
                    total++
                    if (r.ok) okCount++
                    ApplicationManager.getApplication().invokeLater { results.addElement(r) }
                }
            } catch (ex: Exception) {
                error = ex.message ?: ex.toString()
            }
            ApplicationManager.getApplication().invokeLater {
                testButton.isEnabled = true
                if (error != null) {
                    details.text = error
                    info("Could not run the tests", false)
                } else {
                    val allOk = okCount == total
                    (0 until results.size()).firstOrNull { !results[it].ok }?.let { resultList.selectedIndex = it }
                    if (!thenSubmit) {
                        info(if (allOk) "All $total tests passed" else "$okCount of $total tests passed", allOk)
                    } else if (allOk) {
                        info("All $total tests passed, submitting…", true)
                        TestRunner.sourceOf(dir)?.let { SubmitAction.submitFile(project, it) }
                    } else {
                        info("Not submitted: $okCount of $total tests passed. Fix the solution first", false)
                    }
                }
            }
        }
    }

    private fun deleteTest(r: TestResult) {
        r.file?.let { f ->
            Files.deleteIfExists(f)
            Files.deleteIfExists(f.resolveSibling(f.fileName.toString().removeSuffix(".in") + ".out"))
        }
        results.removeElement(r)
        details.text = ""
        info("Test ${r.name} deleted", true)
    }

    private fun saveRunAsTest() {
        val dir = runDir ?: return
        val input = inputArea.text
        if (input.isBlank()) { info("Type an input first", false); return }
        val out = expectedArea.text.takeIf { it.isNotBlank() } ?: if (lastRunOk && input == lastRunInput) outputArea.text else null
        val name = TestRunner.saveCustomTest(dir, input, out)
        info(
            if (out.isNullOrBlank()) "Saved as test $name (no expected answer: type it below or run the program first)"
            else "Saved as test $name. It runs together with the examples", true
        )
    }

    private fun showHistory(dir: Path) {
        historyDir = dir
        historyModel.clear()
        historyOpen.isEnabled = false
        historyCode.text = "Loading the submissions…"
        (cards.layout as CardLayout).show(cards, "history")
        setBottomVisible(true)
        body.proportion = 0.42f
        val lines = Files.readAllLines(dir.resolve(MARKER))
        val letter = lines.getOrNull(1) ?: return
        val contestId = lines.getOrNull(2)?.trim()?.toIntOrNull() ?: return
        val s = EjudgeSettings.getInstance()
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val client = EjudgeClient(s.state.serverUrl, contestId)
                client.login(s.state.login, s.password)
                val runs = client.runs().filter { it.letter == letter }.sortedByDescending { it.id }
                ApplicationManager.getApplication().invokeLater {
                    if (historyDir != dir) return@invokeLater
                    historyClient = client
                    runs.forEach { historyModel.addElement(it) }
                    historyCode.text = if (runs.isEmpty()) "You have not submitted this problem yet" else "Select a submission to see its code"
                    if (runs.isNotEmpty()) historyList.selectedIndex = 0
                }
            } catch (ex: Exception) {
                ApplicationManager.getApplication().invokeLater { historyCode.text = "Could not load the submissions: ${ex.message}" }
            }
        }
    }

    private fun loadHistorySource() {
        val run = historyList.selectedValue ?: return
        val client = historyClient ?: return
        historyOpen.isEnabled = false
        historyCode.text = "Loading the code of #${run.id}…"
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = try {
                client.runSource(run)
            } catch (ex: Exception) {
                try {
                    val dump = EjudgeSettings.getInstance().contestsPath().resolve("debug-source.html")
                    Files.createDirectories(dump.parent)
                    Files.writeString(dump, client.lastSourceHtml)
                } catch (_: Exception) {}
                null to (ex.message ?: ex.toString())
            }
            ApplicationManager.getApplication().invokeLater {
                if (historyList.selectedValue?.id != run.id) return@invokeLater
                if (result is String) {
                    historyText = result
                    historyCode.text = result
                    historyOpen.isEnabled = true
                } else {
                    historyText = ""
                    historyCode.text = "Could not load the code: ${(result as Pair<*, *>).second}"
                }
                historyCode.caretPosition = 0
            }
        }
    }

    private fun openHistoryFile() {
        val dir = historyDir ?: return
        val run = historyList.selectedValue ?: return
        if (historyText.isEmpty()) return
        val lang = run.column("язык|lang").lowercase()
        val ext = when {
            lang.contains("python") || lang.contains("pypy") -> "py"
            lang.contains("g++") || lang.contains("c++") -> "cpp"
            lang.contains("java") && !lang.contains("script") -> "java"
            lang.contains("kotlin") -> "kt"
            lang.contains("rust") -> "rs"
            lang.contains("gcc") || lang.trim() == "c" -> "c"
            else -> TestRunner.sourceOf(dir)?.fileName?.toString()?.substringAfterLast('.') ?: "cpp"
        }
        val f = dir.resolve("history").resolve("run${run.id}.$ext")
        Files.createDirectories(f.parent)
        Files.writeString(f, historyText)
        open(f)
    }

}
