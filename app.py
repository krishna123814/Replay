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


def render_fullscreen_button():
    """Render a real HTML button whose onclick calls the Fullscreen API
    directly, in the browser, with no Streamlit rerun in between.

    This matters because requestFullscreen() only works when called
    synchronously inside a genuine user-gesture handler (a real click).
    Routing the click through st.button() -> session_state flag ->
    st.rerun() -> streamlit_js_eval loses that gesture (there's a server
    round-trip in between), so the browser silently rejects the request.
    It also previously called requestFullscreen() on the *component
    iframe's own* document instead of the actual app page, so even when
    the gesture wasn't lost, the wrong element was being fullscreened.

    window.parent.document targets the real top-level app page (this
    button's iframe is a direct child of it), and the click happens
    entirely client-side, so the gesture requirement is satisfied.
    """
    components.html(
        """
        <button id="fs-btn" style="
            width:100%; padding:0.5rem 0; border-radius:0.5rem;
            border:1px solid rgba(49,51,63,0.2); background:#fff;
            font-size:1rem; cursor:pointer;">
            ⛶ Fullscreen
        </button>
        <script>
        document.getElementById('fs-btn').addEventListener('click', function () {
            try {
                var el = window.parent.document.documentElement;
                if (el.requestFullscreen) { el.requestFullscreen(); }
                else if (el.webkitRequestFullscreen) { el.webkitRequestFullscreen(); }
            } catch (e) {}
        });
        </script>
        """,
        height=48,
    )


def hide_manage_app_badge():
    """Hide Streamlit Community Cloud's floating "Manage app" badge, which
    only the app's owner sees when signed in — visitors never see it.
    Runs unconditionally (no user gesture needed for this one).
    """
    js_code = """
    (function() {
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
        return 'badge-done';
    })();
    """
    streamlit_js_eval(js_expressions=js_code, key="hide_manage_app_badge", want_output=False)


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


