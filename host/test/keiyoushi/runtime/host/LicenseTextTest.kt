package keiyoushi.runtime.host

import org.junit.Assert.assertEquals
import org.junit.Test

class LicenseTextTest {
    @Test
    fun joinsWrappedLinesAndKeepsParagraphs() {
        val text = """
            |   1. Definitions.
            |
            |      "License" shall mean the terms and conditions for use, reproduction,
            |      and distribution as defined by Sections 1 through 9 of this document.
            |
            |      "Licensor" shall mean the copyright owner.
        """.trimMargin()

        assertEquals(
            "1. Definitions.\n\n" +
                "\"License\" shall mean the terms and conditions for use, reproduction, " +
                "and distribution as defined by Sections 1 through 9 of this document.\n\n" +
                "\"Licensor\" shall mean the copyright owner.",
            LicenseText.reflow(text),
        )
    }

    @Test
    fun keepsListItemsAndTitlesOnTheirOwnLines() {
        val text = """
            |                                 Apache License
            |                           Version 2.0, January 2004
            |
            |Redistribution is permitted provided that:
            |  * source code keeps this
            |    notice, and
            |  - binaries reproduce it.
            |  (a) You must give a copy;
            |  2. non-
            |  exclusive terms apply.
        """.trimMargin()

        assertEquals(
            "Apache License\nVersion 2.0, January 2004\n\n" +
                "Redistribution is permitted provided that:\n" +
                "* source code keeps this notice, and\n" +
                "- binaries reproduce it.\n" +
                "(a) You must give a copy;\n" +
                "2. non-exclusive terms apply.",
            LicenseText.reflow(text),
        )
    }

    @Test
    fun normalisesLineEndingsAndTrailingBlankLines() {
        assertEquals("a b\n\nc", LicenseText.reflow("a\r\nb\r\n\r\n\r\nc\r\n\r\n"))
    }
}
