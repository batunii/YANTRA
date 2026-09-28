package oracle

import ie.shoonya.yantra.data.filter.*
import ie.shoonya.yantra.data.format.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

// Verbatim copies of the 17d0beb metadata classes (WorkspaceStore.kt, Repos.kt).
@Serializable
data class Manifest(
    val formatVersion: Int = 2,
    val name: String,
    val createdAt: Long,
    val epoch: Int = 1,
    @SerialName("archive_after_days") val archiveAfterDays: Int = 0,
)
@Serializable data class LabelDef(val id: String, val name: String, val color: Long? = null)
@Serializable data class PropertyDef(val id: String, val name: String, val kind: String, val config: String? = null)
@Serializable
data class SmartListDef(
    val nodeId: String,
    val scopeRootId: String? = null,
    val filterJson: String,
    val sortJson: String? = null,
    val homeParentId: String? = null,
    val applyOnCreateJson: String? = null,
)
@Serializable data class SelectOption(val name: String, val color: Long? = null)
@Serializable data class SelectConfig(val options: List<SelectOption> = emptyList())

@Serializable data class LineCase(val source: String, val kind: String, val rendered: String, val passthrough: String)
@Serializable data class JsonCase(val name: String, val json: String)

fun stripRaw(b: Block): Block = when (b) {
    is Prose -> b.copy(raw = null)
    is Heading -> b.copy(raw = null)
    is Bullet -> b.copy(raw = null)
    is Numbered -> b.copy(raw = null)
    is TaskRef -> b.copy(raw = null)
    is InkRef -> b.copy(raw = null)
    is ImageRef -> b.copy(raw = null)
    is EventRef -> b.copy(raw = null)
}

fun builtInProperties(): List<PropertyDef> = listOf(
    PropertyDef("builtin-priority", "Priority", "select",
        config = FilterJson.encodeToString(SelectConfig.serializer(), SelectConfig(listOf(
            SelectOption("High", 0xFFFF4A1F), SelectOption("Medium", 0xFFFFB020), SelectOption("Low", 0xFF4A90D9))))),
    PropertyDef("builtin-due", "Due", "date"),
    PropertyDef("builtin-deadline", "Deadline", "date"),
    PropertyDef("builtin-assignee", "Assignee", "text"),
)

