"""
Ghar Khata (Family Ledger)
--------------------------
Streamlit app to track multiple family members' savings/pension accounts,
month by month, with data persisted in the VISITOR'S OWN BROWSER via
localStorage (no server-side file or database — nothing is saved on
GitHub / Streamlit Cloud's disk).

Data model (all kept in st.session_state.data, mirrored to localStorage):

{
  "accounts": [
    {
      "id": "acc_1",
      "name": "Savings Account",
      "months": {
        "2026-07": {
          "previous_balance": 6074.4,
          "entries": [
            {"label": "From July Salary", "amount": 2540},
            {"label": "Petrol", "amount": 6002},
            {"label": "Interest", "amount": 46}
          ]
        }
      }
    }
  ]
}

total(month) = previous_balance + sum(entry amounts)
next month's previous_balance is auto carried forward from this total.
"""

import json
import uuid
from datetime import date

import pandas as pd
import plotly.graph_objects as go
import streamlit as st
import streamlit.components.v1 as components
from streamlit_js_eval import streamlit_js_eval

# --------------------------------------------------------------------------- #
# Config & constants
# --------------------------------------------------------------------------- #

STORAGE_KEY = "ghar_khata_data_v1"
MAX_LOAD_RETRIES = 3  # streamlit-local-storage needs a couple of reruns
                       # before the value actually comes back from the browser

# --- Google Drive sync (optional) ------------------------------------------
# Uses Google Identity Services (browser-side OAuth token flow) + the Drive
# API's private "appDataFolder" — a hidden folder only this app can see, not
# the user's regular Drive files. No client secret is needed for this flow.
# Values are read ONLY from Streamlit Cloud's "Secrets" (st.secrets) —
# nothing sensitive is hardcoded in this file. Set these under your app's
# Settings → Secrets on Streamlit Cloud:
#   GOOGLE_CLIENT_ID = "your-client-id.apps.googleusercontent.com"
#   GOOGLE_API_KEY = "your-api-key"
try:
    GOOGLE_CLIENT_ID = st.secrets.get("GOOGLE_CLIENT_ID", "")
    GOOGLE_API_KEY = st.secrets.get("GOOGLE_API_KEY", "")
except Exception:
    GOOGLE_CLIENT_ID = ""
    GOOGLE_API_KEY = ""
DRIVE_FILE_NAME = "ghar_khata_data_v1.json"
DRIVE_RESTORE_KEY = "ghar_khata_drive_restore_payload"  # bridge key used to
                                                          # hand restored JSON
                                                          # from JS back to Python

MONTH_NAMES = [
    "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December",
]


def default_data():
    """Seed data — matches the values already in the user's spreadsheet."""
    return {
        "accounts": [
            {
                "id": "acc_savings",
                "name": "Savings Account",
                "months": {
                    "2026-07": {
                        "previous_balance": 6074.4,
                        "entries": [
                            {"label": "From July Salary", "amount": 2540},
                            {"label": "Petrol", "amount": 6002},
                            {"label": "Interest", "amount": 46},
                        ],
                    },
                    "2026-08": {"previous_balance": 14662.4, "entries": []},
                },
            },
            {
                "id": "acc_mummy1",
                "name": "Mummy Account 1",
                "months": {
                    "2026-07": {
                        "previous_balance": 3600,
                        "entries": [
                            {"label": "From July Pension", "amount": 3200},
                        ],
                    }
                },
            },
            {
                "id": "acc_mummy2",
                "name": "Mummy Account 2",
                "months": {
                    "2026-07": {
                        "previous_balance": 1100,
                        "entries": [
                            {"label": "From July Pension", "amount": 1100},
                        ],
                    }
                },
            },
        ]
    }


# --------------------------------------------------------------------------- #
# Storage helpers
# --------------------------------------------------------------------------- #

def load_data():
    """Load ledger data from the browser's localStorage into session_state.

    The JS bridge fetches the value asynchronously, so the first render (or
    two) may come back empty even when data exists. We rerun a few times to
    give it a chance before falling back to the sample data.
    """
    if "data" in st.session_state:
        return

    raw = streamlit_js_eval(
        js_expressions=f"localStorage.getItem({json.dumps(STORAGE_KEY)})",
        key="load_ghar_khata",
        want_output=True,
    )

    if raw:
        try:
            st.session_state.data = json.loads(raw)
            return
        except (json.JSONDecodeError, TypeError):
            pass

    tries = st.session_state.get("_load_tries", 0)
    if tries < MAX_LOAD_RETRIES:
        st.session_state._load_tries = tries + 1
        st.rerun()
    else:
        st.session_state.data = default_data()


def run_pending_save():
    """Unconditionally invoke the JS bridge every rerun (required for it to
    work reliably), writing to localStorage only when a save is pending.
    """
    pending = st.session_state.pop("_pending_save_json", None)
    counter = st.session_state.get("_save_counter", 0)

    if pending is not None:
        counter += 1
        st.session_state["_save_counter"] = counter
        js_code = (
            f"localStorage.setItem({json.dumps(STORAGE_KEY)}, "
            f"{json.dumps(pending)}); 'saved'"
        )
    else:
        js_code = "'idle'"

    streamlit_js_eval(js_expressions=js_code, key=f"save_ghar_khata_{counter}", want_output=False)

    if pending is not None:
        st.session_state.dirty = False
        st.toast("Saved to this browser's storage ✔", icon="💾")


def save_data():
    """Queue the current session_state.data to be written to localStorage."""
    st.session_state["_pending_save_json"] = json.dumps(st.session_state.data)


