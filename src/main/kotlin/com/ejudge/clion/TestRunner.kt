package com.ejudge.clion

import org.jsoup.Jsoup
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

data class TestResult(
    val name: String, val verdict: String, val ok: Boolean, val ms: Long,
    val input: String, val expected: String, val actual: String, val note: String = "",
    val kb: Long = -1, val warn: String = "", val file: Path? = null,
) {
    val custom: Boolean get() = name.startsWith("u")
    override fun toString() = "${if (ok) "✓" else "✗"} $name  $verdict  ${ms} ms"
}

/** Time and memory limits of a problem, read from its statement; null when unknown. */
data class Limits(val timeMs: Long?, val memKb: Long?) {
    companion object {
        val NONE = Limits(null, null)

        fun of(dir: Path): Limits = try {
            val text = Jsoup.parse(Files.readString(dir.resolve("statement.html"))).text()
            val t = Regex("Ограничение времени:\\s*([\\d.,]+)\\s*(мс|с|сек|ms|s)", RegexOption.IGNORE_CASE).find(text)?.let {
                val v = it.groupValues[1].replace(',', '.').toDouble()
                (if (it.groupValues[2].lowercase().let { u -> u == "мс" || u == "ms" }) v else v * 1000).toLong()
            }
            val m = Regex("Ограничение памяти:\\s*([\\d.,]+)\\s*([KMGКМГ])", RegexOption.IGNORE_CASE).find(text)?.let {
                val v = it.groupValues[1].replace(',', '.').toDouble()
                when (it.groupValues[2].uppercase()) {
                    "K", "К" -> v
                    "G", "Г" -> v * 1024 * 1024
                    else -> v * 1024
                }.toLong()
            }
            Limits(t, m)
        } catch (_: Exception) {
            NONE
        }
    }
}

/** Compiles the solution of a problem folder and runs it on every tests/N.in (examples) and tests/uN.in (own tests). */
object TestRunner {
    private const val DEFAULT_TIMEOUT_MS = 5000L

    /** The measuring tool of the OS: it reports the peak memory of the child process. */
    private val measure: List<String>? = run {
        val os = System.getProperty("os.name").lowercase()
        val bin = java.io.File("/usr/bin/time")
        when {
            !bin.canExecute() -> null
            os.contains("mac") -> listOf(bin.path, "-l")
            os.contains("linux") -> listOf(bin.path, "-v")
            else -> null
        }
    }

    fun sourceOf(dir: Path): Path? = Files.list(dir).use { s ->
        val files = s.filter { Files.isRegularFile(it) }.toList()
        files.firstOrNull { it.fileName.toString().startsWith("main.") }
            ?: files.firstOrNull { it.fileName.toString().substringAfterLast('.') in setOf("cpp", "cc", "c", "py") }
    }

    /** Test files sorted: examples by number first, then own tests. */
    fun testInputs(dir: Path): List<Path> {
        val td = dir.resolve("tests")
        if (!Files.isDirectory(td)) return emptyList()
        return Files.list(td).use { s ->
            s.filter { it.fileName.toString().endsWith(".in") && Files.readString(it).isNotBlank() }.toList()
        }.sortedWith(compareBy({ it.fileName.toString().startsWith("u") }, { it.fileName.toString().removeSuffix(".in").trimStart('u').toIntOrNull() ?: Int.MAX_VALUE }))
    }

    /** Saves [input] (and the expected [output], if known) as the next own test; returns its name. */
    fun saveCustomTest(dir: Path, input: String, output: String?): String {
        val td = dir.resolve("tests")
        Files.createDirectories(td)
        var n = 1
        while (Files.exists(td.resolve("u$n.in"))) n++
        Files.writeString(td.resolve("u$n.in"), if (input.endsWith("\n")) input else input + "\n")
        if (!output.isNullOrBlank()) Files.writeString(td.resolve("u$n.out"), if (output.endsWith("\n")) output else output + "\n")
        return "u$n"
    }

