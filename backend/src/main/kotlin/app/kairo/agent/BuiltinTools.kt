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
import app.kairo.services.Notifier
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

    // ---------------------------------------------------------------- Notas

    private fun ToolRegistryBuilder.noteTools() {
        tool(
            "create_note", "Guardando nota",
            "Guarda una nota. Úsala para apuntar ideas, datos, claves, ideas de regalo, etc. Si el usuario dicta varias cosas relacionadas, ponlas en una sola nota.",
            params {
                string("title", "Título corto (opcional, se deduce del contenido)")
                string("content", "Contenido completo de la nota, en markdown simple", required = true)
                stringArray("tags", "Etiquetas en minúsculas sin #")
                boolean("pinned", "Fijar arriba")
                boolean("shared", "Nota familiar: la ve toda la familia (wifi de la casa, datos del colegio...)")
            },
        ) { a ->
            val content = a.str("content") ?: return@tool ToolResult.error("Falta el contenido de la nota")
            val attach = pendingAttachments.toList().also { pendingAttachments.clear() }
            val shared = a.bool("shared") ?: false
            val n = notes.create(userId, a.str("title") ?: "", content, a.strList("tags") ?: emptyList(), a.bool("pinned") ?: false, source, attach, shared)
            ToolResult(
                json { put("id", n.id.toString()); put("title", n.title); put("shared", n.shared) },
                (if (n.shared) "Nota familiar: " else "Nota guardada: ") + n.title,
                entity = EntityRef(EntityTypes.NOTE, n.id, n.title),
            )
        }

        tool(
            "search_notes", "Buscando en tus notas",
            "Busca en las notas del usuario por significado y por texto. Úsala antes de responder preguntas sobre información que el usuario guardó.",
            params {
                string("query", "Qué buscar, en lenguaje natural", required = true)
                integer("limit", "Máximo de resultados (por defecto 5)")
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
                                put("tags", JsonArray(n.tags.map { JsonPrimitive(it) })); put("updated", humanDateTime(n.updatedAt, zone))
                                if (n.shared) put("familiar", true)
                                memberName(n.ownerId)?.let { put("de", it) }
                            })
                        }
                    })
                },
                if (found.isEmpty()) "Sin notas para \"$q\"" else "${found.size} nota(s) encontradas",
                untrusted = OTHERS_NOTES.takeIf { found.any { fromOthers(it.ownerId, it.shared, it.source) } },
            )
        }

        tool(
            "update_note", "Editando nota",
            "Modifica una nota existente (usa search_notes para obtener el id). Con `append` agrega texto al final sin borrar lo anterior.",
            params {
                string("id", "Id de la nota", required = true)
                string("title", "Nuevo título")
                string("content", "Contenido nuevo completo (reemplaza)")
                string("append", "Texto a añadir al final")
                stringArray("tags", "Etiquetas nuevas")
                boolean("pinned", "Fijar o desfijar")
            },
        ) { a ->
            val id = a.uuid("id") ?: return@tool ToolResult.error("Id de nota inválido")
            val current = notes.get(userId, id) ?: return@tool ToolResult.error("No encontré esa nota")
            val content = a.str("content") ?: a.str("append")?.let { current.content.trimEnd() + "\n" + it }
            val n = notes.update(userId, id, NotePatch(a.str("title"), content, a.strList("tags"), a.bool("pinned")), source)!!
            ToolResult(json { put("id", n.id.toString()); put("title", n.title) }, "Nota actualizada: ${n.title}", entity = EntityRef(EntityTypes.NOTE, n.id, n.title))
        }

        tool(
            "delete_note", "Eliminando nota",
            "Elimina una nota (se puede deshacer desde Actividad). Solo si el usuario lo pidió explícitamente.",
            params { string("id", "Id de la nota", required = true) },
        ) { a ->
            val id = a.uuid("id") ?: return@tool ToolResult.error("Id inválido")
            val n = notes.delete(userId, id, source) ?: return@tool ToolResult.error("No encontré esa nota")
            ToolResult(json { put("deleted", true) }, "Nota eliminada: ${n.title}", entity = EntityRef(EntityTypes.NOTE, n.id, n.title))
        }
    }

    // ---------------------------------------------------------------- Tareas

    private fun ToolRegistryBuilder.taskTools() {
        tool(
            "create_task", "Creando tarea",
            "Crea una tarea en el organizador (cosas por hacer). Para avisos a una hora usa create_reminder; puedes usar ambas si el usuario quiere tarea y aviso.",
            params {
                string("title", "Qué hay que hacer, en infinitivo o frase corta", required = true)
                string("notes", "Detalles")
                string("due_date", "Fecha límite YYYY-MM-DD")
                string("due_time", "Hora límite HH:MM (opcional)")
                string("priority", "Prioridad", enum = listOf("low", "medium", "high", "urgent"))
                string("project", "Nombre del proyecto o área (se crea si no existe)")
                string("status", "Estado inicial", enum = listOf("todo", "doing"))
                boolean("shared", "Tarea familiar, visible para todos")
                string("assignee", "Nombre del miembro de la familia que debe hacerla (la vuelve familiar)")
            },
        ) { a ->
            val title = a.str("title") ?: return@tool ToolResult.error("Falta el título")
            val date = a.str("due_date")?.let { runCatching { parseUserDate(it) }.getOrNull() }
            val dueAt = if (date != null) a.str("due_time")?.let { t -> runCatching { date.atTime(LocalTime.parse(t.padStart(5, '0'))).atZone(zone).toInstant() }.getOrNull() } else null
            val assignee = a.str("assignee")?.let { n -> findMember(n) ?: return@tool unknownMember(n) }
            val project = a.str("project")?.let { tasks.findOrCreateProject(userId, it) }
            val t = tasks.create(
                userId, title, a.str("notes") ?: "", a.str("priority") ?: "medium", date, dueAt, project?.id,
                status = a.str("status") ?: "todo", source = source, shared = a.bool("shared") ?: false, assigneeId = assignee?.id,
            )
            val who = memberName(t.assigneeId)
            ToolResult(
                json {
                    put("id", t.id.toString()); put("title", t.title); t.dueDate?.let { put("due", humanDate(it, today())) }
                    put("shared", t.shared); who?.let { put("assignee", it) }
                },
                "Tarea" + (who?.let { " para $it" } ?: if (t.shared) " familiar" else "") + ": ${t.title}" + (t.dueDate?.let { " · ${humanDate(it, today())}" } ?: ""),
                entity = EntityRef(EntityTypes.TASK, t.id, t.title),
            )
        }

        tool(
            "list_tasks", "Revisando tus tareas",
            "Lista tareas abiertas (y opcionalmente las hechas recientemente). Filtra por proyecto, estado o vencimiento.",
            params {
                string("status", "Filtrar por estado", enum = listOf("todo", "doing", "done"))
                string("project", "Nombre del proyecto")
                string("due_before", "Solo con fecha límite hasta YYYY-MM-DD")
                string("query", "Texto para buscar en el título")
                string("assignee", "Solo las asignadas a este miembro ('yo' para las del usuario)")
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
                        list.take(40).forEach { t ->
                            add(json {
                                put("id", t.id.toString()); put("title", t.title); put("status", t.status); put("priority", t.priority)
                                t.dueDate?.let { put("due", it.toString()) }; t.projectName?.let { put("project", it) }
                                if (t.shared) put("familiar", true)
                                (if (t.assigneeId == userId) "tú" else memberName(t.assigneeId))?.let { put("asignada_a", it) }
                                memberName(t.ownerId)?.let { put("creada_por", it) }
                            })
                        }
                    })
                    put("total", list.size)
                },
                "${list.size} tarea(s)",
                untrusted = OTHERS_ITEMS.takeIf { list.take(40).any { fromOthers(it.ownerId, it.shared, it.source) } },
            )
        }

        tool(
            "update_task", "Actualizando tarea",
            "Cambia una tarea: título, estado (todo, doing, done), prioridad, fecha, proyecto o notas. Usa list_tasks para obtener el id.",
            params {
                string("id", "Id de la tarea", required = true)
                string("title", "Nuevo título")
                string("status", "Nuevo estado", enum = listOf("todo", "doing", "done"))
                string("priority", "Prioridad", enum = listOf("low", "medium", "high", "urgent"))
                string("due_date", "Nueva fecha YYYY-MM-DD, o 'none' para quitarla")
                string("project", "Proyecto")
                string("notes", "Notas (reemplaza)")
                string("assignee", "Miembro responsable, o 'none' para quitarlo")
            },
        ) { a ->
            val id = a.uuid("id") ?: return@tool ToolResult.error("Id inválido")
            val due = a.str("due_date")
            val assigneeRaw = a.str("assignee")
            val assignee = assigneeRaw?.takeIf { it != "none" }?.let { n -> findMember(n) ?: return@tool unknownMember(n) }
            val project = a.str("project")?.let { tasks.findOrCreateProject(userId, it) }
            val t = tasks.update(
                userId, id,
                TaskPatch(
                    title = a.str("title"), notes = a.str("notes"), status = a.str("status"), priority = a.str("priority"),
                    dueDate = due?.takeIf { it != "none" }?.let { runCatching { parseUserDate(it) }.getOrNull() },
                    clearDueDate = due == "none", projectId = project?.id,
                    assigneeId = assignee?.id, clearAssignee = assigneeRaw == "none",
                ),
                source,
            ) ?: return@tool ToolResult.error("No encontré esa tarea")
            ToolResult(json { put("id", t.id.toString()); put("status", t.status) }, if (t.status == "done") "Completada: ${t.title}" else "Tarea actualizada: ${t.title}", entity = EntityRef(EntityTypes.TASK, t.id, t.title))
        }

        tool(
            "complete_task", "Completando tarea",
            "Marca una tarea como hecha. Acepta el id o un texto para encontrarla.",
            params {
                string("id", "Id de la tarea")
                string("query", "Texto de la tarea si no tienes el id")
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
                        "Varias tareas coinciden",
                        untrusted = OTHERS_ITEMS.takeIf { found.any { fromOthers(it.ownerId, it.shared, it.source) } },
                    )
                }
                found.firstOrNull()?.id
            } ?: return@tool ToolResult.error("No encontré esa tarea")
            val t = tasks.update(userId, id, TaskPatch(status = "done"), source) ?: return@tool ToolResult.error("No encontré esa tarea")
            ToolResult(json { put("id", t.id.toString()); put("done", true) }, "Completada: ${t.title}", entity = EntityRef(EntityTypes.TASK, t.id, t.title))
        }

        tool(
            "delete_task", "Eliminando tarea",
            "Elimina una tarea (se puede deshacer). Solo si el usuario lo pidió.",
            params { string("id", "Id de la tarea", required = true) },
        ) { a ->
            val id = a.uuid("id") ?: return@tool ToolResult.error("Id inválido")
            val t = tasks.delete(userId, id, source) ?: return@tool ToolResult.error("No encontré esa tarea")
            ToolResult(json { put("deleted", true) }, "Tarea eliminada: ${t.title}", entity = EntityRef(EntityTypes.TASK, t.id, t.title))
        }
    }

    // ---------------------------------------------------------------- Recordatorios

    private fun ToolRegistryBuilder.reminderTools() {
        tool(
            "create_reminder", "Programando recordatorio",
            "Crea un aviso para un momento (due_at) o al llegar/salir de un lugar guardado (place). Para recurrentes usa rrule RFC 5545, " +
                "por ejemplo FREQ=DAILY, FREQ=WEEKLY;BYDAY=MO,WE, FREQ=MONTHLY;BYMONTHDAY=1. Si el usuario dice 'en 20 minutos', calcula la hora exacta.",
            params {
                string("text", "Qué recordar, redactado como aviso (ej. 'Llamar al dentista')", required = true)
                string("due_at", "Fecha y hora ISO 8601 con zona, ej. 2026-10-11T09:00:00-05:00")
                string("rrule", "Regla de recurrencia sin el prefijo RRULE:")
                string("place", "Nombre de un lugar guardado (Casa, Trabajo, Súper...)")
                string("place_trigger", "Cuándo avisar en el lugar", enum = listOf("arrive", "leave"))
                stringArray("for", "A quién recordarle: nombres de miembros de la familia, o ['todos']. Sin esto, al usuario.")
            },
        ) { a ->
            val text = a.str("text") ?: return@tool ToolResult.error("Falta el texto")
            val due = a.str("due_at")?.let { runCatching { parseUserDateTime(it, zone) }.getOrElse { e -> return@tool ToolResult.error(e.message ?: "Fecha inválida") } }
            val forNames = a.strList("for").orEmpty().filter { it.isNotBlank() }
            val targets = when {
                forNames.isEmpty() -> listOf(userId)
                forNames.any { Text.normalize(it) in EVERYONE } -> familyMembers.map { it.id }.ifEmpty { listOf(userId) }
                else -> forNames.map { n -> findMember(n)?.id ?: return@tool unknownMember(n) }.distinct()
            }
            val place = a.str("place")?.let { name ->
                places.findByName(userId, name) ?: return@tool ToolResult.error(
                    "No tengo guardado el lugar '$name'. Lugares: ${places.list(userId).joinToString { Untrusted.clean(it.name, 60) }.ifEmpty { "ninguno" }}. " +
                        "El usuario puede guardarlo diciendo 'guarda este lugar como $name' estando allí, o desde la app.",
                )
            }
            if (place != null && targets.any { it != userId } && !place.shared) {
                return@tool ToolResult.error("El lugar '${place.name}' es personal; para avisar a otros allí, primero compártelo con la familia (herramienta share).")
            }
            if (due == null && place == null) return@tool ToolResult.error("Necesito una fecha/hora o un lugar para el recordatorio")
            val rule = a.str("rrule")?.let { Recurrence.normalize(it) ?: return@tool ToolResult.error("Regla de recurrencia inválida: $it") }
            val created = targets.map { target ->
                val tz = if (target == userId) user.timezone else users.byId(target)?.timezone ?: user.timezone
                reminders.create(target, text, due, rule, tz, place?.id, a.str("place_trigger"), source = source, createdBy = userId)
            }
            val r = created.first()
            val whenText = buildString {
                r.nextFireAt?.let { append(humanDateTime(it, zone)) }
                rule?.let { append(" (${Recurrence.describe(it)})") }
                place?.let { append(if (a.str("place_trigger") == "leave") "al salir de ${it.name}" else "al llegar a ${it.name}") }
            }.trim()
            val who = targets.mapNotNull { memberName(it) ?: if (targets.size > 1) "ti" else null }
            ToolResult(
                json {
                    put("ids", JsonArray(created.map { JsonPrimitive(it.id.toString()) })); put("when", whenText)
                    if (who.isNotEmpty()) put("para", JsonArray(who.map { JsonPrimitive(it) }))
                },
                "Recordatorio" + (if (who.isNotEmpty()) " para ${who.joinToString(" y ")}" else "") + ": ${r.text} · $whenText",
                entity = EntityRef(EntityTypes.REMINDER, r.id, r.text),
            )
        }

        tool(
            "list_reminders", "Revisando recordatorios",
            "Lista los recordatorios pendientes (programados y los que ya sonaron sin marcarse como hechos).",
            params { boolean("include_done", "Incluir hechos y cancelados") },
        ) { a ->
            val list = reminders.list(userId, if (a.bool("include_done") == true) null else listOf("scheduled", "fired"))
            ToolResult(
                buildJsonObject {
                    put("reminders", buildJsonArray {
                        list.take(40).forEach { r ->
                            add(json {
                                put("id", r.id.toString()); put("text", r.text); put("status", r.status)
                                r.nextFireAt?.let { put("next", humanDateTime(it, zone)) }
                                r.rrule?.let { put("repeats", Recurrence.describe(it)) }
                                r.placeName?.let { put("place", it) }
                                memberName(r.ownerId)?.let { put("para", it) }
                                memberName(r.createdBy)?.let { put("programado_por", it) }
                            })
                        }
                    })
                },
                "${list.size} recordatorio(s)",
                untrusted = OTHERS_ITEMS.takeIf { list.take(40).any { fromOthers(it) } },
            )
        }

        tool(
            "update_reminder", "Cambiando recordatorio",
            "Cambia el texto, la hora o la recurrencia de un recordatorio. Usa list_reminders para el id.",
            params {
                string("id", "Id del recordatorio", required = true)
                string("text", "Nuevo texto")
                string("due_at", "Nueva fecha/hora ISO 8601")
                string("rrule", "Nueva regla, o 'none' para que no se repita")
            },
        ) { a ->
            val id = a.uuid("id") ?: return@tool ToolResult.error("Id inválido")
            val due = a.str("due_at")?.let { runCatching { parseUserDateTime(it, zone) }.getOrNull() }
            val rr = a.str("rrule")
            val r = reminders.update(userId, id, ReminderPatch(a.str("text"), due, rr?.takeIf { it != "none" }, clearRrule = rr == "none"), source)
                ?: return@tool ToolResult.error("No encontré ese recordatorio")
            ToolResult(json { put("id", r.id.toString()); r.nextFireAt?.let { put("next", humanDateTime(it, zone)) } }, "Recordatorio actualizado: ${r.text}", entity = EntityRef(EntityTypes.REMINDER, r.id, r.text))
        }

        tool(
            "complete_reminder", "Marcando como hecho",
            "Marca un recordatorio como hecho (si es recurrente, queda programado para la próxima vez).",
            params { string("id", "Id del recordatorio", required = true) },
        ) { a ->
            val id = a.uuid("id") ?: return@tool ToolResult.error("Id inválido")
            val r = reminders.complete(userId, id, source) ?: return@tool ToolResult.error("No encontré ese recordatorio")
            ToolResult(json { put("id", r.id.toString()); put("status", r.status) }, "Hecho: ${r.text}", entity = EntityRef(EntityTypes.REMINDER, r.id, r.text))
        }

        tool(
            "snooze_reminder", "Posponiendo",
            "Pospone un recordatorio unos minutos.",
            params {
                string("id", "Id del recordatorio", required = true)
                integer("minutes", "Minutos (por defecto 10)")
            },
        ) { a ->
            val id = a.uuid("id") ?: return@tool ToolResult.error("Id inválido")
            val minutes = a.int("minutes") ?: 10
            val r = reminders.snooze(userId, id, minutes, source) ?: return@tool ToolResult.error("No encontré ese recordatorio")
            ToolResult(json { put("id", r.id.toString()); r.nextFireAt?.let { put("next", humanTime(it, zone)) } }, "Pospuesto $minutes min: ${r.text}", entity = EntityRef(EntityTypes.REMINDER, r.id, r.text))
        }

        tool(
            "cancel_reminder", "Cancelando recordatorio",
            "Cancela un recordatorio para que no vuelva a sonar.",
            params { string("id", "Id del recordatorio", required = true) },
        ) { a ->
            val id = a.uuid("id") ?: return@tool ToolResult.error("Id inválido")
            val r = reminders.cancel(userId, id, source) ?: return@tool ToolResult.error("No encontré ese recordatorio")
            ToolResult(json { put("cancelled", true) }, "Cancelado: ${r.text}", entity = EntityRef(EntityTypes.REMINDER, r.id, r.text))
        }

        tool(
            "save_current_place", "Guardando lugar",
            "Guarda la ubicación actual del teléfono con un nombre (Casa, Trabajo, Súper) para recordatorios por ubicación.",
            params {
                string("name", "Nombre del lugar", required = true)
                integer("radius_m", "Radio en metros (por defecto 150)")
                boolean("shared", "Lugar familiar (Casa, el colegio): sirve para recordatorios de todos")
            },
        ) { a ->
            val loc = location ?: return@tool ToolResult.error("No tengo tu ubicación. Abre Kairo en el teléfono con la ubicación activada, o guarda el lugar desde Ajustes > Lugares.")
            val name = a.str("name") ?: return@tool ToolResult.error("Falta el nombre")
            val p = places.upsert(userId, name, loc.latitude, loc.longitude, (a.int("radius_m") ?: 150).coerceIn(50, 2000), null, source, a.bool("shared"))
            ToolResult(json { put("id", p.id.toString()); put("name", p.name) }, "Lugar guardado: ${p.name}", entity = EntityRef(EntityTypes.PLACE, p.id, p.name))
        }
    }

    // ---------------------------------------------------------------- Listas

    private fun ToolRegistryBuilder.listTools() {
        tool(
            "add_to_list", "Agregando a la lista",
            "Agrega uno o varios elementos a una lista (súper, farmacia, viaje...). Crea la lista si no existe. Cada producto es un elemento separado.",
            params {
                string("list", "Nombre de la lista, ej. 'Supermercado'", required = true)
                stringArray("items", "Elementos a agregar", required = true)
                boolean("shared", "Usar o crear la lista familiar (la que ve toda la casa)")
            },
        ) { a ->
            val name = a.str("list") ?: "Supermercado"
            val items = a.strList("items") ?: return@tool ToolResult.error("No hay elementos")
            val list = lists.findOrCreate(userId, name, source, shared = a.bool("shared") ?: false)
            val updated = lists.addItems(userId, list.id, items, source)!!
            ToolResult(
                json { put("list", updated.name); put("pending", updated.items.count { !it.checked }) },
                "${updated.name}: + ${items.joinToString(", ")}",
                entity = EntityRef(EntityTypes.LIST, updated.id, updated.name),
            )
        }

        tool(
            "get_list", "Leyendo la lista",
            "Muestra los elementos de una lista. Sin nombre, devuelve todas las listas con sus pendientes.",
            params { string("list", "Nombre de la lista") },
        ) { a ->
            val name = a.str("list")
            val target = name?.let { lists.findByName(userId, it) }
            val all = if (target != null) listOf(target) else lists.all(userId)
            if (name != null && target == null) return@tool ToolResult.error("No tengo una lista llamada '$name'. Listas: ${lists.all(userId).joinToString { Untrusted.clean(it.name, 60) }.ifEmpty { "ninguna" }}")
            ToolResult(
                buildJsonObject {
                    put("lists", buildJsonArray {
                        all.forEach { l ->
                            add(json {
                                put("id", l.id.toString()); put("name", l.name)
                                if (l.shared) put("familiar", true)
                                put("pending", JsonArray(l.items.filter { !it.checked }.map { JsonPrimitive(it.text) }))
                                put("checked", JsonArray(l.items.filter { it.checked }.map { JsonPrimitive(it.text) }))
                            })
                        }
                    })
                },
                target?.let { "${it.name}: ${it.items.count { i -> !i.checked }} pendiente(s)" } ?: "${all.size} lista(s)",
                untrusted = OTHERS_LISTS.takeIf { all.any { fromOthers(it.ownerId, it.shared) } },
            )
        }

        tool(
            "check_list_items", "Marcando en la lista",
            "Marca (o desmarca) elementos como comprados/hechos.",
            params {
                string("list", "Nombre de la lista", required = true)
                stringArray("items", "Elementos a marcar", required = true)
                boolean("checked", "true para marcar (por defecto), false para desmarcar")
            },
        ) { a ->
            val list = lists.findByName(userId, a.str("list") ?: "") ?: return@tool ToolResult.error("No encontré esa lista")
            val (updated, missing) = lists.setChecked(userId, list.id, a.strList("items") ?: emptyList(), a.bool("checked") ?: true, source)
            ToolResult(
                json { put("list", list.name); put("not_found", JsonArray(missing.map { JsonPrimitive(it) })); put("pending", updated?.items?.count { !it.checked } ?: 0) },
                "${list.name}: marcado ${(a.strList("items") ?: emptyList()).minus(missing.toSet()).joinToString(", ")}",
                entity = EntityRef(EntityTypes.LIST, list.id, list.name),
            )
        }

        tool(
            "remove_from_list", "Quitando de la lista",
            "Quita elementos de una lista. Con clear_checked=true elimina todos los ya marcados.",
            params {
                string("list", "Nombre de la lista", required = true)
                stringArray("items", "Elementos a quitar")
                boolean("clear_checked", "Quitar todos los marcados")
            },
        ) { a ->
            val list = lists.findByName(userId, a.str("list") ?: "") ?: return@tool ToolResult.error("No encontré esa lista")
            if (a.bool("clear_checked") == true) {
                lists.clearChecked(userId, list.id, source)
                return@tool ToolResult(json { put("cleared", true) }, "${list.name}: limpiados los marcados", entity = EntityRef(EntityTypes.LIST, list.id, list.name))
            }
            val (_, missing) = lists.removeItems(userId, list.id, a.strList("items") ?: emptyList(), source)
            ToolResult(json { put("not_found", JsonArray(missing.map { JsonPrimitive(it) })) }, "${list.name}: elementos quitados", entity = EntityRef(EntityTypes.LIST, list.id, list.name))
        }
    }

    // ---------------------------------------------------------------- Calendario

    private fun ToolRegistryBuilder.calendarTools() {
        tool(
            "create_event", "Agendando evento",
            "Crea un evento en el calendario (citas, reuniones, cumpleaños). Si no dicen duración, usa 1 hora. Para todo el día usa all_day=true.",
            params {
                string("title", "Título del evento", required = true)
                string("start", "Inicio ISO 8601 con zona (o YYYY-MM-DD si es todo el día)", required = true)
                string("end", "Fin ISO 8601 con zona")
                boolean("all_day", "Evento de todo el día")
                string("location", "Lugar")
                string("description", "Descripción")
                string("rrule", "Recurrencia RFC 5545 sin prefijo (ej. FREQ=YEARLY para cumpleaños)")
                intArray("reminder_minutes", "Minutos antes para avisar (ej. [30, 10])")
                boolean("shared", "Evento familiar: aparece en el calendario de toda la familia y les avisa")
            },
        ) { a ->
            val title = a.str("title") ?: return@tool ToolResult.error("Falta el título")
            val allDay = a.bool("all_day") ?: false
            val startRaw = a.str("start") ?: return@tool ToolResult.error("Falta el inicio")
            val start = runCatching {
                if (allDay) parseUserDate(startRaw).atStartOfDay(zone).toInstant() else parseUserDateTime(startRaw, zone)
            }.getOrElse { return@tool ToolResult.error("Fecha de inicio inválida") }
            val end = a.str("end")?.let { runCatching { if (allDay) parseUserDate(it).plusDays(1).atStartOfDay(zone).toInstant() else parseUserDateTime(it, zone) }.getOrNull() }
                ?: if (allDay) start.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant() else start.plusSeconds(3600)
            val e = events.create(
                userId, title, start, end, allDay, user.timezone, a.str("description") ?: "", a.str("location") ?: "",
                a.str("rrule"), a.intList("reminder_minutes") ?: listOf(settings.defaultEventReminderMinutes), source = source,
                shared = a.bool("shared") ?: false,
            )
            val whenText = if (allDay) humanDate(start.atZone(zone).toLocalDate(), today()) + " (todo el día)" else humanDateTime(start, zone)
            ToolResult(
                json { put("id", e.id.toString()); put("when", whenText); put("shared", e.shared) },
                (if (e.shared) "Evento familiar: " else "Evento: ") + "${e.title} · $whenText",
                entity = EntityRef(EntityTypes.EVENT, e.id, e.title),
            )
        }

        tool(
            "get_agenda", "Revisando tu agenda",
            "Devuelve eventos, tareas con fecha y recordatorios entre dos fechas. Úsala para '¿qué tengo hoy/mañana/esta semana?'.",
            params {
                string("from", "Desde YYYY-MM-DD (por defecto hoy)")
                string("to", "Hasta YYYY-MM-DD inclusive (por defecto igual a from)")
            },
        ) { a ->
            val from = a.str("from")?.let { runCatching { parseUserDate(it) }.getOrNull() } ?: today()
            val to = a.str("to")?.let { runCatching { parseUserDate(it) }.getOrNull() } ?: from
            val start = from.atStartOfDay(zone).toInstant()
            val end = to.plusDays(1).atStartOfDay(zone).toInstant()
            val evs = events.range(userId, start, end)
            val ts = tasks.list(userId, includeDone = false).filter { t ->
                t.status != "done" && t.dueDate != null && !t.dueDate.isBefore(from) && !t.dueDate.isAfter(to) && (t.assigneeId == null || t.assigneeId == userId)
            }
            val rs = reminders.list(userId, listOf("scheduled")).filter { r -> r.nextFireAt != null && r.nextFireAt >= start && r.nextFireAt < end }
            ToolResult(
                buildJsonObject {
                    put("range", if (from == to) humanDate(from, today()) else "${humanDate(from, today())} a ${humanDate(to, today())}")
                    put("events", buildJsonArray {
                        evs.forEach { e ->
                            add(json {
                                put("id", e.id.toString()); put("title", e.title)
                                put("when", if (e.allDay) humanDate(e.startAt.atZone(zone).toLocalDate(), today()) + " todo el día" else humanDateTime(e.startAt, zone) + " a " + humanTime(e.endAt, zone))
                                if (e.location.isNotBlank()) put("location", e.location)
                                if (e.shared) put("familiar", true)
                                memberName(e.ownerId)?.let { put("agendado_por", it) }
                            })
                        }
                    })
                    put("tasks", buildJsonArray { ts.forEach { add(json { put("id", it.id.toString()); put("title", it.title); put("due", humanDate(it.dueDate!!, today())); put("priority", it.priority) }) } })
                    put("reminders", buildJsonArray { rs.forEach { add(json { put("id", it.id.toString()); put("text", it.text); put("at", humanDateTime(it.nextFireAt!!, zone)) }) } })
                },
                "${evs.size} evento(s), ${ts.size} tarea(s), ${rs.size} recordatorio(s)",
                untrusted = OTHERS_ITEMS.takeIf {
                    evs.any { fromOthers(it.ownerId, it.shared, it.source) } || ts.any { fromOthers(it.ownerId, it.shared, it.source) } || rs.any { fromOthers(it) }
                },
            )
        }

        tool(
            "update_event", "Moviendo evento",
            "Modifica un evento (usa get_agenda para el id). Si cambias solo el inicio, se conserva la duración.",
            params {
                string("id", "Id del evento", required = true)
                string("title", "Nuevo título")
                string("start", "Nuevo inicio ISO 8601")
                string("end", "Nuevo fin ISO 8601")
                string("location", "Lugar")
                string("description", "Descripción")
            },
        ) { a ->
            val id = a.uuid("id") ?: return@tool ToolResult.error("Id inválido")
            val e = events.update(
                userId, id,
                EventPatch(
                    title = a.str("title"), location = a.str("location"), description = a.str("description"),
                    startAt = a.str("start")?.let { runCatching { parseUserDateTime(it, zone) }.getOrNull() },
                    endAt = a.str("end")?.let { runCatching { parseUserDateTime(it, zone) }.getOrNull() },
                ),
                source,
            ) ?: return@tool ToolResult.error("No encontré ese evento")
            ToolResult(json { put("id", e.id.toString()); put("when", humanDateTime(e.startAt, zone)) }, "Evento: ${e.title} · ${humanDateTime(e.startAt, zone)}", entity = EntityRef(EntityTypes.EVENT, e.id, e.title))
        }

        tool(
            "delete_event", "Eliminando evento",
            "Elimina un evento del calendario (se puede deshacer). Solo si el usuario lo pidió.",
            params { string("id", "Id del evento", required = true) },
        ) { a ->
            val id = a.uuid("id") ?: return@tool ToolResult.error("Id inválido")
            val e = events.delete(userId, id, source) ?: return@tool ToolResult.error("No encontré ese evento")
            ToolResult(json { put("deleted", true) }, "Evento eliminado: ${e.title}", entity = EntityRef(EntityTypes.EVENT, e.id, e.title))
        }
    }

    // ---------------------------------------------------------------- Memoria

    private fun ToolRegistryBuilder.memoryTools() {
        tool(
            "remember", "Recordando",
            "Guarda un dato duradero sobre el usuario para usarlo en el futuro (preferencias, personas, datos de salud, trabajo). " +
                "Redáctalo en tercera persona y autocontenido, ej. 'Su hija se llama Sofía y nació el 3 de mayo'. No guardes contraseñas aquí.",
            params {
                string("fact", "El dato a recordar", required = true)
                string("category", "Categoría", enum = listOf("perfil", "preferencias", "personas", "salud", "trabajo", "hogar", "general"))
            },
        ) { a ->
            val fact = a.str("fact") ?: return@tool ToolResult.error("Falta el dato")
            val m = memories.create(userId, fact, a.str("category") ?: "general", source)
            ToolResult(json { put("id", m.id.toString()) }, "Recordaré: ${m.content}", entity = EntityRef(EntityTypes.MEMORY, m.id, m.content.take(60)))
        }

        tool(
            "recall", "Buscando en tu memoria",
            "Busca datos que el usuario te pidió recordar.",
            params { string("query", "Qué buscar", required = true) },
        ) { a ->
            val found = memories.search(userId, a.str("query") ?: "")
            ToolResult(
                buildJsonObject { put("memories", buildJsonArray { found.forEach { add(json { put("id", it.id.toString()); put("fact", it.content); put("category", it.category) }) } }) },
                "${found.size} recuerdo(s)",
            )
        }

        tool(
            "forget", "Olvidando",
            "Borra un dato de la memoria a pedido del usuario.",
            params { string("id", "Id del recuerdo (usa recall)", required = true) },
        ) { a ->
            val id = a.uuid("id") ?: return@tool ToolResult.error("Id inválido")
            val m = memories.delete(userId, id, source) ?: return@tool ToolResult.error("No encontré ese recuerdo")
            ToolResult(json { put("forgotten", true) }, "Olvidado: ${m.content}", entity = EntityRef(EntityTypes.MEMORY, m.id, m.content.take(60)))
        }
    }

    // ---------------------------------------------------------------- Familia

    private fun ToolRegistryBuilder.familyTools() {
        tool(
            "share", "Compartiendo con la familia",
            "Comparte con la familia (o deja de compartir) algo que ya existe: una nota, tarea o evento por id, o una lista o lugar por nombre.",
            params {
                string("type", "Qué es", required = true, enum = listOf("note", "task", "event", "list", "place"))
                string("id", "Id (notas, tareas y eventos)")
                string("name", "Nombre (listas y lugares)")
                boolean("shared", "true para compartir, false para volverlo personal", required = true)
            },
        ) { a ->
            val shared = a.bool("shared") ?: true
            val verb = if (shared) "Compartido con la familia" else "Ahora es personal"
            runCatching {
                when (a.str("type")) {
                    "note" -> notes.update(userId, a.uuid("id") ?: error("Falta el id"), NotePatch(shared = shared), source)
                        ?.let { ToolResult(json { put("ok", true) }, "$verb: ${it.title}", entity = EntityRef(EntityTypes.NOTE, it.id, it.title)) }
                    "task" -> tasks.update(userId, a.uuid("id") ?: error("Falta el id"), TaskPatch(shared = shared), source)
                        ?.let { ToolResult(json { put("ok", true) }, "$verb: ${it.title}", entity = EntityRef(EntityTypes.TASK, it.id, it.title)) }
                    "event" -> events.update(userId, a.uuid("id") ?: error("Falta el id"), EventPatch(shared = shared), source)
                        ?.let { ToolResult(json { put("ok", true) }, "$verb: ${it.title}", entity = EntityRef(EntityTypes.EVENT, it.id, it.title)) }
                    "list" -> {
                        val l = a.uuid("id")?.let { lists.get(userId, it) } ?: lists.findByName(userId, a.str("name") ?: "", preferShared = !shared)
                        l?.let { lists.rename(userId, it.id, null, null, shared) }
                            ?.let { ToolResult(json { put("ok", true) }, "$verb: ${it.name}", entity = EntityRef(EntityTypes.LIST, it.id, it.name)) }
                    }
                    "place" -> places.findByName(userId, a.str("name") ?: "")?.let { places.setShared(userId, it.id, shared) }
                        ?.let { ToolResult(json { put("ok", true) }, "$verb: ${it.name}", entity = EntityRef(EntityTypes.PLACE, it.id, it.name)) }
                    else -> null
                }
            }.getOrElse { return@tool ToolResult.error(it.message ?: "No se pudo cambiar") } ?: ToolResult.error("No lo encontré")
        }

        tool(
            "message_family", "Avisando a la familia",
            "Envía un aviso a miembros de la familia en su teléfono ('avísale a Valentina que llego tarde', 'diles a todos que la cena está lista').",
            params {
                string("text", "El mensaje, redactado para quien lo recibe", required = true)
                stringArray("to", "Nombres de los destinatarios; vacío o ['todos'] para toda la familia")
            },
        ) { a ->
            val text = a.str("text")?.trim()?.take(500)?.ifEmpty { null } ?: return@tool ToolResult.error("Falta el mensaje")
            val names = a.strList("to").orEmpty().filter { it.isNotBlank() && Text.normalize(it) !in EVERYONE }
            val to = if (names.isEmpty()) familyMembers.map { it.id } - userId else names.map { n -> findMember(n)?.id ?: return@tool unknownMember(n) } - userId
            if (to.isEmpty()) return@tool ToolResult.error("No hay otros miembros en la familia. Se invitan desde Ajustes > Familia.")
            notifier.family(userId, to, NoticeKinds.MESSAGE, notifier.firstName(userId), text)
            val who = to.mapNotNull { memberName(it) }
            ToolResult(json { put("sent", to.size) }, "Aviso a ${who.joinToString(" y ")}: $text")
        }
    }

    private fun ToolContext.unknownMember(name: String): ToolResult = ToolResult.error(
        if (familyMembers.size <= 1) "No hay nadie más en tu familia todavía. Se invita desde Ajustes > Familia en el dashboard."
        else "No encontré a '$name' en la familia. Miembros: ${familyMembers.joinToString { Untrusted.clean(it.displayName, 60) }}.",
    )

    // ---------------------------------------------------------------- Dispositivo

    private fun ToolRegistryBuilder.deviceTools() {
        tool(
            "set_timer", "Poniendo temporizador",
            "Inicia un temporizador en el teléfono.",
            params {
                integer("seconds", "Duración en segundos", required = true)
                string("label", "Etiqueta (ej. 'Pasta')")
            },
        ) { a ->
            if ("timer" !in capabilities) return@tool ToolResult.error("Los temporizadores solo funcionan desde la app del teléfono o el reloj")
            val secs = (a.int("seconds") ?: 0).coerceIn(1, 24 * 3600)
            val label = a.str("label") ?: "Kairo"
            ToolResult(
                json { put("ok", true) },
                "Temporizador: ${formatDuration(secs)}",
                deviceAction = json { put("type", "timer"); put("seconds", secs); put("label", label) },
            )
        }

        tool(
            "set_alarm", "Poniendo alarma",
            "Programa una alarma en el reloj del teléfono.",
            params {
                string("time", "Hora HH:MM (24 h)", required = true)
                string("label", "Etiqueta")
                stringArray("days", "Días para repetir: mon,tue,wed,thu,fri,sat,sun")
            },
        ) { a ->
            if ("alarm" !in capabilities) return@tool ToolResult.error("Las alarmas solo funcionan desde la app del teléfono")
            val t = runCatching { LocalTime.parse((a.str("time") ?: "").padStart(5, '0')) }.getOrElse { return@tool ToolResult.error("Hora inválida") }
            ToolResult(
                json { put("ok", true) },
                "Alarma: ${t.toString().take(5)}",
                deviceAction = json {
                    put("type", "alarm"); put("hour", t.hour); put("minute", t.minute); put("label", a.str("label") ?: "Kairo")
                    a.strList("days")?.let { put("days", JsonArray(it.map { d -> JsonPrimitive(d) })) }
                },
            )
        }
    }

    // ---------------------------------------------------------------- Información

    private fun ToolRegistryBuilder.infoTools() {
        tool(
            "get_daily_briefing", "Preparando tu resumen",
            "Devuelve el panorama del día: clima, eventos, tareas vencidas y de hoy, recordatorios y listas pendientes.",
            params { string("date", "Fecha YYYY-MM-DD (por defecto hoy)") },
        ) { a ->
            val date = a.str("date")?.let { runCatching { parseUserDate(it) }.getOrNull() }
            val d = briefing.today(user, date)
            ToolResult(
                json {
                    put("date", d.dateLabel)
                    d.weather?.let { w -> put("weather", "${w.place}: ${w.description}, ${w.temperature.toInt()}°" + (w.days.firstOrNull()?.let { ", máx ${it.max.toInt()}°, mín ${it.min.toInt()}°, lluvia ${it.rainChance ?: 0}%" } ?: "")) }
                    put("events", JsonArray(d.events.map { JsonPrimitive(if (it.allDay) "${it.title} (todo el día)" else "${humanTime(it.startAt, zone)} ${it.title}") }))
                    put("tasks_today", JsonArray(d.tasksDueToday.map { JsonPrimitive(it.title) }))
                    put("tasks_overdue", JsonArray(d.tasksOverdue.map { JsonPrimitive(it.title) }))
                    put("in_progress", JsonArray(d.tasksInProgress.map { JsonPrimitive(it.title) }))
                    put("reminders", JsonArray(d.reminders.map { JsonPrimitive("${it.nextFireAt?.let { t -> humanTime(t, zone) } ?: ""} ${it.text}".trim()) }))
                    put("lists", JsonArray(d.lists.map { JsonPrimitive("${it.name}: ${it.items.count { i -> !i.checked }} pendientes") }))
                },
                "Resumen de ${d.dateLabel.lowercase()}",
                untrusted = OTHERS_ITEMS.takeIf {
                    d.events.any { fromOthers(it.ownerId, it.shared, it.source) } ||
                        (d.tasksDueToday + d.tasksOverdue + d.tasksInProgress).any { fromOthers(it.ownerId, it.shared, it.source) } ||
                        d.reminders.any { fromOthers(it) } || d.lists.any { fromOthers(it.ownerId, it.shared) }
                },
            )
        }

        tool(
            "get_weather", "Consultando el clima",
            "Pronóstico del clima. Sin ciudad, usa la ciudad configurada del usuario.",
            params {
                string("city", "Ciudad (opcional)")
                integer("days", "Días de pronóstico 1-7")
            },
        ) { a ->
            val city = a.str("city")
            val (lat, lon, name) = if (city != null) {
                val g = weather.geocode(city).firstOrNull() ?: return@tool ToolResult.error("No encontré la ciudad $city")
                Triple(g.latitude, g.longitude, g.name)
            } else {
                if (settings.homeLatitude == null || settings.homeLongitude == null) {
                    if (location != null) Triple(location.latitude, location.longitude, "tu ubicación")
                    else return@tool ToolResult.error("No tengo tu ciudad configurada. Pídele al usuario que la configure en Ajustes o que diga la ciudad.")
                } else Triple(settings.homeLatitude, settings.homeLongitude, settings.homeCity ?: "Casa")
            }
            val w = weather.forecast(lat, lon, name, a.int("days") ?: 3) ?: return@tool ToolResult.error("El servicio de clima no respondió")
            ToolResult(
                json {
                    put("place", w.place); put("now", "${w.description}, ${w.temperature.toInt()}°")
                    w.feelsLike?.let { put("feels_like", it.toInt()) }
                    put("days", buildJsonArray { w.days.forEach { d -> add(json { put("date", humanDate(LocalDate.parse(d.date), today())); put("min", d.min.toInt()); put("max", d.max.toInt()); put("rain", d.rainChance ?: 0); put("sky", d.description) }) } })
                },
                "Clima en ${w.place}: ${w.temperature.toInt()}°, ${w.description}",
            )
        }

        tool(
            "search_everything", "Buscando",
            "Busca en notas, tareas, recordatorios, eventos, listas y memoria a la vez.",
            params { string("query", "Qué buscar", required = true) },
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
                    put("events", buildJsonArray { es.forEach { add(json { put("id", it.id.toString()); put("title", it.title); put("when", humanDateTime(it.startAt, zone)) }) } })
                    put("reminders", buildJsonArray { rs.forEach { add(json { put("id", it.id.toString()); put("text", it.text); put("status", it.status) }) } })
                    put("lists", buildJsonArray { ls.forEach { add(json { put("name", it.name); put("items", JsonArray(it.items.map { i -> JsonPrimitive(i.text) })) }) } })
                    put("memories", buildJsonArray { ms.forEach { add(json { put("id", it.id.toString()); put("fact", it.content) }) } })
                },
                "Resultados para \"$q\"",
                untrusted = OTHERS_ITEMS.takeIf {
                    ns.any { fromOthers(it.ownerId, it.shared, it.source) } || ts.any { fromOthers(it.ownerId, it.shared, it.source) } ||
                        es.any { fromOthers(it.ownerId, it.shared, it.source) } || rs.any { fromOthers(it) } || ls.any { fromOthers(it.ownerId, it.shared) }
                },
            )
        }

        tool(
            "generate_image", "Creando imagen",
            "Genera una imagen a partir de una descripción (ilustraciones, ideas visuales). Describe la imagen en inglés con detalle para mejor calidad.",
            params {
                string("prompt", "Descripción detallada de la imagen", required = true)
                boolean("save_to_notes", "Guardar en una nota")
            },
        ) { a ->
            val prompt = a.str("prompt") ?: return@tool ToolResult.error("Falta la descripción")
            val url = nan.generateImage(prompt, settings.imageModel)
            val (bytes, type) = if (url.startsWith("data:")) {
                java.util.Base64.getDecoder().decode(url.substringAfter(",")) to "image/png"
            } else nan.download(url)
            val fileId = files.store(userId, bytes, type, "imagen.png")
            val ref = files.ref(fileId, type)
            if (a.bool("save_to_notes") == true) notes.create(userId, "Imagen: ${prompt.take(50)}", prompt, listOf("imagen"), source = source, attachments = listOf(fileId))
            ToolResult(json { put("ok", true); put("shown_to_user", true) }, "Imagen creada", attachment = ref)
        }
    }

    // ---------------------------------------------------------------- confirmaciones

    /**
     * Datos del elemento que tocaría una acción (dueño, si es familiar, título, destinatarios) para que
     * [ToolPolicy] decida si hace falta la confirmación de la persona. Resuelve el objetivo igual que la herramienta.
     */
    suspend fun facts(ctx: ToolContext, name: String, a: JsonObject): ActionFacts = with(ctx) {
        val base = ActionFacts(name, a, userId)
        fun recipients(ids: Collection<UUID>) = base.copy(recipients = ids.toSet(), recipientNames = ids.distinct().map { memberName(it) ?: "ti" })
        when (name) {
            "update_note", "delete_note" -> a.uuid("id")?.let { notes.get(userId, it) }
                ?.let { base.copy(targetOwner = it.ownerId, targetShared = it.shared, targetTitle = it.title) } ?: base
            "update_task", "delete_task", "complete_task" -> {
                val t = a.uuid("id")?.let { tasks.get(userId, it) }
                    ?: a.str("query")?.takeIf { name == "complete_task" }?.let { tasks.findOpen(userId, it, 3).firstOrNull() }
                val assignee = a.str("assignee")?.takeIf { name == "update_task" && it != "none" }?.let { findMember(it) }
                val withTarget = t?.let { base.copy(targetOwner = it.ownerId, targetShared = it.shared, targetTitle = it.title) } ?: base
                if (assignee != null) withTarget.copy(recipients = setOf(assignee.id), recipientNames = listOf(memberName(assignee.id) ?: "ti")) else withTarget
            }
            "update_event", "delete_event" -> a.uuid("id")?.let { events.get(userId, it) }
                ?.let { base.copy(targetOwner = it.ownerId, targetShared = it.shared, targetTitle = it.title) } ?: base
            "update_reminder", "complete_reminder", "snooze_reminder", "cancel_reminder" -> a.uuid("id")?.let { reminders.get(userId, it) }
                ?.let { base.copy(targetOwner = it.ownerId, targetTitle = it.text) } ?: base
            "forget" -> a.uuid("id")?.let { memories.get(userId, it) }?.let { base.copy(targetOwner = userId, targetTitle = it.content) } ?: base
            "add_to_list" -> lists.findByName(userId, a.str("list") ?: "Supermercado", preferShared = true)
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

    // ---------------------------------------------------------------- utilidades

    /** Un recordatorio pudo escribirlo otra persona si es para alguien más o lo programó otro miembro. */
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
        val EVERYONE = setOf("todos", "todas", "todo el mundo", "la familia", "familia")
        const val OTHERS_NOTES = "notas familiares, de otra persona o compartidas desde fuera"
        const val OTHERS_LISTS = "listas familiares o de otra persona"
        const val OTHERS_ITEMS = "elementos familiares, de otra persona o externos"
    }
}
