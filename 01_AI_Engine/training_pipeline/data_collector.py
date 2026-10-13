import cv2
import mediapipe as mp
import csv
import os
import time
import requests

def main():
    csv_path = 'posture_data_real.csv'
    pose_model_path = 'models/pose_landmarker_lite.task'
    
    # --- AUTO-STOP CONFIGURATION ---
    # 300 frames at ~30 FPS = exactly 10 seconds of data per person
    SESSION_FRAME_LIMIT = 300 

    # 1. Download model if missing
    if not os.path.exists(pose_model_path):
        print("Downloading MediaPipe model...")
        url = "https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/1/pose_landmarker_lite.task"
        r = requests.get(url, allow_redirects=True)
        open(pose_model_path, 'wb').write(r.content)

    # 2. Initialize MediaPipe
    BaseOptions = mp.tasks.BaseOptions
    PoseLandmarker = mp.tasks.vision.PoseLandmarker
    PoseLandmarkerOptions = mp.tasks.vision.PoseLandmarkerOptions
    VisionRunningMode = mp.tasks.vision.RunningMode

    options = PoseLandmarkerOptions(
        base_options=BaseOptions(model_asset_path=pose_model_path),
        running_mode=VisionRunningMode.VIDEO,
        min_pose_detection_confidence=0.6, 
        min_tracking_confidence=0.6
    )
    landmarker = PoseLandmarker.create_from_options(options)

    # 3. Setup CSV & Count Existing Data for Balance
    header = ['nose_x', 'nose_y', 'eye_l_x', 'eye_l_y', 'eye_r_x', 'eye_r_y', 'shoulder_l_x', 'shoulder_l_y', 'shoulder_r_x', 'shoulder_r_y', 'label']
    file_exists = os.path.exists(csv_path)
    
    counts = {"Good Posture": 0, "Slouching": 0, "Not At Desk": 0}

    if file_exists:
        with open(csv_path, 'r') as f:
            reader = csv.reader(f)
            next(reader, None)
            for row in reader:
                if len(row) > 10 and row[10] in counts:
                    counts[row[10]] += 1

    try:
        csv_file = open(csv_path, 'a', newline='')
        writer = csv.writer(csv_file)
        if not file_exists or os.stat(csv_path).st_size == 0: 
            writer.writerow(header)

        cap = cv2.VideoCapture(0)
        if not cap.isOpened():
            raise IOError("Cannot open webcam.")

        print("\n--- ENTERPRISE DATA COLLECTOR (AUTO-STOP ENABLED) ---")
        
        recording_label = None
        frames_recorded_this_session = 0

        while cap.isOpened():
            ret, frame = cap.read()
            if not ret: break
            
            # Mirror the frame so it acts like a natural mirror for the user
            frame = cv2.flip(frame, 1)
            
            # --- DRAW THE GREEN ACTION ZONE ---
            height, width, _ = frame.shape
            margin_x = int(width * 0.15) # 15% margin on sides
            margin_y = int(height * 0.10) # 10% margin on top/bottom
            
            # Draw the guide box
            cv2.rectangle(frame, (margin_x, margin_y), (width - margin_x, height - margin_y), (0, 255, 0), 2)
            cv2.putText(frame, "STAY INSIDE THIS FRAME", (margin_x, margin_y - 10), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0, 255, 0), 1, cv2.LINE_AA)

            # AI Processing
            image = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
            mp_image = mp.Image(image_format=mp.ImageFormat.SRGB, data=image)
            timestamp_ms = int(cap.get(cv2.CAP_PROP_POS_MSEC))
            if timestamp_ms == 0: timestamp_ms = int(time.time() * 1000)

            detection_result = landmarker.detect_for_video(mp_image, timestamp_ms)

            # --- DATA EXTRACTION & QUALITY CONTROL ---
            if recording_label and detection_result.pose_landmarks:
                lm_list = detection_result.pose_landmarks[0]
                
                if lm_list[0].visibility > 0.6 and lm_list[11].visibility > 0.6 and lm_list[12].visibility > 0.6:
                    try:
                        row = [
                            lm_list[0].x, lm_list[0].y,
                            lm_list[1].x, lm_list[1].y,
                            lm_list[2].x, lm_list[2].y,
                            lm_list[11].x, lm_list[11].y,
                            lm_list[12].x, lm_list[12].y,
                            recording_label
                        ]
                        writer.writerow(row)
                        counts[recording_label] += 1
                        frames_recorded_this_session += 1
                        
                        # --- AUTO-STOP TRIGGER ---
                        if frames_recorded_this_session >= SESSION_FRAME_LIMIT:
                            print(f"SUCCESS: Captured {SESSION_FRAME_LIMIT} frames for {recording_label}.")
                            recording_label = None # Turn off recording automatically
                            
                            # Visual flash to indicate completion
                            cv2.rectangle(frame, (0,0), (width, height), (0, 255, 0), -1) 
                            
                    except IndexError:
                        pass

            # --- DRAW UI OVERLAYS ---
            if recording_label:
                # Calculate progress percentage
                progress = int((frames_recorded_this_session / SESSION_FRAME_LIMIT) * 100)
                color = (0, 0, 255) if (int(time.time() * 2) % 2 == 0) else (0, 100, 255)
                
                cv2.putText(frame, f"RECORDING: {recording_label} [{progress}%]", (10, 30), cv2.FONT_HERSHEY_SIMPLEX, 0.7, color, 2, cv2.LINE_AA)
                
                # Draw a visual progress bar at the bottom
                bar_width = int((width) * (progress / 100))
                cv2.rectangle(frame, (0, height - 10), (bar_width, height), (0, 0, 255), -1)

            else:
                cv2.putText(frame, "Ready. Press G, S, or N to start 10-sec capture.", (10, 30), cv2.FONT_HERSHEY_SIMPLEX, 0.6, (0, 255, 0), 2, cv2.LINE_AA)

            # Live Database Balance Stats
            stats_text = f"DB Balance | G: {counts['Good Posture']}  S: {counts['Slouching']}  N: {counts['Not At Desk']}"
            cv2.putText(frame, stats_text, (10, 60), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (255, 255, 0), 1, cv2.LINE_AA)

            cv2.imshow('DeskBot Data Collector', frame)

            # --- KEYBOARD TOGGLES ---
            key = cv2.waitKey(1) & 0xFF
            if key == ord('q'): break
            elif key == ord('g'): 
                recording_label = 'Good Posture'
                frames_recorded_this_session = 0
            elif key == ord('s'): 
                recording_label = 'Slouching'
                frames_recorded_this_session = 0
            elif key == ord('n'): 
                recording_label = 'Not At Desk'
                frames_recorded_this_session = 0

    except Exception as e:
        print(f"Error: {e}")
    finally:
        if 'cap' in locals() and cap.isOpened(): cap.release()
        if 'csv_file' in locals() and not csv_file.closed: csv_file.close()
        if 'landmarker' in locals(): landmarker.close()
        cv2.destroyAllWindows()
        print("Data collection closed. Database updated.")

if __name__ == "__main__":
    main()