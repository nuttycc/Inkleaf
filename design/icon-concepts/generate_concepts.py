from __future__ import annotations

import math
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


OUTPUT_DIR = Path(__file__).resolve().parent

INK = "#2B2B2E"
PAPER = "#FFFFFF"
ROUGE = "#9D2933"
MUTED = "#77777F"
CANVAS = "#F2F2F4"


def cubic_bezier(
    start: tuple[float, float],
    control_1: tuple[float, float],
    control_2: tuple[float, float],
    end: tuple[float, float],
    steps: int = 32,
) -> list[tuple[float, float]]:
    points: list[tuple[float, float]] = []
    for index in range(steps + 1):
        t = index / steps
        inverse = 1 - t
        x = (
            inverse**3 * start[0]
            + 3 * inverse**2 * t * control_1[0]
            + 3 * inverse * t**2 * control_2[0]
            + t**3 * end[0]
        )
        y = (
            inverse**3 * start[1]
            + 3 * inverse**2 * t * control_1[1]
            + 3 * inverse * t**2 * control_2[1]
            + t**3 * end[1]
        )
        points.append((x, y))
    return points


def scaled(points: list[tuple[float, float]], scale: float) -> list[tuple[int, int]]:
    return [(round(x * scale), round(y * scale)) for x, y in points]


def make_base(size: int = 1024) -> tuple[Image.Image, ImageDraw.ImageDraw, float]:
    image = Image.new("RGB", (size, size), INK)
    return image, ImageDraw.Draw(image), size / 1024


def ink_leaf(size: int = 1024) -> Image.Image:
    image, draw, scale = make_base(size)

    outline = []
    outline += cubic_bezier((505, 746), (209, 675), (171, 470), (506, 258))
    outline += cubic_bezier((506, 258), (815, 380), (853, 608), (505, 746))[1:]
    draw.polygon(scaled(outline, scale), fill=PAPER)

    vein = cubic_bezier((492, 704), (516, 585), (512, 430), (506, 302), steps=50)
    draw.line(scaled(vein, scale), fill=INK, width=round(34 * scale), joint="curve")

    branch_1 = cubic_bezier((510, 510), (445, 492), (405, 454), (370, 405), steps=22)
    branch_2 = cubic_bezier((511, 442), (570, 428), (618, 390), (648, 350), steps=22)
    draw.line(scaled(branch_1, scale), fill=INK, width=round(24 * scale))
    draw.line(scaled(branch_2, scale), fill=INK, width=round(24 * scale))

    stem = cubic_bezier((495, 728), (484, 755), (463, 782), (441, 798), steps=24)
    draw.line(scaled(stem, scale), fill=ROUGE, width=round(34 * scale), joint="curve")
    return image


def page_leaf(size: int = 1024) -> Image.Image:
    image, draw, scale = make_base(size)

    left_page = []
    left_page += cubic_bezier((500, 376), (422, 338), (332, 338), (278, 374))
    left_page += cubic_bezier((278, 374), (286, 480), (280, 590), (320, 672))[1:]
    left_page += cubic_bezier((320, 672), (394, 658), (458, 678), (500, 720))[1:]
    left_page += cubic_bezier((500, 720), (498, 580), (498, 444), (500, 376))[1:]

    right_page = []
    right_page += cubic_bezier((524, 376), (602, 338), (692, 338), (746, 374))
    right_page += cubic_bezier((746, 374), (738, 480), (744, 590), (704, 672))[1:]
    right_page += cubic_bezier((704, 672), (630, 658), (566, 678), (524, 720))[1:]
    right_page += cubic_bezier((524, 720), (526, 580), (526, 444), (524, 376))[1:]

    draw.polygon(scaled(left_page, scale), fill=PAPER)
    draw.polygon(scaled(right_page, scale), fill=PAPER)

    left_fold = cubic_bezier((458, 414), (398, 390), (338, 394), (310, 424), steps=24)
    right_fold = cubic_bezier((566, 414), (626, 390), (686, 394), (714, 424), steps=24)
    draw.line(scaled(left_fold, scale), fill=INK, width=round(18 * scale))
    draw.line(scaled(right_fold, scale), fill=INK, width=round(18 * scale))

    page_corner = [(680, 348), (746, 374), (686, 410)]
    draw.polygon(scaled(page_corner, scale), fill=ROUGE)
    return image


