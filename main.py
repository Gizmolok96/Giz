"""
AI Studio — Desktop Image & Video Generator
Works fully offline after setup. GPU-accelerated via CUDA.
"""

import sys
import os
import gc
import json
import time
import shutil
import logging
import contextlib
import subprocess
import threading
import warnings
from pathlib import Path

# ─── Suppress noisy third-party warnings before any heavy imports ─────────────
# triton is an optional JIT compiler; its absence doesn't affect generation
warnings.filterwarnings("ignore", message=".*triton.*")
warnings.filterwarnings("ignore", category=UserWarning, module="xformers")
# Silence "Fast suffix deprecated" and similar deprecation notices from HF libs
warnings.filterwarnings("ignore", category=FutureWarning)
warnings.filterwarnings("ignore", category=DeprecationWarning)
# Suppress torch flop-counter log about triton
logging.getLogger("torch").setLevel(logging.ERROR)

# Suppress HuggingFace verbose info logs (model loading progress etc.)
os.environ.setdefault("TRANSFORMERS_VERBOSITY", "error")
os.environ.setdefault("DIFFUSERS_VERBOSITY", "error")

# ─── CUDA allocator config — MUST be set before torch is imported ─────────────
# expandable_segments: allocator grows segments on demand instead of
# pre-reserving large contiguous blocks → drastically less fragmentation
os.environ.setdefault("PYTORCH_CUDA_ALLOC_CONF", "expandable_segments:True")

# ─── Dependency check BEFORE heavy imports ────────────────────────────────────
REQUIRED = {
    "torch": "torch",
    "diffusers": "diffusers",
    "transformers": "transformers",
    "PIL": "Pillow",
    "PyQt6": "PyQt6",
    "cv2": "opencv-python",
    "imageio": "imageio",
    "accelerate": "accelerate",
    "safetensors": "safetensors",
    "huggingface_hub": "huggingface_hub",
}

def check_deps():
    missing = []
    for module, pkg in REQUIRED.items():
        try:
            __import__(module)
        except ImportError:
            missing.append(pkg)
    return missing

missing_deps = check_deps()
if missing_deps:
    try:
        from PyQt6.QtWidgets import QApplication, QMessageBox
        app = QApplication(sys.argv)
        msg = QMessageBox()
        msg.setWindowTitle("AI Studio — Отсутствуют зависимости")
        msg.setText(
            f"Не найдены библиотеки:\n{', '.join(missing_deps)}\n\n"
            "Пожалуйста, запустите install.bat для автоматической установки."
        )
        msg.setIcon(QMessageBox.Icon.Critical)
        msg.exec()
    except Exception:
        print(f"[ОШИБКА] Отсутствуют: {', '.join(missing_deps)}")
        print("Запустите install.bat для установки всех зависимостей.")
        input("Нажмите Enter для выхода...")
    sys.exit(1)

# ─── Main imports ─────────────────────────────────────────────────────────────
import torch
import numpy as np
from PIL import Image

from PyQt6.QtWidgets import (
    QApplication, QMainWindow, QWidget, QVBoxLayout, QHBoxLayout,
    QGridLayout, QLabel, QPushButton, QTextEdit, QLineEdit,
    QComboBox, QSlider, QSpinBox, QDoubleSpinBox, QCheckBox,
    QFileDialog, QProgressBar, QTabWidget, QGroupBox,
    QListWidget, QListWidgetItem, QSplitter, QScrollArea,
    QFrame, QMessageBox, QSizePolicy, QDialog,
    QDialogButtonBox, QStackedWidget, QSystemTrayIcon, QMenu,
    QToolTip
)
from PyQt6.QtCore import Qt, QThread, pyqtSignal, QTimer, QSize, QUrl, QSettings
from PyQt6.QtGui import (
    QPixmap, QImage, QFont, QColor, QPalette, QAction, QCursor
)
from PyQt6.QtMultimedia import QMediaPlayer
from PyQt6.QtMultimediaWidgets import QVideoWidget

# ─────────────────────────────────────────────────────────────────────────────
#  Config / Paths
# ─────────────────────────────────────────────────────────────────────────────

APP_DIR      = Path(__file__).parent.resolve()
CONFIG_FILE  = APP_DIR / "config.json"
CACHE_DIR    = APP_DIR / "model_cache"
OUTPUT_DIR   = Path.home() / "Pictures" / "AI_Studio"

DEFAULT_CONFIG = {
    "model_path":   "",
    "recent_models": [],
    "output_dir":   str(OUTPUT_DIR),
    "last_model":   "",
    "steps":        20,
    "cfg_scale":    7.0,
    "sampler":      "Euler a",
    "fp16":         True,
    "low_vram":     False,
    "resolution":   "512x512",
    "fps":          8,
    "num_frames":   16,
    "seed":         -1,
    "neg_prompt":   "blurry, bad anatomy, ugly, watermark, low quality, deformed, disfigured, extra limbs",
    "window_geometry": None,
}

# ─────────────────────────────────────────────────────────────────────────────
#  VRAM Reservation
# ─────────────────────────────────────────────────────────────────────────────

VRAM_RESERVE_GB = 1.0  # always keep this many GB free

def apply_vram_limit():
    """Cap PyTorch GPU memory so VRAM_RESERVE_GB is always left untouched.

    expandable_segments is already enabled via os.environ before torch import
    AND via run.bat (belt-and-suspenders). Here we set the per-process fraction
    and enable TF32 for faster/lighter matmul on Ampere+ (RTX 30xx/40xx).
    """
    if not torch.cuda.is_available():
        return
    try:
        total_bytes = torch.cuda.get_device_properties(0).total_memory
        total_gb    = total_bytes / (1024 ** 3)
        reserve_gb  = min(VRAM_RESERVE_GB, total_gb * 0.15)   # never reserve >15%
        fraction    = max(0.5, (total_gb - reserve_gb) / total_gb)
        torch.cuda.set_per_process_memory_fraction(fraction, device=0)
    except Exception:
        pass
    # TF32 reduces matmul memory on Ampere+ GPUs with negligible quality loss
    torch.backends.cuda.matmul.allow_tf32 = True
    torch.backends.cudnn.allow_tf32       = True

def load_config() -> dict:
    if CONFIG_FILE.exists():
        try:
            with open(CONFIG_FILE, "r", encoding="utf-8") as f:
                saved = json.load(f)
            cfg = dict(DEFAULT_CONFIG)
            cfg.update(saved)
            return cfg
        except Exception:
            pass
    return dict(DEFAULT_CONFIG)

def save_config(cfg: dict):
    try:
        with open(CONFIG_FILE, "w", encoding="utf-8") as f:
            json.dump(cfg, f, ensure_ascii=False, indent=2)
    except Exception:
        pass

# ─────────────────────────────────────────────────────────────────────────────
#  Download Worker
# ─────────────────────────────────────────────────────────────────────────────

class DownloadWorker(QThread):
    progress  = pyqtSignal(int, str)
    file_progress = pyqtSignal(str, int, int)   # filename, downloaded_mb, total_mb
    finished  = pyqtSignal(bool, str)

    SKIP_PATTERNS = {".msgpack", ".h5", "flax_model", "rust_model", "tf_model"}

    MODELS = {
        "AnimateDiff v1.5.2 (для T2V)": {
            "repo": "guoyww/animatediff-motion-adapter-v1-5-2",
            "local": "animatediff-motion-adapter-v1-5-2",
        },
        "Stable Video Diffusion XT (для I2V)": {
            "repo": "stabilityai/stable-video-diffusion-img2vid-xt",
            "local": "stable-video-diffusion-img2vid-xt",
        },
        "VAE ft-mse (улучшенный)": {
            "repo": "stabilityai/sd-vae-ft-mse",
            "local": "sd-vae-ft-mse",
        },
    }

    def __init__(self, model_keys: list):
        super().__init__()
        self.model_keys = model_keys
        self._cancelled = False

    def cancel(self):
        self._cancelled = True

    def _should_skip(self, filename: str) -> bool:
        for pat in self.SKIP_PATTERNS:
            if pat in filename:
                return True
        return False

    def run(self):
        import requests
        from huggingface_hub import list_repo_files, hf_hub_url
        CACHE_DIR.mkdir(parents=True, exist_ok=True)

        total_models = len(self.model_keys)

        for m_idx, key in enumerate(self.model_keys):
            if self._cancelled:
                self.finished.emit(False, "Отменено")
                return

            info = self.MODELS[key]
            repo_id  = info["repo"]
            local_dir = CACHE_DIR / info["local"]
            local_dir.mkdir(parents=True, exist_ok=True)

            self.progress.emit(
                int(m_idx / total_models * 100),
                f"Получение списка файлов: {key}..."
            )

            # Get list of files
            try:
                all_files = [
                    f for f in list_repo_files(repo_id)
                    if not self._should_skip(f)
                ]
            except Exception as e:
                self.finished.emit(False, f"Ошибка получения файлов {key}: {e}")
                return

            total_files = len(all_files)

            for f_idx, filename in enumerate(all_files):
                if self._cancelled:
                    self.finished.emit(False, "Отменено")
                    return

                dest = local_dir / filename
                dest.parent.mkdir(parents=True, exist_ok=True)

                # Skip if already downloaded
                if dest.exists() and dest.stat().st_size > 1024:
                    pct = int((m_idx + (f_idx + 1) / total_files) / total_models * 100)
                    self.progress.emit(pct, f"Пропуск (уже есть): {filename}")
                    continue

                url = hf_hub_url(repo_id=repo_id, filename=filename)
                short_name = Path(filename).name

                try:
                    resp = requests.get(url, stream=True, timeout=60)
                    resp.raise_for_status()
                    total_bytes = int(resp.headers.get("content-length", 0))
                    downloaded  = 0
                    chunk_size  = 1024 * 1024  # 1 MB

                    with open(dest, "wb") as fh:
                        for chunk in resp.iter_content(chunk_size=chunk_size):
                            if self._cancelled:
                                fh.close()
                                dest.unlink(missing_ok=True)
                                self.finished.emit(False, "Отменено")
                                return
                            fh.write(chunk)
                            downloaded += len(chunk)

                            # Update progress
                            file_pct = int(downloaded / total_bytes * 100) if total_bytes else 0
                            overall  = int((m_idx + (f_idx + file_pct / 100) / total_files) / total_models * 100)
                            dl_mb    = downloaded // (1024 * 1024)
                            tot_mb   = total_bytes // (1024 * 1024) if total_bytes else 0
                            self.progress.emit(
                                overall,
                                f"[{m_idx+1}/{total_models}] {short_name}  {dl_mb}/{tot_mb} MB"
                            )

                except Exception as e:
                    self.finished.emit(False, f"Ошибка загрузки {filename}: {e}")
                    return

            self.progress.emit(
                int((m_idx + 1) / total_models * 100),
                f"Готово: {key}"
            )

        self.progress.emit(100, "Все компоненты загружены!")
        self.finished.emit(True, "Загрузка завершена успешно")

# ─────────────────────────────────────────────────────────────────────────────
#  Download Dialog
# ─────────────────────────────────────────────────────────────────────────────

class DownloadDialog(QDialog):
    def __init__(self, parent=None):
        super().__init__(parent)
        self.setWindowTitle("Загрузка AI компонентов")
        self.setMinimumWidth(540)
        self.setStyleSheet(DARK_STYLE)
        self._worker = None
        self._build()

    def _build(self):
        layout = QVBoxLayout(self)

        info = QLabel(
            "Выберите компоненты для загрузки.\n"
            "AnimateDiff нужен для Text→Video.\n"
            "Stable Video Diffusion нужен для Image→Video.\n"
            "Скачиваются один раз и кэшируются локально."
        )
        info.setWordWrap(True)
        info.setStyleSheet("color: #aaaacc; padding: 8px;")
        layout.addWidget(info)

        self.checks = {}
        for key in DownloadWorker.MODELS:
            local = CACHE_DIR / DownloadWorker.MODELS[key]["local"]
            already = local.exists() and any(local.iterdir()) if local.exists() else False
            cb = QCheckBox(key + (" ✅ (уже скачан)" if already else ""))
            cb.setChecked(not already)
            cb.setEnabled(not already)
            self.checks[key] = cb
            layout.addWidget(cb)

        self.progress = QProgressBar()
        self.progress.setValue(0)
        layout.addWidget(self.progress)

        self.status_label = QLabel("")
        self.status_label.setStyleSheet("color: #88aaff;")
        layout.addWidget(self.status_label)

        btn_row = QHBoxLayout()
        self.start_btn = QPushButton("⬇ Скачать выбранное")
        self.start_btn.clicked.connect(self._start)
        self.cancel_btn = QPushButton("Отмена")
        self.cancel_btn.clicked.connect(self._cancel)
        self.close_btn = QPushButton("Закрыть")
        self.close_btn.clicked.connect(self.accept)
        self.close_btn.setEnabled(False)
        btn_row.addWidget(self.start_btn)
        btn_row.addWidget(self.cancel_btn)
        btn_row.addWidget(self.close_btn)
        layout.addLayout(btn_row)

    def _start(self):
        selected = [k for k, cb in self.checks.items() if cb.isChecked() and cb.isEnabled()]
        if not selected:
            QMessageBox.information(self, "Ничего не выбрано", "Все выбранные компоненты уже скачаны.")
            return
        self.start_btn.setEnabled(False)
        self._worker = DownloadWorker(selected)
        self._worker.progress.connect(lambda v, t: (self.progress.setValue(v), self.status_label.setText(t)))
        self._worker.finished.connect(self._done)
        self._worker.start()

    def _cancel(self):
        if self._worker and self._worker.isRunning():
            self._worker.cancel()
        self.reject()

    def _done(self, ok: bool, msg: str):
        self.status_label.setText(msg)
        self.start_btn.setEnabled(True)
        self.close_btn.setEnabled(True)
        if ok:
            QMessageBox.information(self, "Готово", "Компоненты успешно загружены!")

