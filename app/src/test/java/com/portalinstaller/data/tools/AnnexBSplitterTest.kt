package com.portalinstaller.data.tools

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnexBSplitterTest {

    private fun bytes(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    @Test fun `NAL exactly at byte limit is emitted before following prefix`() {
        val splitter = AnnexBSplitter(maxNalBytes = 8)
        val result = splitter.feed(bytes(0, 0, 1, 0x65, 2, 2, 2, 2, 0, 0, 1, 0x41))
        assertEquals(1, result.size)
        assertArrayEquals(bytes(0, 0, 1, 0x65, 2, 2, 2, 2), result.single())
    }

    @Test
    fun `emits complete NALs and retains the last incomplete one`() {
        val splitter = AnnexBSplitter()
        // Trei coduri de start; ultimul NAL ramane in buffer (nu urmeaza alt cod).
        val input = bytes(
            0, 0, 0, 1, 0x67, 0x10,   // SPS (prefix pe 4 octeti)
            0, 0, 1, 0x68, 0x20,      // PPS (prefix pe 3 octeti)
            0, 0, 1, 0x65, 0x30       // IDR (retinut, nu urmeaza cod de start)
        )
        val nals = splitter.feed(input)
        assertEquals(2, nals.size)
        // Octetul in plus de 00 din prefixul pe 4 octeti este aruncat: emitem 00 00 01 ...
        assertArrayEquals(bytes(0, 0, 1, 0x67, 0x10), nals[0])
        assertArrayEquals(bytes(0, 0, 1, 0x68, 0x20), nals[1])
    }

    @Test
    fun `completes a NAL split across two feeds`() {
        val splitter = AnnexBSplitter()
        // Primul feed: un NAL complet + inceputul urmatorului.
        val first = splitter.feed(bytes(0, 0, 1, 0x67, 0x10, 0, 0, 1, 0x65))
        assertEquals(1, first.size)
        assertArrayEquals(bytes(0, 0, 1, 0x67, 0x10), first[0])
        // Al doilea feed: restul NAL-ului + un cod de start nou ca sa il inchida.
        val second = splitter.feed(bytes(0x30, 0x31, 0, 0, 1, 0x68))
        assertEquals(1, second.size)
        assertArrayEquals(bytes(0, 0, 1, 0x65, 0x30, 0x31), second[0])
    }

    @Test
    fun `completes a start code split across the feed boundary`() {
        val splitter = AnnexBSplitter()
        // Primul feed: SPS + primii doi octeti (00 00) ai codului urmator. NAL-ul
        // nu se poate emite inca (nu stim unde se termina pana nu vine urmatorul cod).
        val first = splitter.feed(bytes(0, 0, 1, 0x67, 0x10, 0, 0))
        assertEquals(0, first.size)
        // Al doilea feed: 01 completeaza codul de start (deci SPS se inchide), apoi
        // IDR urmat de inca un cod care il inchide. Codul de start impartit peste
        // granita a fost reasamblat corect.
        val second = splitter.feed(bytes(1, 0x65, 0x40, 0, 0, 1, 0x41))
        assertEquals(2, second.size)
        assertArrayEquals(bytes(0, 0, 1, 0x67, 0x10), second[0])
        assertArrayEquals(bytes(0, 0, 1, 0x65, 0x40), second[1])
    }

    @Test
    fun `discards leading garbage before the first start code`() {
        val splitter = AnnexBSplitter()
        val nals = splitter.feed(bytes(0xAA, 0xBB, 0, 0, 1, 0x67, 0x10, 0, 0, 1, 0x68))
        assertEquals(1, nals.size)
        assertArrayEquals(bytes(0, 0, 1, 0x67, 0x10), nals[0])
    }

    @Test
    fun `empty feed returns nothing`() {
        assertEquals(0, AnnexBSplitter().feed(ByteArray(0)).size)
    }

    @Test
    fun `nalType reads the header byte after the start code`() {
        assertEquals(7, nalType(bytes(0, 0, 1, 0x67, 0x00)))
        assertEquals(8, nalType(bytes(0, 0, 1, 0x68, 0x00)))
        assertEquals(5, nalType(bytes(0, 0, 1, 0x65, 0x00)))
        assertEquals(1, nalType(bytes(0, 0, 1, 0x41, 0x00)))
    }

    @Test
    fun `isConfigNal is true only for SPS and PPS`() {
        assertTrue(isConfigNal(bytes(0, 0, 1, 0x67, 0x00)))  // SPS
        assertTrue(isConfigNal(bytes(0, 0, 1, 0x68, 0x00)))  // PPS
        assertFalse(isConfigNal(bytes(0, 0, 1, 0x65, 0x00))) // IDR
        assertFalse(isConfigNal(bytes(0, 0, 1, 0x41, 0x00))) // non-IDR slice
    }
}
