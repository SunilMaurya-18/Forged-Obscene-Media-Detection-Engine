#!/usr/bin/env python3
"""Live stack feature sweep against a running FMD backend. Synthetic media only."""

from __future__ import annotations

import io
import json
import sys
import time
import traceback
from dataclasses import dataclass
from datetime import datetime, timezone, timedelta
from pathlib import Path

import requests
from PIL import Image, ImageDraw

BASE = "http://localhost:8081"
USER = "Admin"
PASS = "moderator"
API_KEY = "fmd_live_demo_local_only_do_not_use_in_prod"
OUT = Path(__file__).resolve().parent / "live-stack-results.json"

session = requests.Session()
session.auth = (USER, PASS)
session.headers["Accept"] = "application/json"
session.timeout = 60


@dataclass
class Result:
    name: str
    status: str  # PASS | FAIL | SKIP | BLOCKED
    detail: str = ""
    http: int | None = None


RESULTS: list[Result] = []


def record(name: str, status: str, detail: str = "", http: int | None = None):
    RESULTS.append(Result(name, status, detail, http))
    mark = {"PASS": "OK", "FAIL": "FAIL", "SKIP": "SKIP", "BLOCKED": "BLOCK"}[status]
    extra = f" [{http}]" if http is not None else ""
    print(f"[{mark}]{extra} {name}: {detail}")


def synthetic_jpeg(seed: int = 0, size: int = 224) -> bytes:
    img = Image.new("RGB", (size, size))
    draw = ImageDraw.Draw(img)
    tile = 8
    for y in range(0, size, tile):
        for x in range(0, size, tile):
            n = (x * 13 + y * 7 + seed) % 255
            draw.rectangle([x, y, x + tile - 1, y + tile - 1], fill=(n, (n * 3) % 255, 255 - n))
    buf = io.BytesIO()
    img.save(buf, format="JPEG", quality=92)
    return buf.getvalue()


def files(seed: int, name: str | None = None):
    data = synthetic_jpeg(seed)
    return {"file": (name or f"synthetic-{seed}.jpg", data, "image/jpeg")}


def expect(name: str, resp: requests.Response, ok: set[int] | None = None, predicate=None):
    ok = ok or {200}
    try:
        body = resp.json() if resp.content and "json" in (resp.headers.get("Content-Type") or "") else None
    except Exception:
        body = None
    detail = ""
    if body and isinstance(body, dict):
        detail = body.get("error") or body.get("message") or str(list(body.keys())[:6])
    elif resp.text:
        detail = resp.text[:180].replace("\n", " ")
    if resp.status_code not in ok:
        record(name, "FAIL", detail or resp.reason, resp.status_code)
        return None
    if predicate is not None:
        try:
            msg = predicate(resp, body)
            record(name, "PASS", msg or "ok", resp.status_code)
            return body if body is not None else resp
        except Exception as ex:
            record(name, "FAIL", str(ex), resp.status_code)
            return None
    record(name, "PASS", detail or "ok", resp.status_code)
    return body if body is not None else resp


