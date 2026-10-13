import cv2
import sklearn
import sklearn.ensemble
import mediapipe as mp
import joblib
import numpy as np
import os
import time
import json
import requests
import psutil
from collections import deque
from AWSIoTPythonSDK.MQTTLib import AWSIoTMQTTClient

# ==========================================
# --- AWS IOT CORE CONFIGURATION ---
# ==========================================
AWS_ENDPOINT = "a260nvvzszz66f-ats.iot.us-east-1.amazonaws.com" 
AWS_PORT = 8883
AWS_CLIENT_ID = "DeskBot_Laptop"
AWS_TOPIC = "deskbot/posture"

ROOT_CA_PATH = "AmazonRootCA1.pem"
PRIVATE_KEY_PATH = "db8eb26a2499f06fb749dbd05764d4a2f6cdccc53e7142f7617dc8463bfcc2a8-private.pem.key"
CERTIFICATE_PATH = "db8eb26a2499f06fb749dbd05764d4a2f6cdccc53e7142f7617dc8463bfcc2a8-certificate.pem.crt"

print("Connecting to AWS IoT Core...")
mqtt_client = AWSIoTMQTTClient(AWS_CLIENT_ID)
mqtt_client.configureEndpoint(AWS_ENDPOINT, AWS_PORT)
mqtt_client.configureCredentials(ROOT_CA_PATH, PRIVATE_KEY_PATH, CERTIFICATE_PATH)
mqtt_client.configureAutoReconnectBackoffTime(1, 32, 20)
mqtt_client.configureOfflinePublishQueueing(-1)  

try:
    mqtt_client.connect()
    print("Successfully connected to AWS IoT Core!")
except Exception as e:
    print(f"AWS Connection Failed. Error: {e}")
    # We won't exit here so you can still test it locally without internet

# ==========================================
# --- GEOMETRY MATH (CRITICAL FOR ACCURACY) ---
# ==========================================
def download_model(url, filename):
    if not os.path.exists(filename):
        print(f"Downloading {filename}...")
        r = requests.get(url, allow_redirects=True)
        open(filename, 'wb').write(r.content)

def calculate_geometric_features(lm_list):
    nose = np.array([lm_list[0].x, lm_list[0].y])
    shoulder_l = np.array([lm_list[11].x, lm_list[11].y])
    shoulder_r = np.array([lm_list[12].x, lm_list[12].y])
    
    shoulder_width = np.linalg.norm(shoulder_l - shoulder_r)
    shoulder_midpoint = (shoulder_l + shoulder_r) / 2.0
    neck_length = np.linalg.norm(nose - shoulder_midpoint)
    slouch_ratio = neck_length / (shoulder_width + 1e-6) 
    
    delta_y = shoulder_r[1] - shoulder_l[1]
    delta_x = shoulder_r[0] - shoulder_l[0]
    shoulder_tilt_angle = np.degrees(np.arctan2(delta_y, delta_x))

    return [
        shoulder_width, neck_length, slouch_ratio, shoulder_tilt_angle,
        lm_list[0].x, lm_list[0].y, lm_list[1].x, lm_list[1].y,
        lm_list[2].x, lm_list[2].y, lm_list[11].x, lm_list[11].y,
        lm_list[12].x, lm_list[12].y
    ]

def check_if_in_meeting():
    """Scans running laptop processes to see if you are in a meeting."""
    meeting_apps = ['Zoom.exe', 'Teams.exe', 'ms-teams.exe']
    for proc in psutil.process_iter(['name']):
        try:
            if proc.info['name'] in meeting_apps:
                return True
        except (psutil.NoSuchProcess, psutil.AccessDenied, psutil.ZombieProcess):
            pass
    return False

