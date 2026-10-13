import os
from pathlib import Path

def comment_out_dnd():
    print("=== AUTO-FIXER: COMMENTING OUT DND CODE ===")
    
    # 1. Target the main UI / Composable file where DND switch lives
    # Searching common paths for Jetpack Compose UI files
    target_files = []
    for root, dirs, files in os.walk("."):
        for file in files:
            if file.endswith(".kt") and ("Main" in file or "UI" in file or "Screen" in file or "Dashboard" in file or "App" in file):
                target_files.append(Path(root) / file)
                
    modified_count = 0
    for path in target_files:
        try:
            content = path.read_text(encoding="utf-8")
            if "DND" in content or "dnd" in content:
                lines = content.splitlines()
                new_lines = []
                in_dnd_block = False
                bracket_count = 0
                
                for line in lines:
                    # Look for DND UI blocks or Composables and comment them out safely
                    if "Remote DND Mode" in line or "DndSwitch" in line or "setDndMode" in line or ('"dnd"' in line):
                        new_lines.append(f"    // [COMMENTED OUT DND] {line}")
                        modified_count += 1
                    else:
                        new_lines.append(line)
                        
                path.write_text("\n".join(new_lines), encoding="utf-8")
                print(f"[UPDATED] Processed UI file: {path.name}")
        except Exception as e:
            print(f"[WARNING] Could not read {path}: {e}")

    # 2. Also neutralize the MqttManager dnd call if needed
    mqtt_path = Path("app/src/main/java/com/deskbot/os/MqttManager.kt")
    if mqtt_path.exists():
        content = mqtt_path.read_text(encoding="utf-8")
        if "setDndMode" in content:
            # Comment out the active payload transmission for dnd
            new_content = content.replace("fun setDndMode(", "// fun setDndMode(")
            new_content = new_content.replace(".put(\"dnd\"", "// .put(\"dnd\"")
            mqtt_path.write_text(new_content, encoding="utf-8")
            print("[UPDATED] Neutralized setDndMode inside MqttManager.kt")

    print("\n=== DND TEMPORARILY DISABLED & COMMENTED OUT ===")

if __name__ == "__main__":
    comment_out_dnd()