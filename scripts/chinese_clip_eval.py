"""Offline Chinese-CLIP RN50 evaluation and ONNX parity checks for DropZone."""
import argparse
import hashlib
import json
import os
import time
from pathlib import Path

import numpy as np
import torch
from cn_clip.clip import load_from_name, tokenize
from PIL import Image, ImageOps

DATASETS = {"pxhere": 50, "real-life": 63}
CHECKPOINT_SHA256 = "b196ee3ee528b70be1158ab1aafb1d2f1c801ad2d9ffb3bae31b0d305f82fc88"

def photos(root, corpus):
    extensions = {".jpg", ".jpeg", ".png", ".webp", ".heic", ".heif"}
    return sorted(
        (p for p in (root / corpus).iterdir()
         if p.is_file() and p.suffix.lower() in extensions
         and (corpus != "pxhere" or p.name[:2].isdigit())),
        key=lambda p: p.name.lower(),
    )

def encode_photos(model, preprocess, paths):
    batches = []
    started = time.perf_counter()
    for offset in range(0, len(paths), 8):
        tensors = []
        for path in paths[offset:offset + 8]:
            with Image.open(path) as image:
                tensors.append(preprocess(ImageOps.exif_transpose(image).convert("RGB")))
        with torch.inference_mode():
            batches.append(torch.nn.functional.normalize(model.encode_image(torch.stack(tensors)).float()).numpy())
    return np.concatenate(batches), time.perf_counter() - started

class ImageEncoder(torch.nn.Module):
    def __init__(self, model):
        super().__init__()
        self.model = model

    def forward(self, image):
        return torch.nn.functional.normalize(self.model.encode_image(image).float())

class TextEncoder(torch.nn.Module):
    def __init__(self, model):
        super().__init__()
        self.model = model

    def forward(self, tokens):
        return torch.nn.functional.normalize(self.model.encode_text(tokens).float())

def export_and_check(model, preprocess, example, output):
    import onnxruntime as ort
    with Image.open(example) as image:
        pixels = preprocess(ImageOps.exif_transpose(image).convert("RGB")).unsqueeze(0)
    tokens = tokenize(["雨后的街道"])
    session_options = ort.SessionOptions()
    session_options.intra_op_num_threads = 4
    stats = {}
    for key, module, name, tensor in [
        ("image", ImageEncoder(model), "image", pixels),
        ("text", TextEncoder(model), "tokens", tokens),
    ]:
        module.eval()
        path = output / (key + ".onnx")
        torch.onnx.export(module, tensor, str(path), input_names=[name], output_names=["embedding"], opset_version=17)
        session = ort.InferenceSession(str(path), sess_options=session_options, providers=["CPUExecutionProvider"])
        with torch.inference_mode():
            expected = module(tensor).numpy()
        result = session.run(None, {name: tensor.numpy()})[0]
        samples = []
        for _ in range(10):
            start = time.perf_counter()
            session.run(None, {name: tensor.numpy()})
            samples.append((time.perf_counter() - start) * 1000)
        np.testing.assert_allclose(result, expected, rtol=2e-3, atol=2e-4)
        stats[key] = {"bytes": path.stat().st_size, "cosine": float(np.sum(result * expected)), "p50_ms": float(np.median(samples))}
    return stats

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--photos", type=Path, required=True)
    parser.add_argument("--checkpoint", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--queries", type=Path)
    parser.add_argument("--export", action="store_true")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    with args.checkpoint.open("rb") as checkpoint:
        digest = hashlib.sha256()
        for chunk in iter(lambda: checkpoint.read(1024 * 1024), b""):
            digest.update(chunk)
    if digest.hexdigest() != CHECKPOINT_SHA256:
        raise ValueError("Checkpoint must be the verified OFA-Sys Chinese-CLIP RN50 weights.")
    torch.set_num_threads(min(6, os.cpu_count() or 1))
    model, preprocess = load_from_name(str(args.checkpoint), device="cpu", vision_model_name="RN50", text_model_name="RBT3-chinese", input_resolution=224)
    model.eval()
    sets = {}
    paths = {}
    for corpus, expected_count in DATASETS.items():
        corpus_paths = photos(args.photos, corpus)
        if len(corpus_paths) != expected_count:
            raise ValueError(f"{corpus}: expected {expected_count} photos, found {len(corpus_paths)}")
        paths[corpus] = corpus_paths
        vectors, seconds = encode_photos(model, preprocess, corpus_paths)
        sets[corpus] = {"count": len(vectors), "dimensions": vectors.shape[1], "seconds": seconds}
        np.save(args.output / (corpus + ".npy"), vectors, allow_pickle=False)
        print(corpus, sets[corpus], flush=True)
    stats = {"datasets": sets}
    if args.queries:
        spec = json.loads(args.queries.read_text(encoding="utf-8"))
        for corpus, cases in spec.items():
            with torch.inference_mode():
                vectors = torch.nn.functional.normalize(model.encode_text(tokenize([case["text"] for case in cases])).float()).numpy()
            ranks = vectors @ np.load(args.output / (corpus + ".npy"), allow_pickle=False).T
            sets[corpus]["queries"] = [
                {"text": case["text"], "ranked_ids": (np.argsort(-row)[:10] + 1).tolist(), "scores": sorted(row.tolist(), reverse=True)[:10]}
                for case, row in zip(cases, ranks)
            ]
            labeled = [(case, result) for case, result in zip(cases, sets[corpus]["queries"]) if case.get("expected_ids")]
            if labeled:
                sets[corpus]["reference_recall_at_10"] = sum(
                    len(set(case["expected_ids"]).intersection(result["ranked_ids"])) / len(case["expected_ids"])
                    for case, result in labeled
                ) / len(labeled)
    destination = args.output / "metrics.json"
    destination.write_text(json.dumps(stats, ensure_ascii=False, indent=2), encoding="utf-8")
    if args.export:
        stats["onnx"] = export_and_check(model, preprocess, paths["pxhere"][0], args.output)
        print("ONNX", stats["onnx"], flush=True)
    destination.write_text(json.dumps(stats, ensure_ascii=False, indent=2), encoding="utf-8")

if __name__ == "__main__":
    main()