def render_drive_sync(data):
    """Optional Google Drive sync — connect once, then Save/Restore the whole
    ledger as a single JSON file in the app's private, hidden Drive folder
    (appDataFolder). This never touches the rest of the user's Drive.

    IMPORTANT: Google's Identity Services refuses to run a sign-in flow
    inside an embedded iframe ("Access blocked: Authorization Error" /
    Error 400: invalid_request). Streamlit's components.html() always
    renders inside an iframe, so instead of building the button/script
    there, we inject a real <script> tag into the TOP-LEVEL page
    (window.parent.document) — same trick used by inject_auto_fullscreen()
    below. That script builds a small floating panel directly on the real
    page, so Google sees a genuine top-level sign-in, not an iframe one.
    """
    st.markdown("### ☁️ Google Drive Sync")

    if not GOOGLE_CLIENT_ID:
        st.info(
            "Google Drive sync abhi setup nahi hai — Streamlit Cloud app ke "
            "**Settings → Secrets** mein `GOOGLE_CLIENT_ID` aur `GOOGLE_API_KEY` "
            "daalne ke baad ye section active ho jayega."
        )
        return

    st.caption(
        "Screen ke sabse neeche ek floating panel dikhega — 'Connect Google Drive' "
        "se login karein, phir 'Save to Drive' / 'Restore from Drive' use karein."
    )

    current_data_json = json.dumps(json.dumps(data))  # embed as a JS string literal

    inner_js = f"""
    (function() {{
      // Refresh the latest ledger data every rerun, even though the panel
      // itself (below) is only built once per page load.
      window.__ghkCurrentDataJson = {current_data_json};
      if (window.__ghkDriveHooked) {{ return; }}
      window.__ghkDriveHooked = true;

      const CLIENT_ID = {json.dumps(GOOGLE_CLIENT_ID)};
      const SCOPE = "https://www.googleapis.com/auth/drive.appdata";
      const FILE_NAME = {json.dumps(DRIVE_FILE_NAME)};
      const RESTORE_KEY = {json.dumps(DRIVE_RESTORE_KEY)};

      const panel = document.createElement('div');
      panel.id = 'ghk-drive-panel';
      panel.style.cssText =
        'position:fixed;left:0;right:0;bottom:0;z-index:999999;' +
        'background:#ffffff;border-top:2px solid #1f6feb;padding:10px 14px 12px;' +
        'font-family:-apple-system,Segoe UI,Roboto,sans-serif;' +
        'box-shadow:0 -2px 10px rgba(0,0,0,0.15);';
      panel.innerHTML =
        '<div id="ghk-drive-status" style="font-size:13px;color:#666;margin-bottom:6px;">Google Drive: Not connected</div>' +
        '<button id="ghk-connect-btn" style="padding:8px 14px;border-radius:8px;border:none;background:#1f6feb;color:#fff;font-size:13px;margin-right:6px;margin-bottom:6px;cursor:pointer;">Connect Google Drive</button>' +
        '<button id="ghk-save-btn" disabled style="padding:8px 14px;border-radius:8px;border:none;background:#2e7d32;color:#fff;font-size:13px;margin-right:6px;margin-bottom:6px;opacity:0.5;cursor:pointer;">☁️ Save to Drive</button>' +
        '<button id="ghk-restore-btn" disabled style="padding:8px 14px;border-radius:8px;border:none;background:#b26a00;color:#fff;font-size:13px;margin-bottom:6px;opacity:0.5;cursor:pointer;">⬇️ Restore from Drive</button>' +
        '<button id="ghk-close-btn" style="float:right;padding:6px 10px;border-radius:8px;border:1px solid #ccc;background:#f5f5f5;color:#333;font-size:13px;cursor:pointer;">✕</button>' +
        '<div id="ghk-drive-msg" style="font-size:12px;min-height:16px;margin-top:2px;"></div>';
      document.body.appendChild(panel);
      document.getElementById('ghk-close-btn').addEventListener('click', function() {{
        panel.style.display = 'none';
      }});

      const statusEl = document.getElementById('ghk-drive-status');
      const msgEl = document.getElementById('ghk-drive-msg');
      const connectBtn = document.getElementById('ghk-connect-btn');
      const saveBtn = document.getElementById('ghk-save-btn');
      const restoreBtn = document.getElementById('ghk-restore-btn');

      let accessToken = window.sessionStorage.getItem('ghk_drive_token') || null;
      let tokenExpiry = parseInt(window.sessionStorage.getItem('ghk_drive_token_exp') || '0', 10);

      function setMsg(text, isError) {{
        msgEl.style.color = isError ? '#b3261e' : '#2e7d32';
        msgEl.textContent = text;
      }}
      function haveValidToken() {{ return accessToken && Date.now() < tokenExpiry; }}
      function markConnected() {{
        statusEl.textContent = 'Google Drive: Connected ✔';
        statusEl.style.color = '#2e7d32';
        saveBtn.disabled = false; saveBtn.style.opacity = 1;
        restoreBtn.disabled = false; restoreBtn.style.opacity = 1;
        connectBtn.textContent = 'Reconnect';
      }}
      if (haveValidToken()) {{ markConnected(); }}

      let tokenClient = null;
      function bootTokenClient() {{
        if (!window.google || !google.accounts || !google.accounts.oauth2) {{
          setTimeout(bootTokenClient, 200);
          return;
        }}
        tokenClient = google.accounts.oauth2.initTokenClient({{
          client_id: CLIENT_ID,
          scope: SCOPE,
          callback: (resp) => {{
            window.__ghkConnecting = false;
            if (resp.error) {{ setMsg('Login fail: ' + resp.error, true); return; }}
            accessToken = resp.access_token;
            tokenExpiry = Date.now() + (resp.expires_in * 1000) - 30000;
            window.sessionStorage.setItem('ghk_drive_token', accessToken);
            window.sessionStorage.setItem('ghk_drive_token_exp', String(tokenExpiry));
            markConnected();
            setMsg('Connect ho gaya ✔');
          }},
          error_callback: (err) => {{
            // Fires when the popup itself fails (blocked, closed early, or
            // the browser severed window.opener so GIS can't deliver the
            // token back — common on mobile Chrome / Cross-Origin-Opener-
            // Policy issues). Without this, the UI just silently stayed
            // "Not connected" with zero explanation.
            window.__ghkConnecting = false;
            const t = (err && err.type) || 'unknown';
            setMsg('Popup issue (' + t + '). Try: allow popups for this site, ' +
                   'or open in a normal (non-incognito) tab and retry.', true);
          }},
        }});
      }}
      if (!document.getElementById('ghk-gis-script')) {{
        const s = document.createElement('script');
        s.id = 'ghk-gis-script';
        s.src = 'https://accounts.google.com/gsi/client';
        s.async = true; s.defer = true;
        s.onload = bootTokenClient;
        document.head.appendChild(s);
      }} else {{
        bootTokenClient();
      }}

      connectBtn.addEventListener('click', function() {{
        setMsg('Connecting...');
        window.__ghkConnecting = true;
        setTimeout(function() {{
          if (window.__ghkConnecting) {{
            window.__ghkConnecting = false;
            setMsg(
              'Google se koi reply nahi aaya (15 sec). Ye aksar tab hota hai jab ' +
              'popup band ho gaya par browser wapas token deliver nahi kar paaya. ' +
              'Popups allow karke aur normal (non-incognito) tab mein dobara try karein.',
              true
            );
          }}
        }}, 15000);
        const tryRequest = () => {{
          if (tokenClient) {{
            tokenClient.requestAccessToken({{ prompt: haveValidToken() ? '' : 'consent' }});
          }} else {{
            setTimeout(tryRequest, 150);
          }}
        }};
        tryRequest();
      }});

      async function findFileId() {{
        const res = await fetch(
          'https://www.googleapis.com/drive/v3/files?spaces=appDataFolder&q=' +
          encodeURIComponent("name='" + FILE_NAME + "'") + '&fields=files(id)',
          {{ headers: {{ Authorization: 'Bearer ' + accessToken }} }}
        );
        const j = await res.json();
        return (j.files && j.files.length > 0) ? j.files[0].id : null;
      }}

      saveBtn.addEventListener('click', async function() {{
        if (!haveValidToken()) {{ setMsg('Pehle Connect karein.', true); return; }}
        setMsg('Saving to Drive...');
        try {{
          const fileId = await findFileId();
          const bodyJson = window.__ghkCurrentDataJson;
          let res;
          if (fileId) {{
            res = await fetch('https://www.googleapis.com/upload/drive/v3/files/' + fileId + '?uploadType=media', {{
              method: 'PATCH',
              headers: {{ Authorization: 'Bearer ' + accessToken, 'Content-Type': 'application/json' }},
              body: bodyJson,
            }});
          }} else {{
            const boundary = 'ghk_boundary_xyz';
            const metadata = JSON.stringify({{ name: FILE_NAME, parents: ['appDataFolder'] }});
            const body = '--' + boundary + '\\r\\nContent-Type: application/json; charset=UTF-8\\r\\n\\r\\n' +
              metadata + '\\r\\n--' + boundary + '\\r\\nContent-Type: application/json\\r\\n\\r\\n' +
              bodyJson + '\\r\\n--' + boundary + '--';
            res = await fetch('https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart', {{
              method: 'POST',
              headers: {{ Authorization: 'Bearer ' + accessToken,
                          'Content-Type': 'multipart/related; boundary=' + boundary }},
              body: body,
            }});
          }}
          if (res.ok) {{ setMsg('Google Drive par save ho gaya ✔'); }}
          else {{ const t = await res.text(); setMsg('Save fail: ' + res.status + ' ' + t.slice(0,150), true); }}
        }} catch (e) {{ setMsg('Error: ' + e.message, true); }}
      }});

      restoreBtn.addEventListener('click', async function() {{
        if (!haveValidToken()) {{ setMsg('Pehle Connect karein.', true); return; }}
        setMsg('Restoring from Drive...');
        try {{
          const fileId = await findFileId();
          if (!fileId) {{ setMsg('Drive par abhi koi saved file nahi mili.', true); return; }}
          const res = await fetch('https://www.googleapis.com/drive/v3/files/' + fileId + '?alt=media', {{
            headers: {{ Authorization: 'Bearer ' + accessToken }},
          }});
          if (!res.ok) {{ setMsg('Restore fail: ' + res.status, true); return; }}
          const text = await res.text();
          window.localStorage.setItem(RESTORE_KEY, text);
          setMsg('Data mil gaya — Streamlit page mein "Apply restored data" button dabayein ⬇️');
        }} catch (e) {{ setMsg('Error: ' + e.message, true); }}
      }});
    }})();
    """

    components.html(
        f"""
        <script>
        (function() {{
            try {{
                var s = window.parent.document.createElement('script');
                s.textContent = {json.dumps(inner_js)};
                window.parent.document.head.appendChild(s);
            }} catch (e) {{}}
        }})();
        </script>
        """,
        height=0,
    )

    if st.button("✅ Apply restored data from Drive"):
        st.session_state["_apply_drive_restore"] = True
        st.session_state["_drive_restore_tries"] = 0
        st.rerun()

    if st.session_state.get("_apply_drive_restore"):
        raw = streamlit_js_eval(
            js_expressions=f"localStorage.getItem({json.dumps(DRIVE_RESTORE_KEY)})",
            key=f"ghk_read_drive_restore_{st.session_state.get('_drive_restore_tries', 0)}",
            want_output=True,
        )
        if raw:
            try:
                st.session_state.data = json.loads(raw)
                st.session_state.dirty = True
                st.session_state["_apply_drive_restore"] = False
                st.success(
                    "Drive se data restore ho gaya! Ab neeche 'Save to browser storage' "
                    "bhi dabakar isko is browser mein bhi save kar lein."
                )
                st.rerun()
            except (json.JSONDecodeError, TypeError):
                st.error("Restored data padhne mein error aayi — dobara 'Restore from Drive' try karein.")
                st.session_state["_apply_drive_restore"] = False
        else:
            tries = st.session_state.get("_drive_restore_tries", 0)
            if tries < MAX_LOAD_RETRIES:
                st.session_state["_drive_restore_tries"] = tries + 1
                st.rerun()
            else:
                st.warning("Koi restored data nahi mila. Pehle neeche floating panel me 'Restore from Drive' dabayein.")
                st.session_state["_apply_drive_restore"] = False

    st.caption(
        "Pehli baar: floating panel me 'Connect Google Drive' dabayein aur apne Google "
        "account se login karein (sirf ek chhoti hidden app-folder access hoti hai, "
        "aapki asli Drive files nahi). Uske baad 'Save to Drive' se upload aur "
        "'Restore from Drive' se kisi bhi device par wapas laayein."
    )


