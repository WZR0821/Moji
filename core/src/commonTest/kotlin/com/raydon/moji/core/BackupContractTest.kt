package com.raydon.moji.core

import kotlin.test.*
import kotlinx.serialization.json.*

class BackupContractTest {
    private val date = "2026-10-05T09:00:00.123Z"
    private val record = TimeRecord("record-1", "全天记录", startDate = date, endDate = date, scheduleKind = "allDay")
    private val snapshot = PlanSnapshot(records = listOf(record), lastUpdated = date)
    private fun archive(state: PlanSnapshot = snapshot) = PlanBackupArchive(appVersion = "1.4.0", exportedAt = date, snapshot = state)
    private fun raw(value: PlanBackupArchive = archive()) = MojiJson.codec.encodeToString(PlanBackupArchive.serializer(), value)

    @Test fun unrelatedJsonCannotBecomeAnEmptyBackup() {
        listOf("{}", "[]", "null", "{\"notMoji\":true}").forEach {
            assertFails { MojiJson.decodeBackupOrSnapshot(it) }
        }
    }
    @Test fun brokenArchiveCannotFallBackToLegacySnapshot() {
        assertFails { MojiJson.decodeBackupOrSnapshot("""{"formatVersion":1,"snapshot":{"records":"broken"}}""") }
        val root = MojiJson.codec.parseToJsonElement(raw()).jsonObject.toMutableMap()
        root["preferences"] = JsonPrimitive("broken")
        assertFails { MojiJson.decodeBackupOrSnapshot(JsonObject(root).toString()) }
    }
    @Test fun backupIdentityAndVersionsAreRequired() {
        assertFails { MojiJson.decodeBackupOrSnapshot(raw(archive().copy(appIdentifier = "other.app"))) }
        assertFails { MojiJson.decodeBackupOrSnapshot(raw(archive().copy(formatVersion = 2))) }
        assertFails { MojiJson.decodeBackupOrSnapshot(raw(archive().copy(formatVersion = 0))) }
        val root = MojiJson.codec.parseToJsonElement(raw()).jsonObject.toMutableMap()
        root.remove("appIdentifier")
        assertFails { MojiJson.decodeBackupOrSnapshot(JsonObject(root).toString()) }
    }
    @Test fun futureSchemaIsRejected() {
        assertFails { MojiJson.decodeBackupOrSnapshot(raw(archive(snapshot.copy(schemaVersion = PlanSnapshot.CURRENT_SCHEMA_VERSION + 1)))) }
    }
    @Test fun genuineEmptyAndLegacyBackupsRemainReadable() {
        assertTrue(MojiJson.decodeBackupOrSnapshot(raw(archive(PlanSnapshot(lastUpdated = date)))).first.records.isEmpty())
        val legacy = """{"schemaVersion":1,"records":[],"countdowns":[],"lastUpdated":"2026-07-23T00:00:00Z"}"""
        assertEquals(1, MojiJson.decodeBackupOrSnapshot(legacy).first.schemaVersion)
    }
    @Test fun fractionalDatesAndNonPreciseZeroDurationArePreserved() {
        val result = MojiJson.decodeBackupOrSnapshot(raw()).first
        assertEquals(listOf(record), result.records)
    }
    @Test fun invalidDatesAndPreciseZeroDurationAreRejected() {
        listOf(record.copy(startDate = "bad"), record.copy(scheduleKind = "exactTime"), record.copy(endDate = "2025-01-01T00:00:00Z")).forEach {
            assertFails { MojiJson.decodeBackupOrSnapshot(raw(archive(snapshot.copy(records = listOf(it))))) }
        }
    }
    @Test fun duplicateIdentifiersAreRejected() {
        assertFails { MojiJson.decodeBackupOrSnapshot(raw(archive(snapshot.copy(records = listOf(record, record))))) }
    }
    @Test fun oldCompletedKindMigratesAndDeviceCalendarIdsAreRemovedOnExport() {
        val plan = CheckInItem("plan-1", "旧记录", kind = "completed", scheduledStart = date, createdAt = date, calendarEventIdentifier = "123")
        val state = snapshot.copy(checkInItems = listOf(plan), countdowns = listOf(CountdownEvent("event-1", "纪念", date, createdAt = date, calendarEventIdentifier = "456")))
        assertEquals("completedLog", MojiJson.decodeBackupOrSnapshot(raw(archive(state))).first.checkInItems.single().kind)
        val exported = MojiJson.decodeBackupOrSnapshot(MojiJson.encodeBackup(archive(state))).first
        assertNull(exported.checkInItems.single().calendarEventIdentifier)
        assertNull(exported.countdowns.single().calendarEventIdentifier)
        assertEquals("123", state.checkInItems.single().calendarEventIdentifier)
    }
    @Test fun unknownRecordScheduleIsRejected() {
        val unknown = snapshot.copy(records = listOf(record.copy(scheduleKind = "unknown")))
        assertFails { MojiJson.decodeBackupOrSnapshot(raw(archive(unknown))) }
    }
    @Test fun invalidNestedPreferencesAreRejectedBeforeRestore() {
        val invalid = PlanPreferencesArchive(customCategories = "{bad}")
        assertFails { MojiJson.decodeBackupOrSnapshot(raw(archive().copy(preferences = invalid))) }
        assertFails { MojiJson.decodeBackupOrSnapshot(raw(archive().copy(preferences = PlanPreferencesArchive(pomodoroRemaining = -1)))) }
    }
    @Test fun customCategoriesAndQuickPresetsRoundTrip() {
        val preferences = PlanPreferencesArchive(customCategories = "[\"阅读\"]", quickPlanPresets = """[{"id":"general","buttonTitle":"读书","planTitle":"每日阅读","category":"custom:阅读"}]""")
        assertEquals(preferences, MojiJson.decodeBackupOrSnapshot(raw(archive().copy(preferences = preferences))).second)
    }
    @Test fun followDefaultPresetKeepsANullCategory() {
        val preferences = PlanPreferencesArchive(defaultCategory = "work", quickPlanPresets = """[{"id":"general","buttonTitle":"待办","planTitle":"新计划","category":null}]""")
        val restored = MojiJson.decodeBackupOrSnapshot(raw(archive().copy(preferences = preferences))).second!!
        val presets = MojiJson.codec.decodeFromString<List<QuickPlanPresetArchive>>(restored.quickPlanPresets!!)
        assertNull(presets.single().category)
        assertEquals("work", restored.defaultCategory)
    }
}
