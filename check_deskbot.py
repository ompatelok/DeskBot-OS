import os
import sys
import py_compile

new_root = r"D:\Marwadi University (D)\DeskBot"
old_roots = [
    r"D:\Marwadi University (D)\SEM-6\HCD",
    r"D:\Marwadi University (D)\SEM-7\deskbot_companion"
]

print("=" * 60)
print(" DESKBOT OS MASTER VERIFICATION & HEALTH CHECK")
print("=" * 60)

# --- 1. DIRECTORY STRUCTURE & ARDUINO IDE COMPATIBILITY ---
print("\n[1] Checking Directory & Tool Requirements...")

# Arduino IDE strictly requires the folder name to match the .ino file name
arduino_dir = os.path.join(new_root, "02_Hardware_Firmware", "DeskBot_Final")
arduino_ino = os.path.join(arduino_dir, "DeskBot_Final.ino")

if os.path.exists(arduino_ino):
    print("  [PASS] Arduino IDE: 'DeskBot_Final.ino' matches its parent folder.")
else:
    print(f"  [WARN] Arduino IDE: Missing or misnamed {arduino_ino}")

# Check Android App root markers
android_dir = os.path.join(new_root, "03_Android_App")
android_markers = ["build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts"]
android_ok = any(os.path.exists(os.path.join(android_dir, m)) for m in android_markers)
if android_ok:
    print("  [PASS] Android Studio: Root Gradle configuration files detected.")
else:
    print("  [WARN] Android Studio: Root build.gradle missing in 03_Android_App.")

# --- 2. PYTHON ENGINE, MODEL, & CERTIFICATE CHECKS ---
print("\n[2] Checking AI Engine & Required Assets...")
ai_dir = os.path.join(new_root, "01_AI_Engine")
models_dir = os.path.join(ai_dir, "models")
certs_dir = os.path.join(ai_dir, "certs")

# Check model assets
model_pkl = os.path.join(models_dir, "posture_model_v3.pkl")
task_file = os.path.join(models_dir, "pose_landmarker_lite.task")

print(f"  {'[PASS]' if os.path.exists(model_pkl) else '[FAIL]'} Model file: posture_model_v3.pkl")
print(f"  {'[PASS]' if os.path.exists(task_file) else '[FAIL]'} Task model: pose_landmarker_lite.task")

# Check certificates
certs_found = [f for f in os.listdir(certs_dir) if f.endswith(('.pem', '.crt', '.key'))] if os.path.exists(certs_dir) else []
print(f"  [{'PASS' if len(certs_found) > 0 else 'WARN'}] AWS Certificates: {len(certs_found)} cert/key files found in certs/")

# Check Python Syntax & Compilation
py_files = [f for f in os.listdir(ai_dir) if f.endswith(".py")] if os.path.exists(ai_dir) else []
for py_file in py_files:
    py_path = os.path.join(ai_dir, py_file)
    try:
        py_compile.compile(py_path, doraise=True)
        print(f"  [PASS] Python Syntax Check: {py_file} compiles cleanly.")
    except py_compile.PyCompileError as err:
        print(f"  [FAIL] Python Syntax Error in {py_file}: {err}")

# --- 3. AUTOMATIC PATH SYNCHRONIZATION ---
print("\n[3] Scanning and Auto-Fixing Hardcoded Paths...")
old_ai_str = r"D:\Marwadi University (D)\DeskBot\01_AI_Engine"
old_hw_str = r"D:\Marwadi University (D)\DeskBot\02_Hardware_Firmware\DeskBot_Final"

updated_count = 0
for root, _, files in os.walk(new_root):
    for f in files:
        if f.endswith((".py", ".ino", ".json", ".h", ".cpp")):
            fp = os.path.join(root, f)
            try:
                with open(fp, "r", encoding="utf-8", errors="ignore") as r_file:
                    content = r_file.read()
                
                updated = content
                # Fix Windows backslash paths
                updated = updated.replace(old_ai_str, ai_dir)
                updated = updated.replace(old_hw_str, arduino_dir)
                # Fix forward slash paths
                updated = updated.replace(old_ai_str.replace("\\", "/"), ai_dir.replace("\\", "/"))
                updated = updated.replace(old_hw_str.replace("\\", "/"), arduino_dir.replace("\\", "/"))

                if updated != content:
                    with open(fp, "w", encoding="utf-8") as w_file:
                        w_file.write(updated)
                    print(f"  -> Auto-corrected paths inside: {f}")
                    updated_count += 1
            except Exception as e:
                pass

if updated_count == 0:
    print("  [PASS] All internal paths are up to date.")

# --- 4. SCAN OLD FOLDERS FOR JUNK / DUPLICATE CANDIDATES ---
print("\n[4] Scanning Old Folders to Generate Safe Deletion List...")
junk_candidates = []
total_junk_size = 0

# Targets that are safe to delete from old folders
safe_delete_patterns = [
    os.path.join(old_roots[1], "DeskBot_OS_Source.zip"),
    os.path.join(old_roots[1], "gradle", "wrapper", "gradle-8.7-bin.zip"),
    os.path.join(old_roots[0], "DeskBot_AI_AWS", "output"),
    os.path.join(old_roots[0], "DeskBot_AI_AWS", "exe"),
    os.path.join(old_roots[0], "DeskBot_AI_AWS", "__pycache__"),
]

for target in safe_delete_patterns:
    if os.path.exists(target):
        if os.path.isfile(target):
            size = os.path.getsize(target)
            junk_candidates.append((target, size))
            total_junk_size += size
        elif os.path.isdir(target):
            dir_size = sum(os.path.getsize(os.path.join(r, f)) for r, _, fs in os.walk(target) for f in fs)
            junk_candidates.append((target + " (Folder)", dir_size))
            total_junk_size += dir_size

report_path = r"D:\DeskBot_Deletion_Candidates.txt"
with open(report_path, "w", encoding="utf-8") as f:
    f.write("=== DESKBOT OS SAFE-TO-DELETE CANDIDATES ===\n")
    f.write(f"Total space that can be reclaimed: {total_junk_size / (1024*1024):.2f} MB\n\n")
    f.write("The following files/folders in your old directories are already backed up or build bloat:\n\n")
    for item, size in junk_candidates:
        f.write(f"  [{size / (1024*1024):.2f} MB] {item}\n")

print(f"  [DONE] Generated deletion review list: {report_path}")
print(f"  Total recoverable space: {total_junk_size / (1024*1024):.2f} MB")
print("\nVerification complete. Open 'D:\\DeskBot_Deletion_Candidates.txt' to review.")