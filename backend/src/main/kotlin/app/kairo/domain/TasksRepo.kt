package app.kairo.domain

import app.kairo.db.Db
import app.kairo.db.TextArray
import app.kairo.db.Tx
import app.kairo.db.instant
import app.kairo.db.instantOrNull
import app.kairo.db.localDateOrNull
import app.kairo.db.str
import app.kairo.db.strOrNull
import app.kairo.db.textList
import app.kairo.db.uuid
import app.kairo.db.uuidOrNull
import app.kairo.services.NoticeText
import app.kairo.services.Notifier
import app.kairo.util.M
import app.kairo.util.Plain
import app.kairo.util.localized
import app.kairo.util.say
import app.kairo.util.humanDate
import app.kairo.util.requireInput
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class TaskPatch(
    val title: String? = null,
    val notes: String? = null,
    val status: String? = null,
    val priority: String? = null,
    val dueDate: LocalDate? = null,
    val clearDueDate: Boolean = false,
    val dueAt: Instant? = null,
    val clearDueAt: Boolean = false,
    val projectId: UUID? = null,
    val clearProject: Boolean = false,
    val tags: List<String>? = null,
    val sort: Double? = null,
    val shared: Boolean? = null,
    /** Responsible family member; assigning someone else makes it a family task. */
    val assigneeId: UUID? = null,
    val clearAssignee: Boolean = false,
)

class TasksRepo(private val db: Db, private val activity: ActivityRepo, private val notifier: Notifier) {

    private val select = """select t.id, t.user_id, t.shared, t.assignee_id, t.title, t.notes, t.status, t.priority, t.due_date, t.due_at,
        t.project_id, p.name as project_name, t.tags, t.sort, t.source, t.completed_at, t.created_at, t.updated_at
        from tasks t left join projects p on p.id = t.project_id and p.deleted_at is null"""

    suspend fun list(
        userId: UUID,
        status: String? = null,
        projectId: UUID? = null,
        includeDone: Boolean = true,
        dueBefore: LocalDate? = null,
        limit: Int = 500,
        assigneeId: UUID? = null,
    ): List<Task> = db.read {
        query(
            """$select where ${visibleTo("t.")} and t.deleted_at is null
               and (?::text is null or t.status = ?)
               and (?::uuid is null or t.project_id = ?)
               and (? or t.status <> 'done' or t.completed_at > now() - interval '3 days')
               and (?::date is null or t.due_date <= ?)
               and (?::uuid is null or t.assignee_id = ?)
               order by case t.status when 'doing' then 0 when 'todo' then 1 else 2 end,
                        t.sort, t.due_date nulls last, t.created_at desc
               limit ?""",
            userId, userId, status, status, projectId, projectId, includeDone, dueBefore, dueBefore, assigneeId, assigneeId, limit,
        ) { map(it) }
    }

    suspend fun get(userId: UUID, id: UUID): Task? = db.read { getTx(userId, id) }

    private fun Tx.getTx(userId: UUID, id: UUID): Task? =
        one("$select where ${visibleTo("t.")} and t.id = ? and t.deleted_at is null", userId, userId, id) { map(it) }

    suspend fun create(
        userId: UUID,
        title: String,
        notes: String = "",
        priority: String = "medium",
        dueDate: LocalDate? = null,
        dueAt: Instant? = null,
        projectId: UUID? = null,
        tags: List<String> = emptyList(),
        status: String = "todo",
        source: String,
        shared: Boolean = false,
        assigneeId: UUID? = null,
    ): Task {
        val isShared = shared || (assigneeId != null && assigneeId != userId)
        projectId?.let { requireOwnProject(userId, it) }
        val task = db.tx {
            val sort = one("select coalesce(min(sort), 0) - 1 as s from tasks where user_id = ? and status = ?", userId, status) { it.getDouble("s") } ?: 0.0
            val id = one(
                """insert into tasks(user_id, household_id, shared, assignee_id, title, notes, priority, due_date, due_at, project_id, tags, status, sort, source)
                   values (?, $HOUSEHOLD_OF, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""",
                userId, userId, isShared, assigneeId, title.trim(), notes, normalizePriority(priority), dueDate, dueAt, projectId,
                TextArray(tags), normalizeStatus(status), sort, source,
            ) { it.uuid("id") }!!
            getTx(userId, id)!!
        }
        activity.record(userId, Kinds.CREATED, EntityTypes.TASK, task.id, say(M.ACT_TASK, task.title), source, shared = task.shared)
        if (task.shared) {
            val who = notifier.who(userId)
            val detail = localized { lang -> task.title + (task.dueDate?.let { " · ${humanDate(it, lang = lang)}" } ?: "") }
            val assignee = task.assigneeId?.takeIf { it != userId }
            assignee?.let { notifier.family(userId, listOf(it), NoticeKinds.ASSIGNED, NoticeText(say(M.NOTICE_TASK_ASSIGNED, who), detail), EntityTypes.TASK, task.id) }
            val others = notifier.members(userId).toSet() - setOfNotNull(assignee)
            notifier.family(userId, others, NoticeKinds.SHARED, NoticeText(say(M.NOTICE_TASK_ADDED, who), detail), EntityTypes.TASK, task.id)
        }
        return task
    }

