import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CommonGitVersionTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `reads the selected checkout with spaces and marks tracked changes`() {
        val root = repository("project with spaces")
        val revision = git(root, "rev-parse", "--short=12", "HEAD")
        assertEquals(revision, Common.getSourceRevision(root))
        File(root, "source.txt").writeText("modified")
        assertEquals("$revision.dirty", Common.getSourceRevision(root))
    }

    @Test fun `untracked source files also mark a dirty build`() {
        val root = repository("untracked")
        val revision = git(root, "rev-parse", "--short=12", "HEAD")
        File(root, "new-source.txt").writeText("new source")
        assertEquals("$revision.dirty", Common.getSourceRevision(root))
    }

    @Test fun `source archives cannot inherit a parent checkout revision`() {
        val root = repository("parent")
        val archive = File(root, "source archive").apply { mkdirs() }
        assertEquals("no_commit", Common.getSourceRevision(archive))
    }

    private fun repository(name: String): File = temporary.newFolder(name).apply {
        git(this, "init", "--quiet")
        File(this, "source.txt").writeText("source")
        git(this, "add", "source.txt")
        git(this, "-c", "user.name=UniMusic Test", "-c", "user.email=test@example.invalid",
            "-c", "commit.gpgsign=false", "commit", "--quiet", "-m", "fixture")
    }

    private fun git(root: File, vararg arguments: String): String {
        val process = ProcessBuilder("git", "-c", "safe.directory=${root.invariantSeparatorsPath}",
            "-c", "core.hooksPath=${File(root, ".no-hooks").absolutePath}", "-C", root.absolutePath, *arguments).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor() == 0) { "Fixture Git operation failed" }
        return output.trim()
    }
}
