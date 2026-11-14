"""把模拟器截图做成商店用的 1080x1920 图（等比缩放 + 上下补底色，不裁内容）。

用法：
  python docs/release/make_store_shots.py
依赖：Pillow
"""

from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "docs" / "shots"
OUT = ROOT / "docs" / "release" / "screenshots"
BG = (10, 16, 20)  # #0A1014，与 App 深墨底一致
TARGET = (1080, 1920)

SHOTS = [
    ("m4-home.png", "01-home.png"),
    ("m4-capture.png", "02-capture.png"),
    ("m4-done.png", "03-done.png"),
    ("m5-ledger.png", "04-evidence.png"),
    ("m6-fullscreen.png", "05-alarm.png"),
    ("m5-wall.png", "06-wall.png"),
]


def make(src_name: str, out_name: str) -> None:
    src_path = SRC / src_name
    if not src_path.exists():
        print(f"skip (missing): {src_name}")
        return
    im = Image.open(src_path).convert("RGB")
    tw, th = TARGET
    scale = min(tw / im.width, th / im.height)
    nw, nh = round(im.width * scale), round(im.height * scale)
    im = im.resize((nw, nh), Image.LANCZOS)
    canvas = Image.new("RGB", TARGET, BG)
    canvas.paste(im, ((tw - nw) // 2, (th - nh) // 2))
    OUT.mkdir(parents=True, exist_ok=True)
    dst = OUT / out_name
    canvas.save(dst, "PNG", optimize=True)
    print(f"{out_name}: {src_name} -> {nw}x{nh} on {tw}x{th}, {dst.stat().st_size} B")


if __name__ == "__main__":
    for src, out in SHOTS:
        make(src, out)
    print("done ->", OUT)
