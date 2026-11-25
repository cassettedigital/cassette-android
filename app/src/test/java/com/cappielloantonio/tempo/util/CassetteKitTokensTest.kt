package com.cappielloantonio.tempo.util

import digital.cassette.kit.CassetteKitTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Android's sizes are the shared kit's, never its own (design wave 6). The kit's generator writes
 * digital/cassette/kit/CassetteKitTokens.kt and res/values{,-night}/cassette_kit.xml from
 * packages/cassette-kit/tokens.json; they are copied in together. These fail if the two copies drift
 * apart, or if a key's style or a record head's key row types a number of its own instead of reading
 * the kit's (C-rows-model: the website's rows are the model).
 */
class CassetteKitTokensTest {
    private val res = File("src/main/res")

    /** name -> raw value of every dimen, integer, colour and float item in a resource file. */
    private fun values(path: String): Map<String, String> {
        val text = File(res, path).readText()
        val out = mutableMapOf<String, String>()
        Regex("""<(dimen|color|integer) name="([a-z0-9_]+)">([^<]+)</\1>""").findAll(text)
            .forEach { out[it.groupValues[2]] = it.groupValues[3].trim() }
        Regex("""<item name="([a-z0-9_]+)" format="float" type="dimen">([^<]+)</item>""").findAll(text)
            .forEach { out[it.groupValues[1]] = it.groupValues[2].trim() }
        return out
    }

    private fun number(v: String?): Float = v!!.removeSuffix("dp").removeSuffix("sp").toFloat()

    private fun colour(v: String?): Long = ("FF" + v!!.removePrefix("#")).toLong(16)

    /** The items of one style in a resource file, by attribute name. */
    private fun style(path: String, name: String): Map<String, String> {
        val text = File(res, path).readText()
        val body = Regex("""<style name="${Regex.escape(name)}"[^>]*>(.*?)</style>""", RegexOption.DOT_MATCHES_ALL)
            .find(text)?.groupValues?.get(1) ?: error("no style $name in $path")
        return Regex("""<item name="([^"]+)">([^<]+)</item>""").findAll(body)
            .associate { it.groupValues[1] to it.groupValues[2].trim() }
    }

    @Test
    fun keysAndCornersAreTheKits() {
        val v = values("values/cassette_kit.xml")
        val c = CassetteKitTokens.Control
        assertEquals(c.action, number(v["kit_key_action"]))
        assertEquals(c.head, number(v["kit_key_head"]))
        assertEquals(c.card, number(v["kit_key_card"]))
        assertEquals(c.chip, number(v["kit_key_chip"]))
        assertEquals(c.row, number(v["kit_key_row"]))
        assertEquals(c.player, number(v["kit_key_player"]))
        assertEquals(c.keyLetters, number(v["kit_key_letters"]))
        assertEquals(c.keyPad, number(v["kit_key_pad"]))
        assertEquals(c.keyGap, number(v["kit_key_gap"]))
        assertEquals(c.keyLetters * 1.25f, number(v["kit_key_glyph"]))
        assertEquals(c.keyTrackEm, number(v["kit_key_track"]), 0.001f)

        val r = CassetteKitTokens.Radius
        assertEquals(r.artworkTile, number(v["kit_radius_artwork_tile"]))
        assertEquals(r.artworkRow, number(v["kit_radius_artwork_row"]))
        assertEquals(r.card, number(v["kit_radius_card"]))
        assertEquals(r.panel, number(v["kit_radius_panel"]))
        assertEquals(r.litCard, number(v["kit_radius_lit_card"]))
        assertEquals(r.playerArtwork, number(v["kit_radius_player_artwork"]))
        // AND-25: the cover glow's numbers are ratios, never lengths
        assertTrue(v["kit_ambient_scale"]!!.none { it.isLetter() })
    }

    @Test
    fun coloursAreTheKitsOnBothPapers() {
        val dark = values("values-night/cassette_kit.xml")
        val light = values("values/cassette_kit.xml")
        assertEquals(CassetteKitTokens.Color.textPrimary, colour(dark["kit_text_primary"]))
        assertEquals(CassetteKitTokens.Color.surfaceBase, colour(dark["kit_surface_base"]))
        assertEquals(CassetteKitTokens.Color.accentAnswer, colour(dark["kit_accent_answer"]))
        assertEquals(CassetteKitTokens.ColorLight.textPrimary, colour(light["kit_text_primary"]))
        assertEquals(CassetteKitTokens.ColorLight.surfaceBase, colour(light["kit_surface_base"]))
        assertEquals(CassetteKitTokens.ColorLight.fillSelected, colour(light["kit_fill_selected"]))
    }