def inject_pwa_manifest():
    """Make the app installable as a PWA on Android so that opening it from
    the home-screen icon launches with ZERO taps and NO browser chrome —
    this is the only real way to get true "opens already fullscreen"
    behavior, because requestFullscreen() can never fire without a genuine
    user gesture (see inject_auto_fullscreen() docstring below).

    Once the user does "Add to Home screen" from Chrome once, every
    future launch from that icon is chrome-less automatically.
    """
    icon_192 = "iVBORw0KGgoAAAANSUhEUgAAAMAAAADACAIAAADdvvtQAAACDElEQVR4nO3UsQ3CUAxAQYIYgT5smIzCiPRhCBaggack+tJd6cYunjzNy3aBf13PPoCxCYhEQCQCIhEQiYBIBEQiIBIBkQiIREAkAiIREImASAREIiASAZEIiERAJAIiERCJgEgERCIgEgGRCIhEQCQCIhEQye34la/n/filP3ms76/zcS/fjw9EIiASAZEIiERAJAIiERCJgEgERCIgEgGRCIhEQCQCIhEQiYBIBEQiIBIBkQiIREAkAiIREImASAREIiASAZEIiERAJAIiERCJgEgERCIgEgGRCIhEQCQCIhEQiYBIBEQiIBIBkQiIREAkAiIREImASAREIiASAZEIiERAJAIiERCJgEgERCIgEgGRCIhEQCQCIhEQiYBIBEQiIBIBkQiIREAkAiIREImASAREIiASAZEIiERAJAIiERCJgEgERCIgEgGRCIhEQCQCIhEQiYBIBEQiIBIBkQiIREAkAiIREImASAREIiASAZEIiERAJAIiERCJgEgERCIgEgGRCIhEQCQCIhEQiYBIBEQiIBIBkQiIREAkAiIREImASAREIiASAZEIiERAJAIiERCJgEgERCIgEgGRTPOynX0DA/OBSAREIiASAZEIiERAJAIiERCJgEgERCIgEgGRCIhEQCQCIhEQiYBIBEQiIBIBkQiIREAkAiIREImASAREIiASAZEIiOQDcdYKc0/De0MAAAAASUVORK5CYII="
    icon_512 = "iVBORw0KGgoAAAANSUhEUgAAAgAAAAIACAIAAAB7GkOtAAAHRUlEQVR4nO3XwQnCUBRFQSOW4D52mJRiie5jEbYgZPEIZ6aAz4W3OPxl3Y4bAD336QEAzBAAgCgBAIgSAIAoAQCIEgCAKAEAiBIAgCgBAIgSAIAoAQCIEgCAKAEAiBIAgCgBAIgSAIAoAQCIEgCAKAEAiBIAgCgBAIgSAIAoAQCIEgCAKAEAiBIAgCgBAIgSAIAoAQCIEgCAKAEAiBIAgCgBAIgSAIAoAQCIEgCAKAEAiBIAgCgBAIgSAIAoAQCIEgCAKAEAiBIAgCgBAIgSAIAoAQCIEgCAKAEAiBIAgCgBAIgSAIAoAQCIEgCAKAEAiBIAgCgBAIgSAIAoAQCIEgCAKAEAiBIAgCgBAIgSAIAoAQCIEgCAKAEAiBIAgCgBAIgSAIAoAQCIEgCAKAEAiBIAgCgBAIgSAIAoAQCIEgCAKAEAiBIAgKjH9IDr+byf0xP412v/nnzBuS/k/Llr/AAAogQAIEoAAKIEACBKAACiBAAgSgAAogQAIEoAAKIEACBKAACiBAAgSgAAogQAIEoAAKIEACBKAACiBAAgSgAAogQAIEoAAKIEACBKAACiBAAgSgAAogQAIEoAAKIEACBKAACiBAAgSgAAogQAIEoAAKIEACBKAACiBAAgSgAAogQAIEoAAKIEACBKAACiBAAgSgAAogQAIEoAAKIEACBKAACiBAAgSgAAogQAIEoAAKIEACBKAACiBAAgSgAAogQAIEoAAKIEACBKAACiBAAgSgAAogQAIEoAAKIEACBKAACiBAAgSgAAogQAIEoAAKIEACBKAACiBAAgSgAAogQAIEoAAKIEACBKAACiBAAgSgAAogQAIEoAAKIEACBKAACiBAAgSgAAogQAIEoAAKIEACBKAACiBAAgSgAAogQAIEoAAKIEACBKAACiBAAgSgAAogQAIEoAAKIEACBKAACiBAAgSgAAogQAIEoAAKIEACBKAACiBAAgSgAAogQAIEoAAKIEACBKAACiBAAgSgAAogQAIEoAAKIEACBKAACiBAAgSgAAogQAIEoAAKIEACBKAACiBAAgSgAAogQAIEoAAKIEACBKAACiBAAgSgAAogQAIEoAAKIEACBKAACiBAAgSgAAogQAIEoAAKIEACBKAACiBAAgSgAAogQAIEoAAKIEACBKAACiBAAgSgAAogQAIEoAAKIEACBKAACiBAAgSgAAogQAIEoAAKIEACBKAACiBAAgSgAAogQAIEoAAKIEACBKAACiBAAgSgAAogQAIEoAAKIEACBKAACiBAAgSgAAogQAIEoAAKIEACBKAACiBAAgSgAAogQAIEoAAKIEACBKAACiBAAgSgAAogQAIEoAAKIEACBKAACiBAAgSgAAogQAIEoAAKIEACBKAACiBAAgSgAAogQAIEoAAKIEACBKAACiBAAgSgAAogQAIEoAAKIEACBKAACiBAAgSgAAogQAIEoAAKIEACBKAACiBAAgSgAAogQAIEoAAKIEACBKAACiBAAgSgAAogQAIEoAAKIEACBKAACiBAAgSgAAogQAIEoAAKIEACBKAACiBAAgSgAAogQAIEoAAKIEACBKAACiBAAgSgAAogQAIEoAAKIEACBKAACiBAAgSgAAogQAIEoAAKIEACBKAACiBAAgSgAAogQAIEoAAKIEACBKAACiBAAgSgAAogQAIEoAAKIEACBKAACiBAAgSgAAogQAIEoAAKIEACBKAACiBAAgSgAAogQAIEoAAKIEACBKAACiBAAgSgAAogQAIEoAAKIEACBKAACiBAAgSgAAogQAIEoAAKIEACBKAACiBAAgSgAAogQAIEoAAKIEACBKAACiBAAgSgAAogQAIEoAAKIEACBKAACiBAAgSgAAogQAIEoAAKIEACBKAACiBAAgSgAAogQAIEoAAKIEACBKAACiBAAgSgAAogQAIEoAAKIEACBKAACiBAAgSgAAogQAIEoAAKIEACBKAACiBAAgSgAAogQAIEoAAKIEACBKAACiBAAgSgAAogQAIEoAAKIEACBKAACiBAAgSgAAogQAIEoAAKIEACBKAACiBAAgSgAAogQAIEoAAKIEACBKAACiBAAgSgAAopZ1O6Y3ADDADwAgSgAAogQAIEoAAKIEACBKAACiBAAgSgAAogQAIEoAAKIEACBKAACiBAAgSgAAogQAIEoAAKIEACBKAACiBAAgSgAAogQAIEoAAKIEACBKAACiBAAgSgAAogQAIEoAAKIEACBKAACiBAAgSgAAogQAIEoAAKIEACBKAACiBAAgSgAAogQAIEoAAKIEACBKAACiBAAgSgAAogQAIEoAAKIEACBKAACiBAAgSgAAogQAIEoAAKIEACBKAACiBAAgSgAAogQAIEoAAKIEACBKAACiBAAgSgAAogQAIEoAAKIEACBKAACiBAAgSgAAogQAIEoAAKIEACBKAACiBAAgSgAAogQAIEoAAKIEACBKAACiBAAgSgAAon6wewzzYj9AZAAAAABJRU5ErkJggg=="
    manifest = {
        "name": "Ghar Khata — Family Ledger",
        "short_name": "Ghar Khata",
        "start_url": ".",
        "display": "fullscreen",
        "background_color": "#1f6feb",
        "theme_color": "#1f6feb",
        "icons": [
            {"src": f"data:image/png;base64,{icon_192}", "sizes": "192x192", "type": "image/png"},
            {"src": f"data:image/png;base64,{icon_512}", "sizes": "512x512", "type": "image/png"},
        ],
    }
    manifest_json = json.dumps(manifest)
    js = """
    (function() {
        if (window.__ghk_manifest_hooked) { return; }
        window.__ghk_manifest_hooked = true;
        try {
            var head = document.head;
            var link = document.createElement('link');
            link.rel = 'manifest';
            link.href = 'data:application/manifest+json,' + encodeURIComponent(%s);
            head.appendChild(link);

            var meta1 = document.createElement('meta');
            meta1.name = 'mobile-web-app-capable';
            meta1.content = 'yes';
            head.appendChild(meta1);

            var meta2 = document.createElement('meta');
            meta2.name = 'theme-color';
            meta2.content = '#1f6feb';
            head.appendChild(meta2);
        } catch (e) {}
    })();
    """ % json.dumps(manifest_json)

    components.html(
        f"""
        <script>
        (function() {{
            try {{
                var s = window.parent.document.createElement('script');
                s.textContent = {json.dumps(js)};
                window.parent.document.head.appendChild(s);
            }} catch (e) {{}}
        }})();
        </script>
        """,
        height=0,
    )


