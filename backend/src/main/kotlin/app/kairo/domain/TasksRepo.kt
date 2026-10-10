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
)

class TasksRepo(private val db: Db, private val activity: ActivityRepo) {

    private val select = """select t.id, t.title, t.notes, t.status, t.priority, t.due_date, t.due_at, t.project_id,
        p.name as project_name, t.tags, t.sort, t.source, t.completed_at, t.created_at, t.updated_at
        from tasks t left join projects p on p.id = t.project_id"""

    suspend fun list(
        userId: UUID,
        status: String? = null,
        projectId: UUID? = null,
        includeDone: Boolean = true,
        dueBefore: LocalDate? = null,
        limit: Int = 500,
    ): List<Task> = db.read {
        query(
            """$select where t.user_id = ? and t.deleted_at is null
               and (?::text is null or t.status = ?)
               and (?::uuid is null or t.project_id = ?)
               and (? or t.status <> 'done' or t.completed_at > now() - interval '3 days')
               and (?::date is null or t.due_date <= ?)
               order by case t.status when 'doing' then 0 when 'todo' then 1 else 2 end,
                        t.sort, t.due_date nulls last, t.created_at desc
               limit ?""",
            userId, status, status, projectId, projectId, includeDone, dueBefore, dueBefore, limit,
        ) { map(it) }
    }

    suspend fun get(userId: UUID, id: UUID): Task? = db.read { getTx(userId, id) }

    private fun Tx.getTx(userId: UUID, id: UUID): Task? =
        one("$select where t.user_id = ? and t.id = ? and t.deleted_at is null", userId, id) { map(it) }

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
    ): Task {
        val task = db.tx {
            val sort = one("select coalesce(min(sort), 0) - 1 as s from tasks where user_id = ? and status = ?", userId, status) { it.getDouble("s") } ?: 0.0
            val id = one(
                """insert into tasks(user_id, title, notes, priority, due_date, due_at, project_id, tags, status, sort, source)
                   values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""",
                userId, title.trim(), notes, normalizePriority(priority), dueDate, dueAt, projectId,
                TextArray(tags), normalizeStatus(status), sort, source,
            ) { it.uuid("id") }!!
            getTx(userId, id)!!
        }
        activity.record(userId, Kinds.CREATED, EntityTypes.TASK, task.id, "Tarea: ${task.title}", source)
        return task
    }

    suspend fun update(userId: UUID, id: UUID, patch: TaskPatch, source: String): Task? {
        val current = get(userId, id) ?: return null
        val newStatus = patch.status?.let(::normalizeStatus) ?: current.status
        val task = db.tx {
            exec(
                """update tasks set title = ?, notes = ?, status = ?, priority = ?, due_date = ?, due_at = ?, project_id = ?,
                   tags = ?, sort = ?, updated_at = now(),
                   completed_at = case when ? = 'done' then coalesce(completed_at, now()) else null end
                   where user_id = ? and id = ?""",
                patch.title?.trim() ?: current.title,
                patch.notes ?: current.notes,
                newStatus,
                patch.priority?.let(::normalizePriority) ?: current.priority,
                if (patch.clearDueDate) null else patch.dueDate ?: current.dueDate,
                if (patch.clearDueAt) null else patch.dueAt ?: current.dueAt,
                if (patch.clearProject) null else patch.projectId ?: current.projectId,
                TextArray(patch.tags ?: current.tags),
                patch.sort ?: current.sort,
                newStatus,
                userId, id,
            )
            getTx(userId, id)
        } ?: return null
        val kind = if (newStatus == "done" && current.status != "done") Kinds.COMPLETED else Kinds.UPDATED
        val summary = if (kind == Kinds.COMPLETED) "Tarea completada: ${task.title}" else "Tarea editada: ${task.title}"
        activity.record(userId, kind, EntityTypes.TASK, id, summary, source)
        return task
    }

    suspend fun delete(userId: UUID, id: UUID, source: String): Task? {
        val task = get(userId, id) ?: return null
        db.tx { exec("update tasks set deleted_at = now() where user_id = ? and id = ?", userId, id) }
        activity.record(userId, Kinds.DELETED, EntityTypes.TASK, id, "Tarea eliminada: ${task.title}", source)
        return task
    }

    suspend fun restore(userId: UUID, id: UUID): Boolean =
        db.tx { exec("update tasks set deleted_at = null where user_id = ? and id = ?", userId, id) } > 0

    /** Tareas abiertas cuyo título se parece al texto (para que el agente resuelva "la tarea del pasaporte"). */
    suspend fun findOpen(userId: UUID, text: String, limit: Int = 5): List<Task> = db.read {
        query(
            """$select where t.user_id = ? and t.deleted_at is null and t.status <> 'done'
               and (t.title ilike ? or similarity(t.title, ?) > 0.2)
               order by similarity(t.title, ?) desc limit ?""",
            userId, "%${text.trim()}%", text, text, limit,
        ) { map(it) }
    }

    // --- Proyectos ---

    suspend fun projects(userId: UUID): List<Project> = db.read {
        query("select * from projects where user_id = ? and deleted_at is null order by sort, name", userId) { mapProject(it) }
    }

    suspend fun findOrCreateProject(userId: UUID, name: String): Project = db.tx {
        one("select * from projects where user_id = ? and lower(name) = lower(?) and deleted_at is null", userId, name.trim()) { mapProject(it) }
            ?: one("insert into projects(user_id, name) values (?, ?) returning *", userId, name.trim()) { mapProject(it) }!!
    }

    suspend fun createProject(userId: UUID, name: String, color: String?): Project = db.tx {
        one("insert into projects(user_id, name, color) values (?, ?, ?) returning *", userId, name.trim(), color) { mapProject(it) }!!
    }

    suspend fun updateProject(userId: UUID, id: UUID, name: String?, color: String?, sort: Double?): Project? = db.tx {
        one(
            """update projects set name = coalesce(?, name), color = coalesce(?, color), sort = coalesce(?, sort)
               where user_id = ? and id = ? and deleted_at is null returning *""",
            name?.trim(), color, sort, userId, id,
        ) { mapProject(it) }
    }

    suspend fun deleteProject(userId: UUID, id: UUID): Boolean = db.tx {
        exec("update tasks set project_id = null where user_id = ? and project_id = ?", userId, id)
        exec("update projects set deleted_at = now() where user_id = ? and id = ?", userId, id) > 0
    }

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
    )

    companion object {
        fun normalizeStatus(s: String): String = when (s.lowercase().trim()) {
            "doing", "in_progress", "en curso", "en progreso", "haciendo" -> "doing"
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
