package com.ejudge.clion

import org.jsoup.nodes.Element
import java.nio.file.Files
import java.nio.file.Path

/** Statement images are served under a session-bound URL, so they are saved next to the statement. */
object StatementImages {
    /** Downloads every remote image of [body] into `<dir>/images` and points the `src` to the local copy. */
    fun localize(client: EjudgeClient, body: Element, dir: Path) {
        for (img in body.select("img[src]")) {
            val src = img.absUrl("src").ifEmpty { img.attr("src") }
            if (!src.startsWith("http")) continue
            val name = Regex("[?&]file=([^&]+)").find(src)?.groupValues?.get(1) ?: "img${src.hashCode().toUInt()}"
            val safe = name.replace(Regex("[^\\w.-]"), "_").let { if (it.contains('.')) it else "$it.png" }
            val target = dir.resolve("images").resolve(safe)
            try {
                Files.createDirectories(target.parent)
                if (!Files.exists(target)) Files.write(target, client.download(src))
                img.attr("src", "images/$safe")
                img.removeAttr("style")
            } catch (_: Exception) {
                // keep the remote src; the panel will try again next time
            }
        }
    }
}
