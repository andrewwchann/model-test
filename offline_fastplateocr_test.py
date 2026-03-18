from __future__ import annotations

import argparse
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

import numpy as np
import onnxruntime as ort
from PIL import Image


@dataclass
class PlateConfig:
    max_plate_slots: int
    alphabet: str
    pad_char: str
    img_height: int
    img_width: int
    keep_aspect_ratio: bool
    image_color_mode: str


@dataclass
class SlotPrediction:
    slot_index: int
    class_index: int
    character: str
    confidence: float


@dataclass
class DecodeResult:
    raw_text: str
    final_text: str
    average_confidence: float
    slots: list[SlotPrediction]
    variant_name: str


def parse_simple_yaml(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.split("#", 1)[0].strip()
        if not line or ":" not in line:
            continue
        key, value = line.split(":", 1)
        values[key.strip()] = value.strip()
    return values


def load_config(path: Path) -> PlateConfig:
    values = parse_simple_yaml(path)

    def required(key: str) -> str:
        if key not in values:
            raise ValueError(f"Missing {key!r} in {path}")
        return values[key]

    alphabet = required("alphabet").strip("'\"")
    pad_char = required("pad_char").strip("'\"")
    return PlateConfig(
        max_plate_slots=int(required("max_plate_slots")),
        alphabet=alphabet,
        pad_char=pad_char,
        img_height=int(required("img_height")),
        img_width=int(required("img_width")),
        keep_aspect_ratio=required("keep_aspect_ratio").lower() == "true",
        image_color_mode=required("image_color_mode").strip("'\"").lower(),
    )


def validate_contract(session: ort.InferenceSession, config: PlateConfig) -> tuple[str, str]:
    input_meta = session.get_inputs()[0]
    output_names = {out.name for out in session.get_outputs()}
    if "plate" not in output_names:
        raise ValueError("Model missing 'plate' output")
    if input_meta.type != "tensor(uint8)":
        raise ValueError(f"Expected uint8 input, got {input_meta.type}")
    if input_meta.shape[1:] != [config.img_height, config.img_width, 3]:
        raise ValueError(f"Expected input [batch, {config.img_height}, {config.img_width}, 3], got {input_meta.shape}")
    plate_meta = next(out for out in session.get_outputs() if out.name == "plate")
    if plate_meta.shape[1:] != [config.max_plate_slots, len(config.alphabet)]:
        raise ValueError(
            f"Expected plate output [batch, {config.max_plate_slots}, {len(config.alphabet)}], got {plate_meta.shape}"
        )
    return input_meta.name, plate_meta.name


def build_variants(image: Image.Image) -> list[tuple[str, Image.Image]]:
    variants: list[tuple[str, Image.Image]] = [("full", image)]
    variants.append(("scaled2x", image.resize((image.width * 2, image.height * 2), Image.Resampling.BILINEAR)))

    for name, top_fraction, bottom_fraction in (
        ("band_18_90", 0.18, 0.90),
        ("band_28_82", 0.28, 0.82),
        ("band_34_76", 0.34, 0.76),
        ("band_26_82", 0.26, 0.82),
        ("band_34_78", 0.34, 0.78),
    ):
        top = max(0, min(image.height - 1, int(image.height * top_fraction)))
        bottom = max(top + 1, min(image.height, int(image.height * bottom_fraction)))
        if bottom > top:
            variants.append((name, image.crop((0, top, image.width, bottom))))

    deduped: list[tuple[str, Image.Image]] = []
    seen: set[tuple[int, int, str]] = set()
    for name, variant in variants:
        key = (variant.width, variant.height, name)
        if key not in seen:
            seen.add(key)
            deduped.append((name, variant))
    return deduped


def preprocess(image: Image.Image, config: PlateConfig) -> np.ndarray:
    rgb = image.convert("RGB")
    resized = rgb.resize((config.img_width, config.img_height), Image.Resampling.BILINEAR)
    data = np.asarray(resized, dtype=np.uint8)
    return np.expand_dims(data, axis=0)


def decode(logits: np.ndarray, config: PlateConfig, variant_name: str) -> DecodeResult:
    slots: list[SlotPrediction] = []
    chars: list[str] = []
    for slot_index in range(logits.shape[0]):
        slot = logits[slot_index]
        class_index = int(np.argmax(slot))
        confidence = float(slot[class_index])
        character = config.alphabet[class_index]
        chars.append(character)
        slots.append(
            SlotPrediction(
                slot_index=slot_index,
                class_index=class_index,
                character=character,
                confidence=confidence,
            )
        )

    raw_text = "".join(chars)
    final_text = raw_text.rstrip(config.pad_char).strip()
    avg_conf = float(np.mean([slot.confidence for slot in slots])) if slots else 0.0
    return DecodeResult(
        raw_text=raw_text,
        final_text=final_text,
        average_confidence=avg_conf,
        slots=slots,
        variant_name=variant_name,
    )


def choose_best(results: Iterable[DecodeResult]) -> DecodeResult | None:
    results = list(results)
    if not results:
        return None
    return max(results, key=lambda r: (r.average_confidence + len(r.final_text) * 0.05, len(r.final_text)))


def iter_images(path: Path) -> list[Path]:
    if path.is_file():
        return [path]
    exts = {".jpg", ".jpeg", ".png", ".bmp", ".webp"}
    return sorted(p for p in path.rglob("*") if p.is_file() and p.suffix.lower() in exts)


def main() -> None:
    parser = argparse.ArgumentParser(description="Run FastPlateOCR ONNX offline against images or a directory.")
    parser.add_argument("input", nargs="?", default="car-photos/cropped", help="Image file or directory to OCR")
    parser.add_argument("--model", default="app/src/main/assets/ocr/plate_ocr.onnx", help="Path to ONNX model")
    parser.add_argument("--config", default="app/src/main/assets/ocr/plate_config.yaml", help="Path to YAML config")
    parser.add_argument("--verbose", action="store_true", help="Print per-slot predictions")
    args = parser.parse_args()

    # # Common placeholder from docs/examples; map it to the repo sample folder.
    # if args.input in {"path\\to\\crop_dir", "path/to/crop_dir"}:
    #     args.input = "car-photos"

    config = load_config(Path(args.config))
    session = ort.InferenceSession(str(Path(args.model)), providers=["CPUExecutionProvider"])
    input_name, plate_name = validate_contract(session, config)

    print(f"model={args.model}")
    print(f"config={args.config}")
    print(
        f"contract input=[1,{config.img_height},{config.img_width},3] uint8 "
        f"plate=[1,{config.max_plate_slots},{len(config.alphabet)}] alphabet={config.alphabet}"
    )

    image_paths = iter_images(Path(args.input))
    if not image_paths:
        raise SystemExit(f"No images found in {args.input}")

    for image_path in image_paths:
        image = Image.open(image_path)
        results: list[DecodeResult] = []
        for variant_name, variant in build_variants(image):
            tensor = preprocess(variant, config)
            outputs = session.run([plate_name], {input_name: tensor})
            plate_logits = outputs[0][0]
            results.append(decode(plate_logits, config, variant_name))

        best = choose_best(results)
        if best is None:
            print(f"{image_path.name}: no result")
            continue

        print(
            f"{image_path.name}: text='{best.final_text}' raw='{best.raw_text}' "
            f"avg_conf={best.average_confidence:.3f} variant={best.variant_name}"
        )
        if args.verbose:
            for slot in best.slots:
                print(
                    f"  slot={slot.slot_index} char='{slot.character}' "
                    f"class={slot.class_index} conf={slot.confidence:.3f}"
                )


if __name__ == "__main__":
    main()