def main():
    seed = int(time.time()) % 10_000
    ctx: dict = {"seed": seed}

    # --- health / public ---
    r = requests.get(f"{BASE}/api/stats", timeout=30)
    expect(
        "stats",
        r,
        predicate=lambda resp, b: f"forgeryReview={b['thresholds']['forgeryReview']}",
    )
    r = requests.get(f"{BASE}/api/transparency", timeout=30)
    expect("transparency", r, predicate=lambda resp, b: f"keys={list(b.keys())[:5]}")

    r = session.get(f"{BASE}/api/auth/me")
    expect("auth.me", r, predicate=lambda resp, b: f"{b.get('username')} roles={b.get('roles') or b.get('authorities')}")

    r = session.get(f"{BASE}/api/moderation/me")
    me = expect("moderation.me", r, predicate=lambda resp, b: f"user={b.get('username')} team={b.get('team')}")
    r = session.get(f"{BASE}/api/moderation/teams")
    expect("moderation.teams", r, predicate=lambda resp, b: f"n={len(b) if isinstance(b, list) else b}")

    # --- analyze (expect REVIEW with DECISION_FORGERY_REVIEW=0.05) ---
    r = requests.post(f"{BASE}/api/analyze", files=files(seed), data={"jurisdiction": "IN"}, timeout=120)
    analysis = expect(
        "analyze.image",
        r,
        predicate=lambda resp, b: (
            f"id={b['id']} decision={b['decision']} pid={b['provenanceId']} notice={b.get('notice', {}).get('noticeNumber')}"
        ),
    )
    if not analysis:
        record("analyze.followups", "BLOCKED", "analyze failed")
    else:
        ctx["analysis"] = analysis
        notice = analysis.get("notice") or {}
        ctx["noticeNumber"] = notice.get("noticeNumber")
        ctx["pin"] = notice.get("pin")
        ctx["provenanceId"] = analysis["provenanceId"]
        ctx["id"] = analysis["id"]
        ctx["phash"] = analysis.get("phash")
        ctx["decision"] = analysis["decision"]

        r = requests.get(f"{BASE}/api/verify/{analysis['provenanceId']}", timeout=30)
        expect(
            "verify.provenance",
            r,
            predicate=lambda resp, b: f"events={len(b.get('events') or [])}",
        )

        # labelled copy verify if present
        labelled = analysis.get("labelled") or analysis.get("labelledImage")
        # analyze may return labelled media as base64 under various keys — skip soft
        record("verify.upload.labelled", "SKIP", "no separate labelled re-upload in this sweep")

    # second analyze with different seed for investigation linking
    r = requests.post(f"{BASE}/api/analyze", files=files(seed + 17), data={"jurisdiction": "IN"}, timeout=120)
    analysis2 = expect(
        "analyze.image.second",
        r,
        predicate=lambda resp, b: f"id={b['id']} decision={b['decision']}",
    )
    if analysis2:
        ctx["id2"] = analysis2["id"]

    # --- API v1 with demo key ---
    r = requests.post(
        f"{BASE}/api/v1/analyze",
        files=files(seed + 3),
        headers={"X-API-Key": API_KEY},
        timeout=120,
    )
    expect(
        "api.v1.analyze",
        r,
        ok={200, 401, 403, 429},
        predicate=lambda resp, b: (
            f"ok id={b.get('id')}" if resp.status_code == 200 else f"status={resp.status_code} {b}"
        ),
    )
    r = requests.get(f"{BASE}/api/v1/usage", headers={"X-API-Key": API_KEY}, timeout=30)
    expect("api.v1.usage", r, ok={200, 401, 403}, predicate=lambda resp, b: str(b)[:120])

    # --- queue ---
    r = session.get(f"{BASE}/api/queue", params={"status": "review"})
    expect("queue.review", r, predicate=lambda resp, b: f"n={len(b)}")
    r = session.get(f"{BASE}/api/queue", params={"status": "flagged"})
    expect("queue.flagged", r, predicate=lambda resp, b: f"n={len(b)}")
    r = session.get(f"{BASE}/api/queue", params={"status": "all"})
    expect("queue.all", r, predicate=lambda resp, b: f"n={len(b)}")

    if analysis and analysis.get("decision") == "REVIEW":
        aid = analysis["id"]
        r = session.post(f"{BASE}/api/queue/{aid}/assign", json={})
        expect("queue.assign.self", r, ok={200, 204, 409}, predicate=lambda resp, b: str(b)[:100] if b else "empty")
        r = session.post(f"{BASE}/api/queue/{aid}/decision", json={"decision": "ALLOWED"})
        expect("queue.decision.ALLOWED", r, predicate=lambda resp, b: f"decision={b.get('decision')}")
        r = session.get(f"{BASE}/api/queue/{aid}/audit")
        expect("queue.audit", r, predicate=lambda resp, b: f"n={len(b) if isinstance(b, list) else b}")
        # escalate needs an item — use id2 if in review
        if analysis2 and analysis2.get("decision") == "REVIEW":
            r = session.post(
                f"{BASE}/api/queue/{analysis2['id']}/escalate",
                json={"reason": "Live stack test escalation"},
            )
            expect("queue.escalate", r, ok={200, 409, 400}, predicate=lambda resp, b: str(b)[:120])
            r = session.post(f"{BASE}/api/queue/{analysis2['id']}/unassign", json={})
            expect("queue.unassign", r, ok={200, 204, 409, 400}, predicate=lambda resp, b: str(b)[:80] if b else "ok")
    else:
        record("queue.decision.ALLOWED", "SKIP", f"decision was {analysis.get('decision') if analysis else None}")

    # Force AUTO_FLAG path for appeal: decide flagged or analyze until high score.
    # With review threshold 0.05, synthetic often hits REVIEW; force AUTO_FLAG via decision if needed.
    appeal_item = None
    appeal_notice = None
    appeal_pin = None
    if analysis and analysis.get("decision") == "AUTO_FLAG":
        appeal_item = analysis
        appeal_notice = ctx.get("noticeNumber")
        appeal_pin = ctx.get("pin")
    else:
        # New upload then moderator sets AUTO_FLAG (may need second review for leaving AUTO_FLAG,
        # but setting TO AUTO_FLAG from REVIEW should work)
        r = requests.post(f"{BASE}/api/analyze", files=files(seed + 99), data={"jurisdiction": "IN"}, timeout=120)
        flag_src = expect(
            "analyze.for.appeal",
            r,
            predicate=lambda resp, b: f"id={b['id']} decision={b['decision']}",
        )
        if flag_src:
            appeal_notice = (flag_src.get("notice") or {}).get("noticeNumber")
            appeal_pin = (flag_src.get("notice") or {}).get("pin")
            fid = flag_src["id"]
            if flag_src["decision"] != "AUTO_FLAG":
                r = session.post(f"{BASE}/api/queue/{fid}/decision", json={"decision": "AUTO_FLAG"})
                expect("queue.decision.AUTO_FLAG", r, predicate=lambda resp, b: f"decision={b.get('decision')}")
                # refresh decision
                flag_src["decision"] = "AUTO_FLAG"
            appeal_item = flag_src
            ctx["appeal_id"] = fid

    # --- report + verify-report ---
    report_id = None
    target_id = ctx.get("appeal_id") or ctx.get("id")
    if target_id:
        r = session.post(f"{BASE}/api/queue/{target_id}/report")
        if r.status_code == 200:
            report_id = r.headers.get("X-Report-Id")
            panels = r.headers.get("X-Report-Panels")
            cd = r.headers.get("Content-Disposition", "")
            pdf_ok = r.content[:5] == b"%PDF-"
            text_snip = ""
            try:
                # light check without pdf lib
                ascii_part = r.content.decode("latin-1", errors="ignore")
                markers = [
                    "Decision record",
                    "Decision record v2",
                    "Perceptual hash mosaic",
                    "Grad-CAM",
                    "Score meters",
                    "Stored fingerprints",
                ]
                found = [m for m in markers if m in ascii_part]
                text_snip = ",".join(found) or "no-ascii-markers"
            except Exception:
                text_snip = "decode-fail"
            record(
                "report.download",
                "PASS" if pdf_ok else "FAIL",
                f"{report_id} panels={panels} {cd} markers={text_snip} bytes={len(r.content)}",
                r.status_code,
            )
            if report_id:
                r2 = session.get(f"{BASE}/api/verify-report/{report_id}")
                expect(
                    "verify.report",
                    r2,
                    predicate=lambda resp, b: f"status={b.get('status')} alg={b.get('algorithm')}",
                )
        else:
            record("report.download", "FAIL", r.text[:200], r.status_code)

    r = session.get(f"{BASE}/api/verify-report/keys")
    expect("verify.report.keys", r, predicate=lambda resp, b: f"n={len(b) if isinstance(b, list) else b}")

    # --- appeal ---
    if appeal_item and appeal_notice and appeal_pin and appeal_item.get("decision") == "AUTO_FLAG":
        r = requests.get(
            f"{BASE}/api/appeals/{appeal_notice}/status",
            headers={"X-Case-Pin": appeal_pin},
            timeout=30,
        )
        expect(
            "appeal.status.before",
            r,
            predicate=lambda resp, b: f"appealable={b.get('appealable')} decision={b.get('decision')}",
        )
        r = requests.post(
            f"{BASE}/api/appeals",
            json={
                "noticeNumber": appeal_notice,
                "pin": appeal_pin,
                "statement": "Live stack test: I believe this automatic removal is incorrect.",
            },
            timeout=30,
        )
        filed = expect(
            "appeal.file",
            r,
            ok={200, 201},
            predicate=lambda resp, b: f"case={b.get('caseNumber')} status={b.get('status')}",
        )
        r = session.get(f"{BASE}/api/appeals", params={"status": "open"})
        open_appeals = expect("appeals.list.open", r, predicate=lambda resp, b: f"n={len(b)}")
        # Decide with Admin — may be conflicted if Admin made the AUTO_FLAG decision
        appeal_row = None
        if isinstance(open_appeals, list):
            for a in open_appeals:
                if a.get("caseNumber") == appeal_notice or a.get("analysisId") == appeal_item.get("id") or a.get("analysisId") == ctx.get("appeal_id"):
                    appeal_row = a
                    break
            if appeal_row is None and open_appeals:
                appeal_row = open_appeals[0]
        if appeal_row and appeal_row.get("canDecide"):
            r = session.post(
                f"{BASE}/api/appeals/{appeal_row['id']}/decision",
                json={"outcome": "UPHOLD", "reason": "Live stack test: scores still support removal."},
            )
            expect("appeal.decide.UPHOLD", r, predicate=lambda resp, b: f"status={b.get('status')}")
        elif appeal_row:
            record(
                "appeal.decide.UPHOLD",
                "SKIP",
                f"canDecide=false conflicted={appeal_row.get('conflicted')} (need second moderator)",
            )
        else:
            record("appeal.decide.UPHOLD", "SKIP", "no open appeal row found")
    else:
        record("appeal.file", "SKIP", "no AUTO_FLAG item with notice/pin")

    # --- public complaint (reporter wizard) ---
    r = requests.post(
        f"{BASE}/api/complaints",
        files=files(seed + 5, "complaint.jpg"),
        data={
            "relationship": "SELF",
            "consent": "true",
            "details": "Live stack synthetic complaint for feature sweep.",
            "contact": "live-test@example.org",
            "jurisdiction": "IN",
            "locale": "en",
        },
        timeout=120,
    )
    complaint = expect(
        "complaints.file",
        r,
        ok={200, 201},
        predicate=lambda resp, b: f"case={b.get('caseNumber')} pin_returned={'pin' in b}",
    )
    if complaint and complaint.get("caseNumber") and complaint.get("pin"):
        r = requests.get(
            f"{BASE}/api/complaints/{complaint['caseNumber']}/status",
            headers={"X-Case-Pin": complaint["pin"]},
            timeout=30,
        )
        expect("complaints.status", r, predicate=lambda resp, b: f"stage={b.get('stage') or b.get('status')}")

    # --- compliance ---
    r = session.post(
        f"{BASE}/api/compliance/complaints",
        files=files(seed + 7),
        data={
            "complaintType": "USER_COMPLAINT",
            "reference": f"LIVE-{seed}",
            "jurisdiction": "IN",
        },
        timeout=120,
    )
    comp = expect(
        "compliance.file",
        r,
        predicate=lambda resp, b: f"id={b.get('id')} sla={b.get('slaStatus')} j={b.get('jurisdiction')}",
    )
    r = session.get(f"{BASE}/api/compliance/queue")
    expect("compliance.queue", r, predicate=lambda resp, b: f"n={len(b)}")
    r = session.get(f"{BASE}/api/compliance/summary")
    expect("compliance.summary", r, predicate=lambda resp, b: str(list(b.keys())[:8]))
    r = session.get(f"{BASE}/api/compliance/breaches")
    expect("compliance.breaches", r, predicate=lambda resp, b: f"n={len(b)}")
    r = session.get(f"{BASE}/api/compliance/rules")
    expect("compliance.rules", r, predicate=lambda resp, b: f"n={len(b)}")

    # --- prescreen ---
    phash = ctx.get("phash") or "c2423d3d3b783cc6"
    r = requests.post(
        f"{BASE}/api/prescreen",
        json={"hash": phash, "algorithm": "phash"},
        timeout=30,
    )
    expect("prescreen.hash", r, predicate=lambda resp, b: f"match={b.get('match')} source={b.get('source')}")
    r = session.get(f"{BASE}/api/admin/known-hashes")
    expect("admin.known-hashes", r, ok={200, 403}, predicate=lambda resp, b: f"n={len(b) if isinstance(b, list) else b}")

    # --- admin ---
    r = session.get(f"{BASE}/api/admin/overview")
    expect("admin.overview", r, ok={200, 403}, predicate=lambda resp, b: str(list(b.keys())[:8]) if isinstance(b, dict) else str(b)[:80])
    r = session.get(f"{BASE}/api/admin/accounts")
    expect("admin.accounts", r, ok={200, 403}, predicate=lambda resp, b: f"n={len(b)}")
    r = session.get(f"{BASE}/api/admin/clients")
    clients = expect("admin.clients.list", r, ok={200, 403}, predicate=lambda resp, b: f"n={len(b)}")
    if r.status_code == 200:
        r = session.post(
            f"{BASE}/api/admin/clients",
            json={"name": f"LiveTest-{seed}", "plan": "FREE"},
        )
        created = expect(
            "admin.clients.create",
            r,
            ok={200, 201},
            predicate=lambda resp, b: f"id={b.get('id')} key_once={'apiKey' in b or 'key' in b}",
        )
        if created and created.get("id"):
            cid = created["id"]
            r = session.post(f"{BASE}/api/admin/clients/{cid}/plan", json={"plan": "PRO"})
            expect("admin.clients.plan", r, predicate=lambda resp, b: f"plan={b.get('plan')}")
            r = session.post(f"{BASE}/api/admin/clients/{cid}/rotate")
            expect("admin.clients.rotate", r, predicate=lambda resp, b: f"rotated={'apiKey' in b or 'key' in b}")
            r = session.post(f"{BASE}/api/admin/clients/{cid}/revoke")
            expect("admin.clients.revoke", r, predicate=lambda resp, b: f"revoked={b.get('revoked') or b.get('status')}")

    # --- investigations ---
    inv_id = ctx.get("id") or (comp.get("id") if isinstance(comp, dict) else None)
    if inv_id:
        r = session.post(f"{BASE}/api/investigations", json={"analysisId": inv_id, "title": f"Live case {seed}"})
        inv = expect(
            "investigations.open",
            r,
            ok={200, 201},
            predicate=lambda resp, b: f"case={b.get('caseNumber')} status={b.get('status')}",
        )
        if inv and inv.get("caseNumber"):
            cn = inv["caseNumber"]
            r = session.get(f"{BASE}/api/investigations/{cn}")
            expect("investigations.get", r, predicate=lambda resp, b: f"items={len(b.get('items') or [])}")
            r = session.get(f"{BASE}/api/investigations", params={"status": "OPEN"})
            expect("investigations.list", r, predicate=lambda resp, b: f"n={len(b)}")
            if ctx.get("id2"):
                r = session.post(f"{BASE}/api/investigations/{cn}/items", json={"analysisId": ctx["id2"]})
                expect("investigations.add_item", r, ok={200, 201, 409}, predicate=lambda resp, b: str(b)[:100])
            r = session.post(
                f"{BASE}/api/investigations/{cn}/sightings",
                json={
                    "platform": "mock-feed",
                    "url": f"https://example.test/post/{seed}",
                    "observedAt": datetime.now(timezone.utc).isoformat(),
                    "note": "Live stack sighting",
                },
            )
            expect("investigations.sighting", r, ok={200, 201, 400}, predicate=lambda resp, b: str(b)[:120])
            r = session.post(f"{BASE}/api/investigations/{cn}/notes", json={"body": "Live stack note"})
            expect("investigations.note", r, ok={200, 201}, predicate=lambda resp, b: str(b)[:80])
            r = session.get(f"{BASE}/api/investigations/{cn}/custody")
            expect("investigations.custody", r, predicate=lambda resp, b: str(list(b.keys())[:6]) if isinstance(b, dict) else f"n={len(b)}")
            r = session.post(f"{BASE}/api/investigations/{cn}/preservations", json={"note": "Live preserve"})
            preserv = expect(
                "investigations.preserve",
                r,
                ok={200, 201, 400, 409},
                predicate=lambda resp, b: f"id={b.get('preservationId') or b.get('id')}",
            )
            r = session.post(
                f"{BASE}/api/investigations/{cn}/legal-holds",
                json={"authority": "Live Test Authority", "reference": f"LH-{seed}", "note": "hold"},
            )
            hold = expect(
                "investigations.legal_hold",
                r,
                ok={200, 201, 400, 409},
                predicate=lambda resp, b: f"hold={b.get('holdNumber')}",
            )
            r = session.get(f"{BASE}/api/legal-requests")
            expect("legal_requests.list", r, predicate=lambda resp, b: f"n={len(b)}")
            r = session.get(f"{BASE}/api/legal-requests/investigators")
            invs = expect("legal_requests.investigators", r, predicate=lambda resp, b: f"n={len(b)}")
            investigator = None
            if isinstance(invs, list) and invs:
                investigator = invs[0].get("username") or invs[0].get("name")
            if investigator:
                expires = (datetime.now(timezone.utc) + timedelta(days=30)).isoformat()
                r = session.post(
                    f"{BASE}/api/legal-requests",
                    json={
                        "authority": "Live Stack Court",
                        "orderReference": f"ORD-{seed}",
                        "investigator": investigator,
                        "caseNumbers": [cn],
                        "expiresAt": expires,
                        "note": "Live stack legal request",
                    },
                )
                lr = expect(
                    "legal_requests.create",
                    r,
                    ok={200, 201, 400},
                    predicate=lambda resp, b: f"req={b.get('requestNumber')} status={b.get('status')}",
                )
                if lr and lr.get("requestNumber"):
                    r = session.post(
                        f"{BASE}/api/legal-requests/{lr['requestNumber']}/approve",
                        json={"note": "approved in live test"},
                    )
                    expect(
                        "legal_requests.approve",
                        r,
                        ok={200, 400, 403, 409},
                        predicate=lambda resp, b: f"status={b.get('status')} err={b.get('error')}",
                    )
            else:
                record("legal_requests.create", "SKIP", "no investigator accounts seeded")
            r = session.post(f"{BASE}/api/investigations/{cn}/close", json={"note": "Live stack close"})
            expect("investigations.close", r, ok={200, 400, 409}, predicate=lambda resp, b: f"status={b.get('status')}")
            r = session.post(f"{BASE}/api/investigations/{cn}/reopen", json={"note": "Live stack reopen"})
            expect("investigations.reopen", r, ok={200, 400, 409}, predicate=lambda resp, b: f"status={b.get('status')}")
    else:
        record("investigations.open", "BLOCKED", "no analysis id")

    # --- wellbeing break ---
    r = session.post(f"{BASE}/api/moderation/break")
    expect("moderation.break", r, ok={200, 204}, predicate=lambda resp, b: str(b)[:80] if b else "ok")

    # --- frontend / mock feed reachability ---
    for label, url in [
        ("ui.vite", "http://localhost:5173/"),
        ("ui.docker", "http://localhost:3000/"),
        ("mock.feed", "http://localhost:3001/"),
        ("grafana", "http://localhost:3002/login"),
        ("prometheus", "http://localhost:9090/-/healthy"),
    ]:
        try:
            resp = requests.get(url, timeout=15)
            expect(label, resp, ok={200, 301, 302}, predicate=lambda resp, b: f"len={len(resp.content)}")
        except Exception as ex:
            record(label, "FAIL", str(ex))

    # summary
    counts = {k: 0 for k in ("PASS", "FAIL", "SKIP", "BLOCKED")}
    for row in RESULTS:
        counts[row.status] += 1
    summary = {
        "at": datetime.now(timezone.utc).isoformat(),
        "base": BASE,
        "counts": counts,
        "results": [row.__dict__ for row in RESULTS],
        "ctx": {k: v for k, v in ctx.items() if k not in ("pin",) and not str(k).endswith("pin")},
    }
    # keep pin out of saved file
    OUT.write_text(json.dumps(summary, indent=2), encoding="utf-8")
    print("\n=== SUMMARY ===")
    print(json.dumps(counts, indent=2))
    print(f"Wrote {OUT}")
    return 0 if counts["FAIL"] == 0 else 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception:
        traceback.print_exc()
        sys.exit(2)
