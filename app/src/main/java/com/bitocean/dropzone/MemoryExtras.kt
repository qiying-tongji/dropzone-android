package com.bitocean.dropzone

import android.database.sqlite.SQLiteDatabase
import java.time.LocalDate

internal data class Milestone(val id: Long, val theme: Long, val title: String, val day: LocalDate, val body: String, val byDate: Boolean)
internal data class ReminderTarget(val kind: String, val id: Long, val collection: String = "")
internal data class Reminder(val target: ReminderTarget, val time: Long, val state: String, val version: String)
internal data class PhotoGroup(val id: Long, val name: String, val latitude: Double?, val longitude: Double?)
internal data class FaceSample(val group: Long, val item: Long, val vector: ByteArray)
internal data class FaceCover(val item: Long, val bounds: String)

internal class MemoryExtras(private val store: Store) {
    private val db get() = store.writableDatabase

    fun themes() = db.rawQuery("SELECT id,name FROM memory_theme ORDER BY id", null).use { c ->
        buildList { while (c.moveToNext()) add(c.getLong(0) to c.getString(1)) }
    }

    fun saveTheme(name: String, id: Long? = null) {
        if (id == null) db.execSQL("INSERT INTO memory_theme(name) VALUES(?)", arrayOf<Any?>(name))
        else db.execSQL("UPDATE memory_theme SET name=? WHERE id=?", arrayOf<Any?>(name, id))
    }

    fun deleteTheme(id: Long) { db.execSQL("DELETE FROM memory_theme WHERE id=?", arrayOf<Any?>(id)) }
    fun milestones(theme: Long) = readMilestones("theme", theme)
    fun milestone(id: Long) = readMilestones("id", id).singleOrNull()

