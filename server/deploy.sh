#!/bin/sh
# Updates xrnrule-sync and its status page on the home server, reached over ssh (Termux with runit).
# It tests the server, replaces the program and the web folder together, restarts the service
# and waits for it to answer. The data is left alone.
#
#     server/deploy.sh [ssh-host]      (default: homeserver)
#
# The web folder goes with the program on purpose. Copying only xrnrule-sync.py left the page
# of 24 Oct 2026 on the server while later changes to it sat in the repo (found 11 Nov 2026).
set -eu

HOST=${1:-homeserver}
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/.." && pwd)

echo "Testing the server code first..."
if ! (cd "$REPO" && PYTHONDONTWRITEBYTECODE=1 python3 -m unittest server/test_xrnrule_sync.py >/dev/null 2>&1); then
    echo "The server tests failed, so nothing was changed. Run them for details:" >&2
    echo "    python3 -m unittest server/test_xrnrule_sync.py" >&2
    exit 1
fi

echo "Copying it to $HOST..."
tar czf - -C "$HERE" xrnrule-sync.py web | ssh "$HOST" '
    set -e
    if [ ! -d ~/xrnrule-sync/data ]; then
        echo "No ~/xrnrule-sync/data on this host: set the service up first (home-server README)." >&2
        exit 1
    fi
    cd ~/xrnrule-sync
    # Unpacked beside the live files and moved in whole, so a copy cut short changes nothing
    # and a file the repo dropped does not stay behind.
    rm -rf .incoming
    mkdir .incoming
    tar xzf - -C .incoming
    test -s .incoming/xrnrule-sync.py
    test -s .incoming/web/index.html
    rm -rf web.old
    if [ -d web ]; then mv web web.old; fi
    mv .incoming/web web
    mv .incoming/xrnrule-sync.py xrnrule-sync.py
    chmod 755 xrnrule-sync.py
    rm -rf .incoming web.old
    SVDIR=$PREFIX/var/service sv restart xrnrule-sync
'

echo "Waiting for it to answer..."
tries=0
until [ "$(ssh "$HOST" 'curl -s -m 3 http://127.0.0.1:5235/health' || true)" = ok ]; do
    tries=$((tries + 1))
    if [ "$tries" -ge 8 ]; then
        echo "It did not start. Its log:" >&2
        ssh "$HOST" 'tail -20 $PREFIX/var/log/sv/xrnrule-sync/current' >&2 || true
        exit 1
    fi
    sleep 2
done

echo "Done."
