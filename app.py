import json
import streamlit as st
import streamlit.components.v1 as components

st.set_page_config(page_title="Unknown Man — Trait Constellation", layout="wide")

# ---------------------------------------------------------------------------
# Character data lives directly in this file — no external JSON file needed.
# ---------------------------------------------------------------------------
DEFAULT_QUALITIES = {
    "Loving": {
        "color": "#ff6b8a",
        "subAngles": [
            {"name": "Caring", "desc": "Dhyaan rakhta hai, chhoti-chhoti zarooraton ka khayal rakhta hai."},
            {"name": "Affectionate", "desc": "Apna sneh khule dil se jataata hai, hesitate nahi karta."},
            {"name": "Family-oriented", "desc": "Parivar ke rishton ko sabse upar rakhta hai."},
            {"name": "Romantic", "desc": "Chhote-chhote palon ko khaas banane ki koshish karta hai."},
            {"name": "Compassionate", "desc": "Doosron ke dard ko mehsoos karta hai aur madad karta hai."},
        ],
    },
    "Honest": {
        "color": "#ffc75c",
        "subAngles": [
            {"name": "Transparent", "desc": "Kuch chhupata nahi, seedha aur saaf baat karta hai."},
            {"name": "Trustworthy", "desc": "Uski baat par bharosa kiya ja sakta hai."},
            {"name": "Direct", "desc": "Ghuma-firake bina seedhi baat kehta hai."},
        ],
    },
    "Confident": {
        "color": "#a78bfa",
        "subAngles": [
            {"name": "Self-assured", "desc": "Apne faislon par yakeen rakhta hai."},
            {"name": "Calm under pressure", "desc": "Mushkil waqt mein bhi sthir rehta hai."},
            {"name": "Decisive", "desc": "Jaldi aur sahi faisle leta hai."},
        ],
    },
}

# ---------------------------------------------------------------------------
# 10 selectable characters (Free Fire style) — each has its own color scheme
# and a signature accessory (mask, cape, wings, hood, etc.)
# ---------------------------------------------------------------------------
CHARACTERS = [
    {"id": "shadow",  "name": "Shadow Ninja",   "primary": "#3b3550", "secondary": "#1c1826", "accent": "#a78bfa", "skin": "#caa887", "accessory": "mask"},
    {"id": "blaze",   "name": "Crimson Blaze",  "primary": "#c0374a", "secondary": "#7a1f2b", "accent": "#ff9d4d", "skin": "#e8b48c", "accessory": "headband"},
    {"id": "frost",   "name": "Frost Guardian", "primary": "#4d7ea8", "secondary": "#274b66", "accent": "#e6f7ff", "skin": "#f0d3b8", "accessory": "goggles"},
    {"id": "jungle",  "name": "Jungle Ranger",  "primary": "#3f7d47", "secondary": "#234a29", "accent": "#c9a15a", "skin": "#c98f5e", "accessory": "scarf"},
    {"id": "gold",    "name": "Golden Warrior", "primary": "#caa23a", "secondary": "#7a5c17", "accent": "#2b2b2b", "skin": "#e8b48c", "accessory": "crown"},
    {"id": "cyber",   "name": "Neon Cyber",     "primary": "#1aa6b7", "secondary": "#0c5560", "accent": "#ff59c8", "skin": "#d9b48f", "accessory": "visor"},
    {"id": "desert",  "name": "Desert Nomad",   "primary": "#c68a3e", "secondary": "#7a521f", "accent": "#f2e2c8", "skin": "#caa06c", "accessory": "hood"},
    {"id": "storm",   "name": "Storm Rider",    "primary": "#5b6b7d", "secondary": "#333d47", "accent": "#5aa9ff", "skin": "#e0b894", "accessory": "goggles"},
    {"id": "phoenix", "name": "Phoenix Knight", "primary": "#d9432e", "secondary": "#7a1f10", "accent": "#ffcf5c", "skin": "#e8b48c", "accessory": "wings"},
    {"id": "void",    "name": "Void Walker",    "primary": "#5a3fa0", "secondary": "#241a42", "accent": "#00e5ff", "skin": "#b78f6b", "accessory": "hood"},
]

