package com.ejudge.clion

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.CookieManager
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID

/** The statement part of an ejudge problem page (without menu, submit forms and previous runs). */
fun statementBody(page: Document): Element {
    val area = page.selectFirst("#probNavTaskArea-ins") ?: return page.body()
    val copy = area.clone()
    copy.select("#ej-submit-tabs").remove()
    copy.select("script, style, link, meta").remove()
    return copy
}

class EjudgeException(message: String) : Exception(message)

data class Problem(val name: String, val url: String, val id: String)
data class Sample(val input: String, val output: String)
data class Run(val id: Int, val columns: Map<String, String>, val links: List<Pair<String, String>> = emptyList()) {
    /** Letter of the problem this run belongs to. */
    val letter: String get() = columns.entries.firstOrNull { it.key.contains(Regex("задача|problem", RegexOption.IGNORE_CASE)) }
        ?.value?.trim()?.split(' ', ':', '.')?.firstOrNull().orEmpty()
    val accepted: Boolean get() = !inProgress && Regex("^(OK|Accepted|Полное|Принято|Зачтено)", RegexOption.IGNORE_CASE).containsMatchIn(result)
    fun column(pattern: String): String = columns.entries.firstOrNull { it.key.contains(Regex(pattern, RegexOption.IGNORE_CASE)) }?.value.orEmpty()

    val result: String get() = columns.entries.firstOrNull { it.key.contains(Regex("результат|result|status", RegexOption.IGNORE_CASE)) }?.value ?: ""
    val inProgress: Boolean get() = result.isBlank() || result.contains(
        Regex("компилир|выполня|ожида|тестир|проверя|compil|running|judging|pending|waiting|queue|testing", RegexOption.IGNORE_CASE)
    )

    fun describe(): String = columns.entries
        .filter { it.key.contains(Regex("результат|result|status|тест|test|баллы|score", RegexOption.IGNORE_CASE)) && it.value.isNotBlank() }
        .joinToString(", ") { "${it.key}: ${it.value}" }
        .ifBlank { columns.values.joinToString(" | ") }
}

/** Whether a problem is accepted on the server, and the short verdict of the last failed run. */
data class ProblemState(val solved: Boolean, val verdict: String)

private val ACCEPTED = Regex("^(OK|Accepted|Полное|Принято|Зачтено)", RegexOption.IGNORE_CASE)

fun shortVerdict(r: String): String = when {
    r.contains(Regex("неправильн|wrong", RegexOption.IGNORE_CASE)) -> "WA"
    r.contains(Regex("runtime|выполнен", RegexOption.IGNORE_CASE)) -> "RE"
    r.contains(Regex("врем|time", RegexOption.IGNORE_CASE)) -> "TL"
    r.contains(Regex("памят|memory", RegexOption.IGNORE_CASE)) -> "ML"
    r.contains(Regex("компил|compil", RegexOption.IGNORE_CASE)) -> "CE"
    r.contains(Regex("презент|presentation", RegexOption.IGNORE_CASE)) -> "PE"
    else -> r.take(12)
}

/** Per problem letter: solved if any finished run was accepted, otherwise the verdict of the last finished run. */
fun problemStates(runs: List<Run>, tabs: Map<String, String> = emptyMap()): Map<String, ProblemState> {
    val byLetter = runs.filter { !it.inProgress }.groupBy { it.letter }
    val fromRuns = byLetter.filterKeys { it.isNotEmpty() }.mapValues { (_, list) ->
        if (list.any { ACCEPTED.containsMatchIn(it.result) }) ProblemState(true, "OK")
        else ProblemState(false, shortVerdict(list.last().result))
    }
    val result = fromRuns.toMutableMap()
    for ((letter, cls) in tabs) {
        when {
            cls.contains("Ok") -> result[letter] = ProblemState(true, "OK")
            cls.contains("Empty") -> {}
            else -> if (result[letter]?.solved != true) result[letter] = result[letter] ?: ProblemState(false, "FAIL")
        }
    }
    return result
}

