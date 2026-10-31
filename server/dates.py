"""Find the past period a question refers to, in French or English.

Plain code rather than an LLM call: small models get calendar maths wrong.
A phrasing this module does not know simply searches all dates.
"""
import re
import unicodedata
from calendar import monthrange
from datetime import date, timedelta

Period = tuple[date, date]

MONTHS = {
    "janvier": 1, "fevrier": 2, "mars": 3, "avril": 4, "mai": 5, "juin": 6, "juillet": 7,
    "aout": 8, "septembre": 9, "octobre": 10, "novembre": 11, "decembre": 12,
    "january": 1, "february": 2, "march": 3, "april": 4, "may": 5, "june": 6, "july": 7,
    "august": 8, "september": 9, "october": 10, "november": 11, "december": 12,
}
WEEKDAYS = {
    "lundi": 0, "mardi": 1, "mercredi": 2, "jeudi": 3, "vendredi": 4, "samedi": 5, "dimanche": 6,
    "monday": 0, "tuesday": 1, "wednesday": 2, "thursday": 3, "friday": 4, "saturday": 5, "sunday": 6,
}
NUMBERS = {
    "un": 1, "une": 1, "deux": 2, "trois": 3, "quatre": 4, "cinq": 5, "six": 6, "sept": 7,
    "huit": 8, "neuf": 9, "dix": 10, "quinze": 15,
    "a": 1, "an": 1, "one": 1, "two": 2, "three": 3, "four": 4, "five": 5, "seven": 7,
    "eight": 8, "nine": 9, "ten": 10, "fifteen": 15,
}

MONTH = "|".join(MONTHS)
WEEKDAY = "|".join(WEEKDAYS)
NUMBER = r"\d+|" + "|".join(NUMBERS)
LAST_YEAR = r"last year|l'an dernier|l'an passe|l'annee derniere|l'annee passee"

# About the future or a deadline: those things were planned in earlier recordings.
FUTURE = re.compile(
    r"\b(demain|tomorrow|prochaine?s?|next|d'ici|until|jusqu'a|"
    rf"(avant|before|by|pour) (le |la |en |in )?({WEEKDAY}|{MONTH}))\b"
)
# "What must I do today?" is about tasks, wherever they were recorded.
TASK = re.compile(r"\b(dois|doit|devons|il faut|a faire|prevu|prevue|have to|has to|need to|needs to|should|must|supposed to)\b")


def _normalize(text: str) -> str:
    decomposed = unicodedata.normalize("NFKD", text.lower().replace("’", "'"))
    return "".join(c for c in decomposed if not unicodedata.combining(c))


def _number(word: str) -> int:
    count = int(word) if word.isdigit() else NUMBERS[word]
    if count >= 100_000:
        raise ValueError("Date offset is too large")
    return count


def _month_period(year: int, month: int) -> Period:
    return date(year, month, 1), date(year, month, monthrange(year, month)[1])


