package app.familyhub.client.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** Every icon in tools/design-assets/icons.txt has its generated outlined and filled drawables (ADR-0016). */
class IconResourcesTest {
    @Test
    fun `every listed icon has both drawables`() {
        // Tests run in the module directory (client/shared).
        val list = File("../../tools/design-assets/icons.txt")
        val drawables = File("src/commonMain/composeResources/drawable")
        val names = list.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        assertTrue(names.size >= 50, "icons.txt looks truncated: ${names.size}")
        val missing = names.flatMap { listOf("ic_$it.xml", "ic_${it}_filled.xml") }.filterNot { File(drawables, it).isFile }
        assertTrue(missing.isEmpty(), "Missing drawables, rerun tools/design-assets/build.py: $missing")
    }
}