def month_keys_for_account(account, years_ahead=10):
    """All existing months, plus every month for the next `years_ahead`
    years, so the month picker never needs a manual 'add month' step."""
    months = account.get("months", {})
    existing = sorted_month_keys(months)
    start = existing[0] if existing else f"{date.today().year:04d}-{date.today().month:02d}"
    generated = {shift_month_key(start, i) for i in range(years_ahead * 12)}
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
        .block-container {{ padding-top: 1.2rem; zoom: {text_scale}%; }}

        .stApp {{
            background: #F6F1E7;
        }}

        /* Top icon nav bar (Family Saving, Expenses, settings gear) */
        div:has(> div.top-nav-marker) ~ div[data-testid="stHorizontalBlock"] {{
            background: #FFFDF7;
            border: 1px solid #E4D8BC;
            border-radius: 10px;
            padding: 0.45rem 0.7rem;
            margin-bottom: 0.9rem;
            align-items: center !important;
            flex-wrap: nowrap !important;
        }}
        div:has(> div.top-nav-marker) ~ div[data-testid="stHorizontalBlock"] [data-testid="stPopover"] > button {{
            border-radius: 50%;
            width: 42px;
            height: 42px;
            padding: 0;
            font-size: 1.05rem;
            border: 2px solid transparent;
            background: #F3E3C3;
        }}
        .nav-icon {{
            display: flex;
            align-items: center;
            justify-content: center;
            width: 42px;
            height: 42px;
            border-radius: 50%;
            font-size: 1.25rem;
            background: #F3E3C3;
            border: 2px solid transparent;
            flex-shrink: 0;
        }}
        .nav-icon-active {{
            border-color: #7A1F2B;
            background: #7A1F2B;
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

        /* Compact one-line account rows (Name  —  ₹ Amount  ✏️) */
        .acct-row-label {{
            font-family: 'Inter', sans-serif;
            color: #4A3F2A;
            font-size: 0.95rem;
            padding-top: 0.35rem;
        }}
        .acct-row-figure {{
            font-family: 'IBM Plex Mono', monospace;
            font-weight: 600;
            color: #0F5132;
            font-size: 1.05rem;
            padding-top: 0.3rem;
        }}
        .acct-row-total {{
            font-weight: 700;
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

        /* Keep "account name + add-entry icon" on one line even on narrow
           phone screens — Streamlit stacks columns by default once they
           get too narrow, this overrides that just for these rows. */
        div:has(> div.ov-row-marker) ~ div[data-testid="stHorizontalBlock"] {{
            flex-wrap: nowrap !important;
            align-items: center !important;
            gap: 0.5rem !important;
            padding: 0.3rem 0;
            border-bottom: 1px solid #E7DFC9;
        }}
        div:has(> div.ov-row-marker) ~ div[data-testid="stHorizontalBlock"] > div {{
            width: auto !important;
            min-width: 0 !important;
            flex: initial !important;
        }}
        div:has(> div.ov-row-marker) ~ div[data-testid="stHorizontalBlock"] [data-testid="stCaptionContainer"] {{
            white-space: nowrap;
        }}
        </style>
        """,
        unsafe_allow_html=True,
    )


# --------------------------------------------------------------------------- #
# UI sections
# --------------------------------------------------------------------------- #

def render_top_nav(data):
    """Horizontal icon-only nav bar at the very top. 'Family Saving' is the
    current, active screen. 'Expenses' is just a placeholder icon for now —
    no screen or functionality behind it yet. A settings gear sits in the
    right corner and holds the text-size control.
    """
    st.markdown('<div class="top-nav-marker"></div>', unsafe_allow_html=True)
    icon_col, exp_col, spacer_col, settings_col = st.columns([1, 1, 6, 1])
    with icon_col:
        st.markdown(
            '<div class="nav-icon nav-icon-active" title="Family Saving">💰</div>',
            unsafe_allow_html=True,
        )
    with exp_col:
        st.markdown(
            '<div class="nav-icon" title="Expenses">🧾</div>',
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


def render_overview(data):
    accounts = data["accounts"]
    grand_total = 0.0

    for acc in accounts:
        t = latest_total(acc)
        grand_total += t
        st.markdown('<div class="ov-row-marker"></div>', unsafe_allow_html=True)
        label_col, val_col, edit_col = st.columns([3, 3, 1], gap="small")
        with label_col:
            st.markdown(
                f'<div class="acct-row-label">{acc["name"]}</div>',
                unsafe_allow_html=True,
            )
        with val_col:
            st.markdown(
                f'<div class="acct-row-figure">₹ {t:,.2f}</div>',
                unsafe_allow_html=True,
            )
        with edit_col:
            quick_add_entry(acc)

    st.markdown('<div class="ov-row-marker"></div>', unsafe_allow_html=True)
    total_label_col, total_val_col, _ = st.columns([3, 3, 1], gap="small")
    with total_label_col:
        st.markdown(
            '<div class="acct-row-label acct-row-total">Family Total</div>',
            unsafe_allow_html=True,
        )
    with total_val_col:
        st.markdown(
            f'<div class="acct-row-figure acct-row-total">₹ {grand_total:,.2f}</div>',
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
    st.plotly_chart(fig, use_container_width=True)


def render_account(account):
    months = account["months"]

    new_name = st.text_input(
        "Account name", value=account["name"], key=f"name_{account['id']}"
    )
    if new_name != account["name"]:
        account["name"] = new_name
        st.session_state.dirty = True

    all_keys = month_keys_for_account(account)
    existing_keys = sorted_month_keys(months)
    state_key = f"selected_month_{account['id']}"

    if state_key in st.session_state and st.session_state[state_key] in all_keys:
        default_idx = all_keys.index(st.session_state[state_key])
    elif existing_keys:
        default_idx = all_keys.index(existing_keys[-1])
    else:
        today_key = f"{date.today().year:04d}-{date.today().month:02d}"
        default_idx = all_keys.index(today_key) if today_key in all_keys else 0

    selected = st.selectbox(
        "Month",
        options=all_keys,
        format_func=month_label,
        index=default_idx,
        key=f"select_{account['id']}",
    )
    st.session_state[state_key] = selected

    st.markdown('<div class="ledger-card">', unsafe_allow_html=True)

    if is_seed_month(account, selected):
        current_pb = float((months.get(selected) or {}).get("previous_balance", 0) or 0)
        pb = st.number_input(
            "Opening balance (this is the account's first month)",
            value=current_pb,
            step=1.0,
            format="%.2f",
            key=f"pb_{account['id']}_{selected}",
        )
        if pb != current_pb:
            months.setdefault(selected, {"entries": []})
            months[selected]["previous_balance"] = pb
            st.session_state.dirty = True
    else:
        computed_pb = previous_balance_for(account, selected)
        st.number_input(
            "Previous balance (auto carried forward)",
            value=computed_pb,
            format="%.2f",
            disabled=True,
            key=f"pb_ro_{account['id']}_{selected}",
        )

    st.caption("Entries for this month (salary, pension, petrol, interest, etc.)")
    entries = (months.get(selected) or {}).get("entries", [])
    if entries:
        df = pd.DataFrame(entries)
        df["label"] = df["label"].astype(str)
        df["amount"] = df["amount"].astype(float)
    else:
        df = pd.DataFrame({"label": pd.Series(dtype="str"), "amount": pd.Series(dtype="float")})
    df = df.rename(columns={"label": "Description", "amount": "Amount"})

    edited = st.data_editor(
        df,
        num_rows="dynamic",
        use_container_width=True,
        key=f"editor_{account['id']}_{selected}",
        column_config={
            "Description": st.column_config.TextColumn(required=True),
            "Amount": st.column_config.NumberColumn(format="%.2f", required=True),
        },
    )

    new_entries = [
        {"label": row["Description"], "amount": float(row["Amount"])}
        for _, row in edited.iterrows()
        if pd.notna(row.get("Description")) and str(row.get("Description")).strip() != ""
        and pd.notna(row.get("Amount"))
    ]
    if new_entries != entries:
        months.setdefault(selected, {"entries": []})
        months[selected]["entries"] = new_entries
        st.session_state.dirty = True

    total = month_total(account, selected)
    st.markdown(
        f'<div class="total-label">Total ({month_label(selected)})</div>'
        f'<div class="total-figure">₹ {total:,.2f}</div>',
        unsafe_allow_html=True,
    )
    st.markdown("</div>", unsafe_allow_html=True)

    # History table for this account (only months that actually have data)
    with st.expander("Month-by-month history"):
        hist_rows = [
            {
                "Month": month_label(k),
                "Previous balance": previous_balance_for(account, k),
                "Total": month_total(account, k),
            }
            for k in sorted_month_keys(months)
        ]
        st.dataframe(pd.DataFrame(hist_rows), use_container_width=True, hide_index=True)


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
        st.info("Loading your saved data from this browser...")
        st.stop()

    data = st.session_state.data
    data.setdefault("settings", {"text_scale": 100})
    if "dirty" not in st.session_state:
        st.session_state.dirty = False

    inject_css(data["settings"].get("text_scale", 100))

    render_top_nav(data)

    render_fullscreen_button()

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
