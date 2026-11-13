"""Package an iPhone device build as an IPA for re-signing by a sideloader."""

from io import BytesIO
from pathlib import Path
import plistlib
import sys
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo

from PIL import Image, ImageDraw


def icon(size):
    image = Image.new("RGB", (216, 216), "#05130E")
    draw = ImageDraw.Draw(image)
    strokes = [
        [(20, 55), (40, 55), (44, 60), (51, 60)],
        [(88, 55), (68, 55), (64, 60), (57, 60)],
        [(54, 46), (54, 38)],
        [(33, 32), (33, 25), (47, 25)],
        [(75, 32), (75, 25), (61, 25)],
        [(36, 75), (72, 75)], [(54, 75), (54, 83)],
        [(24, 41), (30, 41)], [(24, 49), (28, 49)],
        [(84, 41), (78, 41)], [(84, 49), (80, 49)],
    ]
    for points in strokes:
        draw.line([(x * 2, y * 2) for x, y in points], fill="#49FA92", width=5, joint="curve")
    draw.ellipse((96, 96, 120, 120), outline="#49FA92", width=4)
    stream = BytesIO()
    image.resize((size, size), Image.Resampling.LANCZOS).save(stream, "PNG")
    return stream.getvalue()


def package(binary, output):
    plist = plistlib.loads(Path(__file__).with_name("Info.plist").read_bytes())
    app = "Payload/CarHUD.app/"
    with ZipFile(output, "w", compression=ZIP_DEFLATED) as archive:
        executable = ZipInfo(app + plist["CFBundleExecutable"])
        executable.create_system = 3
        executable.external_attr = 0o100755 << 16
        executable.compress_type = ZIP_DEFLATED
        archive.writestr(executable, Path(binary).read_bytes())
        archive.writestr(app + "Info.plist", plistlib.dumps(plist, fmt=plistlib.FMT_BINARY))
        archive.writestr(app + "AppIcon60@2x.png", icon(120))
        archive.writestr(app + "AppIcon60@3x.png", icon(180))
    with ZipFile(output) as archive:
        assert archive.testzip() is None


if __name__ == "__main__":
    package(*sys.argv[1:])
