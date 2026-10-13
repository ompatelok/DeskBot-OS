import sys
import json
import argparse
from pathlib import Path

def get_comment_marker(file_path):
    """Returns the appropriate comment marker based on the file extension."""
    ext = file_path.suffix.lower()
    if ext in ['.kt', '.java', '.cpp', '.c', '.h', '.js', '.ts', '.gradle']:
        return "\n\n// --- inserted by codegen ---\n"
    elif ext in ['.xml', '.html']:
        return "\n\n<!-- --- inserted by codegen --- -->\n"
    else:
        return "\n\n# --- inserted by codegen ---\n"

def process_spec(spec_data, dry_run):
    created = []
    appended = []
    skipped = []
    errors = []

    files = spec_data.get("files", [])
    
    for item in files:
        try:
            target_path = Path(item["path"])
            content = item["content"]
            
            # Idempotency check & Appending
            if target_path.exists():
                existing_content = target_path.read_text(encoding="utf-8")
                
                # If the exact block of code already exists, skip it
                if content.strip() in existing_content:
                    skipped.append(str(target_path))
                    continue
                
                if not dry_run:
                    marker = get_comment_marker(target_path)
                    with target_path.open("a", encoding="utf-8") as f:
                        f.write(f"{marker}{content}\n")
                appended.append(str(target_path))
                
            # Creating new file
            else:
                if not dry_run:
                    target_path.parent.mkdir(parents=True, exist_ok=True)
                    target_path.write_text(f"{content}\n", encoding="utf-8")
                created.append(str(target_path))
                
        except Exception as e:
            errors.append(f"{item.get('path', 'Unknown')} - {str(e)}")

    # Print Summary
    print("\n" + "="*40)
    print(f"Codegen Summary {'(DRY RUN)' if dry_run else ''}")
    print("="*40)
    print(f"Created  : {len(created)}")
    for f in created: print(f"  + {f}")
    
    print(f"\nAppended : {len(appended)}")
    for f in appended: print(f"  ~ {f}")
    
    print(f"\nSkipped (Already exists) : {len(skipped)}")
    for f in skipped: print(f"  - {f}")
    
    if errors:
        print(f"\nErrors   : {len(errors)}")
        for e in errors: print(f"  ! {e}")
    print("="*40 + "\n")

def main():
    parser = argparse.ArgumentParser(description="Standalone codegen tool.")
    parser.add_argument("spec", nargs="?", help="Path to the JSON spec file (reads from stdin if omitted).")
    parser.add_argument("--dry-run", action="store_true", help="Show what would happen without writing files.")
    
    args = parser.parse_args()
    
    try:
        if args.spec:
            with open(args.spec, 'r', encoding='utf-8') as f:
                spec_data = json.load(f)
        else:
            if sys.stdin.isatty():
                print("Error: No spec file provided and no data piped via stdin.")
                sys.exit(1)
            spec_data = json.load(sys.stdin)
            
        process_spec(spec_data, args.dry_run)
        
    except json.JSONDecodeError as e:
        print(f"Error parsing JSON: {e}")
        sys.exit(1)
    except Exception as e:
        print(f"Unexpected error: {e}")
        sys.exit(1)

if __name__ == "__main__":
    main()  