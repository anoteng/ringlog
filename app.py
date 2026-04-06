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
    Flask, render_template, request, redirect, url_for, g, send_file, flash, session, jsonify
)
from flask_babel import Babel, gettext as _, ngettext
from werkzeug.security import generate_password_hash, check_password_hash
from io import BytesIO
from dotenv import load_dotenv

load_dotenv(os.path.join(os.path.dirname(__file__), ".env"))

app = Flask(__name__)
app.secret_key = os.environ.get("SECRET_KEY") or os.urandom(24)

SUPPORTED_LANGS = ["en", "nb", "nl"]


def _get_locale():
    if g.user and g.user.get("lang") and g.user["lang"] in SUPPORTED_LANGS:
        return g.user["lang"]
    lang = request.cookies.get("lang")
    if lang in SUPPORTED_LANGS:
        return lang
    return request.accept_languages.best_match(SUPPORTED_LANGS, default="en")


babel = Babel(app, locale_selector=_get_locale)

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
        weeks  = (today - bd).days // 7
        if weeks < 1:
            return "< 1 wk"
        if weeks < 12:
            return f"{weeks} wk"
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
    from flask_babel import get_locale
    return {"current_user": g.user, "get_locale": get_locale}


def login_required(f):
    @wraps(f)
    def decorated(*args, **kwargs):
        if not g.user:
            flash(_("Please log in to continue."), "error")
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
            flash(_("You must accept the Terms of Use to register."), "error")
        elif not username or len(username) < 3:
            flash(_("Username must be at least 3 characters."), "error")
        elif not username.replace("_", "").isalnum():
            flash(_("Username may only contain letters, numbers, and underscores."), "error")
        elif len(password) < 8:
            flash(_("Password must be at least 8 characters."), "error")
        elif password != confirm:
            flash(_("Passwords do not match."), "error")
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
                flash(_("Welcome, %(name)s!", name=user["username"]), "success")
                return redirect(url_for("flocks"))
            except pymysql.IntegrityError as e:
                if "email" in str(e).lower():
                    flash(_("That email address is already registered."), "error")
                else:
                    flash(_("Username already taken."), "error")
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
            flash(_("Welcome back, %(name)s!", name=user["username"]), "success")
            return redirect(next_url)
        flash(_("Invalid username or password."), "error")
    return render_template("login.html")


@app.route("/logout", methods=["POST"])
def logout():
    session.clear()
    return redirect(url_for("login"))


