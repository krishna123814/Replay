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

HTML = f"""
<div id="appRoot">
<div id="root">
  <button id="fsBtn" title="Full screen">⛶</button>
  <div id="stage">
    <div id="orbit">
      <div id="figureWrap">
        <svg width="150" height="320" viewBox="0 0 150 320">
          <defs>
            <linearGradient id="bodyGrad" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stop-color="#9aa3c9"/>
              <stop offset="100%" stop-color="#4d5480"/>
            </linearGradient>
          </defs>
          <circle cx="75" cy="55" r="42" fill="url(#bodyGrad)" stroke="#c7cdea" stroke-width="1.5" stroke-opacity="0.4"/>
          <path d="M75 95 C40 95 22 130 22 175 L22 260 C22 285 40 300 75 300 C110 300 128 285 128 260 L128 175 C128 130 110 95 75 95 Z" fill="url(#bodyGrad)" stroke="#c7cdea" stroke-width="1.5" stroke-opacity="0.4"/>
          <path d="M22 150 C10 160 4 190 8 225" stroke="#6b7299" stroke-width="16" stroke-linecap="round" fill="none"/>
          <path d="M128 150 C140 160 146 190 142 225" stroke="#6b7299" stroke-width="16" stroke-linecap="round" fill="none"/>
        </svg>
      </div>
    </div>
  </div>
  <div id="hint">drag to rotate • tap a tag for details</div>

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
    height: 640px;
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

  #stage {{
    position: absolute;
    inset: 0;
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
    filter: drop-shadow(0 0 24px rgba(167,139,250,0.45));
  }}
  @keyframes breathe {{
    0%, 100% {{ transform: translate(-50%, -50%) scale(1); }}
    50% {{ transform: translate(-50%, -50%) scale(1.02) translateY(-4px); }}
  }}

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
  const names = Object.keys(QUALITIES);
  const root = document.getElementById('root');
  const orbit = document.getElementById('orbit');
  const iconAnims = ['pulse', 'glow', 'wave'];

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

  layout();
  requestAnimationFrame(tick);
</script>
"""

components.html(HTML, height=680, scrolling=False)
