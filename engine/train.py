"""Train YOLO26 on the Roboflow ore dataset (spec: "Training Specification").

Export your Roboflow dataset in YOLO format (it includes data.yaml), then:

    python train.py --data path/to/data.yaml

The spec recommends training both yolo26n and yolo26s and comparing mAP:

    python train.py --data path/to/data.yaml --model yolo26n.pt --name yolo26n_ores
    python train.py --data path/to/data.yaml --model yolo26s.pt --name yolo26s_ores

Expected on an RTX 4060 Ti: ~15-30 minutes for 100 epochs.
"""
import argparse

from ultralytics import YOLO


def main() -> None:
    p = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    p.add_argument("--data", required=True, help="Path to data.yaml exported by Roboflow")
    p.add_argument(
        "--model",
        default="yolo26s.pt",
        help="Pretrained weights to fine-tune (yolo26s.pt primary, yolo26n.pt fallback)",
    )
    p.add_argument("--epochs", type=int, default=100)
    p.add_argument("--imgsz", type=int, default=640)
    p.add_argument("--batch", type=int, default=16, help="16 is safe for 4060 Ti VRAM")
    p.add_argument("--device", default="0", help="GPU index, or 'cpu'")
    p.add_argument("--name", default="yolo26s_ores", help="Run name under the minesight_runs/ folder")
    args = p.parse_args()

    model = YOLO(args.model)
    model.train(
        data=args.data,
        epochs=args.epochs,
        imgsz=args.imgsz,
        batch=args.batch,
        device=args.device,
        # Spec says project="minesight", but that name is taken by the Python
        # package directory; keep training output out of the import path.
        project="minesight_runs",
        name=args.name,
    )

    # Validate the best checkpoint so runs are easy to compare.
    metrics = model.val()
    # Ask the trainer for the real path: ultralytics may nest relative project
    # names under its runs_dir setting (e.g. runs/detect/<project>/<name>).
    best = model.trainer.best
    print(f"\nValidation mAP50-95: {metrics.box.map:.4f}   mAP50: {metrics.box.map50:.4f}")
    print(f"Best weights: {best}")
    print(f'Run the engine with:  python -m minesight --weights "{best}"')


if __name__ == "__main__":
    main()
