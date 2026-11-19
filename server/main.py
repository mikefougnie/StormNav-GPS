from datetime import datetime, timedelta, timezone
from pathlib import Path
import sqlite3
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

DB = Path(__file__).with_name("stormnav.sqlite3")
REPORT_TTL_MINUTES = 90
ALLOWED_TYPES = {"Flooding", "Hail", "Fallen tree", "Road hazard", "High winds", "Accident"}
app = FastAPI(title="StormNav Community Reports", version="0.1.0")


def connect():
    con = sqlite3.connect(DB)
    con.row_factory = sqlite3.Row
    return con


def init_db():
    with connect() as con:
        con.execute("""CREATE TABLE IF NOT EXISTS reports (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            type TEXT NOT NULL,
            lat REAL NOT NULL,
            lon REAL NOT NULL,
            created_at TEXT NOT NULL,
            confirmations INTEGER NOT NULL DEFAULT 0,
            dismissed INTEGER NOT NULL DEFAULT 0
        )""")


class ReportIn(BaseModel):
    type: str
    lat: float = Field(ge=-90, le=90)
    lon: float = Field(ge=-180, le=180)


@app.on_event("startup")
def startup():
    init_db()


def expire_old(con):
    cutoff = (datetime.now(timezone.utc) - timedelta(minutes=REPORT_TTL_MINUTES)).isoformat()
    con.execute("DELETE FROM reports WHERE created_at < ?", (cutoff,))


@app.get("/health")
def health():
    return {"ok": True, "service": "StormNav reports"}


@app.get("/reports")
def list_reports():
    with connect() as con:
        expire_old(con)
        rows = con.execute("SELECT id,type,lat,lon,created_at,confirmations FROM reports WHERE dismissed=0 ORDER BY created_at DESC LIMIT 300").fetchall()
        return [dict(row) for row in rows]


@app.post("/reports", status_code=201)
def create_report(report: ReportIn):
    if report.type not in ALLOWED_TYPES:
        raise HTTPException(status_code=400, detail="Unsupported report type")
    now = datetime.now(timezone.utc).isoformat()
    with connect() as con:
        expire_old(con)
        # Small-community abuse guard: no more than 5 reports from the same type/coordinate
        # neighborhood is enforced by duplicate suppression rather than collecting user identity.
        near = con.execute("""SELECT id FROM reports WHERE type=? AND ABS(lat-?) < 0.003 AND ABS(lon-?) < 0.003
            AND created_at > ? AND dismissed=0 LIMIT 1""", (report.type, report.lat, report.lon,
            (datetime.now(timezone.utc)-timedelta(minutes=10)).isoformat())).fetchone()
        if near:
            raise HTTPException(status_code=409, detail="A similar recent report already exists nearby")
        cur = con.execute("INSERT INTO reports(type,lat,lon,created_at) VALUES(?,?,?,?)",
                          (report.type, report.lat, report.lon, now))
        return {"id": cur.lastrowid, "type": report.type, "lat": report.lat, "lon": report.lon, "created_at": now, "confirmations": 0}


@app.post("/reports/{report_id}/confirm")
def confirm_report(report_id: int):
    with connect() as con:
        cur = con.execute("UPDATE reports SET confirmations=confirmations+1 WHERE id=? AND dismissed=0", (report_id,))
        if cur.rowcount == 0:
            raise HTTPException(status_code=404, detail="Report not found or expired")
        row = con.execute("SELECT id,confirmations FROM reports WHERE id=?", (report_id,)).fetchone()
        return dict(row)


@app.post("/reports/{report_id}/dismiss")
def dismiss_report(report_id: int):
    with connect() as con:
        cur = con.execute("UPDATE reports SET dismissed=1 WHERE id=?", (report_id,))
        if cur.rowcount == 0:
            raise HTTPException(status_code=404, detail="Report not found")
        return {"ok": True}
