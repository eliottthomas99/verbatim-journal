from datetime import date

import pytest

from server.dates import parse_period

TODAY = date(2026, 10, 31)  # a Saturday; weeks run Monday to Sunday


@pytest.mark.parametrize("question, start, end", [
    ("Qu'est-ce que j'ai fait hier ?", "2026-10-30", "2026-10-30"),
    ("What did I do yesterday?", "2026-10-30", "2026-10-30"),
    ("Et avant-hier ?", "2026-10-29", "2026-10-29"),
    ("what about the day before yesterday", "2026-10-29", "2026-10-29"),
    ("De quoi j'ai parlé il y a trois jours ?", "2026-10-28", "2026-10-28"),
    ("What did I say 3 days ago?", "2026-10-28", "2026-10-28"),
    ("Qu'est-ce que j'ai dit lundi ?", "2026-10-26", "2026-10-26"),
    ("What did I say on Tuesday?", "2026-10-27", "2026-10-27"),
    ("Qu'ai-je noté lundi dernier ?", "2026-10-26", "2026-10-26"),
    ("what did I say last Saturday", "2026-10-24", "2026-10-24"),
    ("Résume ma semaine dernière", "2026-10-19", "2026-10-25"),
    ("What happened last week?", "2026-10-19", "2026-10-25"),
    ("il y a deux semaines, j'ai parlé de quoi ?", "2026-10-12", "2026-10-18"),
    ("Résume cette semaine", "2026-10-26", "2026-10-31"),
    ("Qu'est-ce que j'ai fait en mars ?", "2026-03-01", "2026-03-31"),
    ("Qu'est-ce que j'ai fait en décembre ?", "2025-12-01", "2025-12-31"),
    ("Résume le mois dernier", "2026-09-01", "2026-09-30"),
    ("summarize last month", "2026-09-01", "2026-09-30"),
    ("What did I say in March last year?", "2025-03-01", "2025-03-31"),
    ("Qu'ai-je dit en mars l'an dernier ?", "2025-03-01", "2025-03-31"),
    ("Qu'ai-je dit le 12 mars ?", "2026-03-12", "2026-03-12"),
    ("What did I say on March 12th, 2025?", "2025-03-12", "2025-03-12"),
    ("mes notes de mars 2025", "2025-03-01", "2025-03-31"),
    ("Et le 2026-01-15 ?", "2026-01-15", "2026-01-15"),
    ("Qu'est-ce que j'ai dit ce matin ?", "2026-10-31", "2026-10-31"),
    ("What did I record today?", "2026-10-31", "2026-10-31"),
    ("les 5 derniers jours", "2026-10-26", "2026-10-31"),
    ("the past 2 days", "2026-10-29", "2026-10-31"),
    ("le week-end dernier", "2026-10-24", "2026-10-25"),
    ("qu'ai-je fait l'année dernière ?", "2025-01-01", "2025-12-31"),
    ("il y a un mois", "2026-09-01", "2026-09-30"),
])
def test_past_periods(question, start, end):
    assert parse_period(question, TODAY) == (date.fromisoformat(start), date.fromisoformat(end))


@pytest.mark.parametrize("question", [
    "Qui dois-je appeler demain ?",
    "Quelles tâches ai-je à faire avant vendredi ?",
    "What do I have to do next week?",
    "Who did I ask to write the report?",
    "Que dois-je acheter samedi ?",
    "What must I do today?",
    "Qu'est-ce que je dois faire la semaine prochaine ?",
    "May I see my notes about the garage?",
    "Le 31 février ?",
    "Où est-ce que Julien veut aller ?",
])
def test_questions_without_a_past_period(question):
    assert parse_period(question, TODAY) is None


@pytest.mark.parametrize("question, expected", [
    ("What did I say on March 12 last year?", date(2025, 3, 12)),
    ("Qu'ai-je dit le 12 mars l'an dernier ?", date(2025, 3, 12)),
    ("What did I say on February 29?", date(2024, 2, 29)),
])
def test_day_year_qualifiers_and_leap_days(question, expected):
    assert parse_period(question, TODAY) == (expected, expected)


def test_past_recording_cue_survives_a_task_deadline():
    assert parse_period("What did I say yesterday that I need to finish before Friday?", TODAY) == (
        date(2026, 10, 30), date(2026, 10, 30))


@pytest.mark.parametrize("question", [
    "What do I need to finish by October 30 2026?",
    "Que dois-je finir avant le 30 octobre 2026 ?",
    "What is due by 2026-10-30?",
    "What did I do between March 1 and March 31?",
    "Mes notes du 1 mars au 31 mars",
    "What happened 999999999999999999 days ago?",
    "il y a 100000 semaines",
])
def test_do_not_silently_narrow_deadlines_ranges_or_overflows(question):
    assert parse_period(question, TODAY) is None
