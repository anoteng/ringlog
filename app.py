"""RingLog - Bird registry for ducks and hens."""

import csv
import io
import json
import os
import re
import secrets
import urllib.request
import zipfile
from functools import wraps
from datetime import date as _date, datetime, timedelta

import pymysql
import pymysql.cursors
from flask import (
    Flask, render_template, request, redirect, url_for, g, send_file, flash, session
)
from werkzeug.security import generate_password_hash, check_password_hash
from io import BytesIO
from dotenv import load_dotenv

load_dotenv(os.path.join(os.path.dirname(__file__), ".env"))

app = Flask(__name__)
app.secret_key = os.environ.get("SECRET_KEY") or os.urandom(24)

MAIL_FROM    = os.environ.get("MAIL_FROM", "ringlog@noteng.no")
BREVO_API_KEY = os.environ.get("BREVO_API_KEY", "")

DB_CONFIG = dict(
    host=os.environ.get("DB_HOST", "localhost"),
    user=os.environ["DB_USER"],
    password=os.environ["DB_PASS"],
    database=os.environ["DB_NAME"],
    charset="utf8mb4",
    cursorclass=pymysql.cursors.DictCursor,
)


# ---------------------------------------------------------------------------
# DB wrapper — sqlite3-compatible interface over PyMySQL
# ---------------------------------------------------------------------------

class _Conn:
    """Wraps a PyMySQL connection to mimic sqlite3's db.execute() interface."""
    def __init__(self, conn):
        self._c = conn

    def execute(self, sql, params=()):
        cur = self._c.cursor()
        cur.execute(sql.replace("?", "%s"), params or ())
        return cur

    def commit(self):
        self._c.commit()

    def close(self):
        self._c.close()


def get_db():
    if "db" not in g:
        g.db = _Conn(pymysql.connect(**DB_CONFIG))
    return g.db


@app.teardown_appcontext
def close_db(exc):
    db = g.pop("db", None)
    if db:
        db.close()


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _natural_key(s):
    return [int(c) if c.isdigit() else c.lower() for c in re.split(r"(\d+)", s or "")]


def slugify(name):
    return re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-")


@app.template_filter("age")
def age_filter(birth_date):
    if not birth_date:
        return "—"
    try:
        bd = birth_date if isinstance(birth_date, _date) else _date.fromisoformat(str(birth_date))
        today = _date.today()
        months = (today.year - bd.year) * 12 + (today.month - bd.month)
        if months < 1:
            return "< 1 mo"
        if months < 24:
            return f"{months} mo"
        return f"{months // 12} yr"
    except (ValueError, TypeError):
        return "—"


# ---------------------------------------------------------------------------
# Auth
# ---------------------------------------------------------------------------

@app.before_request
def load_user():
    uid = session.get("user_id")
    g.user = get_db().execute("SELECT * FROM users WHERE id = ?", (uid,)).fetchone() if uid else None


@app.context_processor
def inject_user():
    return {"current_user": g.user}


def login_required(f):
    @wraps(f)
    def decorated(*args, **kwargs):
        if not g.user:
            flash("Please log in to continue.", "error")
            return redirect(url_for("login", next=request.path))
        return f(*args, **kwargs)
    return decorated


# ---------------------------------------------------------------------------
# Flock access
# ---------------------------------------------------------------------------

def get_permission(flock_id):
    """Return 'edit', 'view', or None for g.user on the given flock."""
    if not g.user:
        return None
    db = get_db()
    flock = db.execute("SELECT user_id FROM flocks WHERE id = ?", (flock_id,)).fetchone()
    if not flock:
        return None
    if g.user["id"] == flock["user_id"]:
        return "edit"
    share = db.execute(
        "SELECT can_edit FROM flock_shares WHERE flock_id = ? AND grantee_id = ?",
        (flock_id, g.user["id"])
    ).fetchone()
    if not share:
        return None
    return "edit" if share["can_edit"] else "view"


def ring_number_taken(flock, ring_number, exclude_bird_id=None):
    """Check if ring_number is already in use in the flock.
    If flock.allow_ring_reuse, only living birds are checked."""
    db = get_db()
    sql = "SELECT id FROM birds WHERE flock_id = ? AND ring_number = ?"
    params = [flock["id"], ring_number]
    if flock["allow_ring_reuse"]:
        sql += " AND is_dead = 0 AND is_sold = 0"
    if exclude_bird_id:
        sql += " AND id != ?"
        params.append(exclude_bird_id)
    return db.execute(sql, params).fetchone() is not None


def resolve_flock(username, flock_name):
    """Look up a flock by owner username + flock name (case-insensitive)."""
    return get_db().execute(
        """SELECT f.*, u.username
           FROM flocks f JOIN users u ON u.id = f.user_id
           WHERE u.username = ? AND LOWER(f.name) = LOWER(?)""",
        (username, flock_name)
    ).fetchone()


def user_flocks(user_id):
    """All flocks owned by a user."""
    return get_db().execute(
        "SELECT * FROM flocks WHERE user_id = ? ORDER BY name", (user_id,)
    ).fetchall()


def shared_flocks_for(user_id):
    """Flocks shared with a user (not owned by them)."""
    return get_db().execute(
        """SELECT f.*, u.username, fs.can_edit
           FROM flock_shares fs
           JOIN flocks f ON f.id = fs.flock_id
           JOIN users u ON u.id = f.user_id
           WHERE fs.grantee_id = ?
           ORDER BY u.username, f.name""",
        (user_id,)
    ).fetchall()


# ---------------------------------------------------------------------------
# Email
# ---------------------------------------------------------------------------

def send_email(to, subject, body):
    payload = json.dumps({
        "sender":  {"email": MAIL_FROM, "name": "RingLog"},
        "to":      [{"email": to}],
        "subject": subject,
        "textContent": body,
    }).encode()
    req = urllib.request.Request(
        "https://api.brevo.com/v3/smtp/email",
        data=payload,
        headers={
            "api-key": BREVO_API_KEY,
            "Content-Type": "application/json",
            "Accept": "application/json",
        },
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=10) as resp:
        if resp.status not in (200, 201):
            raise RuntimeError(f"Brevo API error {resp.status}")


