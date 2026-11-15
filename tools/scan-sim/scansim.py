#!/usr/bin/env python3
"""Simulated Portal camera frames of a phone showing a QR code, for tuning the scanner.

The Portal's camera cannot be steered, so the scanner is tuned by what it does with each
frame (see QrSearch.scanWhole). Recorded frames are the best evidence (scan_debug, see
CLAUDE.md), but they are pictures of a room and get deleted. This tool makes stand-ins from
a screenshot of the code: it places the screenshot at a distance, makes the screen brighter
than the sensor can hold, blurs it like the fixed-focus lens, clips, and adds noise.

  pip install numpy opencv-python-headless zxing-cpp
  scansim.py score  SCREENSHOT.png CX CY WIDTH          # table: current looks per look
  scansim.py frames SCREENSHOT.png CX CY WIDTH OUTDIR   # PGM frames for the on-device replay

CX CY WIDTH: centre and width of the code in the screenshot, in pixels. The code is assumed
to be 3.4 cm wide on the phone (an album code on a 6.3 inch phone). The decoded text is never
printed. A screenshot of a private album link is private: keep it and the frames out of git.
"""
import os
import sys

import cv2
import numpy as np
import zxingcpp

W, H, SS = 1280, 720, 3
PX_PER_CM_AT_1CM = 576.0  # 1280 px across a 96 degree view
CODE_CM = 3.4