def ink_drop_page(size: int = 1024) -> Image.Image:
    image, draw, scale = make_base(size)

    drop = []
    drop += cubic_bezier((512, 240), (474, 330), (338, 470), (338, 610))
    drop += cubic_bezier((338, 610), (338, 735), (420, 800), (512, 800))[1:]
    drop += cubic_bezier((512, 800), (604, 800), (686, 735), (686, 610))[1:]
    drop += cubic_bezier((686, 610), (686, 470), (550, 330), (512, 240))[1:]
    draw.polygon(scaled(drop, scale), fill=PAPER)

    page = [
        (430, 474),
        (570, 474),
        (622, 526),
        (622, 680),
        (430, 680),
    ]
    draw.rounded_rectangle(
        (
            round(414 * scale),
            round(458 * scale),
            round(638 * scale),
            round(696 * scale),
        ),
        radius=round(24 * scale),
        fill=INK,
    )
    draw.polygon(scaled(page, scale), fill=INK)

    fold = [(570, 474), (622, 526), (570, 526)]
    draw.polygon(scaled(fold, scale), fill=ROUGE)
    draw.line(
        scaled([(456, 570), (580, 570)], scale),
        fill=PAPER,
        width=round(18 * scale),
    )
    draw.line(
        scaled([(456, 622), (548, 622)], scale),
        fill=PAPER,
        width=round(18 * scale),
    )
    return image


def squircle_mask(size: int, radius_ratio: float = 0.225) -> Image.Image:
    mask = Image.new("L", (size, size), 0)
    draw = ImageDraw.Draw(mask)
    radius = round(size * radius_ratio)
    draw.rounded_rectangle((0, 0, size - 1, size - 1), radius=radius, fill=255)
    return mask


def circle_mask(size: int) -> Image.Image:
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, size - 1, size - 1), fill=255)
    return mask


def masked(icon: Image.Image, mask: Image.Image) -> Image.Image:
    result = icon.convert("RGBA")
    result.putalpha(mask)
    return result


def monochrome_leaf_mask(size: int = 1024) -> Image.Image:
    scale = size / 1024
    mask = Image.new("L", (size, size), 0)
    draw = ImageDraw.Draw(mask)

    outline = []
    outline += cubic_bezier((505, 746), (209, 675), (171, 470), (506, 258))
    outline += cubic_bezier((506, 258), (815, 380), (853, 608), (505, 746))[1:]
    draw.polygon(scaled(outline, scale), fill=255)

    vein = cubic_bezier((492, 704), (516, 585), (512, 430), (506, 302), steps=50)
    draw.line(scaled(vein, scale), fill=0, width=round(34 * scale), joint="curve")

    stem = cubic_bezier((495, 728), (484, 755), (463, 782), (441, 798), steps=24)
    draw.line(scaled(stem, scale), fill=255, width=round(34 * scale), joint="curve")
    return mask


def themed_icon(size: int, foreground: str, background: str) -> Image.Image:
    icon = Image.new("RGBA", (size, size), background)
    mark = Image.new("RGBA", (size, size), foreground)
    mark.putalpha(monochrome_leaf_mask(size))
    icon.alpha_composite(mark)
    return icon


def load_font(size: int, bold: bool = False) -> ImageFont.FreeTypeFont | ImageFont.ImageFont:
    candidates = [
        Path("C:/Windows/Fonts/msyhbd.ttc" if bold else "C:/Windows/Fonts/msyh.ttc"),
        Path("C:/Windows/Fonts/segoeuib.ttf" if bold else "C:/Windows/Fonts/segoeui.ttf"),
    ]
    for candidate in candidates:
        if candidate.exists():
            return ImageFont.truetype(str(candidate), size=size)
    return ImageFont.load_default()


def centered_text(
    draw: ImageDraw.ImageDraw,
    box: tuple[int, int, int, int],
    value: str,
    font: ImageFont.ImageFont,
    fill: str,
) -> None:
    bounds = draw.textbbox((0, 0), value, font=font)
    width = bounds[2] - bounds[0]
    height = bounds[3] - bounds[1]
    x = box[0] + (box[2] - box[0] - width) / 2
    y = box[1] + (box[3] - box[1] - height) / 2 - bounds[1]
    draw.text((x, y), value, font=font, fill=fill)


