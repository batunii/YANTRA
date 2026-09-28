package ie.shoonya.yantra

import android.util.Base64
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ie.shoonya.yantra.data.ink.ShapeKind
import ie.shoonya.yantra.data.ink.StrokeCodec
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * **The ink file is the contract between the two apps, so the real ink library writes the fixtures.**
 *
 * Nothing in the JVM test suite can call androidx.ink — its codec is native — so the golden `.ink`
 * blobs the iOS codec is checked against are made here, on a device, by [StrokeCodec] exactly as the
 * app makes them. Each blob is written beside what the library reads back out of it: the point
 * stream iOS must decode to.
 *
 * The other direction runs here too. Blobs the iOS encoder produced arrive as the `iosInk`
 * instrumentation argument (`name:base64,…`); each must decode through [StrokeCodec.decode] — the
 * very call the canvas makes, which drops anything that fails — and what Android reads from it is
 * written out for the iOS side to compare with what it meant to write.
 *
 * Test-only: the app itself is untouched. Output lands in the app's external files directory,
 * under `ink-interop/`, for `adb pull`.
 */
@RunWith(AndroidJUnit4::class)
class InkInteropFixturesTest {

    private val out: File by lazy {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        File(ctx.getExternalFilesDir(null), "ink-interop").apply { mkdirs() }
    }

    @Test
    fun theLibraryWritesTheGoldenStrokes() {
        val fixtures = linkedMapOf(
            // A pen stroke as a stylus reports it: every channel present, a unit length set.
            "stylus-pen" to stroke(StrokeCodec.FAMILY_PRESSURE_PEN, 0xFFF1EEE7L, 2.6f, InputToolType.STYLUS) { i ->
                Input(
                    x = 120f + i * 3.7f, y = 300f + 40f * kotlin.math.sin(i / 6f), t = i * 8L,
                    cm = 0.014775f, pressure = 0.2f + (i % 10) / 20f, tilt = 0.3f + i / 400f,
                    orientation = (1.1f + i / 50f) % (2f * Math.PI.toFloat()),
                )
            },
            // A finger: position and time only.
            "touch-marker" to stroke(StrokeCodec.FAMILY_MARKER, 0xFF3A6EA5L, 5.5f, InputToolType.TOUCH) { i ->
                Input(x = 800f - i * 11f, y = 1300f + i * 2.5f, t = i * 16L)
            },
            // Highlighters store the forced alpha, whatever swatch was picked.
            "highlighter" to stroke(StrokeCodec.FAMILY_HIGHLIGHTER, 0xFFE0A83EL, 9f, InputToolType.STYLUS) { i ->
                Input(x = 50f + i * 20f, y = 2000f, t = i * 10L, cm = 0.014775f, pressure = 0.5f)
            },
            // A recognised shape, synthesised by the shape tool: tool unknown, 6 ms apart.
            "shape-rectangle" to Stroke(
                StrokeCodec.brush(StrokeCodec.FAMILY_PRESSURE_PEN, 0xFF23211CL, 2.6f),
                StrokeCodec.shapeInputs(ShapeKind.RECTANGLE, 100f, 100f, 400f, 250f),
            ).let { it to StrokeCodec.FAMILY_PRESSURE_PEN },
            // One point: a dot. Zero width and height are their own branch in the encoder.
            "dot" to stroke(StrokeCodec.FAMILY_PRESSURE_PEN, 0xFF23211CL, 2.6f, InputToolType.STYLUS, count = 1) {
                Input(x = 500f, y = 707.1f, t = 0L, cm = 0.014775f, pressure = 0.7f)
            },
        )
        val index = JSONArray()
        for ((name, pair) in fixtures) {
            val (stroke, family) = pair
            val blob = StrokeCodec.encode(stroke, family)
            File(out, "$name.blob").writeBytes(blob)
            File(out, "$name.json").writeText(describe(StrokeCodec.decode(blob)).toString(1))
            index.put(name)
        }
        File(out, "android-index.json").writeText(index.toString())
    }

    @Test
    fun whatIosWroteDecodesHere() {
        val arg = InstrumentationRegistry.getArguments().getString("iosInk") ?: return
        val decoded = JSONObject()
        for (entry in arg.split(',').filter { it.isNotBlank() }) {
            val name = entry.substringBefore(':')
            val blob = Base64.decode(entry.substringAfter(':'), Base64.DEFAULT)
            val stroke = StrokeCodec.decodeOrNull(blob)
            if (stroke == null) {
                decoded.put(name, JSONObject.NULL)
                continue
            }
            decoded.put(name, describe(stroke))
            // Android's own re-encoding of the same stroke, so the protobufs can be compared.
            val family = JSONObject(String(blob, 4, readInt(blob), Charsets.UTF_8)).getString("family")
            File(out, "ios-$name.android.blob").writeBytes(StrokeCodec.encode(stroke, family))
        }
        File(out, "ios-decoded.json").writeText(decoded.toString(1))
        val failed = decoded.keys().asSequence().filter { decoded.isNull(it) }.toList()
        assertTrue("Android could not decode the iOS strokes $failed", failed.isEmpty())
    }

    private data class Input(
        val x: Float, val y: Float, val t: Long,
        val cm: Float = StrokeInput.NO_STROKE_UNIT_LENGTH,
        val pressure: Float = StrokeInput.NO_PRESSURE,
        val tilt: Float = StrokeInput.NO_TILT,
        val orientation: Float = StrokeInput.NO_ORIENTATION,
    )

    private fun stroke(
        family: String, color: Long, size: Float, tool: InputToolType, count: Int = 48,
        input: (Int) -> Input,
    ): Pair<Stroke, String> {
        val batch = MutableStrokeInputBatch()
        for (i in 0 until count) {
            val p = input(i)
            batch.add(tool, p.x, p.y, p.t, p.cm, p.pressure, p.tilt, p.orientation)
        }
        return Stroke(StrokeCodec.brush(family, color, size), batch.toImmutable()) to family
    }

    private fun describe(stroke: Stroke): JSONObject {
        val inputs = stroke.inputs
        val p = StrokeInput()
        val x = JSONArray(); val y = JSONArray(); val t = JSONArray()
        val pressure = JSONArray(); val tilt = JSONArray(); val orientation = JSONArray()
        for (i in 0 until inputs.size) {
            inputs.populate(i, p)
            x.put(p.x.toDouble()); y.put(p.y.toDouble()); t.put(p.elapsedTimeMillis)
            pressure.put(p.pressure.toDouble()); tilt.put(p.tiltRadians.toDouble())
            orientation.put(p.orientationRadians.toDouble())
        }
        if (inputs.size > 0) inputs.populate(0, p)
        return JSONObject()
            .put("colorArgb", stroke.brush.colorIntArgb.toLong() and 0xFFFFFFFFL)
            .put("size", stroke.brush.size.toDouble())
            .put("epsilon", stroke.brush.epsilon.toDouble())
            .put("toolType", p.toolType.toString())
            .put("strokeUnitLengthCm", p.strokeUnitLengthCm.toDouble())
            .put("hasPressure", inputs.hasPressure())
            .put("hasTilt", inputs.hasTilt())
            .put("hasOrientation", inputs.hasOrientation())
            .put("x", x).put("y", y).put("tMillis", t)
            .put("pressure", pressure).put("tilt", tilt).put("orientation", orientation)
    }

    private fun readInt(b: ByteArray): Int =
        (b[0].toInt() and 0xFF shl 24) or (b[1].toInt() and 0xFF shl 16) or
            (b[2].toInt() and 0xFF shl 8) or (b[3].toInt() and 0xFF)
}
