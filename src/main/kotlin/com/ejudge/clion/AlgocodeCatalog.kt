package com.ejudge.clion

import org.jsoup.Jsoup

data class Parallel(val name: String, val url: String) {
    override fun toString() = name
}

data class ContestInfo(val id: Int, val date: String, val topic: String) {
    override fun toString() = "$date — $topic ($id)"
}

/** Reads the course structure of algocode.ru: parallels from the home page, contests from a parallel page. */
object AlgocodeCatalog {
    private const val HOME = "https://algocode.ru/"

    fun parallels(): List<Parallel> =
        Jsoup.connect(HOME).get().select(".courses a[href]")
            .filter { it.attr("href").matches(Regex("/[a-z]+\\d{4}/")) }
            .map { Parallel(it.text().trim(), it.absUrl("href")) }

    fun contests(parallel: Parallel): List<ContestInfo> =
        Jsoup.connect(parallel.url).get().select(".lesson").flatMap { lesson ->
            val date = lesson.selectFirst(".lesson_date")?.text()?.trim() ?: ""
            val topic = lesson.selectFirst(".lesson_topic")?.text()?.trim() ?: ""
            lesson.select("a[href*=new-client?contest_id=]").mapNotNull { a ->
                Regex("contest_id=(\\d+)").find(a.attr("href"))?.groupValues?.get(1)?.toInt()
                    ?.let { ContestInfo(it, date, topic) }
            }
        }
}
