package app.kairo.agent

import app.kairo.domain.EntityRef
import app.kairo.domain.EntityTypes
import app.kairo.domain.EventPatch
import app.kairo.domain.EventsRepo
import app.kairo.domain.FilesRepo
import app.kairo.domain.ListsRepo
import app.kairo.domain.MemoriesRepo
import app.kairo.domain.NotePatch
import app.kairo.domain.NoticeKinds
import app.kairo.domain.NotesRepo
import app.kairo.domain.PlacesRepo
import app.kairo.domain.Recurrence
import app.kairo.domain.ReminderPatch
import app.kairo.domain.RemindersRepo
import app.kairo.domain.TaskPatch
import app.kairo.domain.TasksRepo
import app.kairo.domain.Text
import app.kairo.domain.UsersRepo
import app.kairo.integrations.WeatherService
import app.kairo.nan.NanClient
import app.kairo.services.BriefingService
import app.kairo.services.NoticeText
import app.kairo.services.Notifier
import app.kairo.util.InvalidInput
import app.kairo.util.M
import app.kairo.util.Plain
import app.kairo.util.bool
import app.kairo.util.humanDate
import app.kairo.util.humanDateTime
import app.kairo.util.humanTime
import app.kairo.util.int
import app.kairo.util.intList
import app.kairo.util.parseUserDate
import app.kairo.util.parseUserDateTime
import app.kairo.util.str
import app.kairo.util.strList
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * The agent's built-in tools. Descriptions and parameter docs are for the model, so they are English;
 * what the user sees (tool labels, action cards, errors) comes from the message catalog in the user's language.
 * Field names in tool results are English too (the model reads them).
 */
