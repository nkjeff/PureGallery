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
        // Shizuku API 13.1.1+ marks newProcess private while retaining it for
        // compatibility until API 14. Invoke the retained API reflectively.
        // A future revision should migrate this shell bridge to UserService.
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
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
        return Regex("""UserInfo\{(\d+):""").findAll(output)
            .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
            .distinct().toList().ifEmpty { listOf(0) }
    }

    fun scanPackages(userId: Int): Pair<Set<String>, Set<String>> {
        val enabled = runCatching { run("pm list packages --user $userId -3") }.getOrDefault("")
        val disabled = runCatching { run("pm list packages --user $userId -3 -d") }.getOrDefault("")
        fun parse(s: String) = Regex("""package:([^\s]+)""").findAll(s)
            .map { it.groupValues[1] }.toSet()
        return parse(enabled) + parse(disabled) to parse(disabled)
    }

    fun freeze(app: AppInstance) {
        run("pm disable-user --user ${app.userId} ${quote(app.packageName)}")
    }

    fun unfreeze(app: AppInstance) {
        run("pm enable --user ${app.userId} ${quote(app.packageName)}")
    }

    fun launch(app: AppInstance) {
        // Resolve in the target user first. Never fall back to the owner's profile.
        val resolved = run("cmd package resolve-activity --brief --user ${app.userId} ${quote(app.packageName)}")
        val component = resolved.lineSequence().map { it.trim() }
            .lastOrNull { it.contains("/") && !it.startsWith("priority=") }
            ?: error("无法解析该用户下的启动 Activity，已取消启动以防打开错实例")
        require(component.startsWith(app.packageName + "/")) { "解析到的 Activity 包名不匹配，已取消启动" }
        run("am start --user ${app.userId} -n ${quote(component)}")
    }

    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
}
