package io.github.mangi.flymefreeform.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PinnedTargetCodecTest {
    @Test
    fun mixedTargetsRoundTripWithoutLosingOrderOrAliasSeparators() {
        val targets = listOf(
            PinnedTarget.App("com.example", "com.example.Main"),
            PinnedTarget.Sidebar(PinnedTarget.Kind.Tool, "capture", 0),
            PinnedTarget.Sidebar(PinnedTarget.Kind.Shortcut, "com.example_记事|a/b#999", 10),
            PinnedTarget.Sidebar(PinnedTarget.Kind.AppEntry, "com.example#com.example.Note", 0),
            PinnedTarget.Sidebar(PinnedTarget.Kind.CloneApp, "com.example#com.example.Main#999", 0),
        )
        assertEquals(targets, PinnedTargetCodec.decodeList(PinnedTargetCodec.encodeList(targets)))
    }

    @Test
    fun legacyMigrationNormalizesEquivalentComponentsAndPreservesAllItemsInOrder() {
        val raw = "com.example/.Main\ninvalid\ncom.example/com.example.Main\n" +
            (1..7).joinToString("\n") { "com.app$it/.Main" }
        val targets = PinnedTargetCodec.decodeLegacy(raw)
        assertEquals(8, targets.size)
        assertEquals(PinnedTarget.App("com.example", "com.example.Main"), targets.first())
        assertEquals(PinnedTarget.App("com.app7", "com.app7.Main"), targets.last())
    }

    @Test
    fun largeMultiRingSidebarListRoundTripsBeyondOldTextLimit() {
        val targets = (1..100).map {
            PinnedTarget.Sidebar(PinnedTarget.Kind.Shortcut, "entry$it" + "a".repeat(256), 0)
        }
        assertEquals(targets, PinnedTargetCodec.decodeList(PinnedTargetCodec.encodeList(targets)))
    }

    @Test
    fun identitiesIncludeKindAndUser() {
        val targets = listOf(
            PinnedTarget.Sidebar(PinnedTarget.Kind.Tool, "same", 0),
            PinnedTarget.Sidebar(PinnedTarget.Kind.Shortcut, "same", 0),
            PinnedTarget.Sidebar(PinnedTarget.Kind.Tool, "same", 10),
            PinnedTarget.Sidebar(PinnedTarget.Kind.CloneApp, "same", 0),
        )
        assertEquals(targets, PinnedTargetCodec.normalize(targets + targets))
    }

    @Test
    fun rejectsMalformedUnknownAndNonCanonicalIdentities() {
        for (value in listOf("T|-1|YQ", "T|0|", "T|00|YQ", "T|0|YQ==", "T|0|Cg", "S|0|***", "X|0|YQ", "A|bad pkg/.A", "A|com.example/", "A|com.example/.A/extra")) {
            assertNull(value, PinnedTargetCodec.decode(value))
        }
        assertNull(PinnedTargetCodec.decode(PinnedTargetCodec.encode(PinnedTarget.Sidebar(PinnedTarget.Kind.Tool, "a".repeat(1_025), 0))))
        assertEquals(emptyList<PinnedTarget>(), PinnedTargetCodec.decodeList("A|com.example/.A\n".repeat(20_000)))
    }

    @Test
    fun emptyAndInvalidListsNeverInventAReplacementApp() {
        assertEquals(emptyList<PinnedTarget>(), PinnedTargetCodec.decodeList(""))
        assertEquals(emptyList<PinnedTarget>(), PinnedTargetCodec.decodeList("unknown"))
    }
}