class BuiltinTools(
    private val notes: NotesRepo,
    private val tasks: TasksRepo,
    private val reminders: RemindersRepo,
    private val places: PlacesRepo,
    private val lists: ListsRepo,
    private val events: EventsRepo,
    private val memories: MemoriesRepo,
    private val briefing: BriefingService,
    private val weather: WeatherService,
    private val files: FilesRepo,
    private val nan: NanClient,
    private val users: UsersRepo,
    private val notifier: Notifier,
) {

    fun all(): List<Tool> = ToolRegistryBuilder().apply {
        noteTools()
        taskTools()
        reminderTools()
        listTools()
        calendarTools()
        memoryTools()
        familyTools()
        deviceTools()
        infoTools()
    }.tools

    // ---------------------------------------------------------------- Notes

    private fun ToolRegistryBuilder.noteTools() {
        tool(
            "create_note", M.TL_CREATE_NOTE,
            "Saves a note. Use it to jot down ideas, facts, passwords, gift ideas, etc. If the user dictates several related things, put them in a single note.",
            params {
                string("title", "Short title (optional, derived from the content)")
                string("content", "Full content of the note, in simple markdown", required = true)
                stringArray("tags", "Lowercase tags without #")
                boolean("pinned", "Pin to the top")
                boolean("shared", "Family note: the whole family sees it (home wifi, school information...)")
            },
        ) { a ->
            val content = a.str("content") ?: return@tool ToolResult.error(t(M.TE_NOTE_CONTENT_MISSING))
            val attach = pendingAttachments.toList().also { pendingAttachments.clear() }
            val shared = a.bool("shared") ?: false
            val n = notes.create(userId, a.str("title") ?: "", content, a.strList("tags") ?: emptyList(), a.bool("pinned") ?: false, source, attach, shared)
            ToolResult(
                json { put("id", n.id.toString()); put("title", n.title); put("shared", n.shared) },
                t(if (n.shared) M.TS_NOTE_SHARED else M.TS_NOTE_SAVED, n.title),
                entity = EntityRef(EntityTypes.NOTE, n.id, n.title),
            )
        }

        tool(
            "search_notes", M.TL_SEARCH_NOTES,
            "Searches the user's notes by meaning and by text. Use it before answering questions about information the user saved.",
            params {
                string("query", "What to look for, in natural language", required = true)
                integer("limit", "Maximum results (default 5)")
            },
        ) { a ->
            val q = a.str("query") ?: ""
            val found = notes.search(userId, q, (a.int("limit") ?: 5).coerceIn(1, 10))
            ToolResult(
                buildJsonObject {
                    put("results", buildJsonArray {
                        found.forEach { n ->
                            add(json {
                                put("id", n.id.toString()); put("title", n.title); put("content", n.content.take(1500))
                                put("tags", JsonArray(n.tags.map { JsonPrimitive(it) })); put("updated", humanDateTime(n.updatedAt, zone, lang = lang))
                                if (n.shared) put("shared", true)
                                memberName(n.ownerId)?.let { put("from", it) }
                            })
                        }
                    })
                },
                if (found.isEmpty()) t(M.TS_NOTES_NONE, q) else t(M.TS_NOTES_FOUND, found.size),
                untrusted = OTHERS_NOTES.takeIf { found.any { fromOthers(it.ownerId, it.shared, it.source) } },
            )
        }

        tool(
            "update_note", M.TL_UPDATE_NOTE,
            "Modifies an existing note (use search_notes to get the id). With `append` it adds text at the end without deleting what was there.",
            params {
                string("id", "Note id", required = true)
                string("title", "New title")
                string("content", "Complete new content (replaces)")
                string("append", "Text to add at the end")
                stringArray("tags", "New tags")
                boolean("pinned", "Pin or unpin")
            },
        ) { a ->
            val id = a.uuid("id") ?: return@tool ToolResult.error(t(M.TE_NOTE_BAD_ID))
            val current = notes.get(userId, id) ?: return@tool ToolResult.error(t(M.TE_NOTE_NOT_FOUND))
            val content = a.str("content") ?: a.str("append")?.let { current.content.trimEnd() + "\n" + it }
            val n = notes.update(userId, id, NotePatch(a.str("title"), content, a.strList("tags"), a.bool("pinned")), source)!!
            ToolResult(json { put("id", n.id.toString()); put("title", n.title) }, t(M.TS_NOTE_UPDATED, n.title), entity = EntityRef(EntityTypes.NOTE, n.id, n.title))
        }

        tool(
            "delete_note", M.TL_DELETE_NOTE,
            "Deletes a note (it can be undone from Activity). Only if the user explicitly asked.",
            params { string("id", "Note id", required = true) },
        ) { a ->
            val id = a.uuid("id") ?: return@tool ToolResult.error(t(M.INVALID_ID))
            val n = notes.delete(userId, id, source) ?: return@tool ToolResult.error(t(M.TE_NOTE_NOT_FOUND))
            ToolResult(json { put("deleted", true) }, t(M.TS_NOTE_DELETED, n.title), entity = EntityRef(EntityTypes.NOTE, n.id, n.title))
        }
    }

    // ---------------------------------------------------------------- Tasks

    private fun ToolRegistryBuilder.taskTools() {
        tool(
            "create_task", M.TL_CREATE_TASK,
            "Creates a task in the organizer (things to do). For alerts at a given time use create_reminder; you can use both if the user wants a task and an alert.",
            params {
                string("title", "What needs to be done, as a short phrase", required = true)
                string("notes", "Details")
                string("due_date", "Due date YYYY-MM-DD")
                string("due_time", "Due time HH:MM (optional)")
                string("priority", "Priority", enum = listOf("low", "medium", "high", "urgent"))
                string("project", "Name of the project or area (created if it does not exist)")
                string("status", "Initial status", enum = listOf("todo", "doing"))
                boolean("shared", "Family task, visible to everyone")
                string("assignee", "Name of the family member who must do it (makes it a family task)")
            },
        ) { a ->
            val title = a.str("title") ?: return@tool ToolResult.error(t(M.TE_MISSING_TITLE))
            val date = a.str("due_date")?.let { runCatching { parseUserDate(it) }.getOrNull() }
            val dueAt = if (date != null) a.str("due_time")?.let { tm -> runCatching { date.atTime(LocalTime.parse(tm.padStart(5, '0'))).atZone(zone).toInstant() }.getOrNull() } else null
            val assignee = a.str("assignee")?.let { n -> findMember(n) ?: return@tool unknownMember(n) }
            val project = a.str("project")?.let { tasks.findOrCreateProject(userId, it) }
            val task = tasks.create(
                userId, title, a.str("notes") ?: "", a.str("priority") ?: "medium", date, dueAt, project?.id,
                status = a.str("status") ?: "todo", source = source, shared = a.bool("shared") ?: false, assigneeId = assignee?.id,
            )
            val who = memberName(task.assigneeId)
            val label = task.title + (task.dueDate?.let { " · ${humanDate(it, today(), lang)}" } ?: "")
            ToolResult(
                json {
                    put("id", task.id.toString()); put("title", task.title); task.dueDate?.let { put("due", humanDate(it, today(), lang)) }
                    put("shared", task.shared); who?.let { put("assignee", it) }
                },
                when {
                    who != null -> t(M.TS_TASK_FOR, who, label)
                    task.shared -> t(M.TS_TASK_SHARED, label)
                    else -> t(M.TS_TASK, label)
                },
                entity = EntityRef(EntityTypes.TASK, task.id, task.title),
            )
        }

        tool(
            "list_tasks", M.TL_LIST_TASKS,
            "Lists open tasks (and optionally recently completed ones). Filter by project, status or due date.",
            params {
                string("status", "Filter by status", enum = listOf("todo", "doing", "done"))
                string("project", "Project name")
                string("due_before", "Only with a due date up to YYYY-MM-DD")
                string("query", "Text to search in the title")
                string("assignee", "Only the ones assigned to this member ('me' for the user's own)")
            },
        ) { a ->
            val project = a.str("project")?.let { name -> tasks.projects(userId).firstOrNull { it.name.equals(name, ignoreCase = true) } }
            val assignee = a.str("assignee")?.let { n -> findMember(n) ?: return@tool unknownMember(n) }
            var list = tasks.list(
                userId, status = a.str("status"), projectId = project?.id, includeDone = a.str("status") == "done",
                dueBefore = a.str("due_before")?.let { runCatching { parseUserDate(it) }.getOrNull() }, assigneeId = assignee?.id,
            )
            a.str("query")?.let { q -> list = tasks.findOpen(userId, q, 10).takeIf { it.isNotEmpty() } ?: list.filter { it.title.contains(q, true) } }
            ToolResult(
                buildJsonObject {
                    put("tasks", buildJsonArray {
                        list.take(40).forEach { task ->
                            add(json {
                                put("id", task.id.toString()); put("title", task.title); put("status", task.status); put("priority", task.priority)
                                task.dueDate?.let { put("due", it.toString()) }; task.projectName?.let { put("project", it) }
                                if (task.shared) put("shared", true)
                                (if (task.assigneeId == userId) "you" else memberName(task.assigneeId))?.let { put("assigned_to", it) }
                                memberName(task.ownerId)?.let { put("created_by", it) }
                            })
                        }
                    })
                    put("total", list.size)
                },
                t(M.TS_TASKS_COUNT, list.size),
                untrusted = OTHERS_ITEMS.takeIf { list.take(40).any { fromOthers(it.ownerId, it.shared, it.source) } },
            )
        }

        tool(
            "update_task", M.TL_UPDATE_TASK,
            "Changes a task: title, status (todo, doing, done), priority, date, project or notes. Use list_tasks to get the id.",
            params {
                string("id", "Task id", required = true)
                string("title", "New title")
                string("status", "New status", enum = listOf("todo", "doing", "done"))
                string("priority", "Priority", enum = listOf("low", "medium", "high", "urgent"))
                string("due_date", "New date YYYY-MM-DD, or 'none' to remove it")
                string("project", "Project")
                string("notes", "Notes (replaces)")
                string("assignee", "Responsible member, or 'none' to remove")
            },
        ) { a ->
            val id = a.uuid("id") ?: return@tool ToolResult.error(t(M.INVALID_ID))
            val due = a.str("due_date")
            val assigneeRaw = a.str("assignee")
            val assignee = assigneeRaw?.takeIf { it != "none" }?.let { n -> findMember(n) ?: return@tool unknownMember(n) }
            val project = a.str("project")?.let { tasks.findOrCreateProject(userId, it) }
            val task = tasks.update(
                userId, id,
                TaskPatch(
                    title = a.str("title"), notes = a.str("notes"), status = a.str("status"), priority = a.str("priority"),
                    dueDate = due?.takeIf { it != "none" }?.let { runCatching { parseUserDate(it) }.getOrNull() },
                    clearDueDate = due == "none", projectId = project?.id,
                    assigneeId = assignee?.id, clearAssignee = assigneeRaw == "none",
                ),
                source,
            ) ?: return@tool ToolResult.error(t(M.TE_TASK_NOT_FOUND))
            ToolResult(
                json { put("id", task.id.toString()); put("status", task.status) },
                t(if (task.status == "done") M.TS_TASK_DONE else M.TS_TASK_UPDATED, task.title),
                entity = EntityRef(EntityTypes.TASK, task.id, task.title),
            )
        }

        tool(
            "complete_task", M.TL_COMPLETE_TASK,
            "Marks a task as done. Accepts the id or a text to find it.",
            params {
                string("id", "Task id")
                string("query", "Task text if you do not have the id")
            },
        ) { a ->
            val id = a.uuid("id") ?: a.str("query")?.let { q ->
                val found = tasks.findOpen(userId, q, 3)
                if (found.size > 1 && found[0].title.lowercase() != q.lowercase()) {
                    return@tool ToolResult(
                        buildJsonObject {
                            put("ambiguous", true)
                            put("candidates", buildJsonArray { found.forEach { add(json { put("id", it.id.toString()); put("title", it.title) }) } })
                        },
                        t(M.TS_TASKS_AMBIGUOUS),
                        untrusted = OTHERS_ITEMS.takeIf { found.any { fromOthers(it.ownerId, it.shared, it.source) } },
                    )
                }
                found.firstOrNull()?.id
            } ?: return@tool ToolResult.error(t(M.TE_TASK_NOT_FOUND))
            val task = tasks.update(userId, id, TaskPatch(status = "done"), source) ?: return@tool ToolResult.error(t(M.TE_TASK_NOT_FOUND))
            ToolResult(json { put("id", task.id.toString()); put("done", true) }, t(M.TS_TASK_DONE, task.title), entity = EntityRef(EntityTypes.TASK, task.id, task.title))
        }

        tool(
            "delete_task", M.TL_DELETE_TASK,
            "Deletes a task (it can be undone). Only if the user asked.",
            params { string("id", "Task id", required = true) },
        ) { a ->
            val id = a.uuid("id") ?: return@tool ToolResult.error(t(M.INVALID_ID))
            val task = tasks.delete(userId, id, source) ?: return@tool ToolResult.error(t(M.TE_TASK_NOT_FOUND))
            ToolResult(json { put("deleted", true) }, t(M.TS_TASK_DELETED, task.title), entity = EntityRef(EntityTypes.TASK, task.id, task.title))
        }
    }

    // ---------------------------------------------------------------- Reminders

    private fun ToolRegistryBuilder.reminderTools() {
        tool(
            "create_reminder", M.TL_CREATE_REMINDER,
            "Creates an alert for a moment (due_at) or when arriving at/leaving a saved place (place). For recurring ones use an RFC 5545 rrule, " +
                "for example FREQ=DAILY, FREQ=WEEKLY;BYDAY=MO,WE, FREQ=MONTHLY;BYMONTHDAY=1. If the user says 'in 20 minutes', compute the exact time.",
            params {
                string("text", "What to remember, worded as a notice (e.g. 'Call the dentist')", required = true)
                string("due_at", "Date and time ISO 8601 with zone, e.g. 2026-10-11T09:00:00-05:00")
                string("rrule", "Recurrence rule without the RRULE: prefix")
                string("place", "Name of a saved place (Home, Work, Supermarket...)")
                string("place_trigger", "When to alert at the place", enum = listOf("arrive", "leave"))
                stringArray("for", "Who to remind: names of family members, or ['everyone']. Without this, the user.")
            },
        ) { a ->
            val text = a.str("text") ?: return@tool ToolResult.error(t(M.TE_MISSING_TEXT))
            val due = a.str("due_at")?.let { runCatching { parseUserDateTime(it, zone) }.getOrElse { e -> return@tool ToolResult.error((e as? InvalidInput)?.text(lang) ?: t(M.INVALID_DATE, it)) } }
            val forNames = a.strList("for").orEmpty().filter { it.isNotBlank() }
            val targets = when {
                forNames.isEmpty() -> listOf(userId)
                forNames.any { Text.normalize(it) in EVERYONE } -> familyMembers.map { it.id }.ifEmpty { listOf(userId) }
                else -> forNames.map { n -> findMember(n)?.id ?: return@tool unknownMember(n) }.distinct()
            }
            val place = a.str("place")?.let { name ->
                places.findByName(userId, name) ?: return@tool ToolResult.error(
                    t(M.TE_PLACE_UNKNOWN, name, places.list(userId).joinToString { Untrusted.clean(it.name, 60) }.ifEmpty { t(M.TE_NONE_M) }),
                )
            }
            if (place != null && targets.any { it != userId } && !place.shared) {
                return@tool ToolResult.error(t(M.TE_PLACE_PERSONAL, place.name))
            }
            if (due == null && place == null) return@tool ToolResult.error(t(M.TE_NEED_DATE_OR_PLACE))
            val rule = a.str("rrule")?.let { Recurrence.normalize(it) ?: return@tool ToolResult.error(t(M.TE_BAD_RRULE, it)) }
            val created = targets.map { target ->
                val tz = if (target == userId) user.timezone else users.byId(target)?.timezone ?: user.timezone
                reminders.create(target, text, due, rule, tz, place?.id, a.str("place_trigger"), source = source, createdBy = userId)
            }
            val r = created.first()
            val whenText = buildString {
                r.nextFireAt?.let { append(humanDateTime(it, zone, lang = lang)) }
                rule?.let { append(" (${Recurrence.describe(it, lang)})") }
                place?.let {
                    if (isNotEmpty()) append(" ")
                    append(t(if (a.str("place_trigger") == "leave") M.WHEN_AT_PLACE_LEAVE else M.WHEN_AT_PLACE_ARRIVE, it.name))
                }
            }.trim()
            val who = targets.mapNotNull { memberName(it) ?: if (targets.size > 1) t(M.DESC_WHO_YOU) else null }
            ToolResult(
                json {
                    put("ids", JsonArray(created.map { JsonPrimitive(it.id.toString()) })); put("when", whenText)
                    if (who.isNotEmpty()) put("for", JsonArray(who.map { JsonPrimitive(it) }))
                },
                if (who.isNotEmpty()) t(M.TS_REMINDER_FOR, who.joinToString(" " + t(M.DESC_AND) + " "), r.text, whenText) else t(M.TS_REMINDER, r.text, whenText),
                entity = EntityRef(EntityTypes.REMINDER, r.id, r.text),
            )
        }

        tool(
            "list_reminders", M.TL_LIST_REMINDERS,
            "Lists pending reminders (scheduled and those that already rang without being marked as done).",
            params { boolean("include_done", "Include done and cancelled") },
        ) { a ->
            val list = reminders.list(userId, if (a.bool("include_done") == true) null else listOf("scheduled", "fired"))
            ToolResult(
                buildJsonObject {
                    put("reminders", buildJsonArray {
                        list.take(40).forEach { r ->
                            add(json {
                                put("id", r.id.toString()); put("text", r.text); put("status", r.status)
                                r.nextFireAt?.let { put("next", humanDateTime(it, zone, lang = lang)) }
                                r.rrule?.let { put("repeats", Recurrence.describe(it, lang)) }
                                r.placeName?.let { put("place", it) }
                                memberName(r.ownerId)?.let { put("for", it) }
                                memberName(r.createdBy)?.let { put("scheduled_by", it) }
                            })
                        }
                    })
                },
                t(M.TS_REMINDERS_COUNT, list.size),
                untrusted = OTHERS_ITEMS.takeIf { list.take(40).any { fromOthers(it) } },
            )
        }

        tool(
            "update_reminder", M.TL_UPDATE_REMINDER,
            "Changes the text, time or recurrence of a reminder. Use list_reminders for the id.",
            params {
                string("id", "Reminder id", required = true)
                string("text", "New text")
                string("due_at", "New date/time ISO 8601")
                string("rrule", "New rule, or 'none' so it does not repeat")
            },
        ) { a ->
            val id = a.uuid("id") ?: return@tool ToolResult.error(t(M.INVALID_ID))
            val due = a.str("due_at")?.let { runCatching { parseUserDateTime(it, zone) }.getOrNull() }
            val rr = a.str("rrule")
            val r = reminders.update(userId, id, ReminderPatch(a.str("text"), due, rr?.takeIf { it != "none" }, clearRrule = rr == "none"), source)
                ?: return@tool ToolResult.error(t(M.TE_REMINDER_NOT_FOUND))
            ToolResult(
                json { put("id", r.id.toString()); r.nextFireAt?.let { put("next", humanDateTime(it, zone, lang = lang)) } },
                t(M.TS_REMINDER_UPDATED, r.text), entity = EntityRef(EntityTypes.REMINDER, r.id, r.text),
            )
        }

        tool(
            "complete_reminder", M.TL_COMPLETE_REMINDER,
            "Marks a reminder as done (if it is recurring, it stays scheduled for the next time).",
            params { string("id", "Reminder id", required = true) },
        ) { a ->
            val id = a.uuid("id") ?: return@tool ToolResult.error(t(M.INVALID_ID))
            val r = reminders.complete(userId, id, source) ?: return@tool ToolResult.error(t(M.TE_REMINDER_NOT_FOUND))
            ToolResult(json { put("id", r.id.toString()); put("status", r.status) }, t(M.TS_REMINDER_DONE, r.text), entity = EntityRef(EntityTypes.REMINDER, r.id, r.text))
        }

        tool(
            "snooze_reminder", M.TL_SNOOZE_REMINDER,
            "Snoozes a reminder for a few minutes.",
            params {
                string("id", "Reminder id", required = true)
                integer("minutes", "Minutes (default 10)")
            },
        ) { a ->
            val id = a.uuid("id") ?: return@tool ToolResult.error(t(M.INVALID_ID))
            val minutes = a.int("minutes") ?: 10
            val r = reminders.snooze(userId, id, minutes, source) ?: return@tool ToolResult.error(t(M.TE_REMINDER_NOT_FOUND))
            ToolResult(
                json { put("id", r.id.toString()); r.nextFireAt?.let { put("next", humanTime(it, zone, lang)) } },
                t(M.TS_REMINDER_SNOOZED, minutes, r.text), entity = EntityRef(EntityTypes.REMINDER, r.id, r.text),
            )
        }

        tool(
            "cancel_reminder", M.TL_CANCEL_REMINDER,
            "Cancels a reminder so it never rings again.",
            params { string("id", "Reminder id", required = true) },
        ) { a ->
            val id = a.uuid("id") ?: return@tool ToolResult.error(t(M.INVALID_ID))
            val r = reminders.cancel(userId, id, source) ?: return@tool ToolResult.error(t(M.TE_REMINDER_NOT_FOUND))
            ToolResult(json { put("cancelled", true) }, t(M.TS_REMINDER_CANCELLED, r.text), entity = EntityRef(EntityTypes.REMINDER, r.id, r.text))
        }

        tool(
            "save_current_place", M.TL_SAVE_PLACE,
            "Saves the phone's current location with a name (Home, Work, Supermarket) for location reminders.",
            params {
                string("name", "Name of the place", required = true)
                integer("radius_m", "Radius in meters (default 150)")
                boolean("shared", "Family place (Home, school): works for everyone's reminders")
            },
        ) { a ->
            val loc = location ?: return@tool ToolResult.error(t(M.TE_NO_LOCATION))
            val name = a.str("name") ?: return@tool ToolResult.error(t(M.TE_MISSING_NAME))
            val p = places.upsert(userId, name, loc.latitude, loc.longitude, (a.int("radius_m") ?: 150).coerceIn(50, 2000), null, source, a.bool("shared"))
            ToolResult(json { put("id", p.id.toString()); put("name", p.name) }, t(M.TS_PLACE_SAVED, p.name), entity = EntityRef(EntityTypes.PLACE, p.id, p.name))
        }
    }

    // ---------------------------------------------------------------- Lists

    private fun ToolRegistryBuilder.listTools() {
        tool(
            "add_to_list", M.TL_ADD_TO_LIST,
            "Adds one or several items to a list (shopping, pharmacy, trip...). Creates the list if it does not exist. Each product is a separate item.",
            params {
                string("list", "Name of the list, e.g. 'Shopping list'", required = true)
                stringArray("items", "Items to add", required = true)
                boolean("shared", "Use or create the family list (the one the whole house sees)")
            },
        ) { a ->
            val name = a.str("list") ?: t(M.DEFAULT_LIST_NAME)
            val items = a.strList("items") ?: return@tool ToolResult.error(t(M.TE_NO_ITEMS))
            val list = lists.findOrCreate(userId, name, source, shared = a.bool("shared") ?: false)
            val updated = lists.addItems(userId, list.id, items, source)!!
            ToolResult(
                json { put("list", updated.name); put("pending", updated.items.count { !it.checked }) },
                t(M.TS_LIST_ADDED, updated.name, items.joinToString(", ")),
                entity = EntityRef(EntityTypes.LIST, updated.id, updated.name),
            )
        }

        tool(
            "get_list", M.TL_GET_LIST,
            "Shows the items of a list. Without a name, it returns all the lists with their pending items.",
            params { string("list", "List name") },
        ) { a ->
            val name = a.str("list")
            val target = name?.let { lists.findByName(userId, it) }
            val all = if (target != null) listOf(target) else lists.all(userId)
            if (name != null && target == null) {
                return@tool ToolResult.error(t(M.TE_LIST_UNKNOWN, name, lists.all(userId).joinToString { Untrusted.clean(it.name, 60) }.ifEmpty { t(M.TE_NONE_F) }))
            }
            ToolResult(
                buildJsonObject {
                    put("lists", buildJsonArray {
                        all.forEach { l ->
                            add(json {
                                put("id", l.id.toString()); put("name", l.name)
                                if (l.shared) put("shared", true)
                                put("pending", JsonArray(l.items.filter { !it.checked }.map { JsonPrimitive(it.text) }))
                                put("checked", JsonArray(l.items.filter { it.checked }.map { JsonPrimitive(it.text) }))
                            })
                        }
                    })
                },
                target?.let { t(M.TS_LIST_PENDING, it.name, it.items.count { i -> !i.checked }) } ?: t(M.TS_LISTS_COUNT, all.size),
                untrusted = OTHERS_LISTS.takeIf { all.any { fromOthers(it.ownerId, it.shared) } },
            )
        }

        tool(
            "check_list_items", M.TL_CHECK_LIST,
            "Checks (or unchecks) items as bought/done.",
            params {
                string("list", "List name", required = true)
                stringArray("items", "Items to check", required = true)
                boolean("checked", "true to check (default), false to uncheck")
            },
        ) { a ->
            val list = lists.findByName(userId, a.str("list") ?: "") ?: return@tool ToolResult.error(t(M.TE_LIST_NOT_FOUND))
            val (updated, missing) = lists.setChecked(userId, list.id, a.strList("items") ?: emptyList(), a.bool("checked") ?: true, source)
            ToolResult(
                json { put("list", list.name); put("not_found", JsonArray(missing.map { JsonPrimitive(it) })); put("pending", updated?.items?.count { !it.checked } ?: 0) },
                t(M.TS_LIST_CHECKED, list.name, (a.strList("items") ?: emptyList()).minus(missing.toSet()).joinToString(", ")),
                entity = EntityRef(EntityTypes.LIST, list.id, list.name),
            )
        }

        tool(
            "remove_from_list", M.TL_REMOVE_FROM_LIST,
            "Removes items from a list. With clear_checked=true it removes all the checked ones.",
            params {
                string("list", "List name", required = true)
                stringArray("items", "Items to remove")
                boolean("clear_checked", "Remove all the checked ones")
            },
        ) { a ->
            val list = lists.findByName(userId, a.str("list") ?: "") ?: return@tool ToolResult.error(t(M.TE_LIST_NOT_FOUND))
            if (a.bool("clear_checked") == true) {
                lists.clearChecked(userId, list.id, source)
                return@tool ToolResult(json { put("cleared", true) }, t(M.TS_LIST_CLEARED, list.name), entity = EntityRef(EntityTypes.LIST, list.id, list.name))
            }
            val (_, missing) = lists.removeItems(userId, list.id, a.strList("items") ?: emptyList(), source)
            ToolResult(json { put("not_found", JsonArray(missing.map { JsonPrimitive(it) })) }, t(M.TS_LIST_REMOVED, list.name), entity = EntityRef(EntityTypes.LIST, list.id, list.name))
        }
    }

    // ---------------------------------------------------------------- Calendar

    private fun ToolRegistryBuilder.calendarTools() {
        tool(
            "create_event", M.TL_CREATE_EVENT,
            "Creates an event in the calendar (appointments, meetings, birthdays). If no duration is given, use 1 hour. For all day use all_day=true.",
            params {
                string("title", "Event title", required = true)
                string("start", "Start ISO 8601 with zone (or YYYY-MM-DD if all day)", required = true)
                string("end", "End ISO 8601 with zone")
                boolean("all_day", "All-day event")
                string("location", "Location")
                string("description", "Description")
                string("rrule", "RFC 5545 recurrence without prefix (e.g. FREQ=YEARLY for birthdays)")
                intArray("reminder_minutes", "Minutes before to alert (e.g. [30, 10])")
                boolean("shared", "Family event: it appears on the whole family's calendar and notifies them")
            },
        ) { a ->
            val title = a.str("title") ?: return@tool ToolResult.error(t(M.TE_MISSING_TITLE))
            val allDay = a.bool("all_day") ?: false
            val startRaw = a.str("start") ?: return@tool ToolResult.error(t(M.TE_MISSING_START))
            val start = runCatching {
                if (allDay) parseUserDate(startRaw).atStartOfDay(zone).toInstant() else parseUserDateTime(startRaw, zone)
            }.getOrElse { return@tool ToolResult.error(t(M.TE_BAD_START)) }
            val end = a.str("end")?.let { runCatching { if (allDay) parseUserDate(it).plusDays(1).atStartOfDay(zone).toInstant() else parseUserDateTime(it, zone) }.getOrNull() }
                ?: if (allDay) start.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant() else start.plusSeconds(3600)
            val e = events.create(
                userId, title, start, end, allDay, user.timezone, a.str("description") ?: "", a.str("location") ?: "",
                a.str("rrule"), a.intList("reminder_minutes") ?: listOf(settings.defaultEventReminderMinutes), source = source,
                shared = a.bool("shared") ?: false,
            )
            val whenText = if (allDay) t(M.WHEN_ALL_DAY, humanDate(start.atZone(zone).toLocalDate(), today(), lang)) else humanDateTime(start, zone, lang = lang)
            ToolResult(
                json { put("id", e.id.toString()); put("when", whenText); put("shared", e.shared) },
                t(if (e.shared) M.TS_EVENT_SHARED else M.TS_EVENT, e.title, whenText),
                entity = EntityRef(EntityTypes.EVENT, e.id, e.title),
            )
        }

        tool(
            "get_agenda", M.TL_GET_AGENDA,
            "Returns events, dated tasks and reminders between two dates. Use it for 'what do I have today/tomorrow/this week?'.",
            params {
                string("from", "From YYYY-MM-DD (default today)")
                string("to", "Until YYYY-MM-DD inclusive (default same as from)")
            },
        ) { a ->
            val from = a.str("from")?.let { runCatching { parseUserDate(it) }.getOrNull() } ?: today()
            val to = a.str("to")?.let { runCatching { parseUserDate(it) }.getOrNull() } ?: from
            val start = from.atStartOfDay(zone).toInstant()
            val end = to.plusDays(1).atStartOfDay(zone).toInstant()
            val evs = events.range(userId, start, end)
            val ts = tasks.list(userId, includeDone = false).filter { task ->
                task.status != "done" && task.dueDate != null && !task.dueDate.isBefore(from) && !task.dueDate.isAfter(to) && (task.assigneeId == null || task.assigneeId == userId)
            }
            val rs = reminders.list(userId, listOf("scheduled")).filter { r -> r.nextFireAt != null && r.nextFireAt >= start && r.nextFireAt < end }
            ToolResult(
                buildJsonObject {
                    put("range", if (from == to) humanDate(from, today(), lang) else "${humanDate(from, today(), lang)} to ${humanDate(to, today(), lang)}")
                    put("events", buildJsonArray {
                        evs.forEach { e ->
                            add(json {
                                put("id", e.id.toString()); put("title", e.title)
                                put("when", if (e.allDay) t(M.WHEN_ALL_DAY, humanDate(e.startAt.atZone(zone).toLocalDate(), today(), lang)) else humanDateTime(e.startAt, zone, lang = lang) + " to " + humanTime(e.endAt, zone, lang))
                                if (e.location.isNotBlank()) put("location", e.location)
                                if (e.shared) put("shared", true)
                                memberName(e.ownerId)?.let { put("created_by", it) }
                            })
                        }
                    })
                    put("tasks", buildJsonArray { ts.forEach { add(json { put("id", it.id.toString()); put("title", it.title); put("due", humanDate(it.dueDate!!, today(), lang)); put("priority", it.priority) }) } })
                    put("reminders", buildJsonArray { rs.forEach { add(json { put("id", it.id.toString()); put("text", it.text); put("at", humanDateTime(it.nextFireAt!!, zone, lang = lang)) }) } })
                },
                t(M.TS_AGENDA, evs.size, ts.size, rs.size),
                untrusted = OTHERS_ITEMS.takeIf {
                    evs.any { fromOthers(it.ownerId, it.shared, it.source) } || ts.any { fromOthers(it.ownerId, it.shared, it.source) } || rs.any { fromOthers(it) }
                },
            )
        }

        tool(
            "update_event", M.TL_UPDATE_EVENT,
            "Modifies an event (use get_agenda for the id). If you only change the start, the duration is kept.",
            params {
                string("id", "Event id", required = true)
                string("title", "New title")
                string("start", "New start ISO 8601")
                string("end", "New end ISO 8601")
                string("location", "Location")
                string("description", "Description")
            },
        ) { a ->
            val id = a.uuid("id") ?: return@tool ToolResult.error(t(M.INVALID_ID))
            val e = events.update(
                userId, id,
                EventPatch(
                    title = a.str("title"), location = a.str("location"), description = a.str("description"),
                    startAt = a.str("start")?.let { runCatching { parseUserDateTime(it, zone) }.getOrNull() },
                    endAt = a.str("end")?.let { runCatching { parseUserDateTime(it, zone) }.getOrNull() },
                ),
                source,
            ) ?: return@tool ToolResult.error(t(M.TE_EVENT_NOT_FOUND))
            ToolResult(
                json { put("id", e.id.toString()); put("when", humanDateTime(e.startAt, zone, lang = lang)) },
                t(M.TS_EVENT, e.title, humanDateTime(e.startAt, zone, lang = lang)), entity = EntityRef(EntityTypes.EVENT, e.id, e.title),
            )
        }

        tool(
            "delete_event", M.TL_DELETE_EVENT,
            "Deletes an event from the calendar (it can be undone). Only if the user asked.",
            params { string("id", "Event id", required = true) },
        ) { a ->
            val id = a.uuid("id") ?: return@tool ToolResult.error(t(M.INVALID_ID))
            val e = events.delete(userId, id, source) ?: return@tool ToolResult.error(t(M.TE_EVENT_NOT_FOUND))
            ToolResult(json { put("deleted", true) }, t(M.TS_EVENT_DELETED, e.title), entity = EntityRef(EntityTypes.EVENT, e.id, e.title))
        }
    }

    // ---------------------------------------------------------------- Memory

    private fun ToolRegistryBuilder.memoryTools() {
        tool(
            "remember", M.TL_REMEMBER,
            "Saves a lasting fact about the user to use in the future (preferences, people, health information, work). " +
                "Word it in the third person and self-contained, e.g. 'Her daughter is called Sofía and was born on May 3'. Do not store passwords here.",
            params {
                string("fact", "The fact to remember", required = true)
                // The category ids are stored values (part of the API), so they keep their names
                string("category", "Category", enum = listOf("perfil", "preferencias", "personas", "salud", "trabajo", "hogar", "general"))
            },
        ) { a ->
            val fact = a.str("fact") ?: return@tool ToolResult.error(t(M.TE_MISSING_FACT))
            val m = memories.create(userId, fact, a.str("category") ?: "general", source)
            ToolResult(json { put("id", m.id.toString()) }, t(M.TS_REMEMBER, m.content), entity = EntityRef(EntityTypes.MEMORY, m.id, m.content.take(60)))
        }

        tool(
            "recall", M.TL_RECALL,
            "Looks up facts the user asked you to remember.",
            params { string("query", "What to look for", required = true) },
        ) { a ->
            val found = memories.search(userId, a.str("query") ?: "")
            ToolResult(
                buildJsonObject { put("memories", buildJsonArray { found.forEach { add(json { put("id", it.id.toString()); put("fact", it.content); put("category", it.category) }) } }) },
                t(M.TS_RECALL_COUNT, found.size),
            )
        }

        tool(
            "forget", M.TL_FORGET,
            "Erases a fact from memory at the user's request.",
            params { string("id", "Memory id (use recall)", required = true) },
        ) { a ->
            val id = a.uuid("id") ?: return@tool ToolResult.error(t(M.INVALID_ID))
            val m = memories.delete(userId, id, source) ?: return@tool ToolResult.error(t(M.TE_MEMORY_NOT_FOUND))
            ToolResult(json { put("forgotten", true) }, t(M.TS_FORGOTTEN, m.content), entity = EntityRef(EntityTypes.MEMORY, m.id, m.content.take(60)))
        }
    }

    // ---------------------------------------------------------------- Family

    private fun ToolRegistryBuilder.familyTools() {
        tool(
            "share", M.TL_SHARE,
            "Shares with the family (or stops sharing) something that already exists: a note, task or event by id, or a list or place by name.",
            params {
                string("type", "What it is", required = true, enum = listOf("note", "task", "event", "list", "place"))
                string("id", "Id (notes, tasks and events)")
                string("name", "Name (lists and places)")
                boolean("shared", "true to share, false to make it personal", required = true)
            },
        ) { a ->
            val shared = a.bool("shared") ?: true
            val verb = if (shared) M.TS_SHARED else M.TS_UNSHARED
            runCatching {
                when (a.str("type")) {
                    "note" -> notes.update(userId, a.uuid("id") ?: throw InvalidInput(M.TE_MISSING_ID), NotePatch(shared = shared), source)
                        ?.let { ToolResult(json { put("ok", true) }, t(verb, it.title), entity = EntityRef(EntityTypes.NOTE, it.id, it.title)) }
                    "task" -> tasks.update(userId, a.uuid("id") ?: throw InvalidInput(M.TE_MISSING_ID), TaskPatch(shared = shared), source)
                        ?.let { ToolResult(json { put("ok", true) }, t(verb, it.title), entity = EntityRef(EntityTypes.TASK, it.id, it.title)) }
                    "event" -> events.update(userId, a.uuid("id") ?: throw InvalidInput(M.TE_MISSING_ID), EventPatch(shared = shared), source)
                        ?.let { ToolResult(json { put("ok", true) }, t(verb, it.title), entity = EntityRef(EntityTypes.EVENT, it.id, it.title)) }
                    "list" -> {
                        val l = a.uuid("id")?.let { lists.get(userId, it) } ?: lists.findByName(userId, a.str("name") ?: "", preferShared = !shared)
                        l?.let { lists.rename(userId, it.id, null, null, shared) }
                            ?.let { ToolResult(json { put("ok", true) }, t(verb, it.name), entity = EntityRef(EntityTypes.LIST, it.id, it.name)) }
                    }
                    "place" -> places.findByName(userId, a.str("name") ?: "")?.let { places.setShared(userId, it.id, shared) }
                        ?.let { ToolResult(json { put("ok", true) }, t(verb, it.name), entity = EntityRef(EntityTypes.PLACE, it.id, it.name)) }
                    else -> null
                }
            }.getOrElse { e -> return@tool ToolResult.error((e as? app.kairo.util.Localizable)?.text(lang) ?: e.message ?: t(M.TE_SHARE_FAILED)) }
                ?: ToolResult.error(t(M.TE_NOT_FOUND_GENERIC))
        }

        tool(
            "message_family", M.TL_MESSAGE_FAMILY,
            "Sends a notice to family members on their phone ('tell Valentina I'm running late', 'tell everyone dinner is ready').",
            params {
                string("text", "The message, worded for whoever receives it", required = true)
                stringArray("to", "Names of the recipients; empty or ['everyone'] for the whole family")
            },
        ) { a ->
            val text = a.str("text")?.trim()?.take(500)?.ifEmpty { null } ?: return@tool ToolResult.error(t(M.TE_MISSING_MESSAGE))
            val names = a.strList("to").orEmpty().filter { it.isNotBlank() && Text.normalize(it) !in EVERYONE }
            val to = if (names.isEmpty()) familyMembers.map { it.id } - userId else names.map { n -> findMember(n)?.id ?: return@tool unknownMember(n) } - userId
            if (to.isEmpty()) return@tool ToolResult.error(t(M.TE_NO_OTHER_MEMBERS))
            notifier.family(userId, to, NoticeKinds.MESSAGE, NoticeText(notifier.who(userId), Plain(text)))
            val who = to.mapNotNull { memberName(it) }
            ToolResult(json { put("sent", to.size) }, t(M.TS_NOTICE_SENT, who.joinToString(" " + t(M.DESC_AND) + " "), text))
        }
    }

    private fun ToolContext.unknownMember(name: String): ToolResult = ToolResult.error(
        if (familyMembers.size <= 1) t(M.TE_NO_ONE_ELSE)
        else t(M.TE_MEMBER_UNKNOWN, name, familyMembers.joinToString { Untrusted.clean(it.displayName, 60) }),
    )

    // ---------------------------------------------------------------- Device

    private fun ToolRegistryBuilder.deviceTools() {
        tool(
            "set_timer", M.TL_SET_TIMER,
            "Starts a timer on the phone.",
            params {
                integer("seconds", "Duration in seconds", required = true)
                string("label", "Label (e.g. 'Pasta')")
            },
        ) { a ->
            if ("timer" !in capabilities) return@tool ToolResult.error(t(M.TE_TIMER_DEVICE_ONLY))
            val secs = (a.int("seconds") ?: 0).coerceIn(1, 24 * 3600)
            val label = a.str("label") ?: "Kairo"
            ToolResult(
                json { put("ok", true) },
                t(M.TS_TIMER, formatDuration(secs)),
                deviceAction = json { put("type", "timer"); put("seconds", secs); put("label", label) },
            )
        }

        tool(
            "set_alarm", M.TL_SET_ALARM,
            "Sets an alarm on the phone's clock.",
            params {
                string("time", "Time HH:MM (24 h)", required = true)
                string("label", "Label")
                stringArray("days", "Days to repeat: mon,tue,wed,thu,fri,sat,sun")
            },
        ) { a ->
            if ("alarm" !in capabilities) return@tool ToolResult.error(t(M.TE_ALARM_DEVICE_ONLY))
            val tm = runCatching { LocalTime.parse((a.str("time") ?: "").padStart(5, '0')) }.getOrElse { return@tool ToolResult.error(t(M.TE_BAD_TIME)) }
            ToolResult(
                json { put("ok", true) },
                t(M.TS_ALARM, tm.toString().take(5)),
                deviceAction = json {
                    put("type", "alarm"); put("hour", tm.hour); put("minute", tm.minute); put("label", a.str("label") ?: "Kairo")
                    a.strList("days")?.let { put("days", JsonArray(it.map { d -> JsonPrimitive(d) })) }
                },
            )
        }
    }

    // ---------------------------------------------------------------- Information

    private fun ToolRegistryBuilder.infoTools() {
        tool(
            "get_daily_briefing", M.TL_BRIEFING,
            "Returns the overview of the day: weather, events, overdue and today's tasks, reminders and pending lists.",
            params { string("date", "Date YYYY-MM-DD (default today)") },
        ) { a ->
            val date = a.str("date")?.let { runCatching { parseUserDate(it) }.getOrNull() }
            val d = briefing.today(user, date)
            ToolResult(
                json {
                    put("date", d.dateLabel)
                    d.weather?.let { w -> put("weather", "${w.place}: ${w.description}, ${w.temperature.toInt()}°" + (w.days.firstOrNull()?.let { ", high ${it.max.toInt()}°, low ${it.min.toInt()}°, rain ${it.rainChance ?: 0}%" } ?: "")) }
                    put("events", JsonArray(d.events.map { JsonPrimitive(if (it.allDay) "${it.title} (all day)" else "${humanTime(it.startAt, zone, lang)} ${it.title}") }))
                    put("tasks_today", JsonArray(d.tasksDueToday.map { JsonPrimitive(it.title) }))
                    put("tasks_overdue", JsonArray(d.tasksOverdue.map { JsonPrimitive(it.title) }))
                    put("in_progress", JsonArray(d.tasksInProgress.map { JsonPrimitive(it.title) }))
                    put("reminders", JsonArray(d.reminders.map { JsonPrimitive("${it.nextFireAt?.let { tm -> humanTime(tm, zone, lang) } ?: ""} ${it.text}".trim()) }))
                    put("lists", JsonArray(d.lists.map { JsonPrimitive("${it.name}: ${it.items.count { i -> !i.checked }} pending") }))
                },
                t(M.TS_BRIEFING, d.dateLabel.lowercase()),
                untrusted = OTHERS_ITEMS.takeIf {
                    d.events.any { fromOthers(it.ownerId, it.shared, it.source) } ||
                        (d.tasksDueToday + d.tasksOverdue + d.tasksInProgress).any { fromOthers(it.ownerId, it.shared, it.source) } ||
                        d.reminders.any { fromOthers(it) } || d.lists.any { fromOthers(it.ownerId, it.shared) }
                },
            )
        }

        tool(
            "get_weather", M.TL_WEATHER,
            "Weather forecast. Without a city, it uses the user's configured city.",
            params {
                string("city", "City (optional)")
                integer("days", "Forecast days 1-7")
            },
        ) { a ->
            val city = a.str("city")
            val (lat, lon, name) = if (city != null) {
                val g = weather.geocode(city, lang).firstOrNull() ?: return@tool ToolResult.error(t(M.TE_CITY_NOT_FOUND, city))
                Triple(g.latitude, g.longitude, g.name)
            } else {
                if (settings.homeLatitude == null || settings.homeLongitude == null) {
                    if (location != null) Triple(location.latitude, location.longitude, t(M.YOUR_LOCATION))
                    else return@tool ToolResult.error(t(M.TE_NO_HOME_CITY))
                } else Triple(settings.homeLatitude, settings.homeLongitude, settings.homeCity ?: t(M.DEFAULT_HOME_NAME))
            }
            val w = weather.forecast(lat, lon, name, a.int("days") ?: 3, lang) ?: return@tool ToolResult.error(t(M.TE_WEATHER_DOWN))
            ToolResult(
                json {
                    put("place", w.place); put("now", "${w.description}, ${w.temperature.toInt()}°")
                    w.feelsLike?.let { put("feels_like", it.toInt()) }
                    put("days", buildJsonArray { w.days.forEach { d -> add(json { put("date", humanDate(LocalDate.parse(d.date), today(), lang)); put("min", d.min.toInt()); put("max", d.max.toInt()); put("rain", d.rainChance ?: 0); put("sky", d.description) }) } })
                },
                t(M.TS_WEATHER, w.place, w.temperature.toInt(), w.description),
            )
        }

        tool(
            "search_everything", M.TL_SEARCH_ALL,
            "Searches notes, tasks, reminders, events, lists and memory at once.",
            params { string("query", "What to look for", required = true) },
        ) { a ->
            val q = a.str("query") ?: ""
            val ns = notes.search(userId, q, 4)
            val ts = tasks.findOpen(userId, q, 5)
            val ms = memories.search(userId, q, 4).filter { (it.score ?: 0.0) > 0.4 }
            val now = Instant.now()
            val es = events.range(userId, now.minusSeconds(30L * 86400), now.plusSeconds(180L * 86400)).filter { it.title.contains(q, true) || it.description.contains(q, true) }.take(5)
            val rs = reminders.list(userId).filter { it.text.contains(q, true) }.take(5)
            val ls = lists.all(userId).filter { l -> l.name.contains(q, true) || l.items.any { it.text.contains(q, true) } }.take(3)
            ToolResult(
                buildJsonObject {
                    put("notes", buildJsonArray { ns.forEach { add(json { put("id", it.id.toString()); put("title", it.title); put("content", it.content.take(500)) }) } })
                    put("tasks", buildJsonArray { ts.forEach { add(json { put("id", it.id.toString()); put("title", it.title); put("status", it.status) }) } })
                    put("events", buildJsonArray { es.forEach { add(json { put("id", it.id.toString()); put("title", it.title); put("when", humanDateTime(it.startAt, zone, lang = lang)) }) } })
                    put("reminders", buildJsonArray { rs.forEach { add(json { put("id", it.id.toString()); put("text", it.text); put("status", it.status) }) } })
                    put("lists", buildJsonArray { ls.forEach { add(json { put("name", it.name); put("items", JsonArray(it.items.map { i -> JsonPrimitive(i.text) })) }) } })
                    put("memories", buildJsonArray { ms.forEach { add(json { put("id", it.id.toString()); put("fact", it.content) }) } })
                },
                t(M.TS_SEARCH, q),
                untrusted = OTHERS_ITEMS.takeIf {
                    ns.any { fromOthers(it.ownerId, it.shared, it.source) } || ts.any { fromOthers(it.ownerId, it.shared, it.source) } ||
                        es.any { fromOthers(it.ownerId, it.shared, it.source) } || rs.any { fromOthers(it) } || ls.any { fromOthers(it.ownerId, it.shared) }
                },
            )
        }

        tool(
            "generate_image", M.TL_GENERATE_IMAGE,
            "Generates an image from a description (illustrations, visual ideas). Describe the image in English in detail for better quality.",
            params {
                string("prompt", "Detailed description of the image", required = true)
                boolean("save_to_notes", "Save in a note")
            },
        ) { a ->
            val prompt = a.str("prompt") ?: return@tool ToolResult.error(t(M.TE_MISSING_DESCRIPTION))
            val url = nan.generateImage(prompt, settings.imageModel)
            val (bytes, type) = if (url.startsWith("data:")) {
                java.util.Base64.getDecoder().decode(url.substringAfter(",")) to "image/png"
            } else nan.download(url)
            val fileId = files.store(userId, bytes, type, "image.png")
            val ref = files.ref(fileId, type)
            if (a.bool("save_to_notes") == true) {
                notes.create(userId, t(M.IMAGE_NOTE_TITLE, prompt.take(50)), prompt, listOf(t(M.IMAGE_NOTE_TAG)), source = source, attachments = listOf(fileId))
            }
            ToolResult(json { put("ok", true); put("shown_to_user", true) }, t(M.TS_IMAGE), attachment = ref)
        }
    }

    // ---------------------------------------------------------------- confirmations

    /**
     * Facts about the element an action would touch (owner, whether it is a family one, title, recipients) so
     * [ToolPolicy] can decide whether the person's confirmation is needed. It resolves the target the same way the tool does.
     */
    suspend fun facts(ctx: ToolContext, name: String, a: JsonObject): ActionFacts = with(ctx) {
        val base = ActionFacts(name, a, userId)
        fun recipients(ids: Collection<UUID>) = base.copy(recipients = ids.toSet(), recipientNames = ids.distinct().map { memberName(it) ?: t(M.DESC_WHO_YOU) })
        when (name) {
            "update_note", "delete_note" -> a.uuid("id")?.let { notes.get(userId, it) }
                ?.let { base.copy(targetOwner = it.ownerId, targetShared = it.shared, targetTitle = it.title) } ?: base
            "update_task", "delete_task", "complete_task" -> {
                val task = a.uuid("id")?.let { tasks.get(userId, it) }
                    ?: a.str("query")?.takeIf { name == "complete_task" }?.let { tasks.findOpen(userId, it, 3).firstOrNull() }
                val assignee = a.str("assignee")?.takeIf { name == "update_task" && it != "none" }?.let { findMember(it) }
                val withTarget = task?.let { base.copy(targetOwner = it.ownerId, targetShared = it.shared, targetTitle = it.title) } ?: base
                if (assignee != null) withTarget.copy(recipients = setOf(assignee.id), recipientNames = listOf(memberName(assignee.id) ?: t(M.DESC_WHO_YOU))) else withTarget
            }
            "update_event", "delete_event" -> a.uuid("id")?.let { events.get(userId, it) }
                ?.let { base.copy(targetOwner = it.ownerId, targetShared = it.shared, targetTitle = it.title) } ?: base
            "update_reminder", "complete_reminder", "snooze_reminder", "cancel_reminder" -> a.uuid("id")?.let { reminders.get(userId, it) }
                ?.let { base.copy(targetOwner = it.ownerId, targetTitle = it.text) } ?: base
            "forget" -> a.uuid("id")?.let { memories.get(userId, it) }?.let { base.copy(targetOwner = userId, targetTitle = it.content) } ?: base
            "add_to_list" -> lists.findByName(userId, a.str("list") ?: t(M.DEFAULT_LIST_NAME), preferShared = true)
                ?.let { base.copy(targetOwner = it.ownerId, targetShared = it.shared, targetTitle = it.name) } ?: base
            "check_list_items", "remove_from_list" -> lists.findByName(userId, a.str("list") ?: "")
                ?.let { base.copy(targetOwner = it.ownerId, targetShared = it.shared, targetTitle = it.name) } ?: base
            "save_current_place" -> a.str("name")?.let { n -> places.findByName(userId, n)?.takeIf { Text.normalize(it.name) == Text.normalize(n) } }
                ?.let { base.copy(targetOwner = it.ownerId, targetShared = it.shared, targetTitle = it.name) } ?: base
            "share" -> {
                val shared = a.bool("shared") ?: true
                when (a.str("type")) {
                    "note" -> a.uuid("id")?.let { notes.get(userId, it) }?.let { base.copy(targetOwner = it.ownerId, targetShared = it.shared, targetTitle = it.title) }
                    "task" -> a.uuid("id")?.let { tasks.get(userId, it) }?.let { base.copy(targetOwner = it.ownerId, targetShared = it.shared, targetTitle = it.title) }
                    "event" -> a.uuid("id")?.let { events.get(userId, it) }?.let { base.copy(targetOwner = it.ownerId, targetShared = it.shared, targetTitle = it.title) }
                    "list" -> (a.uuid("id")?.let { lists.get(userId, it) } ?: lists.findByName(userId, a.str("name") ?: "", preferShared = !shared))
                        ?.let { base.copy(targetOwner = it.ownerId, targetShared = it.shared, targetTitle = it.name) }
                    "place" -> places.findByName(userId, a.str("name") ?: "")?.let { base.copy(targetOwner = it.ownerId, targetShared = it.shared, targetTitle = it.name) }
                    else -> null
                } ?: base
            }
            "create_reminder" -> {
                val names = a.strList("for").orEmpty().filter { it.isNotBlank() }
                recipients(
                    when {
                        names.isEmpty() -> listOf(userId)
                        names.any { Text.normalize(it) in EVERYONE } -> familyMembers.map { it.id }.ifEmpty { listOf(userId) }
                        else -> names.mapNotNull { findMember(it)?.id }
                    },
                )
            }
            "create_task" -> a.str("assignee")?.let { findMember(it) }?.let { recipients(listOf(it.id)) } ?: base
            "message_family" -> {
                val names = a.strList("to").orEmpty().filter { it.isNotBlank() && Text.normalize(it) !in EVERYONE }
                recipients(if (names.isEmpty()) familyMembers.map { it.id } - userId else names.mapNotNull { findMember(it)?.id } - userId)
            }
            else -> base
        }
    }

    // ---------------------------------------------------------------- utilities

    /** A reminder may have been written by someone else if it is for someone else or another member set it. */
    private fun ToolContext.fromOthers(r: app.kairo.domain.Reminder): Boolean =
        fromOthers(r.ownerId, false, r.source) || (r.createdBy != null && r.createdBy != userId)

    private fun ToolContext.today(): LocalDate = Instant.now().atZone(zone).toLocalDate()

    private fun JsonObject.uuid(key: String): UUID? = str(key)?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    private inline fun json(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject(block)

    private fun formatDuration(secs: Int): String {
        val h = secs / 3600
        val m = (secs % 3600) / 60
        val s = secs % 60
        return listOfNotNull(h.takeIf { it > 0 }?.let { "$it h" }, m.takeIf { it > 0 }?.let { "$it min" }, s.takeIf { it > 0 && h == 0 }?.let { "$it s" }).joinToString(" ")
    }

    private companion object {
        /** The ways of saying "everyone" (English and Spanish), normalized with [Text.normalize]. */
        val EVERYONE = setOf(
            "todos", "todas", "todo el mundo", "la familia", "familia",
            "everyone", "everybody", "all", "all of us", "us", "family", "the family", "whole family", "the whole family",
        )
        // Reasons the model reads when a result carries other people's content
        const val OTHERS_NOTES = "family notes, someone else's, or shared from outside"
        const val OTHERS_LISTS = "family lists or someone else's"
        const val OTHERS_ITEMS = "family items, someone else's, or external"
    }
}
