"""RingLog - Bird registry for ducks and hens."""

import sqlite3
import os
import re
from functools import wraps
from flask import (
    Flask, render_template, request, redirect, url_for, g, send_file, flash, session
)
from werkzeug.security import generate_password_hash, check_password_hash
from io import BytesIO
from datetime import date as _date

app = Flask(__name__)
app.secret_key = os.environ.get("SECRET_KEY") or os.urandom(24)


@app.template_filter("age")
def age_filter(birth_date_str):
    if not birth_date_str:
        return "—"
    try:
        bd = _date.fromisoformat(birth_date_str)
        today = _date.today()
        months = (today.year - bd.year) * 12 + (today.month - bd.month)
        if months < 1:
            return "< 1 mo"
        if months < 24:
            return f"{months} mo"
        return f"{months // 12} yr"
    except ValueError:
        return "—"
DATABASE = os.path.join(os.path.dirname(__file__), "flockbook.db")


def _natural_key(s):
    return [int(c) if c.isdigit() else c.lower() for c in re.split(r'(\d+)', s or '')]


# ---------------------------------------------------------------------------
# Database
# ---------------------------------------------------------------------------

def get_db():
    if "db" not in g:
        g.db = sqlite3.connect(DATABASE)
        g.db.row_factory = sqlite3.Row
        g.db.execute("PRAGMA foreign_keys = ON")
    return g.db


@app.teardown_appcontext
def close_db(exc):
    db = g.pop("db", None)
    if db is not None:
        db.close()


def _db_has_column(db, table, column):
    rows = db.execute(f"PRAGMA table_info({table})").fetchall()
    return any(r["name"] == column for r in rows)