def main():
    download_model("https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/1/pose_landmarker_lite.task", 'models/pose_landmarker_lite.task')
    download_model("https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/int8/1/efficientdet_lite0.tflite", 'efficientdet_lite0.tflite')

    model_data = joblib.load('models/posture_model_v3.pkl')
    rf_model = model_data['model']
    scaler = model_data['scaler']

    BaseOptions = mp.tasks.BaseOptions
    VisionRunningMode = mp.tasks.vision.RunningMode

    # Initialize Posture AI
    pose_options = mp.tasks.vision.PoseLandmarkerOptions(
        base_options=BaseOptions(model_asset_path='models/pose_landmarker_lite.task'),
        running_mode=VisionRunningMode.VIDEO,
        min_pose_detection_confidence=0.6,
        min_tracking_confidence=0.6
    )
    landmarker = mp.tasks.vision.PoseLandmarker.create_from_options(pose_options)

    # Initialize Phone Detection AI (Score tuned to 30%)
    obj_options = mp.tasks.vision.ObjectDetectorOptions(
        base_options=BaseOptions(model_asset_path='efficientdet_lite0.tflite'),
        running_mode=VisionRunningMode.VIDEO,
        max_results=2,
        category_allowlist=['cell phone'],
        score_threshold=0.30 
    )
    object_detector = mp.tasks.vision.ObjectDetector.create_from_options(obj_options)

    cap = cv2.VideoCapture(0) 
    CAM_WIDTH, CAM_HEIGHT = 800, 600
    cap.set(cv2.CAP_PROP_FRAME_WIDTH, CAM_WIDTH)
    cap.set(cv2.CAP_PROP_FRAME_HEIGHT, CAM_HEIGHT)
    
    print("Starting Flawless Cyber-HUD Vision...")
    
    prediction_history = deque(maxlen=20) 
    last_sent_state = ""
    last_send_time = time.time()
    frame_counter = 0

    PHONE_LIMIT_SECONDS = 10 # Set for 2 minits
    phone_accumulated_time = 0.0
    phone_last_seen = time.time()

    # --- EMA SMOOTHING VARIABLES ---
    target_bbox = None
    smoothed_bbox = None 
    target_landmarks = None
    smoothed_landmarks = {}

    while cap.isOpened():
        ret, frame = cap.read()
        if not ret: break
        
        frame_counter += 1
        timestamp_ms = int(cap.get(cv2.CAP_PROP_POS_MSEC))
        if timestamp_ms == 0: timestamp_ms = int(time.time() * 1000)
        current_time = time.time()

        # FLIP FRAME ONCE
        frame = cv2.flip(frame, 1)

        # ==========================================
        # 1. AI INFERENCE ENGINE (Runs every 3rd frame)
        # ==========================================
        if frame_counter % 3 == 0:
            rgb_frame = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
            mp_image = mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb_frame)
            
            # --- Object Detection ---
            obj_result = object_detector.detect_for_video(mp_image, timestamp_ms)
            valid_phone_found = False

            if len(obj_result.detections) > 0:
                detection = obj_result.detections[0]
                bbox = detection.bounding_box
                box_area = bbox.width * bbox.height
                screen_area = CAM_WIDTH * CAM_HEIGHT
                area_ratio = box_area / screen_area

                # Phone size filter
                if 0.005 < area_ratio < 0.50:
                    valid_phone_found = True
                    target_bbox = [bbox.origin_x, bbox.origin_y, bbox.width, bbox.height]
                    phone_accumulated_time += (current_time - phone_last_seen)

            if not valid_phone_found:
                target_bbox = None
                phone_accumulated_time = max(0.0, phone_accumulated_time - (current_time - phone_last_seen) * 2)
            
            phone_last_seen = current_time

            # --- Posture Detection ---
            pose_result = landmarker.detect_for_video(mp_image, timestamp_ms)
            if pose_result.pose_landmarks:
                target_landmarks = pose_result.pose_landmarks[0]
                lm = pose_result.pose_landmarks[0]
                
                if prediction_history.count("Not At Desk") > 10:
                    prediction_history.clear()
                
                # CRITICAL FIX: Math is applied here!
                engineered_features = calculate_geometric_features(lm)
                features_scaled = scaler.transform([engineered_features])
                raw_prediction = rf_model.predict(features_scaled)[0]
                prediction_history.append(raw_prediction)
            else:
                target_landmarks = None
                prediction_history.append("Not At Desk")

            # --- Master State Logic ---
            smoothed_prediction = "Detecting..."
            if len(prediction_history) > 0:
                smoothed_prediction = max(set(prediction_history), key=prediction_history.count)

            final_ai_state = smoothed_prediction
            if phone_accumulated_time > PHONE_LIMIT_SECONDS:
                final_ai_state = "Phone Distraction"

            # --- AWS Publish ---
            if final_ai_state != last_sent_state or (current_time - last_send_time > 2.0):
                payload = json.dumps({"state": final_ai_state})
                try:
                    mqtt_client.publish(AWS_TOPIC, payload, 1)
                    print(f"AWS Sync -> {payload}")
                    last_sent_state = final_ai_state
                    last_send_time = current_time
                except Exception as e:
                    pass 
            # --- Auto-DND Detection ---
            is_busy = check_if_in_meeting()

            # --- AWS Publish ---
            if final_ai_state != last_sent_state or (current_time - last_send_time > 2.0):
                payload = json.dumps({
                    "state": final_ai_state,
                    "dnd": is_busy
                })
                try:
                    mqtt_client.publish(AWS_TOPIC, payload, 1)
                    print(f"AWS Sync -> {payload}")
                    last_sent_state = final_ai_state
                    last_send_time = current_time
                except Exception as e:
                    pass

        # ==========================================
        # 2. HUD DRAWING ENGINE 
        # ==========================================
        h, w, _ = frame.shape

        # A. Draw Smoothed Neon Skeleton
        if target_landmarks:
            connections = [(11, 12), (11, 13), (13, 15), (12, 14), (14, 16), (11, 23), (12, 24), (23, 24)]
            
            for i, lm in enumerate(target_landmarks):
                if lm.visibility > 0.5:
                    tx, ty = int(lm.x * w), int(lm.y * h)
                    if i not in smoothed_landmarks:
                        smoothed_landmarks[i] = [tx, ty]
                    else:
                        smoothed_landmarks[i][0] += (tx - smoothed_landmarks[i][0]) * 0.4
                        smoothed_landmarks[i][1] += (ty - smoothed_landmarks[i][1]) * 0.4
                elif i in smoothed_landmarks:
                    del smoothed_landmarks[i] 

            for p1, p2 in connections:
                if p1 in smoothed_landmarks and p2 in smoothed_landmarks:
                    x1, y1 = int(smoothed_landmarks[p1][0]), int(smoothed_landmarks[p1][1])
                    x2, y2 = int(smoothed_landmarks[p2][0]), int(smoothed_landmarks[p2][1])
                    cv2.line(frame, (x1, y1), (x2, y2), (255, 255, 0), 2)
                    
            for i in [11, 12, 13, 14, 15, 16, 23, 24]:
                if i in smoothed_landmarks:
                    cx, cy = int(smoothed_landmarks[i][0]), int(smoothed_landmarks[i][1])
                    cv2.circle(frame, (cx, cy), 5, (0, 255, 255), -1)
        else:
            smoothed_landmarks.clear()

        # B. Draw Smoothed Phone Box (DOUBLE-MIRROR BUG FIXED!)
        if target_bbox:
            tx, ty, tw, th = target_bbox
            # We NO LONGER flip the math here. tx is already perfectly aligned!
            
            if smoothed_bbox is None:
                smoothed_bbox = [tx, ty, tw, th]
            else:
                smoothed_bbox[0] += (tx - smoothed_bbox[0]) * 0.3
                smoothed_bbox[1] += (ty - smoothed_bbox[1]) * 0.3
                smoothed_bbox[2] += (tw - smoothed_bbox[2]) * 0.3
                smoothed_bbox[3] += (th - smoothed_bbox[3]) * 0.3

            sx, sy, sw, sh = [int(v) for v in smoothed_bbox]
            cv2.rectangle(frame, (sx, sy), (sx + sw, sy + sh), (0, 0, 255), 2)
            cv2.putText(frame, "TARGET: SMARTPHONE", (sx, sy - 10), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0, 0, 255), 1)
        else:
            smoothed_bbox = None

        # C. Draw UI Overlay Panels
        overlay = frame.copy()
        cv2.rectangle(overlay, (0, 0), (300, 600), (10, 10, 20), -1)
        cv2.addWeighted(overlay, 0.7, frame, 0.3, 0, frame)

        cv2.putText(frame, "DESKBOT OS v2.0", (15, 35), cv2.FONT_HERSHEY_DUPLEX, 0.7, (255, 255, 255), 1)
        cv2.line(frame, (10, 50), (290, 50), (255, 255, 0), 2)

        state_color = (0, 255, 0)
        if last_sent_state == "Slouching": state_color = (0, 100, 255) 
        if last_sent_state == "Phone Distraction": state_color = (0, 0, 255) 
        if last_sent_state == "Not At Desk": state_color = (255, 255, 255) 

        cv2.putText(frame, "AI MASTER STATE:", (15, 90), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (200, 200, 200), 1)
        cv2.putText(frame, last_sent_state.upper() if last_sent_state else "DETECTING...", (15, 120), cv2.FONT_HERSHEY_DUPLEX, 0.7, state_color, 2)

        cv2.putText(frame, "PHONE TRACKER:", (15, 180), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (200, 200, 200), 1)
        bar_fill = int((min(phone_accumulated_time, PHONE_LIMIT_SECONDS) / PHONE_LIMIT_SECONDS) * 260)
        cv2.rectangle(frame, (15, 200), (275, 220), (50, 50, 50), -1)
        cv2.rectangle(frame, (15, 200), (15 + bar_fill, 220), (0, 0, 255) if bar_fill >= 260 else (255, 255, 0), -1) 
        cv2.putText(frame, f"{phone_accumulated_time:.1f}s / {PHONE_LIMIT_SECONDS}s", (15, 240), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (255, 255, 255), 1)

        cv2.putText(frame, "SYSTEM DIAGNOSTICS", (15, 420), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (200, 200, 200), 1)
        cv2.putText(frame, "DB Rows Trained: 38,272", (15, 445), cv2.FONT_HERSHEY_SIMPLEX, 0.4, (0, 255, 0), 1)
        cv2.putText(frame, "AWS: SECURE TUNNEL", (15, 470), cv2.FONT_HERSHEY_SIMPLEX, 0.4, (0, 255, 0), 1)
        cv2.putText(frame, f"FPS: {cap.get(cv2.CAP_PROP_FPS):.0f}", (15, 495), cv2.FONT_HERSHEY_SIMPLEX, 0.4, (0, 255, 0), 1)

        cv2.imshow('DeskBot Cyber-HUD', frame)
        if cv2.waitKey(1) & 0xFF == ord('q'): break

    if 'cap' in locals() and cap.isOpened(): cap.release()
    cv2.destroyAllWindows()
    if 'landmarker' in locals(): landmarker.close()
    if 'object_detector' in locals(): object_detector.close()
    try:
        mqtt_client.disconnect()
    except: pass

if __name__ == "__main__":
    main()