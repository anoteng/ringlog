"""RingLog - Bird registry for ducks and hens."""

import csv
import io
import os
import re
import zipfile
from functools import wraps
from datetime import date as _date

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


if __name__ == "__main__":
    app.run(debug=True, port=5000)
