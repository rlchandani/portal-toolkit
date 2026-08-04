package com.portalinstaller.data.tools

import org.junit.Assert.assertEquals
import org.junit.Test

class InputInjectorTest {

    // mapPoint: view == device, no letterbox -> identity-ish mapping.
    @Test
    fun `mapPoint maps proportionally when aspect matches`() {
        // view 640x400, device 1280x800 (same 1.6 aspect). Center of view -> center of device.
        val (x, y) = InputInjector.mapPoint(320f, 200f, 640, 400, 1280, 800)
        assertEquals(640, x)
        assertEquals(400, y)
    }

    // Horizontal letterbox: view is WIDER than device aspect -> bars on left/right.
    // device 1280x800 (1.6). view 800x400 (2.0). Content rect is 640 wide, centered:
    // left bar = (800-640)/2 = 80. A tap at viewX=80 (left content edge) -> devX 0.
    @Test
    fun `mapPoint accounts for horizontal letterbox`() {
        val (x, y) = InputInjector.mapPoint(80f, 200f, 800, 400, 1280, 800)
        assertEquals(0, x)
        assertEquals(400, y)
    }

    // Vertical letterbox: view is TALLER than device aspect -> bars top/bottom.
    // device 1280x800 (1.6). view 640x600. Content rect is 400 tall, centered:
    // top bar = (600-400)/2 = 100. A tap at viewY=100 (top content edge) -> devY 0.
    @Test
    fun `mapPoint accounts for vertical letterbox`() {
        val (x, y) = InputInjector.mapPoint(320f, 100f, 640, 600, 1280, 800)
        assertEquals(640, x)
        assertEquals(0, y)
    }

    // Taps inside a letterbox bar clamp to device bounds (never negative, never > dev).
    @Test
    fun `mapPoint clamps points inside letterbox bars`() {
        // Same horizontal-letterbox geometry; viewX=0 is inside the left bar -> clamps to 0.
        val (x, _) = InputInjector.mapPoint(0f, 200f, 800, 400, 1280, 800)
        assertEquals(0, x)
        // viewX=800 (right edge, inside right bar) -> clamps to devW-... but bounded to 1280.
        val (x2, _) = InputInjector.mapPoint(800f, 200f, 800, 400, 1280, 800)
        assertEquals(1280, x2)
    }

    @Test
    fun `mapPoint maps exact bottom-right corner of content`() {
        val (x, y) = InputInjector.mapPoint(640f, 400f, 640, 400, 1280, 800)
        assertEquals(1280, x)
        assertEquals(800, y)
    }

    @Test
    fun `tapCommand formats x and y`() {
        assertEquals("input tap 100 200", InputInjector.tapCommand(100, 200))
    }

    @Test
    fun `swipeCommand formats coordinates and duration`() {
        assertEquals("input swipe 10 20 30 40 250", InputInjector.swipeCommand(10, 20, 30, 40, 250))
    }

    @Test
    fun `longPressCommand is a same-point swipe with default duration`() {
        assertEquals("input swipe 50 60 50 60 600", InputInjector.longPressCommand(50, 60))
    }

    @Test
    fun `keyeventCommand emits correct keycodes for every key`() {
        assertEquals("input keyevent 4", InputInjector.keyeventCommand(PortalKey.BACK))
        assertEquals("input keyevent 3", InputInjector.keyeventCommand(PortalKey.HOME))
        assertEquals("input keyevent 187", InputInjector.keyeventCommand(PortalKey.RECENTS))
        assertEquals("input keyevent 26", InputInjector.keyeventCommand(PortalKey.POWER))
        assertEquals("input keyevent 24", InputInjector.keyeventCommand(PortalKey.VOLUME_UP))
        assertEquals("input keyevent 25", InputInjector.keyeventCommand(PortalKey.VOLUME_DOWN))
    }

    @Test
    fun `textCommand replaces spaces with percent-s and quotes the argument`() {
        // Spaces become %s (input text's space rule); the whole thing is shell-quoted.
        assertEquals("input text 'hello%sworld'", InputInjector.textCommand("hello world"))
    }

    @Test
    fun `textCommand escapes embedded single quotes via shellQuote`() {
        assertEquals("input text 'it'\\''s'", InputInjector.textCommand("it's"))
    }

    @Test
    fun `textCommand tolerates empty string`() {
        assertEquals("input text ''", InputInjector.textCommand(""))
    }
}
