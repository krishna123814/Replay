"""
================================================================
 REPLAY APP — BankNifty + BTCUSDT Historical Stack Viewer
 Streamlit version (no local server needed)
 Data source: .pkl.gz files pulled directly from your GitHub repo
================================================================
Repo: krishna123814/Replay (branch: main)
Files expected in repo root:
    banknifty_all_tf_pkl.gz
    btc_all_tf_pkl.gz

Run locally:
    pip install streamlit pandas plotly requests
    streamlit run app.py

Deploy:
    Push this file (app.py) + the two .gz files to your GitHub repo,
    then deploy on https://share.streamlit.io pointing at app.py
"""

import gzip
import pickle
import io
import requests
import pandas as pd
import streamlit as st
import plotly.graph_objects as go
from plotly.subplots import make_subplots

# ─────────────────────────────────────────────────────────────
# CONFIG
# ─────────────────────────────────────────────────────────────
GH_BASE = "https://raw.githubusercontent.com/krishna123814/Replay/main/"

ASSETS = {
    "bn": {
        "label": "📊 BANKNIFTY",
        "file": "banknifty_all_tf.pkl.gz",
        "tfs": ["5m", "15m", "45m", "135m", "1d", "3d", "9d", "27d"],
    },
    "btc": {
        "label": "₿ BTCUSDT",
        "file": "btc_all_tf.pkl.gz",
        "tfs": ["160m", "8h", "1d", "3d", "9d", "27d"],
    },
}

TF_COLOR = {
    "5m": "#5b9cf6", "15m": "#7b61ff", "45m": "#f0b429", "135m": "#ef5350",
    "160m": "#26a69a", "8h": "#f0b429",
    "1d": "#2962ff", "3d": "#ef5350", "9d": "#f7525f", "27d": "#ff6d00",
}

st.set_page_config(page_title="Replay — BN + BTC Stack", layout="wide")

# ─────────────────────────────────────────────────────────────
# DATA LOADING (cached)
# ─────────────────────────────────────────────────────────────
@st.cache_data(show_spinner=False)
def load_data_from_github(filename: str):
    """Download the .pkl.gz from GitHub raw and unpickle it."""
    url = GH_BASE + filename
    resp = requests.get(url, timeout=60)
    resp.raise_for_status()
    with gzip.open(io.BytesIO(resp.content), "rb") as f:
        data = pickle.load(f)
    return data


@st.cache_data(show_spinner=False)
def load_data_from_local(path: str):
    """Fallback: read a .pkl.gz that sits next to app.py."""
    with gzip.open(path, "rb") as f:
        return pickle.load(f)


def get_asset_data(asset_key: str):
    cfg = ASSETS[asset_key]
    try:
        return load_data_from_github(cfg["file"])
    except Exception as e:
        # Fallback to a local copy if running with the file alongside app.py
        try:
            return load_data_from_local(cfg["file"])
        except Exception:
            st.error(f"❌ Could not load {cfg['file']} from GitHub or locally.\n\n{e}")
            st.stop()


# ─────────────────────────────────────────────────────────────
# SWING S/R + CANDLE H/L INDICATOR (ported from replay_app.html)
# ─────────────────────────────────────────────────────────────
def compute_swing_lines(df: pd.DataFrame, window: int = 300):
    """
    Returns two lists of dicts:
      swing_lines : top-5 most recent swing high/low S/R levels (infinite, solid)
      candle_lines: candle H/L echo levels (finite, dashed) still 'active'
    Each dict: {price, start, end(optional), kind}
    """
    if df is None or len(df) < 2:
        return [], []

    sub = df.tail(window) if len(df) > window else df
    idx = sub.index
    opens, highs, lows, closes = (
        sub["Open"].values, sub["High"].values, sub["Low"].values, sub["Close"].values,
    )

    a_res, a_sup = [], []   # swing resistance / support: dict(price, start_time, kind)
    ch_lines, cl_lines = [], []  # candle high / low echo lines

    p_green = p_red = False
    p_high = p_low = 0.0

    n = len(sub)
    bar_td = (idx[1] - idx[0]) if n > 1 else pd.Timedelta(days=1)

    for i in range(n):
        cur_o, cur_h, cur_l, cur_c, cur_t = opens[i], highs[i], lows[i], closes[i], idx[i]
        cur_green = cur_c > cur_o
        cur_red = cur_c < cur_o

        # validate / expire existing candle H/L echo lines
        def _validate(lines):
            kept = []
            for ln in lines:
                if ln["creation_i"] >= i:
                    kept.append(ln)
                    continue
                if cur_l <= ln["price"] <= cur_h:
                    ln["end"] = cur_t + 2 * bar_td
                    kept.append(ln)
                # else: line invalidated (price broken through) -> dropped
            return kept

        ch_lines = _validate(ch_lines)
        cl_lines = _validate(cl_lines)

        if i >= 1 and p_green and cur_red:
            r_price = max(p_high, cur_h)
            r_time = idx[i - 1] if p_high >= cur_h else cur_t
            a_res = [l for l in a_res if l["price"] <= r_price]
            a_sup = [l for l in a_sup if l["price"] <= r_price]
            a_res.append({"price": r_price, "start": r_time, "kind": "sh"})

        if i >= 1 and p_red and cur_green:
            s_price = min(p_low, cur_l)
            s_time = idx[i - 1] if p_low <= cur_l else cur_t
            a_sup = [l for l in a_sup if l["price"] >= s_price]
            a_res = [l for l in a_res if l["price"] >= s_price]
            a_sup.append({"price": s_price, "start": s_time, "kind": "sl"})

        swing_prices = {l["price"] for l in a_res} | {l["price"] for l in a_sup}
        if cur_h not in swing_prices:
            ch_lines.append({"price": cur_h, "start": cur_t, "end": cur_t + 2 * bar_td,
                              "creation_i": i, "kind": "ch"})
        if cur_l not in swing_prices:
            cl_lines.append({"price": cur_l, "start": cur_t, "end": cur_t + 2 * bar_td,
                              "creation_i": i, "kind": "cl"})

        p_green, p_red, p_high, p_low = cur_green, cur_red, cur_h, cur_l

    last_time = idx[-1] if n else None

    all_swings = sorted(a_res + a_sup, key=lambda l: l["start"], reverse=True)[:5]
    swing_lines = [{"price": s["price"], "start": s["start"], "kind": s["kind"]} for s in all_swings]

    candle_lines = [l for l in (ch_lines + cl_lines) if last_time is None or l["end"] > last_time]

    return swing_lines, candle_lines


