#!/usr/bin/env python3
"""Turns IMDb's free daily datasets into small per-show files of episode ratings.

Usage: imdb-episode-ratings.py title.ratings.tsv.gz title.episode.tsv.gz OUT_DIR

Writes OUT_DIR/000.json .. 999.json. A show's file is its IMDb number modulo 1000
(tt0460649 -> 649.json). Each file maps show id -> season -> episode -> rating:
{"tt0460649":{"2":{"5":8.7}}}. Every file is written, even when empty, so the app
can tell "no ratings for this show" apart from "could not download".
Data: https://developer.imdb.com/non-commercial-datasets/ (personal, non-commercial use).
"""
import csv, gzip, json, os, sys
from datetime import datetime, timezone

SHARDS = 1000
csv.field_size_limit(1 << 24)


def rows(path):
    with gzip.open(path, "rt", encoding="utf-8", newline="") as f:
        reader = csv.reader(f, delimiter="\t", quoting=csv.QUOTE_NONE)
        header = next(reader)
        for row in reader:
            yield dict(zip(header, row))


def main(ratings_path, episodes_path, out_dir):
    ratings = {}
    for r in rows(ratings_path):
        try:
            ratings[r["tconst"]] = float(r["averageRating"])
        except (KeyError, ValueError):
            pass
    if len(ratings) < 100_000:
        sys.exit(f"Only {len(ratings)} ratings read; the IMDb file looks wrong. Not publishing.")

    shows, episodes = {}, 0
    for e in rows(episodes_path):
        rating = ratings.get(e.get("tconst"))
        season, episode, parent = e.get("seasonNumber"), e.get("episodeNumber"), e.get("parentTconst", "")
        if rating is None or not season or not episode or not season.isdigit() or not episode.isdigit():
            continue
        if not parent.startswith("tt") or not parent[2:].isdigit():
            continue
        shows.setdefault(parent, {}).setdefault(str(int(season)), {})[str(int(episode))] = rating
        episodes += 1
    if episodes < 100_000:
        sys.exit(f"Only {episodes} rated episodes found; the IMDb files look wrong. Not publishing.")

    shards = [{} for _ in range(SHARDS)]
    for show, seasons in shows.items():
        shards[int(show[2:]) % SHARDS][show] = seasons
    os.makedirs(out_dir, exist_ok=True)
    for i, shard in enumerate(shards):
        with open(os.path.join(out_dir, f"{i:03d}.json"), "w", encoding="utf-8") as f:
            json.dump(shard, f, separators=(",", ":"), sort_keys=True)
    with open(os.path.join(out_dir, "info.json"), "w", encoding="utf-8") as f:
        json.dump({"updated": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%MZ"),
                   "shows": len(shows), "episodes": episodes}, f)
    print(f"{len(shows)} shows, {episodes} rated episodes, {SHARDS} files")


if __name__ == "__main__":
    main(*sys.argv[1:4])