def inject_auto_fullscreen():
    """No visible fullscreen button/label anywhere on screen.

    Important browser rule: a page cannot go fullscreen the instant it
    loads, with zero taps — requestFullscreen() only works when it's
    called synchronously inside a real user gesture (a click/tap), and
    only when that call happens in the actual top-level page's own
    script context. Every browser enforces this for security, so
    nothing (Streamlit or otherwise) can truly auto-fullscreen on load
    with no interaction at all.

    Why the previous version didn't work: streamlit_js_eval / components
    run inside a separate iframe. Even when that iframe's script called
    window.parent.document.documentElement.requestFullscreen(), browsers
    evaluate the user-gesture / permission check against the *calling
    frame*, not just the target document — so the request from inside a
    nested iframe gets silently rejected.

    Fix: instead of running the code inside the iframe, we build a real
    <script> tag and insert it directly into the parent page's own DOM
    (createElement + appendChild — this is the one way an injected
    script tag actually executes, unlike innerHTML). That script then
    runs as genuine top-level page code, so the first tap/click
    anywhere on the page triggers real fullscreen, no button needed.
    """
    js = """
    (function() {
        if (window.__ghk_fs_hooked) { return; }
        window.__ghk_fs_hooked = true;
        function goFullscreen() {
            try {
                var el = document.documentElement;
                if (el.requestFullscreen) { el.requestFullscreen().catch(function(){}); }
                else if (el.webkitRequestFullscreen) { el.webkitRequestFullscreen(); }
            } catch (e) {}
            document.removeEventListener('click', goFullscreen, true);
            document.removeEventListener('touchend', goFullscreen, true);
        }
        document.addEventListener('click', goFullscreen, true);
        document.addEventListener('touchend', goFullscreen, true);
    })();
    """
    components.html(
        f"""
        <script>
        (function() {{
            try {{
                var s = window.parent.document.createElement('script');
                s.textContent = {json.dumps(js)};
                window.parent.document.body.appendChild(s);
            }} catch (e) {{}}
        }})();
        </script>
        """,
        height=0,
    )