# ─────────────────────────────────────────────────────────────
# CHART BUILDING
# ─────────────────────────────────────────────────────────────
def build_stack_figure(data: dict, tfs: list, bars_back: int, show_swing: bool, show_candle: bool):
    n = len(tfs)
    fig = make_subplots(
        rows=n, cols=1, shared_xaxes=False,
        vertical_spacing=0.02,
        subplot_titles=[f"{tf}" for tf in tfs],
    )

    for row, tf in enumerate(tfs, start=1):
        df = data.get(tf)
        if df is None or df.empty:
            continue
        plot_df = df.tail(bars_back) if bars_back else df
        color = TF_COLOR.get(tf, "#9598a1")

        fig.add_trace(
            go.Candlestick(
                x=plot_df.index,
                open=plot_df["Open"], high=plot_df["High"],
                low=plot_df["Low"], close=plot_df["Close"],
                increasing_line_color="#26a69a", decreasing_line_color="#ef5350",
                increasing_fillcolor="#26a69a", decreasing_fillcolor="#ef5350",
                name=tf, showlegend=False,
            ),
            row=row, col=1,
        )

        if show_swing or show_candle:
            swing_lines, candle_lines = compute_swing_lines(plot_df)
            x_last = plot_df.index[-1]

            if show_swing:
                for ln in swing_lines:
                    fig.add_shape(
                        type="line", row=row, col=1,
                        x0=ln["start"], x1=x_last, y0=ln["price"], y1=ln["price"],
                        line=dict(color=color, width=1.4),
                    )

            if show_candle:
                for ln in candle_lines:
                    fig.add_shape(
                        type="line", row=row, col=1,
                        x0=ln["start"], x1=min(ln["end"], x_last + (x_last - plot_df.index[0]) * 0.05),
                        y0=ln["price"], y1=ln["price"],
                        line=dict(color=color, width=0.8, dash="dot"),
                    )

        fig.update_xaxes(rangeslider_visible=False, row=row, col=1,
                          showgrid=False, zeroline=False)
        fig.update_yaxes(row=row, col=1, showgrid=False, zeroline=False, side="right")

    fig.update_layout(
        height=320 * n,
        template="plotly_dark",
        plot_bgcolor="#131722", paper_bgcolor="#131722",
        font=dict(color="#d1d4dc", size=11),
        margin=dict(l=10, r=50, t=30, b=10),
        showlegend=False,
        dragmode="pan",
    )
    return fig


# ─────────────────────────────────────────────────────────────
# UI
# ─────────────────────────────────────────────────────────────
st.markdown(
    "<style>div.block-container{padding-top:1.2rem;}</style>",
    unsafe_allow_html=True,
)

st.title("⏵ Replay — Historical Multi-Timeframe Stack")

col1, col2, col3, col4 = st.columns([2, 2, 2, 3])

with col1:
    asset_key = st.selectbox(
        "Symbol",
        options=list(ASSETS.keys()),
        format_func=lambda k: ASSETS[k]["label"],
    )

with col2:
    bars_back = st.selectbox(
        "Bars per chart",
        options=[100, 200, 300, 500, 1000],
        index=2,
    )

with col3:
    show_swing = st.checkbox("Swing S/R lines", value=True)

with col4:
    show_candle = st.checkbox("Candle High/Low lines", value=True)

cfg = ASSETS[asset_key]

with st.spinner(f"Loading {cfg['file']} …"):
    data = get_asset_data(asset_key)

available_tfs = [tf for tf in cfg["tfs"] if tf in data]

selected_tfs = st.multiselect(
    "Timeframes to stack",
    options=available_tfs,
    default=available_tfs,
)

if not selected_tfs:
    st.warning("Select at least one timeframe.")
    st.stop()

fig = build_stack_figure(data, selected_tfs, bars_back, show_swing, show_candle)
st.plotly_chart(fig, use_container_width=True, config={"scrollZoom": True})

with st.expander("ℹ️ Data info"):
    meta = data.get("meta")
    if meta is not None:
        st.write(meta)
    for tf in available_tfs:
        st.write(f"**{tf}** — {len(data[tf]):,} rows  "
                  f"({data[tf].index.min()} → {data[tf].index.max()})")