# ─────────────────────────────────────────────────────────────────────────────
#  Generation Worker
# ─────────────────────────────────────────────────────────────────────────────

class GeneratorThread(QThread):
    progress      = pyqtSignal(int, str)
    image_ready   = pyqtSignal(object)
    finished      = pyqtSignal(object, str)
    error         = pyqtSignal(str)

    def __init__(self, config: dict):
        super().__init__()
        self.config = config
        self._cancelled = False

    def cancel(self):
        self._cancelled = True

    def _cb(self, step, timestep, latents):
        if self._cancelled:
            raise InterruptedError("Отменено")
        steps = self.config.get("steps", 20)
        pct = int(step / steps * 85) + 10
        self.progress.emit(pct, f"Шаг {step}/{steps}…")

    def run(self):
        try:
            mode = self.config["mode"]
            if mode == "t2i":
                self._t2i()
            elif mode == "t2v":
                self._t2v()
            elif mode == "i2v":
                self._i2v()
        except InterruptedError:
            self.error.emit("Генерация отменена пользователем.")
        except torch.cuda.OutOfMemoryError as oom_exc:
            # Auto-retry with CPU offload enabled
            if not self.config.get("_oom_retry"):
                self.progress.emit(0, "OOM — повтор с CPU offload…")
                # Drop the traceback reference BEFORE _clear_vram so that
                # Python's ref-counter can release all tensors kept alive by
                # the exception frame (e.g. the 7+ GB pipe object).
                # empty_cache() only frees *reserved-but-unallocated* memory;
                # memory still referenced by the traceback stays on the GPU.
                oom_exc.__traceback__ = None
                del oom_exc
                self._clear_vram()
                self.config["_oom_retry"] = True
                self.config["low_vram"] = True
                try:
                    mode = self.config["mode"]
                    if mode == "t2i":
                        self._t2i()
                    elif mode == "t2v":
                        self._t2v()
                    elif mode == "i2v":
                        self._i2v()
                    return
                except InterruptedError:
                    self.error.emit("Генерация отменена пользователем.")
                    return
                except Exception as e2:
                    import traceback
                    self.error.emit(
                        "Не хватает VRAM даже с CPU offload.\n"
                        "Попробуйте:\n"
                        "• Уменьшить разрешение до 512×512\n"
                        "• Уменьшить кол-во кадров\n"
                        "• Включить FP16\n\n"
                        f"Детали: {e2}"
                    )
                    return
            self.error.emit(
                "Недостаточно памяти видеокарты (VRAM).\n"
                "Попробуйте:\n"
                "• Уменьшить разрешение (512×512)\n"
                "• Уменьшить кол-во кадров\n"
                "• Включить FP16 и режим «Low VRAM»"
            )
        except Exception as e:
            import traceback
            self.error.emit(f"{type(e).__name__}: {e}\n\n{traceback.format_exc()[-800:]}")

    # ── Helpers ───────────────────────────────────────────────────────────────

    def _device(self):
        return "cuda" if torch.cuda.is_available() else "cpu"

    def _dtype(self):
        if not torch.cuda.is_available():
            return torch.float32
        # On CUDA: force FP16 when total VRAM < 16 GB — FP32 doubles memory
        # usage (~10.7 GB vs ~5.4 GB for SD 1.5) and causes OOM on 12 GB cards.
        total_gb = torch.cuda.get_device_properties(0).total_memory / (1024 ** 3)
        if total_gb < 16.0:
            return torch.float16
        return torch.float16 if self.config.get("fp16", True) else torch.float32

    def _seed_generator(self, device):
        g = torch.Generator(device=device)
        s = self.config.get("seed", -1)
        if s == -1:
            s = int(torch.randint(0, 2**31, (1,)).item())
        g.manual_seed(s)
        return g, s

    def _resolution(self):
        try:
            w, h = self.config.get("resolution", "512x512").split("x")
            return int(w), int(h)
        except Exception:
            return 512, 512

    def _make_scheduler(self, pipe):
        from diffusers import (
            EulerAncestralDiscreteScheduler,
            DPMSolverMultistepScheduler,
            DDIMScheduler,
            LCMScheduler,
            KDPM2AncestralDiscreteScheduler,
            HeunDiscreteScheduler,
        )
        schedulers = {
            "Euler a":       EulerAncestralDiscreteScheduler,
            "DPM++ 2M":      DPMSolverMultistepScheduler,
            "DPM++ 2M Karras": DPMSolverMultistepScheduler,
            "DDIM":          DDIMScheduler,
            "LCM":           LCMScheduler,
            "KDPM2 a":       KDPM2AncestralDiscreteScheduler,
            "Heun":          HeunDiscreteScheduler,
        }
        name = self.config.get("sampler", "Euler a")
        cls = schedulers.get(name, EulerAncestralDiscreteScheduler)
        kwargs = {}
        if name == "DPM++ 2M Karras":
            kwargs["use_karras_sigmas"] = True
        pipe.scheduler = cls.from_config(pipe.scheduler.config, **kwargs)

    def _load_loras(self, pipe):
        for i, lora in enumerate(self.config.get("loras", [])):
            if not lora.get("path") or not os.path.exists(lora["path"]):
                continue
            try:
                adapter_name = f"lora_{i}"
                pipe.load_lora_weights(
                    os.path.dirname(lora["path"]),
                    weight_name=os.path.basename(lora["path"]),
                    adapter_name=adapter_name,
                )
                pipe.fuse_lora(lora_scale=lora.get("weight", 1.0))
                self.progress.emit(8, f"LoRA загружена: {os.path.basename(lora['path'])}")
            except Exception as e:
                self.progress.emit(8, f"LoRA пропущена: {e}")

    def _clear_vram(self):
        """Flush GPU memory before loading a new model."""
        gc.collect()
        if torch.cuda.is_available():
            torch.cuda.empty_cache()
            torch.cuda.ipc_collect()

    def _apply_cpu_offload(self, pipe):
        """Move model sub-components to CPU to save VRAM.

        Strategy:
        1. _hook_sequential_offload — block-level PyTorch hooks (our impl, reliable)
        2. enable_sequential_cpu_offload — accelerate (tried after, as extra guarantee)
        3. pipe.to(device) — full GPU, absolute last resort

        NOTE: We intentionally skip enable_sequential_cpu_offload as the FIRST step
        because for large non-standard models (WAN 2.2, etc.) it silently "succeeds"
        without actually offloading, causing the same OOM we're trying to prevent.
        Our hook-based approach is always applied first.
        """
        if not torch.cuda.is_available():
            return pipe.to("cpu")

        # Block-level hook offload — installs pre/post hooks on every transformer
        # block so only ONE block (~200-400 MB) is ever on GPU at a time.
        # We do NOT call enable_sequential_cpu_offload() here because accelerate
        # registers its own device hooks that can OVERRIDE ours and end up moving
        # the entire model back to GPU (causing the exact OOM we're preventing).
        return self._hook_sequential_offload(pipe)

    def _hook_sequential_offload(self, pipe):
        """True block-level CPU offload via PyTorch hooks — mirrors A1111 --lowvram.

        Exactly what AUTOMATIC1111's modules/lowvram.py does:
          1. Every transformer/UNet BLOCK gets pre/post hooks → only ONE block
             (~200–400 MB) is on GPU at any moment during the forward pass.
          2. Text encoders, VAE, image_encoder are offloaded as whole modules
             (they run only once per generation, so per-call move is fine).

        For WAN 2.2 I2V on 12 GB (transformer ~10 GB, each block ~250 MB):
            Peak GPU ≈ 1 block (250 MB) + activations (~500 MB) ≈ 750 MB total.
        """
        cuda = torch.device("cuda:0")
        cpu  = torch.device("cpu")

        def _to_dev(obj, device):
            if isinstance(obj, torch.Tensor):
                return obj.to(device, non_blocking=True)
            if isinstance(obj, (list, tuple)):
                return type(obj)(_to_dev(x, device) for x in obj)
            if isinstance(obj, dict):
                return {k: _to_dev(v, device) for k, v in obj.items()}
            return obj

        def register_offload(module):
            """Attach GPU/CPU hooks to a single nn.Module."""
            module.to(cpu)
            try:
                # PyTorch ≥2.0: handle kwargs too (encoder_hidden_states etc.)
                def pre(mod, args, kwargs):
                    mod.to(cuda)
                    return _to_dev(args, cuda), _to_dev(kwargs, cuda)
                def post(mod, args, output):
                    mod.to(cpu)
                    torch.cuda.empty_cache()
                module.register_forward_pre_hook(pre, with_kwargs=True)
                module.register_forward_hook(post)
            except TypeError:
                def pre_c(mod, args):
                    mod.to(cuda)
                    return _to_dev(args, cuda)
                def post_c(mod, args, output):
                    mod.to(cpu)
                    torch.cuda.empty_cache()
                module.register_forward_pre_hook(pre_c)
                module.register_forward_hook(post_c)

        # ── Whole-module offload: text encoders, VAE, image encoder ───────────
        for attr in ("text_encoder", "text_encoder_2", "text_encoder_3",
                     "vae", "image_encoder"):
            mod = getattr(pipe, attr, None)
            if mod is not None:
                register_offload(mod)

        # ── Block-level offload for UNet / WAN DiT transformer ────────────────
        # Iterate into the actual block LISTS (not the ModuleList container)
        # so each individual block gets its own hook — like A1111 --lowvram.
        for main_attr in ("unet", "transformer"):
            main = getattr(pipe, main_attr, None)
            if main is None:
                continue
            main.to(cpu)

            # Small fixed sub-modules (embeddings, projections, norms)
            for sub_attr in ("conv_in", "conv_out", "conv_norm_out",
                             "patch_embedding", "patch_embed",
                             "time_embedding", "time_proj", "time_text_embed",
                             "text_projection", "context_embedder",
                             "add_embedding", "add_time_proj",
                             "proj_out", "norm_out", "norm"):
                sub = getattr(main, sub_attr, None)
                if sub is not None and isinstance(sub, torch.nn.Module):
                    register_offload(sub)

            # WAN DiT / SD3 / Flux: transformer blocks list
            for list_attr in ("blocks", "transformer_blocks", "single_blocks",
                              "down_blocks", "up_blocks"):
                block_list = getattr(main, list_attr, None)
                if block_list is not None:
                    for block in block_list:   # iterate the list, not hook it
                        register_offload(block)

            # UNet mid block
            mid = getattr(main, "mid_block", None)
            if mid is not None:
                register_offload(mid)

        return pipe

    def _optimize_pipe(self, pipe, low_vram: bool = False):
        """Apply memory optimizations following AUTOMATIC1111's strategy.

        Always applied:
          • attention_slicing(1)     — process 1 head at a time
          • vae_slicing + tiling     — decode VAE in tiles
          • text_encoder / VAE hooks — keep light components on CPU,
                                       auto-move to GPU only for their forward
                                       pass (saves 1.8–2.6 GB VRAM for SDXL)

        low_vram=True additionally:
          • Full sequential / model / hook offload for the UNet as well
        """
        pipe.enable_attention_slicing(1)
        if hasattr(pipe, "enable_vae_slicing"):
            pipe.enable_vae_slicing()
        if hasattr(pipe, "enable_vae_tiling"):
            pipe.enable_vae_tiling()

        if low_vram:
            # Full offload: accelerate-based first, then our hook fallback
            pipe = self._apply_cpu_offload(pipe)
        else:
            # ── Never blindly call pipe.to("cuda") ────────────────────────────
            # Large models (WAN 2.2, SDXL, SVD) can be 10+ GB total and would
            # OOM immediately if moved to GPU all at once.
            # Instead: selectively place components (A1111 medvram approach):
            #   heavy compute (UNet / DiT transformer) → GPU
            #   light one-shot components (text encoders, VAE) → CPU + auto-GPU hook
            pipe = self._smart_device_placement(pipe)

        return pipe

    def _smart_device_placement(self, pipe):
        """Place model components optimally without OOM — mirrors A1111 medvram.

        UNet / transformer → GPU  (used every denoising step, must be fast)
        text_encoder(s), VAE, image_encoder → CPU with forward hooks that
            temporarily move each module to GPU only for its own forward pass.

        For WAN 2.2 I2V on 12 GB (T5-XXL ~4.5 GB + transformer ~5 GB):
            GPU peak ≈ 5 GB (transformer) + 0.3 GB (inference tensors) = 5.3 GB
            T5 moves to GPU only during prompt encoding (once), then back to CPU.
        """
        cuda = torch.device("cuda:0") if torch.cuda.is_available() else torch.device("cpu")
        cpu  = torch.device("cpu")

        # ── Heavy compute component → GPU only if it comfortably fits ─────────
        for attr in ("unet", "transformer"):
            mod = getattr(pipe, attr, None)
            if mod is None:
                continue
            # Estimate model parameter bytes
            try:
                param_bytes = sum(p.numel() * p.element_size()
                                  for p in mod.parameters())
            except Exception:
                param_bytes = 0

            # Query free GPU memory (initialise CUDA if needed)
            try:
                free_bytes = torch.cuda.mem_get_info(0)[0] \
                             if torch.cuda.is_available() else 0
            except Exception:
                free_bytes = 0

            # Leave at least 2 GB headroom for activations during inference
            headroom = 2 * (1024 ** 3)
            fits_on_gpu = (param_bytes > 0) and (param_bytes + headroom <= free_bytes)

            if fits_on_gpu:
                try:
                    mod.to(cuda)
                    if hasattr(mod, "enable_forward_chunking"):
                        mod.enable_forward_chunking(chunk_size=1, dim=1)
                except RuntimeError:
                    # OOM during placement — fall through to block-level offload
                    fits_on_gpu = False

            if not fits_on_gpu:
                # Too large for GPU → block-level hooks (A1111 --lowvram strategy)
                # This sets up hooks for ALL components including text encoders/VAE
                return self._hook_sequential_offload(pipe)

        # ── Light / one-shot components → CPU with auto-GPU hooks ─────────────
        def _attach_offload_hooks(module):
            if module is None:
                return
            module.to(cpu)

            def _pre(mod, args):
                mod.to(cuda)
                return args

            def _post(mod, args, output):
                mod.to(cpu)
                torch.cuda.empty_cache()

            module.register_forward_pre_hook(_pre)
            module.register_forward_hook(_post)

        for attr in ("text_encoder", "text_encoder_2", "text_encoder_3",
                     "vae", "image_encoder"):
            _attach_offload_hooks(getattr(pipe, attr, None))

        # xformers — only works with UNet on GPU
        try:
            pipe.enable_xformers_memory_efficient_attention()
        except Exception:
            pass

        return pipe

    def _save_image(self, image: Image.Image) -> str:
        out = Path(self.config.get("output_dir", str(OUTPUT_DIR)))
        out.mkdir(parents=True, exist_ok=True)
        path = out / f"image_{int(time.time())}.png"
        image.save(str(path))
        return str(path)

    def _save_video(self, frames, fps: int) -> str:
        import imageio
        out = Path(self.config.get("output_dir", str(OUTPUT_DIR)))
        out.mkdir(parents=True, exist_ok=True)
        path = out / f"video_{int(time.time())}.mp4"
        writer = imageio.get_writer(str(path), fps=fps, codec="libx264", quality=8)
        for frame in frames:
            if isinstance(frame, Image.Image):
                frame = np.array(frame)
            writer.append_data(frame)
        writer.close()
        return str(path)

    # ── Text → Image ──────────────────────────────────────────────────────────

    def _t2i(self):
        from diffusers import AutoPipelineForText2Image
        cfg = self.config
        device = self._device()
        dtype = self._dtype()
        low_vram = cfg.get("low_vram", False)

        self.progress.emit(1, "Очистка памяти…")
        self._clear_vram()

        self.progress.emit(2, "Загрузка модели…")

        vae = None
        if cfg.get("vae_path"):
            from diffusers import AutoencoderKL
            self.progress.emit(4, "Загрузка VAE…")
            vae = AutoencoderKL.from_pretrained(
                cfg["vae_path"], torch_dtype=dtype,
                device_map=None, low_cpu_mem_usage=True,
            )

        te = {}
        if cfg.get("text_encoder_path"):
            from transformers import CLIPTextModel
            self.progress.emit(5, "Загрузка Text Encoder…")
            te["text_encoder"] = CLIPTextModel.from_pretrained(
                cfg["text_encoder_path"], torch_dtype=dtype,
                device_map=None, low_cpu_mem_usage=True,
            )

        pipe = AutoPipelineForText2Image.from_pretrained(
            cfg["model_path"],
            torch_dtype=dtype,
            device_map=None,          # force CPU — never auto-place on GPU
            low_cpu_mem_usage=True,   # meta tensors during load → less RAM spike
            safety_checker=None,
            requires_safety_checker=False,
            **({"vae": vae} if vae else {}),
            **te,
        )
        self._make_scheduler(pipe)
        self._load_loras(pipe)
        pipe = self._optimize_pipe(pipe, low_vram=low_vram)

        # Release PyTorch's reserved-but-unused VRAM pool right before inference.
        # This is critical: ~1.3 GB is typically held in the cache after model load.
        gc.collect()
        if torch.cuda.is_available():
            torch.cuda.empty_cache()

        self.progress.emit(10, "Генерация…")
        w, h = self._resolution()
        gen, seed = self._seed_generator(device)
        steps = cfg.get("steps", 20)

        ctx = torch.autocast("cuda", dtype=torch.float16) if torch.cuda.is_available() \
              else contextlib.nullcontext()
        with torch.no_grad(), ctx:
            result = pipe(
                prompt=cfg["prompt"],
                negative_prompt=cfg.get("neg_prompt", ""),
                width=w, height=h,
                num_inference_steps=steps,
                guidance_scale=cfg.get("cfg_scale", 7.0),
                generator=gen,
                callback_on_step_end=lambda p, i, t, cb_kwargs: (
                    self._cb_simple(i, steps) or cb_kwargs
                ),
            )

        image = result.images[0]
        self.progress.emit(97, "Сохранение…")
        path = self._save_image(image)
        self.finished.emit(image, path)

        del pipe
        self._clear_vram()

    def _cb_simple(self, step, total):
        if self._cancelled:
            raise InterruptedError("Отменено")
        pct = int(step / total * 85) + 10
        self.progress.emit(pct, f"Шаг {step}/{total}…")
        # Release reserved-but-unused VRAM every step so the next allocation
        # doesn't fail due to fragmentation (fixes "reserved but unallocated" OOM)
        if torch.cuda.is_available():
            torch.cuda.empty_cache()

    # ── Text → Video ──────────────────────────────────────────────────────────

    def _t2v(self):
        from diffusers import AnimateDiffPipeline, MotionAdapter, DDIMScheduler

        cfg = self.config
        device = self._device()
        dtype = self._dtype()
        low_vram = cfg.get("low_vram", False)

        adapter_path = CACHE_DIR / "animatediff-motion-adapter-v1-5-2"
        if not adapter_path.exists():
            raise FileNotFoundError(
                "AnimateDiff не найден.\n"
                "Откройте меню Инструменты → Загрузить AI компоненты."
            )

        self.progress.emit(1, "Очистка памяти…")
        self._clear_vram()

        self.progress.emit(2, "Загрузка AnimateDiff…")
        adapter = MotionAdapter.from_pretrained(
            str(adapter_path), torch_dtype=dtype,
            device_map=None, low_cpu_mem_usage=True,
        )

        self.progress.emit(8, "Загрузка базовой модели…")
        pipe = AnimateDiffPipeline.from_pretrained(
            cfg["model_path"],
            motion_adapter=adapter,
            torch_dtype=dtype,
            device_map=None,
            low_cpu_mem_usage=True,
        )
        pipe.scheduler = DDIMScheduler.from_config(
            pipe.scheduler.config,
            clip_sample=False,
            timestep_spacing="linspace",
            beta_schedule="linear",
            steps_offset=1,
        )
        pipe = self._optimize_pipe(pipe, low_vram=low_vram)
        self._load_loras(pipe)

        # Release reserved VRAM pool before video inference
        gc.collect()
        if torch.cuda.is_available():
            torch.cuda.empty_cache()

        self.progress.emit(15, "Генерация видео…")
        w, h = self._resolution()
        exec_device = getattr(pipe, '_execution_device', device)
        gen, seed = self._seed_generator(exec_device)
        fps  = cfg.get("fps", 8)
        frames = cfg.get("num_frames", 16)

        steps = cfg.get("steps", 20)
        ctx = torch.autocast("cuda", dtype=torch.float16) if torch.cuda.is_available() \
              else contextlib.nullcontext()
        with torch.no_grad(), ctx:
            output = pipe(
                prompt=cfg["prompt"],
                negative_prompt=cfg.get("neg_prompt", ""),
                num_frames=frames,
                guidance_scale=cfg.get("cfg_scale", 7.5),
                num_inference_steps=steps,
                width=w, height=h,
                generator=gen,
                callback_on_step_end=lambda p, i, t, cb_kwargs: (
                    self._cb_simple(i, steps) or cb_kwargs
                ),
            )

        self.progress.emit(92, "Сохранение видео…")
        path = self._save_video(output.frames[0], fps)
        self.finished.emit(None, path)

        del pipe, adapter
        self._clear_vram()

    # ── Image → Video ─────────────────────────────────────────────────────────

    def _svd_block_offload(self, pipe):
        """Block-level CPU offload for SVD XT on 12 GB VRAM.
        
        enable_sequential_cpu_offload() hooks the ENTIRE UNet (~9 GB) as one unit
        causing 9 GB on GPU at once -> OOM. This method uses cpu_offload_with_hook
        on each DOWN/MID/UP block individually (~300 MB-2 GB per block).
        Small UNet glue modules stay on GPU permanently (~50-100 MB).
        Peak VRAM ~2.5 GB.
        """
        cuda_dev = torch.device("cuda:0")
        try:
            from accelerate.hooks import cpu_offload_with_hook
        except ImportError:
            pipe.enable_sequential_cpu_offload()
            return

        prev_hook = None
        if hasattr(pipe, "image_encoder") and pipe.image_encoder is not None:
            pipe.image_encoder, prev_hook = cpu_offload_with_hook(
                pipe.image_encoder, cuda_dev, prev_module_hook=prev_hook)

        unet = getattr(pipe, "unet", None)
        if unet is not None:
            unet.to("cpu")
            glue = ("conv_in","conv_out","conv_norm_out","time_proj",
                "time_embedding","add_embedding","add_time_proj","encoder_hid_proj")
            for attr in glue:
                mod = getattr(unet, attr, None)
                if mod is not None and isinstance(mod, torch.nn.Module):
                    mod.to(cuda_dev)
            for block in getattr(unet, "down_blocks", []):
                block, prev_hook = cpu_offload_with_hook(
                    block, cuda_dev, prev_module_hook=prev_hook)
            mid = getattr(unet, "mid_block", None)
            if mid is not None:
                unet.mid_block, prev_hook = cpu_offload_with_hook(
                    unet.mid_block, cuda_dev, prev_module_hook=prev_hook)
            for block in getattr(unet, "up_blocks", []):
                block, prev_hook = cpu_offload_with_hook(
                    block, cuda_dev, prev_module_hook=prev_hook)

        if hasattr(pipe, "vae") and pipe.vae is not None:
            pipe.vae, prev_hook = cpu_offload_with_hook(
                pipe.vae, cuda_dev, prev_module_hook=prev_hook)

    def _wan_block_offload(self, pipe):
        """Block-level CPU offload for WAN 2.2 I2V on 12 GB VRAM.

        Uses the same cpu_offload_with_hook pattern as _svd_block_offload:
        each transformer block is moved to GPU just for its forward pass,
        then immediately returned to CPU before the next block starts.

        Chain order: text_encoder(s) → image_encoder
                     → transformer blocks (one by one)
                     → VAE

        Peak VRAM: ~4–5 GB  (one block ~300–800 MB + activations).
        This matches the previously working state reported by the user.
        """
        cuda_dev = torch.device("cuda:0")
        try:
            from accelerate.hooks import cpu_offload_with_hook
        except ImportError:
            # Fallback: standard accelerate sequential offload
            pipe.enable_attention_slicing(1)
            if hasattr(pipe, "enable_vae_slicing"):
                pipe.enable_vae_slicing()
            if hasattr(pipe, "enable_vae_tiling"):
                pipe.enable_vae_tiling()
            pipe.enable_sequential_cpu_offload()
            return pipe

        prev_hook = None

        # ── Text encoders (T5, CLIP, etc.) ────────────────────────────────────
        for attr in ("text_encoder", "text_encoder_2", "text_encoder_3"):
            mod = getattr(pipe, attr, None)
            if mod is not None:
                setattr(pipe, attr, mod)
                mod, prev_hook = cpu_offload_with_hook(
                    mod, cuda_dev, prev_module_hook=prev_hook)
                setattr(pipe, attr, mod)

        # ── Image encoder (CLIP vision encoder for I2V) ────────────────────────
        if getattr(pipe, "image_encoder", None) is not None:
            pipe.image_encoder, prev_hook = cpu_offload_with_hook(
                pipe.image_encoder, cuda_dev, prev_module_hook=prev_hook)

        # ── WAN DiT transformer — block by block ───────────────────────────────
        transformer = getattr(pipe, "transformer", None)
        if transformer is not None:
            transformer.to("cpu")

            # Small fixed submodules that run very quickly — keep on CPU,
            # offload as a group before the block chain starts.
            fixed_attrs = (
                "patch_embedding", "patch_embed", "time_embedding",
                "time_proj", "time_text_embed", "context_embedder",
                "text_projection", "proj_out", "norm_out", "norm",
                "condition_embedder", "rope",
            )
            fixed_mods = []
            for sub_attr in fixed_attrs:
                sub = getattr(transformer, sub_attr, None)
                if sub is not None and isinstance(sub, torch.nn.Module):
                    fixed_mods.append(sub)

            if fixed_mods:
                import torch.nn as nn
                fixed_group = nn.ModuleList(fixed_mods)
                fixed_group, prev_hook = cpu_offload_with_hook(
                    fixed_group, cuda_dev, prev_module_hook=prev_hook)

            # Individual transformer blocks — ONE block on GPU at a time
            for blk_attr in ("blocks", "transformer_blocks"):
                blk_list = getattr(transformer, blk_attr, None)
                if blk_list is not None:
                    for blk in blk_list:
                        blk, prev_hook = cpu_offload_with_hook(
                            blk, cuda_dev, prev_module_hook=prev_hook)
                    break  # only process first matching attribute

        # ── VAE (video decoder — runs once at the end) ─────────────────────────
        if getattr(pipe, "vae", None) is not None:
            pipe.vae.enable_slicing()
            if hasattr(pipe.vae, "enable_tiling"):
                pipe.vae.enable_tiling()
            pipe.vae, prev_hook = cpu_offload_with_hook(
                pipe.vae, cuda_dev, prev_module_hook=prev_hook)

        # Attention slicing — reduces peak activation VRAM inside each block
        pipe.enable_attention_slicing(1)

        return pipe

    def _i2v(self):
        from diffusers.utils import load_image
        cfg = self.config
        device = self._device()
        input_path = cfg.get("input_image", "")
        if not input_path or not os.path.exists(input_path):
            raise ValueError("Не выбрано входное изображение!")
        self.progress.emit(1, "Очистка памяти…")
        self._clear_vram()
        model_path = cfg.get("model_path", "").strip()
        # WAN mode: either a directory with model_index.json,
        # or a .safetensors file whose parent dir has model_index.json
        use_wan = False
        wan_load_path = ""
        if model_path:
            if os.path.isdir(model_path) and os.path.isfile(
                os.path.join(model_path, "model_index.json")
            ):
                use_wan = True
                wan_load_path = model_path
            elif (
                os.path.isfile(model_path)
                and model_path.lower().endswith(".safetensors")
            ):
                parent = os.path.dirname(model_path)
                if os.path.isfile(os.path.join(parent, "model_index.json")):
                    use_wan = True
                    wan_load_path = parent
                else:
                    # Single .safetensors without a WAN directory — fall through to SVD
                    use_wan = False
        if use_wan:
            self._i2v_wan(cfg, device, input_path, wan_load_path)
        else:
            self._i2v_svd(cfg, device, input_path)

    def _i2v_wan(self, cfg, device, input_path, model_path):
        """WAN 2.2 Image-to-Video loaded via from_pretrained (directory)."""
        try:
            from diffusers import WanImageToVideoPipeline
        except ImportError:
            raise ImportError(
                "Для WAN 2.2 i2v нужен diffusers >= 0.33.0.\n"
                "pip install -U diffusers"
            )
        from diffusers.utils import load_image
        dtype = torch.bfloat16 if torch.cuda.is_bf16_supported() else torch.float16
        self.progress.emit(2, "Загрузка WAN 2.2 I2V…")
        if not os.path.isdir(model_path):
            raise ValueError(
                "WAN 2.2 I2V требует папку с моделью, а не одиночный .safetensors файл.\n\n"
                "Выберите папку, например: C:\\AI_Models\\Wan2.2-I2V-14B-480P\n"
                "В папке должен быть файл model_index.json.\n\n"
                "Если нужен Stable Video Diffusion XT — не выбирайте .safetensors файл,\n"
                "а загрузите SVD через Инструменты → Загрузить AI компоненты."
            )
        pipe = WanImageToVideoPipeline.from_pretrained(
            model_path,
            torch_dtype=dtype,
            device_map=None,
            low_cpu_mem_usage=True,
        )

        try:
            # ── Memory optimizations ──────────────────────────────────────────────
            # Attention slicing + VAE tiling reduce activation memory significantly.
            # Must be called BEFORE enable_sequential_cpu_offload so that diffusers
            # picks them up when registering hooks.
            pipe.enable_attention_slicing(1)
            if hasattr(pipe, "enable_vae_slicing"):
                pipe.enable_vae_slicing()
            if hasattr(pipe, "enable_vae_tiling"):
                pipe.enable_vae_tiling()

            # ── Temporal chunking (BEFORE cpu offload hooks) ──────────────────────
            # Processes frames one-at-a-time inside each transformer block so that
            # activation memory scales with 1 frame instead of N frames.
            if hasattr(pipe, "transformer"):
                xfmr = pipe.transformer
                if hasattr(xfmr, "enable_forward_chunking"):
                    xfmr.enable_forward_chunking(chunk_size=1, dim=1)
                for blk_attr in ("blocks", "transformer_blocks", "single_blocks"):
                    blk_list = getattr(xfmr, blk_attr, None)
                    if blk_list is not None:
                        for blk in blk_list:
                            if hasattr(blk, "enable_forward_chunking"):
                                blk.enable_forward_chunking(chunk_size=1, dim=1)
                        break

            # ── Sequential CPU offload via accelerate ─────────────────────────────
            # WHY NOT our custom _hook_sequential_offload:
            #   WanImageToVideoPipeline.__call__ internally calls .to(device) on
            #   the transformer before inference, which overrides bare PyTorch
            #   register_forward_pre_hook hooks and puts the full 7+ GB model on
            #   GPU → OOM.
            # WHY enable_sequential_cpu_offload works:
            #   Accelerate's AlignDevicesHook patches the module's .to() method
            #   to a no-op, so pipeline-internal .to() calls are silently ignored.
            #   Only ONE sub-module (transformer block, encoder, VAE) is on GPU
            #   at any given time → peak VRAM ≈ largest single block + activations.
            if torch.cuda.is_available():
                self.progress.emit(5, "Настройка CPU offload…")
                pipe.enable_sequential_cpu_offload()
            else:
                pipe.to("cpu")

            image = load_image(input_path)
            w, h = self._resolution()
            image = image.resize((w, h))

            fps          = cfg.get("fps", 16)
            total_frames = cfg.get("num_frames", 25)
            chunk_size   = cfg.get("chunk_frames", 25)
            steps        = cfg.get("steps", 20)
            prompt       = cfg.get("prompt", "")
            neg_prompt   = cfg.get("neg_prompt", "")
            guidance     = cfg.get("cfg_scale", 5.0)

            # Generator on CPU — works fine with sequential offload; the pipeline
            # moves it to the right device internally when needed.
            g = torch.Generator(device="cpu")
            s = cfg.get("seed", -1)
            if s == -1:
                s = int(torch.randint(0, 2**31, (1,)).item())
            g.manual_seed(s)

            # Flush fragmented VRAM pool before inference
            gc.collect()
            if torch.cuda.is_available():
                torch.cuda.empty_cache()
                torch.cuda.reset_peak_memory_stats()

            ctx = torch.autocast("cuda", dtype=dtype) if torch.cuda.is_available() \
                  else contextlib.nullcontext()

            all_frames: list = []
            current_image    = image
            frames_remaining = total_frames
            chunk_idx        = 0

            while frames_remaining > 0:
                this_chunk   = min(frames_remaining, chunk_size)
                total_chunks = max(1, -(-total_frames // chunk_size))  # ceil div
                pct_base     = 25 + int(chunk_idx / total_chunks * 65)
                self.progress.emit(
                    pct_base,
                    f"Генерация чанк {chunk_idx+1}/{total_chunks} "
                    f"({this_chunk} кадров)…"
                )

                # Fresh generator per chunk so long videos don't loop
                gc_chunk = torch.Generator(device="cpu")
                gc_chunk.manual_seed(
                    int(torch.randint(0, 2**31, (1,), generator=g).item())
                )

                with torch.no_grad(), ctx:
                    result = pipe(
                        image=current_image,
                        prompt=prompt,
                        negative_prompt=neg_prompt,
                        num_frames=this_chunk,
                        num_inference_steps=steps,
                        guidance_scale=guidance,
                        width=w, height=h,
                        generator=gc_chunk,
                        callback_on_step_end=lambda p, i, t, cb: (
                            self._cb_simple(i, steps) or cb
                        ),
                    )

                chunk_frames = result.frames[0]

                # Skip first frame of subsequent chunks — it's a near-duplicate
                # of the last frame used as seed image for this chunk.
                if chunk_idx > 0 and len(chunk_frames) > 1:
                    chunk_frames = chunk_frames[1:]

                all_frames.extend(chunk_frames)

                # Use last frame as seed image for the next chunk
                last = chunk_frames[-1]
                current_image = (
                    Image.fromarray(last) if isinstance(last, np.ndarray) else last
                )

                frames_remaining -= this_chunk
                chunk_idx        += 1

                del result
                gc.collect()
                if torch.cuda.is_available():
                    torch.cuda.empty_cache()

            self.progress.emit(92, "Сохранение видео…")
            path = self._save_video(all_frames, fps)
            self.finished.emit(None, path)
        finally:
            # Always release pipe — even if an exception occurred during inference.
            # Without this the 7+ GB model stays allocated and makes the next
            # generation attempt fail immediately.
            del pipe
            self._clear_vram()

    def _i2v_svd(self, cfg, device, input_path):
        """SVD XT Image-to-Video (fallback)."""
        from diffusers import StableVideoDiffusionPipeline
        from diffusers.utils import load_image
        svd_path = CACHE_DIR / "stable-video-diffusion-img2vid-xt"
        if not svd_path.exists():
            raise FileNotFoundError(
                "Выберите WAN 2.2 .safetensors через кнопку Модель \n"
                "(или загрузите SVD XT через Инструменты → Загрузить AI компоненты)"
            )
        self.progress.emit(2, "Загрузка SVD…")
        pipe = StableVideoDiffusionPipeline.from_pretrained(
            str(svd_path),
            torch_dtype=torch.float16,
            variant="fp16",
            device_map=None,
            low_cpu_mem_usage=True,
        )
        pipe.enable_attention_slicing(1)
        if hasattr(pipe, "enable_vae_slicing"): pipe.enable_vae_slicing()
        if hasattr(pipe, "enable_vae_tiling"):  pipe.enable_vae_tiling()
        if torch.cuda.is_available():
            self._svd_block_offload(pipe)
        else:
            pipe.to("cpu")
        if hasattr(pipe, "unet"):
            pipe.unet.enable_forward_chunking(chunk_size=1, dim=0)
        image = load_image(input_path)
        w, h = self._resolution()
        image = image.resize((w, h))
        exec_device = getattr(pipe, "_execution_device", device)
        gen, seed = self._seed_generator(exec_device)
        fps    = cfg.get("fps", 6)
        frames = cfg.get("num_frames", 25)
        motion = cfg.get("motion_bucket", 127)
        gc.collect()
        if torch.cuda.is_available(): torch.cuda.empty_cache()
        self.progress.emit(25, "Генерация SVD…")
        ctx = torch.autocast("cuda", dtype=torch.float16) if torch.cuda.is_available() \
              else contextlib.nullcontext()
        with torch.no_grad(), ctx:
            result = pipe(
                image,
                num_frames=frames,
                num_inference_steps=cfg.get("steps", 25),
                decode_chunk_size=2,
                motion_bucket_id=motion,
                fps=fps, generator=gen,
            )
        self.progress.emit(92, "Сохранение видео…")
        path = self._save_video(result.frames[0], fps)
        self.finished.emit(None, path)
        del pipe
        self._clear_vram()


class LoraDialog(QDialog):
    def __init__(self, parent=None):
        super().__init__(parent)
        self.setWindowTitle("Добавить LoRA")
        self.setMinimumWidth(480)
        self.setStyleSheet(DARK_STYLE)
        self._build()

    def _build(self):
        layout = QVBoxLayout(self)
        layout.setSpacing(10)

        layout.addWidget(QLabel("Файл LoRA (.safetensors):"))
        row = QHBoxLayout()
        self.path_edit = QLineEdit()
        self.path_edit.setPlaceholderText("Путь к файлу…")
        btn = QPushButton("📁 Обзор")
        btn.clicked.connect(self._browse)
        row.addWidget(self.path_edit)
        row.addWidget(btn)
        layout.addLayout(row)

        layout.addWidget(QLabel("Имя (для отображения):"))
        self.name_edit = QLineEdit()
        layout.addWidget(self.name_edit)

        wrow = QHBoxLayout()
        wrow.addWidget(QLabel("Вес LoRA:"))
        self.weight = QDoubleSpinBox()
        self.weight.setRange(0.0, 2.0)
        self.weight.setSingleStep(0.05)
        self.weight.setValue(1.0)
        wrow.addWidget(self.weight)
        wrow.addStretch()
        layout.addLayout(wrow)

        btns = QDialogButtonBox(
            QDialogButtonBox.StandardButton.Ok | QDialogButtonBox.StandardButton.Cancel
        )
        btns.accepted.connect(self.accept)
        btns.rejected.connect(self.reject)
        layout.addWidget(btns)

    def _browse(self):
        path, _ = QFileDialog.getOpenFileName(
            self, "Выбрать LoRA", "",
            "Model Files (*.safetensors *.bin *.pt *.ckpt)"
        )
        if path:
            self.path_edit.setText(path)
            if not self.name_edit.text():
                self.name_edit.setText(Path(path).stem)

    def result_data(self):
        return {
            "path":   self.path_edit.text(),
            "name":   self.name_edit.text() or Path(self.path_edit.text()).stem,
            "weight": self.weight.value(),
        }

# ─────────────────────────────────────────────────────────────────────────────
#  GPU Monitor Thread
# ─────────────────────────────────────────────────────────────────────────────

class GpuMonitor(QThread):
    updated = pyqtSignal(str)

    def run(self):
        while True:
            try:
                result = subprocess.run(
                    ["nvidia-smi", "--query-gpu=utilization.gpu,memory.used,memory.total,temperature.gpu",
                     "--format=csv,noheader,nounits"],
                    capture_output=True, text=True, timeout=2
                )
                if result.returncode == 0:
                    parts = result.stdout.strip().split(",")
                    if len(parts) >= 4:
                        gpu_util  = parts[0].strip()
                        mem_used  = int(parts[1].strip())
                        mem_total = int(parts[2].strip())
                        temp      = parts[3].strip()
                        self.updated.emit(
                            f"GPU: {gpu_util}%  |  VRAM: {mem_used}/{mem_total} MB  |  Temp: {temp}°C"
                        )
            except Exception:
                pass
            self.msleep(2000)

# ─────────────────────────────────────────────────────────────────────────────
#  Dark Style Sheet
# ─────────────────────────────────────────────────────────────────────────────

DARK_STYLE = """
QMainWindow, QWidget, QDialog {
    background-color: #14141f;
    color: #ddddf0;
    font-family: 'Segoe UI', Arial, sans-serif;
    font-size: 13px;
}
QGroupBox {
    border: 1px solid #2e2e50;
    border-radius: 8px;
    margin-top: 14px;
    padding-top: 10px;
    font-weight: bold;
    color: #8888bb;
}
QGroupBox::title {
    subcontrol-origin: margin;
    subcontrol-position: top left;
    padding: 0 8px;
    left: 10px;
}
QPushButton {
    background-color: #222240;
    border: 1px solid #3a3a6a;
    border-radius: 6px;
    padding: 6px 14px;
    color: #ddddf0;
    min-height: 28px;
}
QPushButton:hover  { background-color: #2e2e5a; border-color: #5555aa; }
QPushButton:pressed{ background-color: #1a1a35; }
QPushButton:disabled { color: #555570; border-color: #282840; }
QPushButton#generateBtn {
    background: qlineargradient(x1:0,y1:0,x2:1,y2:0,stop:0 #4a2080,stop:1 #6a40b0);
    border: none;
    font-size: 15px;
    font-weight: bold;
    min-height: 52px;
    border-radius: 8px;
    color: #ffffff;
}
QPushButton#generateBtn:hover {
    background: qlineargradient(x1:0,y1:0,x2:1,y2:0,stop:0 #5a30a0,stop:1 #7a50c0);
}
QPushButton#cancelBtn {
    background-color: #5a1515;
    border-color: #8a3535;
}
QPushButton#cancelBtn:hover { background-color: #7a2020; }
QPushButton#saveBtn {
    background-color: #155a15;
    border-color: #358a35;
    color: #ddddf0;
}
QPushButton#saveBtn:hover { background-color: #1f7a1f; }
QTextEdit, QLineEdit {
    background-color: #0e0e1e;
    border: 1px solid #2e2e50;
    border-radius: 6px;
    padding: 6px 8px;
    color: #ddddf0;
    selection-background-color: #4040a0;
}
QTextEdit:focus, QLineEdit:focus { border-color: #6060c0; }
QComboBox {
    background-color: #0e0e1e;
    border: 1px solid #2e2e50;
    border-radius: 6px;
    padding: 5px 8px;
    color: #ddddf0;
    min-height: 28px;
}
QComboBox::drop-down { border: none; width: 20px; }
QComboBox::down-arrow { image: none; }
QComboBox:hover { border-color: #5555aa; }
QComboBox QAbstractItemView {
    background-color: #1a1a30;
    color: #ddddf0;
    selection-background-color: #3a3a80;
    border: 1px solid #3a3a6a;
}
QSpinBox, QDoubleSpinBox {
    background-color: #0e0e1e;
    border: 1px solid #2e2e50;
    border-radius: 6px;
    padding: 4px 6px;
    color: #ddddf0;
    min-height: 26px;
}
QSpinBox:focus, QDoubleSpinBox:focus { border-color: #6060c0; }
QProgressBar {
    border: 1px solid #2e2e50;
    border-radius: 6px;
    background: #0e0e1e;
    text-align: center;
    color: #aaaaff;
    min-height: 22px;
}
QProgressBar::chunk {
    background: qlineargradient(x1:0,y1:0,x2:1,y2:0,stop:0 #4a2080,stop:1 #7a50c0);
    border-radius: 5px;
}
QTabWidget::pane { border: 1px solid #2e2e50; border-radius: 6px; }
QTabBar::tab {
    background: #14141f;
    border: 1px solid #2e2e50;
    padding: 8px 18px;
    color: #8888bb;
    border-radius: 4px 4px 0 0;
}
QTabBar::tab:selected { background: #1e1e3a; color: #ddddf0; border-bottom: 2px solid #6060c0; }
QTabBar::tab:hover:!selected { background: #1a1a2e; }
QListWidget {
    background-color: #0e0e1e;
    border: 1px solid #2e2e50;
    border-radius: 6px;
    color: #ddddf0;
    padding: 4px;
}
QListWidget::item { padding: 4px 6px; border-radius: 4px; }
QListWidget::item:selected { background-color: #2e2e5a; }
QListWidget::item:hover { background-color: #1e1e40; }
QScrollArea { border: none; }
QScrollBar:vertical {
    background: #0e0e1e;
    width: 8px;
    border-radius: 4px;
}
QScrollBar::handle:vertical { background: #3a3a6a; border-radius: 4px; min-height: 20px; }
QScrollBar::add-line:vertical, QScrollBar::sub-line:vertical { height: 0; }
QLabel#previewLabel {
    background-color: #0a0a18;
    border: 1px solid #2e2e50;
    border-radius: 8px;
    color: #44446a;
}
QMenuBar {
    background-color: #10101c;
    color: #ddddf0;
    border-bottom: 1px solid #2e2e50;
}
QMenuBar::item:selected { background-color: #2e2e5a; }
QMenu {
    background-color: #1a1a30;
    color: #ddddf0;
    border: 1px solid #2e2e50;
}
QMenu::item:selected { background-color: #3a3a6a; }
QCheckBox { color: #ddddf0; spacing: 6px; }
QCheckBox::indicator {
    width: 16px; height: 16px;
    border: 1px solid #3a3a6a;
    border-radius: 3px;
    background: #0e0e1e;
}
QCheckBox::indicator:checked {
    background: #5050c0;
    border-color: #7070e0;
}
"""

# ─────────────────────────────────────────────────────────────────────────────
#  Main Window
# ─────────────────────────────────────────────────────────────────────────────

class MainWindow(QMainWindow):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("AI Studio — Image & Video Generator")
        self.setMinimumSize(1300, 820)
        self.resize(1520, 940)

        self._cfg     = load_config()
        self._worker  = None
        self._loras   = []
        self._last_path  = None
        self._last_image = None

        self._build_menu()
        self._build_ui()
        self._restore_state()
        self._start_gpu_monitor()
        self._check_first_run()

    # ─── Menu ─────────────────────────────────────────────────────────────────

    def _build_menu(self):
        bar = self.menuBar()

        file_menu = bar.addMenu("Файл")
        act_open = QAction("📂 Открыть папку результатов", self)
        act_open.triggered.connect(self._open_output_folder)
        file_menu.addAction(act_open)
        file_menu.addSeparator()
        act_exit = QAction("Выход", self)
        act_exit.triggered.connect(self.close)
        file_menu.addAction(act_exit)

        tools_menu = bar.addMenu("Инструменты")
        act_dl = QAction("⬇ Загрузить AI компоненты…", self)
        act_dl.triggered.connect(self._open_download_dialog)
        tools_menu.addAction(act_dl)
        tools_menu.addSeparator()
        act_cache = QAction("🗑 Очистить VRAM кэш", self)
        act_cache.triggered.connect(self._clear_vram)
        tools_menu.addAction(act_cache)
        tools_menu.addSeparator()
        act_cuda = QAction("🔧 Переустановить PyTorch+CUDA", self)
        act_cuda.triggered.connect(self._reinstall_cuda)
        tools_menu.addAction(act_cuda)
        act_diag = QAction("🔍 Диагностика GPU", self)
        act_diag.triggered.connect(self._show_gpu_diag)
        tools_menu.addAction(act_diag)

        help_menu = bar.addMenu("Справка")
        act_about = QAction("О программе", self)
        act_about.triggered.connect(self._show_about)
        help_menu.addAction(act_about)

    # ─── UI ───────────────────────────────────────────────────────────────────

    def _build_ui(self):
        central = QWidget()
        self.setCentralWidget(central)
        root = QHBoxLayout(central)
        root.setContentsMargins(6, 6, 6, 6)
        root.setSpacing(6)

        splitter = QSplitter(Qt.Orientation.Horizontal)
        splitter.setHandleWidth(3)
        root.addWidget(splitter)

        splitter.addWidget(self._build_left())
        splitter.addWidget(self._build_center())
        splitter.addWidget(self._build_right())
        splitter.setSizes([340, 560, 500])

        # Status bar
        self.gpu_status = QLabel("GPU: определение…")
        self.gpu_status.setStyleSheet("color: #5555aa; font-size: 11px; padding: 0 8px;")
        self.statusBar().addPermanentWidget(self.gpu_status)
        self.statusBar().setStyleSheet("background: #10101c; border-top: 1px solid #2e2e50;")

    # ── Left panel ────────────────────────────────────────────────────────────

    def _build_left(self):
        scroll = QScrollArea()
        scroll.setWidgetResizable(True)
        scroll.setMinimumWidth(300)
        scroll.setMaximumWidth(380)

        w = QWidget()
        lay = QVBoxLayout(w)
        lay.setSpacing(8)
        lay.setContentsMargins(4, 4, 4, 4)

        # Mode
        mode_group = QGroupBox("Режим генерации")
        mg = QVBoxLayout(mode_group)
        self.mode_tabs = QTabWidget()
        self.mode_tabs.addTab(QWidget(), "🖼 Текст → Фото")
        self.mode_tabs.addTab(QWidget(), "🎬 Текст → Видео")
        self.mode_tabs.addTab(QWidget(), "🎥 Фото → Видео")
        self.mode_tabs.currentChanged.connect(self._on_mode_changed)
        mg.addWidget(self.mode_tabs)
        lay.addWidget(mode_group)

        # Model
        model_group = QGroupBox("Модель")
        mg2 = QVBoxLayout(model_group)
        mg2.setSpacing(6)

        mg2.addWidget(QLabel("Путь к файлу модели (.safetensors / .ckpt):"))
        path_row = QHBoxLayout()
        self.model_path_edit = QLineEdit(self._cfg.get("model_path", ""))
        self.model_path_edit.setPlaceholderText("Выберите файл модели через кнопку 📁…")
        self.model_path_edit.setReadOnly(True)
        browse_model_btn = QPushButton("📁 Выбрать модель")
        browse_model_btn.clicked.connect(self._browse_model_file)
        path_row.addWidget(self.model_path_edit)
        path_row.addWidget(browse_model_btn)
        mg2.addLayout(path_row)

        mg2.addWidget(QLabel("Последние модели:"))
        self.recent_combo = QComboBox()
        self.recent_combo.addItem("— история —")
        for p in self._cfg.get("recent_models", []):
            self.recent_combo.addItem(Path(p).name, p)
        self.recent_combo.currentIndexChanged.connect(self._on_recent_model_selected)
        mg2.addWidget(self.recent_combo)

        self.model_name_lbl = QLabel("")
        self.model_name_lbl.setStyleSheet("color: #7799dd; font-size: 11px;")
        self.model_name_lbl.setWordWrap(True)
        mg2.addWidget(self.model_name_lbl)

        lay.addWidget(model_group)

        # VAE / Text Encoder
        ext_group = QGroupBox("VAE / Text Encoder (опционально)")
        eg = QVBoxLayout(ext_group)

        eg.addWidget(QLabel("ВАЕ (папка или файл):"))
        vr = QHBoxLayout()
        self.vae_edit = QLineEdit(); self.vae_edit.setPlaceholderText("Не выбран")
        vb = QPushButton("Указать...")
        vb.setMinimumWidth(90)
        vb.clicked.connect(self._browse_vae)
        vr.addWidget(self.vae_edit); vr.addWidget(vb)
        eg.addLayout(vr)

        eg.addWidget(QLabel("Text Encoder (папка или файл):"))
        tr = QHBoxLayout()
        self.te_edit = QLineEdit(); self.te_edit.setPlaceholderText("Не выбран")
        tb = QPushButton("Указать...")
        tb.setMinimumWidth(90)
        tb.clicked.connect(self._browse_te)
        tr.addWidget(self.te_edit); tr.addWidget(tb)
        eg.addLayout(tr)
        lay.addWidget(ext_group)

        # LoRA
        lora_group = QGroupBox("LoRA (можно несколько)")
        lg = QVBoxLayout(lora_group)
        lora_btns = QHBoxLayout()
        add_b = QPushButton("+ Добавить LoRA")
        rem_b = QPushButton("− Удалить")
        add_b.clicked.connect(self._add_lora)
        rem_b.clicked.connect(self._remove_lora)
        lora_btns.addWidget(add_b); lora_btns.addWidget(rem_b)
        lg.addLayout(lora_btns)
        self.lora_list = QListWidget()
        self.lora_list.setMaximumHeight(110)
        lg.addWidget(self.lora_list)
        lay.addWidget(lora_group)

        # Generation settings
        gen_group = QGroupBox("Настройки генерации")
        gg = QGridLayout(gen_group)

        gg.addWidget(QLabel("Шаги:"), 0, 0)
        self.steps_spin = QSpinBox(); self.steps_spin.setRange(1, 200); self.steps_spin.setValue(self._cfg["steps"])
        gg.addWidget(self.steps_spin, 0, 1)

        gg.addWidget(QLabel("CFG Scale:"), 1, 0)
        self.cfg_spin = QDoubleSpinBox(); self.cfg_spin.setRange(1.0, 30.0)
        self.cfg_spin.setValue(self._cfg["cfg_scale"]); self.cfg_spin.setSingleStep(0.5)
        gg.addWidget(self.cfg_spin, 1, 1)

        gg.addWidget(QLabel("Seed (-1 = случайный):"), 2, 0)
        self.seed_spin = QSpinBox(); self.seed_spin.setRange(-1, 2147483647)
        self.seed_spin.setValue(self._cfg["seed"]); self.seed_spin.setSpecialValueText("Случайный")
        gg.addWidget(self.seed_spin, 2, 1)

        gg.addWidget(QLabel("Sampler:"), 3, 0)
        self.sampler_combo = QComboBox()
        self.sampler_combo.addItems(["Euler a", "DPM++ 2M", "DPM++ 2M Karras", "DDIM", "LCM", "KDPM2 a", "Heun"])
        idx = self.sampler_combo.findText(self._cfg.get("sampler", "Euler a"))
        if idx >= 0: self.sampler_combo.setCurrentIndex(idx)
        gg.addWidget(self.sampler_combo, 3, 1)

        gg.addWidget(QLabel("Использовать FP16:"), 4, 0)
        self.fp16_check = QCheckBox()
        self.fp16_check.setChecked(self._cfg.get("fp16", True))
        self.fp16_check.setToolTip("Ускорение и экономия VRAM. Рекомендуется для RTX.")
        gg.addWidget(self.fp16_check, 4, 1)

        gg.addWidget(QLabel("Режим Low VRAM:"), 5, 0)
        self.low_vram_check = QCheckBox()
        self.low_vram_check.setChecked(self._cfg.get("low_vram", False))
        self.low_vram_check.setToolTip(
            "CPU offload: слои модели хранятся в RAM и передаются на GPU по одному.\n"
            "Медленнее, но позволяет работать с картами от 4 GB VRAM."
        )
        gg.addWidget(self.low_vram_check, 5, 1)

        lay.addWidget(gen_group)

        # Resolution
        res_group = QGroupBox("Разрешение вывода")
        rg = QVBoxLayout(res_group)
        self.resolution_combo = QComboBox()
        self.resolution_combo.addItems([
            "512x512", "640x640", "768x768", "1024x1024",
            "512x768 (вертикальное)", "768x512 (горизонтальное)",
            "576x1024 (9:16 вертикальное)", "1024x576 (16:9 горизонтальное)",
            "480x832 (WAN 480P вертикальное)", "832x480 (WAN 480P горизонтальное)",
            "720x1280 (WAN 720P вертикальное)", "1280x720 (WAN 720P горизонтальное)",
            "1080x1920 (WAN Full HD вертикальное)", "1920x1080 (Full HD горизонтальное)",
            "Своё…"
        ])
        self.resolution_combo.currentTextChanged.connect(self._on_resolution_changed)
        rg.addWidget(self.resolution_combo)

        cw = QHBoxLayout()
        cw.addWidget(QLabel("W:"))
        self.res_w = QSpinBox(); self.res_w.setRange(64, 4096); self.res_w.setValue(512)
        cw.addWidget(self.res_w)
        cw.addWidget(QLabel("H:"))
        self.res_h = QSpinBox(); self.res_h.setRange(64, 4096); self.res_h.setValue(512)
        cw.addWidget(self.res_h)
        self._custom_res = QWidget(); self._custom_res.setLayout(cw)
        self._custom_res.setVisible(False)
        rg.addWidget(self._custom_res)
        lay.addWidget(res_group)

        # Video settings
        self.video_group = QGroupBox("Настройки видео")
        vg = QGridLayout(self.video_group)

        vg.addWidget(QLabel("Количество кадров:"), 0, 0)
        self.frames_spin = QSpinBox()
        self.frames_spin.setRange(1, 99999)
        self.frames_spin.setValue(self._cfg.get("num_frames", 25))
        self.frames_spin.setToolTip(
            "Любое количество кадров.\n"
            "Для WAN I2V: при >100 кадрах автоматически\n"
            "включается пошаговая генерация (чанки по 25 кадров).\n"
            "Результат склеивается в одно видео."
        )
        vg.addWidget(self.frames_spin, 0, 1)

        vg.addWidget(QLabel("FPS (кадр/сек):"), 1, 0)
        self.fps_spin = QSpinBox(); self.fps_spin.setRange(1, 60); self.fps_spin.setValue(self._cfg.get("fps", 16))
        vg.addWidget(self.fps_spin, 1, 1)

        vg.addWidget(QLabel("Длительность:"), 2, 0)
        self.duration_lbl = QLabel("2.0 сек")
        self.duration_lbl.setStyleSheet("color: #7799cc;")
        vg.addWidget(self.duration_lbl, 2, 1)

        vg.addWidget(QLabel("Кадров за проход (WAN):"), 3, 0)
        self.chunk_spin = QSpinBox()
        self.chunk_spin.setRange(8, 200)
        self.chunk_spin.setValue(self._cfg.get("chunk_frames", 25))
        self.chunk_spin.setToolTip(
            "Для длинных видео (WAN I2V) — размер одного чанка.\n"
            "Меньше = экономия VRAM, дольше.\n"
            "Рекомендуется 25 для RTX 3060 12 ГБ."
        )
        vg.addWidget(self.chunk_spin, 3, 1)

        self.frames_spin.valueChanged.connect(self._update_duration)
        self.fps_spin.valueChanged.connect(self._update_duration)
        self._update_duration()

        self.motion_lbl = QLabel("Motion (SVD):")
        self.motion_spin = QSpinBox(); self.motion_spin.setRange(1, 255); self.motion_spin.setValue(127)
        self.motion_spin.setToolTip("Интенсивность движения для Image→Video (1=мало, 255=много)")
        vg.addWidget(self.motion_lbl, 3, 0)
        vg.addWidget(self.motion_spin, 3, 1)

        lay.addWidget(self.video_group)
        self.video_group.setVisible(False)

        # Output folder
        out_group = QGroupBox("Папка сохранения")
        og = QHBoxLayout(out_group)
        self.output_edit = QLineEdit(self._cfg["output_dir"])
        ob = QPushButton("📁"); ob.setFixedWidth(34)
        ob.clicked.connect(self._browse_output_dir)
        og.addWidget(self.output_edit)
        og.addWidget(ob)
        lay.addWidget(out_group)

        lay.addStretch()
        scroll.setWidget(w)
        return scroll

    # ── Center panel ──────────────────────────────────────────────────────────

    def _build_center(self):
        w = QWidget()
        lay = QVBoxLayout(w)
        lay.setSpacing(8)
        lay.setContentsMargins(4, 4, 4, 4)

        # Prompt
        pg = QGroupBox("Промт (описание желаемого результата)")
        pl = QVBoxLayout(pg)
        self.prompt_edit = QTextEdit()
        self.prompt_edit.setPlaceholderText(
            "Опишите что хотите получить на английском языке…\n\n"
            "Пример фото: a beautiful sunset over mountains, ultra realistic, 8K, cinematic lighting, RAW photo\n"
            "Пример видео: a cat walking on a sunny street, high quality, smooth motion"
        )
        self.prompt_edit.setMinimumHeight(130)
        pl.addWidget(self.prompt_edit)
        lay.addWidget(pg)

        # Negative prompt
        ng = QGroupBox("Негативный промт (что НЕ должно быть)")
        nl = QVBoxLayout(ng)
        self.neg_edit = QTextEdit()
        self.neg_edit.setMaximumHeight(90)
        self.neg_edit.setText(self._cfg.get("neg_prompt", ""))
        nl.addWidget(self.neg_edit)
        lay.addWidget(ng)

        # Image input (i2v)
        self.img_input_group = QGroupBox("Входное изображение (для Фото→Видео)")
        il = QHBoxLayout(self.img_input_group)
        self.input_img_edit = QLineEdit(); self.input_img_edit.setPlaceholderText("Перетащите или выберите файл…")
        ibtn = QPushButton("📂 Выбрать")
        ibtn.clicked.connect(self._browse_input_image)
        self.thumb_label = QLabel()
        self.thumb_label.setFixedSize(90, 70)
        self.thumb_label.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.thumb_label.setStyleSheet("border: 1px solid #2e2e50; border-radius: 4px; color: #44446a;")
        self.thumb_label.setText("нет")
        il.addWidget(self.input_img_edit)
        il.addWidget(ibtn)
        il.addWidget(self.thumb_label)
        lay.addWidget(self.img_input_group)
        self.img_input_group.setVisible(False)

        # Presets
        pre_group = QGroupBox("Быстрые пресеты")
        pre_lay = QHBoxLayout(pre_group)
        presets = {
            "📷 Реализм":    ", ultra realistic photo, 8K, RAW, photorealistic, sharp focus, DSLR",
            "🎨 Цифровой арт": ", digital art, concept art, artstation, stunning, detailed",
            "🌸 Аниме":      ", anime style, vibrant colors, high quality, studio ghibli",
            "👤 Портрет":    ", portrait photography, professional studio lighting, 4K, sharp",
            "🌌 Фантастика": ", fantasy landscape, epic, magical, cinematic, ultra detailed",
        }
        for name, suffix in presets.items():
            btn = QPushButton(name)
            btn.clicked.connect(lambda c=False, s=suffix: self._add_to_prompt(s))
            pre_lay.addWidget(btn)
        lay.addWidget(pre_group)

        lay.addStretch()

        # Generate / Cancel
        gen_row = QHBoxLayout()
        self.generate_btn = QPushButton("⚡  Генерировать")
        self.generate_btn.setObjectName("generateBtn")
        self.generate_btn.clicked.connect(self._start_generation)

        self.cancel_btn = QPushButton("✕")
        self.cancel_btn.setObjectName("cancelBtn")
        self.cancel_btn.setFixedWidth(52)
        self.cancel_btn.setMinimumHeight(52)
        self.cancel_btn.setEnabled(False)
        self.cancel_btn.clicked.connect(self._cancel)
        gen_row.addWidget(self.generate_btn)
        gen_row.addWidget(self.cancel_btn)
        lay.addLayout(gen_row)

        self.progress_bar = QProgressBar()
        self.progress_bar.setValue(0)
        lay.addWidget(self.progress_bar)

        self.status_lbl = QLabel("Готов к работе")
        self.status_lbl.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.status_lbl.setStyleSheet("color: #6666aa; font-size: 12px;")
        lay.addWidget(self.status_lbl)

        return w

    # ── Right panel ───────────────────────────────────────────────────────────

    def _build_right(self):
        w = QWidget()
        lay = QVBoxLayout(w)
        lay.setSpacing(6)
        lay.setContentsMargins(4, 4, 4, 4)

        preview_group = QGroupBox("Предпросмотр результата")
        pg = QVBoxLayout(preview_group)

        self.preview_tabs = QTabWidget()

        img_w = QWidget(); il = QVBoxLayout(img_w)
        self.image_label = QLabel("Результат появится здесь")
        self.image_label.setObjectName("previewLabel")
        self.image_label.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.image_label.setMinimumHeight(380)
        self.image_label.setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Expanding)
        il.addWidget(self.image_label)
        self.preview_tabs.addTab(img_w, "🖼 Изображение")

        self.video_player = QMediaPlayer()
        self.video_widget = QVideoWidget()
        self.video_player.setVideoOutput(self.video_widget)
        vid_w = QWidget(); vl = QVBoxLayout(vid_w)
        vl.addWidget(self.video_widget)
        ctrl = QHBoxLayout()
        self.play_btn = QPushButton("▶ Воспроизвести")
        self.play_btn.clicked.connect(self._toggle_play)
        self.stop_btn = QPushButton("⏹")
        self.stop_btn.setFixedWidth(40)
        self.stop_btn.clicked.connect(self.video_player.stop)
        ctrl.addWidget(self.play_btn)
        ctrl.addWidget(self.stop_btn)
        vl.addLayout(ctrl)
        self.video_player.playbackStateChanged.connect(self._on_play_state)
        self.preview_tabs.addTab(vid_w, "🎬 Видео")

        pg.addWidget(self.preview_tabs)
        lay.addWidget(preview_group)

        self.info_lbl = QLabel("")
        self.info_lbl.setStyleSheet("color: #6666aa; font-size: 11px;")
        self.info_lbl.setWordWrap(True)
        lay.addWidget(self.info_lbl)

        btn_row = QHBoxLayout()
        self.save_btn = QPushButton("💾 Сохранить копию…")
        self.save_btn.setObjectName("saveBtn")
        self.save_btn.setMinimumHeight(40)
        self.save_btn.setEnabled(False)
        self.save_btn.clicked.connect(self._save_output)

        self.open_btn = QPushButton("📂 Открыть папку")
        self.open_btn.setMinimumHeight(40)
        self.open_btn.clicked.connect(self._open_output_folder)

        btn_row.addWidget(self.save_btn)
        btn_row.addWidget(self.open_btn)
        lay.addLayout(btn_row)

        return w

    # ─── State ────────────────────────────────────────────────────────────────

    def _restore_state(self):
        path = self._cfg.get("model_path", "")
        if path:
            self.model_path_edit.setText(path)
            self.model_name_lbl.setText(Path(path).name if os.path.exists(path) else "⚠ Файл не найден")

        res = self._cfg.get("resolution", "512x512")
        idx = self.resolution_combo.findText(res)
        if idx >= 0:
            self.resolution_combo.setCurrentIndex(idx)
        else:
            self.resolution_combo.setCurrentText("512x512")

    def _save_state(self):
        self._cfg.update({
            "model_path":   self.model_path_edit.text(),
            "output_dir":   self.output_edit.text(),
            "steps":        self.steps_spin.value(),
            "cfg_scale":    self.cfg_spin.value(),
            "sampler":      self.sampler_combo.currentText(),
            "fp16":         self.fp16_check.isChecked(),
            "low_vram":     self.low_vram_check.isChecked(),
            "resolution":   self._get_resolution(),
            "fps":          self.fps_spin.value(),
            "num_frames":   self.frames_spin.value(),
            "chunk_frames": self.chunk_spin.value(),
            "seed":         self.seed_spin.value(),
            "neg_prompt":   self.neg_edit.toPlainText(),
        })
        save_config(self._cfg)

    # ─── GPU Monitor ──────────────────────────────────────────────────────────

    def _start_gpu_monitor(self):
        if torch.cuda.is_available():
            name = torch.cuda.get_device_name(0)
            total = torch.cuda.get_device_properties(0).total_memory / 1024**3
            self.gpu_status.setText(f"🟢 {name} ({total:.1f} GB VRAM)")
            self.gpu_status.setStyleSheet("color: #44bb44; font-size: 11px; padding: 0 8px;")
            self._gpu_mon = GpuMonitor()
            self._gpu_mon.updated.connect(lambda t: self.gpu_status.setText(t))
            self._gpu_mon.start()
        else:
            _gpu_hint = ""
            try:
                import subprocess as _subp
                _r = _subp.run(["nvidia-smi", "--query-gpu=name", "--format=csv,noheader"],
                               capture_output=True, text=True, timeout=3)
                if _r.returncode == 0 and _r.stdout.strip():
                    _gpu_hint = f" (GPU {_r.stdout.strip().splitlines()[0].strip()} найден, но PyTorch без CUDA)"
            except Exception:
                pass
            self.gpu_status.setText(f"🔴 GPU не найден — CPU режим{_gpu_hint}")
            self.gpu_status.setStyleSheet("color: #bb6644; font-size: 11px; padding: 0 8px;")
            self.gpu_status.setToolTip(
                "Пакет PyTorch+CUDA не установлен.\n"
                "Решение: Инструменты → Переустановить PyTorch+CUDA"
            )

    # ─── First Run ────────────────────────────────────────────────────────────

    def _check_first_run(self):
        if not (CACHE_DIR / "animatediff-motion-adapter-v1-5-2").exists():
            QTimer.singleShot(800, self._suggest_download)

    def _suggest_download(self):
        ret = QMessageBox.question(
            self,
            "Загрузить AI компоненты?",
            "Для генерации видео нужны дополнительные компоненты:\n"
            "• AnimateDiff (для Text→Video, ~1.8 GB)\n"
            "• Stable Video Diffusion (для Image→Video, ~10 GB)\n\n"
            "Хотите открыть менеджер загрузки?",
            QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No
        )
        if ret == QMessageBox.StandardButton.Yes:
            self._open_download_dialog()

    # ─── Model Scan ───────────────────────────────────────────────────────────

    def _browse_model_file(self):
        start_dir = str(Path(self.model_path_edit.text()).parent) \
            if self.model_path_edit.text() else str(Path.home())
        # Ask user which method to use: file or folder (for WAN pipelines)
        from PyQt6.QtWidgets import QMenu
        menu = QMenu(self)
        act_file   = menu.addAction("📄 Выбрать файл (.safetensors / .ckpt…)")
        act_folder = menu.addAction("📁 Выбрать папку модели (WAN 2.2 и др.)")
        chosen = menu.exec(QCursor.pos())
        if chosen == act_file:
            path, _ = QFileDialog.getOpenFileName(
                self, "Выбрать файл модели", start_dir,
                "Model Files (*.safetensors *.ckpt *.bin *.pt);;All Files (*)"
            )
        elif chosen == act_folder:
            path = QFileDialog.getExistingDirectory(
                self, "Выбрать папку с моделью", start_dir
            )
        else:
            return
        if path:
            self._set_model_path(path)

    def _set_model_path(self, path: str):
        self.model_path_edit.setText(path)
        self.model_name_lbl.setText(Path(path).name)
        self.status_lbl.setText(f"Модель: {Path(path).name}")
        self._add_to_recent(path)

    def _add_to_recent(self, path: str):
        recent = self._cfg.get("recent_models", [])
        if path in recent:
            recent.remove(path)
        recent.insert(0, path)
        recent = recent[:10]
        self._cfg["recent_models"] = recent

        self.recent_combo.blockSignals(True)
        self.recent_combo.clear()
        self.recent_combo.addItem("— история —")
        for p in recent:
            self.recent_combo.addItem(Path(p).name, p)
        self.recent_combo.blockSignals(False)

    def _on_recent_model_selected(self, idx: int):
        if idx <= 0:
            return
        path = self.recent_combo.itemData(idx)
        if path and os.path.exists(path):
            self._set_model_path(path)
        elif path:
            self.status_lbl.setText(f"⚠ Файл не найден: {Path(path).name}")
            self.model_name_lbl.setText(f"⚠ Файл не найден")
        self.recent_combo.setCurrentIndex(0)

    # ─── LoRA ─────────────────────────────────────────────────────────────────

    def _add_lora(self):
        dlg = LoraDialog(self)
        if dlg.exec() == QDialog.DialogCode.Accepted:
            data = dlg.result_data()
            if not os.path.exists(data["path"]):
                QMessageBox.warning(self, "Ошибка", "Файл LoRA не найден!")
                return
            self._loras.append(data)
            self.lora_list.addItem(f"  {data['name']}  ×{data['weight']}")

    def _remove_lora(self):
        row = self.lora_list.currentRow()
        if row >= 0:
            self.lora_list.takeItem(row)
            self._loras.pop(row)

    # ─── File dialogs ─────────────────────────────────────────────────────────

    def _browse_vae(self):
        menu = QMenu(self)
        act_file   = menu.addAction("U0001f4c4 Файл (.safetensors / .bin / .pt / .ckpt)")
        act_folder = menu.addAction("U0001f4c1 Папка с моделью")
        action = menu.exec(QCursor.pos())
        if action == act_file:
            path, _ = QFileDialog.getOpenFileName(
                self, "Выбрать VAE файл", "",
                "Model Files (*.safetensors *.bin *.pt *.ckpt);;All Files (*.*)"
            )
            if path: self.vae_edit.setText(path)
        elif action == act_folder:
            path = QFileDialog.getExistingDirectory(self, "Выбрать папку VAE")
            if path: self.vae_edit.setText(path)

    def _browse_te(self):
        menu = QMenu(self)
        act_file   = menu.addAction("U0001f4c4 Файл (.safetensors / .bin / .pt)")
        act_folder = menu.addAction("U0001f4c1 Папка с Text Encoder")
        action = menu.exec(QCursor.pos())
        if action == act_file:
            path, _ = QFileDialog.getOpenFileName(
                self, "Выбрать Text Encoder файл", "",
                "Model Files (*.safetensors *.bin *.pt *.ckpt);;All Files (*.*)"
            )
            if path: self.te_edit.setText(path)
        elif action == act_folder:
            path = QFileDialog.getExistingDirectory(self, "Выбрать папку Text Encoder")
            if path: self.te_edit.setText(path)

    def _browse_folder(self, edit: QLineEdit, title: str):
        path = QFileDialog.getExistingDirectory(self, f"Выбрать {title}")
        if path:
            edit.setText(path)

    def _browse_input_image(self):
        path, _ = QFileDialog.getOpenFileName(
            self, "Выбрать изображение", "",
            "Images (*.png *.jpg *.jpeg *.webp *.bmp *.tiff)"
        )
        if path:
            self.input_img_edit.setText(path)
            pix = QPixmap(path).scaled(90, 70, Qt.AspectRatioMode.KeepAspectRatio,
                                       Qt.TransformationMode.SmoothTransformation)
            self.thumb_label.setPixmap(pix)

    def _browse_output_dir(self):
        folder = QFileDialog.getExistingDirectory(self, "Папка для сохранения")
        if folder:
            self.output_edit.setText(folder)

    # ─── Resolution ───────────────────────────────────────────────────────────

    def _on_resolution_changed(self, text: str):
        self._custom_res.setVisible(text == "Своё…")
        if text != "Своё…":
            parts = text.split(" ")[0].split("x")
            if len(parts) == 2:
                try:
                    self.res_w.setValue(int(parts[0]))
                    self.res_h.setValue(int(parts[1]))
                except ValueError:
                    pass

    def _get_resolution(self) -> str:
        return f"{self.res_w.value()}x{self.res_h.value()}"

    # ─── Mode ─────────────────────────────────────────────────────────────────

    def _on_mode_changed(self, idx: int):
        is_video = idx in (1, 2)
        self.video_group.setVisible(is_video)
        self.img_input_group.setVisible(idx == 2)

    # ─── Duration ─────────────────────────────────────────────────────────────

    def _update_duration(self):
        fps = self.fps_spin.value()
        if fps > 0:
            self.duration_lbl.setText(f"{self.frames_spin.value() / fps:.1f} сек")

    # ─── Prompts ──────────────────────────────────────────────────────────────

    def _add_to_prompt(self, suffix: str):
        cur = self.prompt_edit.toPlainText().rstrip()
        if cur and not cur.endswith(","):
            cur += ","
        self.prompt_edit.setPlainText(cur + suffix)
        self.prompt_edit.moveCursor(self.prompt_edit.textCursor().End)

    # ─── Generation ───────────────────────────────────────────────────────────

    def _start_generation(self):
        prompt = self.prompt_edit.toPlainText().strip()
        if not prompt:
            QMessageBox.warning(self, "Пустой промт", "Введите описание того, что нужно сгенерировать!")
            return

        model_path = self.model_path_edit.text().strip()
        mode_idx = self.mode_tabs.currentIndex()

        if mode_idx in (0, 1) and not model_path:
            QMessageBox.warning(self, "Модель не выбрана",
                "Нажмите кнопку 📁 Выбрать модель и укажите файл .safetensors или .ckpt.")
            return

        self._on_resolution_changed(self.resolution_combo.currentText())

        modes = {0: "t2i", 1: "t2v", 2: "i2v"}
        config = {
            "mode":         modes[mode_idx],
            "model_path":   model_path or "",
            "prompt":       prompt,
            "neg_prompt":   self.neg_edit.toPlainText().strip(),
            "steps":        self.steps_spin.value(),
            "cfg_scale":    self.cfg_spin.value(),
            "seed":         self.seed_spin.value(),
            "sampler":      self.sampler_combo.currentText(),
            "fp16":         self.fp16_check.isChecked(),
            "low_vram":     self.low_vram_check.isChecked(),
            "resolution":   self._get_resolution(),
            "vae_path":     self.vae_edit.text().strip() or None,
            "text_encoder_path": self.te_edit.text().strip() or None,
            "loras":        list(self._loras),
            "output_dir":   self.output_edit.text() or str(OUTPUT_DIR),
            "num_frames":   self.frames_spin.value(),
            "chunk_frames": self.chunk_spin.value(),
            "fps":          self.fps_spin.value(),
            "motion_bucket": self.motion_spin.value(),
            "input_image":  self.input_img_edit.text().strip(),
        }

        self._worker = GeneratorThread(config)
        self._worker.progress.connect(lambda v, t: (
            self.progress_bar.setValue(v),
            self.status_lbl.setText(t)
        ))
        self._worker.finished.connect(self._on_done)
        self._worker.error.connect(self._on_error)

        self.generate_btn.setEnabled(False)
        self.cancel_btn.setEnabled(True)
        self.save_btn.setEnabled(False)
        self.progress_bar.setValue(0)
        self.status_lbl.setText("Запуск генерации…")
        self._worker.start()

    def _cancel(self):
        if self._worker and self._worker.isRunning():
            self._worker.cancel()
            self.status_lbl.setText("Отмена…")

    def _on_done(self, image, path: str):
        self.progress_bar.setValue(100)
        self.generate_btn.setEnabled(True)
        self.cancel_btn.setEnabled(False)
        self.save_btn.setEnabled(True)
        self._last_path  = path
        self._last_image = image

        if image is not None:
            self._show_image(image)
            self.preview_tabs.setCurrentIndex(0)
            self.status_lbl.setText(f"✅ Готово: {Path(path).name}")
        else:
            self._show_video(path)
            self.preview_tabs.setCurrentIndex(1)
            self.status_lbl.setText(f"✅ Видео: {Path(path).name}")

        self.info_lbl.setText(f"Сохранено: {path}")

    def _on_error(self, msg: str):
        self.progress_bar.setValue(0)
        self.generate_btn.setEnabled(True)
        self.cancel_btn.setEnabled(False)
        self.status_lbl.setText("❌ Ошибка")
        QMessageBox.critical(self, "Ошибка генерации", msg)

    # ─── Preview ──────────────────────────────────────────────────────────────

    def _show_image(self, image: Image.Image):
        data = image.convert("RGB").tobytes("raw", "RGB")
        qimg = QImage(data, image.width, image.height, QImage.Format.Format_RGB888)
        pix  = QPixmap.fromImage(qimg)
        size = self.image_label.size()
        scaled = pix.scaled(size, Qt.AspectRatioMode.KeepAspectRatio,
                            Qt.TransformationMode.SmoothTransformation)
        self.image_label.setPixmap(scaled)

    def _show_video(self, path: str):
        self.video_player.setSource(QUrl.fromLocalFile(path))
        self.video_player.play()

    def _toggle_play(self):
        st = self.video_player.playbackState()
        if st == QMediaPlayer.PlaybackState.PlayingState:
            self.video_player.pause()
        else:
            self.video_player.play()

    def _on_play_state(self, state):
        self.play_btn.setText(
            "⏸ Пауза" if state == QMediaPlayer.PlaybackState.PlayingState else "▶ Воспроизвести"
        )

    # ─── Save / Open ──────────────────────────────────────────────────────────

    def _save_output(self):
        if not self._last_path or not os.path.exists(self._last_path):
            QMessageBox.warning(self, "Нет результата", "Сначала выполните генерацию!")
            return
        ext = Path(self._last_path).suffix
        is_video = ext.lower() in (".mp4", ".avi", ".mov")
        flt = "Video Files (*.mp4)" if is_video else "Images (*.png *.jpg)"
        dest, _ = QFileDialog.getSaveFileName(
            self, "Сохранить файл", str(Path.home() / f"output{ext}"), flt
        )
        if dest:
            shutil.copy2(self._last_path, dest)
            QMessageBox.information(self, "Сохранено", f"Файл сохранён:\n{dest}")

    def _open_output_folder(self):
        folder = self.output_edit.text() or str(OUTPUT_DIR)
        os.makedirs(folder, exist_ok=True)
        os.startfile(folder)

    # ─── Tools ────────────────────────────────────────────────────────────────

    def _open_download_dialog(self):
        dlg = DownloadDialog(self)
        dlg.exec()

    def _clear_vram(self):
        gc.collect()
        if torch.cuda.is_available():
            torch.cuda.empty_cache()
            torch.cuda.synchronize()
        QMessageBox.information(self, "VRAM очищен", "Кэш видеопамяти очищен.")

    def _reinstall_cuda(self):
        import subprocess
        msg = QMessageBox(self)
        msg.setWindowTitle("Переустановка PyTorch+CUDA")
        msg.setText(
            "Будет выполнена переустановка PyTorch c CUDA 12.1 (для RTX 30xx/40xx).\n\n"
            "Важно: нужно соединение с интернетом. Процесс займёт 3-10 минут."
        )
        msg.setStandardButtons(QMessageBox.StandardButton.Ok | QMessageBox.StandardButton.Cancel)
        if msg.exec() != QMessageBox.StandardButton.Ok:
            return
        import sys, os
        venv_pip = str(APP_DIR / "venv" / "Scripts" / "pip.exe")
        pip_cmd = venv_pip if os.path.exists(venv_pip) else sys.executable.replace("python.exe", "pip.exe")
        cmd = [
            pip_cmd, "install", "--upgrade",
            "torch", "torchvision", "torchaudio",
            "--index-url", "https://download.pytorch.org/whl/cu121"
        ]
        try:
            result = subprocess.run(cmd, capture_output=True, text=True, timeout=600)
            if result.returncode == 0:
                QMessageBox.information(self, "Готово",
                    "PyTorch+CUDA успешно установлен!\nПерезапустите программу.")
                self._start_gpu_monitor()
            else:
                QMessageBox.critical(self, "Ошибка",
                    f"Не удалось установить:\n{result.stderr[-600:]}")
        except Exception as e:
            QMessageBox.critical(self, "Ошибка", str(e))

    def _show_gpu_diag(self):
        import subprocess
        info_lines = [
            f"torch: {__import__('torch').__version__}",
            f"cuda available: {__import__('torch').cuda.is_available()}",
        ]
        try:
            import torch
            if torch.cuda.is_available():
                info_lines.append(f"GPU: {torch.cuda.get_device_name(0)}")
                info_lines.append(f"CUDA version: {torch.version.cuda}")
        except Exception as e:
            info_lines.append(f"torch error: {e}")
        try:
            r = subprocess.run(["nvidia-smi", "--query-gpu=name,driver_version,memory.total",
                                "--format=csv,noheader"],
                               capture_output=True, text=True, timeout=3)
            info_lines.append(f"nvidia-smi: {r.stdout.strip() or r.stderr.strip()}")
        except Exception as e:
            info_lines.append(f"nvidia-smi: {e}")
        QMessageBox.information(self, "Диагностика GPU",
                                "\n".join(info_lines))

    def _show_about(self):
        QMessageBox.about(
            self, "О программе",
            "<b>AI Studio</b><br>"
            "Генератор изображений и видео на базе ИИ<br><br>"
            "Поддерживаемые режимы:<br>"
            "• Text → Image (Stable Diffusion)<br>"
            "• Text → Video (AnimateDiff)<br>"
            "• Image → Video (Stable Video Diffusion)<br><br>"
            "GPU-ускорение через CUDA (NVIDIA)<br>"
            "Поддержка LoRA, VAE, Custom Text Encoder"
        )

    # ─── Lifecycle ────────────────────────────────────────────────────────────

    def resizeEvent(self, event):
        super().resizeEvent(event)
        if self._last_image:
            self._show_image(self._last_image)

    def closeEvent(self, event):
        self._save_state()
        if self._worker and self._worker.isRunning():
            self._worker.cancel()
            self._worker.wait(3000)
        event.accept()


# ─────────────────────────────────────────────────────────────────────────────
#  Entry Point
# ─────────────────────────────────────────────────────────────────────────────

def main():
    # Reserve 1 GB VRAM headroom before anything touches the GPU
    apply_vram_limit()

    # Must be called BEFORE QApplication is created
    QApplication.setHighDpiScaleFactorRoundingPolicy(
        Qt.HighDpiScaleFactorRoundingPolicy.PassThrough
    )

    app = QApplication(sys.argv)
    app.setApplicationName("AI Studio")
    app.setOrganizationName("AI Studio")
    app.setStyleSheet(DARK_STYLE)

    win = MainWindow()
    win.show()
    sys.exit(app.exec())


if __name__ == "__main__":
    main()
