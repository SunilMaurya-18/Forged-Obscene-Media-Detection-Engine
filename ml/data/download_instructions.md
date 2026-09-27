# Dataset Download Instructions

These benchmarks **cannot be auto-downloaded**. Each requires an access request and
acceptance of the dataset license. Place downloaded archives under `ml/data/raw/`
using the layout below.

> **Policy:** Use only consenting-actor research datasets. Never place real
> non-consensual or explicit imagery in this repository.

---

## Expected directory layout

```
ml/data/raw/
├── faceforensics++/
│   ├── original/          # pristine videos
│   └── manipulated/       # Deepfakes / Face2Face / FaceSwap / NeuralTextures
├── dfdc/
│   ├── real/
│   └── fake/
└── celeb-df/
    ├── Celeb-real/
    └── Celeb-synthesis/
```

---

## FaceForensics++ (primary train/val source)

1. Open https://github.com/ondyari/FaceForensics  
2. Fill out the **dataset request form** linked in that README.  
3. Wait for credentials / download links from the authors.  
4. Download the **c23** (compressed) video set for:
   - `original_sequences`
   - manipulation methods you need (at minimum Deepfakes)
5. Extract into `ml/data/raw/faceforensics++/original/` and
   `ml/data/raw/faceforensics++/manipulated/` (flatten or keep method
   subfolders — `build_splits.py` accepts either).

**Citation:** Rössler et al., FaceForensics++, ICCV 2019.

---

## DFDC (held out — cross-dataset test only)

1. Open https://ai.meta.com/datasets/dfdc/ (or the Kaggle DFDC page).  
2. Accept the competition / dataset terms.  
3. Download a subset of train/test videos (full dump is very large).  
4. Sort into `ml/data/raw/dfdc/real/` and `ml/data/raw/dfdc/fake/` using the
   official labels CSV.

**Do not** use DFDC for training splits in this project — it is reserved for
generalization evaluation (Phase 2).

**Citation:** Dolhansky et al., The DeepFake Detection Challenge Dataset, 2020.

---

## Celeb-DF (held out — cross-dataset test only)

1. Open https://github.com/yuezunli/celeb-deepfakeforensics  
2. Submit the access request / follow the authors’ download instructions.  
3. Place `Celeb-real` and `Celeb-synthesis` under `ml/data/raw/celeb-df/`.

**Do not** mix Celeb-DF into train/val — hold out entirely for testing.

**Citation:** Li et al., Celeb-DF, CVPR 2020.

---

## After download

```bash
# From repo root, with ml/ Python deps installed:
python ml/data/extract_frames.py --input ml/data/raw --output ml/data/frames --fps 1
python ml/data/detect_align_faces.py --input ml/data/frames --output ml/data/crops
python ml/data/build_splits.py --crops ml/data/crops --output ml/data/splits --seed 42
```

For a smoke test without licensed data, see `ml/data/tests/test_pipeline.py`
(synthetic videos + center-crop fallback).