def _months_ago(today: date, count: int) -> Period:
    index = today.year * 12 + today.month - 1 - count
    return _month_period(index // 12, index % 12 + 1)


def _week_of(day: date) -> Period:
    monday = day - timedelta(days=day.weekday())
    return monday, monday + timedelta(days=6)


def _latest_month(today: date, month: int) -> Period:
    return _month_period(today.year if month <= today.month else today.year - 1, month)


def _explicit_date(q: str, today: date) -> Period | None:
    """2026-03-12, "le 12 mars", "March 12th 2025", "mars 2025", "en mars l'an dernier"..."""
    if m := re.search(r"\b(\d{4})-(\d{2})-(\d{2})\b", q):
        day = date(int(m[1]), int(m[2]), int(m[3]))
        return day, day

    numbered = re.search(rf"\b(\d{{1,2}})(?:er|st|nd|rd|th)? ({MONTH})(?: (\d{{4}}))?\b", q) or None
    if numbered:
        day_number, month_name, year = numbered[1], numbered[2], numbered[3]
    elif m := re.search(rf"\b({MONTH}) (\d{{1,2}})(?:st|nd|rd|th)?(?:,? (\d{{4}}))?(?!\d)", q):
        month_name, day_number, year = m[1], m[2], m[3]
    else:
        day_number = None
    if day_number:
        month = MONTHS[month_name]
        if not year and re.search(LAST_YEAR, q):
            year = str(today.year - 1)
        if year:
            day = date(int(year), month, int(day_number))
        else:
            # Find the most recent valid occurrence, including February 29.
            for candidate in range(today.year, max(0, today.year - 8), -1):
                try:
                    day = date(candidate, month, int(day_number))
                except ValueError:
                    continue
                if day <= today:
                    break
            else:
                raise ValueError("Invalid calendar day")
        return day, day

    if m := re.search(rf"\b({MONTH}) (\d{{4}})\b", q):
        return _month_period(int(m[2]), MONTHS[m[1]])
    prefix = rf"(?:en|in|de|du mois de|au mois de|during) ({MONTH})\b"
    if (m := re.search(prefix, q)) and re.search(LAST_YEAR, q):
        return _month_period(today.year - 1, MONTHS[m[1]])
    if m := re.search(rf"\b(?:last ({MONTH})|({MONTH}) (?:dernier|passe))\b", q):
        return _latest_month(today, MONTHS[m[1] or m[2]])
    if m := re.search(r"\b(?:en|in) (\d{4})\b", q):
        return date(int(m[1]), 1, 1), date(int(m[1]), 12, 31)
    return None


def _relative_past(q: str, today: date) -> Period | None:
    """Words that can only mean the past: yesterday, il y a trois jours, last week..."""
    if re.search(r"\b(avant[- ]hier|day before yesterday)\b", q):
        day = today - timedelta(days=2)
        return day, day
    if re.search(r"\b(hier|yesterday)\b", q):
        day = today - timedelta(days=1)
        return day, day

    ago = re.search(rf"\bil y a ({NUMBER}) (jour|semaine|mois|an|annee)s?\b", q)
    ago = ago or re.search(rf"\b({NUMBER}) (day|week|month|year)s? ago\b", q)
    if ago:
        count, unit = _number(ago[1]), ago[2]
        if unit in ("jour", "day"):
            day = today - timedelta(days=count)
            return day, day
        if unit in ("semaine", "week"):
            return _week_of(today - timedelta(weeks=count))
        if unit in ("mois", "month"):
            return _months_ago(today, count)
        return date(today.year - count, 1, 1), date(today.year - count, 12, 31)

    recent = re.search(rf"\b(?:les|mes|(?:the )?(?:last|past)) ({NUMBER}) (?:derniers jours|days)\b", q)
    if recent:
        return today - timedelta(days=_number(recent[1])), today
    if re.search(r"\b(ces derniers jours|(last|past) few days)\b", q):
        return today - timedelta(days=7), today

    if re.search(r"\b(week-?end (dernier|passe)|last week-?end)\b", q):
        saturday = _week_of(today - timedelta(weeks=1))[0] + timedelta(days=5)
        return saturday, saturday + timedelta(days=1)
    if re.search(r"\b(semaine (derniere|passee)|last week)\b", q):
        return _week_of(today - timedelta(weeks=1))
    if re.search(r"\b(mois (dernier|passe)|last month)\b", q):
        return _months_ago(today, 1)
    if re.search(LAST_YEAR, q):
        return date(today.year - 1, 1, 1), date(today.year - 1, 12, 31)

    if m := re.search(rf"\b(?:last ({WEEKDAY})|({WEEKDAY}) (?:dernier|passe))\b", q):
        days_back = (today.weekday() - WEEKDAYS[m[1] or m[2]]) % 7 or 7
        day = today - timedelta(days=days_back)
        return day, day
    return None


def _current_or_bare(q: str, today: date) -> Period | None:
    """Words that may also be about what is still to come: today, lundi, en mars..."""
    if re.search(r"\b(aujourd'hui|today|ce matin|ce soir|cet apres-midi|this morning|this afternoon|this evening|tonight)\b", q):
        return today, today
    if re.search(r"\b(cette semaine|this week)\b", q):
        return _week_of(today)[0], today
    if re.search(r"\b(ce mois(-ci)?|this month)\b", q):
        return today.replace(day=1), today
    if re.search(r"\b(cette annee|this year)\b", q):
        return today.replace(month=1, day=1), today
    if m := re.search(rf"\b({WEEKDAY})\b", q):
        day = today - timedelta(days=(today.weekday() - WEEKDAYS[m[1]]) % 7)
        return day, day
    if m := re.search(rf"\b(?:en|in|de|du mois de|au mois de|during) ({MONTH})\b", q):
        return _latest_month(today, MONTHS[m[1]])
    return None


def parse_period(question: str, today: date) -> Period | None:
    """Return the inclusive (first day, last day) the question asks about, or None."""
    q = _normalize(question)
    # Do not turn an unsupported range into its first recognizable day.
    # A later parser can support these without silently narrowing the search now.
    if re.search(r"\b(between|entre|from .+ to|du .+ au)\b", q):
        return None
    # Explicit dates used as task deadlines refer to when work is due, not
    # when it was recorded. Relative past cues remain useful ("asked yesterday").
    deadline = (TASK.search(q) or re.search(r"\b(deadline|due|echeance)\b", q)) and re.search(
        r"\b(by|before|until|avant|pour|d'ici|jusqu'a)\b", q
    )
    try:
        relative = _relative_past(q, today)
        period = relative if deadline else (_explicit_date(q, today) or relative)
        if not period and not FUTURE.search(q) and not TASK.search(q):
            period = _current_or_bare(q, today)
    except (ValueError, OverflowError):  # invalid calendar day or oversized offset
        return None
    if period and period[0] > today:
        return None  # nothing can have been recorded in the future
    return period
