package app.konspektor

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Runs the phone's GitHub client against the real data repository, the same way the app does:
 * upload a lesson to inbox/, read it back, update it (sha handling), list the tree, clean up.
 * Run with: gradlew testDebugUnitTest -Pkonspektor.tokenFile=<path to file with github_pat_…>
 */
class GitHubApiTest {
    private val tokenFile = System.getProperty("konspektor.tokenFile").orEmpty()
    private val repo = System.getProperty("konspektor.repo") ?: "mirrip/konspektor-data"

    @Test
    fun roundTripThroughInbox() {
        assumeTrue("no token file given", tokenFile.isNotBlank() && File(tokenFile).exists())
        val token = Regex("github_pat_[A-Za-z0-9_]+").find(File(tokenFile).readText())!!.value
        val api = GitHubApi(token, repo)

        val branch = api.checkRepo()
        val id = "2026-11-16_12-00_Общий_test"
        val base = "inbox/$id"
        val text = "[00:00] Сегодня тема урока — законы Ньютона. Домашнее задание: №12 и 15 к пятнице.\n"
        val meta = JSONObject().put("lessonId", id).put("complete", false).put("subject_hint", "Физика")

        val sha1 = api.writeText("$base/transcript.txt", text, "Тест: урок $id", null)
        api.writeText("$base/meta.json", meta.toString(2), "Тест: meta", null)
        assertEquals(text, api.readText("$base/transcript.txt"))

        // update without knowing the sha -> client must recover (409/422 path)
        val sha2 = api.writeText("$base/transcript.txt", text + "Ещё строка.\n", "Тест: правка", null)
        assertTrue(sha1 != sha2)

        val tree = api.tree(branch)
        assertNotNull(tree["$base/transcript.txt"])
        assertNotNull(tree["subjects/Вероятность и статистика/README.md"])
        assertNotNull(tree["index.json"])

        // clean up
        api.delete("$base/transcript.txt", tree["$base/transcript.txt"]!!, "Тест: удалить")
        api.delete("$base/meta.json", tree["$base/meta.json"]!!, "Тест: удалить")
        assertNull(api.readText("$base/transcript.txt"))
        println("GitHub round trip OK on $repo ($branch), files in tree: ${tree.size}")
    }
}