def hide_manage_app_badge():
    """Hide Streamlit Community Cloud's floating "Manage app" badge, which
    only the app's owner sees when signed in — visitors never see it.

    Same fix as above: the script now runs directly in the parent
    page's own DOM (not the component iframe), and keeps re-checking
    with a MutationObserver + interval, since Streamlit Cloud can
    (re)inject that badge after our first pass already ran.
    """
    js = """
    (function() {
        if (window.__ghk_badge_hooked) { return; }
        window.__ghk_badge_hooked = true;
        function hideBadge() {
            try {
                document.querySelectorAll('*').forEach(function(n) {
                    if (n.children.length === 0 && n.textContent &&
                        n.textContent.trim() === 'Manage app') {
                        var node = n;
                        for (var i = 0; i < 6 && node; i++) {
                            node = node.parentElement;
                            if (node && node.getBoundingClientRect().height < 140) {
                                node.style.display = 'none';
                            }
                        }
                    }
                });
            } catch (e) {}
        }
        hideBadge();
        try {
            var obs = new MutationObserver(hideBadge);
            obs.observe(document.body, {childList: true, subtree: true});
        } catch (e) {}
        setInterval(hideBadge, 1500);
    })();
    """
    components.html(
        f"""
        <script>
        (function() {{
            try {{
                var s = window.parent.document.createElement('script');
                s.textContent = {json.dumps(js)};
                window.parent.document.body.appendChild(s);
            }} catch (e) {{}}
        }})();
        </script>
        """,
        height=0,
    )


# --------------------------------------------------------------------------- #
# Ledger math helpers
# --------------------------------------------------------------------------- #

def month_total(account, key):
    """Total for `key`, using the auto-carried-forward previous balance."""
    months = account.get("months", {})
    month_obj = months.get(key, {"entries": []})
    prev = previous_balance_for(account, key)
    entries_sum = sum(float(e.get("amount", 0)) for e in month_obj.get("entries", []))
    return round(prev + entries_sum, 2)


def previous_balance_for(account, key):
    """Auto-carried-forward balance for `key`: the total of the most recent
    earlier month that has data. If there is no earlier month at all, this is
    the account's very first (seed) month, whose opening balance is manually
    entered.
    """
    months = account.get("months", {})
    prior_keys = [k for k in months if k < key]
    if prior_keys:
        return month_total(account, max(prior_keys))
    return float((months.get(key) or {}).get("previous_balance", 0) or 0)


def is_seed_month(account, key):
    """True if `key` has no earlier month with data — i.e. its opening
    balance must be entered manually rather than carried forward."""
    months = account.get("months", {})
    return not any(k < key for k in months)


def sorted_month_keys(months_dict):
    return sorted(months_dict.keys())


def latest_total(account):
    months = account.get("months", {})
    if not months:
        return 0.0
    return month_total(account, sorted_month_keys(months)[-1])


def shift_month_key(key, delta_months):
    y, m = map(int, key.split("-"))
    idx = y * 12 + (m - 1) + delta_months
    y2, m2 = divmod(idx, 12)
    return f"{y2:04d}-{m2 + 1:02d}"


def month_keys_for_account(account, years_ahead=10, years_behind=3):
    """All existing months, plus every month for `years_behind` years before
    and `years_ahead` years after, so the month picker covers past months
    (that already went by) as well as future ones — never needs a manual
    'add month' step."""
    months = account.get("months", {})
    existing = sorted_month_keys(months)
    today_key = f"{date.today().year:04d}-{date.today().month:02d}"
    anchor = min(existing[0], today_key) if existing else today_key
    generated = {
        shift_month_key(anchor, i)
        for i in range(-years_behind * 12, years_ahead * 12)
    }
    generated.update(existing)
    return sorted(generated)


def month_label(key):
    y, m = key.split("-")
    return f"{MONTH_NAMES[int(m) - 1]} {y}"


# --------------------------------------------------------------------------- #
# Styling
# --------------------------------------------------------------------------- #

