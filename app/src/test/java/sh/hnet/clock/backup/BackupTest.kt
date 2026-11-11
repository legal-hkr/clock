package sh.hnet.clock.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupTest {

    private val backup = BackupFile(
        appVersion = "0.1.0",
        appVersionCode = 1,
        createdAt = "2026-11-11T18:00:00Z",
        settings = BackupSettings(clock = BackupSettings.Clock(showSeconds = true, homeClock = false, homeZone = "Europe/Warsaw")),
        cities = listOf("Atlantic/Reykjavik", "America/New_York", "Asia/Kolkata"),
    )

    @Test
    fun `a backup reads back as it was written`() {
        val check = Backup.check(Backup.encode(backup))
        assertEquals(BackupCheck.Valid(backup), check)
    }

    @Test
    fun `the file names its format and version`() {
        val text = Backup.encode(backup)
        assertTrue(text.contains("\"format\": \"sh.hnet.clock.backup\""))
        assertTrue(text.contains("\"formatVersion\": 1"))
    }

    @Test
    fun `text that is not JSON, or not an object, is not ours`() {
        assertTrue(Backup.check("#EXTM3U") is BackupCheck.NotOurs)
        assertTrue(Backup.check("[1, 2]") is BackupCheck.NotOurs)
        assertTrue(Backup.check("") is BackupCheck.NotOurs)
    }

    @Test
    fun `JSON of another app is not ours`() {
        assertTrue(Backup.check("""{"format": "sh.hnet.music.backup", "formatVersion": 1}""") is BackupCheck.NotOurs)
        assertTrue(Backup.check("""{"formatVersion": 1}""") is BackupCheck.NotOurs)
        assertTrue(Backup.check("""{"format": "sh.hnet.clock.backup"}""") is BackupCheck.NotOurs)
    }

    @Test
    fun `a later format version is refused as newer`() {
        val check = Backup.check("""{"format": "sh.hnet.clock.backup", "formatVersion": 2, "something": []}""")
        assertEquals(BackupCheck.Newer(2), check)
    }

    @Test
    fun `fields this version doesn't know are ignored`() {
        val text = """
            {"format": "sh.hnet.clock.backup", "formatVersion": 1, "appVersion": "0.9.0", "appVersionCode": 9,
             "createdAt": "2027-01-01T00:00:00Z", "settings": {"later": true}, "alarms": [{"time": "06:30"}]}
        """.trimIndent()
        val check = Backup.check(text)
        assertTrue(check is BackupCheck.Valid)
        assertEquals("0.9.0", (check as BackupCheck.Valid).backup.appVersion)
    }

    @Test
    fun `a backup from before cities and clock settings names neither`() {
        val text = """
            {"format": "sh.hnet.clock.backup", "formatVersion": 1, "appVersion": "0.1.0", "appVersionCode": 1,
             "createdAt": "2026-11-11T18:00:00Z", "settings": {}}
        """.trimIndent()
        val valid = Backup.check(text) as BackupCheck.Valid
        assertEquals(null, valid.backup.cities)
        assertEquals(null, valid.backup.settings.clock)
        assertEquals(0, valid.cities)
    }

    @Test
    fun `the cities keep their order`() {
        val valid = Backup.check(Backup.encode(backup)) as BackupCheck.Valid
        assertEquals(listOf("Atlantic/Reykjavik", "America/New_York", "Asia/Kolkata"), valid.backup.cities)
        assertEquals(3, valid.cities)
    }

    @Test
    fun `a file of our format without its required fields is not ours`() {
        assertTrue(Backup.check("""{"format": "sh.hnet.clock.backup", "formatVersion": 1}""") is BackupCheck.NotOurs)
    }
}