# ---------------------------------------------------------------------------
# Auth routes
# ---------------------------------------------------------------------------

@app.route("/register", methods=["GET", "POST"])
def register():
    if g.user:
        return redirect(url_for("flocks"))
    if request.method == "POST":
        username       = request.form.get("username", "").strip()
        email          = request.form.get("email", "").strip() or None
        password       = request.form.get("password", "")
        confirm        = request.form.get("confirm", "")
        accept_terms   = request.form.get("accept_terms")
        if not accept_terms:
            flash("You must accept the Terms of Use to register.", "error")
        elif not username or len(username) < 3:
            flash("Username must be at least 3 characters.", "error")
        elif not username.replace("_", "").isalnum():
            flash("Username may only contain letters, numbers, and underscores.", "error")
        elif len(password) < 8:
            flash("Password must be at least 8 characters.", "error")
        elif password != confirm:
            flash("Passwords do not match.", "error")
        else:
            try:
                db = get_db()
                db.execute(
                    "INSERT INTO users (username, email, password_hash, terms_accepted_at) VALUES (?, ?, ?, NOW())",
                    (username, email, generate_password_hash(password))
                )
                db.commit()
                user = db.execute("SELECT * FROM users WHERE username = ?", (username,)).fetchone()
                session.clear()
                session["user_id"] = user["id"]
                flash(f"Welcome, {user['username']}!", "success")
                return redirect(url_for("flocks"))
            except pymysql.IntegrityError as e:
                if "email" in str(e).lower():
                    flash("That email address is already registered.", "error")
                else:
                    flash("Username already taken.", "error")
    return render_template("register.html")


@app.route("/login", methods=["GET", "POST"])
def login():
    if g.user:
        return redirect(url_for("flocks"))
    if request.method == "POST":
        username = request.form.get("username", "").strip()
        password = request.form.get("password", "")
        user = get_db().execute("SELECT * FROM users WHERE username = ?", (username,)).fetchone()
        if user and check_password_hash(user["password_hash"], password):
            session.clear()
            session["user_id"] = user["id"]
            next_url = request.args.get("next", "")
            if not next_url.startswith("/"):
                next_url = url_for("flocks")
            flash(f"Welcome back, {user['username']}!", "success")
            return redirect(next_url)
        flash("Invalid username or password.", "error")
    return render_template("login.html")


@app.route("/logout", methods=["POST"])
def logout():
    session.clear()
    return redirect(url_for("login"))


@app.route("/forgot-password", methods=["GET", "POST"])
def forgot_password():
    if g.user:
        return redirect(url_for("flocks"))
    if request.method == "POST":
        email = request.form.get("email", "").strip()
        if email:
            db = get_db()
            user = db.execute("SELECT * FROM users WHERE email = ?", (email,)).fetchone()
            if user:
                token = secrets.token_urlsafe(32)
                expires = datetime.now() + timedelta(hours=2)
                db.execute("DELETE FROM password_resets WHERE user_id = ?", (user["id"],))
                db.execute(
                    "INSERT INTO password_resets (user_id, token, expires_at) VALUES (?,?,?)",
                    (user["id"], token, expires)
                )
                db.commit()
                reset_url = url_for("reset_password", token=token, _external=True)
                try:
                    send_email(
                        to=email,
                        subject="RingLog — password reset",
                        body=(
                            f"Hi {user['username']},\n\n"
                            f"Click the link below to reset your password. "
                            f"The link is valid for 2 hours.\n\n"
                            f"{reset_url}\n\n"
                            f"If you didn't request this, you can ignore this email.\n\n"
                            f"— RingLog"
                        ),
                    )
                except Exception:
                    flash("Failed to send email. Please try again later.", "error")
                    return render_template("forgot_password.html")
        # Always show the same message to avoid user enumeration
        flash("If that email address is registered, a reset link has been sent.", "success")
        return redirect(url_for("login"))
    return render_template("forgot_password.html")


@app.route("/reset-password/<token>", methods=["GET", "POST"])
def reset_password(token):
    if g.user:
        return redirect(url_for("flocks"))
    db = get_db()
    row = db.execute(
        "SELECT * FROM password_resets WHERE token = ? AND expires_at > NOW()",
        (token,)
    ).fetchone()
    if not row:
        flash("This reset link is invalid or has expired.", "error")
        return redirect(url_for("forgot_password"))

    if request.method == "POST":
        password = request.form.get("password", "")
        confirm  = request.form.get("confirm", "")
        if len(password) < 8:
            flash("Password must be at least 8 characters.", "error")
        elif password != confirm:
            flash("Passwords do not match.", "error")
        else:
            db.execute(
                "UPDATE users SET password_hash = ? WHERE id = ?",
                (generate_password_hash(password), row["user_id"])
            )
            db.execute("DELETE FROM password_resets WHERE token = ?", (token,))
            db.commit()
            flash("Password updated. You can now log in.", "success")
            return redirect(url_for("login"))

    return render_template("reset_password.html", token=token)


# ---------------------------------------------------------------------------
# Flock list / create
# ---------------------------------------------------------------------------

@app.route("/")
def index():
    if g.user:
        return redirect(url_for("flocks"))
    return render_template("landing.html")


@app.route("/terms")
def terms():
    return render_template("terms.html")


@app.route("/flocks", methods=["GET", "POST"])
@login_required
def flocks():
    db = get_db()
    if request.method == "POST":
        name = request.form.get("name", "").strip()
        description = request.form.get("description", "").strip() or None
        if not name:
            flash("Flock name is required.", "error")
        else:
            try:
                db.execute(
                    "INSERT INTO flocks (user_id, name, description) VALUES (?, ?, ?)",
                    (g.user["id"], name, description)
                )
                db.commit()
                flash(f"Flock '{name}' created.", "success")
                return redirect(url_for("view_flock", username=g.user["username"], flock_name=name))
            except pymysql.IntegrityError:
                flash("You already have a flock with that name.", "error")

    my_flocks = user_flocks(g.user["id"])
    shared    = shared_flocks_for(g.user["id"])

    # Bird counts per flock
    counts = {}
    for f in my_flocks:
        row = db.execute("SELECT COUNT(*) AS n FROM birds WHERE flock_id = ?", (f["id"],)).fetchone()
        counts[f["id"]] = row["n"]

    return render_template("flocks.html", my_flocks=my_flocks, shared=shared, counts=counts)


