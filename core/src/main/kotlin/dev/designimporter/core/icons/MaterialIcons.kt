package dev.designimporter.core.icons

import com.google.gson.JsonParser
import dev.designimporter.core.Names
import dev.designimporter.core.svg.IconHash
import dev.designimporter.core.svg.PathData
import dev.designimporter.core.svg.VectorDoc

data class MaterialIcon(val style: String, val name: String, val extended: Boolean, val hash: String) {
    /** e.g. "Icons.AutoMirrored.Filled.ArrowBack" */
    val expression get() = "Icons.$style.$name"
    val importPath get() = "androidx.compose.material.icons.${style.lowercase()}.$name"
    val ref get() = "$style.$name"
}

class MaterialIconIndex(val version: String, val icons: List<MaterialIcon>) {
    val byHash: Map<String, List<MaterialIcon>> = icons.groupBy { it.hash }
    val byName: Map<String, List<MaterialIcon>> = icons.groupBy { it.name.lowercase() }

    companion object {
        const val RESOURCE = "/dev/designimporter/core/material-icons-index.json"

        val shared: MaterialIconIndex by lazy { load() }

        fun load(): MaterialIconIndex {
            val stream = MaterialIconIndex::class.java.getResourceAsStream(RESOURCE) ?: return MaterialIconIndex("none", emptyList())
            val root = stream.bufferedReader().use { JsonParser.parseReader(it).asJsonObject }
            val icons = root.getAsJsonArray("icons").map { e ->
                val a = e.asJsonArray
                MaterialIcon(a[0].asString, a[1].asString, a[2].asString == "extended", a[3].asString)
            }
            return MaterialIconIndex(root["version"].asString, icons)
        }
    }
}

enum class MatchMode { OFF, GEOMETRY, GEOMETRY_AND_NAME }
enum class MatchKind { GEOMETRY, NAME, SYMBOL }

data class IconMatch(val icon: MaterialIcon, val kind: MatchKind)