    suspend fun update(userId: UUID, id: UUID, patch: TaskPatch, source: String): Task? {
        val current = get(userId, id) ?: return null
        if (patch.shared != null && patch.shared != current.shared && current.ownerId != userId) {
            throw FamilyException(M.ONLY_OWNER_SHARE_TASK)
        }
        if (!patch.clearProject && patch.projectId != null && patch.projectId != current.projectId) requireOwnProject(userId, patch.projectId)
        val newStatus = patch.status?.let(::normalizeStatus) ?: current.status
        val newAssignee = if (patch.clearAssignee) null else patch.assigneeId ?: current.assigneeId
        val newShared = (patch.shared ?: current.shared) || (newAssignee != null && newAssignee != current.ownerId)
        val task = db.tx {
            exec(
                """update tasks set title = ?, notes = ?, status = ?, priority = ?, due_date = ?, due_at = ?, project_id = ?,
                   tags = ?, sort = ?, shared = ?, assignee_id = ?, updated_at = now(),
                   completed_at = case when ? = 'done' then coalesce(completed_at, now()) else null end
                   where ${visibleTo()} and id = ?""",
                patch.title?.trim() ?: current.title,
                patch.notes ?: current.notes,
                newStatus,
                patch.priority?.let(::normalizePriority) ?: current.priority,
                if (patch.clearDueDate) null else patch.dueDate ?: current.dueDate,
                if (patch.clearDueAt) null else patch.dueAt ?: current.dueAt,
                if (patch.clearProject) null else patch.projectId ?: current.projectId,
                TextArray(patch.tags ?: current.tags),
                patch.sort ?: current.sort,
                newShared,
                newAssignee,
                newStatus,
                userId, userId, id,
            )
            getTx(userId, id)
        } ?: return null
        val kind = if (newStatus == "done" && current.status != "done") Kinds.COMPLETED else Kinds.UPDATED
        val summary = say(if (kind == Kinds.COMPLETED) M.ACT_TASK_DONE else M.ACT_TASK_EDITED, task.title)
        activity.record(userId, kind, EntityTypes.TASK, id, summary, source, shared = task.shared || current.shared)
        if (task.shared) {
            val who = notifier.who(userId)
            if (task.assigneeId != null && task.assigneeId != current.assigneeId && task.assigneeId != userId) {
                notifier.family(userId, listOf(task.assigneeId), NoticeKinds.ASSIGNED, NoticeText(say(M.NOTICE_TASK_ASSIGNED, who), Plain(task.title)), EntityTypes.TASK, id)
            }
            if (kind == Kinds.COMPLETED) {
                // Whoever created it and whoever it was assigned to want to know it is done
                notifier.family(userId, listOfNotNull(task.ownerId, task.assigneeId), NoticeKinds.UPDATED, NoticeText(say(M.NOTICE_TASK_DONE, who), Plain(task.title)), EntityTypes.TASK, id)
            }
            if (!current.shared) {
                notifier.family(userId, notifier.members(userId).toSet() - setOfNotNull(task.assigneeId), NoticeKinds.SHARED, NoticeText(say(M.NOTICE_TASK_SHARED, who), Plain(task.title)), EntityTypes.TASK, id)
            }
        }
        return task
    }

    suspend fun delete(userId: UUID, id: UUID, source: String): Task? {
        val task = get(userId, id) ?: return null
        db.tx { exec("update tasks set deleted_at = now() where ${visibleTo()} and id = ?", userId, userId, id) }
        activity.record(userId, Kinds.DELETED, EntityTypes.TASK, id, say(M.ACT_TASK_DELETED, task.title), source, shared = task.shared)
        return task
    }

