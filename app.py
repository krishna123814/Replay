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


# --------------------------------------------------------------------------- #
# Ledger math helpers
# --------------------------------------------------------------------------- #

def month_total(month_obj):
    return round(
        float(month_obj.get("previous_balance", 0))
        + sum(float(e.get("amount", 0)) for e in month_obj.get("entries", [])),
        2,
    )


def sorted_month_keys(months_dict):
    return sorted(months_dict.keys())


def latest_total(account):
    months = account.get("months", {})
    if not months:
        return 0.0
    last_key = sorted_month_keys(months)[-1]
    return month_total(months[last_key])


def add_next_month(account):
    months = account["months"]
    keys = sorted_month_keys(months)
    if keys:
        last_key = keys[-1]
        y, m = map(int, last_key.split("-"))
        m += 1
        if m > 12:
            m = 1
            y += 1
    else:
        today = date.today()
        y, m = today.year, today.month
    new_key = f"{y:04d}-{m:02d}"
    if new_key not in months:
        months[new_key] = {
            "previous_balance": latest_total(account) if keys else 0.0,
            "entries": [],
        }
    return new_key


def month_label(key):
    y, m = key.split("-")
    return f"{MONTH_NAMES[int(m) - 1]} {y}"


# --------------------------------------------------------------------------- #
# Styling
# --------------------------------------------------------------------------- #

def inject_css():
    st.markdown(
        """
        <style>
        @import url('https://fonts.googleapis.com/css2?family=Zilla+Slab:wght@500;600;700&family=Inter:wght@400;500;600&family=IBM+Plex+Mono:wght@500;600&display=swap');

        html, body, [class*="css"]  { font-family: 'Inter', sans-serif; }

        .stApp {
            background: #F6F1E7;
        }

        /* Ledger-style header */
        .khata-header {
            background: #7A1F2B;
            color: #F3E3C3;
            padding: 1.4rem 1.6rem;
            border-radius: 6px;
            margin-bottom: 1.2rem;
            border-left: 8px solid #D4A017;
            box-shadow: 0 3px 10px rgba(0,0,0,0.15);
        }
        .khata-header h1 {
            font-family: 'Zilla Slab', serif;
            font-weight: 700;
            font-size: 1.9rem;
            margin: 0;
            letter-spacing: 0.3px;
        }
        .khata-header p {
            margin: 0.25rem 0 0 0;
            color: #E9D9AF;
            font-size: 0.92rem;
        }

        /* Ledger paper card for each account */
        .ledger-card {
            background: #FFFDF7;
            border: 1px solid #E4D8BC;
            border-left: 5px solid #7A1F2B;
            border-radius: 4px;
            padding: 1rem 1.3rem;
            margin-bottom: 1rem;
            position: relative;
        }
        .ledger-card::before {
            content: "";
            position: absolute;
            left: 46px;
            top: 0;
            bottom: 0;
            width: 1px;
            background: #E7B7B7;
        }

        .total-figure {
            font-family: 'IBM Plex Mono', monospace;
            font-weight: 600;
            color: #0F5132;
            font-size: 1.6rem;
        }
        .total-label {
            font-family: 'Inter', sans-serif;
            color: #6B5B3E;
            font-size: 0.8rem;
            text-transform: uppercase;
            letter-spacing: 0.6px;
        }

        .stButton>button {
            border-radius: 5px;
            border: 1px solid #7A1F2B;
            color: #7A1F2B;
            font-weight: 600;
        }
        .stButton>button:hover {
            background: #7A1F2B;
            color: #F3E3C3;
            border-color: #7A1F2B;
        }
        div[data-testid="stMetricValue"] {
            font-family: 'IBM Plex Mono', monospace;
            color: #0F5132;
        }
        </style>
        """,
        unsafe_allow_html=True,
    )


# --------------------------------------------------------------------------- #
# UI sections
# --------------------------------------------------------------------------- #

def render_overview(data):
    accounts = data["accounts"]
    cols = st.columns(len(accounts) + 1)
    grand_total = 0.0
    for col, acc in zip(cols, accounts):
        t = latest_total(acc)
        grand_total += t
        with col:
            st.metric(acc["name"], f"₹ {t:,.2f}")
    with cols[-1]:
        st.metric("Family Total", f"₹ {grand_total:,.2f}")

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

    top_l, top_r = st.columns([3, 1])
    with top_l:
        new_name = st.text_input(
            "Account name", value=account["name"], key=f"name_{account['id']}"
        )
        if new_name != account["name"]:
            account["name"] = new_name
            st.session_state.dirty = True
    with top_r:
        st.write("")
        st.write("")
        if st.button("➕ Add next month", key=f"addmonth_{account['id']}"):
            new_key = add_next_month(account)
            st.session_state.dirty = True
            st.session_state[f"selected_month_{account['id']}"] = new_key
            st.rerun()

    if not months:
        st.info("No months yet — click 'Add next month' to start this account.")
        return

    keys = sorted_month_keys(months)
    default_idx = len(keys) - 1
    state_key = f"selected_month_{account['id']}"
    if state_key in st.session_state and st.session_state[state_key] in keys:
        default_idx = keys.index(st.session_state[state_key])

    selected = st.selectbox(
        "Month",
        options=keys,
        format_func=month_label,
        index=default_idx,
        key=f"select_{account['id']}",
    )
    st.session_state[state_key] = selected

    month_obj = months[selected]

    st.markdown('<div class="ledger-card">', unsafe_allow_html=True)

    pb = st.number_input(
        "Previous balance (carried forward)",
        value=float(month_obj.get("previous_balance", 0)),
        step=1.0,
        format="%.2f",
        key=f"pb_{account['id']}_{selected}",
    )
    if pb != month_obj.get("previous_balance"):
        month_obj["previous_balance"] = pb
        st.session_state.dirty = True

    st.caption("Entries for this month (salary, pension, petrol, interest, etc.)")
    df = pd.DataFrame(month_obj.get("entries", []))
    if df.empty:
        df = pd.DataFrame({"label": [], "amount": []})
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
    if new_entries != month_obj.get("entries", []):
        month_obj["entries"] = new_entries
        st.session_state.dirty = True

    total = month_total(month_obj)
    st.markdown(
        f'<div class="total-label">Total ({month_label(selected)})</div>'
        f'<div class="total-figure">₹ {total:,.2f}</div>',
        unsafe_allow_html=True,
    )
    st.markdown("</div>", unsafe_allow_html=True)

    # History table for this account
    with st.expander("Month-by-month history"):
        hist_rows = [
            {
                "Month": month_label(k),
                "Previous balance": months[k].get("previous_balance", 0),
                "Total": month_total(months[k]),
            }
            for k in keys
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
    inject_css()
    load_data()

    if "data" not in st.session_state:
        st.info("Loading your saved data from this browser...")
        st.stop()

    data = st.session_state.data
    if "dirty" not in st.session_state:
        st.session_state.dirty = False

    st.markdown(
        """
        <div class="khata-header">
            <h1>📒 Ghar Khata — Family Ledger</h1>
            <p>Savings &amp; pension accounts for the whole family, saved right here in this browser.</p>
        </div>
        """,
        unsafe_allow_html=True,
    )

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
    # required for the JS bridge component to behave reliably.
    run_pending_save()


if __name__ == "__main__":
    main()
