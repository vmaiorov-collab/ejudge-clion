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

    init {
        val importButton = JButton("Import contest", AllIcons.Actions.Download)
        val refreshButton = JButton(AllIcons.Actions.Refresh).apply { toolTipText = "Refresh" }
        collapseButton.addActionListener { setBottomVisible(!cards.isVisible) }
        val top = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            border = JBUI.Borders.empty(8)
            add(contests, BorderLayout.CENTER)
            add(topButtons.apply { add(importButton); add(refreshButton); add(collapseButton) }, BorderLayout.EAST)
        }

        problemList.emptyText.text = "No problems yet"
        problemList.emptyText.appendLine("Press “Import contest” to download one")
        problemList.cellRenderer = object : ColoredListCellRenderer<ProblemDir>() {
            override fun customizeCellRenderer(list: JList<out ProblemDir>, value: ProblemDir, index: Int, selected: Boolean, hasFocus: Boolean) {
                border = JBUI.Borders.empty(3, 8)
                append(value.letter, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                if (value.title.isNotBlank()) append("   ${value.title}", SimpleTextAttributes.REGULAR_ATTRIBUTES)
            }
        }
        resultList.emptyText.text = "Press “Test” to run the examples"
        resultList.cellRenderer = object : ColoredListCellRenderer<TestResult>() {
            override fun customizeCellRenderer(list: JList<out TestResult>, value: TestResult, index: Int, selected: Boolean, hasFocus: Boolean) {
                border = JBUI.Borders.empty(3, 8)
                append("Test ${value.name}", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                val color = if (value.ok) JBColor(0x2E8B57, 0x62B543) else JBColor(0xD0312D, 0xFF6B68)
                append("   ${value.verdict}", SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, color))
                append("   ${value.ms} ms", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }

        val statementButton = JButton("Show statement", AllIcons.Actions.Preview)
        val submitButton = JButton("Submit", AllIcons.Actions.Upload)
        val runButton = JButton("Run with my input", AllIcons.Actions.Lightning)
        val actions = JPanel(GridLayout(2, 2, JBUI.scale(6), JBUI.scale(6))).apply {
            border = JBUI.Borders.empty(8)
            add(testButton); add(runButton); add(statementButton); add(submitButton)
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
            add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(4))).apply { add(runNowButton); add(JBLabel("Input → Output")) }, BorderLayout.NORTH)
            add(JBSplitter(true, 0.5f).apply {
                firstComponent = JBScrollPane(inputArea)
                secondComponent = JBScrollPane(outputArea)
            }, BorderLayout.CENTER)
        }, "run")
        cards.add(JBSplitter(true, 0.5f).apply {
            firstComponent = JBScrollPane(resultList)
            secondComponent = JBScrollPane(details)
        }, "tests")

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
        testButton.addActionListener { selectedDir()?.let { runTests(it) } ?: info("Select a problem first") }
        submitButton.addActionListener {
            selectedSource()?.let { SubmitAction.submitFile(project, it) } ?: info("Select a problem first")
        }
        problemList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) selectedSource()?.let { open(it) }
            }
        })
        problemList.addListSelectionListener {
            if (it.valueIsAdjusting) return@addListSelectionListener
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
            val r = resultList.selectedValue ?: return@addListSelectionListener
            details.text = "Input\n${r.input}\nExpected\n${r.expected}\nYour output\n${r.actual}" +
                (if (r.note.isNotBlank()) "\n${r.note}" else "")
            details.caretPosition = 0
        }
        EjudgeEvents.addListener { refresh() }
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
                problems.clear()
                list.forEach { problems.addElement(it) }
                val target = pendingSelect ?: editorProblem()
                val want = target?.let { w -> list.indexOfFirst { it.dir == w } } ?: -1
                pendingSelect = null
                programmatic++
                try {
                    if (want >= 0) problemList.selectedIndex = want else if (list.isNotEmpty()) problemList.selectedIndex = 0
                } finally { programmatic-- }
            }
        }
    }

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
                outputArea.text = result.first
                outputArea.caretPosition = 0
                info(if (result.second) "Finished" else "Failed", result.second)
            }
        }
    }

    private fun runTests(dir: Path) {
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
                    info(if (okCount == total) "All $total tests passed" else "$okCount of $total tests passed", okCount == total)
                    (0 until results.size()).firstOrNull { !results[it].ok }?.let { resultList.selectedIndex = it }
                }
            }
        }
    }
}