    suspend fun restore(userId: UUID, id: UUID): Boolean =
        db.tx { exec("update tasks set deleted_at = null where ${visibleTo()} and id = ?", userId, userId, id) } > 0

    /** Open tasks whose title resembles the text (so the agent can resolve "the passport task"). */
    suspend fun findOpen(userId: UUID, text: String, limit: Int = 5): List<Task> = db.read {
        query(
            """$select where ${visibleTo("t.")} and t.deleted_at is null and t.status <> 'done'
               and (t.title ilike ? or similarity(t.title, ?) > 0.2)
               order by similarity(t.title, ?) desc limit ?""",
            userId, userId, "%${text.trim()}%", text, text, limit,
        ) { map(it) }
    }

    // --- Projects ---

    /** Projects are personal: only one of the user's own that is not deleted can be used. */
    private suspend fun requireOwnProject(userId: UUID, projectId: UUID) {
        val ok = db.read { one("select 1 as ok from projects where id = ? and user_id = ? and deleted_at is null", projectId, userId) { true } } ?: false
        requireInput(ok, M.PROJECT_NOT_FOUND)
    }

    suspend fun projects(userId: UUID): List<Project> = db.read {
        query("select * from projects where user_id = ? and deleted_at is null order by sort, name", userId) { mapProject(it) }
    }

    suspend fun findOrCreateProject(userId: UUID, name: String): Project = db.tx {
        one("select * from projects where user_id = ? and lower(name) = lower(?) and deleted_at is null", userId, name.trim()) { mapProject(it) }
            ?: one("insert into projects(user_id, name) values (?, ?) returning *", userId, name.trim()) { mapProject(it) }!!
    }

    suspend fun createProject(userId: UUID, name: String, color: String?): Project = db.tx {
        one("insert into projects(user_id, name, color) values (?, ?, ?) returning *", userId, name.trim(), color) { mapProject(it) }!!
    }.also { activity.notify(userId, EntityTypes.PROJECT, it.id, Kinds.CREATED) }

    suspend fun updateProject(userId: UUID, id: UUID, name: String?, color: String?, sort: Double?): Project? = db.tx {
        one(
            """update projects set name = coalesce(?, name), color = coalesce(?, color), sort = coalesce(?, sort)
               where user_id = ? and id = ? and deleted_at is null returning *""",
            name?.trim(), color, sort, userId, id,
        ) { mapProject(it) }
    }.also { if (it != null) activity.notify(userId, EntityTypes.PROJECT, id, Kinds.UPDATED) }

    suspend fun deleteProject(userId: UUID, id: UUID): Boolean = db.tx {
        exec("update tasks set project_id = null where project_id = ? and exists (select 1 from projects where id = ? and user_id = ?)", id, id, userId)
        exec("update projects set deleted_at = now() where user_id = ? and id = ?", userId, id) > 0
    }.also { if (it) activity.notify(userId, EntityTypes.PROJECT, id, Kinds.DELETED) }

    private fun mapProject(rs: ResultSet) = Project(rs.uuid("id"), rs.str("name"), rs.strOrNull("color"), rs.getDouble("sort"), rs.instant("created_at"))

    private fun map(rs: ResultSet) = Task(
        id = rs.uuid("id"),
        title = rs.str("title"),
        notes = rs.str("notes"),
        status = rs.str("status"),
        priority = rs.str("priority"),
        dueDate = rs.localDateOrNull("due_date"),
        dueAt = rs.instantOrNull("due_at"),
        projectId = rs.uuidOrNull("project_id"),
        projectName = rs.strOrNull("project_name"),
        tags = rs.textList("tags"),
        sort = rs.getDouble("sort"),
        source = rs.str("source"),
        completedAt = rs.instantOrNull("completed_at"),
        createdAt = rs.instant("created_at"),
        updatedAt = rs.instant("updated_at"),
        shared = rs.getBoolean("shared"),
        ownerId = rs.uuid("user_id"),
        assigneeId = rs.uuidOrNull("assignee_id"),
    )

    companion object {
        fun normalizeStatus(s: String): String = when (s.lowercase().trim()) {
            "doing", "in_progress", "in progress", "en curso", "en progreso", "haciendo" -> "doing"
            "done", "completed", "hecho", "hecha", "completada", "terminada" -> "done"
            else -> "todo"
        }

        fun normalizePriority(p: String): String = when (p.lowercase().trim()) {
            "low", "baja" -> "low"
            "high", "alta" -> "high"
            "urgent", "urgente", "critical" -> "urgent"
            else -> "medium"
        }
    }
}
