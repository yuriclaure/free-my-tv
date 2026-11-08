#!/usr/bin/env python3
"""
Tries what the TV's Netflix row would read, from this computer, before it's built into the app.

  python3 tools/netflix_probe.py --save   asks for the two netflix.com cookies (not shown as you type) and keeps them in
                                          netflix-cookies.local (only you can read it; never commit it)
  python3 tools/netflix_probe.py          reads your viewing activity and each show's next episode, and prints the cards
                                          the TV would show (titles only: never the cookies)

The cookies: sign in at netflix.com in a desktop browser, pick your profile, open the developer tools (Application or
Storage → Cookies → https://www.netflix.com) and copy the values of NetflixId and SecureNetflixId.
Netflix's website interface isn't public: it may change, and using it may go against Netflix's terms of use.
"""
import getpass
import http.client
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

FILE = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "netflix-cookies.local")
UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"


def save():
    nid = getpass.getpass("NetflixId: ").strip()
    snid = getpass.getpass("SecureNetflixId: ").strip()
    if not nid or not snid:
        sys.exit("Both values are needed.")
    fd = os.open(FILE, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f:
        json.dump({"NetflixId": nid, "SecureNetflixId": snid}, f)
    print("Saved in " + FILE + " (only your user can read it).")


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def get(url, cookies):
    req = urllib.request.Request(url, headers={
        "User-Agent": UA, "Accept-Language": "en-GB,en;q=0.9",
        "Cookie": "; ".join(k + "=" + v for k, v in cookies.items()),
    })
    opener = urllib.request.build_opener(NoRedirect)
    try:
        with opener.open(req, timeout=20) as r:
            rotated = [h for h in r.headers.get_all("Set-Cookie") or [] if h.split("=")[0] in cookies]
            try:
                body = r.read()
            except http.client.IncompleteRead as e:   # Netflix's big pages sometimes end early: what came is enough
                body = e.partial
            return r.status, body.decode("utf-8", "replace"), rotated
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace") if e.fp else "", []


def main():
    if "--save" in sys.argv:
        return save()
    if not os.path.exists(FILE):
        sys.exit("No cookies yet: run  python3 tools/netflix_probe.py --save  first.")
    cookies = json.load(open(FILE))

    status, html, rotated = get("https://www.netflix.com/browse", cookies)
    print("1. /browse:", status, "(cookies renewed by Netflix)" if rotated else "")
    if status in (301, 302, 303):
        sys.exit("   Netflix sent us to sign in: the cookies have expired or belong to a signed-out session.")
    m = re.search(r'"userGuid"\s*:\s*"([A-Z0-9]+)"', html)
    if not m:
        sys.exit("   No profile on the page: Netflix changed it.")
    guid = m.group(1)

    # The viewing list (the account page's own call): newest first, without positions.
    def page(n):
        url = "https://www.netflix.com/api/aui/pathEvaluator/web/%5E2.0.0?" + urllib.parse.urlencode(
            {"method": "call", "callPath": json.dumps(["aui", "viewingActivity", n, 20]), "falcor_server": "0.1.0"})
        body = urllib.parse.urlencode({"param": json.dumps({"guid": guid})}).encode()
        req = urllib.request.Request(url, data=body, headers={
            "User-Agent": UA, "Content-Type": "application/x-www-form-urlencoded", "Accept": "application/json",
            "Origin": "https://www.netflix.com", "Referer": "https://www.netflix.com/settings/viewed",
            "x-netflix.request.routing": json.dumps({"path": "/nq/aui/endpoint/%5E1.0.0-web/pathEvaluator", "control_tag": "auinqweb"}),
            "Cookie": "; ".join(k + "=" + v for k, v in cookies.items())})
        try:
            with urllib.request.urlopen(req, timeout=20) as r:
                data = json.loads(r.read().decode())
        except urllib.error.HTTPError as e:
            sys.exit("2. viewing activity: %s %s" % (e.code, e.read()[:200]))
        return data.get("jsonGraph", {}).get("aui", {}).get("viewingActivity", {}).get("value", {}).get("viewedItems", [])

    # Pages of 20 until 12 different titles, or 120 days back.
    items, titles = [], set()
    for n in range(10):
        got = page(n)
        items += got
        titles |= {i.get("series") or i.get("movieID") for i in got}
        if not got or len(titles) >= 12 or (got[-1].get("date", 0) / 1000 < time.time() - 120 * 86400):
            break
    print("2. viewing activity: %d entries" % len(items))

    # Each title's details: where you are in it, and for a show the episode Netflix would play next.
    cards, seen, now = [], set(), time.time()
    for it in items:
        key = it.get("series") or it.get("movieID")
        if key in seen:
            continue
        seen.add(key)
        if len(seen) > 12:
            break
        status, body, _ = get("https://www.netflix.com/nq/website/memberapi/release/metadata?movieid=%s&_=%d"
                              % (key, int(now * 1000)), cookies)
        if status != 200:
            print("   details of %s: %s" % (it.get("seriesTitle") or it.get("title"), status))
            continue
        v = json.loads(body).get("video", {})
        title = v.get("title") or it.get("seriesTitle") or it.get("title")
        if v.get("type") == "show":
            current, found = v.get("currentEpisode"), None
            for season in v.get("seasons", []):
                for ep in season.get("episodes", []):
                    if current in (ep.get("id"), ep.get("episodeId")):
                        found = (season, ep)
            if not found:
                cards.append((title, "(no next episode found)", None))
                continue
            season, ep = found
            offset = (ep.get("bookmark") or {}).get("offset", 0) or 0
            end = ep.get("creditsOffset") or ep.get("runtime") or 0
            label = "S%s E%s %s" % (season.get("seq"), ep.get("seq"), ep.get("title"))
            if end and offset >= end - 30:
                cards.append((title, "finished (%s was the last)" % label, None))
            elif offset > 60:
                cards.append((title, "%s - %d min left" % (label, max(1, (end - offset) // 60)), ep.get("id")))
            else:
                cards.append((title, "%s - up next" % label, ep.get("id")))
        else:
            offset = (v.get("bookmark") or {}).get("offset", 0) or 0
            end = v.get("creditsOffset") or v.get("runtime") or 0
            if end and offset >= end - 30:
                cards.append((title, "movie, finished", None))
            elif offset > 60:
                cards.append((title, "movie - %d min left" % max(1, (end - offset) // 60), v.get("id")))
            else:
                cards.append((title, "movie, not started", None))

    print("\nThe cards the TV would show (newest first; finished ones left out):")
    for title, what, vid in cards:
        print("  %s %-34s %s" % ("*" if vid else " ", (title or "")[:34], what))

if __name__ == "__main__":
    main()
