"""
================================================================
 REPLAY APP — BankNifty + BTCUSDT Historical Stack Viewer
 Streamlit version (no local server needed)
 Data source: raw 5-minute .json.gz files pulled directly from your
 GitHub repo. All higher timeframes (15m, 45m, 1d, 3d, ...) are
 derived on the fly by resampling the 5-minute OHLC data.
================================================================
Repo: krishna123814/Replay (branch: main)
Files expected in repo root:
    banknifty_5m_csv.json.gz       -> {"meta": {...}, "data": [{"t","o","h","l","c"}, ...]}
    Bitcoin_BTCUSDT_IST_5m.json.gz -> [{"t","o","h","l","c"}, ...]

Run locally:
    pip install streamlit pandas plotly requests
    streamlit run app.py

Deploy:
    Push this file (app.py) + the two .gz files to your GitHub repo,
    then deploy on https://share.streamlit.io pointing at app.py
"""

import gzip
import json
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
        "file": "banknifty_5m_csv.json.gz",
        "tfs": ["5m", "15m", "45m", "135m", "1d", "3d", "9d", "27d"],
    },
    "btc": {
        "label": "₿ BTCUSDT",
        "file": "Bitcoin_BTCUSDT_IST_5m.json.gz",
        "tfs": ["5m", "160m", "8h", "1d", "3d", "9d", "27d"],
    },
}

# Pandas resample rule for each timeframe key (base data is 5-minute bars)
TF_RULE = {
    "5m": "5min", "15m": "15min", "45m": "45min", "135m": "135min",
    "160m": "160min", "8h": "8h",
    "1d": "1D", "3d": "3D", "9d": "9D", "27d": "27D",
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
def _parse_5m_df(raw_bytes: bytes) -> pd.DataFrame:
    """Decompress + parse a .json.gz into a 5-minute OHLC DataFrame.

    Accepts either:
      {"meta": {...}, "data": [{"t","o","h","l","c"}, ...]}
    or a bare list:
      [{"t","o","h","l","c"}, ...]
    """
    with gzip.open(io.BytesIO(raw_bytes), "rt") as f:
        payload = json.load(f)

    records = payload["data"] if isinstance(payload, dict) and "data" in payload else payload

    df = pd.DataFrame.from_records(records)
    df["t"] = pd.to_datetime(df["t"], unit="s", utc=True)
    df = df.rename(columns={"o": "Open", "h": "High", "l": "Low", "c": "Close"})
    df = df.set_index("t").sort_index()
    return df[["Open", "High", "Low", "Close"]]


def _resample(df5: pd.DataFrame, rule: str) -> pd.DataFrame:
    """Resample 5-minute OHLC data up to a higher timeframe."""
    if rule == "5min":
        return df5
    agg = pd.concat(
        [
            df5["Open"].resample(rule).first(),
            df5["High"].resample(rule).max(),
            df5["Low"].resample(rule).min(),
            df5["Close"].resample(rule).last(),
        ],
        axis=1,
    )
    agg.columns = ["Open", "High", "Low", "Close"]
    return agg.dropna(how="any")


@st.cache_data(show_spinner=False)
def _build_all_tfs(raw_bytes: bytes, tfs: tuple) -> dict:
    """Parse raw 5m bytes once, then derive every requested timeframe."""
    df5 = _parse_5m_df(raw_bytes)
    out = {}
    for tf in tfs:
        rule = TF_RULE.get(tf)
        if rule is None:
            continue
        out[tf] = _resample(df5, rule)
    out["meta"] = {
        "rows_5m": len(df5),
        "range_start": str(df5.index.min()),
        "range_end": str(df5.index.max()),
    }
    return out


@st.cache_data(show_spinner=False)
def _fetch_bytes_from_github(filename: str) -> bytes:
    url = GH_BASE + filename
    resp = requests.get(url, timeout=60)
    resp.raise_for_status()
    return resp.content


def _read_bytes_local(filename: str) -> bytes:
    with open(filename, "rb") as f:
        return f.read()


def get_asset_data(asset_key: str):
    cfg = ASSETS[asset_key]
    try:
        raw = _fetch_bytes_from_github(cfg["file"])
    except Exception as e_gh:
        try:
            raw = _read_bytes_local(cfg["file"])
        except Exception:
            st.error(f"❌ Could not load {cfg['file']} from GitHub or locally.\n\n{e_gh}")
            st.stop()
    return _build_all_tfs(raw, tuple(cfg["tfs"]))


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

        fig.update_xaxes(
            rangeslider_visible=False, row=row, col=1,
            showgrid=True, gridcolor="#e6e9ec", zeroline=False,
            showspikes=True, spikemode="across", spikesnap="cursor",
            spikecolor="#9598a1", spikethickness=1, spikedash="solid",
        )
        fig.update_yaxes(
            row=row, col=1, showgrid=True, gridcolor="#e6e9ec",
            zeroline=False, side="right",
            showspikes=True, spikemode="across", spikesnap="cursor",
            spikecolor="#9598a1", spikethickness=1, spikedash="solid",
        )

    fig.update_layout(
        height=320 * n,
        template="plotly_white",
        plot_bgcolor="#ffffff", paper_bgcolor="#ffffff",
        font=dict(color="#131722", size=11),
        margin=dict(l=10, r=50, t=30, b=10),
        showlegend=False,
        dragmode="pan",
        hovermode="x",
    )
    return fig


# ─────────────────────────────────────────────────────────────
# UI
# ─────────────────────────────────────────────────────────────
st.markdown(
    """
    <style>
        html, body, .stApp { background-color: #ffffff; }
        div.block-container { padding-top: 0.6rem; padding-bottom: 0rem; }
        #MainMenu {visibility: hidden;}
        footer {visibility: hidden;}
        header {visibility: hidden;}
        div[data-testid="stToolbar"] {visibility: hidden;}
    </style>
    """,
    unsafe_allow_html=True,
)

with st.sidebar:
    st.markdown("### ⏵ Replay")

    asset_key = st.selectbox(
        "Symbol",
        options=list(ASSETS.keys()),
        format_func=lambda k: ASSETS[k]["label"],
    )

    bars_back = st.selectbox(
        "Bars per chart",
        options=[100, 200, 300, 500, 1000],
        index=2,
    )

    show_swing = st.checkbox("Swing S/R lines", value=True)
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

    with st.expander("ℹ️ Data info"):
        meta = data.get("meta")
        if meta is not None:
            st.write(meta)
        for tf in available_tfs:
            st.write(f"**{tf}** — {len(data[tf]):,} rows  "
                      f"({data[tf].index.min()} → {data[tf].index.max()})")

if not selected_tfs:
    st.warning("Select at least one timeframe.")
    st.stop()

fig = build_stack_figure(data, selected_tfs, bars_back, show_swing, show_candle)
st.plotly_chart(fig, use_container_width=True, config={"scrollZoom": True})