def inject_css(text_scale=100):
    st.markdown(
        f"""
        <style>
        @import url('https://fonts.googleapis.com/css2?family=Zilla+Slab:wght@500;600;700&family=Inter:wght@400;500;600&family=IBM+Plex+Mono:wght@500;600&display=swap');

        html, body, [class*="css"]  {{ font-family: 'Inter', sans-serif; }}

        /* Hide Streamlit's own chrome for a more full-screen, app-like feel */
        #MainMenu {{ visibility: hidden; }}
        footer {{ visibility: hidden; }}
        header[data-testid="stHeader"] {{ height: 0; visibility: hidden; }}
        [data-testid="stToolbar"] {{ visibility: hidden; height: 0; }}
        [data-testid="stDecoration"] {{ display: none; }}
        [data-testid="stStatusWidget"] {{ visibility: hidden; }}
        .block-container {{ padding-top: 2.6rem; zoom: {text_scale}%; }}

        .stApp {{
            background: #F6F1E7;
        }}

        /* Top header bar (TradingView-style): dark, fixed to the very top
           of the viewport, "Family Savings" title on the left and a
           Setting icon+label button on the right. Everything else stays a
           normal scrollable page below it. */
        div[data-testid="stHorizontalBlock"]:has(div.top-nav-marker) {{
            position: fixed;
            top: 0;
            left: 0;
            right: 0;
            z-index: 9999;
            background: #131722;
            border: none;
            border-bottom: 1px solid #2A2E39;
            border-radius: 0;
            padding: 0.25rem 1rem;
            margin-bottom: 0;
            align-items: center !important;
            flex-wrap: nowrap !important;
        }}
        div[data-testid="stHorizontalBlock"]:has(div.top-nav-marker) [data-testid="stPopover"] > button {{
            border-radius: 50%;
            width: 26px;
            height: 26px;
            padding: 0;
            font-size: 0.85rem;
            border: 2px solid transparent;
            background: #1E222D;
        }}
        div[data-testid="stHorizontalBlock"]:has(div.top-nav-marker) [data-testid="stPopover"] > button:hover {{
            border-color: #7A1F2B;
            background: #2A2E39;
        }}
        .nav-icon {{
            display: flex;
            align-items: center;
            justify-content: center;
            width: 26px;
            height: 26px;
            border-radius: 50%;
            font-size: 0.85rem;
            background: #F3E3C3;
            border: 2px solid transparent;
            flex-shrink: 0;
        }}
        .nav-icon-active {{
            border-color: #7A1F2B;
            background: #7A1F2B;
        }}

        .ledger-month-header {{
            font-family: 'Zilla Slab', serif;
            font-weight: 700;
            font-size: 1.15rem;
            color: #7A1F2B;
            margin-top: 0.6rem;
            margin-bottom: 0.3rem;
            text-transform: uppercase;
            letter-spacing: 0.4px;
        }}

        /* Ledger paper card for each account */
        .ledger-card {{
            background: #FFFDF7;
            border: 1px solid #E4D8BC;
            border-left: 5px solid #7A1F2B;
            border-radius: 4px;
            padding: 1rem 1.3rem;
            margin-bottom: 1rem;
            position: relative;
        }}
        .ledger-card::before {{
            content: "";
            position: absolute;
            left: 46px;
            top: 0;
            bottom: 0;
            width: 1px;
            background: #E7B7B7;
        }}

        .total-figure {{
            font-family: 'IBM Plex Mono', monospace;
            font-weight: 600;
            color: #0F5132;
            font-size: 1.6rem;
        }}
        .total-label {{
            font-family: 'Inter', sans-serif;
            color: #6B5B3E;
            font-size: 0.8rem;
            text-transform: uppercase;
            letter-spacing: 0.6px;
        }}

        .addgone-label {{
            font-family: 'Inter', sans-serif;
            font-weight: 700;
            font-size: 0.9rem;
            margin-bottom: 0.3rem;
        }}
        .addgone-added {{ color: #0F5132; }}
        .addgone-gaya {{ color: #7A1F2B; }}
        .addgone-item {{
            font-family: 'Inter', sans-serif;
            font-size: 0.85rem;
            color: #4A3F2A;
            padding: 0.1rem 0;
        }}

        /* Compact one-line account rows (Name  —  ₹ Amount  ✏️) */
        .acct-row {{
            display: flex;
            justify-content: space-between;
            align-items: baseline;
            width: 100%;
            gap: 0.6rem;
        }}
        .acct-row-label {{
            font-family: 'Inter', sans-serif;
            color: #4A3F2A;
            font-size: 0.95rem;
            padding-top: 0.35rem;
            white-space: nowrap;
            overflow: hidden;
            text-overflow: ellipsis;
        }}
        .acct-row-figure {{
            font-family: 'IBM Plex Mono', monospace;
            font-weight: 600;
            color: #0F5132;
            font-size: 1.05rem;
            padding-top: 0.3rem;
            white-space: nowrap;
            flex-shrink: 0;
        }}
        .acct-row-total {{
            font-weight: 700;
        }}
        .acct-row-total .acct-row-label,
        .acct-row-total .acct-row-figure {{
            color: #7A1F2B;
        }}

        .stButton>button {{
            border-radius: 5px;
            border: 1px solid #7A1F2B;
            color: #7A1F2B;
            font-weight: 600;
        }}
        .stButton>button:hover {{
            background: #7A1F2B;
            color: #F3E3C3;
            border-color: #7A1F2B;
        }}
        div[data-testid="stMetricValue"] {{
            font-family: 'IBM Plex Mono', monospace;
            color: #0F5132;
        }}

        /* Icon-only popover trigger buttons (Add entry) — keep them small */
        [data-testid="stPopover"] button {{
            padding: 0.15rem 0.6rem;
            min-width: 0;
        }}

        /* Keep "account name+amount" and the add-entry icon on one line
           even on narrow phone screens — Streamlit stacks columns by
           default once they get too narrow, this overrides that just for
           these rows. The text column grows to fill the row; the icon
           column stays compact on the right. */
        div[data-testid="stHorizontalBlock"]:has(div.ov-row-marker) {{
            flex-wrap: nowrap !important;
            align-items: center !important;
            gap: 0.5rem !important;
            padding: 0.3rem 0;
            border-bottom: 1px solid #E7DFC9;
        }}
        div[data-testid="stHorizontalBlock"]:has(div.ov-row-marker) > div:nth-child(1) {{
            flex: 1 1 auto !important;
            min-width: 0 !important;
        }}
        div[data-testid="stHorizontalBlock"]:has(div.ov-row-marker) > div:nth-child(2),
        div[data-testid="stHorizontalBlock"]:has(div.ov-row-marker) > div:nth-child(3) {{
            width: auto !important;
            min-width: 0 !important;
            flex: 0 0 auto !important;
        }}
        div[data-testid="stHorizontalBlock"]:has(div.ov-row-marker) [data-testid="stButton"] > button {{
            padding: 0.15rem 0.6rem;
            min-width: 0;
            border-radius: 6px;
        }}
        </style>

        """,
        unsafe_allow_html=True,
    )


