package dev.huenotes

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Guards translations: every language has exactly the same keys, placeholders and no blank values. */
class StringsTest {
    private val langs = listOf("fr", "es", "pt", "de")
    private val placeholder = Regex("%\\d\\$[sd]|%%")

    private fun load(dir: String): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/$dir/strings.xml"))
        val out = linkedMapOf<String, String>()
        val s = doc.getElementsByTagName("string")
        for (i in 0 until s.length) out[s.item(i).attributes.getNamedItem("name").nodeValue] = s.item(i).textContent
        val p = doc.getElementsByTagName("plurals")
        for (i in 0 until p.length) {
            val items = p.item(i).childNodes
            for (j in 0 until items.length) {
                val it = items.item(j)
                if (it.nodeName == "item") out[p.item(i).attributes.getNamedItem("name").nodeValue + "#" + it.attributes.getNamedItem("quantity").nodeValue] = it.textContent
            }
        }
        return out
    }

    @Test fun everyLanguageMatchesEnglish() {
        val en = load("values")
        assertTrue(en.size > 100)
        for (l in langs) {
            val other = load("values-$l")
            assertEquals("keys differ in $l", en.keys, other.keys)
            for ((k, v) in en) {
                assertEquals("placeholders differ for $k in $l", placeholder.findAll(v).map { it.value }.sorted().toList(),
                    placeholder.findAll(other[k]!!).map { it.value }.sorted().toList())
                assertTrue("blank $k in $l", other[k]!!.isNotBlank())
            }
        }
    }

    @Test fun localeConfigListsEveryTranslation() {
        val cfg = File("src/main/res/xml/locales_config.xml").readText()
        (listOf("en") + langs).forEach { assertTrue("missing $it", cfg.contains("android:name=\"$it\"")) }
    }
}
