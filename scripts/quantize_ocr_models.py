#!/usr/bin/env python3
# 将 PaddleOCR det/rec 的 FP32 ONNX 模型量化为 INT8，用于压缩 APK 体积。
#
# 用法：
#   pip install onnxruntime onnx
#   python3 scripts/quantize_ocr_models.py [--static] [--calib-dir DIR]
#
# 默认走 dynamic 量化（weight-only，无需校准数据）。
# --static 走 static 量化，需要 --calib-dir 提供漫画页面图作为校准样本。
# 量化产物默认写到 /tmp/inkleaf-quantized/，可用 --out-dir 覆盖。
# 验证精度后再手动替换 app/src/main/assets/ocr/ppocrv6_small/ 下的模型。

from __future__ import annotations

import argparse
import os
import sys
from collections import Counter
from pathlib import Path

import onnx
from onnxruntime.quantization import QuantFormat, QuantType, quantize_dynamic, quantize_static, CalibrationDataReader


ASSETS_DIR = Path("app/src/main/assets/ocr/ppocrv6_small")
MODELS = ("det", "rec")


def list_operators(model_path: Path) -> Counter:
    """统计 ONNX 模型用到的算子类型及出现次数。"""
    model = onnx.load(str(model_path))
    counts: Counter[str] = Counter()
    for node in model.graph.node:
        counts[node.op_type] += 1
    return counts


def human_size(num_bytes: int) -> str:
    return f"{num_bytes / 1024 / 1024:.2f} MB"


def quantize_one_dynamic(src: Path, dst: Path) -> None:
    # weight-only 量化：权重 INT8，激活保持 FP32。无需校准数据，最稳。
    quantize_dynamic(
        model_input=str(src),
        model_output=str(dst),
        weight_type=QuantType.QInt8,
        per_channel=True,  # 按通道量化，对 Conv 掉点更小
    )


class _ImageCalibReader(CalibrationDataReader):
    """static 量化用的校准数据读取器：把图片预处理成 det/rec 的输入张量。"""

    def __init__(self, calib_dir: Path, model_name: str) -> None:
        # 简化实现：仅占位，真正 static 量化需要按 det (NCHW 动态) / rec (N,3,48,W) 预处理。
        raise NotImplementedError(
            "static 量化需要实现 det/rec 的预处理校准流。本次先用 dynamic 验证体积，"
            "精度不达标再补校准数据。"
        )


def main() -> int:
    parser = argparse.ArgumentParser(description="量化 PaddleOCR det/rec ONNX 模型")
    parser.add_argument("--out-dir", default="/tmp/inkleaf-quantized", help="量化产物输出目录")
    parser.add_argument("--static", action="store_true", help="改用 static 量化（需要校准数据）")
    parser.add_argument("--calib-dir", help="static 量化用的校准图片目录")
    args = parser.parse_args()

    if not ASSETS_DIR.exists():
        print(f"错误：找不到 assets 目录 {ASSETS_DIR}", file=sys.stderr)
        return 1

    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    total_before = 0
    total_after = 0

    for name in MODELS:
        src = ASSETS_DIR / name / "inference.onnx"
        if not src.exists():
            print(f"跳过 {name}：{src} 不存在", file=sys.stderr)
            continue

        dst = out_dir / name / "inference.onnx"
        dst.parent.mkdir(parents=True, exist_ok=True)

        ops = list_operators(src)
        size_before = src.stat().st_size
        total_before += size_before
        print(f"=== {name} ===")
        print(f"  原始大小: {human_size(size_before)}")
        print(f"  算子分布: {dict(ops.most_common())}")

        if args.static:
            if not args.calib_dir:
                print("  错误：--static 需要 --calib-dir", file=sys.stderr)
                return 1
            reader = _ImageCalibReader(Path(args.calib_dir), name)
            quantize_static(
                model_input=str(src),
                model_output=str(dst),
                calibration_data_reader=reader,
                quant_format=QuantFormat.QDQ,
                per_channel=True,
                weight_type=QuantType.QInt8,
            )
        else:
            quantize_one_dynamic(src, dst)

        size_after = dst.stat().st_size
        total_after += size_after
        ops_after = list_operators(dst)
        ratio = (1 - size_after / size_before) * 100 if size_before else 0
        print(f"  量化后大小: {human_size(size_after)}  (省 {ratio:.1f}%)")
        # dynamic 量化会插入 Q/DQ 节点，列出新增的算子便于核对
        new_ops = ops_after - ops
        if new_ops:
            print(f"  新增算子: {dict(new_ops)}")
        print()

    print(f"合计：{human_size(total_before)} -> {human_size(total_after)}  "
          f"(省 {human_size(total_before - total_after)})")
    print(f"\n量化产物在 {out_dir}。验证精度后替换 {ASSETS_DIR} 下对应文件。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