    fun run(dir: Path, onResult: (TestResult) -> Unit): String? {
        val src = sourceOf(dir) ?: return "No solution file (main.cpp / main.py) in $dir"
        val tmp = Files.createTempDirectory("ejudge-run")
        try {
            writeStdcppShim(tmp)
            val cmd = try {
                command(src, tmp, "sol")
            } catch (ex: EjudgeException) {
                return ex.message
            }
            val inputs = testInputs(dir)
            if (inputs.isEmpty()) return "No tests in ${dir.resolve("tests")}"
            val limits = Limits.of(dir)
            for (inp in inputs) onResult(runOne(cmd, inp, tmp, limits))
            return null
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    /** Compiles (if needed) and runs the solution once on arbitrary input. */
    fun runCustom(dir: Path, input: String): TestResult {
        val src = sourceOf(dir) ?: throw EjudgeException("No solution file (main.cpp / main.py) in $dir")
        val tmp = Files.createTempDirectory("ejudge-run")
        try {
            writeStdcppShim(tmp)
            val cmd = command(src, tmp, "sol")
            val inFile = tmp.resolve("custom.in")
            Files.writeString(inFile, input)
            return runOne(cmd, inFile, tmp, Limits.of(dir))
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    private fun kill(p: Process) {
        p.descendants().forEach { it.destroyForcibly() }
        p.destroyForcibly()
    }

    private fun command(src: Path, tmp: Path, name: String): List<String> {
        val exe = tmp.resolve(name).toString()
        return when (src.fileName.toString().substringAfterLast('.')) {
            "cpp", "cc", "cxx" -> {
                compile(listOf("c++", "-O2", "-std=c++17", "-I", tmp.toString(), "-o", exe, src.toString()), tmp, name)?.let { throw EjudgeException(it) }
                listOf(exe)
            }
            "c" -> {
                compile(listOf("cc", "-O2", "-o", exe, src.toString(), "-lm"), tmp, name)?.let { throw EjudgeException(it) }
                listOf(exe)
            }
            "py" -> listOf("python3", src.toString())
            else -> throw EjudgeException("Unsupported language: ${src.fileName}")
        }
    }

    private fun writeStdcppShim(tmp: Path) {
        Files.createDirectories(tmp.resolve("bits"))
        Files.writeString(tmp.resolve("bits/stdc++.h"), CMakeGen.STDCPP_SHIM)
    }

    /** Returns compiler output on failure, null on success. */
    private fun compile(cmd: List<String>, tmp: Path, name: String): String? {
        val log = tmp.resolve("compile-$name.log")
        val p = ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(log.toFile()).start()
        if (!p.waitFor(120, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            return "Compilation timed out"
        }
        val what = if (name == "sol") "" else " of $name"
        return if (p.exitValue() == 0) null else "Compilation error$what:\n" + Files.readString(log)
    }

    private fun runOne(cmd: List<String>, inp: Path, tmp: Path, limits: Limits): TestResult {
        val name = inp.fileName.toString().removeSuffix(".in")
        val outFile = inp.resolveSibling("$name.out")
        val expected = if (Files.exists(outFile)) Files.readString(outFile) else ""
        val input = Files.readString(inp)
        val actualFile = tmp.resolve("$name.actual")
        val errFile = tmp.resolve("$name.err")
        val timeout = (limits.timeMs ?: DEFAULT_TIMEOUT_MS) * 2 + 1000
        val start = System.currentTimeMillis()
        val p = ProcessBuilder((measure ?: emptyList()) + cmd).redirectInput(inp.toFile())
            .redirectOutput(actualFile.toFile()).redirectError(errFile.toFile()).start()
        val finished = p.waitFor(timeout, TimeUnit.MILLISECONDS)
        val ms = System.currentTimeMillis() - start
        if (!finished) kill(p)
        val actual = Files.readString(actualFile)
        val (err, kb) = splitMeasure(Files.readString(errFile))
        val warns = mutableListOf<String>()
        if (limits.timeMs != null && ms * 10 > limits.timeMs * 7) warns += "time ${ms} of ${limits.timeMs} ms"
        if (limits.memKb != null && kb > 0 && kb * 10 > limits.memKb * 7) warns += "memory ${kb / 1024} of ${limits.memKb / 1024} MB"
        fun r(verdict: String, ok: Boolean, note: String = "") =
            TestResult(name, verdict, ok, ms, input, expected, actual, note, kb, if (ok && warns.isNotEmpty()) "close to the limit: " + warns.joinToString(", ") else "", inp)
        return when {
            !finished -> r("TL", false, "Time limit exceeded: killed after ${timeout / 1000}s")
            p.exitValue() != 0 -> r("RE", false, "Exit code ${p.exitValue()}\n$err")
            limits.memKb != null && kb > limits.memKb -> r("ML", false, "Memory ${kb / 1024} MB, limit ${limits.memKb / 1024} MB")
            limits.timeMs != null && ms > limits.timeMs -> r("TL", false, "Took $ms ms here, the limit is ${limits.timeMs} ms (your computer may be slower or faster than the server)")
            !Files.exists(outFile) -> r("?", true, "No expected output file; output shown only")
            tokens(actual) == tokens(expected) -> r("OK", true)
            else -> r("WA", false)
        }
    }

    /** Cuts the report of /usr/bin/time off the stderr and returns (program stderr, peak memory in KB or -1). */
    private fun splitMeasure(raw: String): Pair<String, Long> {
        if (measure == null) return raw to -1
        val mac = Regex("(?m)^\\s*[\\d.]+\\s+real\\s").find(raw)
        val linux = Regex("(?m)^\\s*Command being timed").find(raw)
        val cut = mac?.range?.first ?: linux?.range?.first ?: return raw to -1
        val report = raw.substring(cut)
        val kb = Regex("(\\d+)\\s+maximum resident set size").find(report)?.groupValues?.get(1)?.toLongOrNull()?.div(1024)
            ?: Regex("Maximum resident set size \\(kbytes\\):\\s*(\\d+)").find(report)?.groupValues?.get(1)?.toLongOrNull()
            ?: -1
        return raw.substring(0, cut) to kb
    }

    private fun tokens(s: String) = s.trim().split(Regex("\\s+"))
}
