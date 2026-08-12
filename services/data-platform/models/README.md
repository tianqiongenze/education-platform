# AIOps ONNX Models (D-189)

This directory documents the ONNX models used by the AIOps subsystem
(`com.ep.data.aiops`). It is **documentation only** — no `.onnx` files
are shipped in the repository (Iron Law 9: Real implementation, no fake
model files).

## Model loading

`ModelManager` loads `.onnx` files from the directory specified by:

1. System property `ep.aiops.models.dir`, or
2. Environment variable `EP_AIOPS_MODELS_DIR`, or
3. Default: `/opt/ep/models/aiops`

The manager is a no-op in dev/CI mode: when `EP_DEV_ONLY` is unset,
blank, or `"true"`, no ONNX sessions are created and all AIOps components
fall back to their pure-Java implementations.

## Model files

| Model name              | File               | Shape (input)          | Shape (output)         | Used by                |
|-------------------------|--------------------|------------------------|------------------------|------------------------|
| `capacity-lstm`         | `capacity_lstm.onnx` | `[1, 14, 1]` (seq, feat) | `[1, forecastDays]`    | `CapacityForecaster`   |
| `anomaly-if`            | `anomaly_if.onnx`   | `[1, N, D]` (samples, dims) | `[1, N]` (scores)     | `AnomalyDetector`      |
| `schedule-gbt`          | `schedule_gbt.onnx` | `[1, 6]` (features)    | `[1, 4]` (parallelism, cores, memory, saving) | `ScheduleOptimizer` |

## Feature definitions

### capacity-lstm

| Index | Feature             | Scale        |
|-------|---------------------|--------------|
| 0     | EWMA-smoothed value | raw          |

Input sequence length = 14 days, feature dim = 1.

### anomaly-if

Features are the raw metric dimensions per sample. The model outputs a
per-sample anomaly score in `[0, 1]`. Samples with score > 0.7 are
flagged as anomalies.

### schedule-gbt

| Index | Feature               | Scale            |
|-------|-----------------------|------------------|
| 0     | backpressureRatio     | 0.0–1.0          |
| 1     | parallelism           | raw              |
| 2     | tmCores               | raw              |
| 3     | tmMemoryMb            | raw              |
| 4     | inputRecords / 1M     | millions         |
| 5     | durationMs / 1000     | seconds          |

Output: `[newParallelism, newTmCores, newTmMemoryMb, costSavingPct]`.

## Training

Models are trained offline using Python (PyTorch / scikit-learn) and
exported to ONNX format. The training pipeline and datasets are managed
by the ML platform team and are not part of this repository.

## Adding a model in production

1. Train and export the model to `.onnx`.
2. Copy the file to `/opt/ep/models/aiops/` (or the configured
   `EP_AIOPS_MODELS_DIR`).
3. Set `EP_DEV_ONLY=false` in the environment.
4. Restart the data-platform service.
5. Verify via the `/actuator/health` endpoint — `ModelManager` logs
   loaded models at startup.