@app.route("/flock/<username>/<flock_name>/delete", methods=["GET", "POST"])
@login_required
def delete_flock(username, flock_name):
    flock = resolve_flock(username, flock_name)
    if not flock or flock["user_id"] != g.user["id"]:
        flash("Access denied.", "error")
        return redirect(url_for("flocks"))

    db = get_db()
    bird_count = db.execute(
        "SELECT COUNT(*) AS n FROM birds WHERE flock_id = ?", (flock["id"],)
    ).fetchone()["n"]

    if request.method == "POST":
        db.execute("DELETE FROM flocks WHERE id = ?", (flock["id"],))
        db.commit()
        flash(f"Flock '{flock['name']}' deleted.", "success")
        return redirect(url_for("flocks"))

    return render_template("delete_flock_confirm.html", flock=flock, bird_count=bird_count)


# ---------------------------------------------------------------------------
# Flock view
# ---------------------------------------------------------------------------

@app.route("/flock/<username>/<flock_name>")
@login_required
def view_flock(username, flock_name):
    flock = resolve_flock(username, flock_name)
    if not flock:
        flash("Flock not found.", "error")
        return redirect(url_for("flocks"))

    perm = get_permission(flock["id"])
    if not perm:
        flash("You don't have access to that flock.", "error")
        return redirect(url_for("flocks"))

    db = get_db()
    filter_species = request.args.get("species", "")
    filter_status  = request.args.get("status", "alive")

    query = """SELECT id, ring_number, name, species, breed, breed_mix, sex,
                      birth_date, birth_approximate, is_dead, is_sold,
                      image IS NOT NULL AS has_image
               FROM birds WHERE flock_id = ?"""
    params = [flock["id"]]
    if filter_species:
        query += " AND species = ?"
        params.append(filter_species)
    if filter_status == "alive":
        query += " AND is_dead = 0 AND is_sold = 0"
    elif filter_status == "dead":
        query += " AND is_dead = 1"
    elif filter_status == "sold":
        query += " AND is_sold = 1"

    birds = sorted(db.execute(query, params).fetchall(), key=lambda b: _natural_key(b["ring_number"]))
    shared = shared_flocks_for(g.user["id"])

    return render_template(
        "index.html",
        flock=flock,
        birds=birds,
        filter_species=filter_species,
        filter_status=filter_status,
        can_edit=(perm == "edit"),
        is_own_flock=(flock["user_id"] == g.user["id"]),
        shared_flocks=shared,
        my_flocks=user_flocks(g.user["id"]),
    )


# ---------------------------------------------------------------------------
# Flock export
# ---------------------------------------------------------------------------

@app.route("/flock/<username>/<flock_name>/export")
@login_required
def export_flock(username, flock_name):
    flock = resolve_flock(username, flock_name)
    if not flock:
        flash("Flock not found.", "error")
        return redirect(url_for("flocks"))
    if not get_permission(flock["id"]):
        flash("Access denied.", "error")
        return redirect(url_for("flocks"))

    db = get_db()
    birds = db.execute(
        """SELECT id, ring_number, name, species, breed, breed_mix, sex,
                  birth_date, birth_approximate, notes,
                  is_dead, death_date, is_sold, sold_date, created_at
           FROM birds WHERE flock_id = ? ORDER BY ring_number""",
        (flock["id"],)
    ).fetchall()

    bird_ids = [b["id"] for b in birds]
    notes = []
    if bird_ids:
        placeholders = ",".join(["%s"] * len(bird_ids))
        notes = db._c.cursor()
        notes.execute(
            f"SELECT bn.id, bn.bird_id, b.ring_number, bn.note_date, bn.content, bn.created_at "
            f"FROM bird_notes bn JOIN birds b ON b.id = bn.bird_id "
            f"WHERE bn.bird_id IN ({placeholders}) ORDER BY bn.bird_id, bn.note_date DESC",
            bird_ids
        )
        notes = notes.fetchall()

    # Build birds.csv
    bird_fields = ["ring_number", "name", "species", "breed", "breed_mix", "sex",
                   "birth_date", "birth_approximate", "is_dead", "death_date",
                   "is_sold", "sold_date", "notes", "created_at"]
    birds_buf = io.StringIO()
    w = csv.DictWriter(birds_buf, fieldnames=bird_fields, extrasaction="ignore")
    w.writeheader()
    for b in birds:
        w.writerow({k: b.get(k, "") for k in bird_fields})

    # Build notes.csv
    note_fields = ["ring_number", "note_date", "content", "created_at"]
    notes_buf = io.StringIO()
    w2 = csv.DictWriter(notes_buf, fieldnames=note_fields, extrasaction="ignore")
    w2.writeheader()
    for n in notes:
        w2.writerow({k: n.get(k, "") for k in note_fields})

    # Pack into ZIP
    zip_buf = io.BytesIO()
    with zipfile.ZipFile(zip_buf, "w", zipfile.ZIP_DEFLATED) as zf:
        zf.writestr("birds.csv", birds_buf.getvalue())
        zf.writestr("notes.csv", notes_buf.getvalue())
    zip_buf.seek(0)

    safe_name = re.sub(r"[^a-z0-9_-]", "_", flock["name"].lower())
    return send_file(
        zip_buf,
        mimetype="application/zip",
        as_attachment=True,
        download_name=f"{safe_name}_export.zip",
    )


# ---------------------------------------------------------------------------
# Bird CRUD
# ---------------------------------------------------------------------------

