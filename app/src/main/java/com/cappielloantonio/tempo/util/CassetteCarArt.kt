package com.cappielloantonio.tempo.util

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.bumptech.glide.Glide
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.glide.CustomGlideRequest
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * cassette: every picture the car shows (Android Auto), handed over from this phone, the way
 * CarPlay draws every picture from the phone (CarPlayStyle on the iPhone).
 *
 * Android Auto only takes pictures as on-phone addresses (content://), never a web link. The car
 * used to be handed the computer's getCoverArt address and fetched it itself, so on the road a
 * cover showed or didn't depending on the signal. Here a cover is the file already synced to the
 * phone (CassetteAlbumArt, the same file the phone's own screens show), the computer's copy only
 * when the phone has none, and a lettered tile when neither answers (BUG-421 on the iPhone).
 *
 * The rest is made for the car and drawn on demand, never kept: an album's track numbers, the
 * Play / Shuffle / tab glyphs (white, the car's ink), an artist's photo in a circle, and a
 * station's name on its own colour when it has no logo. A made picture is written to a scratch
 * file, opened, and the file deleted at once, so nothing is stored beside the person's pictures.
 *
 * Addresses: cover/<coverArtId>[?circle=1&alt=<coverArtId>&name=…], web/<key>[?name=…],
 * number/<n>, glyph/<name>[?tab=1], word?text=…&color=#RRGGBB. A web address is only ever one the
 * car's own lists registered ([web]), so nothing else can make the phone fetch a link.
 */
class CassetteCarArt : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "image/*"

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read only")
        val context = context ?: throw FileNotFoundException(uri.toString())
        val segments = uri.pathSegments
        val name = uri.getQueryParameter("name")
        return when (segments.firstOrNull()) {
            "cover" -> cover(context, segments.getOrNull(1)?.takeUnless { it == NONE }, uri.getQueryParameter("alt"),
                uri.getQueryParameter("circle") == "1", name)
            "web" -> {
                val url = webUrls[segments.getOrNull(1) ?: ""] ?: throw FileNotFoundException(uri.toString())
                bundledPreset(context, url) ?: fetch(context, url)?.let { open(it) } ?: made(context, placeholder(context, name))
            }
            "number" -> made(context, number(context, segments.getOrNull(1)?.toIntOrNull() ?: 0))
            "glyph" -> made(context, glyph(context, segments.getOrNull(1) ?: "", uri.getQueryParameter("tab") == "1"))
            "word" -> made(context, word(context, uri.getQueryParameter("text") ?: "",
                runCatching { Color.parseColor(uri.getQueryParameter("color")) }.getOrDefault(0xFF6B5A44.toInt())))
            else -> throw FileNotFoundException(uri.toString())
        }
    }

    // ---- covers ----

    private fun cover(context: Context, id: String?, alt: String?, circle: Boolean, name: String?): ParcelFileDescriptor {
        // an artist's own photo only when the phone holds it (never asked of the computer, whose
        // artist pictures may come from places Cassette does not use); else their record's cover,
        // which stays square (the iPhone's artist header)
        val asked = id?.takeUnless { it.startsWith("ar-") }
        val own = id?.let { local(context, it) } ?: asked?.let { fetchCover(context, it) }
        val file = own ?: alt?.let { local(context, it) ?: fetchCover(context, it) }
            ?: return made(context, placeholder(context, name, circle))
        if (!circle || own == null) return open(file)
        val bitmap = decode(file) ?: return made(context, placeholder(context, name, true))
        return made(context, circled(bitmap))
    }

    private fun local(context: Context, id: String): File? =
        if (id.startsWith("ar-")) CassetteArtistArt.localFile(context, id) else CassetteAlbumArt.localFile(context, id)

    /** The computer's copy at a ladder rung, through the app's own checked door (Glide's hub client). */
    private fun fetchCover(context: Context, id: String): File? = fetch(context, CustomGlideRequest.createUrl(id, COVER_SIZE))

    /** Fetched into Glide's own disk cache (the file the phone's screens would load), or null. */
    private fun fetch(context: Context, url: String): File? = try {
        Glide.with(context).asFile().load(url).submit().get(FETCH_SECONDS, TimeUnit.SECONDS)?.takeIf { it.length() > 0 }
    } catch (e: Exception) {
        android.util.Log.w(TAG, "fetch failed: ${e.javaClass.simpleName}")
        null
    }

    /** A playlist preset ships with the app (playlist-covers/preset-07.png), as the phone draws it. */
    private fun bundledPreset(context: Context, url: String): ParcelFileDescriptor? {
        val m = PRESET.matcher(url)
        if (!m.find()) return null
        val file = File.createTempFile("preset", ".png", scratch(context))
        return try {
            context.assets.open("playlist-covers/${m.group(1)!!.lowercase()}.png").use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
            open(file)
        } catch (e: Exception) {
            null
        } finally {
            file.delete()
        }
    }

    private fun decode(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MADE_SIZE * 2) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    // ---- made for the car ----

    private fun circled(source: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(MADE_SIZE, MADE_SIZE, Bitmap.Config.ARGB_8888)
        val side = minOf(source.width, source.height).toFloat()
        val scale = MADE_SIZE / side
        val shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(Matrix().apply {
                setScale(scale, scale)
                postTranslate(-(source.width - side) / 2f * scale, -(source.height - side) / 2f * scale)
            })
        }
        Canvas(out).drawCircle(MADE_SIZE / 2f, MADE_SIZE / 2f, MADE_SIZE / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader })
        return out
    }

    /** No picture: the brand tile with the name's first letter, legible at car size (the iPhone's BUG-425). */
    private fun placeholder(context: Context, name: String?, circle: Boolean = false): Bitmap {
        val out = Bitmap.createBitmap(MADE_SIZE, MADE_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TILE }
        if (circle) canvas.drawCircle(MADE_SIZE / 2f, MADE_SIZE / 2f, MADE_SIZE / 2f, fill)
        else canvas.drawRect(0f, 0f, MADE_SIZE.toFloat(), MADE_SIZE.toFloat(), fill)
        val initial = name?.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: return out
        centered(canvas, initial, typeface(context, R.font.inter, Typeface.BOLD), MADE_SIZE * 0.42f, MARK)
        return out
    }

    /** An album's track number, in place of a cover (the iPhone's album page). */
    private fun number(context: Context, n: Int): Bitmap {
        val out = Bitmap.createBitmap(MADE_SIZE, MADE_SIZE, Bitmap.Config.ARGB_8888)
        centered(Canvas(out), if (n > 0) n.toString() else "·", typeface(context, R.font.inter, Typeface.NORMAL), MADE_SIZE * 0.34f, INK)
        return out
    }

    /** A Lucide glyph in the car's ink: small in a row (Play, Shuffle, All Songs), near full in a tab. */
    private fun glyph(context: Context, name: String, tab: Boolean): Bitmap {
        val res = GLYPHS[name] ?: throw FileNotFoundException(name)
        val drawable = ContextCompat.getDrawable(context, res)?.mutate() ?: throw FileNotFoundException(name)
        drawable.setTint(INK)
        val out = Bitmap.createBitmap(MADE_SIZE, MADE_SIZE, Bitmap.Config.ARGB_8888)
        val inset = (MADE_SIZE * if (tab) 0.06f else 0.27f).toInt()
        drawable.setBounds(inset, inset, MADE_SIZE - inset, MADE_SIZE - inset)
        drawable.draw(Canvas(out))
        return out
    }

    /** A station with no logo: its name on its own colour, as its tile on the phone (Barlow, cream). */
    private fun word(context: Context, text: String, color: Int): Bitmap {
        val out = Bitmap.createBitmap(MADE_SIZE, MADE_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(color)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = CREAM
            typeface = typeface(context, R.font.barlow_condensed, Typeface.BOLD)
        }
        val width = (MADE_SIZE * 0.82f).toInt()
        var size = MADE_SIZE * 0.24f
        var layout: StaticLayout
        do {
            paint.textSize = size
            layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(3).build()
            size *= 0.9f
        } while ((layout.height > MADE_SIZE * 0.8f || (0 until layout.lineCount).any { layout.getLineWidth(it) > width }) && size > 12f)
        canvas.save()
        canvas.translate((MADE_SIZE - width) / 2f, (MADE_SIZE - layout.height) / 2f)
        layout.draw(canvas)
        canvas.restore()
        return out
    }

    private fun centered(canvas: Canvas, text: String, face: Typeface, size: Float, color: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = size
            typeface = face
            textAlign = Paint.Align.CENTER
        }
        val y = MADE_SIZE / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(text, MADE_SIZE / 2f, y, paint)
    }

    private fun typeface(context: Context, font: Int, style: Int): Typeface =
        Typeface.create(runCatching { ResourcesCompat.getFont(context, font) }.getOrNull() ?: Typeface.DEFAULT, style)

    // ---- handing a file over ----

    private fun open(file: File): ParcelFileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)

    /** A made picture: written, opened, and the file deleted at once (the open handle keeps it readable). */
    private fun made(context: Context, bitmap: Bitmap): ParcelFileDescriptor {
        val file = File.createTempFile("car", ".png", scratch(context))
        try {
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            return open(file)
        } finally {
            file.delete()
        }
    }

    private fun scratch(context: Context): File = File(context.cacheDir, "car_art").apply { mkdirs() }

    companion object {
        private const val TAG = "CassetteCarArt"
        private const val MADE_SIZE = 256
        private const val COVER_SIZE = 480
        private const val FETCH_SECONDS = 6L
        private const val MAX_WEB = 4000

        // the car is dark: the kit's night tile and its quiet letter (the iPhone's placeholderMark)
        private const val TILE = 0xFF2A261E.toInt()
        private const val MARK = 0xA6A89878.toInt()
        private const val INK = Color.WHITE
        private const val CREAM = 0xFFF3EADB.toInt()

        private val PRESET = java.util.regex.Pattern.compile("playlist-covers/(preset-\\d+)\\.(?:svg|png)", java.util.regex.Pattern.CASE_INSENSITIVE)

        private val GLYPHS = mapOf(
            "play" to R.drawable.ic_lucide_play,
            "shuffle" to R.drawable.ic_lucide_shuffle,
            "songs" to R.drawable.ic_lucide_music,
            "home" to R.drawable.ic_lucide_house,
            "albums" to R.drawable.ic_lucide_disc,
            "artists" to R.drawable.ic_lucide_user,
            "playlists" to R.drawable.ic_lucide_list_music,
        )

        private val webUrls = ConcurrentHashMap<String, String>()

        private fun base(): Uri.Builder = Uri.Builder().scheme("content")
            .authority(com.cappielloantonio.tempo.App.getContext().packageName + ".carart")

        /**
         * A record's (or song's) cover. [circle] rounds [coverArtId] (an artist's photo); [alt] is
         * tried when the phone has none of it, and stays square (their record's cover).
         */
        @JvmStatic
        @JvmOverloads
        fun cover(coverArtId: String?, name: String?, circle: Boolean = false, alt: String? = null): Uri {
            val primary = coverArtId?.takeIf { it.isNotEmpty() }
            val second = alt?.takeIf { it.isNotEmpty() && it != primary }
            if (primary == null) return if (second == null) placeholderUri(name, circle) else cover(second, name)
            return base().appendPath("cover").appendPath(primary).apply {
                if (circle) appendQueryParameter("circle", "1")
                if (second != null) appendQueryParameter("alt", second)
                if (!name.isNullOrEmpty()) appendQueryParameter("name", name)
            }.build()
        }

        private const val NONE = "none"

        private fun placeholderUri(name: String?, circle: Boolean): Uri =
            base().appendPath("cover").appendPath(NONE).apply {
                if (circle) appendQueryParameter("circle", "1")
                if (!name.isNullOrEmpty()) appendQueryParameter("name", name)
            }.build()

        /** A picture from a web address (a drawn mix cover, a playlist cover, a station logo). */
        @JvmStatic
        fun web(url: String?, name: String?): Uri? {
            if (url.isNullOrEmpty()) return placeholderUri(name, false)
            val absolute = if (url.startsWith("/")) "https://cassette.digital$url" else url
            if (!absolute.startsWith("https://") && !absolute.startsWith("http://")) return cover(absolute, name)
            val key = MessageDigest.getInstance("SHA-1").digest(absolute.toByteArray())
                .joinToString("") { "%02x".format(it) }.take(24)
            if (webUrls.size > MAX_WEB) webUrls.clear()
            webUrls[key] = absolute
            return base().appendPath("web").appendPath(key).apply {
                if (!name.isNullOrEmpty()) appendQueryParameter("name", name)
            }.build()
        }

        @JvmStatic
        fun number(n: Int): Uri = base().appendPath("number").appendPath(n.toString()).build()

        @JvmStatic
        @JvmOverloads
        fun glyph(name: String, tab: Boolean = false): Uri = base().appendPath("glyph").appendPath(name).apply {
            if (tab) appendQueryParameter("tab", "1")
        }.build()

        @JvmStatic
        fun word(text: String, color: Int): Uri = base().appendPath("word")
            .appendQueryParameter("text", text)
            .appendQueryParameter("color", String.format("#%06X", 0xFFFFFF and color)).build()
    }
}