def migrate_db():
    """One-time migrations applied to existing databases."""
    db = sqlite3.connect(DATABASE)
    db.row_factory = sqlite3.Row
    db.execute("PRAGMA foreign_keys = ON")

    # Ensure users table exists before migration that depends on it
    db.execute("""
        CREATE TABLE IF NOT EXISTS users (
            id            INTEGER PRIMARY KEY AUTOINCREMENT,
            username      TEXT    NOT NULL UNIQUE COLLATE NOCASE,
            password_hash TEXT    NOT NULL,
            created_at    TEXT    DEFAULT (date('now'))
        )
    """)
    db.commit()

    # If birds table exists but lacks user_id, recreate it with the new schema
    tables = {r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    if "birds" in tables and not _db_has_column(db, "birds", "user_id"):
        # Create a legacy user to own existing birds
        legacy_pw = generate_password_hash(os.urandom(16).hex())
        try:
            db.execute(
                "INSERT INTO users (username, password_hash) VALUES (?, ?)",
                ("legacy", legacy_pw)
            )
            db.commit()
        except sqlite3.IntegrityError:
            pass
        legacy_user = db.execute("SELECT id FROM users WHERE username = 'legacy'").fetchone()
        legacy_id = legacy_user["id"]

        db.execute("ALTER TABLE birds RENAME TO birds_old")
        db.execute("""
            CREATE TABLE birds (
                id                INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id           INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                ring_number       TEXT    NOT NULL,
                name              TEXT,
                species           TEXT    NOT NULL CHECK(species IN ('duck', 'hen')),
                breed             TEXT,
                breed_mix         TEXT,
                sex               TEXT    CHECK(sex IN ('male', 'female', 'unknown')),
                birth_date        TEXT,
                birth_approximate INTEGER DEFAULT 0,
                notes             TEXT,
                is_dead           INTEGER DEFAULT 0,
                death_date        TEXT,
                image             BLOB,
                image_mime        TEXT,
                created_at        TEXT    DEFAULT (date('now')),
                UNIQUE(user_id, ring_number)
            )
        """)
        db.execute(f"""
            INSERT INTO birds
                SELECT id, {legacy_id}, ring_number, name, species, breed, breed_mix,
                       sex, birth_date, birth_approximate, notes, is_dead, death_date,
                       image, image_mime, created_at
            FROM birds_old
        """)
        db.execute("DROP TABLE birds_old")
        db.commit()

    db.close()


def init_db():
    db = sqlite3.connect(DATABASE)
    db.execute("PRAGMA foreign_keys = ON")
    db.execute("""
        CREATE TABLE IF NOT EXISTS users (
            id            INTEGER PRIMARY KEY AUTOINCREMENT,
            username      TEXT    NOT NULL UNIQUE COLLATE NOCASE,
            password_hash TEXT    NOT NULL,
            created_at    TEXT    DEFAULT (date('now'))
        )
    """)
    db.execute("""
        CREATE TABLE IF NOT EXISTS birds (
            id                INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id           INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            ring_number       TEXT    NOT NULL,
            name              TEXT,
            species           TEXT    NOT NULL CHECK(species IN ('duck', 'hen')),
            breed             TEXT,
            breed_mix         TEXT,
            sex               TEXT    CHECK(sex IN ('male', 'female', 'unknown')),
            birth_date        TEXT,
            birth_approximate INTEGER DEFAULT 0,
            notes             TEXT,
            is_dead           INTEGER DEFAULT 0,
            death_date        TEXT,
            image             BLOB,
            image_mime        TEXT,
            created_at        TEXT    DEFAULT (date('now')),
            UNIQUE(user_id, ring_number)
        )
    """)
    db.execute("""
        CREATE TABLE IF NOT EXISTS flock_shares (
            id             INTEGER PRIMARY KEY AUTOINCREMENT,
            owner_id       INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            grantee_id     INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            can_edit       INTEGER NOT NULL DEFAULT 0,
            created_at     TEXT    DEFAULT (date('now')),
            UNIQUE(owner_id, grantee_id)
        )
    """)
    db.execute("""
        CREATE TABLE IF NOT EXISTS bird_notes (
            id         INTEGER PRIMARY KEY AUTOINCREMENT,
            bird_id    INTEGER NOT NULL REFERENCES birds(id) ON DELETE CASCADE,
            note_date  TEXT    NOT NULL,
            content    TEXT    NOT NULL,
            created_at TEXT    DEFAULT (datetime('now'))
        )
    """)
    db.commit()
    db.close()


migrate_db()
init_db()


# ---------------------------------------------------------------------------
# Auth helpers
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


def get_permission(owner_id):
    """Return 'edit', 'view', or None for current user relative to owner_id."""
    if not g.user:
        return None
    if g.user["id"] == owner_id:
        return "edit"
    row = get_db().execute(
        "SELECT can_edit FROM flock_shares WHERE owner_id = ? AND grantee_id = ?",
        (owner_id, g.user["id"])
    ).fetchone()
    if not row:
        return None
    return "edit" if row["can_edit"] else "view"


# ---------------------------------------------------------------------------
# Auth routes
# ---------------------------------------------------------------------------

@app.route("/register", methods=["GET", "POST"])
def register():
    if g.user:
        return redirect(url_for("index"))
    if request.method == "POST":
        username = request.form.get("username", "").strip()
        password = request.form.get("password", "")
        confirm = request.form.get("confirm", "")
        if not username or len(username) < 3:
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
                    "INSERT INTO users (username, password_hash) VALUES (?, ?)",
                    (username, generate_password_hash(password))
                )
                db.commit()
                user = db.execute("SELECT * FROM users WHERE username = ?", (username,)).fetchone()
                session.clear()
                session["user_id"] = user["id"]
                flash(f"Welcome, {user['username']}!", "success")
                return redirect(url_for("index"))
            except sqlite3.IntegrityError:
                flash("Username already taken.", "error")
    return render_template("register.html")