# --------------------------------------------------------------------------- #
# UI sections
# --------------------------------------------------------------------------- #

def render_top_nav(data):
    """TradingView-style dark header bar, fixed at the very top of the
    viewport, half-height. Just two icons live here: the money-bag icon on
    the left, and a settings gear icon on the right that opens the
    existing settings popover (text size, chart tools).
    """
    icon_col, spacer_col, settings_col = st.columns([1, 6, 1])
    with icon_col:
        st.markdown(
            '<div class="top-nav-marker"></div>'
            '<div class="nav-icon nav-icon-active" title="Family Savings">💰</div>',
            unsafe_allow_html=True,
        )
    with settings_col:
        with st.popover("⚙️", help="Settings"):
            st.caption("Text size")
            scale = st.slider(
                "Sabhi text aur entries ka size",
                min_value=80,
                max_value=160,
                step=10,
                value=data["settings"].get("text_scale", 100),
                format="%d%%",
                key="text_scale_slider",
            )
            if scale != data["settings"].get("text_scale", 100):
                data["settings"]["text_scale"] = scale
                st.session_state["_pending_save_json"] = json.dumps(data)
                st.rerun()

            st.divider()
            st.caption("Chart tools")
            show_tools = st.checkbox(
                "📷 Zoom & download icons on the chart",
                value=data["settings"].get("show_chart_tools", False),
                key="show_chart_tools_toggle",
                help="Shows Plotly's zoom/pan/download toolbar on the comparison chart.",
            )
            if show_tools != data["settings"].get("show_chart_tools", False):
                data["settings"]["show_chart_tools"] = show_tools
                st.session_state["_pending_save_json"] = json.dumps(data)
                st.rerun()


def quick_add_entry(account):
    """Small icon-only popover, to add an entry to an account's most recent
    month without opening that account's tab. Kept icon-only (no label) so
    it takes minimal space next to the account name."""
    months = account["months"]
    existing_keys = sorted_month_keys(months)
    target_key = existing_keys[-1] if existing_keys else (
        f"{date.today().year:04d}-{date.today().month:02d}"
    )
    with st.popover("✏️", help="Add entry"):
        st.caption(f"Adding to **{month_label(target_key)}**")
        desc = st.text_input("Description", key=f"qk_desc_{account['id']}")
        amt = st.number_input(
            "Amount", step=1.0, format="%.2f", key=f"qk_amt_{account['id']}"
        )
        if st.button("Add", key=f"qk_btn_{account['id']}"):
            if desc.strip():
                months.setdefault(target_key, {"entries": []})
                months[target_key].setdefault("entries", []).append(
                    {"label": desc.strip(), "amount": float(amt)}
                )
                st.session_state.dirty = True
                st.session_state[f"qk_desc_{account['id']}"] = ""
                st.session_state[f"qk_amt_{account['id']}"] = 0.0
                st.rerun()
            else:
                st.warning("Description likhein.")


def open_account_detail(account):
    """Opens a popup with the full month-wise ledger (previous balance,
    entries editable table with add/edit/delete rows, running total, and
    month-by-month history) for a single account — same underlying data
    as the account's own tab further down the page, so any change made
    here immediately adds/subtracts from that account's saving total.
    """

    @st.dialog(f"📋 {account['name']}", width="large")
    def _dialog():
        render_account(account, key_prefix="dialog_")

    _dialog()


def render_overview(data):
    accounts = data["accounts"]
    grand_total = 0.0

    for acc in accounts:
        t = latest_total(acc)
        grand_total += t
        text_col, edit_col, detail_col = st.columns([7, 1, 1], gap="small")
        with text_col:
            st.markdown(
                f'<div class="ov-row-marker"></div>'
                f'<div class="acct-row">'
                f'<span class="acct-row-label">{acc["name"]}</span>'
                f'<span class="acct-row-figure">₹ {t:,.2f}</span>'
                f'</div>',
                unsafe_allow_html=True,
            )
        with edit_col:
            quick_add_entry(acc)
        with detail_col:
            if st.button("📋", key=f"detail_btn_{acc['id']}", help="Month-wise details"):
                open_account_detail(acc)

    st.markdown(
        f'<div class="acct-row acct-row-total">'
        f'<span class="acct-row-label">Family Total</span>'
        f'<span class="acct-row-figure">₹ {grand_total:,.2f}</span>'
        f'</div>',
        unsafe_allow_html=True,
    )

    # Comparison chart across accounts' latest totals
    fig = go.Figure(
        go.Bar(
            x=[a["name"] for a in accounts],
            y=[latest_total(a) for a in accounts],
            marker_color=["#7A1F2B", "#D4A017", "#0F5132", "#3B5998"][: len(accounts)],
            text=[f"₹{latest_total(a):,.0f}" for a in accounts],
            textposition="outside",
        )
    )
    fig.update_layout(
        height=260,
        margin=dict(l=10, r=10, t=10, b=10),
        paper_bgcolor="rgba(0,0,0,0)",
        plot_bgcolor="rgba(0,0,0,0)",
        yaxis=dict(showgrid=True, gridcolor="#E7DFC9"),
        font=dict(family="Inter", color="#4A3F2A"),
    )
    st.plotly_chart(
        fig,
        use_container_width=True,
        config={"displayModeBar": data["settings"].get("show_chart_tools", False)},
    )


