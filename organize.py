import os
import shutil
import py_compile

root = r"D:\Marwadi University (D)\DeskBot"

print("=" * 65)
print(" DESKBOT OS RECURSIVE REORGANIZATION & DEEP AUDIT")
print("=" * 65)

# --- 1. DEFINE CATEGORIZED DIRECTORIES ---
ai_dir = os.path.join(root, "01_AI_Engine")
docs_dir = os.path.join(root, "04_Docs_and_Media")

target_folders = {
    # AI Engine subcategories
    "ai_training": os.path.join(ai_dir, "training_pipeline"),
    "ai_legacy": os.path.join(ai_dir, "legacy_versions"),
    "ai_models": os.path.join(ai_dir, "models"),
    "ai_certs": os.path.join(ai_dir, "certs"),
    
    # Docs & Media subcategories
    "reports": os.path.join(docs_dir, "Reports"),
    "presentations": os.path.join(docs_dir, "Presentations"),
    "cad_3d": os.path.join(docs_dir, "CAD_3D_Printing"),
    "ui_branding": os.path.join(docs_dir, "UI_Assets_and_Icons"),
    "demo_videos": os.path.join(docs_dir, "Demo_Videos"),
    "binaries": os.path.join(docs_dir, "Release_Binaries"),
}

for folder_path in target_folders.values():
    os.makedirs(folder_path, exist_ok=True)

# --- 2. ORGANIZE AI ENGINE ---
print("\n[1] Categorizing AI Engine files...")

# Move legacy / alternative inference scripts
legacy_scripts = [
    "posture_inference_live.py",
    "posture_interference_live_V2.py",
    "posture_interference_live_V3_stable.py",
]
for script in legacy_scripts:
    p = os.path.join(ai_dir, script)
    if os.path.exists(p):
        shutil.move(p, os.path.join(target_folders["ai_legacy"], script))
        print(f"  -> Moved to legacy_versions/: {script}")

# Move training and data collector scripts
training_scripts = ["data_collector.py", "train_model.py"]
for script in training_scripts:
    p = os.path.join(ai_dir, script)
    if os.path.exists(p):
        shutil.move(p, os.path.join(target_folders["ai_training"], script))
        print(f"  -> Moved to training_pipeline/: {script}")

# Move training datasets (.csv, .npy, .npz) if found in root of AI Engine
for item in os.listdir(ai_dir):
    item_path = os.path.join(ai_dir, item)
    if os.path.isfile(item_path):
        if item.endswith((".csv", ".npy", ".npz", ".jsonl")):
            shutil.move(item_path, os.path.join(target_folders["ai_training"], item))
            print(f"  -> Moved dataset to training_pipeline/: {item}")

# --- 3. ORGANIZE DOCS, ASSETS, AND MEDIA ---
print("\n[2] Categorizing Assets, Reports, and Media...")

# Inspect any loose assets in Assets_and_Reports or root Docs folder
legacy_asset_dir = os.path.join(docs_dir, "Assets_and_Reports")
scan_sources = [legacy_asset_dir, docs_dir]

for src in scan_sources:
    if os.path.exists(src):
        for item in list(os.listdir(src)):
            item_path = os.path.join(src, item)
            if not os.path.isfile(item_path):
                continue
            
            ext = os.path.splitext(item)[1].lower()
            dest = None
            
            if ext in [".docx", ".pdf", ".txt"]:
                dest = target_folders["reports"]
            elif ext in [".pptx", ".key"]:
                dest = target_folders["presentations"]
            elif ext in [".stl"]:
                dest = target_folders["cad_3d"]
            elif ext in [".png", ".svg", ".jpg", ".jpeg", ".ico", ".gif"]:
                dest = target_folders["ui_branding"]
            elif ext in [".mp4", ".mov", ".avi"]:
                dest = target_folders["demo_videos"]
            elif ext in [".exe", ".zip"]:
                dest = target_folders["binaries"]
                
            if dest and item_path != os.path.join(dest, item):
                shutil.move(item_path, os.path.join(dest, item))
                print(f"  -> Categorized [{ext}]: {item} -> {os.path.basename(dest)}/")

# Remove empty legacy asset folder if drained
if os.path.exists(legacy_asset_dir) and not os.listdir(legacy_asset_dir):
    os.rmdir(legacy_asset_dir)

# --- 4. DEEP AUDIT & PATH VALIDATION ---
print("\n[3] Validating Core AI Engine Execution & Model Paths...")
active_engine = os.path.join(ai_dir, "posture_interference_live_V3.py")

if os.path.exists(active_engine):
    with open(active_engine, "r", encoding="utf-8", errors="ignore") as f:
        code = f.read()

    # Ensure model and cert paths point correctly
    fixes = [
        ('"posture_model_v3.pkl"', '"models/posture_model_v3.pkl"'),
        ("'posture_model_v3.pkl'", "'models/posture_model_v3.pkl'"),
        ('"pose_landmarker_lite.task"', '"models/pose_landmarker_lite.task"'),
        ("'pose_landmarker_lite.task'", "'models/pose_landmarker_lite.task'"),
    ]
    
    updated_code = code
    for old_ref, new_ref in fixes:
        if old_ref in updated_code and new_ref not in updated_code:
            updated_code = updated_code.replace(old_ref, new_ref)
            
    if updated_code != code:
        with open(active_engine, "w", encoding="utf-8") as f:
            f.write(updated_code)
        print("  -> Auto-synced model references in posture_interference_live_V3.py")

    try:
        py_compile.compile(active_engine, doraise=True)
        print("  [PASS] Production AI Engine (V3) syntax verified.")
    except py_compile.PyCompileError as e:
        print(f"  [FAIL] Syntax issue in V3 script: {e}")
else:
    print("  [WARN] posture_interference_live_V3.py not found at root of 01_AI_Engine.")

# --- 5. PRINT DIRECTORY CATALOG ---
print("\n[4] Complete DeskBot Folder Catalog:")
for root_path, dirs, files in os.walk(root):
    # Skip deep Android build/internal trees to keep output clean
    if any(skip in root_path for skip in [".gradle", ".git", "app\\build", "app\\src"]):
        continue
    rel = os.path.relpath(root_path, root)
    indent = "  " * rel.count(os.sep)
    folder_name = os.path.basename(root_path) if rel != "." else "DeskBot (ROOT)"
    print(f"{indent}[FOLDER] {folder_name}/")
    for file in files:
        if file.endswith((".py", ".ino", ".json", ".h", ".docx", ".pptx", ".stl", ".exe")):
            print(f"{indent}   - {file}")

print("\nDeep organization and audit complete!")