@app.route("/flock/<username>/<flock_name>/add", methods=["GET", "POST"])
@login_required
def add_bird(username, flock_name):
    flock = resolve_flock(username, flock_name)
    if not flock or get_permission(flock["id"]) != "edit":
        flash("Access denied.", "error")
        return redirect(url_for("flocks"))

    db = get_db()
    if request.method == "POST":
        image_data, image_mime = None, None
        file = request.files.get("image")
        if file and file.filename:
            image_data = file.read()
            image_mime = file.content_type

        breed     = request.form.get("breed", "").strip()
        breed_mix = request.form.get("breed_mix", "").strip() if breed == "hybrid" else ""

        ring = request.form["ring_number"].strip()
        if ring_number_taken(flock, ring):
            flash("Ring number already in use in this flock.", "error")
        else:
            db.execute(
                """INSERT INTO birds
                   (flock_id, ring_number, name, species, breed, breed_mix, sex,
                    birth_date, birth_approximate, notes, image, image_mime)
                   VALUES (?,?,?,?,?,?,?,?,?,?,?,?)""",
                (
                    flock["id"], ring,
                    request.form.get("name", "").strip() or None,
                    request.form["species"],
                    breed or None, breed_mix or None,
                    request.form.get("sex", "unknown"),
                    request.form.get("birth_date") or None,
                    1 if request.form.get("birth_approximate") else 0,
                    request.form.get("notes", "").strip() or None,
                    image_data, image_mime,
                ),
            )
            db.commit()
            flash("Bird registered!", "success")
            return redirect(url_for("view_flock", username=username, flock_name=flock_name))

    return render_template("form.html", bird=None, flock=flock)


@app.route("/bird/<int:bird_id>/edit", methods=["GET", "POST"])
@login_required
def edit_bird(bird_id):
    db = get_db()
    bird = db.execute("SELECT * FROM birds WHERE id = ?", (bird_id,)).fetchone()
    if not bird or get_permission(bird["flock_id"]) != "edit":
        flash("Access denied.", "error")
        return redirect(url_for("flocks"))

    flock = db.execute(
        "SELECT f.*, u.username FROM flocks f JOIN users u ON u.id = f.user_id WHERE f.id = ?",
        (bird["flock_id"],)
    ).fetchone()

    if request.method == "POST":
        image_data = bird["image"]
        image_mime = bird["image_mime"]
        if request.form.get("remove_image"):
            image_data, image_mime = None, None
        file = request.files.get("image")
        if file and file.filename:
            image_data = file.read()
            image_mime = file.content_type

        breed     = request.form.get("breed", "").strip()
        breed_mix = request.form.get("breed_mix", "").strip() if breed == "hybrid" else ""
        is_dead   = 1 if request.form.get("is_dead") else 0
        is_sold   = 1 if request.form.get("is_sold") else 0
        death_date = (request.form.get("death_date") or None) if is_dead else None
        sold_date  = (request.form.get("sold_date") or None) if is_sold else None
        # A bird can only be dead or sold, not both
        if is_dead:
            is_sold, sold_date = 0, None
        elif is_sold:
            is_dead, death_date = 0, None

        ring = request.form["ring_number"].strip()
        if ring_number_taken(flock, ring, exclude_bird_id=bird_id):
            flash("Ring number already in use in this flock.", "error")
        else:
            db.execute(
                """UPDATE birds SET ring_number=?, name=?, species=?, breed=?, breed_mix=?,
                   sex=?, birth_date=?, birth_approximate=?, notes=?, is_dead=?, death_date=?,
                   is_sold=?, sold_date=?, image=?, image_mime=? WHERE id=?""",
                (
                    ring,
                    request.form.get("name", "").strip() or None,
                    request.form["species"],
                    breed or None, breed_mix or None,
                    request.form.get("sex", "unknown"),
                    request.form.get("birth_date") or None,
                    1 if request.form.get("birth_approximate") else 0,
                    request.form.get("notes", "").strip() or None,
                    is_dead, death_date, is_sold, sold_date, image_data, image_mime, bird_id,
                ),
            )
            db.commit()
            flash("Bird updated!", "success")
            return redirect(url_for("view_bird", bird_id=bird_id))

    return render_template("form.html", bird=bird, flock=flock)


@app.route("/bird/<int:bird_id>")
@login_required
def view_bird(bird_id):
    db = get_db()
    bird = db.execute(
        "SELECT *, image IS NOT NULL AS has_image FROM birds WHERE id = ?", (bird_id,)
    ).fetchone()
    if not bird:
        flash("Bird not found.", "error")
        return redirect(url_for("flocks"))

    perm = get_permission(bird["flock_id"])
    if not perm:
        flash("Access denied.", "error")
        return redirect(url_for("flocks"))

    flock = db.execute(
        "SELECT f.*, u.username FROM flocks f JOIN users u ON u.id = f.user_id WHERE f.id = ?",
        (bird["flock_id"],)
    ).fetchone()
    notes = db.execute(
        "SELECT * FROM bird_notes WHERE bird_id = ? ORDER BY note_date DESC, created_at DESC",
        (bird_id,)
    ).fetchall()
    return render_template("view.html", bird=bird, flock=flock, can_edit=(perm == "edit"),
                           notes=notes, today=_date.today().isoformat())


@app.route("/bird/<int:bird_id>/image")
def bird_image(bird_id):
    db = get_db()
    bird = db.execute("SELECT image, image_mime, flock_id FROM birds WHERE id = ?", (bird_id,)).fetchone()
    if not bird or not bird["image"]:
        return "", 404
    if not get_permission(bird["flock_id"]):
        return "", 403
    return send_file(BytesIO(bird["image"]), mimetype=bird["image_mime"])