    /** A key reads its height, side room, glyph, glyph gap and letters from the kit, in the bold file. */
    @Test
    fun theKeyIsTheKits() {
        for (name in listOf("Cassette.Pill", "Cassette.Pill.Shuffle")) {
            val s = style("values/styles.xml", name)
            assertEquals(name, "@dimen/kit_key_action", s["android:minHeight"])
            assertEquals(name, "@dimen/kit_key_pad", s["android:paddingStart"])
            assertEquals(name, "@dimen/kit_key_pad", s["android:paddingEnd"])
            assertEquals(name, "@dimen/kit_key_glyph", s["iconSize"])
            assertEquals(name, "@dimen/kit_key_gap", s["iconPadding"])
            assertEquals(name, "@style/Cassette.Role.Pill", s["android:textAppearance"])
        }
        val words = style("values/typography.xml", "Cassette.Role.Pill")
        assertEquals("@dimen/kit_key_letters", words["android:textSize"])
        assertEquals("@dimen/kit_key_track", words["android:letterSpacing"])
        assertEquals("@font/barlow_condensed_bold", words["android:fontFamily"])
    }

    /** Every record head's keys sit at their own widths (the website's row), never stretched halves. */
    @Test
    fun recordRowsAreTheWebsites() {
        val pages = listOf(
            "fragment_album_page.xml", "fragment_playlist_page.xml", "fragment_genre_page.xml",
            "fragment_artist_page.xml", "fragment_station_page.xml",
        )
        for (page in pages) {
            val text = File(res, "layout/$page").readText()
            val keys = Regex("""<com\.google\.android\.material\.button\.MaterialButton\b(.*?)/>""", RegexOption.DOT_MATCHES_ALL)
                .findAll(text).map { it.groupValues[1] }.filter { "Cassette.Pill" in it }.toList()
            assertEquals(page, 2, keys.size)
            for (key in keys) {
                assertTrue(page, "android:layout_width=\"wrap_content\"" in key)
                assertTrue(page, "layout_weight" !in key)
            }
        }
    }

    /** Every Settings row is the one settings row, its two voices and its edge the kit's. */
    @Test
    fun theSettingsRowIsTheKits() {
        val theme = style("values/styles.xml", "Cassette.PreferenceTheme")
        for (attr in listOf("preferenceStyle", "preferenceScreenStyle", "dialogPreferenceStyle", "switchPreferenceCompatStyle")) {
            val row = style("values/styles.xml", theme[attr]!!.removePrefix("@style/"))
            assertEquals(attr, "@layout/cassette_setting_row", row["layout"])
            assertEquals(attr, "false", row["iconSpaceReserved"])
        }
        val title = style("values/typography.xml", "Cassette.Setting.Title")
        assertEquals("@dimen/kit_type_row_title", title["android:textSize"])
        val answer = style("values/typography.xml", "Cassette.Setting.Answer")
        assertEquals("@dimen/kit_type_caption", answer["android:textSize"])
        assertEquals("@font/dm_mono", answer["android:fontFamily"])
        val row = File(res, "layout/cassette_setting_row.xml").readText()
        assertTrue("android:paddingStart=\"@dimen/kit_space_20\"" in row)
        assertTrue("android:paddingEnd=\"@dimen/kit_space_20\"" in row)
        assertTrue("@style/Cassette.Setting.Title" in row && "@style/Cassette.Setting.Answer" in row)
    }

    /** Every shaped picture wears the kit's ring (ink at the kit's tile-ring strength). */
    @Test
    fun everyPictureHasTheKitsRing() {
        assertTrue(File(res, "color/cs_tile_ring.xml").readText().contains("android:alpha=\"@dimen/kit_tile_ring\""))
        val shaped = Regex("""<com\.google\.android\.material\.imageview\.ShapeableImageView\b(.*?)/?>""", RegexOption.DOT_MATCHES_ALL)
        val layouts = File(res, "layout").listFiles()!!.filter { it.name.endsWith(".xml") }
        var seen = 0
        for (f in layouts) {
            for (m in shaped.findAll(f.readText())) {
                val view = m.groupValues[1]
                if ("shapeAppearanceOverlay" !in view) continue
                seen++
                assertTrue(f.name, "app:strokeColor=\"@color/cs_tile_ring\"" in view)
            }
        }
        assertTrue(seen > 20)
    }
}