if "qualities" not in st.session_state:
    st.session_state.qualities = json.loads(json.dumps(DEFAULT_QUALITIES))

qualities = st.session_state.qualities

# ---------------------------------------------------------------------------
# SIDEBAR — full editing controls
# ---------------------------------------------------------------------------
with st.sidebar:
    st.title("Character Editor")
    st.caption("Yahan se qualities aur unke angles edit karo — figure turant update hoga.")

    st.divider()
    st.subheader("Add a quality")
    with st.form("add_quality", clear_on_submit=True):
        q_name = st.text_input("Quality name (e.g. Loving)")
        q_color = st.color_picker("Glow color", "#a78bfa")
        submitted = st.form_submit_button("Add quality")
        if submitted and q_name.strip():
            if q_name not in qualities:
                qualities[q_name] = {"color": q_color, "subAngles": []}
                st.rerun()
            else:
                st.warning("Ye quality already exist karti hai.")

    st.divider()
    st.subheader("Edit existing qualities")
    if not qualities:
        st.info("Koi quality nahi hai. Upar se add karo.")

    for qname in list(qualities.keys()):
        with st.expander(qname):
            new_color = st.color_picker("Color", qualities[qname]["color"], key=f"color_{qname}")
            qualities[qname]["color"] = new_color

            st.markdown("**Sub-angles**")
            for i, sub in enumerate(list(qualities[qname]["subAngles"])):
                c1, c2 = st.columns([4, 1])
                with c1:
                    sub["name"] = st.text_input("Name", sub["name"], key=f"{qname}_sub_name_{i}")
                    sub["desc"] = st.text_area("Description", sub["desc"], key=f"{qname}_sub_desc_{i}", height=60)
                with c2:
                    if st.button("🗑", key=f"{qname}_sub_del_{i}"):
                        qualities[qname]["subAngles"].pop(i)
                        st.rerun()
                st.markdown("---")

            with st.form(f"add_sub_{qname}", clear_on_submit=True):
                new_sub_name = st.text_input("New angle name", key=f"new_sub_name_{qname}")
                new_sub_desc = st.text_area("New angle description", key=f"new_sub_desc_{qname}", height=60)
                add_sub = st.form_submit_button("Add angle")
                if add_sub and new_sub_name.strip():
                    qualities[qname]["subAngles"].append({"name": new_sub_name, "desc": new_sub_desc})
                    st.rerun()

            if st.button(f"Delete '{qname}' entirely", key=f"del_quality_{qname}"):
                del qualities[qname]
                st.rerun()

    st.divider()
    st.subheader("Backup / restore (optional)")
    st.download_button(
        "Export current data (JSON)",
        data=json.dumps(qualities, ensure_ascii=False, indent=2),
        file_name="qualities.json",
        mime="application/json",
    )
    uploaded = st.file_uploader("Import JSON", type="json")
    if uploaded is not None:
        try:
            st.session_state.qualities = json.load(uploaded)
            st.rerun()
        except Exception as e:
            st.error(f"File load nahi hua: {e}")

    if st.button("Reset to defaults"):
        st.session_state.qualities = json.loads(json.dumps(DEFAULT_QUALITIES))
        st.rerun()

# ---------------------------------------------------------------------------
# MAIN — the animated figure
# ---------------------------------------------------------------------------
st.markdown(
    "<h1 style='font-family:Space Grotesk, sans-serif; letter-spacing:-1px;'>"
    "Unknown Man — Trait Constellation</h1>"
    "<p style='color:#9aa0b4;'>Figure ko drag karke ghumao. Kisi bhi tag par tap karke uske angles dekho. "
    "Top-right corner ka ⛶ icon full screen ke liye hai.</p>",
    unsafe_allow_html=True,
)

QUALITIES_JSON = json.dumps(qualities, ensure_ascii=False)
CHARACTERS_JSON = json.dumps(CHARACTERS, ensure_ascii=False)