    private fun readMilestones(column: String, id: Long) = db.rawQuery(
        "SELECT id,theme,title,day,body,by_date FROM milestone WHERE $column=? ORDER BY day DESC,id DESC", arrayOf(id.toString())
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(Milestone(c.getLong(0), c.getLong(1), c.getString(2),
                LocalDate.parse(c.getString(3)), c.getString(4), c.getInt(5) == 1))
        }
    }

    fun saveMilestone(theme: Long, id: Long?, title: String, day: LocalDate, body: String): Long {
        if (id == null) return db.compileStatement("INSERT INTO milestone(theme,title,day,body) VALUES(?,?,?,?)").use {
            it.bindLong(1, theme)
            it.bindString(2, title)
            it.bindString(3, day.toString())
            it.bindString(4, body)
            it.executeInsert()
        }
        db.execSQL("UPDATE milestone SET title=?,day=?,body=? WHERE id=?", arrayOf<Any?>(title, day.toString(), body, id))
        return id
    }

    fun deleteMilestone(id: Long) { db.execSQL("DELETE FROM milestone WHERE id=?", arrayOf<Any?>(id)) }
    fun links(id: Long) = ids("SELECT item FROM milestone_link WHERE milestone=?", id.toString())

    fun collectionLinks(id: Long) = db.rawQuery(
        "SELECT collection FROM milestone_collection WHERE milestone=? ORDER BY collection", arrayOf(id.toString())
    ).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }

    fun setLinks(id: Long, items: Set<Long>, byDate: Boolean, collections: Set<String> = emptySet()) = synchronized(Store.photoLock) {
        transaction {
            db.execSQL("DELETE FROM milestone_link WHERE milestone=?", arrayOf<Any?>(id))
            if (!byDate) for (item in items) db.execSQL("INSERT OR IGNORE INTO milestone_link SELECT ?,id FROM memory WHERE id=?", arrayOf<Any?>(id, item))
            db.execSQL("DELETE FROM milestone_collection WHERE milestone=?", arrayOf<Any?>(id))
            if (!byDate) for (collection in collections) db.execSQL(
                "INSERT OR IGNORE INTO milestone_collection VALUES(?,?)", arrayOf<Any?>(id, collection))
            db.execSQL("UPDATE milestone SET by_date=? WHERE id=?", arrayOf<Any?>(if (byDate) 1 else 0, id))
        }
    }

    fun material(mark: Milestone, visible: List<Memory>, collectionItems: Set<Long>): List<Memory> {
        if (mark.byDate) return visible.filter { it.kind == "photo" && it.occurredDate == mark.day.toString() }
        val selected = links(mark.id) + collectionItems
        val attachments = store.attachmentIds()
        val ids = selected + visible.filter { it.id in selected && it.kind == "event" }.flatMap { attachments[it.id].orEmpty() }
        val photoIds = visible.filter { it.id in ids && it.kind == "photo" }.mapTo(mutableSetOf()) { it.id }
        return visible.filter { it.id in ids || (it.kind == "journal" && it.journalTarget in photoIds) }
    }

    fun reminders() = readReminders(null)
    fun reminder(target: ReminderTarget) = readReminders(target).singleOrNull()

    private fun readReminders(target: ReminderTarget?) = db.rawQuery(
        "SELECT target_type,target_id,collection,at_time,state,version FROM memory_reminder" +
            (if (target == null) "" else " WHERE target_type=? AND target_id=?") + " ORDER BY at_time",
        target?.let { arrayOf(it.kind, it.id.toString()) }
    ).use { c ->
        buildList { while (c.moveToNext()) add(Reminder(ReminderTarget(c.getString(0), c.getLong(1), c.getString(2)), c.getLong(3), c.getString(4), c.getString(5))) }
    }

    fun saveReminder(reminder: Reminder, zone: String) {
        db.execSQL("INSERT OR REPLACE INTO memory_reminder VALUES(?,?,?,?,?,?,?)",
            arrayOf<Any?>(reminder.target.kind, reminder.target.id, reminder.target.collection, reminder.time, zone, reminder.state, reminder.version))
    }

    fun setReminderState(reminder: Reminder, state: String) {
        db.execSQL("UPDATE memory_reminder SET state=? WHERE target_type=? AND target_id=? AND version=?",
            arrayOf<Any?>(state, reminder.target.kind, reminder.target.id, reminder.version))
    }

    fun deleteReminder(target: ReminderTarget) { db.execSQL("DELETE FROM memory_reminder WHERE target_type=? AND target_id=?", arrayOf<Any?>(target.kind, target.id)) }

    fun groups(kind: String) = db.rawQuery("SELECT g.id,g.name,m.lat,m.lon FROM photo_group g LEFT JOIN photo_group_member m ON m.rowid=(SELECT rowid FROM photo_group_member WHERE group_id=g.id AND manual=0 AND suppressed=0 AND lat IS NOT NULL ORDER BY item LIMIT 1) WHERE g.kind=? ORDER BY g.id", arrayOf(kind)).use { c ->
        buildList {
            while (c.moveToNext()) add(PhotoGroup(c.getLong(0), c.getString(1),
                if (c.isNull(2)) null else c.getDouble(2), if (c.isNull(3)) null else c.getDouble(3)))
        }
    }

    fun createGroup(kind: String, name: String): Long =
        db.insertOrThrow("photo_group", null, android.content.ContentValues().apply {
            put("kind", kind); put("name", name)
        })

    fun renameGroup(id: Long, name: String) { db.execSQL("UPDATE photo_group SET name=? WHERE id=?", arrayOf<Any?>(name, id)) }
    fun members(id: Long) = ids("SELECT DISTINCT item FROM photo_group_member WHERE group_id=? AND suppressed=0", id.toString())
    fun scanned(kind: String) = ids("SELECT item FROM photo_group_scan WHERE kind=?", kind)
    private fun ids(sql: String, arg: String) = db.rawQuery(sql, arrayOf(arg)).use { c ->
        buildSet { while (c.moveToNext()) add(c.getLong(0)) }
    }

    fun faceSamples() = db.rawQuery(
        "SELECT group_id,item,vector FROM photo_group_member WHERE kind='face' AND suppressed=0 AND vector IS NOT NULL", null
    ).use { c ->
        buildList { while (c.moveToNext()) add(FaceSample(c.getLong(0), c.getLong(1), c.getBlob(2))) }
    }

    fun faceCovers(group: Long) = db.rawQuery(
        "SELECT item,face_bounds FROM photo_group_member WHERE kind='face' AND group_id=? AND suppressed=0 AND face_bounds IS NOT NULL ORDER BY item,slot",
        arrayOf(group.toString())
    ).use { c -> buildList { while (c.moveToNext()) add(FaceCover(c.getLong(0), c.getString(1))) } }

    fun photosMissingFaceCovers() = db.rawQuery(
        "SELECT DISTINCT item FROM photo_group_member WHERE kind='face' AND suppressed=0 AND face_bounds IS NULL AND vector IS NOT NULL AND item NOT IN (SELECT item FROM photo_group_scan WHERE kind='face-cover')",
        null
    ).use { c -> buildSet { while (c.moveToNext()) add(c.getLong(0)) } }

    fun missingFaceSamples(item: Long) = db.rawQuery(
        "SELECT slot,vector FROM photo_group_member WHERE kind='face' AND item=? AND suppressed=0 AND face_bounds IS NULL AND vector IS NOT NULL",
        arrayOf(item.toString())
    ).use { c -> buildList { while (c.moveToNext()) add(c.getInt(0) to c.getBlob(1)) } }

    fun saveFaceBounds(item: Long, slot: Int, bounds: String) {
        db.execSQL("UPDATE photo_group_member SET face_bounds=? WHERE kind='face' AND item=? AND slot=? AND suppressed=0",
            arrayOf<Any?>(bounds, item, slot))
    }

    fun addMember(kind: String, item: Long, slot: Int, group: Long, vector: ByteArray? = null, manual: Boolean = false, latitude: Double? = null, longitude: Double? = null, faceBounds: String? = null) {
        db.execSQL("INSERT OR REPLACE INTO photo_group_member VALUES(?,?,?,?,?,?,0,?,?,?)", arrayOf<Any?>(kind, item, slot, group, vector, if (manual) 1 else 0, latitude, longitude, faceBounds))
    }

    fun hasScan(kind: String, item: Long) = db.rawQuery("SELECT 1 FROM photo_group_scan WHERE kind=? AND item=?", arrayOf(kind,item.toString())).use { it.moveToFirst() }

    fun finishScan(kind: String, item: Long) { db.execSQL("INSERT OR IGNORE INTO photo_group_scan VALUES(?,?)", arrayOf<Any?>(kind, item)) }

    fun setPlace(item: Long, group: Long?) = synchronized(Store.photoLock) {
        if (store.get(item)?.kind != "photo") return@synchronized
        transaction {
            db.execSQL("DELETE FROM photo_group_member WHERE kind='place' AND item=?", arrayOf<Any?>(item))
            if (group != null) addMember("place", item, 0, group, manual = true)
            else db.execSQL("INSERT INTO photo_group_member VALUES('place',?,0,0,NULL,1,1,NULL,NULL,NULL)", arrayOf<Any?>(item))
            finishScan("place", item)
        }
    }

    fun moveMember(group: Long, item: Long, destination: Long?) = synchronized(Store.photoLock) {
        if (destination == null) db.execSQL("UPDATE photo_group_member SET suppressed=1,vector=NULL,face_bounds=NULL,manual=1,lat=NULL,lon=NULL WHERE group_id=? AND item=?", arrayOf<Any?>(group, item))
        else db.execSQL("UPDATE photo_group_member SET group_id=?,manual=1,lat=NULL,lon=NULL WHERE group_id=? AND item=? AND suppressed=0", arrayOf<Any?>(destination, group, item))
    }

    fun mergeGroups(group: Long, destination: Long) = synchronized(Store.photoLock) {
        transaction {
            db.execSQL("UPDATE photo_group_member SET group_id=?,manual=1,lat=NULL,lon=NULL WHERE group_id=?", arrayOf<Any?>(destination, group))
            db.execSQL("DELETE FROM photo_group WHERE id=?", arrayOf<Any?>(group))
        }
    }

    fun clearGroups(kind: String) = synchronized(Store.photoLock) {
        transaction {
            if (kind == "place") {
                db.execSQL("DELETE FROM photo_group_member WHERE kind='place' AND manual=0")
                db.execSQL("DELETE FROM photo_group_scan WHERE kind='place' AND item NOT IN (SELECT item FROM photo_group_member WHERE kind='place' AND manual=1)")
                db.execSQL("DELETE FROM photo_group WHERE kind='place' AND id NOT IN (SELECT group_id FROM photo_group_member WHERE kind='place' AND manual=1 AND suppressed=0)")
            } else {
                for (table in listOf("photo_group_member", "photo_group_scan", "photo_group")) db.delete(table, "kind=?", arrayOf(kind))
                db.delete("photo_group_scan", "kind='face-cover'", null)
            }
        }
    }

    fun transaction(block: () -> Unit) {
        val database = db
        database.beginTransaction()
        try { block(); database.setTransactionSuccessful() } finally { database.endTransaction() }
    }

    companion object {

        fun addPreviewsAndCollections(db: SQLiteDatabase) {
            db.execSQL("ALTER TABLE photo_group_member ADD COLUMN face_bounds TEXT")
            db.execSQL("CREATE TABLE milestone_collection(milestone INTEGER NOT NULL,collection TEXT NOT NULL,PRIMARY KEY(milestone,collection))")
            db.execSQL("CREATE TRIGGER remove_milestone_collections AFTER DELETE ON milestone BEGIN DELETE FROM milestone_collection WHERE milestone=old.id; END")
        }

        fun create(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE memory_theme(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL)")
            db.execSQL("CREATE TABLE milestone(id INTEGER PRIMARY KEY AUTOINCREMENT,theme INTEGER NOT NULL,title TEXT NOT NULL,day TEXT NOT NULL,body TEXT NOT NULL,by_date INTEGER NOT NULL DEFAULT 1)")
            db.execSQL("CREATE INDEX milestone_theme_day ON milestone(theme,day)")
            db.execSQL("CREATE TABLE milestone_link(milestone INTEGER NOT NULL,item INTEGER NOT NULL,PRIMARY KEY(milestone,item))")
            db.execSQL("CREATE TABLE memory_reminder(target_type TEXT NOT NULL,target_id INTEGER NOT NULL,collection TEXT NOT NULL,at_time INTEGER NOT NULL,zone TEXT NOT NULL,state TEXT NOT NULL,version TEXT NOT NULL,PRIMARY KEY(target_type,target_id))")
            db.execSQL("CREATE TRIGGER remove_theme AFTER DELETE ON memory_theme BEGIN DELETE FROM milestone WHERE theme=old.id; END")
            db.execSQL("CREATE TRIGGER remove_milestone AFTER DELETE ON milestone BEGIN DELETE FROM milestone_link WHERE milestone=old.id; DELETE FROM memory_reminder WHERE target_type='milestone' AND target_id=old.id; END")
            db.execSQL("CREATE TABLE photo_group(id INTEGER PRIMARY KEY AUTOINCREMENT,kind TEXT NOT NULL,name TEXT NOT NULL)")
            db.execSQL("CREATE TABLE photo_group_scan(kind TEXT NOT NULL,item INTEGER NOT NULL,PRIMARY KEY(kind,item))")
            db.execSQL("CREATE TABLE photo_group_member(kind TEXT NOT NULL,item INTEGER NOT NULL,slot INTEGER NOT NULL,group_id INTEGER NOT NULL,vector BLOB,manual INTEGER NOT NULL,suppressed INTEGER NOT NULL,lat REAL,lon REAL,PRIMARY KEY(kind,item,slot))")
            db.execSQL("CREATE INDEX photo_group_members ON photo_group_member(group_id)")
            db.execSQL("CREATE INDEX photo_group_item ON photo_group_member(item)")
            db.execSQL("CREATE TRIGGER remove_milestone_material AFTER DELETE ON memory BEGIN DELETE FROM milestone_link WHERE item=old.id; DELETE FROM memory_reminder WHERE target_type='memory' AND target_id=old.id; DELETE FROM photo_group_scan WHERE item=old.id; DELETE FROM photo_group_member WHERE item=old.id; END")
            db.execSQL("CREATE TRIGGER invalidate_photo_group AFTER UPDATE OF uri,state ON memory WHEN new.uri!=old.uri OR new.state='unavailable' BEGIN DELETE FROM photo_group_scan WHERE item=old.id AND NOT EXISTS (SELECT 1 FROM photo_group_member WHERE kind='place' AND item=old.id AND manual=1 AND photo_group_scan.kind='place'); DELETE FROM photo_group_member WHERE item=old.id AND (kind='face' OR manual=0); END")
        }
    }
}
