package com.portalinstaller.data.tools

import org.junit.Assert.assertEquals
import org.junit.Test

class LogcatParserTest {

    @Test
    fun `parses brief format line`() {
        val line = LogcatParser.parseLine("E/AndroidRuntime( 1234): FATAL EXCEPTION: main")
        assertEquals('E', line.level)
        assertEquals("AndroidRuntime", line.tag)
        assertEquals("FATAL EXCEPTION: main", line.message)
    }

    @Test
    fun `parses info line with spaces in message`() {
        val line = LogcatParser.parseLine("I/ActivityManager(  567): Start proc com.x for activity")
        assertEquals('I', line.level)
        assertEquals("ActivityManager", line.tag)
        assertEquals("Start proc com.x for activity", line.message)
    }

    @Test
    fun `non-matching continuation line falls back to raw message`() {
        val raw = "\tat com.example.Foo.bar(Foo.java:42)"
        val line = LogcatParser.parseLine(raw)
        assertEquals('?', line.level)
        assertEquals("", line.tag)
        assertEquals("at com.example.Foo.bar(Foo.java:42)", line.message)
    }

    @Test
    fun `blank line is tolerated`() {
        val line = LogcatParser.parseLine("")
        assertEquals('?', line.level)
        assertEquals("", line.message)
    }
}