@app.route("/bird/<int:bird_id>/delete", methods=["GET", "POST"])
@login_required
def delete_bird(bird_id):
    db = get_db()
    bird = db.execute("SELECT * FROM birds WHERE id = ?", (bird_id,)).fetchone()
    if not bird:
        flash("Bird not found.", "error")
        return redirect(url_for("flocks"))

    flock = db.execute(
        "SELECT f.*, u.username FROM flocks f JOIN users u ON u.id = f.user_id WHERE f.id = ?",
        (bird["flock_id"],)
    ).fetchone()

    if not g.user or g.user["id"] != flock["user_id"]:
        flash("Only the flock owner can delete birds.", "error")
        return redirect(url_for("view_bird", bird_id=bird_id))

    if request.method == "GET":
        return render_template("delete_confirm.html", bird=bird, flock=flock)

    db.execute("DELETE FROM birds WHERE id = ?", (bird_id,))
    db.commit()
    flash("Bird deleted.", "success")
    return redirect(url_for("view_flock", username=flock["username"], flock_name=flock["name"]))


# ---------------------------------------------------------------------------
# Notes
# ---------------------------------------------------------------------------

@app.route("/bird/<int:bird_id>/note", methods=["POST"])
@login_required
def add_note(bird_id):
    db = get_db()
    bird = db.execute("SELECT flock_id FROM birds WHERE id = ?", (bird_id,)).fetchone()
    if not bird or get_permission(bird["flock_id"]) != "edit":
        flash("Access denied.", "error")
        return redirect(url_for("flocks"))

    note_date = request.form.get("note_date", "").strip()
    content   = request.form.get("content", "").strip()
    if not note_date or not content:
        flash("Date and content are required.", "error")
    else:
        db.execute(
            "INSERT INTO bird_notes (bird_id, note_date, content) VALUES (?,?,?)",
            (bird_id, note_date, content)
        )
        db.commit()
    return redirect(url_for("view_bird", bird_id=bird_id))


@app.route("/bird/<int:bird_id>/note/<int:note_id>/delete", methods=["POST"])
@login_required
def delete_note(bird_id, note_id):
    db = get_db()
    bird = db.execute("SELECT flock_id FROM birds WHERE id = ?", (bird_id,)).fetchone()
    if not bird or get_permission(bird["flock_id"]) != "edit":
        flash("Access denied.", "error")
        return redirect(url_for("flocks"))

    db.execute("DELETE FROM bird_notes WHERE id = ? AND bird_id = ?", (note_id, bird_id))
    db.commit()
    return redirect(url_for("view_bird", bird_id=bird_id))


# ---------------------------------------------------------------------------
# Settings / sharing
# ---------------------------------------------------------------------------

@app.route("/settings", methods=["GET", "POST"])
@login_required
def settings():
    db = get_db()
    my_flocks = user_flocks(g.user["id"])

    if request.method == "POST":
        action = request.form.get("action")

        if action == "update_email":
            email = request.form.get("email", "").strip() or None
            try:
                db.execute("UPDATE users SET email = ? WHERE id = ?", (email, g.user["id"]))
                db.commit()
                flash("Email address updated.", "success")
            except pymysql.IntegrityError:
                flash("That email address is already registered to another account.", "error")
            return redirect(url_for("settings"))

        elif action == "grant":
            flock_id  = request.form.get("flock_id", type=int)
            username  = request.form.get("username", "").strip()
            can_edit  = 1 if request.form.get("can_edit") else 0
            # Verify flock belongs to current user
            flock = db.execute("SELECT id FROM flocks WHERE id = ? AND user_id = ?",
                               (flock_id, g.user["id"])).fetchone()
            target = db.execute("SELECT * FROM users WHERE username = ?", (username,)).fetchone()
            if not flock:
                flash("Flock not found.", "error")
            elif not target:
                flash("User not found.", "error")
            elif target["id"] == g.user["id"]:
                flash("You can't share with yourself.", "error")
            else:
                try:
                    db.execute(
                        "INSERT INTO flock_shares (flock_id, grantee_id, can_edit) VALUES (?,?,?)",
                        (flock_id, target["id"], can_edit)
                    )
                except pymysql.IntegrityError:
                    db.execute(
                        "UPDATE flock_shares SET can_edit=? WHERE flock_id=? AND grantee_id=?",
                        (can_edit, flock_id, target["id"])
                    )
                db.commit()
                perm_label = "view & edit" if can_edit else "view"
                flash(f"Flock shared with {target['username']} ({perm_label}).", "success")

        elif action == "revoke":
            share_id = request.form.get("share_id", type=int)
            db.execute(
                """DELETE fs FROM flock_shares fs
                   JOIN flocks f ON f.id = fs.flock_id
                   WHERE fs.id = ? AND f.user_id = ?""",
                (share_id, g.user["id"])
            )
            db.commit()
            flash("Share removed.", "success")

        elif action == "toggle_reuse":
            flock_id  = request.form.get("flock_id", type=int)
            allow     = 1 if request.form.get("allow_ring_reuse") else 0
            db.execute(
                "UPDATE flocks SET allow_ring_reuse = ? WHERE id = ? AND user_id = ?",
                (allow, flock_id, g.user["id"])
            )
            db.commit()
            flash("Ring number policy updated.", "success")

    # Shares per flock
    flock_shares = {}
    for f in my_flocks:
        flock_shares[f["id"]] = db.execute(
            """SELECT fs.id, fs.can_edit, u.username
               FROM flock_shares fs JOIN users u ON u.id = fs.grantee_id
               WHERE fs.flock_id = ? ORDER BY u.username""",
            (f["id"],)
        ).fetchall()

    shared_with_me = shared_flocks_for(g.user["id"])

    return render_template("settings.html",
                           my_flocks=my_flocks,
                           flock_shares=flock_shares,
                           shared_with_me=shared_with_me)


# ---------------------------------------------------------------------------
# Hatches
# ---------------------------------------------------------------------------

SPECIES_PRESETS = {
    "chicken": {"incubation_days": 21, "lockdown_day": 18, "humidity_incubation": 45, "humidity_lockdown": 68},
    "duck":    {"incubation_days": 28, "lockdown_day": 25, "humidity_incubation": 55, "humidity_lockdown": 72},
    "muscovy": {"incubation_days": 35, "lockdown_day": 31, "humidity_incubation": 55, "humidity_lockdown": 72},
    "custom":  {"incubation_days": 21, "lockdown_day": 18, "humidity_incubation": 45, "humidity_lockdown": 68},
}


