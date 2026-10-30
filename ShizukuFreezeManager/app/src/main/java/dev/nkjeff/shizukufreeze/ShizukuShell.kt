package dev.nkjeff.shizukufreeze

import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

data class AppInstance(
    val packageName: String,
    val userId: Int,
    val label: String,
    val isSystem: Boolean,
    val isDisabled: Boolean,
    val isClone: Boolean
) {
    val key: String get() = "$packageName@$userId"
}

class ShizukuShell {
    fun available(): Boolean = Shizuku.pingBinder() &&
        Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED

    fun run(command: String): String {
        check(available()) { "Shizuku 尚未授权" }
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        ).apply { isAccessible = true }
        val process = method.invoke(null, arrayOf("sh", "-c", command), null, null) as Process
        val stdout = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
        val stderr = BufferedReader(InputStreamReader(process.errorStream)).use { it.readText() }
        val code = process.waitFor()
        if (code != 0) error(stderr.ifBlank { "命令执行失败 ($code)" })
        return stdout.trim()
    }

    fun scanUsers(): List<Int> {
        val output = run("pm list users")
        return Regex("""UserInfo\\{(\\d+):""").findAll(output)
            .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
            .distinct().toList().ifEmpty { listOf(0) }
    }

    fun scanPackages(userId: Int): Pair<Set<String>, Set<String>> {
        val enabled = run("pm list packages --user $userId -3")
        val disabled = runCatching { run("pm list packages --user $userId -3 -d") }.getOrDefault("")
        val system = runCatching { run("pm list packages --user $userId -s") }.getOrDefault("")
        fun parse(s: String) = Regex("""package:([^\\s]+)""").findAll(s).map { it.groupValues[1] }.toSet()
        val disabledSet = parse(disabled)
        val thirdParty = (parse(enabled) + disabledSet) - parse(system) - "android"
        return thirdParty to (disabledSet intersect thirdParty)
    }

    /**
     * Returns running third-party app instances as packageName@userId.
     * Process UID is matched against Package Manager's per-user package UID list.
     */
    fun scanRunningInstances(): Set<String> {
        val users = scanUsers()
        val packageUidToKey = mutableMapOf<Int, MutableSet<String>>()
        val packageNamesByUser = mutableMapOf<Int, Set<String>>()

        for (userId in users) {
            val (packages, disabled) = scanPackages(userId)
            packageNamesByUser[userId] = packages - disabled
            val output = runCatching {
                run("cmd package list packages -U --user $userId")
            }.getOrDefault("")
            Regex("""package:([^\\s]+)\\s+uid:(\\d+)""").findAll(output).forEach { match ->
                val pkg = match.groupValues[1]
                val uid = match.groupValues[2].toIntOrNull() ?: return@forEach
                if (pkg in packages && pkg !in disabled && pkg != "android") {
                    packageUidToKey.getOrPut(uid) { mutableSetOf() }.add("$pkg@$userId")
                }
            }
        }

        val ps = runCatching { run("ps -A -o UID,NAME") }
            .recoverCatching { run("ps -A") }
            .getOrDefault("")
        val running = mutableSetOf<String>()
        val lines = ps.lineSequence().drop(1)
        for (line in lines) {
            val columns = line.trim().split(Regex("\\s+"))
            if (columns.size < 2) continue
            val uidText = columns[0]
            val processName = columns.last()
            val numericUid = uidText.toIntOrNull()
            if (numericUid != null) {
                packageUidToKey[numericUid]?.let { running.addAll(it) }
                continue
            }
            // Fallback for Android's textual UIDs, e.g. u0_a123 / u10_a123.
            val userId = Regex("""^u(\\d+)_""").find(uidText)?.groupValues?.get(1)?.toIntOrNull()
            if (userId != null) {
                val baseName = processName.substringBefore(':')
                packageNamesByUser[userId].orEmpty().filter { pkg ->
                    baseName == pkg || processName.startsWith("$pkg:")
                }.forEach { running.add("$it@$userId") }
            }
        }
        return running
    }

    fun freeze(app: AppInstance) {
        run("pm disable-user --user ${app.userId} ${quote(app.packageName)}")
    }

    fun unfreeze(app: AppInstance) {
        run("pm enable --user ${app.userId} ${quote(app.packageName)}")
    }

    fun launch(app: AppInstance) {
        val resolved = run("cmd package resolve-activity --brief --user ${app.userId} ${quote(app.packageName)}")
        val component = resolved.lineSequence().map { it.trim() }
            .lastOrNull { it.contains("/") && !it.startsWith("priority=") }
            ?: error("无法解析该用户下的启动 Activity，已取消启动以防打开错实例")
        require(component.startsWith(app.packageName + "/")) { "解析到的 Activity 包名不匹配，已取消启动" }
        run("am start --user ${app.userId} -n ${quote(component)}")
    }

    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
}
