package app.lares.contract.entities

import app.lares.contract.serialization.LenientEnumSerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KeepGeneratedSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The fixed color palette of tags and persons (ADR-0016, ADR-0031): persons use the first ten
 * ([PaletteColors.personColors]), tags all of them. Hex values live in the client theme.
 * Unknown colors from a newer version decode to [GRAY].
 */
@OptIn(ExperimentalSerializationApi::class)
@KeepGeneratedSerializer
@Serializable(with = PaletteColorSerializer::class)
enum class PaletteColor {
    @SerialName("red")
    RED,

    @SerialName("orange")
    ORANGE,

    @SerialName("yellow")
    YELLOW,

    @SerialName("green")
    GREEN,

    @SerialName("teal")
    TEAL,

    @SerialName("blue")
    BLUE,

    @SerialName("purple")
    PURPLE,

    @SerialName("pink")
    PINK,

    @SerialName("brown")
    BROWN,

    @SerialName("gray")
    GRAY,

    // Tags only (ADR-0031).
    @SerialName("rose")
    ROSE,

    @SerialName("olive")
    OLIVE,

    @SerialName("cyan")
    CYAN,

    @SerialName("indigo")
    INDIGO,

    @SerialName("magenta")
    MAGENTA,

    @SerialName("slate")
    SLATE,
    ;

    /** The wire name, e.g. `blue`; also stored in the database. */
    val key: String get() = PaletteColorSerializer.serialName(this)

    companion object {
        /** The color with this wire name, or `null` if this version does not know it. */
        fun fromKey(key: String): PaletteColor? = PaletteColorSerializer.decodeOrNull(key)
    }
}

@OptIn(ExperimentalSerializationApi::class)
internal object PaletteColorSerializer :
    LenientEnumSerializer<PaletteColor>(PaletteColor.generatedSerializer(), PaletteColor.entries, PaletteColor.GRAY)

/** Personal color rules (FND-034), shared by server and client. */
object PaletteColors {
    /** The ten colors a person can have, in palette order. */
    val personColors: List<PaletteColor> = PaletteColor.entries.take(10)

    /** The colors a tag can have, around the color wheel, then the muted ones. */
    val tagColors: List<PaletteColor> =
        listOf(
            PaletteColor.RED,
            PaletteColor.ORANGE,
            PaletteColor.YELLOW,
            PaletteColor.OLIVE,
            PaletteColor.GREEN,
            PaletteColor.TEAL,
            PaletteColor.CYAN,
            PaletteColor.BLUE,
            PaletteColor.INDIGO,
            PaletteColor.PURPLE,
            PaletteColor.MAGENTA,
            PaletteColor.PINK,
            PaletteColor.ROSE,
            PaletteColor.BROWN,
            PaletteColor.SLATE,
            PaletteColor.GRAY,
        )

    private val nonGray: List<PaletteColor> = personColors - PaletteColor.GRAY

    /**
     * The color for a new person: the one used least among [used], ties by palette order.
     * Gray is picked only when every other color is used more often than gray.
     */
    fun pickLeastUsed(used: Collection<PaletteColor>): PaletteColor {
        val counts = used.groupingBy { it }.eachCount()
        val best = nonGray.minBy { counts[it] ?: 0 }
        val grayCount = counts[PaletteColor.GRAY] ?: 0
        return if (grayCount < (counts[best] ?: 0)) PaletteColor.GRAY else best
    }

    /** A stable color for a person whose color is not set (persons created before FND-034). */
    fun fallbackFor(personId: String): PaletteColor {
        // A fixed hash, not String.hashCode, so every platform picks the same color.
        var hash = 0
        for (char in personId.lowercase()) hash = hash * 31 + char.code
        return nonGray[(hash % nonGray.size + nonGray.size) % nonGray.size]
    }
}