def hatch_timeline(h):
    start = h["start_datetime"]
    if isinstance(start, str):
        start = datetime.fromisoformat(start)
    lockdown  = start + timedelta(days=int(h["lockdown_day"]))
    first_pip = start + timedelta(days=int(h["incubation_days"]) - 1)
    hatch_dt  = start + timedelta(days=int(h["incubation_days"]))
    now = datetime.now()
    if now < lockdown:
        status = "incubating"
    elif now < first_pip:
        status = "lockdown"
    elif now <= hatch_dt + timedelta(days=2):
        status = "hatching"
    else:
        status = "complete"
    total   = int(h["incubation_days"])
    elapsed = max(0, min(total, (now - start).days))
    return {
        "start":          start,
        "lockdown":       lockdown,
        "first_pip":      first_pip,
        "hatch_dt":       hatch_dt,
        "status":         status,
        "days_remaining": max(0, (hatch_dt.date() - now.date()).days),
        "progress_pct":   round(elapsed / total * 100),
    }


@app.route("/hatches")
@login_required
def hatches():
    db = get_db()
    rows = db.execute(
        "SELECT * FROM hatches WHERE user_id = ? ORDER BY start_datetime DESC",
        (g.user["id"],)
    ).fetchall()
    items = [{"hatch": r, "tl": hatch_timeline(r)} for r in rows]
    shared_rows = shared_hatches_for(g.user["id"])
    shared_items = [{"hatch": r, "tl": hatch_timeline(r)} for r in shared_rows]
    return render_template("hatches.html", items=items, shared_items=shared_items)


@app.route("/hatch/new", methods=["GET", "POST"])
@login_required
def new_hatch():
    if request.method == "POST":
        return _save_hatch(None)
    return render_template("hatch_form.html", hatch=None, presets=SPECIES_PRESETS)


@app.route("/hatch/<int:hatch_id>")
@login_required
def view_hatch(hatch_id):
    perm = get_hatch_permission(hatch_id)
    if not perm:
        flash("Hatch not found.", "error")
        return redirect(url_for("hatches"))
    db = get_db()
    hatch = db.execute("SELECT * FROM hatches WHERE id = ?", (hatch_id,)).fetchone()
    shares = []
    if perm == "owner":
        shares = db.execute(
            """SELECT hs.id, hs.can_edit, u.username FROM hatch_shares hs
               JOIN users u ON u.id = hs.grantee_id
               WHERE hs.hatch_id = ? ORDER BY u.username""",
            (hatch_id,)
        ).fetchall()
    return render_template("hatch_view.html", hatch=hatch, tl=hatch_timeline(hatch),
                           perm=perm, shares=shares)


@app.route("/hatch/<int:hatch_id>/share", methods=["POST"])
@login_required
def share_hatch(hatch_id):
    hatch = _get_own_hatch(hatch_id)
    if not hatch:
        flash("Access denied.", "error")
        return redirect(url_for("hatches"))
    username = request.form.get("username", "").strip()
    db = get_db()
    target = db.execute("SELECT * FROM users WHERE username = ?", (username,)).fetchone()
    if not target:
        flash("User not found.", "error")
    elif target["id"] == g.user["id"]:
        flash("You can't share with yourself.", "error")
    else:
        can_edit = 1 if request.form.get("can_edit") else 0
        try:
            db.execute(
                "INSERT INTO hatch_shares (hatch_id, grantee_id, can_edit) VALUES (?,?,?)",
                (hatch_id, target["id"], can_edit)
            )
        except pymysql.IntegrityError:
            db.execute(
                "UPDATE hatch_shares SET can_edit=? WHERE hatch_id=? AND grantee_id=?",
                (can_edit, hatch_id, target["id"])
            )
        db.commit()
        perm_label = "view & edit" if can_edit else "view"
        flash(f"Hatch shared with {target['username']} ({perm_label}).", "success")
    return redirect(url_for("view_hatch", hatch_id=hatch_id))


@app.route("/hatch/<int:hatch_id>/share/<int:share_id>/revoke", methods=["POST"])
@login_required
def revoke_hatch_share(hatch_id, share_id):
    hatch = _get_own_hatch(hatch_id)
    if hatch:
        get_db().execute(
            "DELETE FROM hatch_shares WHERE id = ? AND hatch_id = ?",
            (share_id, hatch_id)
        )
        get_db().commit()
        flash("Share removed.", "success")
    return redirect(url_for("view_hatch", hatch_id=hatch_id))


@app.route("/hatch/<int:hatch_id>/edit", methods=["GET", "POST"])
@login_required
def edit_hatch(hatch_id):
    if get_hatch_permission(hatch_id) not in ("owner", "edit"):
        flash("Access denied.", "error")
        return redirect(url_for("hatches"))
    hatch = get_db().execute("SELECT * FROM hatches WHERE id = ?", (hatch_id,)).fetchone()
    if not hatch:
        flash("Hatch not found.", "error")
        return redirect(url_for("hatches"))
    if request.method == "POST":
        return _save_hatch(hatch_id)
    perm = get_hatch_permission(hatch_id)
    return render_template("hatch_form.html", hatch=hatch, presets=SPECIES_PRESETS, perm=perm)


@app.route("/hatch/<int:hatch_id>/delete", methods=["POST"])
@login_required
def delete_hatch(hatch_id):
    hatch = _get_own_hatch(hatch_id)
    if hatch:
        get_db().execute("DELETE FROM hatches WHERE id = ?", (hatch_id,))
        get_db().commit()
        flash("Hatch deleted.", "success")
    return redirect(url_for("hatches"))


def _get_own_hatch(hatch_id):
    return get_db().execute(
        "SELECT * FROM hatches WHERE id = ? AND user_id = ?",
        (hatch_id, g.user["id"])
    ).fetchone()


