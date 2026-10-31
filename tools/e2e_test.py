"""Check a real server + model with fictional recordings, in French and English.

Run it against a server with an EMPTY data directory: it adds these recordings
to the journal and does not remove them.

    python tools/e2e_test.py results.json [http://127.0.0.1:8000]

Reads VERBATIM_API_TOKEN from the environment. Prints a score; the answers are
in the JSON file, for reading.
"""
import json
import os
import sys
import time
from datetime import datetime, timedelta

import requests

SERVER = sys.argv[2] if len(sys.argv) > 2 else "http://127.0.0.1:8000"
HEADERS = {"Authorization": f"Bearer {os.environ['VERBATIM_API_TOKEN']}"}
now = datetime.now().replace(minute=0, second=0, microsecond=0)


def day(days_ago, hour):
    return int((now - timedelta(days=days_ago)).replace(hour=hour).timestamp() * 1000)


RECORDINGS = [
    ("fr-bureau", day(1, 9), "Bon, ce matin j'ai vu Julien au bureau. Je lui ai demandé de finir le rapport trimestriel avant vendredi. Il m'a dit qu'il lui manquait encore les chiffres de Nadia. Il faut aussi que je rappelle le plombier pour la fuite dans la cuisine, parce que ça coule toujours."),
    ("fr-velo", day(1, 18), "Je rentre du travail à vélo, il pleut des cordes. J'ai crevé près du pont, j'ai dû marcher vingt minutes. Il faut que j'achète une chambre à air samedi, et je pense que je vais prendre des pneus renforcés."),
    ("fr-medecin", day(3, 11), "Rendez-vous chez la docteure Morel pour mon genou. Elle m'a dit d'arrêter la course pendant trois semaines et de faire des séances de kiné. Je dois prendre rendez-vous chez le kiné avant la fin du mois."),
    ("en-budget", day(2, 14), "Quick note after the meeting with Sarah. We agreed to cut the marketing budget by ten percent and move the launch to January. Sarah will send the new plan to the board by Tuesday. I need to update the slides."),
    ("en-aunt", day(9, 20), "Called my aunt Rosa tonight. It's her birthday next spring, and she'd love a new teapot. Also my cousin Leo is coming to visit for a week in December, so I should clean the guest room."),
    ("fr-theatre", day(9, 10), "Réunion du club de théâtre. La présidente veut le texte de l'acte trois pour le quinze novembre. On a décidé d'abandonner les décors en bois et de garder uniquement les projections."),
    ("fr-resto", day(0, 8), "Hier soir on a dîné au restaurant libanais avec Karim et Yasmine. Karim m'a appris qu'il déménage à Lyon en février. Yasmine cherche quelqu'un pour reprendre son appartement."),
    ("en-car", day(40, 16), "Took the car to the garage. The mechanic said the brake pads need replacing, it will cost about two hundred euros. I can pick it up on Thursday."),
]

# (question, words any of which must be in the answer, recordings search should surface, kind)
QUESTIONS = [
    ("Qui doit finir le rapport trimestriel ?", ["Julien"], ["fr-bureau"], "fr-fact"),
    ("Qu'est-ce que la docteure m'a dit pour mon genou ?", ["course", "kiné"], ["fr-medecin"], "fr-fact"),
    ("Où déménage Karim ?", ["Lyon"], ["fr-resto"], "fr-fact"),
    ("Qu'est-ce que je dois acheter pour mon vélo ?", ["chambre à air"], ["fr-velo"], "fr-fact"),
    ("Who will send the new plan to the board?", ["Sarah"], ["en-budget"], "en-fact"),
    ("What gift would my aunt like?", ["teapot"], ["en-aunt"], "en-fact"),
    ("How much will the brake pads cost?", ["200", "two hundred"], ["en-car"], "en-fact"),
    ("Qu'est-ce que j'ai fait hier ?", ["Julien", "vélo", "rapport"], ["fr-bureau", "fr-velo"], "date"),
    ("What did I do yesterday?", ["Julien", "bike", "report"], ["fr-bureau", "fr-velo"], "date"),
    ("De quoi j'ai parlé il y a trois jours ?", ["genou", "Morel", "kiné"], ["fr-medecin"], "date"),
    ("Résume ma semaine dernière", ["théâtre", "Rosa", "Leo", "acte"], ["fr-theatre", "en-aunt"], "date"),
    ("Quelles tâches ai-je à faire avant vendredi ?", ["rapport", "Julien"], ["fr-bureau"], "future"),
    ("Qu'est-ce que j'ai dit sur les dinosaures ?", [], [], "absent"),
    ("What did I say in March last year?", [], [], "absent-date"),
]

out = {"summaries": [], "questions": []}
ids = {}
for name, ts, text in RECORDINGS:
    t = time.time()
    r = requests.post(f"{SERVER}/transcript", headers=HEADERS, timeout=900, json={
        "filename": f"{name}.m4a", "timestamp": ts, "durationMs": 60000, "transcript": text})
    r.raise_for_status()
    body = r.json()
    ids[body["recording_id"]] = name
    out["summaries"].append({"name": name, "seconds": round(time.time() - t, 1), "summary": body["summary"], "actions": body["action_items"]})
    print(f"summary {name}: {time.time() - t:.1f}s", flush=True)

for q, words, expected, kind in QUESTIONS:
    t = time.time()
    r = requests.post(f"{SERVER}/query", headers=HEADERS, json={"question": q}, timeout=900)
    r.raise_for_status()
    body = r.json()
    sources = [ids.get(s, s) for s in body["sources"]]
    answer = body["answer"]
    out["questions"].append({
        "q": q, "kind": kind, "seconds": round(time.time() - t, 1), "answer": answer, "sources": sources,
        "word_hit": (not words) or any(w.lower() in answer.lower() for w in words),
        "retrieved_expected": all(e in sources for e in expected),
    })
    print(f"{kind}: {time.time() - t:.1f}s", flush=True)

found = sum(q["retrieved_expected"] for q in out["questions"])
right = sum(q["word_hit"] for q in out["questions"])
print(f"right recording: {found}/{len(QUESTIONS)}; expected words in the answer: {right}/{len(QUESTIONS)}")
with open(sys.argv[1], "w", encoding="utf-8") as f:
    json.dump(out, f, ensure_ascii=False, indent=1)
