import org.gradle.api.Project
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

object Common {
    fun getBuildVersionCode(): Int {
        val appVerCode: Int by lazy {
            val versionCode = SimpleDateFormat("yyMMddHH", Locale.ENGLISH).format(Date())
            versionCode.toInt()
        }
        return appVerCode
    }

    private fun getCurrentDate(project: Project): String {
        val override = project.findProperty("buildVersionTimestamp") as String?
        if (!override.isNullOrBlank()) {
            return override
        }

        val sdf = SimpleDateFormat("MMddHHmm", Locale.ENGLISH)
        sdf.timeZone = TimeZone.getTimeZone("Asia/Taipei")
        return sdf.format(Date())
    }


    internal fun getSourceRevision(rootDirectory: File): String {
        // A source archive inside another checkout must not inherit the parent's revision.
        if (!File(rootDirectory, ".git").exists()) return "no_commit"
        val revision = readGit(rootDirectory, "rev-parse", "--short=12", "HEAD")
            ?.takeIf { it.matches(Regex("[0-9a-fA-F]{12,40}")) } ?: return "no_commit"
        val status = readGit(rootDirectory, "status", "--porcelain", "--untracked-files=normal")
        return revision + when {
            status == null -> ".unknown"
            status.isNotEmpty() -> ".dirty"
            else -> ""
        }
    }

    private fun readGit(rootDirectory: File, vararg arguments: String): String? {
        return try {
            val root = rootDirectory.canonicalFile
            // Scope trust and cwd to this user-selected checkout, never mutate global Git config.
            val process = ProcessBuilder("git", "-c", "safe.directory=${root.invariantSeparatorsPath}",
                "-C", root.absolutePath, *arguments).redirectErrorStream(true).start()
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) return null
                val text = process.inputStream.bufferedReader().use { it.readText() }.trim()
                text.takeIf { process.exitValue() == 0 }
            } finally {
                if (process.isAlive) process.destroyForcibly()
                process.inputStream.close()
            }
        } catch (_: IOException) {
            null
        }
    }

    fun getBuildVersionName(project: Project): String {
        return "${getSourceRevision(project.rootDir)}.${getCurrentDate(project)}"
    }
}

