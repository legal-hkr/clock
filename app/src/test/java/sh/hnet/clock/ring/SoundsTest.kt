package sh.hnet.clock.ring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SoundsTest {
    private val phone = listOf(SystemSound("content://media/internal/audio/media/11", "Argon"), SystemSound("content://media/internal/audio/media/12", "Helium"))
    private val byUri = phone.associate { it.uri to it.title }

    @Test
    fun `a sound still on the phone keeps its address`() {
        assertEquals(phone[0].uri, Sounds.here(phone[0].uri, "Argon", phone))
    }

    @Test
    fun `a sound from another phone is found by its name`() {
        assertEquals(phone[1].uri, Sounds.here("content://media/internal/audio/media/40", "Helium", phone))
        // The other phone's address may belong to a different sound here: the name decides
        assertEquals(phone[1].uri, Sounds.here(phone[0].uri, "Helium", phone))
    }

    @Test
    fun `a sound this phone lacks keeps its address, to be reported as gone`() {
        assertEquals("content://media/internal/audio/media/40", Sounds.here("content://media/internal/audio/media/40", "Neon", phone))
    }

    @Test
    fun `default and silent are not sounds to look for`() {
        assertEquals("default", Sounds.here("default", null, phone))
        assertEquals("silent", Sounds.here("silent", "Argon", phone))
        assertEquals(null, Sounds.here(null, null, phone))
    }

    @Test
    fun `gone is an unknown address or one that belongs to another sound`() {
        assertFalse(Sounds.gone(SoundChoice.Sound(phone[0].uri, "Argon"), byUri))
        assertTrue(Sounds.gone(SoundChoice.Sound("content://media/internal/audio/media/40", "Neon"), byUri))
        assertTrue(Sounds.gone(SoundChoice.Sound(phone[0].uri, "Neon"), byUri))
        // A sound stored without its name is taken by its address
        assertFalse(Sounds.gone(SoundChoice.Sound(phone[0].uri, ""), byUri))
    }

    @Test
    fun `nothing is gone while the list is unknown, and default and silent never are`() {
        assertFalse(Sounds.gone(SoundChoice.Sound("content://x", "Neon"), null))
        assertFalse(Sounds.gone(SoundChoice.Default, byUri))
        assertFalse(Sounds.gone(SoundChoice.Silent, byUri))
    }
}
