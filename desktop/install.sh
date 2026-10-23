#!/usr/bin/env bash
# Installs xrnrule on this laptop (KDE Plasma 6 on Linux): the daemon, its KWin script, and a
# systemd user service that keeps it running. Run it again to update. See desktop/README.md.
#
#   desktop/install.sh            build (needs JDK 17 in JAVA_HOME) and install
#   desktop/install.sh --no-build install what desktop/build/install already has
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
root="$(dirname "$here")"
data="${XDG_DATA_HOME:-$HOME/.local/share}"
config="${XDG_CONFIG_HOME:-$HOME/.config}"

if [[ "${1:-}" != "--no-build" ]]; then
  echo "Building…"
  (cd "$root" && ./gradlew -q :desktop:installDist)
fi

# Moving over from xrnhabit, this project's old name: stop its service, drop its KWin script,
# and carry its settings and state (the blocks, this laptop's device id) over.
if [[ -e "$config/systemd/user/xrnhabit-desktop.service" || -d "$config/xrnhabit" || -d "$data/xrnhabit" ]]; then
  echo "Moving over from xrnhabit"
  systemctl --user disable --now xrnhabit-desktop.service >/dev/null 2>&1 || true
  rm -f "$config/systemd/user/xrnhabit-desktop.service"
  rm -rf "$data/xrnhabit-desktop" "$data/kwin/scripts/xrnhabit"
  kwriteconfig6 --file kwinrc --group Plugins --key xrnhabitEnabled --delete || true
  qdbus6 org.kde.KWin /Scripting org.kde.kwin.Scripting.unloadScript xrnhabit >/dev/null 2>&1 || true
  if [[ -d "$config/xrnhabit" && ! -e "$config/xrnrule" ]]; then
    mv "$config/xrnhabit" "$config/xrnrule"
    # Paths inside it, such as passwordFile, pointed into the old folder.
    sed -i 's#/xrnhabit/#/xrnrule/#g' "$config/xrnrule/desktop.properties"
  fi
  if [[ -d "$data/xrnhabit" && ! -e "$data/xrnrule" ]]; then
    mv "$data/xrnhabit" "$data/xrnrule"
  fi
fi

echo "Installing the daemon to $data/xrnrule-desktop"
rm -rf "$data/xrnrule-desktop"
cp -r "$root/desktop/build/install/xrnrule-desktop" "$data/xrnrule-desktop"

echo "Installing the KWin script"
mkdir -p "$data/kwin/scripts"
rm -rf "$data/kwin/scripts/xrnrule"
cp -r "$here/kwin/xrnrule" "$data/kwin/scripts/xrnrule"
kwriteconfig6 --file kwinrc --group Plugins --key xrnruleEnabled true
qdbus6 org.kde.KWin /Scripting org.kde.kwin.Scripting.unloadScript xrnrule >/dev/null 2>&1 || true
qdbus6 org.kde.KWin /KWin reconfigure >/dev/null 2>&1 || true

if [[ ! -f "$config/xrnrule/desktop.properties" ]]; then
  mkdir -p "$config/xrnrule"
  cat > "$config/xrnrule/desktop.properties" <<'PROPS'
# xrnrule on the laptop. Leave server empty to block on this laptop only.
# Both sync services hang off the server: <server>/xrnrule and <server>/xrnloops.
server=
user=
# Either password=… or a file holding just the password:
passwordFile=
port=47321
PROPS
  echo "Wrote $config/xrnrule/desktop.properties: fill in the server and login to sync with the phone."
fi

echo "Starting the service"
mkdir -p "$config/systemd/user"
cp "$here/xrnrule-desktop.service" "$config/systemd/user/"
systemctl --user daemon-reload
systemctl --user enable xrnrule-desktop.service >/dev/null
systemctl --user restart xrnrule-desktop.service

cat <<DONE

Done. The local page is http://127.0.0.1:47321/ (or the port in desktop.properties).
For websites, load the browser extension once:
  open the browser's extensions page (helium://extensions or chrome://extensions),
  turn on Developer mode, then "Load unpacked" and pick
  $here/extension
  and allow it in incognito windows too (its Details page).
DONE