fun main(args: Array<String>) {
    when (args[0]) {
        // lines <in.json: array of strings> <out.json>
        "lines" -> {
            val sources = FilterJson.decodeFromString(ListSerializer(String.serializer()), File(args[1]).readText())
            val cases = sources.map { src ->
                val b = PageCodec.decodeBlock(src)
                LineCase(src, b::class.simpleName.orEmpty(), PageCodec.encodeBlock(stripRaw(b)), PageCodec.encodeBlock(b))
            }
            File(args[2]).writeText(kotlinx.serialization.json.Json { prettyPrint = true }.encodeToString(ListSerializer(LineCase.serializer()), cases))
        }
        // roundtrip <in dir of .md> <out dir>
        "roundtrip" -> {
            val out = File(args[2]); out.mkdirs()
            File(args[1]).listFiles { f -> f.name.endsWith(".md") }!!.sortedBy { it.name }.forEach { f ->
                File(out, f.name).writeText(PageCodec.encode(PageCodec.decode(f.readText())))
            }
        }
        // workspace <clone> <out>: what 17d0beb makes of every page and registry in a clone, each
        // written to <out> under its own path — the other half of RoundTripRunnerTests' output.
        "workspace" -> {
            val root = File(args[1]); val out = File(args[2])
            fun put(rel: String, text: String) { File(out, rel).also { it.parentFile.mkdirs() }.writeText(text) }
            listOf("pages", "archive/pages").forEach { dir ->
                File(root, dir).listFiles { f -> f.isFile && f.name.endsWith(".md") }.orEmpty().forEach {
                    put("$dir/${it.name}", PageCodec.encode(PageCodec.decode(it.readText())))
                }
            }
            File(root, ".yantra/manifest.json").takeIf { it.exists() }?.let {
                put(".yantra/manifest.json", FilterJson.encodeToString(Manifest.serializer(), FilterJson.decodeFromString(Manifest.serializer(), it.readText())))
            }
            File(root, ".yantra/meta/labels.json").takeIf { it.exists() }?.let {
                val l = ListSerializer(LabelDef.serializer())
                put(".yantra/meta/labels.json", FilterJson.encodeToString(l, FilterJson.decodeFromString(l, it.readText())))
            }
            File(root, ".yantra/meta/properties.json").takeIf { it.exists() }?.let {
                val l = ListSerializer(PropertyDef.serializer())
                put(".yantra/meta/properties.json", FilterJson.encodeToString(l, FilterJson.decodeFromString(l, it.readText())))
            }
            File(root, ".yantra/meta/smartlists").listFiles { f -> f.name.endsWith(".json") }.orEmpty().forEach {
                put(".yantra/meta/smartlists/${it.name}",
                    FilterJson.encodeToString(SmartListDef.serializer(), FilterJson.decodeFromString(SmartListDef.serializer(), it.readText())))
            }
        }
        // one page from a file, re-encoded, to stdout
        "page" -> print(PageCodec.encode(PageCodec.decode(File(args[1]).readText())))
        "json" -> {
            val todayFilter = Filter.All(listOf(Filter.Type("task"), Filter.Done(false), Filter.AnyOf(listOf(
                Filter.Prop(defId = "builtin-due", op = Op.LTE, dateRel = DateRel.TODAY_END),
                Filter.Prop(defId = "builtin-deadline", op = Op.LTE, dateRel = DateRel.TODAY_END)))))
            val cases = listOf(
                JsonCase("properties.json", FilterJson.encodeToString(ListSerializer(PropertyDef.serializer()), builtInProperties())),
                JsonCase("labels-empty.json", FilterJson.encodeToString(ListSerializer(LabelDef.serializer()), emptyList())),
                JsonCase("labels.json", FilterJson.encodeToString(ListSerializer(LabelDef.serializer()), listOf(
                    LabelDef(":label:sync", "sync", 0xFF5D8F52), LabelDef("ws:label:bare", "bare"),
                    LabelDef("w:label:e", "\u001b\u007f\t\n\r\b\u000c é /   😀 \\ \"", 0),
                    LabelDef("w:label:q", "quo\"te\\ \u0001 é /  ", 4294967295)))),
                JsonCase("manifest-new.json", FilterJson.encodeToString(Manifest.serializer(), Manifest(name = "v2-tasks", createdAt = 1788177735338))),
                JsonCase("manifest-upgraded.json", FilterJson.encodeToString(Manifest.serializer(),
                    FilterJson.decodeFromString(Manifest.serializer(), """{"formatVersion":1,"name":"old","createdAt":5}""").copy(formatVersion = 2))),
                JsonCase("manifest-full.json", FilterJson.encodeToString(Manifest.serializer(), Manifest(formatVersion = 3, name = "x", createdAt = 1, epoch = 4, archiveAfterDays = 30))),
                JsonCase("manifest-missing-version-reads-as", FilterJson.decodeFromString(Manifest.serializer(), """{"name":"a","createdAt":1}""").formatVersion.toString()),
                JsonCase("today.json", FilterJson.encodeToString(SmartListDef.serializer(), SmartListDef(
                    nodeId = "today-id",
                    filterJson = FilterJson.encodeToString(Filter.serializer(), todayFilter),
                    sortJson = FilterJson.encodeToString(ListSerializer(SortSpec.serializer()), listOf(SortSpec(by = SortBy.PROP_DATE, defId = "builtin-due"))),
                    homeParentId = "inbox-id",
                    applyOnCreateJson = deriveApplyOnCreate(todayFilter).takeIf { it.isNotEmpty() }
                        ?.let { FilterJson.encodeToString(ListSerializer(ApplyOnCreate.serializer()), it) }))),
                JsonCase("high.json", FilterJson.encodeToString(SmartListDef.serializer(), SmartListDef(
                    nodeId = "high-id",
                    filterJson = FilterJson.encodeToString(Filter.serializer(), Filter.All(listOf(Filter.Type("task"), Filter.Done(false),
                        Filter.Prop(defId = "builtin-priority", op = Op.EQ, text = "High")))),
                    sortJson = FilterJson.encodeToString(ListSerializer(SortSpec.serializer()), listOf(SortSpec(by = SortBy.CREATED, desc = true))),
                    homeParentId = "inbox-id",
                    applyOnCreateJson = FilterJson.encodeToString(ListSerializer(ApplyOnCreate.serializer()),
                        listOf(ApplyOnCreate(defId = "builtin-priority", text = "High")))))),
                JsonCase("filter-every-kind.json", FilterJson.encodeToString(Filter.serializer(), Filter.All(listOf(
                    Filter.AnyOf(listOf(Filter.Not(Filter.InProgress(true)), Filter.HasLabel(":label:x"), Filter.InWorkspace("w1"))),
                    Filter.Prop(defId = "d", op = Op.GTE, number = 5.0),
                    Filter.Prop(defId = "d", op = Op.LT, number = 0.5),
                    Filter.Prop(defId = "d", op = Op.LT, number = 1.0E7),
                    Filter.Prop(defId = "d", op = Op.LT, number = 1.25E-4),
                    Filter.Prop(defId = "d", op = Op.NEQ, number = -3.0),
                    Filter.Prop(defId = "d", op = Op.EQ, date = 1788177735338),
                    Filter.Prop(defId = "d", op = Op.EQ, bool = true),
                    Filter.Prop(defId = "d", op = Op.IS_SET),
                    Filter.Prop(defId = "d", op = Op.NOT_SET, text = "t"),
                )))),
                JsonCase("sort-every-kind.json", FilterJson.encodeToString(ListSerializer(SortSpec.serializer()), listOf(
                    SortSpec(SortBy.PROP_NUMBER, "n", desc = true, nullsLast = false), SortSpec(SortBy.PROP_TEXT, "t"), SortSpec(SortBy.TITLE)))),
                JsonCase("apply-every-kind.json", FilterJson.encodeToString(ListSerializer(ApplyOnCreate.serializer()), listOf(
                    ApplyOnCreate("a", number = 2.0), ApplyOnCreate("b", date = 7), ApplyOnCreate("c", bool = false, dateRel = DateRel.TODAY_START)))),
                JsonCase("select-empty.json", FilterJson.encodeToString(SelectConfig.serializer(), SelectConfig())),
                JsonCase("select-bare-option.json", FilterJson.encodeToString(SelectConfig.serializer(), SelectConfig(listOf(SelectOption("Just a word"))))),
                JsonCase("smartlist-scoped.json", FilterJson.encodeToString(SmartListDef.serializer(),
                    SmartListDef(nodeId = "n", scopeRootId = "r", filterJson = "{\"kind\":\"done\",\"value\":true}"))),
                JsonCase("numbers.json", FilterJson.encodeToString(ListSerializer(ApplyOnCreate.serializer()), listOf(
                    0.0, -0.0, 1.0, 0.1, 0.001, 0.0009999, 9999999.0, 10000000.0, 123456789.125, 1.0E21, 1.0E-7, 2.5E-10,
                    // Not Double.MIN_VALUE: for a subnormal whose shortest form is one digit, the JVM
                    // picks the closest *two*-digit decimal (4.9E-324, not 5.0E-324). No smart-list
                    // number is ever that small, and the Swift side does not reproduce it.
                    Double.MAX_VALUE, 1.0 / 3.0, 100.0, 12345.678,
                ).map { ApplyOnCreate("n", number = it) })),
            )
            File(args[1]).writeText(kotlinx.serialization.json.Json { prettyPrint = true }.encodeToString(ListSerializer(JsonCase.serializer()), cases))
        }
    }
}
