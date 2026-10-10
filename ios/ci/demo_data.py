"""Sample data for "The Rivera family": a realistic household used by the CI mock server (and by
gen_fixtures.py, which writes the JSON fixtures of the KairoKit tests). Everything is relative to `now`,
so "today" always has events, tasks and reminders. All names, places and texts are made up."""
import uuid
from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo

TZ_NAME = "America/New_York"
NS = uuid.UUID("6f1b6a52-0d57-4c2a-9b86-2f1f0f0a7d11")


def uid(key):
    return str(uuid.uuid5(NS, key))


def iso(dt):
    return dt.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"


class Demo:
    """Builds every API resource for a given moment (timezone-aware `now`)."""

    def __init__(self, now):
        self.tz = ZoneInfo(TZ_NAME)
        self.now = now.astimezone(self.tz)
        self.today = self.now.date()
        self.alex, self.sam, self.mia = uid("alex"), uid("sam"), uid("mia")
        self.household = uid("household")
        self.created = iso(self.now - timedelta(days=40))

    # ---- time helpers -------------------------------------------------------------------------
    def at(self, days=0, hour=9, minute=0):
        d = self.today + timedelta(days=days)
        return datetime(d.year, d.month, d.day, hour, minute, tzinfo=self.tz)

    def day(self, days):
        return (self.today + timedelta(days=days)).isoformat()

    def _local_date(self, stamp):
        return datetime.fromisoformat(stamp.replace("Z", "+00:00")).astimezone(self.tz).date()

    def ago(self, **kw):
        return iso(self.now - timedelta(**kw))

    # ---- people -------------------------------------------------------------------------------
    def user(self):
        return {"id": self.alex, "email": "alex@example.com", "displayName": "Alex Rivera", "timezone": TZ_NAME,
                "createdAt": self.created, "householdId": self.household, "role": "admin", "color": None}

    def auth(self):
        return {"user": self.user(), "accessToken": "demo-access", "refreshToken": "demo-refresh", "expiresIn": 3600,
                "deviceId": uid("device-iphone")}

    def family(self):
        return {
            "household": {"id": self.household, "name": "The Rivera family", "createdAt": self.created},
            "members": [
                {"id": self.alex, "displayName": "Alex Rivera", "email": "alex@example.com", "role": "admin", "joinedAt": self.created},
                {"id": self.sam, "displayName": "Sam Rivera", "email": "sam@example.com", "role": "member", "joinedAt": self.ago(days=39)},
                {"id": self.mia, "displayName": "Mia Rivera", "email": "mia@example.com", "role": "member", "joinedAt": self.ago(days=30)},
            ],
            "me": self.alex,
            "isAdmin": True,
        }

    # ---- calendar -----------------------------------------------------------------------------
    def _event(self, key, title, start, end, location="", shared=True, owner=None, all_day=False, reminder=(15,), rrule=None):
        return {
            "id": uid("event-" + key), "title": title, "description": "", "location": location,
            "startAt": iso(start), "endAt": iso(end), "allDay": all_day, "rrule": rrule, "timezone": TZ_NAME,
            "reminderMinutes": list(reminder), "color": None, "source": "kairo", "googleEventId": None,
            "createdAt": self.ago(days=5), "updatedAt": self.ago(days=1), "shared": shared, "ownerId": owner or self.sam,
        }

    def events(self):
        h = lambda d, a, b, m1=0, m2=0: (self.at(d, a, m1), self.at(d, b, m2))
        rows = [
            ("swim", "Mia's swim class", *h(0, 9, 10), "Riverside Pool", True, self.sam),
            ("lunch", "Family lunch at Grandma's", *h(0, 13, 14, 0, 30), "Maple Street", True, self.sam),
            ("bike", "Bike ride with Sam and Mia", *h(1, 8, 10, 0, 30), "Greenway Park", True, self.sam),
            ("run", "Morning run", *h(2, 7, 8), "", False, self.alex),
            ("dentist", "Mia's dentist appointment", *h(3, 16, 17), "Bright Smiles Dental", True, self.sam),
            ("pta", "Parent-teacher meeting", *h(4, 18, 19), "Lincoln Elementary", True, self.sam),
            ("dinner", "Dinner with Jordan", *h(5, 19, 21), "Olive & Thyme", False, self.alex),
            ("pediatrician", "Mia's pediatrician", *h(6, 13, 14), "Northside Clinic", True, self.sam),
            ("movie", "Movie night", *h(8, 20, 22), "", True, self.alex),
            ("recital", "Piano recital", *h(11, 17, 18, 0, 30), "Community Hall", True, self.sam),
            ("birthday", "Mia's birthday party", *h(16, 15, 18), "Backyard", True, self.alex),
        ]
        return [self._event(k, t, s, e, loc, sh, ow) for k, t, s, e, loc, sh, ow in rows]

    # ---- tasks --------------------------------------------------------------------------------
    def _task(self, key, title, status="todo", priority="medium", due=None, project=None, assignee=None, shared=False,
              owner=None, notes="", done=False):
        return {
            "id": uid("task-" + key), "title": title, "notes": notes, "status": "done" if done else status, "priority": priority,
            "dueDate": self.day(due) if due is not None else None, "dueAt": None,
            "projectId": uid("project-" + project) if project else None, "projectName": project, "tags": [], "sort": -float(len(key)),
            "source": "chat", "completedAt": self.ago(hours=3) if done else None, "createdAt": self.ago(days=4),
            "updatedAt": self.ago(hours=2), "shared": shared, "ownerId": owner or self.alex, "assigneeId": assignee,
        }

    def tasks(self):
        a, s = self.alex, self.sam
        return [
            self._task("fee", "Pay the building fee", priority="high", due=-2, project="Finances", assignee=a, shared=True, owner=s),
            self._task("gift", "Buy the teacher's gift", due=6, assignee=a, shared=True, owner=s),
            self._task("pickup", "Pick up Mia from school", due=2, assignee=a, shared=True, owner=s),
            self._task("flights", "Book flights for the holidays", status="doing", priority="high", due=7, project="Travel", assignee=a, shared=True, owner=a),
            self._task("report", "Send the quarterly report to Dana", priority="urgent", due=1, project="Work", assignee=a, owner=a),
            self._task("insurance", "Renew car insurance", due=9, project="Finances", assignee=a, owner=a),
            self._task("plants", "Water the plants", due=0, assignee=a, shared=True, owner=s),
            self._task("shoes", "Order new school shoes for Mia", due=5, assignee=s, shared=True, owner=a),
            self._task("laundry", "Fold the laundry", due=-1, assignee=s, shared=True, owner=s, done=True),
            self._task("dishes", "Fix the dishwasher", due=-3, assignee=a, owner=a, done=True),
        ]

    # ---- reminders / places -------------------------------------------------------------------
    def _reminder(self, key, text, due=None, status="scheduled", owner=None, created_by=None, place=None, trigger=None, rrule=None):
        return {
            "id": uid("reminder-" + key), "text": text, "dueAt": iso(due) if due else None, "rrule": rrule, "timezone": TZ_NAME,
            "placeId": uid("place-" + place) if place else None, "placeName": place, "placeTrigger": trigger,
            "status": status, "nextFireAt": iso(due) if due and status == "scheduled" else None,
            "lastFiredAt": iso(due) if due and status == "fired" else None, "taskId": None, "source": "chat",
            "createdAt": self.ago(days=2), "updatedAt": self.ago(days=1), "ownerId": owner or self.alex, "createdBy": created_by,
        }

    def reminders(self):
        a, s = self.alex, self.sam
        return [
            self._reminder("trash", "Take out the trash", self.at(0, 8, 30), status="fired", owner=a, created_by=s),
            self._reminder("call", "Call Grandma about Saturday", self.at(0, 18, 0), owner=a),
            self._reminder("electric", "Pay the electric bill", self.at(1, 9, 0), owner=a),
            self._reminder("card", "Bring Mia's health card (dentist appointment at 4 pm)", self.at(3, 15, 0), owner=a, created_by=s),
            self._reminder("recycling", "Take out the recycling", place="Home", trigger="arrive", owner=a),
            self._reminder("vitamins", "Take your vitamins", self.at(1, 8, 0), owner=a, rrule="FREQ=DAILY"),
            self._reminder("bins", "Bins out for collection", self.at(2, 19, 0), owner=a, rrule="FREQ=WEEKLY;BYDAY=TU"),
            self._reminder("tax", "Send the tax documents", self.at(-3, 10, 0), status="done", owner=a),
        ]

    def places(self):
        return [
            {"id": uid("place-Home"), "name": "Home", "latitude": 40.7128, "longitude": -74.0060, "radiusM": 150, "address": "12 Maple Street",
             "createdAt": self.created, "shared": True, "ownerId": self.sam},
            {"id": uid("place-Office"), "name": "Office", "latitude": 40.7306, "longitude": -73.9866, "radiusM": 200, "address": None,
             "createdAt": self.created, "shared": False, "ownerId": self.alex},
        ]

    # ---- lists ---------------------------------------------------------------------------------
    def _list(self, key, name, items, shared=True, owner=None):
        return {
            "id": uid("list-" + key), "name": name, "icon": None, "createdAt": self.ago(days=20), "updatedAt": self.ago(hours=5),
            "shared": shared, "ownerId": owner or self.sam,
            "items": [{"id": uid(f"item-{key}-{i}"), "text": t, "checked": c, "sort": float(i), "createdAt": self.ago(days=3)}
                      for i, (t, c) in enumerate(items)],
        }

    def lists(self):
        return [
            self._list("groceries", "Groceries", [
                ("Eggs", False), ("Whole wheat bread", False), ("Cheddar cheese", False), ("Avocados", False), ("Ground coffee", False),
                ("Bananas", False), ("Greek yogurt", False), ("Oat milk", False), ("Apples", True), ("Milk", True)]),
            self._list("birthday", "Mia's birthday", [
                ("Balloons", False), ("Birthday candles", False), ("Party hats", False), ("Goodie bags", False), ("Cake", True)], owner=self.alex),
            self._list("trip", "Weekend packing", [("Sunscreen", False), ("Phone chargers", False), ("Swimsuits", False)], shared=False, owner=self.alex),
        ]

    # ---- notes / memories ----------------------------------------------------------------------
    def _note(self, key, title, content, tags=(), pinned=False, shared=False, owner=None, minutes=60):
        stamp = self.ago(minutes=minutes)
        return {"id": uid("note-" + key), "title": title, "content": content, "tags": list(tags), "pinned": pinned, "source": "app",
                "attachments": [], "createdAt": stamp, "updatedAt": stamp, "score": None, "shared": shared, "ownerId": owner or self.alex}

    def notes(self):
        a, s = self.alex, self.sam
        return [
            self._note("wifi", "Home Wi-Fi", "**Network:** Rivera-Home\n\nThe password is on the sticky note on the fridge.", ("home",), True, True, s, 30),
            self._note("sizes", "Mia's sizes", "- Shoes: 13C\n- T-shirt: 8 years\n- Pants: 8", (), False, True, a, 90),
            self._note("gifts", "Gift ideas for Sam's birthday", "Hiking boots, a good thermos, the new Murakami novel", ("gifts",), False, False, a, 180),
            self._note("contacts", "Building contacts", "- Front desk: ext. 100\n- Maintenance: Mr. Lopez, 555 0142\n- [ ] Ask for the updated house rules", (), False, True, s, 600),
            self._note("trip", "Road trip ideas", "# Coast route\n\n1. Day 1: Scenic drive and lunch in a fishing town\n2. Day 2: Lighthouse and tide pools\n3. Day 3: Back home through the hills", (), False, False, a, 2000),
        ]

    def memories(self):
        rows = [("perfil", "Alex works in marketing and prefers short answers"), ("salud", "Alex is allergic to penicillin"),
                ("personas", "Mia is 8 years old and loves swimming"), ("preferencias", "The family prefers dinner around 7 pm"),
                ("hogar", "The recycling goes out on Tuesdays"), ("trabajo", "Dana is Alex's manager")]
        return [{"id": uid(f"memory-{i}"), "content": c, "category": cat, "createdAt": self.ago(days=10 + i), "updatedAt": self.ago(days=10 + i)}
                for i, (cat, c) in enumerate(rows)]

    # ---- recordings / activity / devices -------------------------------------------------------
    def recordings(self):
        return [{
            "id": uid("recording-1"), "title": "Weekly family planning", "status": "done", "durationS": 754,
            "transcript": "Okay, let's go over the week. Mia has swimming on Saturday and the dentist on Tuesday...",
            "summary": "The family planned the week: swim class on Saturday, a bike ride on Sunday and Mia's dentist appointment on Tuesday.",
            "keyPoints": ["Swim class on Saturday at 9", "Bike ride on Sunday morning", "Dentist appointment on Tuesday at 4 pm"],
            "actionItems": ["Order new school shoes for Mia", "Buy the teacher's gift", "Book the holiday flights"],
            "noteId": None, "error": None, "segments": 3, "createdAt": self.ago(days=1), "updatedAt": self.ago(days=1),
        }]

    def activity(self):
        rows = [
            ("updated", "list", "list-groceries", "Groceries: + Greek yogurt, Bananas", "chat", False, 20),
            ("created", "reminder", "reminder-electric", "Reminder: Pay the electric bill", "voice", True, 55),
            ("created", "event", "event-pta", "Event: Parent-teacher meeting", "chat", True, 140),
            ("created", "note", "note-gifts", "Note: Gift ideas for Sam's birthday", "app", True, 200),
            ("deleted", "task", "task-dishes", "Task deleted: Call the plumber", "chat", True, 1500),
            ("updated", "task", "task-fee", "Task: Pay the building fee", "app", False, 1700),
        ]
        return [{"id": uid(f"activity-{i}"), "kind": k, "entityType": et, "entityId": uid(eid), "summary": s, "source": src,
                 "undoable": u, "undoneAt": None, "createdAt": self.ago(minutes=m)} for i, (k, et, eid, s, src, u, m) in enumerate(rows)]

    def devices(self):
        rows = [("Alex's iPhone", "ios", True, 1), ("Home laptop", "web", False, 40), ("Alex's Pixel", "android", False, 3000), ("Apple Watch", "watch", False, 5)]
        return [{"id": uid(f"device-{n}"), "name": n, "platform": p, "createdAt": self.ago(days=20), "lastSeenAt": self.ago(minutes=m), "current": cur}
                for n, p, cur, m in rows]

    # ---- family notices ------------------------------------------------------------------------
    def notices(self):
        rows = [
            ("reminder", "Sam set a reminder for you", "Take out the trash · today at 8:30 AM", "reminder", "reminder-trash", 5, False),
            ("shared", "Sam shared a list", "Groceries", "list", "list-groceries", 25, False),
            ("shared", "Sam shared a note", "Home Wi-Fi", "note", "note-wifi", 50, False),
            ("message", "Sam", "Be there in 10 minutes", None, None, 80, False),
            ("updated", "Sam canceled a family event", "Pediatrician · Tuesday at 10:00 AM", "event", "event-cancelled", 300, True),
            ("message", "Mia joined the family", "She can now see what you share.", "family", "family", 900, True),
        ]
        return [{"id": uid(f"notice-{i}"), "kind": k, "title": t, "body": b, "actorId": None if k == "message" and et == "family" else self.sam,
                 "entityType": et, "entityId": uid(eid) if eid else None, "createdAt": self.ago(minutes=m),
                 "readAt": self.ago(minutes=m - 1) if read else None} for i, (k, t, b, et, eid, m, read) in enumerate(rows)]

    def notices_response(self):
        n = self.notices()
        return {"notices": n, "unread": sum(1 for x in n if x["readAt"] is None)}

    # ---- chat ----------------------------------------------------------------------------------
    def conversations(self):
        return [
            {"id": uid("conv-week"), "title": "What's on this week with the family?", "createdAt": self.ago(hours=3), "updatedAt": self.ago(hours=3),
             "preview": "Here is your family week"},
            {"id": uid("conv-list"), "title": "Add oat milk to the groceries", "createdAt": self.ago(days=1), "updatedAt": self.ago(days=1),
             "preview": "Done. I added it to Groceries."},
        ]

    def messages(self):
        agenda = "\n".join([
            "Here is your family week:", "",
            "**Today**", "- 9:00 AM: Mia's swim class (Riverside Pool)", "- 1:00 PM: Family lunch at Grandma's (Maple Street)", "",
            "**Tomorrow**", "- 8:00 AM: Bike ride with Sam and Mia (Greenway Park)", "",
            "**Tuesday**", "- 4:00 PM: Mia's dentist appointment", "",
            "Want me to remind you to bring Mia's health card?"])
        t = lambda m: self.ago(minutes=m)
        return [
            {"id": uid("msg-1"), "role": "user", "content": "What's on this week with the family?", "toolName": None, "meta": {"channel": "chat"}, "createdAt": t(185)},
            {"id": uid("msg-2"), "role": "tool", "content": "{}", "toolName": "get_agenda", "meta": {"ok": True, "summary": "6 events, 3 tasks, 2 reminders"}, "createdAt": t(184)},
            {"id": uid("msg-3"), "role": "assistant", "content": agenda, "toolName": None, "meta": None, "createdAt": t(183)},
            {"id": uid("msg-4"), "role": "user", "content": "Yes, remind me on Tuesday at 3 pm", "toolName": None, "meta": {"channel": "chat"}, "createdAt": t(180)},
            {"id": uid("msg-5"), "role": "tool", "content": "{}", "toolName": "create_reminder",
             "meta": {"ok": True, "summary": "Reminder: Bring Mia's health card", "entity": {"type": "reminder", "id": uid("reminder-card"), "title": "Bring Mia's health card"}}, "createdAt": t(179)},
            {"id": uid("msg-6"), "role": "assistant", "content": "Done. I'll remind you on **Tuesday at 3:00 PM**.", "toolName": None, "meta": None, "createdAt": t(178)},
        ]

    # ---- today / sync / settings ---------------------------------------------------------------
    def weather(self):
        days = [{"date": self.day(i), "min": 12.0 + i % 3, "max": 21.0 - i % 2, "rainChance": 10 * (i % 4), "description": d}
                for i, d in enumerate(["Partly cloudy", "Sunny", "Light rain", "Cloudy"])]
        return {"place": "Springfield", "temperature": 19.0, "feelsLike": 18.0, "description": "Partly cloudy", "days": days}

    def today_response(self):
        events = [e for e in self.events() if self._local_date(e["startAt"]) == self.today]
        tasks = self.tasks()
        due_today = [t for t in tasks if t["dueDate"] == self.day(0) and t["status"] != "done"]
        overdue = [t for t in tasks if t["dueDate"] and t["dueDate"] < self.day(0) and t["status"] != "done"]
        doing = [t for t in tasks if t["status"] == "doing"]
        reminders = self.reminders()
        fired = [r for r in reminders if r["status"] == "fired"]
        upcoming = [r for r in reminders if r["status"] == "scheduled" and r["nextFireAt"] and self._local_date(r["nextFireAt"]) == self.today]
        return {
            "date": self.day(0), "dateLabel": self.now.strftime("%A, %B ") + str(self.today.day),
            "greeting": "Good morning", "events": events, "tasksDueToday": due_today, "tasksOverdue": overdue,
            "tasksInProgress": doing, "reminders": upcoming, "firedReminders": fired, "recentNotes": self.notes()[:5],
            "lists": [l for l in self.lists() if l["shared"]][:1], "weather": self.weather(),
        }

    def sync(self):
        alerts = []
        for e in self.events():
            start = datetime.fromisoformat(e["startAt"].replace("Z", "+00:00"))
            if start > self.now:
                alerts.append({"eventId": e["id"], "title": e["title"], "startAt": e["startAt"], "alertAt": iso(start - timedelta(minutes=15)),
                               "minutes": 15, "location": e["location"] or None, "shared": e["shared"]})
        return {
            "serverTime": iso(self.now), "reminders": [r for r in self.reminders() if r["status"] == "scheduled"], "places": self.places(),
            "eventAlerts": alerts[:6], "briefingEnabled": True, "briefingTime": "07:00",
            "notices": [n for n in self.notices() if n["readAt"] is None], "pushRegistered": False,
        }

    def settings(self):
        return {"settings": {
            "chatModel": "deepseek-v4-flash", "fallbackModel": "qwen3.8-flash", "visionModel": "gemma4", "imageModel": "flux-2-klein",
            "fastMode": True, "voice": "af_heart", "voiceSpeed": 1.0, "sttModel": "whisper", "language": "en", "wakeWord": "hey kairo",
            "briefingEnabled": True, "briefingTime": "07:00", "lastBriefingDate": self.day(0), "homeCity": "Springfield",
            "homeLatitude": None, "homeLongitude": None, "ntfyTopic": None, "googleCalendarId": "primary",
            "defaultEventReminderMinutes": 15, "keepRecordingAudio": False}, "googleAvailable": False, "ntfyAvailable": False}

    def voices(self):
        return {"voices": [{"id": "af_heart", "name": "Heart", "description": "American English, warm"},
                           {"id": "am_michael", "name": "Michael", "description": "American English, calm"}]}

    def briefing(self):
        return {"text": "Good morning, Alex. It is partly cloudy and 19 degrees. You have Mia's swim class at 9 and the family lunch at 1. "
                        "Two things are waiting: the building fee is overdue and the quarterly report is due tomorrow."}

    # ---- everything, by resource name ---------------------------------------------------------
    def resources(self):
        return {
            "auth": self.auth(), "today": self.today_response(), "tasks": self.tasks(), "events": self.events(),
            "notes": self.notes(), "lists": self.lists(), "reminders": self.reminders(), "places": self.places(),
            "memories": self.memories(), "recordings": self.recordings(), "activity": self.activity(),
            "conversations": self.conversations(), "messages": self.messages(), "settings": self.settings(),
            "devices": self.devices(), "family": self.family(), "notices": self.notices_response(), "sync": self.sync(),
        }