def render_account(account, key_prefix=""):
    months = account["months"]

    new_name = st.text_input(
        "Account name", value=account["name"], key=f"{key_prefix}name_{account['id']}"
    )
    if new_name != account["name"]:
        account["name"] = new_name
        st.session_state.dirty = True

    existing_keys = sorted_month_keys(months)
    if not existing_keys:
        today_key = f"{date.today().year:04d}-{date.today().month:02d}"
        months[today_key] = {"entries": [], "previous_balance": 0}
        existing_keys = [today_key]
        st.session_state.dirty = True

    # Opening balance — only needed once, for the account's very first month.
    seed_key = existing_keys[0]
    current_pb = float((months.get(seed_key) or {}).get("previous_balance", 0) or 0)
    pb = st.number_input(
        f"Opening balance ({month_label(seed_key)} — account ka pehla month)",
        value=current_pb,
        step=1.0,
        format="%.2f",
        key=f"{key_prefix}pb_{account['id']}_{seed_key}",
    )
    if pb != current_pb:
        months.setdefault(seed_key, {"entries": []})
        months[seed_key]["previous_balance"] = pb
        st.session_state.dirty = True

    st.caption(
        "💡 Saare months ki entries yahan ek hi sheet mein hain. Naya row add karke "
        "'Month' column se koi bhi mahina (aane wale mahine bhi) chun sakte hain — "
        "amount **positive** likhein toh 'Added', **negative** (jaise -500) likhein toh 'Gaya'."
    )
    st.markdown('<div class="ledger-card">', unsafe_allow_html=True)

    # Month options: existing months + a couple of years ahead, so you never
    # have to press a separate "add month" button — just pick it in the sheet.
    all_keys = month_keys_for_account(account, years_ahead=2)
    label_to_key = {month_label(k): k for k in all_keys}
    key_to_label = {k: v for v, k in label_to_key.items()}

    rows = []
    for mkey in existing_keys:
        for e in (months.get(mkey) or {}).get("entries", []):
            rows.append(
                {
                    "Month": key_to_label[mkey],
                    "Description": str(e["label"]),
                    "Amount": float(e["amount"]),
                }
            )

    if rows:
        df = pd.DataFrame(rows)
    else:
        df = pd.DataFrame(
            {
                "Month": pd.Series(dtype="str"),
                "Description": pd.Series(dtype="str"),
                "Amount": pd.Series(dtype="float"),
            }
        )

    edited = st.data_editor(
        df,
        num_rows="dynamic",
        use_container_width=True,
        key=f"{key_prefix}editor_all_{account['id']}",
        column_config={
            "Month": st.column_config.SelectboxColumn(
                options=list(label_to_key.keys()), required=True
            ),
            "Description": st.column_config.TextColumn(required=True),
            "Amount": st.column_config.NumberColumn(format="%.2f", required=True),
        },
    )

    new_entries_by_key = {mkey: [] for mkey in existing_keys}
    for _, row in edited.iterrows():
        month_lbl = row.get("Month")
        if month_lbl not in label_to_key:
            continue
        if pd.isna(row.get("Description")) or str(row.get("Description")).strip() == "":
            continue
        if pd.isna(row.get("Amount")):
            continue
        mkey = label_to_key[month_lbl]
        new_entries_by_key.setdefault(mkey, [])
        new_entries_by_key[mkey].append(
            {"label": str(row["Description"]), "amount": float(row["Amount"])}
        )
        if mkey not in existing_keys:
            existing_keys.append(mkey)
            existing_keys.sort()

    changed = False
    for mkey, new_entries in new_entries_by_key.items():
        old_entries = (months.get(mkey) or {}).get("entries", [])
        if new_entries != old_entries:
            months.setdefault(mkey, {"entries": []})
            months[mkey]["entries"] = new_entries
            changed = True
    if changed:
        st.session_state.dirty = True

    st.markdown("</div>", unsafe_allow_html=True)

    st.markdown('<div class="total-label">Month-wise Totals</div>', unsafe_allow_html=True)
    summary_rows = []
    for mkey in existing_keys:
        entries = new_entries_by_key.get(mkey, [])
        added = sum(e["amount"] for e in entries if e["amount"] > 0)
        gaya = sum(-e["amount"] for e in entries if e["amount"] < 0)
        prev = previous_balance_for(account, mkey)
        total = month_total(account, mkey)
        summary_rows.append(
            {
                "Month": month_label(mkey),
                "Previous Balance": prev,
                "Added": added,
                "Gaya": gaya,
                "Total": total,
            }
        )
    st.dataframe(
        pd.DataFrame(summary_rows),
        use_container_width=True,
        hide_index=True,
        column_config={
            "Previous Balance": st.column_config.NumberColumn(format="₹ %.2f"),
            "Added": st.column_config.NumberColumn(format="₹ %.2f"),
            "Gaya": st.column_config.NumberColumn(format="₹ %.2f"),
            "Total": st.column_config.NumberColumn(format="₹ %.2f"),
        },
    )

    st.divider()
    current_balance = latest_total(account)
    st.markdown(
        f'<div class="total-label">Current Balance</div>'
        f'<div class="total-figure">₹ {current_balance:,.2f}</div>',
        unsafe_allow_html=True,
    )


def render_manage_accounts(data):
    with st.expander("⚙️ Manage accounts (add / remove)"):
        st.caption("Add a new family member's account, or remove one you no longer need.")
        new_acc_name = st.text_input("New account name", key="new_acc_name_input")
        if st.button("Add account"):
            if new_acc_name.strip():
                data["accounts"].append(
                    {
                        "id": f"acc_{uuid.uuid4().hex[:8]}",
                        "name": new_acc_name.strip(),
                        "months": {},
                    }
                )
                st.session_state.dirty = True
                st.rerun()
            else:
                st.warning("Please type a name first.")

        if data["accounts"]:
            to_remove = st.selectbox(
                "Remove an account",
                options=["-- select --"] + [a["name"] for a in data["accounts"]],
                key="remove_acc_select",
            )
            if to_remove != "-- select --" and st.button("Remove selected account", type="secondary"):
                data["accounts"] = [a for a in data["accounts"] if a["name"] != to_remove]
                st.session_state.dirty = True
                st.rerun()


# --------------------------------------------------------------------------- #
# Main
# --------------------------------------------------------------------------- #

def main():
    st.set_page_config(page_title="Ghar Khata — Family Ledger", page_icon="📒", layout="wide")
    load_data()

    if "data" not in st.session_state:
        inject_css()
        inject_pwa_manifest()
        st.info("Loading your saved data from this browser...")
        st.stop()

    data = st.session_state.data
    data.setdefault("settings", {"text_scale": 100, "show_chart_tools": False})
    data["settings"].setdefault("show_chart_tools", False)
    if "dirty" not in st.session_state:
        st.session_state.dirty = False

    inject_css(data["settings"].get("text_scale", 100))

    render_top_nav(data)

    inject_pwa_manifest()
    inject_auto_fullscreen()

    render_overview(data)
    st.divider()

    if not data["accounts"]:
        st.warning("No accounts yet. Add one below.")
    else:
        tabs = st.tabs([a["name"] for a in data["accounts"]])
        for tab, acc in zip(tabs, data["accounts"]):
            with tab:
                render_account(acc)

    st.divider()
    render_manage_accounts(data)

    st.divider()
    render_drive_sync(data)

    st.divider()
    save_col, status_col = st.columns([1, 3])
    with save_col:
        if st.button("💾 Save to browser storage", type="primary"):
            save_data()
    with status_col:
        if st.session_state.dirty:
            st.warning("You have unsaved changes — click Save to keep them in this browser.")
        else:
            st.caption("Everything is saved in this browser's local storage.")

    st.caption(
        "Data lives only in this browser (localStorage) — it is not uploaded anywhere. "
        "Using a different browser or device, or clearing site data, will not show this data."
    )

    # Always invoked, at the same point in the script every rerun — this is
    # required for the JS bridge components to behave reliably.
    run_pending_save()
    hide_manage_app_badge()


if __name__ == "__main__":
    main()