@app.route("/set-lang/<lang>")
def set_lang(lang):
    if lang not in SUPPORTED_LANGS:
        lang = "en"
    if g.user:
        db = get_db()
        db.execute("UPDATE users SET lang = ? WHERE id = ?", (lang, g.user["id"]))
        db.commit()
    resp = redirect(request.referrer or url_for("index"))
    resp.set_cookie("lang", lang, max_age=365 * 24 * 3600)
    return resp


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
                    flash(_("Failed to send email. Please try again later."), "error")
                    return render_template("forgot_password.html")
        # Always show the same message to avoid user enumeration
        flash(_("If that email address is registered, a reset link has been sent."), "success")
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
        flash(_("This reset link is invalid or has expired."), "error")
        return redirect(url_for("forgot_password"))

    if request.method == "POST":
        password = request.form.get("password", "")
        confirm  = request.form.get("confirm", "")
        if len(password) < 8:
            flash(_("Password must be at least 8 characters."), "error")
        elif password != confirm:
            flash(_("Passwords do not match."), "error")
        else:
            db.execute(
                "UPDATE users SET password_hash = ? WHERE id = ?",
                (generate_password_hash(password), row["user_id"])
            )
            db.execute("DELETE FROM password_resets WHERE token = ?", (token,))
            db.commit()
            flash(_("Password updated. You can now log in."), "success")
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
            flash(_("Flock name is required."), "error")
        else:
            try:
                db.execute(
                    "INSERT INTO flocks (user_id, name, description) VALUES (?, ?, ?)",
                    (g.user["id"], name, description)
                )
                db.commit()
                flash(_("Flock '%(name)s' created.", name=name), "success")
                return redirect(url_for("view_flock", username=g.user["username"], flock_name=name))
            except pymysql.IntegrityError:
                flash(_("You already have a flock with that name."), "error")

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
        flash(_("Access denied."), "error")
        return redirect(url_for("flocks"))

    db = get_db()
    bird_count = db.execute(
        "SELECT COUNT(*) AS n FROM birds WHERE flock_id = ?", (flock["id"],)
    ).fetchone()["n"]

    if request.method == "POST":
        db.execute("DELETE FROM flocks WHERE id = ?", (flock["id"],))
        db.commit()
        flash(_("Flock '%(name)s' deleted.", name=flock["name"]), "success")
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
        flash(_("Flock not found."), "error")
        return redirect(url_for("flocks"))

    perm = get_permission(flock["id"])
    if not perm:
        flash(_("You don't have access to that flock."), "error")
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
        flash(_("Flock not found."), "error")
        return redirect(url_for("flocks"))
    if not get_permission(flock["id"]):
        flash(_("Access denied."), "error")
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
        flash(_("Access denied."), "error")
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
            flash(_("Ring number already in use in this flock."), "error")
        else:
            db.execute(
                """INSERT INTO birds
                   (flock_id, ring_number, name, species, breed, breed_mix, sex,
                    birth_date, birth_approximate, notes, image, image_mime, flock_join_date)
                   VALUES (?,?,?,?,?,?,?,?,?,?,?,?,CURDATE())""",
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
            flash(_("Bird registered!"), "success")
            return redirect(url_for("view_flock", username=username, flock_name=flock_name))

    return render_template("form.html", bird=None, flock=flock)


@app.route("/bird/<int:bird_id>/edit", methods=["GET", "POST"])
@login_required
def edit_bird(bird_id):
    db = get_db()
    bird = db.execute("SELECT * FROM birds WHERE id = ?", (bird_id,)).fetchone()
    if not bird or get_permission(bird["flock_id"]) != "edit":
        flash(_("Access denied."), "error")
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
            flash(_("Ring number already in use in this flock."), "error")
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
            flash(_("Bird updated!"), "success")
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
        flash(_("Bird not found."), "error")
        return redirect(url_for("flocks"))

    perm = get_permission(bird["flock_id"])
    if not perm:
        flash(_("Access denied."), "error")
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
        flash(_("Bird not found."), "error")
        return redirect(url_for("flocks"))

    flock = db.execute(
        "SELECT f.*, u.username FROM flocks f JOIN users u ON u.id = f.user_id WHERE f.id = ?",
        (bird["flock_id"],)
    ).fetchone()

    if not g.user or g.user["id"] != flock["user_id"]:
        flash(_("Only the flock owner can delete birds."), "error")
        return redirect(url_for("view_bird", bird_id=bird_id))

    if request.method == "GET":
        return render_template("delete_confirm.html", bird=bird, flock=flock)

    db.execute("DELETE FROM birds WHERE id = ?", (bird_id,))
    db.commit()
    flash(_("Bird deleted."), "success")
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
        flash(_("Access denied."), "error")
        return redirect(url_for("flocks"))

    note_date = request.form.get("note_date", "").strip()
    content   = request.form.get("content", "").strip()
    if not note_date or not content:
        flash(_("Date and content are required."), "error")
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
        flash(_("Access denied."), "error")
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
                flash(_("Email address updated."), "success")
            except pymysql.IntegrityError:
                flash(_("That email address is already registered to another account."), "error")
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
                flash(_("Flock not found."), "error")
            elif not target:
                flash(_("User not found."), "error")
            elif target["id"] == g.user["id"]:
                flash(_("You can't share with yourself."), "error")
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
                perm_label = _("view & edit") if can_edit else _("view")
                flash(_("Flock shared with %(username)s (%(perm)s).", username=target["username"], perm=perm_label), "success")

        elif action == "revoke":
            share_id = request.form.get("share_id", type=int)
            db.execute(
                """DELETE fs FROM flock_shares fs
                   JOIN flocks f ON f.id = fs.flock_id
                   WHERE fs.id = ? AND f.user_id = ?""",
                (share_id, g.user["id"])
            )
            db.commit()
            flash(_("Share removed."), "success")

        elif action == "toggle_reuse":
            flock_id  = request.form.get("flock_id", type=int)
            allow     = 1 if request.form.get("allow_ring_reuse") else 0
            db.execute(
                "UPDATE flocks SET allow_ring_reuse = ? WHERE id = ? AND user_id = ?",
                (allow, flock_id, g.user["id"])
            )
            db.commit()
            flash(_("Ring number policy updated."), "success")

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
        flash(_("Hatch not found."), "error")
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
        flash(_("Access denied."), "error")
        return redirect(url_for("hatches"))
    username = request.form.get("username", "").strip()
    db = get_db()
    target = db.execute("SELECT * FROM users WHERE username = ?", (username,)).fetchone()
    if not target:
        flash(_("User not found."), "error")
    elif target["id"] == g.user["id"]:
        flash(_("You can't share with yourself."), "error")
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
        perm_label = _("view & edit") if can_edit else _("view")
        flash(_("Hatch shared with %(username)s (%(perm)s).", username=target["username"], perm=perm_label), "success")
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
        flash(_("Share removed."), "success")
    return redirect(url_for("view_hatch", hatch_id=hatch_id))


@app.route("/hatch/<int:hatch_id>/edit", methods=["GET", "POST"])
@login_required
def edit_hatch(hatch_id):
    if get_hatch_permission(hatch_id) not in ("owner", "edit"):
        flash(_("Access denied."), "error")
        return redirect(url_for("hatches"))
    hatch = get_db().execute("SELECT * FROM hatches WHERE id = ?", (hatch_id,)).fetchone()
    if not hatch:
        flash(_("Hatch not found."), "error")
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
        flash(_("Hatch deleted."), "success")
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
    is_api = request.path.startswith("/api/")
    f = request.get_json(silent=True) if is_api else request.form
    if f is None:
        f = {}
    species = f.get("species", "chicken")
    preset  = SPECIES_PRESETS.get(species, SPECIES_PRESETS["chicken"])
    try:
        incubation_days = int(f.get("incubation_days") or preset["incubation_days"])
        lockdown_day    = int(f.get("lockdown_day") or preset["lockdown_day"])
    except ValueError:
        if is_api:
            return jsonify({"error": "incubation_days and lockdown_day must be numbers"}), 400
        flash(_("Incubation days and lockdown day must be numbers."), "error")
        return redirect(request.url)

    def _int(key):
        v = f.get(key)
        if v is None or (isinstance(v, str) and not v.strip()):
            return None
        return int(v)

    def _float(key):
        v = f.get(key)
        if v is None or (isinstance(v, str) and not v.strip()):
            return None
        return float(v)

    db = get_db()
    if hatch_id is None:
        if is_api and not f.get("start_datetime"):
            return jsonify({"error": "start_datetime required"}), 400
        db.execute(
            """INSERT INTO hatches
               (user_id, name, species, start_datetime, incubation_days, lockdown_day,
                humidity_incubation, humidity_lockdown, egg_count,
                eggs_brooder, eggs_discarded, eggs_hatched, notes)
               VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            (g.user["id"], (f.get("name") or "").strip() or None, species,
             f.get("start_datetime"), incubation_days, lockdown_day,
             _float("humidity_incubation"), _float("humidity_lockdown"),
             _int("egg_count"), _int("eggs_brooder"), _int("eggs_discarded"),
             _int("eggs_hatched"), (f.get("notes") or "").strip() or None)
        )
        db.commit()
        new_id = db.execute("SELECT LAST_INSERT_ID() AS id").fetchone()["id"]
        if is_api:
            h = db.execute("SELECT * FROM hatches WHERE id=?", (new_id,)).fetchone()
            return jsonify({"id": new_id, "name": h["name"], "species": h["species"]}), 201
        flash(_("Hatch started!"), "success")
        return redirect(url_for("view_hatch", hatch_id=new_id))
    else:
        db.execute(
            """UPDATE hatches SET
               name=?, species=?, start_datetime=?, incubation_days=?, lockdown_day=?,
               humidity_incubation=?, humidity_lockdown=?, egg_count=?,
               eggs_brooder=?, eggs_discarded=?, eggs_hatched=?, notes=?
               WHERE id=?""",
            ((f.get("name") or "").strip() or None, species, f.get("start_datetime"),
             incubation_days, lockdown_day,
             _float("humidity_incubation"), _float("humidity_lockdown"),
             _int("egg_count"), _int("eggs_brooder"), _int("eggs_discarded"),
             _int("eggs_hatched"), (f.get("notes") or "").strip() or None,
             hatch_id)
        )
        db.commit()
        if is_api:
            return jsonify({"ok": True, "id": hatch_id})
        flash(_("Hatch updated."), "success")
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
        flash(_("Log saved."), "success")
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
        flash(_("Flock not found."), "error")
        return redirect(url_for("flocks"))
    perm = get_permission(flock["id"])
    if not perm:
        flash(_("Access denied."), "error")
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
            flash(_("Entry saved."), "success")

        elif action == "delete_log":
            entry_id = request.form.get("entry_id", type=int)
            db.execute("DELETE FROM flock_log WHERE id = ? AND flock_id = ?", (entry_id, flock["id"]))
            db.commit()
            flash(_("Entry deleted."), "success")

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
                flash(_("Stash logged."), "success")

        elif action == "delete_stash":
            stash_id = request.form.get("stash_id", type=int)
            db.execute("DELETE FROM flock_stash WHERE id = ? AND flock_id = ?", (stash_id, flock["id"]))
            db.commit()
            flash(_("Stash entry deleted."), "success")

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
        flash(_("Flock not found."), "error")
        return redirect(url_for("flocks"))
    perm = get_permission(flock["id"])
    if not perm:
        flash(_("Access denied."), "error")
        return redirect(url_for("flocks"))

    db = get_db()
    days = request.args.get("days", 30, type=int)
    if days not in (30, 60, 90, 180, 365):
        days = 30

    logs = db.execute(
        """SELECT l.log_date, l.eggs_collected, l.light_hours, l.bedding_changed,
              (SELECT COUNT(*) FROM birds b
               WHERE b.flock_id = l.flock_id AND b.sex = 'female'
                 AND b.flock_join_date <= l.log_date
                 AND (b.death_date IS NULL OR b.death_date > l.log_date)
                 AND (b.sold_date IS NULL OR b.sold_date > l.log_date)
                 AND (b.birth_date IS NULL OR b.birth_date <= DATE_SUB(l.log_date, INTERVAL 5 MONTH))
              ) AS laying_hens
           FROM flock_log l
           WHERE l.flock_id = ? AND l.log_date >= DATE_SUB(CURDATE(), INTERVAL ? DAY)
           ORDER BY l.log_date""",
        (flock["id"], days)
    ).fetchall()

    stashes = db.execute(
        """SELECT found_date, SUM(egg_count) AS total
           FROM flock_stash WHERE flock_id = ? AND found_date >= DATE_SUB(CURDATE(), INTERVAL ? DAY)
           GROUP BY found_date ORDER BY found_date""",
        (flock["id"], days)
    ).fetchall()

    active_count = db.execute(
        """SELECT COUNT(*) AS n FROM birds
           WHERE flock_id = ? AND is_dead = 0 AND is_sold = 0""",
        (flock["id"],)
    ).fetchone()["n"]

    laying_count = db.execute(
        """SELECT COUNT(*) AS n FROM birds
           WHERE flock_id = ? AND is_dead = 0 AND is_sold = 0 AND sex = 'female'
             AND flock_join_date <= CURDATE()
             AND (birth_date IS NULL OR birth_date <= DATE_SUB(CURDATE(), INTERVAL 5 MONTH))""",
        (flock["id"],)
    ).fetchone()["n"]

    return render_template("flock_report.html", flock=flock, logs=logs, stashes=stashes,
                           days=days, active_count=active_count, laying_count=laying_count)


@app.route("/delete-account", methods=["GET", "POST"])
def delete_account():
    if request.method == "POST":
        if not g.user:
            return redirect(url_for("login", next="/delete-account"))
        db = get_db()
        user_id = g.user["id"]
        # Cascade: birds → via flock_id, flock_shares, flock_log, hatches, user_tokens, password_resets, users
        db.execute("DELETE FROM user_tokens WHERE user_id = ?", (user_id,))
        db.execute("DELETE FROM password_resets WHERE user_id = ?", (user_id,))
        db.execute("DELETE FROM hatch_shares WHERE hatch_id IN (SELECT id FROM hatches WHERE user_id = ?)", (user_id,))
        db.execute("DELETE FROM hatches WHERE user_id = ?", (user_id,))
        db.execute("""DELETE FROM flock_log WHERE flock_id IN
                      (SELECT id FROM flocks WHERE user_id = ?)""", (user_id,))
        db.execute("""DELETE FROM flock_stash WHERE flock_id IN
                      (SELECT id FROM flocks WHERE user_id = ?)""", (user_id,))
        db.execute("""DELETE FROM flock_shares WHERE flock_id IN
                      (SELECT id FROM flocks WHERE user_id = ?)""", (user_id,))
        db.execute("""DELETE FROM bird_notes WHERE bird_id IN
                      (SELECT b.id FROM birds b JOIN flocks f ON f.id = b.flock_id WHERE f.user_id = ?)""",
                   (user_id,))
        db.execute("""DELETE FROM birds WHERE flock_id IN
                      (SELECT id FROM flocks WHERE user_id = ?)""", (user_id,))
        db.execute("DELETE FROM flocks WHERE user_id = ?", (user_id,))
        db.execute("DELETE FROM users WHERE id = ?", (user_id,))
        db.commit()
        session.clear()
        flash(_("Your account and all data have been permanently deleted."), "success")
        return redirect(url_for("landing"))
    return render_template("data_deletion.html")


@app.route("/privacy")
def privacy():
    return render_template("privacy.html")


@app.route("/app")
def app_download():
    return render_template("app_download.html")


@app.route("/app/ringlog.apk")
def download_apk():
    apk_path = os.path.join(os.path.dirname(__file__), "ringlog.apk")
    return send_file(apk_path, as_attachment=True, download_name="ringlog.apk",
                     mimetype="application/vnd.android.package-archive")


@app.route("/app/ringlog.aab")
def download_aab():
    aab_path = os.path.join(os.path.dirname(__file__), "ringlog.aab")
    return send_file(aab_path, as_attachment=True, download_name="ringlog.aab",
                     mimetype="application/octet-stream")


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


# ---------------------------------------------------------------------------
# JSON API v1
# ---------------------------------------------------------------------------

def _api_user():
    """Return user from Bearer token, or None."""
    auth = request.headers.get("Authorization", "")
    if not auth.startswith("Bearer "):
        return None
    token = auth[7:]
    db = get_db()
    row = db.execute(
        """SELECT u.* FROM user_tokens t
           JOIN users u ON u.id = t.user_id
           WHERE t.token = ?""",
        (token,)
    ).fetchone()
    if row:
        db.execute("UPDATE user_tokens SET last_used=NOW() WHERE token=?", (token,))
        db.commit()
    return row


def api_login_required(f):
    @wraps(f)
    def decorated(*args, **kwargs):
        user = _api_user()
        if not user:
            return jsonify({"error": "Unauthorized"}), 401
        g.user = user
        return f(*args, **kwargs)
    return decorated


def _bird_dict(b):
    return {
        "id":               b["id"],
        "flock_id":         b["flock_id"],
        "ring_number":      b["ring_number"],
        "name":             b["name"],
        "species":          b["species"],
        "breed":            b["breed"],
        "breed_mix":        b["breed_mix"],
        "sex":              b["sex"],
        "birth_date":       str(b["birth_date"]) if b["birth_date"] else None,
        "birth_approximate": bool(b["birth_approximate"]),
        "notes":            b["notes"],
        "is_dead":          bool(b["is_dead"]),
        "death_date":       str(b["death_date"]) if b["death_date"] else None,
        "is_sold":          bool(b["is_sold"]),
        "sold_date":        str(b["sold_date"]) if b["sold_date"] else None,
        "has_image":        bool(b["image"]),
    }


def _flock_dict(f, bird_count=None, can_edit=True, owner_username=None):
    return {
        "id":             f["id"],
        "name":           f["name"],
        "description":    f.get("description"),
        "owner_username": owner_username or f.get("username"),
        "can_edit":       can_edit,
        "bird_count":     bird_count,
    }


# --- Auth ---

@app.route("/api/v1/auth/login", methods=["POST"])
def api_login():
    data = request.get_json(silent=True) or {}
    username = (data.get("username") or "").strip()
    password = data.get("password") or ""
    device   = (data.get("device_name") or "")[:128]
    if not username or not password:
        return jsonify({"error": "username and password required"}), 400
    db = get_db()
    user = db.execute("SELECT * FROM users WHERE username = ?", (username,)).fetchone()
    if not user or not check_password_hash(user["password_hash"], password):
        return jsonify({"error": "Invalid username or password"}), 401
    token = secrets.token_hex(32)
    db.execute(
        "INSERT INTO user_tokens (user_id, token, device_name) VALUES (?,?,?)",
        (user["id"], token, device or None)
    )
    db.commit()
    return jsonify({"token": token, "username": user["username"], "user_id": user["id"]})


@app.route("/api/v1/auth/register", methods=["POST"])
def api_register():
    data     = request.get_json(silent=True) or {}
    username = (data.get("username") or "").strip()
    email    = (data.get("email") or "").strip() or None
    password = data.get("password") or ""
    device   = (data.get("device_name") or "")[:128]
    if not username or len(username) < 3:
        return jsonify({"error": "Username must be at least 3 characters"}), 400
    if not username.replace("_", "").isalnum():
        return jsonify({"error": "Username may only contain letters, numbers, and underscores"}), 400
    if len(password) < 8:
        return jsonify({"error": "Password must be at least 8 characters"}), 400
    db = get_db()
    try:
        db.execute(
            "INSERT INTO users (username, email, password_hash, terms_accepted_at) VALUES (?, ?, ?, NOW())",
            (username, email, generate_password_hash(password))
        )
        db.commit()
    except pymysql.IntegrityError as e:
        if "email" in str(e).lower():
            return jsonify({"error": "That email address is already registered"}), 409
        return jsonify({"error": "Username already taken"}), 409
    user  = db.execute("SELECT * FROM users WHERE username = ?", (username,)).fetchone()
    token = secrets.token_hex(32)
    db.execute("INSERT INTO user_tokens (user_id, token, device_name) VALUES (?,?,?)",
               (user["id"], token, device or None))
    db.commit()
    return jsonify({"token": token, "username": user["username"], "user_id": user["id"]}), 201


@app.route("/api/v1/auth/logout", methods=["POST"])
@api_login_required
def api_logout():
    token = request.headers["Authorization"][7:]
    get_db().execute("DELETE FROM user_tokens WHERE token=?", (token,))
    get_db().commit()
    return jsonify({"ok": True})


@app.route("/api/v1/auth/me")
@api_login_required
def api_me():
    u = g.user
    return jsonify({"id": u["id"], "username": u["username"], "lang": u.get("lang", "en")})


# --- Flocks ---

@app.route("/api/v1/flocks")
@api_login_required
def api_flocks():
    db = get_db()
    owned = db.execute(
        """SELECT f.*, u.username,
             (SELECT COUNT(*) FROM birds b WHERE b.flock_id=f.id AND b.is_dead=0 AND b.is_sold=0) AS bird_count
           FROM flocks f JOIN users u ON u.id=f.user_id
           WHERE f.user_id=? ORDER BY f.name""",
        (g.user["id"],)
    ).fetchall()
    shared = db.execute(
        """SELECT f.*, u.username, fs.can_edit,
             (SELECT COUNT(*) FROM birds b WHERE b.flock_id=f.id AND b.is_dead=0 AND b.is_sold=0) AS bird_count
           FROM flock_shares fs
           JOIN flocks f ON f.id=fs.flock_id
           JOIN users u ON u.id=f.user_id
           WHERE fs.grantee_id=? ORDER BY f.name""",
        (g.user["id"],)
    ).fetchall()
    return jsonify({
        "owned":  [_flock_dict(f, f["bird_count"], True) for f in owned],
        "shared": [_flock_dict(f, f["bird_count"], bool(f["can_edit"])) for f in shared],
    })


@app.route("/api/v1/flocks/<int:flock_id>")
@api_login_required
def api_flock_detail(flock_id):
    perm = get_permission(flock_id)
    if not perm:
        return jsonify({"error": "Not found"}), 404
    db = get_db()
    flock = db.execute(
        "SELECT f.*, u.username FROM flocks f JOIN users u ON u.id=f.user_id WHERE f.id=?",
        (flock_id,)
    ).fetchone()
    birds = sorted(
        db.execute("SELECT * FROM birds WHERE flock_id=?", (flock_id,)).fetchall(),
        key=lambda b: _natural_key(b["ring_number"])
    )
    return jsonify({
        **_flock_dict(flock, len(birds), perm == "edit"),
        "can_edit": perm == "edit",
        "birds": [_bird_dict(b) for b in birds],
    })


@app.route("/api/v1/flocks", methods=["POST"])
@api_login_required
def api_create_flock():
    data = request.get_json(silent=True) or {}
    name = (data.get("name") or "").strip()
    if not name:
        return jsonify({"error": "name required"}), 400
    db = get_db()
    try:
        db.execute(
            "INSERT INTO flocks (user_id, name, description) VALUES (?,?,?)",
            (g.user["id"], name, (data.get("description") or "").strip() or None)
        )
        db.commit()
        flock_id = db.execute("SELECT LAST_INSERT_ID() AS id").fetchone()["id"]
        flock = db.execute(
            "SELECT f.*, u.username FROM flocks f JOIN users u ON u.id=f.user_id WHERE f.id=?",
            (flock_id,)
        ).fetchone()
        return jsonify(_flock_dict(flock, 0, True)), 201
    except pymysql.IntegrityError:
        return jsonify({"error": "A flock with that name already exists"}), 409


# --- Birds ---

@app.route("/api/v1/flocks/<int:flock_id>/birds", methods=["POST"])
@api_login_required
def api_add_bird(flock_id):
    if get_permission(flock_id) != "edit":
        return jsonify({"error": "Permission denied"}), 403
    db = get_db()
    flock = db.execute("SELECT * FROM flocks WHERE id=?", (flock_id,)).fetchone()
    f = request.form if request.content_type and "multipart" in request.content_type else (request.get_json(silent=True) or {})
    ring = (f.get("ring_number") or "").strip()
    if not ring:
        return jsonify({"error": "ring_number required"}), 400
    if ring_number_taken(flock, ring):
        return jsonify({"error": "Ring number already in use"}), 409
    image_data = image_mime = None
    if request.files.get("image"):
        img = request.files["image"]
        image_data = img.read()
        image_mime = img.mimetype
    birth_approx = 1 if f.get("birth_approximate") in (True, "true", "1", 1) else 0
    try:
        db.execute(
            """INSERT INTO birds (flock_id, ring_number, name, species, breed, breed_mix, sex,
               birth_date, birth_approximate, notes, image, image_mime, flock_join_date)
               VALUES (?,?,?,?,?,?,?,?,?,?,?,?,CURDATE())""",
            (flock_id, ring, (f.get("name") or "").strip() or None,
             f.get("species") or "chicken", (f.get("breed") or "").strip() or None,
             (f.get("breed_mix") or "").strip() or None, f.get("sex") or "unknown",
             f.get("birth_date") or None, birth_approx,
             (f.get("notes") or "").strip() or None, image_data, image_mime)
        )
        db.commit()
        bird_id = db.execute("SELECT LAST_INSERT_ID() AS id").fetchone()["id"]
        bird = db.execute("SELECT * FROM birds WHERE id=?", (bird_id,)).fetchone()
        return jsonify(_bird_dict(bird)), 201
    except pymysql.IntegrityError:
        return jsonify({"error": "Ring number already in use"}), 409


@app.route("/api/v1/birds/<int:bird_id>")
@api_login_required
def api_bird_detail(bird_id):
    db = get_db()
    bird = db.execute("SELECT * FROM birds WHERE id=?", (bird_id,)).fetchone()
    if not bird or not get_permission(bird["flock_id"]):
        return jsonify({"error": "Not found"}), 404
    notes = db.execute(
        "SELECT id, note_date, content, created_at FROM bird_notes WHERE bird_id=? ORDER BY note_date DESC, id DESC",
        (bird_id,)
    ).fetchall()
    return jsonify({
        **_bird_dict(bird),
        "notes_list": [{"id": n["id"], "note_date": str(n["note_date"]), "content": n["content"]} for n in notes],
    })


@app.route("/api/v1/birds/<int:bird_id>", methods=["PUT"])
@api_login_required
def api_update_bird(bird_id):
    db = get_db()
    bird = db.execute("SELECT * FROM birds WHERE id=?", (bird_id,)).fetchone()
    if not bird or get_permission(bird["flock_id"]) != "edit":
        return jsonify({"error": "Not found or permission denied"}), 404
    flock = db.execute("SELECT * FROM flocks WHERE id=?", (bird["flock_id"],)).fetchone()
    f = request.form if request.content_type and "multipart" in request.content_type else (request.get_json(silent=True) or {})
    ring = (f.get("ring_number") or "").strip() or bird["ring_number"]
    if ring != bird["ring_number"] and ring_number_taken(flock, ring, exclude_bird_id=bird_id):
        return jsonify({"error": "Ring number already in use"}), 409
    image_data = bird["image"]
    image_mime = bird["image_mime"]
    if request.files.get("image"):
        img = request.files["image"]
        image_data = img.read()
        image_mime = img.mimetype
    birth_approx = 1 if f.get("birth_approximate") in (True, "true", "1", 1) else 0
    db.execute(
        """UPDATE birds SET ring_number=?, name=?, species=?, breed=?, breed_mix=?, sex=?,
           birth_date=?, birth_approximate=?, notes=?, is_dead=?, death_date=?, is_sold=?, sold_date=?,
           image=?, image_mime=? WHERE id=?""",
        (ring, (f.get("name") or "").strip() or None,
         f.get("species") or bird["species"], (f.get("breed") or "").strip() or None,
         (f.get("breed_mix") or "").strip() or None, f.get("sex") or bird["sex"],
         f.get("birth_date") or None, birth_approx,
         (f.get("notes") or "").strip() or None,
         1 if f.get("is_dead") in (True, "true", "1", 1) else 0,
         f.get("death_date") or None,
         1 if f.get("is_sold") in (True, "true", "1", 1) else 0,
         f.get("sold_date") or None,
         image_data, image_mime, bird_id)
    )
    db.commit()
    return jsonify(_bird_dict(db.execute("SELECT * FROM birds WHERE id=?", (bird_id,)).fetchone()))


@app.route("/api/v1/birds/<int:bird_id>/image", methods=["PUT"])
@api_login_required
def api_upload_bird_image(bird_id):
    db = get_db()
    bird = db.execute("SELECT * FROM birds WHERE id=?", (bird_id,)).fetchone()
    if not bird or get_permission(bird["flock_id"]) != "edit":
        return jsonify({"error": "Not found or permission denied"}), 404
    img = request.files.get("image")
    if not img:
        return jsonify({"error": "No image provided"}), 400
    db.execute("UPDATE birds SET image=?, image_mime=? WHERE id=?",
               (img.read(), img.mimetype, bird_id))
    db.commit()
    return jsonify({"ok": True})


@app.route("/api/v1/birds/<int:bird_id>", methods=["DELETE"])
@api_login_required
def api_delete_bird(bird_id):
    db = get_db()
    bird = db.execute("SELECT * FROM birds WHERE id=?", (bird_id,)).fetchone()
    if not bird:
        return jsonify({"error": "Not found"}), 404
    flock = db.execute("SELECT * FROM flocks WHERE id=?", (bird["flock_id"],)).fetchone()
    if flock["user_id"] != g.user["id"]:
        return jsonify({"error": "Only the flock owner can delete birds"}), 403
    db.execute("DELETE FROM birds WHERE id=?", (bird_id,))
    db.commit()
    return jsonify({"ok": True})


@app.route("/api/v1/birds/<int:bird_id>/image")
@api_login_required
def api_bird_image(bird_id):
    db = get_db()
    bird = db.execute("SELECT image, image_mime, flock_id FROM birds WHERE id=?", (bird_id,)).fetchone()
    if not bird or not bird["image"] or not get_permission(bird["flock_id"]):
        return jsonify({"error": "Not found"}), 404
    return send_file(BytesIO(bird["image"]), mimetype=bird["image_mime"])


# --- Bird notes ---

@app.route("/api/v1/birds/<int:bird_id>/notes", methods=["POST"])
@api_login_required
def api_add_note(bird_id):
    db = get_db()
    bird = db.execute("SELECT * FROM birds WHERE id=?", (bird_id,)).fetchone()
    if not bird or get_permission(bird["flock_id"]) != "edit":
        return jsonify({"error": "Not found or permission denied"}), 404
    data = request.get_json(silent=True) or {}
    note_date = data.get("note_date") or _date.today().isoformat()
    content = (data.get("content") or "").strip()
    if not content:
        return jsonify({"error": "content required"}), 400
    db.execute("INSERT INTO bird_notes (bird_id, note_date, content) VALUES (?,?,?)",
               (bird_id, note_date, content))
    db.commit()
    note_id = db.execute("SELECT LAST_INSERT_ID() AS id").fetchone()["id"]
    return jsonify({"id": note_id, "bird_id": bird_id, "note_date": note_date, "content": content}), 201


@app.route("/api/v1/birds/<int:bird_id>/notes/<int:note_id>", methods=["DELETE"])
@api_login_required
def api_delete_note(bird_id, note_id):
    db = get_db()
    bird = db.execute("SELECT * FROM birds WHERE id=?", (bird_id,)).fetchone()
    if not bird or get_permission(bird["flock_id"]) != "edit":
        return jsonify({"error": "Not found or permission denied"}), 404
    db.execute("DELETE FROM bird_notes WHERE id=? AND bird_id=?", (note_id, bird_id))
    db.commit()
    return jsonify({"ok": True})


# --- Flock log ---

@app.route("/api/v1/flocks/<int:flock_id>/log")
@api_login_required
def api_flock_log(flock_id):
    if not get_permission(flock_id):
        return jsonify({"error": "Not found"}), 404
    db = get_db()
    from_date = request.args.get("from") or ((_date.today() - timedelta(days=29)).isoformat())
    to_date   = request.args.get("to")   or _date.today().isoformat()
    rows = db.execute(
        """SELECT log_date, eggs_collected, light_hours, bedding_changed, notes
           FROM flock_log WHERE flock_id=? AND log_date BETWEEN ? AND ?
           ORDER BY log_date DESC""",
        (flock_id, from_date, to_date)
    ).fetchall()
    return jsonify([{
        "log_date":        str(r["log_date"]),
        "eggs_collected":  r["eggs_collected"],
        "light_hours":     float(r["light_hours"]) if r["light_hours"] is not None else None,
        "bedding_changed": bool(r["bedding_changed"]),
        "notes":           r["notes"],
    } for r in rows])


@app.route("/api/v1/flocks/<int:flock_id>/log/<log_date>", methods=["PUT"])
@api_login_required
def api_upsert_log(flock_id, log_date):
    if get_permission(flock_id) != "edit":
        return jsonify({"error": "Permission denied"}), 403
    data = request.get_json(silent=True) or {}
    eggs    = data.get("eggs_collected")
    light   = data.get("light_hours")
    bedding = 1 if data.get("bedding_changed") else 0
    notes   = (data.get("notes") or "").strip() or None
    db = get_db()
    db.execute(
        """INSERT INTO flock_log (flock_id, user_id, log_date, eggs_collected, light_hours, bedding_changed, notes)
           VALUES (?,?,?,?,?,?,?)
           ON DUPLICATE KEY UPDATE eggs_collected=VALUES(eggs_collected),
             light_hours=VALUES(light_hours), bedding_changed=VALUES(bedding_changed),
             notes=VALUES(notes), user_id=VALUES(user_id)""",
        (flock_id, g.user["id"], log_date, eggs, light, bedding, notes)
    )
    db.commit()
    return jsonify({"ok": True, "log_date": log_date})


@app.route("/api/v1/flocks/<int:flock_id>/report")
@api_login_required
def api_flock_report(flock_id):
    if not get_permission(flock_id):
        return jsonify({"error": "Not found"}), 404
    days = min(int(request.args.get("days", 30)), 365)
    db = get_db()
    logs = db.execute(
        """SELECT l.log_date, l.eggs_collected, l.light_hours, l.bedding_changed,
              (SELECT COUNT(*) FROM birds b
               WHERE b.flock_id = l.flock_id AND b.sex = 'female'
                 AND b.flock_join_date <= l.log_date
                 AND (b.death_date IS NULL OR b.death_date > l.log_date)
                 AND (b.sold_date IS NULL OR b.sold_date > l.log_date)
                 AND (b.birth_date IS NULL OR b.birth_date <= DATE_SUB(l.log_date, INTERVAL 5 MONTH))
              ) AS laying_hens
           FROM flock_log l
           WHERE l.flock_id=? AND l.log_date >= DATE_SUB(CURDATE(), INTERVAL ? DAY)
           ORDER BY l.log_date""",
        (flock_id, days)
    ).fetchall()
    laying_count = db.execute(
        """SELECT COUNT(*) AS n FROM birds
           WHERE flock_id=? AND is_dead=0 AND is_sold=0 AND sex='female'
             AND flock_join_date <= CURDATE()
             AND (birth_date IS NULL OR birth_date <= DATE_SUB(CURDATE(), INTERVAL 5 MONTH))""",
        (flock_id,)
    ).fetchone()["n"]
    entries = [{"log_date": str(r["log_date"]), "eggs_collected": r["eggs_collected"],
                "light_hours": float(r["light_hours"]) if r["light_hours"] is not None else None,
                "bedding_changed": bool(r["bedding_changed"]),
                "laying_hens": r["laying_hens"]} for r in logs]
    egg_days   = [e for e in entries if e["eggs_collected"] is not None]
    total_eggs = sum(e["eggs_collected"] for e in egg_days)
    avg_eggs   = round(total_eggs / len(egg_days), 1) if egg_days else 0
    max_eggs   = max((e["eggs_collected"] for e in egg_days), default=0)
    return jsonify({
        "days": days, "laying_count": laying_count,
        "stats": {"total_eggs": total_eggs, "avg_eggs_per_day": avg_eggs,
                  "best_day": max_eggs, "days_logged": len(egg_days)},
        "logs": entries,
    })


# --- Hatches ---

@app.route("/api/v1/hatches")
@api_login_required
def api_hatches():
    db = get_db()
    owned = db.execute(
        "SELECT * FROM hatches WHERE user_id=? ORDER BY start_datetime DESC",
        (g.user["id"],)
    ).fetchall()
    shared = shared_hatches_for(g.user["id"])

    def _hatch_dict(h):
        tl = hatch_timeline(h)
        return {
            "id":                  h["id"],
            "name":                h["name"],
            "species":             h["species"],
            "start_datetime":      str(h["start_datetime"]),
            "incubation_days":     h["incubation_days"],
            "lockdown_day":        h["lockdown_day"],
            "humidity_incubation": h["humidity_incubation"],
            "humidity_lockdown":   h["humidity_lockdown"],
            "egg_count":           h["egg_count"],
            "eggs_brooder":        h["eggs_brooder"],
            "eggs_discarded":      h["eggs_discarded"],
            "eggs_hatched":        h["eggs_hatched"],
            "notes":               h["notes"],
            "owner_id":            h["user_id"],
            "timeline": {
                "status":        tl["status"],
                "days_remaining": tl.get("days_remaining"),
                "progress_pct":  tl.get("progress_pct"),
                "lockdown_dt":   str(tl["lockdown"]) if tl.get("lockdown") else None,
                "hatch_dt":      str(tl["hatch_dt"]) if tl.get("hatch_dt") else None,
            },
        }

    return jsonify({
        "owned":  [_hatch_dict(h) for h in owned],
        "shared": [_hatch_dict(h) for h in shared],
    })


@app.route("/api/v1/hatches/<int:hatch_id>")
@api_login_required
def api_hatch_detail(hatch_id):
    perm = get_hatch_permission(hatch_id)
    if not perm:
        return jsonify({"error": "Not found"}), 404
    h = get_db().execute("SELECT * FROM hatches WHERE id=?", (hatch_id,)).fetchone()
    tl = hatch_timeline(h)
    return jsonify({
        "id": h["id"], "name": h["name"], "species": h["species"],
        "start_datetime": str(h["start_datetime"]),
        "incubation_days": h["incubation_days"], "lockdown_day": h["lockdown_day"],
        "humidity_incubation": h["humidity_incubation"], "humidity_lockdown": h["humidity_lockdown"],
        "egg_count": h["egg_count"], "eggs_brooder": h["eggs_brooder"],
        "eggs_discarded": h["eggs_discarded"], "eggs_hatched": h["eggs_hatched"],
        "notes": h["notes"], "owner_id": h["user_id"], "can_edit": perm in ("owner", "edit"),
        "timeline": {
            "status": tl["status"], "days_remaining": tl.get("days_remaining"),
            "progress_pct": tl.get("progress_pct"),
            "lockdown_dt": str(tl["lockdown"]) if tl.get("lockdown") else None,
            "hatch_dt": str(tl["hatch_dt"]) if tl.get("hatch_dt") else None,
        },
    })


@app.route("/api/v1/hatches", methods=["POST"])
@api_login_required
def api_create_hatch():
    g.user = g.user  # already set by decorator
    return _save_hatch(None)


@app.route("/api/v1/hatches/<int:hatch_id>", methods=["PUT"])
@api_login_required
def api_update_hatch(hatch_id):
    if get_hatch_permission(hatch_id) not in ("owner", "edit"):
        return jsonify({"error": "Permission denied"}), 403
    return _save_hatch(hatch_id)


@app.route("/api/v1/hatches/<int:hatch_id>/finish", methods=["POST"])
@api_login_required
def api_finish_hatch(hatch_id):
    if get_hatch_permission(hatch_id) not in ("owner", "edit"):
        return jsonify({"error": "Not found"}), 404

    f = request.get_json(silent=True) or {}

    def _int(key):
        v = f.get(key)
        if v is None or (isinstance(v, str) and not v.strip()):
            return None
        return int(v)

    eggs_hatched   = _int("eggs_hatched")
    eggs_brooder   = _int("eggs_brooder")
    eggs_discarded = _int("eggs_discarded")
    flock_id       = _int("flock_id")
    new_flock_name = (f.get("new_flock_name") or "").strip() or None
    ring_prefix    = f.get("ring_prefix") or ""
    ring_start     = int(f.get("ring_start") or 1)
    birth_date_override = (f.get("birth_date") or "").strip() or None

    db = get_db()
    hatch = db.execute("SELECT * FROM hatches WHERE id=?", (hatch_id,)).fetchone()

    db.execute(
        "UPDATE hatches SET eggs_hatched=?, eggs_brooder=?, eggs_discarded=? WHERE id=?",
        (eggs_hatched, eggs_brooder, eggs_discarded, hatch_id)
    )
    db.commit()

    created_ids = []
    result_flock_id = None

    if eggs_hatched and eggs_hatched > 0 and (flock_id or new_flock_name):
        if not flock_id and new_flock_name:
            db.execute("INSERT INTO flocks (user_id, name) VALUES (?,?)",
                       (g.user["id"], new_flock_name))
            db.commit()
            flock_id = db.execute("SELECT LAST_INSERT_ID() AS id").fetchone()["id"]

        perm = get_permission(flock_id)
        if perm not in ("owner", "edit"):
            return jsonify({"error": "No access to target flock"}), 403

        tl = hatch_timeline(hatch)
        birth_date = birth_date_override or str(tl["hatch_dt"].date())

        for i in range(eggs_hatched):
            ring = f"{ring_prefix}{ring_start + i}"
            try:
                db.execute(
                    """INSERT INTO birds (flock_id, ring_number, species, sex,
                       birth_date, birth_approximate, flock_join_date)
                       VALUES (?,?,?,?,?,0,?)""",
                    (flock_id, ring, hatch["species"], "unknown", birth_date, birth_date)
                )
                db.commit()
                created_ids.append(db.execute("SELECT LAST_INSERT_ID() AS id").fetchone()["id"])
            except pymysql.IntegrityError:
                return jsonify({"error": f"Ring number '{ring}' already in use in that flock"}), 409

        result_flock_id = flock_id

    return jsonify({"ok": True, "flock_id": result_flock_id, "created_bird_ids": created_ids})


@app.route("/api/v1/hatches/<int:hatch_id>", methods=["DELETE"])
@api_login_required
def api_delete_hatch(hatch_id):
    h = _get_own_hatch(hatch_id)
    if not h:
        return jsonify({"error": "Not found or not owner"}), 404
    db = get_db()
    db.execute("DELETE FROM hatches WHERE id=?", (hatch_id,))
    db.commit()
    return jsonify({"ok": True})


if __name__ == "__main__":
    app.run(debug=True, port=5000)
