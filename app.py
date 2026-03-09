"""RingLog - Bird registry for ducks and hens."""

import sqlite3
import os
from datetime import date
from flask import (
    Flask, render_template, request, redirect, url_for, g, send_file, flash
)
from io import BytesIO

app = Flask(__name__)
app.secret_key = os.urandom(24)
DATABASE = os.path.join(os.path.dirname(__file__), "flockbook.db")


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


def init_db():
    db = sqlite3.connect(DATABASE)
    db.execute("""
        CREATE TABLE IF NOT EXISTS birds (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            ring_number TEXT NOT NULL UNIQUE,
            name TEXT,
            species TEXT NOT NULL CHECK(species IN ('duck', 'hen')),
            breed TEXT,
            breed_mix TEXT,
            sex TEXT CHECK(sex IN ('male', 'female', 'unknown')),
            birth_date TEXT,
            birth_approximate INTEGER DEFAULT 0,
            notes TEXT,
            is_dead INTEGER DEFAULT 0,
            death_date TEXT,
            image BLOB,
            image_mime TEXT,
            created_at TEXT DEFAULT (date('now'))
        )
    """)
    db.commit()
    db.close()


@app.route("/")
def index():
    db = get_db()
    filter_species = request.args.get("species", "")
    filter_status = request.args.get("status", "alive")

    query = "SELECT id, ring_number, name, species, breed, breed_mix, sex, birth_date, birth_approximate, is_dead, image IS NOT NULL as has_image FROM birds WHERE 1=1"
    params = []

    if filter_species:
        query += " AND species = ?"
        params.append(filter_species)
    if filter_status == "alive":
        query += " AND is_dead = 0"
    elif filter_status == "dead":
        query += " AND is_dead = 1"

    query += " ORDER BY created_at DESC"
    birds = db.execute(query, params).fetchall()
    return render_template("index.html", birds=birds, filter_species=filter_species, filter_status=filter_status)


@app.route("/add", methods=["GET", "POST"])
def add_bird():
    if request.method == "POST":
        db = get_db()
        image_data = None
        image_mime = None
        file = request.files.get("image")
        if file and file.filename:
            image_data = file.read()
            image_mime = file.content_type

        breed = request.form.get("breed", "").strip()
        breed_mix = ""
        if breed == "hybrid":
            breed_mix = request.form.get("breed_mix", "").strip()

        try:
            db.execute(
                """INSERT INTO birds (ring_number, name, species, breed, breed_mix, sex, birth_date, birth_approximate, notes, image, image_mime)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
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
                    image_data,
                    image_mime,
                ),
            )
            db.commit()
            flash("Bird registered!", "success")
            return redirect(url_for("index"))
        except sqlite3.IntegrityError:
            flash("Ring number already exists.", "error")

    return render_template("form.html", bird=None)


@app.route("/edit/<int:bird_id>", methods=["GET", "POST"])
def edit_bird(bird_id):
    db = get_db()
    bird = db.execute("SELECT * FROM birds WHERE id = ?", (bird_id,)).fetchone()
    if not bird:
        flash("Bird not found.", "error")
        return redirect(url_for("index"))

    if request.method == "POST":
        image_data = bird["image"]
        image_mime = bird["image_mime"]

        if request.form.get("remove_image"):
            image_data = None
            image_mime = None

        file = request.files.get("image")
        if file and file.filename:
            image_data = file.read()
            image_mime = file.content_type

        breed = request.form.get("breed", "").strip()
        breed_mix = ""
        if breed == "hybrid":
            breed_mix = request.form.get("breed_mix", "").strip()

        is_dead = 1 if request.form.get("is_dead") else 0
        death_date = request.form.get("death_date", "").strip() or None
        if not is_dead:
            death_date = None

        try:
            db.execute(
                """UPDATE birds SET ring_number=?, name=?, species=?, breed=?, breed_mix=?, sex=?,
                   birth_date=?, birth_approximate=?, notes=?, is_dead=?, death_date=?, image=?, image_mime=?
                   WHERE id=?""",
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
            flash("Ring number already exists.", "error")

    return render_template("form.html", bird=bird)


@app.route("/bird/<int:bird_id>")
def view_bird(bird_id):
    db = get_db()
    bird = db.execute("SELECT *, image IS NOT NULL as has_image FROM birds WHERE id = ?", (bird_id,)).fetchone()
    if not bird:
        flash("Bird not found.", "error")
        return redirect(url_for("index"))
    return render_template("view.html", bird=bird)


@app.route("/bird/<int:bird_id>/image")
def bird_image(bird_id):
    db = get_db()
    bird = db.execute("SELECT image, image_mime FROM birds WHERE id = ?", (bird_id,)).fetchone()
    if not bird or not bird["image"]:
        return "", 404
    return send_file(BytesIO(bird["image"]), mimetype=bird["image_mime"])


@app.route("/delete/<int:bird_id>", methods=["POST"])
def delete_bird(bird_id):
    db = get_db()
    db.execute("DELETE FROM birds WHERE id = ?", (bird_id,))
    db.commit()
    flash("Bird deleted.", "success")
    return redirect(url_for("index"))


if __name__ == "__main__":
    init_db()
    app.run(debug=True, port=5000)
