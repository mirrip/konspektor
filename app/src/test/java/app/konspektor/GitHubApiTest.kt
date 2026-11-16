package app.konspektor

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Runs the phone's GitHub client against the real data repository, READ-ONLY: the requests the
 * incremental sync makes (branch head, compare, single-directory listings, file SHAs, raw reads).
 * Run with: -Dkonspektor.tokenFile=<file with github_pat_…> [-Dkonspektor.repo=owner/name]
 */
class GitHubApiTest {
    private val tokenFile = System.getProperty("konspektor.tokenFile").orEmpty()
    private val repo = System.getProperty("konspektor.repo") ?: "mirrip/konspektor-data"

    /** First commit with the repository structure (inbox, subjects, homework, index.json). */
    private val structureCommit = "613ddd6092377bc94fdf35bd2e0b68229d8cd8ac"

    @Test
    fun incrementalSyncRequestsWork() {
        assumeTrue("no token file given", tokenFile.isNotBlank() && File(tokenFile).exists())
        val token = Regex("github_pat_[A-Za-z0-9_]+").find(File(tokenFile).readText())!!.value
        val api = GitHubApi(token, repo)

        val branch = api.checkRepo()
        val head = api.headSha(branch)
        assertEquals(40, head.length)

        // what the phone asks after a long pause: everything since the structure commit
        val changes = api.compare(structureCommit, head)!!
        assertTrue(changes.any { it.path == "index.json" && it.status == "modified" })
        assertTrue(changes.any { it.path.endsWith("/konspekt.md") && it.status == "added" })
        assertTrue(api.compare(head, head)!!.isEmpty())
        assertNull(api.compare("0000000000000000000000000000000000000000", head)) // unknown base → full pass

        // full pass lists only two directories
        val root = api.listDir(branch)
        assertTrue(root.getValue("homework").isDir)
        assertFalse(root.getValue("index.json").isDir)
        assertTrue("README.md" in api.listDir(root.getValue("homework").sha))

        assertEquals(root.getValue("index.json").sha, api.fileSha("index.json"))
        assertNull(api.fileSha("inbox/нет такого файла.txt"))

        // every lesson in index.json has its konspekt where the path says (Cyrillic, spaces, dash)
        val lessons = JSONObject(api.readText("index.json")!!).getJSONObject("lessons")
        for (id in lessons.keys()) {
            val path = lessons.getJSONObject(id).getString("path")
            assertNotNull("konspekt of $id", api.readText("$path/konspekt.md"))
        }
        println("GitHub read-only OK on $repo ($branch @ ${head.take(7)}): ${changes.size} changes since structure, ${lessons.length()} lessons")
    }
}
