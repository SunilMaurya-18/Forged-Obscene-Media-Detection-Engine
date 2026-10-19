import json
from pathlib import Path

import requests

BASE = "http://localhost:8081"
s = requests.Session()
s.auth = ("Admin", "moderator")
out = []


def check(name, resp, ok=(200, 201)):
    row = {"name": name, "http": resp.status_code, "body": (resp.text or "")[:220]}
    row["status"] = "PASS" if resp.status_code in ok else "FAIL"
    out.append(row)
    print(f"[{row['status']}] [{resp.status_code}] {name}: {row['body'][:120]}")


# ALLOW decision
q = s.get(f"{BASE}/api/queue", params={"status": "review"}).json()
if q:
    check("queue.decision.ALLOW", s.post(f"{BASE}/api/queue/{q[0]['id']}/decision", json={"decision": "ALLOW"}))
else:
    out.append({"name": "queue.decision.ALLOW", "status": "SKIP", "body": "no review items"})

invs = s.get(f"{BASE}/api/investigations", params={"status": "OPEN"}).json()
cn = invs[0]["caseNumber"] if invs else None
print("case", cn)
if cn:
    check("investigations.note", s.post(f"{BASE}/api/investigations/{cn}/notes", json={"text": "Live stack note fixed"}))
    check(
        "investigations.sighting",
        s.post(
            f"{BASE}/api/investigations/{cn}/sightings",
            json={"platform": "mock-feed", "postRef": "post_live_123", "accountId": "acct_1"},
        ),
    )
    check(
        "investigations.legal_hold",
        s.post(
            f"{BASE}/api/investigations/{cn}/legal-holds",
            json={"platform": "mock-feed", "postRefs": ["post_live_123"], "note": "hold"},
        ),
    )
    detail = s.get(f"{BASE}/api/investigations/{cn}").json()
    holds = detail.get("legalHolds") or detail.get("holds") or []
    print("holds keys", detail.keys())
    hold_num = None
    for h in holds if isinstance(holds, list) else []:
        hold_num = h.get("holdNumber")
        if hold_num:
            break
    if not hold_num:
        # custody detail embedding
        for key in ("legalHolds", "holds", "activeHolds"):
            pass
        # search in nested
        raw = json.dumps(detail)
        if "HL-" in raw or "holdNumber" in raw:
            import re

            m = re.search(r'"holdNumber"\s*:\s*"([^"]+)"', raw)
            hold_num = m.group(1) if m else None
    if hold_num:
        check(
            "investigations.legal_hold.ack",
            s.post(
                f"{BASE}/api/investigations/{cn}/legal-holds/{hold_num}/acknowledge",
                json={"partnerReference": "TKT-1"},
            ),
        )

# appeal as senior
senior = requests.Session()
senior.auth = ("senior", "senior")
apps = senior.get(f"{BASE}/api/appeals", params={"status": "open"}).json()
if not apps:
    out.append({"name": "appeal.decide.UPHOLD.senior", "status": "SKIP", "body": "no open appeals"})
elif apps[0].get("canDecide"):
    check(
        "appeal.decide.UPHOLD.senior",
        senior.post(
            f"{BASE}/api/appeals/{apps[0]['id']}/decision",
            json={"outcome": "UPHOLD", "reason": "Second moderator uphold in live stack test."},
        ),
    )
else:
    out.append(
        {
            "name": "appeal.decide.UPHOLD.senior",
            "status": "SKIP",
            "body": f"canDecide=false conflicted={apps[0].get('conflicted')}",
        }
    )
    print("senior skipped", apps[0].get("conflicted"))

Path(__file__).resolve().parent.joinpath("live-stack-retest.json").write_text(json.dumps(out, indent=2))
print(json.dumps({r["status"]: sum(1 for x in out if x["status"] == r["status"]) for r in out}, indent=2))