@app.route("/login", methods=["GET", "POST"])
def login():
    if g.user:
        return redirect(url_for("index"))
    if request.method == "POST":
        username = request.form.get("username", "").strip()
        password = request.form.get("password", "")
        db = get_db()
        user = db.execute("SELECT * FROM users WHERE username = ?", (username,)).fetchone()
        if user and check_password_hash(user["password_hash"], password):
            session.clear()
            session["user_id"] = user["id"]
            next_url = request.args.get("next", "")
            if not next_url.startswith("/"):
                next_url = url_for("index")
            flash(f"Welcome back, {user['username']}!", "success")
            return redirect(next_url)
        flash("Invalid username or password.", "error")
    return render_template("login.html")


@app.route("/logout", methods=["POST"])
def logout():
    session.clear()
    return redirect(url_for("login"))


# ---------------------------------------------------------------------------
# Flock routes
# ---------------------------------------------------------------------------

@app.route("/")
@login_required
def index():
    return redirect(url_for("view_flock", username=g.user["username"]))


@app.route("/flock/<username>")
@login_required
def view_flock(username):
    db = get_db()
    owner = db.execute("SELECT * FROM users WHERE username = ?", (username,)).fetchone()
    if not owner:
        flash("User not found.", "error")
        return redirect(url_for("index"))

    perm = get_permission(owner["id"])
    if not perm:
        flash("You don't have access to that flock.", "error")
        return redirect(url_for("index"))

    filter_species = request.args.get("species", "")
    filter_status = request.args.get("status", "alive")

    query = """SELECT id, ring_number, name, species, breed, breed_mix, sex,
                      birth_date, birth_approximate, is_dead,
                      image IS NOT NULL as has_image
               FROM birds WHERE user_id = ?"""
    params = [owner["id"]]
    if filter_species:
        query += " AND species = ?"
        params.append(filter_species)
    if filter_status == "alive":
        query += " AND is_dead = 0"
    elif filter_status == "dead":
        query += " AND is_dead = 1"
    birds = sorted(
        db.execute(query, params).fetchall(),
        key=lambda b: _natural_key(b['ring_number'])
    )

    shared_flocks = db.execute("""
        SELECT u.username FROM flock_shares fs
        JOIN users u ON u.id = fs.owner_id
        WHERE fs.grantee_id = ?
    """, (g.user["id"],)).fetchall()

    return render_template(
        "index.html",
        birds=birds,
        filter_species=filter_species,
        filter_status=filter_status,
        owner=owner,
        can_edit=(perm == "edit"),
        is_own_flock=(owner["id"] == g.user["id"]),
        shared_flocks=shared_flocks,
    )


