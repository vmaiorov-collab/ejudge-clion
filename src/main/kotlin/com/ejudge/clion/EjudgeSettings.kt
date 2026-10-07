package com.ejudge.clion

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@State(name = "EjudgeSettings", storages = [Storage("ejudge.xml")])
class EjudgeSettings : PersistentStateComponent<EjudgeSettings.Data> {
    class Data {
        var serverUrl: String = "https://ejudge.algocode.ru/cgi-bin/new-client"
        var contestId: Int = 0
        var login: String = ""
        var onboardingDone: Boolean = false
        var contestsDir: String = "~/ejudge-contests"
    }

    private var data = Data()

    override fun getState(): Data = data
    override fun loadState(state: Data) {
        data = state
    }

    var password: String
        get() = PasswordSafe.instance.getPassword(attrs()) ?: ""
        set(value) = PasswordSafe.instance.set(attrs(), Credentials(data.login, value))

    private fun attrs() = CredentialAttributes(generateServiceName("Ejudge", "password"))

    /** The contests folder; a leading "~" is the home directory of the current computer, so the setting is portable. */
    fun contestsPath(): java.nio.file.Path {
        val raw = data.contestsDir.trim().ifEmpty { "~/ejudge-contests" }
        val home = System.getProperty("user.home")
        val expanded = if (raw == "~") home else if (raw.startsWith("~/")) home + raw.substring(1) else raw
        return java.nio.file.Path.of(expanded)
    }

    companion object {
        fun getInstance(): EjudgeSettings = ApplicationManager.getApplication().getService(EjudgeSettings::class.java)
    }
}