/**
 * Talks to the ejudge new-client web interface. Forms are discovered from the served HTML
 * (hidden fields and action URLs are copied as-is), so no ejudge action numbers are hard-coded.
 */
class EjudgeClient(private val serverUrl: String, private val contestId: Int) {
    private val http = HttpClient.newBuilder()
        .cookieHandler(CookieManager())
        .followRedirects(HttpClient.Redirect.ALWAYS)
        .connectTimeout(Duration.ofSeconds(15))
        .build()

    private var mainUri: URI = URI.create(serverUrl)
    lateinit var mainPage: Document
        private set

    fun login(login: String, password: String) {
        val loginUri = URI.create("$serverUrl?contest_id=$contestId")
        val page = get(loginUri)
        val form = page.select("form").firstOrNull { it.selectFirst("input[type=password]") != null }
            ?: throw EjudgeException("Login form not found at $loginUri")
        val fields = formFields(form).toMutableMap()
        fields[form.selectFirst("input[type=password]")!!.attr("name")] = password
        val loginInput = form.select("input[type=text]").firstOrNull { it.attr("name").contains("login") }
            ?: form.selectFirst("input[type=text]")
            ?: throw EjudgeException("Login field not found")
        fields[loginInput.attr("name")] = login
        val resp = send(
            HttpRequest.newBuilder(URI.create(form.absUrl("action").ifEmpty { loginUri.toString() }))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(urlEncode(fields)))
        )
        mainUri = resp.uri()
        mainPage = Jsoup.parse(resp.body(), mainUri.toString())
        if (mainPage.selectFirst("input[type=password]") != null) {
            throw EjudgeException("Login failed: check login, password and contest id")
        }
    }

    /** Raw HTML of the last runs page, kept for debugging when the table cannot be parsed. */
    var lastRunsHtml: String = ""
        private set

    /** Parses the runs table (page linked as "Посылки"/"Runs") and returns runs sorted by id. */
    fun runs(): List<Run> = runsOrNull() ?: emptyList()

    /** The server colours the problem tabs itself: letter -> css class (nProbOk, nProbBad, nProbEmpty ...). */
    fun tabClasses(): Map<String, String> {
        val doc = Jsoup.parse(lastRunsHtml)
        return doc.select("div[class^=nProb]").mapNotNull { div ->
            val a = div.selectFirst("a[href*=prob_id=]") ?: return@mapNotNull null
            a.text().trim() to div.className()
        }.toMap()
    }

    /** Null when no runs table could be found on the page. */
    fun runsOrNull(): List<Run>? {
        val link = mainPage.select("a").firstOrNull { it.text().contains(Regex("посылки|runs|submissions", RegexOption.IGNORE_CASE)) }
        var doc = if (link != null) get(URI.create(link.absUrl("href"))) else mainPage
        // the page may show only the latest runs; follow a "show all" link if there is one
        val all = doc.select("a").firstOrNull { it.attr("href").contains("all_runs=1") }
        if (all != null) doc = get(URI.create(all.absUrl("href")))
        lastRunsHtml = doc.outerHtml()
        for (table in doc.select("table")) {
            val rows = table.select("tr")
            val headerRow = rows.firstOrNull { it.select("th").size >= 4 } ?: continue
            val header = headerRow.select("th").map { it.text().trim() }
            val parsed = rows.filter { it !== headerRow && it.select("td").size == header.size }.mapNotNull { row ->
                val cells = row.select("td").map { it.text().trim() }
                val id = cells.firstOrNull()?.toIntOrNull() ?: return@mapNotNull null
                val links = row.select("a[href]").map { it.text().trim() to it.absUrl("href") }.filter { it.second.isNotBlank() }
                Run(id, header.zip(cells).toMap(), links)
            }
            return parsed.sortedBy { it.id }
        }
        return null
    }

    /** Waits for the first run newer than [afterId] to finish judging. */
    fun awaitRun(afterId: Int, timeoutSec: Int = 120, onUpdate: (Run) -> Unit = {}): Run? {
        val deadline = System.currentTimeMillis() + timeoutSec * 1000L
        var last: Run? = null
        while (System.currentTimeMillis() < deadline) {
            val all = runsOrNull() ?: return last
            last = all.lastOrNull { it.id > afterId }
            if (last != null) {
                onUpdate(last)
                if (!last.inProgress) return last
            }
            Thread.sleep(3000)
        }
        return last
    }

    /** Source code of a submitted run, from the "view source" page linked in the runs table. */
    fun runSource(run: Run): String {
        val link = run.links.firstOrNull { it.second.contains("action=36") }
            ?: run.links.firstOrNull { it.first.contains(Regex("просмотр|view|исходн|source", RegexOption.IGNORE_CASE)) }
            ?: run.links.firstOrNull { it.second.contains("run_id=") }
            ?: throw EjudgeException("No link to the source of run #${run.id}")
        val doc = get(URI.create(link.second))
        val pre = doc.select("pre").maxByOrNull { it.wholeText().length }
        if (pre == null) {
            lastSourceHtml = doc.outerHtml()
            throw EjudgeException("The source of run #${run.id} was not found on the page ${link.second}")
        }
        return pre.wholeText()
    }

    /** Raw HTML of the last "view source" page that could not be parsed. */
    var lastSourceHtml: String = ""
        private set

    fun problems(): List<Problem> {
        val seen = LinkedHashMap<String, Problem>()
        for (a in mainPage.select("a[href*=prob_id=]")) {
            val href = a.absUrl("href")
            val id = Regex("prob_id=(\\d+)").find(href)?.groupValues?.get(1) ?: continue
            val text = a.text().trim()
            if (id !in seen) seen[id] = Problem(text.ifEmpty { id }, href, id)
        }
        return seen.values.toList()
    }

    fun statement(problem: Problem): Document = get(URI.create(problem.url))

    fun samples(statement: Document): List<Sample> {
        val pres = statementBody(statement).select("pre").map { it.wholeText().trimEnd() + "\n" }
        return pres.chunked(2).filter { it.size == 2 }.map { Sample(it[0], it[1]) }
            .filter { it.input.isNotBlank() && it.output.isNotBlank() }
    }

    /** Finds the submit form (on the main page or a page linked as "submit") and posts [file] to it. */
    fun submit(problem: Problem, file: Path, languageHint: String): String {
        val form = findSubmitForm(problem)
        val fields = formFields(form).toMutableMap()
        fields[selectName(form, "prob_id")] = pickOption(form, "prob_id") { opt ->
            opt.attr("value") == problem.id || opt.text().trim().startsWith(problem.name)
        } ?: throw EjudgeException("Problem ${problem.name} not in the submit form")
        fields[selectName(form, "lang_id")] = pickOption(form, "lang_id") { opt ->
            opt.text().contains(languageHint, ignoreCase = true)
        } ?: throw EjudgeException(
            "No language matching '$languageHint'. Available: " +
                form.select("select[name=lang_id] option").joinToString { it.text() }
        )
        val fileInput = form.selectFirst("input[type=file]")!!.attr("name")
        val boundary = "----ejudge" + UUID.randomUUID()
        val body = ByteArray(0).let { _ ->
            val out = java.io.ByteArrayOutputStream()
            fun w(s: String) = out.write(s.toByteArray())
            for ((k, v) in fields) {
                w("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n$v\r\n")
            }
            w("--$boundary\r\nContent-Disposition: form-data; name=\"$fileInput\"; filename=\"${file.fileName}\"\r\n")
            w("Content-Type: application/octet-stream\r\n\r\n")
            out.write(Files.readAllBytes(file))
            w("\r\n--$boundary--\r\n")
            out.toByteArray()
        }
        val resp = send(
            HttpRequest.newBuilder(URI.create(form.absUrl("action")))
                .header("Content-Type", "multipart/form-data; boundary=$boundary")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
        )
        val doc = Jsoup.parse(resp.body())
        return doc.selectFirst(".message, .error, h2, h1")?.text()?.ifBlank { null } ?: "HTTP ${resp.statusCode()}"
    }

    /** Looks for the submit form: main page, then the problem's own page, then other menu pages. */
    private fun findSubmitForm(problem: Problem): Element {
        fileForm(mainPage)?.let { return it }
        val checked = mutableListOf<String>()
        fun load(url: String): Document? {
            val doc = try { get(URI.create(url)) } catch (_: Exception) { return null }
            if (doc.title().contains("Вход в систему") || doc.text().contains("Invalid session")) {
                throw EjudgeException("Session was lost while looking for the submit form (page: $url)")
            }
            checked += doc.title().ifBlank { url }
            return doc
        }
        val idRe = Regex("prob_id=${Regex.escape(problem.id)}(?!\\d)")
        for (a in mainPage.select("a[href*=prob_id=]").filter { idRe.containsMatchIn(it.attr("href")) }) {
            load(a.absUrl("href"))?.let { doc -> fileForm(doc)?.let { return it } }
        }
        val skip = Regex("выход|выйти|logout|log out|exit", RegexOption.IGNORE_CASE)
        val links = mainPage.select("a[href]")
            .filter { !it.text().contains(skip) && !it.attr("href").contains("logout", true) && it.absUrl("href").startsWith("http") }
            .map { it.absUrl("href").substringBefore('#') }.distinct().take(20)
        for (url in links) {
            load(url)?.let { doc -> fileForm(doc)?.let { return it } }
        }
        throw EjudgeException("Submit form not found. Pages checked: ${checked.joinToString("; ")}")
    }

    private fun fileForm(doc: Document): Element? = doc.select("form").firstOrNull { it.selectFirst("input[type=file]") != null }

    private fun selectName(form: Element, name: String) = form.selectFirst("[name=$name]")?.attr("name")
        ?: throw EjudgeException("Field '$name' not in the submit form")

    private fun pickOption(form: Element, name: String, match: (Element) -> Boolean): String? {
        val el = form.selectFirst("[name=$name]") ?: return null
        if (el.tagName() != "select") return el.attr("value")
        return el.select("option").firstOrNull(match)?.attr("value")
    }

    private fun formFields(form: Element): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        for (i in form.select("input[name]")) {
            when (i.attr("type").lowercase()) {
                "hidden" -> m[i.attr("name")] = i.attr("value")
                "submit" -> if (i.attr("name").startsWith("action") && m.keys.none { it.startsWith("action") && it != "action" }) m[i.attr("name")] = i.attr("value")
            }
        }
        return m
    }

    /** Raw bytes of a file (image) served with the current session. */
    fun download(url: String): ByteArray {
        val resp = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).GET().build(), HttpResponse.BodyHandlers.ofByteArray())
        if (resp.statusCode() >= 400) throw EjudgeException("Server returned HTTP ${resp.statusCode()} for $url")
        return resp.body()
    }

    private fun get(uri: URI): Document {
        val resp = send(HttpRequest.newBuilder(uri).GET())
        return Jsoup.parse(resp.body(), resp.uri().toString())
    }

    private fun send(b: HttpRequest.Builder): HttpResponse<String> {
        val resp = http.send(b.timeout(Duration.ofSeconds(60)).build(), HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() >= 400) throw EjudgeException("Server returned HTTP ${resp.statusCode()} for ${resp.uri()}")
        return resp
    }

    private fun urlEncode(m: Map<String, String>) =
        m.entries.joinToString("&") { "${URLEncoder.encode(it.key, Charsets.UTF_8)}=${URLEncoder.encode(it.value, Charsets.UTF_8)}" }
}
