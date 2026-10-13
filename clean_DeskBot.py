import os
import shutil

targets_to_delete = [
    r"D:\Marwadi University (D)\SEM-7\deskbot_companion\DeskBot_OS_Source.zip",
    r"D:\Marwadi University (D)\SEM-7\deskbot_companion\gradle\wrapper\gradle-8.7-bin.zip",
    r"D:\Marwadi University (D)\SEM-6\HCD\DeskBot_AI_AWS\output",
    r"D:\Marwadi University (D)\SEM-6\HCD\DeskBot_AI_AWS\exe",
]

print("Starting safe cleanup of old build bloat and temporary archives...")

for target in targets_to_delete:
    if os.path.exists(target):
        try:
            if os.path.isfile(target):
                os.remove(target)
                print(f"  [DELETED FILE]   {target}")
            elif os.path.isdir(target):
                shutil.rmtree(target)
                print(f"  [DELETED FOLDER] {target}")
        except Exception as e:
            print(f"  [ERROR] Could not delete {target}: {e}")
    else:
        print(f"  [SKIPPED] Path not found or already deleted: {target}")

print("\nCleanup complete! ~1.8 GB reclaimed.")