@app.route("/add", methods=["GET", "POST"])
@app.route("/add/<owner_username>", methods=["GET", "POST"])
@login_required
def add_bird(owner_username=None):
    db = get_db()
    if owner_username:
        owner = db.execute("SELECT * FROM users WHERE username = ?", (owner_username,)).fetchone()
        if not owner or get_permission(owner["id"]) != "edit":
            flash("Access denied.", "error")
            return redirect(url_for("index"))
    else:
        owner = g.user

    if request.method == "POST":
        image_data, image_mime = None, None
        file = request.files.get("image")
        if file and file.filename:
            image_data = file.read()
            image_mime = file.content_type

        breed = request.form.get("breed", "").strip()
        breed_mix = request.form.get("breed_mix", "").strip() if breed == "hybrid" else ""

        try:
            db.execute(
                """INSERT INTO birds
                   (user_id, ring_number, name, species, breed, breed_mix, sex,
                    birth_date, birth_approximate, notes, image, image_mime)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                (
                    owner["id"],
                    request.form["ring_number"].strip(),
                    request.form.get("name", "").strip() or None,
                    request.form["species"],
                    breed or None,
                    breed_mix or None,
                    request.form.get("sex", "unknown"),
                    request.form.get("birth_date", "").strip() or None,
                    1 if request.form.get("birth_approximate") else 0,
                    request.form.get("notes", "").strip() or None,
                    image_data,
                    image_mime,
                ),
            )
            db.commit()
            flash("Bird registered!", "success")
            return redirect(url_for("view_flock", username=owner["username"]))
        except sqlite3.IntegrityError:
            flash("Ring number already exists in this flock.", "error")

    return render_template("form.html", bird=None, owner=owner)


@app.route("/edit/<int:bird_id>", methods=["GET", "POST"])
@login_required
def edit_bird(bird_id):
    db = get_db()
    bird = db.execute("SELECT * FROM birds WHERE id = ?", (bird_id,)).fetchone()
    if not bird or get_permission(bird["user_id"]) != "edit":
        flash("Access denied.", "error")
        return redirect(url_for("index"))

    owner = db.execute("SELECT * FROM users WHERE id = ?", (bird["user_id"],)).fetchone()

    if request.method == "POST":
        image_data = bird["image"]
        image_mime = bird["image_mime"]
        if request.form.get("remove_image"):
            image_data, image_mime = None, None
        file = request.files.get("image")
        if file and file.filename:
            image_data = file.read()
            image_mime = file.content_type

        breed = request.form.get("breed", "").strip()
        breed_mix = request.form.get("breed_mix", "").strip() if breed == "hybrid" else ""
        is_dead = 1 if request.form.get("is_dead") else 0
        death_date = (request.form.get("death_date", "").strip() or None) if is_dead else None

        try:
            db.execute(
                """UPDATE birds SET ring_number=?, name=?, species=?, breed=?, breed_mix=?,
                   sex=?, birth_date=?, birth_approximate=?, notes=?, is_dead=?, death_date=?,
                   image=?, image_mime=? WHERE id=?""",
                (
                    request.form["ring_number"].strip(),
                    request.form.get("name", "").strip() or None,
                    request.form["species"],
                    breed or None,
                    breed_mix or None,
                    request.form.get("sex", "unknown"),
                    request.form.get("birth_date", "").strip() or None,
                    1 if request.form.get("birth_approximate") else 0,
                    request.form.get("notes", "").strip() or None,
                    is_dead,
                    death_date,
                    image_data,
                    image_mime,
                    bird_id,
                ),
            )
            db.commit()
            flash("Bird updated!", "success")
            return redirect(url_for("view_bird", bird_id=bird_id))
        except sqlite3.IntegrityError:
            flash("Ring number already exists in this flock.", "error")

    return render_template("form.html", bird=bird, owner=owner)


@app.route("/bird/<int:bird_id>")
@login_required
def view_bird(bird_id):
    db = get_db()
    bird = db.execute(
        "SELECT *, image IS NOT NULL as has_image FROM birds WHERE id = ?", (bird_id,)
    ).fetchone()
    if not bird:
        flash("Bird not found.", "error")
        return redirect(url_for("index"))

    perm = get_permission(bird["user_id"])
    if not perm:
        flash("Access denied.", "error")
        return redirect(url_for("index"))

    owner = db.execute("SELECT * FROM users WHERE id = ?", (bird["user_id"],)).fetchone()
    notes = db.execute(
        "SELECT * FROM bird_notes WHERE bird_id = ? ORDER BY note_date DESC, created_at DESC",
        (bird_id,)
    ).fetchall()
    return render_template("view.html", bird=bird, can_edit=(perm == "edit"), owner=owner,
                           notes=notes, today=_date.today().isoformat())


@app.route("/bird/<int:bird_id>/image")
def bird_image(bird_id):
    db = get_db()
    bird = db.execute("SELECT image, image_mime, user_id FROM birds WHERE id = ?", (bird_id,)).fetchone()
    if not bird or not bird["image"]:
        return "", 404
    if not get_permission(bird["user_id"]):
        return "", 403
    return send_file(BytesIO(bird["image"]), mimetype=bird["image_mime"])


@app.route("/delete/<int:bird_id>", methods=["POST"])
@login_required
def delete_bird(bird_id):
    db = get_db()
    bird = db.execute("SELECT user_id FROM birds WHERE id = ?", (bird_id,)).fetchone()
    if not bird:
        flash("Bird not found.", "error")
        return redirect(url_for("index"))
    # Only the owner can delete
    if not g.user or g.user["id"] != bird["user_id"]:
        flash("Only the flock owner can delete birds.", "error")
        return redirect(url_for("view_bird", bird_id=bird_id))

    owner = db.execute("SELECT username FROM users WHERE id = ?", (bird["user_id"],)).fetchone()
    db.execute("DELETE FROM birds WHERE id = ?", (bird_id,))
    db.commit()
    flash("Bird deleted.", "success")
    return redirect(url_for("view_flock", username=owner["username"]))


@app.route("/bird/<int:bird_id>/note", methods=["POST"])
@login_required
def add_note(bird_id):
    db = get_db()
    bird = db.execute("SELECT user_id FROM birds WHERE id = ?", (bird_id,)).fetchone()
    if not bird or get_permission(bird["user_id"]) != "edit":
        flash("Access denied.", "error")
        return redirect(url_for("index"))

    note_date = request.form.get("note_date", "").strip()
    content = request.form.get("content", "").strip()
    if not note_date or not content:
        flash("Date and content are required.", "error")
    else:
        db.execute(
            "INSERT INTO bird_notes (bird_id, note_date, content) VALUES (?, ?, ?)",
            (bird_id, note_date, content)
        )
        db.commit()
    return redirect(url_for("view_bird", bird_id=bird_id))


@app.route("/bird/<int:bird_id>/note/<int:note_id>/delete", methods=["POST"])
@login_required
def delete_note(bird_id, note_id):
    db = get_db()
    bird = db.execute("SELECT user_id FROM birds WHERE id = ?", (bird_id,)).fetchone()
    if not bird or get_permission(bird["user_id"]) != "edit":
        flash("Access denied.", "error")
        return redirect(url_for("index"))

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

    if request.method == "POST":
        action = request.form.get("action")

        if action == "grant":
            username = request.form.get("username", "").strip()
            can_edit = 1 if request.form.get("can_edit") else 0
            target = db.execute("SELECT * FROM users WHERE username = ?", (username,)).fetchone()
            if not target:
                flash("User not found.", "error")
            elif target["id"] == g.user["id"]:
                flash("You can't share your flock with yourself.", "error")
            else:
                try:
                    db.execute(
                        "INSERT INTO flock_shares (owner_id, grantee_id, can_edit) VALUES (?, ?, ?)",
                        (g.user["id"], target["id"], can_edit)
                    )
                except sqlite3.IntegrityError:
                    db.execute(
                        "UPDATE flock_shares SET can_edit = ? WHERE owner_id = ? AND grantee_id = ?",
                        (can_edit, g.user["id"], target["id"])
                    )
                db.commit()
                perm_label = "view & edit" if can_edit else "view"
                flash(f"Flock shared with {target['username']} ({perm_label}).", "success")

        elif action == "revoke":
            share_id = request.form.get("share_id", type=int)
            db.execute(
                "DELETE FROM flock_shares WHERE id = ? AND owner_id = ?",
                (share_id, g.user["id"])
            )
            db.commit()
            flash("Share removed.", "success")

    my_shares = db.execute("""
        SELECT fs.id, fs.can_edit, u.username
        FROM flock_shares fs JOIN users u ON u.id = fs.grantee_id
        WHERE fs.owner_id = ? ORDER BY u.username
    """, (g.user["id"],)).fetchall()

    shared_with_me = db.execute("""
        SELECT u.username, fs.can_edit
        FROM flock_shares fs JOIN users u ON u.id = fs.owner_id
        WHERE fs.grantee_id = ? ORDER BY u.username
    """, (g.user["id"],)).fetchall()

    return render_template("settings.html", my_shares=my_shares, shared_with_me=shared_with_me)


if __name__ == "__main__":
    app.run(debug=True, port=5000)
