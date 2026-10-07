package com.ejudge.clion

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

data class TestResult(
    val name: String, val verdict: String, val ok: Boolean, val ms: Long,
    val input: String, val expected: String, val actual: String, val note: String = "",
) {
    override fun toString() = "${if (ok) "✓" else "✗"} $name  $verdict  ${ms} ms"
}

/** Compiles the solution of a problem folder and runs it on every tests/N.in, comparing with tests/N.out. */
object TestRunner {
    private const val TIME_LIMIT_SEC = 5L

    fun sourceOf(dir: Path): Path? = Files.list(dir).use { s ->
        val files = s.filter { Files.isRegularFile(it) }.toList()
        files.firstOrNull { it.fileName.toString().startsWith("main.") }
            ?: files.firstOrNull { it.fileName.toString().substringAfterLast('.') in setOf("cpp", "cc", "c", "py") }
    }

    fun run(dir: Path, onResult: (TestResult) -> Unit): String? {
        val src = sourceOf(dir) ?: return "No solution file (main.cpp / main.py) in $dir"
        val tmp = Files.createTempDirectory("ejudge-run")
        try {
            writeStdcppShim(tmp)
            val cmd = try {
                command(src, tmp)
            } catch (ex: EjudgeException) {
                return ex.message
            }
            val inputs = Files.list(dir.resolve("tests")).use { s ->
                s.filter { it.fileName.toString().endsWith(".in") && Files.readString(it).isNotBlank() }.toList()
            }.sortedBy { it.fileName.toString().removeSuffix(".in").toIntOrNull() ?: Int.MAX_VALUE }
            if (inputs.isEmpty()) return "No tests in ${dir.resolve("tests")}"
            for (inp in inputs) onResult(runOne(cmd, inp, tmp))
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
            val cmd = command(src, tmp)
            val inFile = tmp.resolve("custom.in")
            Files.writeString(inFile, input)
            return runOne(cmd, inFile, tmp)
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    private fun command(src: Path, tmp: Path): List<String> {
        val sol = tmp.resolve("sol").toString()
        return when (src.fileName.toString().substringAfterLast('.')) {
            "cpp", "cc", "cxx" -> {
                compile(listOf("c++", "-O2", "-std=c++17", "-I", tmp.toString(), "-o", sol, src.toString()), tmp)?.let { throw EjudgeException(it) }
                listOf(sol)
            }
            "c" -> {
                compile(listOf("cc", "-O2", "-o", sol, src.toString(), "-lm"), tmp)?.let { throw EjudgeException(it) }
                listOf(sol)
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
    private fun compile(cmd: List<String>, tmp: Path): String? {
        val log = tmp.resolve("compile.log")
        val p = ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(log.toFile()).start()
        if (!p.waitFor(120, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            return "Compilation timed out"
        }
        return if (p.exitValue() == 0) null else "Compilation error:\n" + Files.readString(log)
    }

    private fun runOne(cmd: List<String>, inp: Path, tmp: Path): TestResult {
        val name = inp.fileName.toString().removeSuffix(".in")
        val outFile = inp.resolveSibling("$name.out")
        val expected = if (Files.exists(outFile)) Files.readString(outFile) else ""
        val input = Files.readString(inp)
        val actualFile = tmp.resolve("$name.actual")
        val errFile = tmp.resolve("$name.err")
        val start = System.currentTimeMillis()
        val p = ProcessBuilder(cmd).redirectInput(inp.toFile())
            .redirectOutput(actualFile.toFile()).redirectError(errFile.toFile()).start()
        val finished = p.waitFor(TIME_LIMIT_SEC, TimeUnit.SECONDS)
        val ms = System.currentTimeMillis() - start
        if (!finished) p.destroyForcibly()
        val actual = Files.readString(actualFile)
        val err = Files.readString(errFile)
        fun r(verdict: String, ok: Boolean, note: String = "") = TestResult(name, verdict, ok, ms, input, expected, actual, note)
        return when {
            !finished -> r("TL", false, "Time limit ${TIME_LIMIT_SEC}s exceeded")
            p.exitValue() != 0 -> r("RE", false, "Exit code ${p.exitValue()}\n$err")
            !Files.exists(outFile) -> r("?", true, "No expected output file; output shown only")
            tokens(actual) == tokens(expected) -> r("OK", true)
            else -> r("WA", false)
        }
    }

    private fun tokens(s: String) = s.trim().split(Regex("\\s+"))
}