class Sim:
    def __init__(self, shot, cx, cy, width):
        grey = cv2.cvtColor(cv2.imread(shot), cv2.COLOR_BGR2GRAY)
        half = int(width * 0.7)
        found = zxingcpp.read_barcodes(
            np.ascontiguousarray(grey[int(cy) - half:int(cy) + half, int(cx) - half:int(cx) + half]),
            formats=zxingcpp.BarcodeFormat.QRCode,
        )
        if not found:
            raise SystemExit("no QR code at that place in the screenshot")
        self.truth = found[0].text
        self.lin = (grey.astype(np.float32) / 255.0) ** 2.2
        self.c = (float(cx), float(cy))
        self.width = float(width)

    def frame(self, d_cm, bright, sigma, glare=0.03, noise=2.0, motion=0, rot=0.0, seed=0):
        """One grey frame. bright: screen white as a multiple of what saturates the sensor;
        sigma: lens blur in camera pixels; glare: share of the light in a wide halo."""
        rng = np.random.default_rng(seed)
        s = (CODE_CM * PX_PER_CM_AT_1CM / d_cm) / self.width * SS
        cx = W * SS / 2 + rng.uniform(-40, 40) * SS
        cy = H * SS / 2 + rng.uniform(-30, 30) * SS
        m = cv2.getRotationMatrix2D(self.c, rot, s)
        m[0, 2] += cx - self.c[0]
        m[1, 2] += cy - self.c[1]
        pre = cv2.GaussianBlur(self.lin, (0, 0), 0.45 / s) if s < 1 else self.lin
        room = 0.012 + 0.01 * rng.random()
        img = cv2.warpAffine(pre, m, (W * SS, H * SS), flags=cv2.INTER_LINEAR,
                             borderMode=cv2.BORDER_CONSTANT, borderValue=room / bright)
        img *= bright
        img = cv2.GaussianBlur(img, (0, 0), sigma * SS)
        if glare > 0:
            halo = cv2.GaussianBlur(cv2.resize(img, (W, H), interpolation=cv2.INTER_AREA), (0, 0), 9.0)
            img = (1 - glare) * img + glare * cv2.resize(halo, (W * SS, H * SS))
        out = cv2.resize(img, (W, H), interpolation=cv2.INTER_AREA)
        if motion > 1:
            k = np.zeros((motion, motion), np.float32)
            k[motion // 2, :] = 1.0 / motion
            out = cv2.filter2D(out, -1, k)
        out = np.clip(out, 0, 1) ** (1 / 2.2) * 255.0 + rng.normal(0, noise, out.shape)
        return np.clip(out + 0.5, 0, 255).astype(np.uint8)

    def reads(self, img):
        res = zxingcpp.read_barcodes(np.ascontiguousarray(img), formats=zxingcpp.BarcodeFormat.QRCode)
        return any(r.valid and r.text == self.truth for r in res)


# Ports of QrFilters.kt, close enough to compare looks.
def sharpen(img, r, gain2):
    blur = cv2.blur(img, (2 * r + 1, 2 * r + 1), borderType=cv2.BORDER_REPLICATE).astype(np.int32)
    v = img.astype(np.int32)
    return np.clip(v + (v - blur) * gain2 // 2, 0, 255).astype(np.uint8)


def shrink_bright(img, r):
    return cv2.erode(img, np.ones((2 * r + 1, 2 * r + 1), np.uint8), borderType=cv2.BORDER_REPLICATE)


def curve(img):
    return (img.astype(np.uint32) ** 3 // (255 * 255)).astype(np.uint8)


def looks(img, side=360, f=3):
    """The looks of QrSearch.scanWhole, with nothing tracked (close look in the middle)."""
    x, y = (W - side) // 2, (H - side) // 2
    src = img[y:y + side, x:x + side]
    big = cv2.resize(sharpen(src, 4, 3), None, fx=f, fy=f, interpolation=cv2.INTER_LINEAR)
    sharp1 = shrink_bright(sharpen(img, 4, 3), 1)
    plain_big = cv2.resize(src, None, fx=f, fy=f, interpolation=cv2.INTER_LINEAR)
    return {
        "sharp-bloom2": shrink_bright(sharp1, 1),
        "plain": img,
        "close": shrink_bright(big, f),
        "close-bloom2": shrink_bright(big, 2 * f),
        "close-bloom3": shrink_bright(big, 3 * f),
        "close-curve": shrink_bright(curve(plain_big), f - 1),
        "bloom2": shrink_bright(img, 2),
        "bloom3": shrink_bright(img, 3),
        "sharp-bloom1": sharp1,
    }


def grid():
    i = 0
    for kw in (dict(glare=0.03, noise=2.0, motion=0), dict(glare=0.08, noise=4.0, motion=3)):
        for sigma in (0.8, 1.2, 1.7):
            for d in (10, 12, 15, 18, 22):
                for bright in (1, 2, 3, 4, 6, 8, 12, 16):
                    i += 1
                    yield dict(d_cm=d, bright=bright, sigma=sigma, rot=(i % 5) * 3 - 6, seed=i, **kw)


def main():
    if len(sys.argv) < 6:
        raise SystemExit(__doc__)
    sim = Sim(sys.argv[2], float(sys.argv[3]), float(sys.argv[4]), float(sys.argv[5]))
    if sys.argv[1] == "score":
        per, total, n = {}, 0, 0
        for p in grid():
            got = {k: sim.reads(v) for k, v in looks(sim.frame(**p)).items()}
            n += 1
            total += any(got.values())
            for k, v in got.items():
                per[k] = per.get(k, 0) + v
        print(f"{n} frames, {total} read by some look")
        for k, v in sorted(per.items(), key=lambda kv: -kv[1]):
            print(f"  {k:14s} {v}")
    elif sys.argv[1] == "frames":
        out = sys.argv[6]
        os.makedirs(out, exist_ok=True)
        n = 0
        for i, p in enumerate(grid()):
            if i % 7:
                continue
            img = sim.frame(**p)
            # Six copies: the scanner's looks rotate, so each frame gets every look once.
            for copy in range(6):
                with open(os.path.join(out, f"f{n:03d}_{copy}.pgm"), "wb") as fh:
                    fh.write(b"P5\n%d %d\n255\n" % (W, H))
                    fh.write(img.tobytes())
            n += 1
        print(f"{n} frames, six copies each, in {out}")
    else:
        raise SystemExit(__doc__)


if __name__ == "__main__":
    main()