class MaterialIconMatcher(
    private val index: MaterialIconIndex,
    private val mode: MatchMode,
    private val defaultStyle: String = "Filled",
    /** When set, replaces the style inferred from the export's icon font for Material Symbols. */
    private val symbolStyleOverride: String? = null,
) {
    /** A Material Symbol used by name can only be the Material icon of exactly that name (no synonyms). */
    fun matchSymbol(symbol: String, styleHint: String?): IconMatch? {
        if (mode == MatchMode.OFF) return null
        val candidates = index.byName[symbol.replace("_", "").lowercase()] ?: return null
        return IconMatch(best(candidates, symbolStyleOverride ?: styleHint), MatchKind.SYMBOL)
    }

    fun match(iconName: String, doc: VectorDoc?): IconMatch? {
        if (mode == MatchMode.OFF) return null
        val (key, hintedStyle) = parseName(iconName)
        if (doc != null) {
            geometryHash(doc)?.let { hash ->
                index.byHash[hash]?.let { return IconMatch(best(it, hintedStyle), MatchKind.GEOMETRY) }
            }
        }
        if (mode == MatchMode.GEOMETRY_AND_NAME) {
            // Synonyms first: e.g. Material's ArrowLeft is a triangle, while "arrow-left" in Lucide means back.
            val candidates = synonyms[key]?.let { index.byName[it.lowercase()] } ?: index.byName[key]
            if (!candidates.isNullOrEmpty()) return IconMatch(best(candidates, hintedStyle), MatchKind.NAME)
        }
        return null
    }

    private fun best(candidates: List<MaterialIcon>, hintedStyle: String?): MaterialIcon {
        fun baseStyle(i: MaterialIcon) = i.style.removePrefix("AutoMirrored.")
        return candidates.sortedWith(
            compareByDescending<MaterialIcon> { hintedStyle != null && baseStyle(it) == hintedStyle }
                .thenByDescending { hintedStyle == null && baseStyle(it) == defaultStyle }
                .thenBy { it.extended }
                .thenByDescending { baseStyle(it) == "Filled" }
                .thenByDescending { it.style.startsWith("AutoMirrored") }
        ).first()
    }

    companion object {
        private val styleWords = mapOf(
            "filled" to "Filled", "fill" to "Filled", "outlined" to "Outlined", "outline" to "Outlined",
            "rounded" to "Rounded", "round" to "Rounded", "sharp" to "Sharp", "twotone" to "TwoTone",
        )
        private val prefixWords = setOf("ic", "icon", "icons", "material", "md", "mi", "baseline")

        /** Common Lucide/Feather/Heroicons names → Material Icons names. */
        private val synonyms = mapOf(
            "x" to "Close", "xmark" to "Close", "user" to "Person", "users" to "Group", "bell" to "Notifications",
            "trash" to "Delete", "trash2" to "Delete", "pencil" to "Edit", "plus" to "Add", "minus" to "Remove",
            "eye" to "Visibility", "eyeoff" to "VisibilityOff", "eyeslash" to "VisibilityOff", "heart" to "Favorite",
            "arrowleft" to "ArrowBack", "arrowright" to "ArrowForward", "arrowup" to "ArrowUpward", "arrowdown" to "ArrowDownward",
            "morehorizontal" to "MoreHoriz", "ellipsis" to "MoreHoriz", "morevertical" to "MoreVert", "ellipsisvertical" to "MoreVert",
            "copy" to "ContentCopy", "filter" to "FilterList", "calendar" to "CalendarToday", "clock" to "Schedule",
            "logout" to "Logout", "creditcard" to "CreditCard", "wallet" to "AccountBalanceWallet",
            "alertcircle" to "Error", "circlealert" to "Error", "alerttriangle" to "Warning", "trianglealert" to "Warning",
            "helpcircle" to "Help", "circlehelp" to "Help", "camera" to "CameraAlt", "mappin" to "Place", "globe" to "Public",
            "refreshcw" to "Refresh", "rotatecw" to "Refresh", "externallink" to "OpenInNew", "shoppingcart" to "ShoppingCart",
            "cart" to "ShoppingCart", "shoppingbag" to "ShoppingBag", "bag" to "ShoppingBag", "gift" to "CardGiftcard",
            "sun" to "LightMode", "moon" to "DarkMode", "sliders" to "Tune", "slidershorizontal" to "Tune", "grid" to "GridView",
            "layoutgrid" to "GridView", "tag" to "LocalOffer", "qrcode" to "QrCode", "scan" to "QrCodeScanner",
            "dollarsign" to "AttachMoney", "dollar" to "AttachMoney", "house" to "Home", "gear" to "Settings", "cog" to "Settings",
            "cog6tooth" to "Settings", "magnifyingglass" to "Search", "arrowupright" to "NorthEast", "piechart" to "PieChart",
            "barchart" to "BarChart", "barchart3" to "BarChart", "chartbar" to "BarChart", "mail" to "Email", "envelope" to "Email",
            "messagecircle" to "ChatBubbleOutline", "message" to "Chat", "messagesquare" to "Chat", "send" to "Send",
            "checkcircle" to "CheckCircle", "circlecheck" to "CheckCircle", "xcircle" to "Cancel", "circlex" to "Cancel",
            "lockkeyhole" to "Lock", "unlock" to "LockOpen", "key" to "Key", "image" to "Image", "file" to "InsertDriveFile",
            "filetext" to "Description", "folder" to "Folder", "bookmark" to "Bookmark", "share2" to "Share", "trendingup" to "TrendingUp",
            "trendingdown" to "TrendingDown", "zap" to "Bolt", "mic" to "Mic", "video" to "Videocam", "phone" to "Phone",
        )

        fun parseName(raw: String): Pair<String, String?> {
            val w = Names.words(raw).toMutableList()
            while (w.size > 1 && w.first() in prefixWords) w.removeAt(0)
            var style: String? = null
            if (w.size > 2 && w[w.size - 2] == "two" && w.last() == "tone") {
                style = "TwoTone"; w.removeAt(w.size - 1); w.removeAt(w.size - 1)
            } else if (w.size > 1 && w.last() in styleWords) {
                style = styleWords[w.removeAt(w.size - 1)]
            } else if (w.size > 1 && w.first() in styleWords) {
                style = styleWords[w.removeAt(0)]
            }
            return w.joinToString("") to style
        }

        /** Only filled, untransformed geometry can be a Material icon. */
        fun geometryHash(doc: VectorDoc): String? {
            if (doc.hasTransforms()) return null
            val paths = doc.allPaths()
            if (paths.isEmpty() || paths.any { it.fill == null || it.stroke != null }) return null
            return IconHash.of(paths.map { PathData.parse(it.d) }, doc.viewportWidth)
        }
    }
}
