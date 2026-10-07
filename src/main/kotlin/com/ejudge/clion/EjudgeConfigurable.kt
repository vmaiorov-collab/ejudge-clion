package com.ejudge.clion

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel

class EjudgeConfigurable : BoundConfigurable("Ejudge") {
    private val settings = EjudgeSettings.getInstance()
    private var password = settings.password

    override fun createPanel(): DialogPanel = panel {
        row("Server URL:") {
            textField().bindText(settings.state::serverUrl).columns(COLUMNS_LARGE)
                .comment("Address of the new-client CGI, e.g. https://ejudge.algocode.ru/cgi-bin/new-client")
        }
        row("Contest ID:") { intTextField().bindIntText(settings.state::contestId) }
        row("Contests folder:") {
            textField().bindText(settings.state::contestsDir).columns(COLUMNS_LARGE)
                .comment("Imported problems are saved here, outside the project. “~” means the home folder of this computer, so the setting works on any PC")
        }
        row {
            checkBox("Send anonymous usage statistics").bindSelected(settings.state::sendStats)
                .comment("Only counts how many people use the plugin: no login, no personal data, no identifier is sent. Details in the README")
        }
        row("Login:") { textField().bindText(settings.state::login) }
        row("Password:") { passwordField().bindText(::password) }
    }

    override fun apply() {
        super.apply()
        settings.password = password
    }
}