HTML = f"""
<div id="appRoot">
<div id="root">
  <button id="fsBtn" title="Full screen">⛶</button>

  <div id="charStripWrap">
    <div id="charStripLabel">Select Character</div>
    <div id="charStrip"></div>
  </div>

  <div id="stage">
    <div id="orbit">
      <div id="figureWrap">
        <svg id="figureSvg" width="180" height="360" viewBox="0 0 180 360"></svg>
      </div>
    </div>
  </div>
  <div id="poseLabel"></div>
  <div id="hint">drag to rotate • tap a tag for details • tap an avatar to change character</div>

  <div id="overlay">
    <div id="panel">
      <button id="closeBtn" onclick="closePanel()">✕</button>
      <h2 id="panelTitle"></h2>
      <p id="panelSub"></p>
      <div class="cardGrid" id="cardGrid"></div>
    </div>
  </div>
</div>
</div>

<style>
  @import url('https://fonts.googleapis.com/css2?family=Space+Grotesk:wght@500;700&family=Inter:wght@400;500&display=swap');

  * {{ box-sizing: border-box; }}

  #appRoot {{ width: 100%; }}

  #root {{
    font-family: 'Inter', sans-serif;
    background:
      radial-gradient(circle at 30% 20%, rgba(167,139,250,0.16), transparent 45%),
      radial-gradient(circle at 75% 75%, rgba(255,107,138,0.12), transparent 45%),
      linear-gradient(180deg, #23273a 0%, #14161f 100%);
    border: 1px solid rgba(255,255,255,0.08);
    border-radius: 20px;
    position: relative;
    width: 100%;
    height: 680px;
    overflow: hidden;
    user-select: none;
  }}
  #root.fullscreen-mode {{
    position: fixed;
    inset: 0;
    width: 100vw;
    height: 100vh;
    border-radius: 0;
    z-index: 999999;
  }}

  #fsBtn {{
    position: absolute;
    top: 14px; right: 14px;
    z-index: 20;
    width: 40px; height: 40px;
    border-radius: 10px;
    border: 1px solid rgba(255,255,255,0.18);
    background: rgba(255,255,255,0.08);
    color: #f2f2f7;
    font-size: 18px;
    cursor: pointer;
  }}
  #fsBtn:hover {{ background: rgba(255,255,255,0.16); }}

  #charStripWrap {{
    position: absolute;
    top: 14px; left: 14px; right: 64px;
    z-index: 15;
  }}
  #charStripLabel {{
    font-family: 'Space Grotesk', sans-serif;
    font-size: 12px;
    letter-spacing: 0.08em;
    text-transform: uppercase;
    color: #9aa0b4;
    margin-bottom: 8px;
  }}
  #charStrip {{
    display: flex;
    gap: 10px;
    overflow-x: auto;
    padding-bottom: 6px;
    scrollbar-width: thin;
  }}
  #charStrip::-webkit-scrollbar {{ height: 5px; }}
  #charStrip::-webkit-scrollbar-thumb {{ background: rgba(255,255,255,0.18); border-radius: 10px; }}

  .avatar {{
    flex: 0 0 auto;
    width: 52px; height: 52px;
    border-radius: 14px;
    display: flex; align-items: center; justify-content: center;
    cursor: pointer;
    border: 2px solid rgba(255,255,255,0.14);
    position: relative;
    transition: transform 0.15s ease, border-color 0.15s ease, box-shadow 0.15s ease;
    font-family: 'Space Grotesk', sans-serif;
    font-weight: 700;
    font-size: 16px;
    color: #fff;
    text-shadow: 0 1px 3px rgba(0,0,0,0.5);
  }}
  .avatar:hover {{ transform: translateY(-3px); }}
  .avatar.active {{
    border-color: #fff;
    box-shadow: 0 0 0 2px rgba(255,255,255,0.25), 0 0 16px rgba(255,255,255,0.35);
    transform: translateY(-3px) scale(1.06);
  }}
  .avatarName {{
    position: absolute;
    bottom: -20px; left: 50%;
    transform: translateX(-50%);
    font-size: 9px;
    color: #c3c8d9;
    white-space: nowrap;
    font-weight: 500;
    opacity: 0;
    transition: opacity 0.15s ease;
  }}
  .avatar:hover .avatarName, .avatar.active .avatarName {{ opacity: 1; }}

  #stage {{
    position: absolute;
    top: 90px; left: 0; right: 0; bottom: 0;
    display: flex;
    align-items: center;
    justify-content: center;
  }}

  #orbit {{
    position: relative;
    width: 520px;
    height: 520px;
    max-width: 92vw;
    max-height: 80vh;
    cursor: grab;
  }}
  #orbit.dragging {{ cursor: grabbing; }}

  #figureWrap {{
    position: absolute;
    top: 50%; left: 50%;
    transform: translate(-50%, -50%);
    animation: breathe 4.5s ease-in-out infinite;
  }}
  @keyframes breathe {{
    0%, 100% {{ transform: translate(-50%, -50%) scale(1); }}
    50% {{ transform: translate(-50%, -50%) scale(1.02) translateY(-4px); }}
  }}

  #figureSvg {{
    transition: opacity 0.28s ease, transform 0.32s cubic-bezier(.34,1.56,.64,1);
    opacity: 1;
  }}
  #figureSvg.posing {{
    opacity: 0;
    transform: scale(0.96) translateY(6px);
  }}

  #poseLabel {{
    position: absolute;
    top: 96px; left: 50%;
    transform: translateX(-50%);
    z-index: 6;
    padding: 5px 14px;
    border-radius: 999px;
    background: rgba(255,255,255,0.08);
    border: 1px solid rgba(255,255,255,0.16);
    backdrop-filter: blur(6px);
    color: #e7e9f5;
    font-family: 'Space Grotesk', sans-serif;
    font-size: 12px;
    letter-spacing: 0.04em;
    text-transform: uppercase;
    opacity: 0;
    transition: opacity 0.3s ease, transform 0.3s ease;
  }}
  #poseLabel.show {{ opacity: 1; transform: translateX(-50%) translateY(0); }}

  .tag {{
    position: absolute;
    top: 50%; left: 50%;
    padding: 8px 16px;
    border-radius: 999px;
    background: rgba(255,255,255,0.1);
    border: 1.5px solid rgba(255,255,255,0.3);
    backdrop-filter: blur(6px);
    color: #ffffff;
    font-size: 14px;
    font-weight: 600;
    white-space: nowrap;
    cursor: pointer;
    box-shadow: 0 2px 12px rgba(0,0,0,0.35);
    transition: transform 0.2s ease, box-shadow 0.2s ease, background 0.2s ease;
  }}
  .tag:hover {{
    transform: translate(-50%,-50%) scale(1.12) !important;
    background: rgba(255,255,255,0.2);
  }}

  .ring-line {{
    position: absolute;
    top: 50%; left: 50%;
    height: 1px;
    background: linear-gradient(90deg, rgba(255,255,255,0.28), transparent);
    transform-origin: left center;
    pointer-events: none;
  }}

  #overlay {{
    position: absolute;
    inset: 0;
    background: rgba(8,9,14,0.82);
    backdrop-filter: blur(10px);
    display: none;
    align-items: center;
    justify-content: center;
    z-index: 30;
    padding: 30px;
  }}
  #overlay.show {{ display: flex; }}

  #panel {{
    max-width: 720px;
    width: 100%;
    max-height: 540px;
    overflow-y: auto;
    background: #1b1e2b;
    border: 1px solid rgba(255,255,255,0.12);
    border-radius: 18px;
    padding: 28px 32px;
    animation: panelIn 0.35s ease;
  }}
  @keyframes panelIn {{
    from {{ opacity: 0; transform: scale(0.94) translateY(10px); }}
    to {{ opacity: 1; transform: scale(1) translateY(0); }}
  }}

  #panelTitle {{
    font-family: 'Space Grotesk', sans-serif;
    font-size: 28px;
    font-weight: 700;
    margin: 0 0 4px 0;
  }}
  #panelSub {{ color: #9aa0b4; margin: 0 0 20px 0; font-size: 14px; }}

  .cardGrid {{
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
    gap: 14px;
  }}
  .card {{
    background: rgba(255,255,255,0.06);
    border: 1px solid rgba(255,255,255,0.12);
    border-radius: 14px;
    padding: 16px;
    opacity: 0;
    animation: cardIn 0.4s ease forwards;
  }}
  @keyframes cardIn {{
    from {{ opacity: 0; transform: translateY(8px); }}
    to {{ opacity: 1; transform: translateY(0); }}
  }}
  .card h4 {{ margin: 8px 0 6px 0; font-family: 'Space Grotesk', sans-serif; font-size: 16px; color: #fff; }}
  .card p {{ margin: 0; color: #c3c8d9; font-size: 13px; line-height: 1.5; }}

  .icon {{
    width: 26px; height: 26px; border-radius: 50%;
    display: inline-block;
  }}
  .icon.pulse {{ animation: pulse 1.6s ease-in-out infinite; }}
  .icon.glow {{ animation: glow 2s ease-in-out infinite; }}
  .icon.wave {{ animation: wave 1.8s ease-in-out infinite; }}
  @keyframes pulse {{ 0%,100% {{ transform: scale(1); opacity: 0.85; }} 50% {{ transform: scale(1.3); opacity: 1; }} }}
  @keyframes glow {{ 0%,100% {{ box-shadow: 0 0 6px currentColor; }} 50% {{ box-shadow: 0 0 18px currentColor; }} }}
  @keyframes wave {{ 0%,100% {{ transform: translateY(0); }} 50% {{ transform: translateY(-5px); }} }}

  #closeBtn {{
    float: right;
    background: rgba(255,255,255,0.1);
    border: none;
    color: #f2f2f7;
    width: 32px; height: 32px;
    border-radius: 50%;
    cursor: pointer;
    font-size: 16px;
  }}
  #closeBtn:hover {{ background: rgba(255,255,255,0.2); }}

  #hint {{
    position: absolute;
    bottom: 16px; left: 50%;
    transform: translateX(-50%);
    color: #b8bccc;
    font-size: 12px;
    z-index: 5;
  }}
</style>

<script>
  const QUALITIES = {QUALITIES_JSON};
  const CHARACTERS = {CHARACTERS_JSON};
  const names = Object.keys(QUALITIES);
  const root = document.getElementById('root');
  const orbit = document.getElementById('orbit');
  const figureWrap = document.getElementById('figureWrap');
  const iconAnims = ['pulse', 'glow', 'wave'];
  let currentCharIndex = 0;
  let currentPoseIndex = 0;

  // -------------------------------------------------------------------
  // Pose library — each pose redefines limb paths + a slight torso/head
  // tilt so every character cycles through several dynamic stances,
  // Free-Fire-lobby style ("ek se ek pose").
  // -------------------------------------------------------------------
  const POSES = [
    {{
      label: 'Idle Stance',
      rotate: 0,
      backArm:  "M118 118 Q142 130 146 168 Q148 190 138 208",  backArmEnd:  [138, 208],
      frontArm: "M62 118 Q38 132 34 168 Q32 192 44 210",       frontArmEnd: [44, 210],
      backLeg:  "M100 232 Q112 268 108 300 Q106 320 116 338",  backLegEnd:  [118, 344],
      frontLeg: "M80 232 Q66 268 70 300 Q72 320 62 338",       frontLegEnd: [60, 344]
    }},
    {{
      label: 'Hands On Hips',
      rotate: -2,
      backArm:  "M118 118 Q152 124 150 152 Q148 170 122 174",  backArmEnd:  [122, 174],
      frontArm: "M62 118 Q28 124 30 152 Q32 170 58 174",       frontArmEnd: [58, 174],
      backLeg:  "M100 232 Q110 266 106 300 Q104 322 114 338",  backLegEnd:  [116, 344],
      frontLeg: "M80 232 Q70 266 74 300 Q76 322 66 338",       frontLegEnd: [64, 344]
    }},
    {{
      label: 'Action Punch',
      rotate: 5,
      backArm:  "M118 118 Q96 96 74 88 Q54 82 40 88",          backArmEnd:  [40, 88],
      frontArm: "M62 118 Q42 142 32 170 Q26 192 36 208",       frontArmEnd: [36, 208],
      backLeg:  "M100 232 Q132 250 136 286 Q138 312 122 336",  backLegEnd:  [124, 342],
      frontLeg: "M80 232 Q54 250 46 282 Q40 306 54 336",       frontLegEnd: [56, 342]
    }},
    {{
      label: 'Victory Pose',
      rotate: 0,
      backArm:  "M118 118 Q142 88 138 50 Q136 28 118 16",      backArmEnd:  [118, 16],
      frontArm: "M62 118 Q38 88 42 50 Q44 28 62 16",           frontArmEnd: [62, 16],
      backLeg:  "M100 232 Q112 266 108 298 Q106 318 116 338",  backLegEnd:  [118, 344],
      frontLeg: "M80 232 Q66 266 70 298 Q72 318 62 338",       frontLegEnd: [60, 344]
    }},
    {{
      label: 'Battle Stride',
      rotate: -4,
      backArm:  "M118 118 Q148 136 154 168 Q156 188 142 202",  backArmEnd:  [142, 202],
      frontArm: "M62 118 Q34 98 26 128 Q22 150 32 168",        frontArmEnd: [32, 168],
      backLeg:  "M100 232 Q122 254 130 288 Q134 310 124 336",  backLegEnd:  [126, 342],
      frontLeg: "M80 232 Q58 254 46 284 Q40 306 50 332",       frontLegEnd: [48, 338]
    }}
  ];

  // -------------------------------------------------------------------
  // Build a jointed, Free-Fire-style character SVG for a given preset
  // -------------------------------------------------------------------
  function buildFigureSVG(ch, poseIndex) {{
    const skin = ch.skin, primary = ch.primary, secondary = ch.secondary, accent = ch.accent;
    const pose = POSES[(poseIndex || 0) % POSES.length];

    let accessorySVG = '';
    switch (ch.accessory) {{
      case 'mask':
        accessorySVG = `<path d="M55 60 Q90 78 125 60 L125 72 Q90 90 55 72 Z" fill="${{secondary}}" opacity="0.92"/>`;
        break;
      case 'headband':
        accessorySVG = `<rect x="50" y="48" width="80" height="12" rx="6" fill="${{accent}}"/>
                         <path d="M128 52 L150 46 L150 58 L128 60 Z" fill="${{accent}}"/>`;
        break;
      case 'goggles':
        accessorySVG = `<rect x="52" y="52" width="76" height="20" rx="10" fill="${{secondary}}" stroke="${{accent}}" stroke-width="2"/>
                         <circle cx="72" cy="62" r="7" fill="${{accent}}" opacity="0.85"/>
                         <circle cx="108" cy="62" r="7" fill="${{accent}}" opacity="0.85"/>`;
        break;
      case 'scarf':
        accessorySVG = `<path d="M50 100 Q90 118 130 100 L130 116 Q90 132 50 116 Z" fill="${{accent}}"/>`;
        break;
      case 'crown':
        accessorySVG = `<path d="M62 26 L72 6 L90 22 L108 6 L118 26 L118 36 L62 36 Z" fill="${{accent}}" stroke="${{secondary}}" stroke-width="1.5"/>`;
        break;
      case 'visor':
        accessorySVG = `<rect x="50" y="50" width="80" height="16" rx="8" fill="${{accent}}" opacity="0.85"/>`;
        break;
      case 'hood':
        accessorySVG = `<path d="M40 55 Q90 -6 140 55 L140 80 Q90 60 40 80 Z" fill="${{secondary}}" opacity="0.96"/>`;
        break;
      case 'wings':
        accessorySVG = `<path d="M30 140 Q-10 160 10 220 Q30 195 55 190 Z" fill="${{accent}}" opacity="0.9"/>
                         <path d="M150 140 Q190 160 170 220 Q150 195 125 190 Z" fill="${{accent}}" opacity="0.9"/>`;
        break;
      default: accessorySVG = '';
    }}

    // Cape (drawn first, sits behind everything) for phoenix/gold flair
    const capeSVG = (ch.accessory === 'wings')
      ? `<path d="M65 110 Q90 260 65 320 Q90 300 90 300 Q90 300 115 320 Q90 260 115 110 Z" fill="${{secondary}}" opacity="0.55"/>`
      : '';

    return `
      <defs>
        <linearGradient id="torsoGrad" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stop-color="${{primary}}"/>
          <stop offset="100%" stop-color="${{secondary}}"/>
        </linearGradient>
        <linearGradient id="limbGrad" x1="0" y1="0" x2="1" y2="1">
          <stop offset="0%" stop-color="${{secondary}}"/>
          <stop offset="100%" stop-color="${{primary}}"/>
        </linearGradient>
      </defs>

      <g transform="rotate(${{pose.rotate}} 90 180)">
        ${{capeSVG}}

        <!-- back arm (right, behind torso) -->
        <path d="${{pose.backArm}}" stroke="url(#limbGrad)" stroke-width="17" stroke-linecap="round" fill="none"/>
        <circle cx="${{pose.backArmEnd[0]}}" cy="${{pose.backArmEnd[1]}}" r="10" fill="${{skin}}"/>

        <!-- back leg (right) -->
        <path d="${{pose.backLeg}}" stroke="url(#limbGrad)" stroke-width="20" stroke-linecap="round" fill="none"/>
        <ellipse cx="${{pose.backLegEnd[0]}}" cy="${{pose.backLegEnd[1]}}" rx="15" ry="8" fill="${{secondary}}"/>

        <!-- front leg (left) -->
        <path d="${{pose.frontLeg}}" stroke="url(#limbGrad)" stroke-width="20" stroke-linecap="round" fill="none"/>
        <ellipse cx="${{pose.frontLegEnd[0]}}" cy="${{pose.frontLegEnd[1]}}" rx="15" ry="8" fill="${{primary}}"/>

        <!-- torso -->
        <path d="M90 82 C60 82 46 108 46 142 L46 210 C46 236 64 250 90 250 C116 250 134 236 134 210 L134 142 C134 108 120 82 90 82 Z"
              fill="url(#torsoGrad)" stroke="${{accent}}" stroke-width="2" stroke-opacity="0.55"/>
        <!-- torso panel detail -->
        <path d="M90 100 L90 230 M62 130 L90 145 L118 130" stroke="${{accent}}" stroke-width="2" fill="none" opacity="0.5"/>

        <!-- neck -->
        <rect x="80" y="66" width="20" height="20" rx="6" fill="${{skin}}"/>

        <!-- head -->
        <circle cx="90" cy="46" r="34" fill="${{skin}}" stroke="${{secondary}}" stroke-width="2" stroke-opacity="0.5"/>

        <!-- front arm (left, in front of torso) -->
        <path d="${{pose.frontArm}}" stroke="url(#limbGrad)" stroke-width="17" stroke-linecap="round" fill="none"/>
        <circle cx="${{pose.frontArmEnd[0]}}" cy="${{pose.frontArmEnd[1]}}" r="10" fill="${{skin}}"/>

        ${{accessorySVG}}
      </g>
    `;
  }}

  function renderFigure(instant) {{
    const svgEl = document.getElementById('figureSvg');
    const ch = CHARACTERS[currentCharIndex];
    const label = document.getElementById('poseLabel');

    const paint = () => {{
      svgEl.innerHTML = buildFigureSVG(ch, currentPoseIndex);
      svgEl.classList.remove('posing');
      label.textContent = POSES[currentPoseIndex].label;
      label.classList.add('show');
    }};

    if (instant) {{
      paint();
      return;
    }}
    svgEl.classList.add('posing');
    label.classList.remove('show');
    setTimeout(paint, 260);
  }}

  function cyclePose() {{
    currentPoseIndex = (currentPoseIndex + 1) % POSES.length;
    renderFigure(false);
  }}

  function applyCharacter(index) {{
    currentCharIndex = index;
    const ch = CHARACTERS[index];
    figureWrap.style.filter = `drop-shadow(0 0 24px ${{ch.accent}}88)`;
    document.querySelectorAll('.avatar').forEach((el, i) => {{
      el.classList.toggle('active', i === index);
    }});
    renderFigure(true);
  }}

  function renderCharStrip() {{
    const strip = document.getElementById('charStrip');
    strip.innerHTML = '';
    CHARACTERS.forEach((ch, i) => {{
      const av = document.createElement('div');
      av.className = 'avatar' + (i === 0 ? ' active' : '');
      av.style.background = `linear-gradient(145deg, ${{ch.primary}}, ${{ch.secondary}})`;
      av.style.borderColor = i === 0 ? '#fff' : 'rgba(255,255,255,0.14)';
      av.innerHTML = `${{ch.name.split(' ').map(w => w[0]).join('')}}<span class="avatarName">${{ch.name}}</span>`;
      av.onclick = () => applyCharacter(i);
      strip.appendChild(av);
    }});
  }}

  function getRadius() {{
    return Math.min(220, orbit.clientWidth / 2 - 40);
  }}

  let rotation = 0;
  let dragging = false;
  let lastX = 0;
  const autoSpeed = 0.05;

  function layout() {{
    orbit.querySelectorAll('.tag, .ring-line').forEach(el => el.remove());
    names.forEach((name, i) => {{
      const angle = (360 / names.length) * i;
      const color = QUALITIES[name].color;

      const line = document.createElement('div');
      line.className = 'ring-line';
      orbit.appendChild(line);

      const tag = document.createElement('div');
      tag.className = 'tag';
      tag.textContent = name;
      tag.style.borderColor = color;
      tag.dataset.angle = angle;
      tag.onclick = () => openPanel(name);
      orbit.appendChild(tag);
    }});
    position();
  }}

  function position() {{
    const radius = getRadius();
    const tags = orbit.querySelectorAll('.tag');
    tags.forEach(tag => {{
      const a = (parseFloat(tag.dataset.angle) + rotation) * Math.PI / 180;
      const x = Math.cos(a) * radius;
      const y = Math.sin(a) * radius * 0.55;
      tag.style.transform = `translate(${{x}}px, ${{y}}px) translate(-50%,-50%)`;
      tag.style.zIndex = Math.round(100 + y);
      tag.style.opacity = 0.65 + (y / (radius*0.55)) * 0.35;
    }});
    const lines = orbit.querySelectorAll('.ring-line');
    lines.forEach((line, i) => {{
      line.style.width = radius + 'px';
      const a = (names.length ? (360/names.length)*i : 0) + rotation;
      line.style.transform = `rotate(${{a}}deg) scaleX(0.5)`;
    }});
  }}

  function tick() {{
    if (!dragging) rotation += autoSpeed;
    position();
    requestAnimationFrame(tick);
  }}

  orbit.addEventListener('pointerdown', e => {{
    dragging = true;
    lastX = e.clientX;
    orbit.classList.add('dragging');
  }});
  window.addEventListener('pointermove', e => {{
    if (!dragging) return;
    const dx = e.clientX - lastX;
    rotation += dx * 0.4;
    lastX = e.clientX;
  }});
  window.addEventListener('pointerup', () => {{
    dragging = false;
    orbit.classList.remove('dragging');
  }});

  function openPanel(name) {{
    const q = QUALITIES[name];
    document.getElementById('panelTitle').textContent = name;
    document.getElementById('panelTitle').style.color = q.color;
    document.getElementById('panelSub').textContent = q.subAngles.length + ' angles of ' + name.toLowerCase();
    const grid = document.getElementById('cardGrid');
    grid.innerHTML = '';
    q.subAngles.forEach((sub, i) => {{
      const card = document.createElement('div');
      card.className = 'card';
      card.style.animationDelay = (i * 0.06) + 's';
      const anim = iconAnims[i % iconAnims.length];
      card.innerHTML = `<span class="icon ${{anim}}" style="background:${{q.color}}; color:${{q.color}};"></span>
                         <h4>${{sub.name}}</h4><p>${{sub.desc}}</p>`;
      grid.appendChild(card);
    }});
    document.getElementById('overlay').classList.add('show');
  }}
  function closePanel() {{
    document.getElementById('overlay').classList.remove('show');
  }}

  document.getElementById('fsBtn').addEventListener('click', () => {{
    root.classList.toggle('fullscreen-mode');
    document.getElementById('fsBtn').textContent = root.classList.contains('fullscreen-mode') ? '✕' : '⛶';
    setTimeout(position, 50);
  }});

  window.addEventListener('resize', position);

  renderCharStrip();
  applyCharacter(0);
  layout();
  requestAnimationFrame(tick);
  setInterval(cyclePose, 2600);
</script>
"""

components.html(HTML, height=720, scrolling=False)
