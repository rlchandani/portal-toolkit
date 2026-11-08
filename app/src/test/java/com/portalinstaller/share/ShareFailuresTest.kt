package com.portalinstaller.share

import org.junit.Assert.assertEquals
import org.junit.Test

class ShareFailuresTest {
    @Test fun androidRefusingToLetUsReadAFileGetsPlainWords() {
        val denied = SecurityException("Permission Denial: opening provider ... has no access to content://media/external/images/1")
        assertEquals("Portal Toolkit wasn’t allowed to open this file. Share it again from the app it’s in. Nothing was sent.",
            ShareFailures.readProblem(denied))
    }

    @Test fun ourOwnValidationMessagesPassThrough() {
        assertEquals("This file is too big.", ShareFailures.readProblem(IllegalArgumentException("This file is too big.")))
    }

    @Test fun anythingElseGetsTheGenericLine() {
        assertEquals("These files can’t be read. Nothing was sent.",
            ShareFailures.readProblem(java.io.IOException("ENOENT /proc/self/fd/42")))
    }
}
