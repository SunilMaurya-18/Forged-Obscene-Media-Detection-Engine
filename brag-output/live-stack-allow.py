import requests

BASE = "http://localhost:8081"
r = requests.Session()
r.auth = ("reviewer", "reviewer")
q = r.get(f"{BASE}/api/queue", params={"status": "review"}).json()
print("review", len(q))
if not q:
    print("SKIP no review items")
else:
    d = r.post(f"{BASE}/api/queue/{q[0]['id']}/decision", json={"decision": "ALLOW"})
    print("ALLOW", d.status_code, d.text[:200])
