package com.example.crtui

import android.os.Environment
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.CopyOnWriteArrayList

enum class SessionType { LOCAL, SSH }

data class SshConfig(val name: String, val user: String, val host: String, val port: Int, val pass: String = "")

class TerminalSession(var name: String, val type: SessionType, val sshConfig: SshConfig? = null) {
    val history = CopyOnWriteArrayList<String>(listOf("> Session established: $name"))
    var buffer = ""
    var cursor = 0
    var cwd: File = Environment.getExternalStorageDirectory()

    private var jschSession: com.jcraft.jsch.Session? = null
    private var shellChannel: ChannelShell? = null
    private var sshOut: java.io.OutputStream? = null

    // Regex to match standard ANSI escape sequences (colors, cursor movements, mode toggles)
    private val ansiRegex = Regex("\u001B\\[[;\\d?]*[a-zA-Z]")

    fun getPrompt(): String {
        return if (type == SessionType.LOCAL) "LOCAL // ${cwd.name} > " else "${sshConfig?.user}@${sshConfig?.host} > "
    }

    fun connectSsh(onUpdate: () -> Unit) {
        Thread {
            try {
                history.add("> Negotiating SSH handshake with ${sshConfig?.host}...")
                onUpdate()

                val jsch = JSch()
                jschSession = jsch.getSession(sshConfig!!.user, sshConfig.host, sshConfig.port)
                jschSession!!.setConfig("StrictHostKeyChecking", "no")
                jschSession!!.setConfig("PreferredAuthentications", "password,keyboard-interactive,publickey")

                var promptCount = 0
                jschSession!!.userInfo = object : UserInfo, UIKeyboardInteractive {
                    override fun getPassphrase(): String? = null
                    override fun getPassword(): String = sshConfig.pass

                    override fun promptPassword(message: String?): Boolean {
                        if (sshConfig.pass.isEmpty() || promptCount > 0) return false
                        promptCount++
                        return true
                    }

                    override fun promptPassphrase(message: String?): Boolean = true
                    override fun promptYesNo(message: String?): Boolean = true
                    override fun showMessage(message: String?) {}

                    override fun promptKeyboardInteractive(
                        destination: String?,
                        name: String?,
                        instruction: String?,
                        prompt: Array<out String>?,
                        echo: BooleanArray?
                    ): Array<String>? {
                        if (sshConfig.pass.isEmpty() || promptCount > 0) {
                            history.add("> Auth failed: Password required or rejected.")
                            onUpdate()
                            return null
                        }
                        promptCount++

                        val responseCount = prompt?.size ?: 0
                        if (responseCount == 0) return emptyArray()
                        return Array(responseCount) { sshConfig.pass }
                    }
                }

                if (sshConfig.pass.isNotEmpty()) {
                    jschSession!!.setPassword(sshConfig.pass)
                }

                jschSession!!.connect(10000)

                shellChannel = jschSession!!.openChannel("shell") as ChannelShell

                // Downgrade from xterm to dumb to prevent the server from trying to send complex colors
                shellChannel!!.setPtyType("dumb")
                shellChannel!!.setPty(true)

                sshOut = shellChannel!!.outputStream
                val sshIn = shellChannel!!.inputStream

                shellChannel!!.connect(5000)
                history.add("> SSH Connection Established.")
                onUpdate()

                val reader = BufferedReader(InputStreamReader(sshIn))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    // Scrub the line of any lingering ANSI codes before adding it to the UI
                    val cleanLine = line!!.replace(ansiRegex, "")
                    history.add(cleanLine)
                    onUpdate()
                }
            } catch (e: Exception) {
                history.add("> SSH Disconnected: ${e.message}")
                onUpdate()
            } finally {
                disconnectSsh()
            }
        }.start()
    }

    fun sendCommand(cmd: String, onUpdate: () -> Unit) {
        if (type == SessionType.SSH) {
            Thread {
                try {
                    sshOut?.write("$cmd\n".toByteArray())
                    sshOut?.flush()
                } catch (e: Exception) {
                    history.add("> Error sending command: ${e.message}")
                    onUpdate()
                }
            }.start()
        }
    }

    fun disconnectSsh() {
        try {
            shellChannel?.disconnect()
            jschSession?.disconnect()
        } catch (e: Exception) {}
    }
}