def build_comparison(concepts: list[tuple[str, str, Image.Image]]) -> Image.Image:
    width, height = 1680, 1060
    sheet = Image.new("RGB", (width, height), CANVAS)
    draw = ImageDraw.Draw(sheet)
    title_font = load_font(48, bold=True)
    subtitle_font = load_font(24)
    label_font = load_font(30, bold=True)
    note_font = load_font(21)

    draw.text((90, 66), "Inkleaf 应用图标草案", font=title_font, fill=INK)
    draw.text(
        (92, 132),
        "同一配色下比较图形；下方展示 Android 常见蒙版与小尺寸效果",
        font=subtitle_font,
        fill=MUTED,
    )

    column_width = 480
    column_gap = 30
    start_x = 90
    icon_size = 400

    for index, (title, note, icon) in enumerate(concepts):
        x = start_x + index * (column_width + column_gap)
        y = 220

        large = icon.resize((icon_size, icon_size), Image.Resampling.LANCZOS)
        large = masked(large, squircle_mask(icon_size))
        sheet.paste(large, (x + 40, y), large)

        centered_text(draw, (x, 646, x + column_width, 694), title, label_font, INK)
        centered_text(draw, (x + 18, 696, x + column_width - 18, 740), note, note_font, MUTED)

        preview_size = 96
        preview_y = 790
        small_icon = icon.resize((preview_size, preview_size), Image.Resampling.LANCZOS)
        circle = masked(small_icon, circle_mask(preview_size))
        squircle = masked(small_icon, squircle_mask(preview_size))
        sheet.paste(circle, (x + 116, preview_y), circle)
        sheet.paste(squircle, (x + 268, preview_y), squircle)

        tiny_size = 48
        tiny = icon.resize((tiny_size, tiny_size), Image.Resampling.LANCZOS)
        tiny = masked(tiny, circle_mask(tiny_size))
        sheet.paste(tiny, (x + 216, 918), tiny)

    return sheet


def build_final_preview(icon: Image.Image) -> Image.Image:
    width, height = 1500, 880
    preview = Image.new("RGB", (width, height), CANVAS)
    draw = ImageDraw.Draw(preview)
    title_font = load_font(52, bold=True)
    subtitle_font = load_font(25)
    label_font = load_font(25, bold=True)
    note_font = load_font(21)

    draw.text((86, 64), "Inkleaf 正式应用图标", font=title_font, fill=INK)
    draw.text(
        (88, 136),
        "墨水叶片 · Android 自适应图标与主题单色图标预览",
        font=subtitle_font,
        fill=MUTED,
    )

    large_size = 470
    large = icon.resize((large_size, large_size), Image.Resampling.LANCZOS)
    large = masked(large, squircle_mask(large_size))
    preview.paste(large, (86, 224), large)
    centered_text(draw, (86, 720, 556, 764), "标准方圆蒙版", label_font, INK)

    circle_size = 230
    circle = icon.resize((circle_size, circle_size), Image.Resampling.LANCZOS)
    circle = masked(circle, circle_mask(circle_size))
    preview.paste(circle, (632, 224), circle)
    centered_text(draw, (616, 474, 878, 518), "圆形蒙版", label_font, INK)

    small_sizes = [96, 64, 48]
    x = 620
    for small_size in small_sizes:
        small = icon.resize((small_size, small_size), Image.Resampling.LANCZOS)
        small = masked(small, circle_mask(small_size))
        preview.paste(small, (x, 590 + (96 - small_size) // 2), small)
        centered_text(
            draw,
            (x - 8, 700, x + small_size + 8, 738),
            f"{small_size}px",
            note_font,
            MUTED,
        )
        x += small_size + 58

    themed_samples = [
        ("#E8DEF8", "#493E5A"),
        ("#C4EED0", "#24543A"),
        ("#FFDAD6", "#73342F"),
        ("#D2E4FF", "#334F73"),
    ]
    themed_size = 166
    themed_x = 994
    themed_y = 248
    for index, (foreground, background) in enumerate(themed_samples):
        sample = themed_icon(themed_size, foreground, background)
        sample = masked(sample, circle_mask(themed_size))
        row = index // 2
        column = index % 2
        px = themed_x + column * 194
        py = themed_y + row * 194
        preview.paste(sample, (px, py), sample)

    centered_text(draw, (970, 656, 1382, 704), "Android 主题图标", label_font, INK)
    centered_text(
        draw,
        (950, 708, 1402, 754),
        "由系统壁纸配色自动着色",
        note_font,
        MUTED,
    )
    return preview


def main() -> None:
    concepts = [
        ("1  墨水叶片", "名字识别最强，安静、有个性", ink_leaf()),
        ("2  展开书页", "阅读含义最直观，亲切稳定", page_leaf()),
        ("3  墨滴书页", "更现代、更像工具型阅读器", ink_drop_page()),
    ]

    for index, (_, _, icon) in enumerate(concepts, start=1):
        icon.resize((512, 512), Image.Resampling.LANCZOS).save(
            OUTPUT_DIR / f"concept-{index}.png"
        )

    build_comparison(concepts).save(OUTPUT_DIR / "inkleaf-icon-concepts.png")

    final_dir = OUTPUT_DIR / "final"
    final_dir.mkdir(exist_ok=True)
    final_icon = ink_leaf()
    final_icon.resize((512, 512), Image.Resampling.LANCZOS).save(
        final_dir / "inkleaf-play-store-512.png"
    )
    build_final_preview(final_icon).save(final_dir / "inkleaf-icon-preview.png")


if __name__ == "__main__":
    main()