def get_hatch_permission(hatch_id):
    """Return 'owner', 'edit', 'view', or None for g.user on the given hatch."""
    if not g.user:
        return None
    db = get_db()
    h = db.execute("SELECT user_id FROM hatches WHERE id = ?", (hatch_id,)).fetchone()
    if not h:
        return None
    if g.user["id"] == h["user_id"]:
        return "owner"
    share = db.execute(
        "SELECT can_edit FROM hatch_shares WHERE hatch_id = ? AND grantee_id = ?",
        (hatch_id, g.user["id"])
    ).fetchone()
    if not share:
        return None
    return "edit" if share["can_edit"] else "view"


def shared_hatches_for(user_id):
    return get_db().execute(
        """SELECT h.*, u.username AS owner_username, hs.can_edit
           FROM hatch_shares hs
           JOIN hatches h ON h.id = hs.hatch_id
           JOIN users u ON u.id = h.user_id
           WHERE hs.grantee_id = ?
           ORDER BY h.start_datetime DESC""",
        (user_id,)
    ).fetchall()


def _save_hatch(hatch_id):
    f = request.form
    species = f.get("species", "chicken")
    preset  = SPECIES_PRESETS.get(species, SPECIES_PRESETS["chicken"])
    try:
        incubation_days = int(f.get("incubation_days") or preset["incubation_days"])
        lockdown_day    = int(f.get("lockdown_day") or preset["lockdown_day"])
    except ValueError:
        flash("Incubation days and lockdown day must be numbers.", "error")
        return redirect(request.url)

    def _int(key):
        v = f.get(key, "").strip()
        return int(v) if v else None

    def _float(key):
        v = f.get(key, "").strip()
        return float(v) if v else None

    db = get_db()
    if hatch_id is None:
        db.execute(
            """INSERT INTO hatches
               (user_id, name, species, start_datetime, incubation_days, lockdown_day,
                humidity_incubation, humidity_lockdown, egg_count,
                eggs_brooder, eggs_discarded, eggs_hatched, notes)
               VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            (g.user["id"], f.get("name","").strip() or None, species,
             f["start_datetime"], incubation_days, lockdown_day,
             _float("humidity_incubation"), _float("humidity_lockdown"),
             _int("egg_count"), _int("eggs_brooder"), _int("eggs_discarded"),
             _int("eggs_hatched"), f.get("notes","").strip() or None)
        )
        db.commit()
        flash("Hatch started!", "success")
        new_id = db.execute("SELECT LAST_INSERT_ID() AS id").fetchone()["id"]
        return redirect(url_for("view_hatch", hatch_id=new_id))
    else:
        db.execute(
            """UPDATE hatches SET
               name=?, species=?, start_datetime=?, incubation_days=?, lockdown_day=?,
               humidity_incubation=?, humidity_lockdown=?, egg_count=?,
               eggs_brooder=?, eggs_discarded=?, eggs_hatched=?, notes=?
               WHERE id=? AND user_id=?""",
            (f.get("name","").strip() or None, species, f["start_datetime"],
             incubation_days, lockdown_day,
             _float("humidity_incubation"), _float("humidity_lockdown"),
             _int("egg_count"), _int("eggs_brooder"), _int("eggs_discarded"),
             _int("eggs_hatched"), f.get("notes","").strip() or None,
             hatch_id, g.user["id"])
        )
        db.commit()
        flash("Hatch updated.", "success")
        return redirect(url_for("view_hatch", hatch_id=hatch_id))


# ---------------------------------------------------------------------------
# Flock log
# ---------------------------------------------------------------------------

def _editable_flocks():
    """All flocks the current user can edit (own + edit-shares)."""
    db = get_db()
    own = db.execute(
        "SELECT f.*, u.username FROM flocks f JOIN users u ON u.id = f.user_id WHERE f.user_id = ? ORDER BY f.name",
        (g.user["id"],)
    ).fetchall()
    shared = db.execute(
        """SELECT f.*, u.username FROM flock_shares fs
           JOIN flocks f ON f.id = fs.flock_id
           JOIN users u ON u.id = f.user_id
           WHERE fs.grantee_id = ? AND fs.can_edit = 1 ORDER BY f.name""",
        (g.user["id"],)
    ).fetchall()
    return list(own) + list(shared)


@app.route("/log", methods=["GET", "POST"])
@login_required
def daily_log():
    db = get_db()
    log_date = request.args.get("date") or request.form.get("date") or _date.today().isoformat()

    flocks = _editable_flocks()
    flock_ids = [f["id"] for f in flocks]

    if request.method == "POST" and request.form.get("action") == "log":
        for flock in flocks:
            fid = flock["id"]
            eggs = request.form.get(f"eggs_{fid}", "").strip() or None
            light = request.form.get(f"light_{fid}", "").strip() or None
            bedding = 1 if request.form.get(f"bedding_{fid}") else 0
            notes = request.form.get(f"notes_{fid}", "").strip() or None
            if eggs is not None or light is not None or bedding or notes:
                db.execute(
                    """INSERT INTO flock_log (flock_id, user_id, log_date, eggs_collected, light_hours, bedding_changed, notes)
                       VALUES (?,?,?,?,?,?,?)
                       ON DUPLICATE KEY UPDATE eggs_collected=VALUES(eggs_collected),
                         light_hours=VALUES(light_hours), bedding_changed=VALUES(bedding_changed),
                         notes=VALUES(notes), user_id=VALUES(user_id)""",
                    (fid, g.user["id"], log_date, eggs, light, bedding, notes)
                )
        db.commit()
        flash("Log saved.", "success")
        return redirect(url_for("daily_log", date=log_date))

    # Load existing entries for the selected date
    existing = {}
    if flock_ids:
        placeholders = ",".join(["%s"] * len(flock_ids))
        rows = db._c.cursor()
        rows.execute(
            f"SELECT * FROM flock_log WHERE log_date = %s AND flock_id IN ({placeholders})",
            [log_date] + flock_ids
        )
        for row in rows.fetchall():
            existing[row["flock_id"]] = row

    return render_template("daily_log.html", flocks=flocks, log_date=log_date, existing=existing)


@app.route("/flock/<username>/<flock_name>/log", methods=["GET", "POST"])
@login_required
def flock_log(username, flock_name):
    flock = resolve_flock(username, flock_name)
    if not flock or get_permission(flock["id"]) not in ("edit", None):
        # allow view permission to see the log, just not post
        pass
    if not flock:
        flash("Flock not found.", "error")
        return redirect(url_for("flocks"))
    perm = get_permission(flock["id"])
    if not perm:
        flash("Access denied.", "error")
        return redirect(url_for("flocks"))

    db = get_db()
    can_edit = (perm == "edit")

    if request.method == "POST" and can_edit:
        action = request.form.get("action")

        if action == "log":
            log_date = request.form.get("log_date", "").strip()
            eggs  = request.form.get("eggs_collected", "").strip() or None
            light = request.form.get("light_hours", "").strip() or None
            bedding = 1 if request.form.get("bedding_changed") else 0
            notes = request.form.get("notes", "").strip() or None
            db.execute(
                """INSERT INTO flock_log (flock_id, user_id, log_date, eggs_collected, light_hours, bedding_changed, notes)
                   VALUES (?,?,?,?,?,?,?)
                   ON DUPLICATE KEY UPDATE eggs_collected=VALUES(eggs_collected),
                     light_hours=VALUES(light_hours), bedding_changed=VALUES(bedding_changed),
                     notes=VALUES(notes), user_id=VALUES(user_id)""",
                (flock["id"], g.user["id"], log_date, eggs, light, bedding, notes)
            )
            db.commit()
            flash("Entry saved.", "success")

        elif action == "delete_log":
            entry_id = request.form.get("entry_id", type=int)
            db.execute("DELETE FROM flock_log WHERE id = ? AND flock_id = ?", (entry_id, flock["id"]))
            db.commit()
            flash("Entry deleted.", "success")

        elif action == "stash":
            found_date = request.form.get("found_date", "").strip()
            egg_count  = request.form.get("egg_count", "").strip()
            notes = request.form.get("stash_notes", "").strip() or None
            if found_date and egg_count:
                db.execute(
                    "INSERT INTO flock_stash (flock_id, user_id, found_date, egg_count, notes) VALUES (?,?,?,?,?)",
                    (flock["id"], g.user["id"], found_date, int(egg_count), notes)
                )
                db.commit()
                flash("Stash logged.", "success")

        elif action == "delete_stash":
            stash_id = request.form.get("stash_id", type=int)
            db.execute("DELETE FROM flock_stash WHERE id = ? AND flock_id = ?", (stash_id, flock["id"]))
            db.commit()
            flash("Stash entry deleted.", "success")

        return redirect(url_for("flock_log", username=username, flock_name=flock_name))

    logs = db.execute(
        "SELECT * FROM flock_log WHERE flock_id = ? ORDER BY log_date DESC LIMIT 90",
        (flock["id"],)
    ).fetchall()
    stashes = db.execute(
        "SELECT * FROM flock_stash WHERE flock_id = ? ORDER BY found_date DESC",
        (flock["id"],)
    ).fetchall()
    today = _date.today().isoformat()
    today_entry = db.execute(
        "SELECT * FROM flock_log WHERE flock_id = ? AND log_date = ?",
        (flock["id"], today)
    ).fetchone()

    return render_template("flock_log.html", flock=flock, logs=logs, stashes=stashes,
                           today=today, today_entry=today_entry, can_edit=can_edit)


@app.route("/flock/<username>/<flock_name>/report")
@login_required
def flock_report(username, flock_name):
    flock = resolve_flock(username, flock_name)
    if not flock:
        flash("Flock not found.", "error")
        return redirect(url_for("flocks"))
    perm = get_permission(flock["id"])
    if not perm:
        flash("Access denied.", "error")
        return redirect(url_for("flocks"))

    db = get_db()
    days = request.args.get("days", 30, type=int)
    if days not in (30, 60, 90, 180, 365):
        days = 30

    logs = db.execute(
        """SELECT log_date, eggs_collected, light_hours, bedding_changed
           FROM flock_log WHERE flock_id = ? AND log_date >= DATE_SUB(CURDATE(), INTERVAL ? DAY)
           ORDER BY log_date""",
        (flock["id"], days)
    ).fetchall()

    stashes = db.execute(
        """SELECT found_date, SUM(egg_count) AS total
           FROM flock_stash WHERE flock_id = ? AND found_date >= DATE_SUB(CURDATE(), INTERVAL ? DAY)
           GROUP BY found_date ORDER BY found_date""",
        (flock["id"], days)
    ).fetchall()

    # Active bird count per day is approximated from bird records
    active_count = db.execute(
        """SELECT COUNT(*) AS n FROM birds
           WHERE flock_id = ? AND is_dead = 0 AND is_sold = 0""",
        (flock["id"],)
    ).fetchone()["n"]

    return render_template("flock_report.html", flock=flock, logs=logs, stashes=stashes,
                           days=days, active_count=active_count)


@app.route("/robots.txt")
def robots():
    return app.response_class(
        "User-agent: *\nAllow: /\nDisallow: /settings\nDisallow: /flocks\nDisallow: /flock/\nDisallow: /bird/\nSitemap: https://ringlog.no/sitemap.xml\n",
        mimetype="text/plain",
    )


@app.route("/sitemap.xml")
def sitemap():
    xml = (
        '<?xml version="1.0" encoding="UTF-8"?>'
        '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">'
        "<url><loc>https://ringlog.no/</loc><changefreq>monthly</changefreq><priority>1.0</priority></url>"
        "<url><loc>https://ringlog.no/register</loc><changefreq>monthly</changefreq><priority>0.8</priority></url>"
        "<url><loc>https://ringlog.no/login</loc><changefreq>monthly</changefreq><priority>0.5</priority></url>"
        "<url><loc>https://ringlog.no/terms</loc><changefreq>yearly</changefreq><priority>0.3</priority></url>"
        "</urlset>"
    )
    return app.response_class(xml, mimetype="application/xml")


if __name__ == "__main__":
    app.run(debug=True, port=5000)
