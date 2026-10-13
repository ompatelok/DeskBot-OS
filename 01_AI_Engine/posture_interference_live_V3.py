import cv2
import glob
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
import sqlite3
import math
import socket
import tkinter as tk
import matplotlib.pyplot as plt
from matplotlib.gridspec import GridSpec
from datetime import datetime
from tkinter import simpledialog, messagebox
from collections import deque
from AWSIoTPythonSDK.MQTTLib import AWSIoTMQTTClient

# ==========================================
# --- 0. DYNAMIC CLOUD CONFIGURATION ---
# ==========================================
CONFIG_FILE = "deskbot_config.json"
DEFAULT_CONFIG = {
    "version": "3.0.0",
    "device_id": "DeskBot_Unit_01",
    "ai_delays": {
        "slouch_delay": 5,
        "drowsy_delay": 3,
        "phone_delay": 10
    },
    "aws": {
        "endpoint": "YOUR_NEW_AWS_ENDPOINT-ats.iot.us-east-1.amazonaws.com",
        "port": 8883
    }
}

def load_or_create_config():
    if not os.path.exists("certs"):
        os.makedirs("certs")
        print("[SETUP] Created 'certs/' directory. Place AWS .pem and .crt files here.")
        
    if not os.path.exists(CONFIG_FILE):
        with open(CONFIG_FILE, 'w') as f:
            json.dump(DEFAULT_CONFIG, f, indent=4)
        print(f"[SETUP] Created default {CONFIG_FILE}.")
        return DEFAULT_CONFIG
        
    with open(CONFIG_FILE, 'r') as f:
        return json.load(f)

app_config = load_or_create_config()

if "YOUR_NEW" in app_config["aws"]["endpoint"]:
    import tkinter as tk
    from tkinter import simpledialog
    temp_root = tk.Tk()
    temp_root.withdraw()
    
    user_endpoint = simpledialog.askstring(
        "DeskBot AWS Setup", 
        "Welcome to DeskBot OS!\n\nPlease paste your AWS IoT Endpoint here:\n(Found in AWS Console -> IoT Core -> Settings -> Device data endpoint)",
        parent=temp_root
    )
    
    if user_endpoint:
        app_config["aws"]["endpoint"] = user_endpoint.strip()
        with open(CONFIG_FILE, 'w') as f:
            json.dump(app_config, f, indent=4)
        print("[SETUP] AWS Endpoint saved permanently!")
    else:
        print("[WARNING] No endpoint provided. AWS will fail to connect.")
        
# ==========================================
# --- 1. SETTINGS, STATE & PROFILES ---
# ==========================================
root = tk.Tk()
root.withdraw()

state = {
    "current_user": "Om Kasvala",
    "profiles": ["Om Kasvala", "Guest"],
    "slouch_delay": app_config["ai_delays"].get("slouch_delay", 5),     
    "drowsy_delay": app_config["ai_delays"].get("drowsy_delay", 3),     
    "phone_delay": app_config["ai_delays"].get("phone_delay", 10),     
    "running": True        
}

BTN_USER = (600, 30, 780, 70)
BTN_SETTING = (600, 90, 780, 130)
BTN_SYNC = (600, 150, 780, 190)
BTN_REPORT = (600, 210, 780, 250) 
BTN_EXIT = (600, 530, 780, 570)

# ==========================================
# --- 2. LOCAL DATABASE ---
# ==========================================
def init_db():
    conn = sqlite3.connect('deskbot_local.db')
    cursor = conn.cursor()
    cursor.execute('''
        CREATE TABLE IF NOT EXISTS session_logs (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            user TEXT,
            timestamp DATETIME DEFAULT CURRENT_TIMESTAMP,
            ai_state TEXT,
            synced_to_cloud BOOLEAN DEFAULT 0
        )
    ''')
    conn.commit()
    return conn

db_conn = init_db()

# ==========================================
# --- 3. ANALYTICS ENGINE (CYBER-HUD PALETTE) ---
# ==========================================
def format_time(seconds):
    if seconds < 3600:
        return f"{int(seconds // 60)}m {int(seconds % 60)}s"
    else:
        hours = int(seconds // 3600)
        minutes = int((seconds % 3600) // 60)
        return f"{hours}h {minutes}m"

def generate_report(user_name, time_range):
    print(f"Generating {time_range} local report...")
    cursor = db_conn.cursor()
    
    if time_range == "Daily":
        query_modifier = "date('now', 'localtime')"
        title_text = "Daily"
    elif time_range == "Weekly":
        query_modifier = "datetime('now', '-7 days', 'localtime')"
        title_text = "7-Day Weekly"
    elif time_range == "Monthly":
        query_modifier = "datetime('now', '-30 days', 'localtime')"
        title_text = "30-Day Monthly"
    else:
        return

    cursor.execute(f'''
        SELECT timestamp, ai_state 
        FROM session_logs 
        WHERE user = ? AND timestamp >= {query_modifier}   
        ORDER BY timestamp ASC
    ''', (user_name,))
    logs = cursor.fetchall()

    if len(logs) < 1:
        messagebox.showinfo("Not Enough Data", f"No data found for the {time_range} timeframe.", parent=root)
        return

    state_durations = {"Good Posture": 0.0, "Slouching": 0.0, "Phone Distraction": 0.0, "Drowsy / Sleeping": 0.0, "Not At Desk": 0.0}
    
    for i in range(len(logs) - 1):
        t1 = datetime.strptime(logs[i][0], '%Y-%m-%d %H:%M:%S')
        t2 = datetime.strptime(logs[i+1][0], '%Y-%m-%d %H:%M:%S')
        duration = (t2 - t1).total_seconds()
        
        if duration < 3600: 
            logged_state = logs[i][1]
            if logged_state in state_durations:
                state_durations[logged_state] += duration

    last_log_time = datetime.strptime(logs[-1][0], '%Y-%m-%d %H:%M:%S')
    time_to_now = (datetime.now() - last_log_time).total_seconds()
    if time_to_now < 3600:
        last_logged_state = logs[-1][1]
        if last_logged_state in state_durations:
            state_durations[last_logged_state] += time_to_now

    total_tracked_time = sum(state_durations.values())
    if total_tracked_time < 5: 
        messagebox.showinfo("Report Error", "Gathering data... please work for a few more seconds.", parent=root)
        return

    labels, sizes, colors, bar_labels, bar_sizes = [], [], [], [], []
    color_map = {
        "Good Posture": '#A3E635',       # Emerald Green
        "Slouching": '#FBBF24',          # Amber
        "Phone Distraction": '#F87171',  # Coral Red
        "Drowsy / Sleeping": '#818CF8',  # Muted Indigo
        "Not At Desk": '#676E7E'         # Slate Muted
    }

    sorted_states = sorted(state_durations.items(), key=lambda item: item[1], reverse=True)

    for st, dur in sorted_states:
        if dur > 0:
            formatted_dur = format_time(dur)
            labels.append(f"{st} ({formatted_dur})")
            sizes.append(dur)
            colors.append(color_map[st])
            bar_labels.append(st)
            bar_sizes.append(dur / 60)

    efficiency_score = int((state_durations["Good Posture"] / total_tracked_time) * 100)

    plt.style.use('dark_background')
    fig = plt.figure(figsize=(12, 6))
    fig.patch.set_facecolor('#07090E')
    fig.canvas.manager.set_window_title('DeskBot OS - Executive Analytics')
    
    gs = GridSpec(1, 2, width_ratios=[1.2, 1])
    ax1 = fig.add_subplot(gs[0])
    ax2 = fig.add_subplot(gs[1])
    ax1.set_facecolor('#0F131D')
    ax2.set_facecolor('#0F131D')
    
    wedges, texts, autotexts = ax1.pie(
        sizes, 
        colors=colors, 
        autopct='%1.1f%%', 
        startangle=140, 
        pctdistance=0.82, 
        explode=[0.02] * len(sizes),
        wedgeprops=dict(width=0.4, edgecolor='#07090E', linewidth=2)
    )
    plt.setp(autotexts, size=10, weight="bold", color="white")
    ax1.legend(wedges, labels, title="State Breakdown", loc="center left", bbox_to_anchor=(0.9, 0, 0.5, 1),
              frameon=False, fontsize=10, title_fontsize=12)
    ax1.text(0, 0, f"INDEX\n{efficiency_score}%", ha='center', va='center', fontsize=19, fontweight='bold', color='#00F0FF')
    ax1.set_title(f"{title_text} Overview", fontsize=13, fontweight='bold', color="white", fontfamily='monospace')

    y_pos = np.arange(len(bar_labels))
    bars = ax2.barh(y_pos, bar_sizes, color=colors, edgecolor='#19202F', height=0.55)
    ax2.set_yticks(y_pos)
    ax2.set_yticklabels(bar_labels, fontsize=10, fontfamily='monospace')
    ax2.invert_yaxis()
    ax2.set_xlabel('Total Minutes', fontsize=10, color='#676E7E', fontfamily='monospace')
    ax2.set_title("Absolute Time Distribution", fontsize=13, fontweight='bold', color="white", fontfamily='monospace')
    
    for bar in bars:
        width = bar.get_width()
        ax2.text(width + (max(bar_sizes)*0.02), bar.get_y() + bar.get_height()/2, 
                 f'{int(width)}m', ha='left', va='center', fontsize=9, color='white', fontfamily='monospace')
    
    ax2.spines['top'].set_visible(False)
    ax2.spines['right'].set_visible(False)
    ax2.spines['left'].set_color('#19202F')
    ax2.spines['bottom'].set_color('#19202F')
    ax2.xaxis.grid(True, linestyle='--', alpha=0.2, color='#676E7E')

    current_time_str = datetime.now().strftime("%B %d, %Y | %I:%M %p")
    fig.suptitle(f"DeskBot Advanced Analytics // {user_name.upper()}", fontsize=16, fontweight='bold', y=0.98, color='#00F0FF', fontfamily='monospace')
    fig.text(0.5, 0.02, f"Total Tracked Time: {format_time(total_tracked_time)}  |  Report Generated Locally: {current_time_str}", 
             ha='center', fontsize=9, color='#676E7E', fontfamily='monospace')
    
    plt.tight_layout(rect=[0, 0.05, 1, 0.9])
    plt.show() 

# ==========================================
# --- 4. UI MOUSE CALLBACK ---
# ==========================================
last_sync_error_time = 0.0

def mouse_click(event, x, y, flags, param):
    global state, db_conn, mqtt_client, last_sync_error_time
    
    if event == cv2.EVENT_LBUTTONDOWN:
        if BTN_USER[0] < x < BTN_USER[2] and BTN_USER[1] < y < BTN_USER[3]:
            new_user = simpledialog.askstring("Add User", "Enter new user name:", parent=root)
            if new_user:
                state["profiles"].append(new_user)
                state["current_user"] = new_user
                
        elif BTN_SETTING[0] < x < BTN_SETTING[2] and BTN_SETTING[1] < y < BTN_SETTING[3]:
            sl = simpledialog.askinteger("Settings", "Slouch Delay (seconds):", initialvalue=state["slouch_delay"], parent=root)
            dr = simpledialog.askinteger("Settings", "Drowsy Delay (seconds):", initialvalue=state["drowsy_delay"], parent=root)
            ph = simpledialog.askinteger("Settings", "Phone Delay (seconds):", initialvalue=state["phone_delay"], parent=root)
            if sl is not None: 
                state["slouch_delay"] = sl
                app_config["ai_delays"]["slouch_delay"] = sl
            if dr is not None: 
                state["drowsy_delay"] = dr
                app_config["ai_delays"]["drowsy_delay"] = dr
            if ph is not None: 
                state["phone_delay"] = ph
                app_config["ai_delays"]["phone_delay"] = ph
            with open(CONFIG_FILE, 'w') as f:
                json.dump(app_config, f, indent=4)

        elif BTN_SYNC[0] < x < BTN_SYNC[2] and BTN_SYNC[1] < y < BTN_SYNC[3]:
            cursor = db_conn.cursor()
            cursor.execute("SELECT id, user, timestamp, ai_state FROM session_logs WHERE synced_to_cloud = 0")
            rows = cursor.fetchall()
            if rows:
                for row in rows:
                    payload = json.dumps({"user": row[1], "time": row[2], "state": row[3]})
                    try:
                        mqtt_client.publish(AWS_TOPIC, payload, 0)
                        cursor.execute("UPDATE session_logs SET synced_to_cloud = 1 WHERE id = ?", (row[0],))
                    except Exception as exc:
                        now_err = time.time()
                        if now_err - last_sync_error_time > 5.0:
                            print(f"[AWS Sync Warning] Batch publish failure: {exc}")
                            last_sync_error_time = now_err
                db_conn.commit()
                messagebox.showinfo("AWS Sync", f"Successfully synced {len(rows)} records to cloud.", parent=root)
            else:
                messagebox.showinfo("AWS Sync", "All local data is already synced.", parent=root)
                
        elif BTN_REPORT[0] < x < BTN_REPORT[2] and BTN_REPORT[1] < y < BTN_REPORT[3]:
            report_choice = simpledialog.askinteger(
                "Analytics Engine", 
                "Select Report Type:\n\n1 = Daily (Today)\n2 = Weekly (Last 7 Days)\n3 = Monthly (Last 30 Days)", 
                parent=root, minvalue=1, maxvalue=3
            )
            if report_choice == 1:
                generate_report(state["current_user"], "Daily")
            elif report_choice == 2:
                generate_report(state["current_user"], "Weekly")
            elif report_choice == 3:
                generate_report(state["current_user"], "Monthly")

        elif BTN_EXIT[0] < x < BTN_EXIT[2] and BTN_EXIT[1] < y < BTN_EXIT[3]:
            state["running"] = False

# ==========================================
# --- 5. AWS IOT CORE CONFIGURATION ---
# ==========================================
AWS_ENDPOINT = app_config["aws"]["endpoint"]
AWS_PORT = app_config["aws"]["port"]
AWS_CLIENT_ID = app_config["device_id"] + "_Laptop"
AWS_TOPIC = "deskbot/posture/live"  

mqtt_client = AWSIoTMQTTClient(AWS_CLIENT_ID)
mqtt_client.configureEndpoint(AWS_ENDPOINT, AWS_PORT)

try:
    ROOT_CA_PATH = "certs/AmazonRootCA1.pem"
    cert_files = glob.glob("certs/*-certificate.pem.crt")
    key_files = glob.glob("certs/*-private.pem.key")
    
    if not cert_files or not key_files or not os.path.exists(ROOT_CA_PATH):
        raise FileNotFoundError("Missing AWS credentials in certs/ folder.")

    CERTIFICATE_PATH = cert_files[0]
    PRIVATE_KEY_PATH = key_files[0]

    mqtt_client.configureCredentials(ROOT_CA_PATH, PRIVATE_KEY_PATH, CERTIFICATE_PATH)
    mqtt_client.connect()
    
    print("[AWS] Successfully connected to IoT Core.")
    print(f"[AWS] Certificate loaded: {os.path.basename(CERTIFICATE_PATH)}")

except Exception as e:
    print(f"[AWS WARNING] Failed to connect to IoT Core: {e}")

# ==========================================
# --- 6. GEOMETRY UTILITIES ---
# ==========================================
def download_model(url, filename):
    if not os.path.exists(filename):
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
    return [shoulder_width, neck_length, slouch_ratio, shoulder_tilt_angle,
            lm_list[0].x, lm_list[0].y, lm_list[1].x, lm_list[1].y,
            lm_list[2].x, lm_list[2].y, lm_list[11].x, lm_list[11].y,
            lm_list[12].x, lm_list[12].y]

def calculate_ear(face_landmarks_list, w, h):
    left_eye = [33, 160, 158, 133, 153, 144]
    right_eye = [362, 385, 387, 263, 373, 380]
    def get_pt(idx): return np.array([face_landmarks_list[idx].x * w, face_landmarks_list[idx].y * h])
    def eye_ratio(eye_pts):
        p = [get_pt(i) for i in eye_pts]
        vert = np.linalg.norm(p[1] - p[5]) + np.linalg.norm(p[2] - p[4])
        horz = np.linalg.norm(p[0] - p[3])
        return vert / (2.0 * horz + 1e-6)
    return (eye_ratio(left_eye) + eye_ratio(right_eye)) / 2.0

# ==========================================
# --- 7. MAIN ENGINE LOOP ---
# ==========================================
def main():
    download_model("https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/1/pose_landmarker_lite.task", 'models/pose_landmarker_lite.task')
    download_model("https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/int8/1/efficientdet_lite0.tflite", 'efficientdet_lite0.tflite')
    download_model("https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task", 'face_landmarker.task')

    model_data = joblib.load('models/posture_model_v3.pkl')
    rf_model, scaler = model_data['model'], model_data['scaler']

    BaseOptions = mp.tasks.BaseOptions
    VisionRunningMode = mp.tasks.vision.RunningMode

    landmarker = mp.tasks.vision.PoseLandmarker.create_from_options(
        mp.tasks.vision.PoseLandmarkerOptions(
            base_options=BaseOptions(model_asset_path='models/pose_landmarker_lite.task'), 
            running_mode=VisionRunningMode.VIDEO
        )
    )
    object_detector = mp.tasks.vision.ObjectDetector.create_from_options(
        mp.tasks.vision.ObjectDetectorOptions(
            base_options=BaseOptions(model_asset_path='efficientdet_lite0.tflite'), 
            running_mode=VisionRunningMode.VIDEO, 
            max_results=2, 
            category_allowlist=['cell phone'], 
            score_threshold=0.30
        )
    )
    face_landmarker = mp.tasks.vision.FaceLandmarker.create_from_options(
        mp.tasks.vision.FaceLandmarkerOptions(
            base_options=BaseOptions(model_asset_path='face_landmarker.task'), 
            running_mode=VisionRunningMode.VIDEO, 
            num_faces=1
        )
    )

    cv2.namedWindow('DeskBot Cyber-HUD v3.0')
    cv2.setMouseCallback('DeskBot Cyber-HUD v3.0', mouse_click)

    cap = cv2.VideoCapture(0) 
    CAM_WIDTH, CAM_HEIGHT = 800, 600
    cap.set(cv2.CAP_PROP_FRAME_WIDTH, CAM_WIDTH)
    cap.set(cv2.CAP_PROP_FRAME_HEIGHT, CAM_HEIGHT)
    
    phone_accumulated = 0.0
    slouch_accumulated = 0.0
    drowsy_accumulated = 0.0
    current_slouch_ratio = 0.0  
    last_time = time.time()
    
    prediction_history = deque(maxlen=15)
    last_logged_state = ""
    frame_counter = 0
    last_aws_error_time = 0.0

    smoothed_landmarks = {}
    smoothed_bbox = None
    target_landmarks = None
    target_bbox = None

    print("[HUD] Starting Cyber-Minimalist Dark Vision Loop...")

    while cap.isOpened() and state["running"]:
        ret, frame = cap.read()
        if not ret: break
        
        frame = cv2.flip(frame, 1)
        h, w, _ = frame.shape
        current_time = time.time()
        delta_time = current_time - last_time
        last_time = current_time
        timestamp_ms = int(current_time * 1000)
        frame_counter += 1

        if frame_counter % 3 == 0:
            rgb_frame = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
            mp_image = mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb_frame)
            
            # 1. Phone Distraction Detection
            obj_result = object_detector.detect_for_video(mp_image, timestamp_ms)
            if obj_result.detections:
                bbox = obj_result.detections[0].bounding_box
                target_bbox = [bbox.origin_x, bbox.origin_y, bbox.width, bbox.height]
                phone_accumulated += delta_time * 3
            else:
                target_bbox = None
                phone_accumulated = max(0.0, phone_accumulated - delta_time * 3)

            # 2. Drowsiness Detection
            face_result = face_landmarker.detect_for_video(mp_image, timestamp_ms)
            is_drowsy_frame = False
            if face_result.face_landmarks:
                if calculate_ear(face_result.face_landmarks[0], w, h) < 0.22:
                    drowsy_accumulated += delta_time * 3
                    is_drowsy_frame = True
            if not is_drowsy_frame:
                drowsy_accumulated = max(0.0, drowsy_accumulated - delta_time * 6)

            # 3. Posture Evaluation
            pose_result = landmarker.detect_for_video(mp_image, timestamp_ms)
            if pose_result.pose_landmarks:
                lm = pose_result.pose_landmarks[0]
                target_landmarks = lm
                features = calculate_geometric_features(lm)
                current_slouch_ratio = features[2] 
                
                features_scaled = scaler.transform([features])
                probs = rf_model.predict_proba(features_scaled)[0]
                max_index = np.argmax(probs)
                raw_prediction = rf_model.classes_[max_index]
                
                if raw_prediction == "Slouching" and current_slouch_ratio > 0.70: 
                    raw_prediction = "Good Posture"

                prediction_history.append(raw_prediction)
                if raw_prediction == "Slouching": 
                    slouch_accumulated += delta_time * 3
                else: 
                    slouch_accumulated = max(0.0, slouch_accumulated - delta_time * 6)
            else:
                target_landmarks = None
                prediction_history.append("Not At Desk")
                slouch_accumulated = 0.0
                current_slouch_ratio = 0.0

        # Master Arbitration
        final_state = "Good Posture"
        if len(prediction_history) > 0:
            if prediction_history.count("Not At Desk") > 5: final_state = "Not At Desk"
            elif phone_accumulated >= state["phone_delay"]: final_state = "Phone Distraction"
            elif drowsy_accumulated >= state["drowsy_delay"]: final_state = "Drowsy / Sleeping"
            elif slouch_accumulated >= state["slouch_delay"]: final_state = "Slouching"

        # Local SQLite Persist on State Delta
        if final_state != last_logged_state and final_state != "Not At Desk":
            db_conn.cursor().execute("INSERT INTO session_logs (user, ai_state) VALUES (?, ?)", (state["current_user"], final_state))
            db_conn.commit()
            last_logged_state = final_state
            
        # Telemetry Stream to AWS (Key fixed to 'focusScore')
        if frame_counter % 15 == 0:
            penalty = int((slouch_accumulated * 2) + (phone_accumulated * 3) + (drowsy_accumulated * 5))
            live_focus_score = max(0, 100 - penalty)

            payload = json.dumps({
                "posture": final_state,
                "focusScore": live_focus_score
            })
            
            try:
                mqtt_client.publish(AWS_TOPIC, payload, 0)
            except Exception as exc:
                now_err = time.time()
                if now_err - last_aws_error_time > 5.0:
                    print(f"[AWS Warning] Failed to publish live telemetry: {exc} (throttled 5s)")
                    last_aws_error_time = now_err

        # ==========================================
        # --- HUD RENDERING (CYBER-MINIMALIST DARK) ---
        # ==========================================
        if target_landmarks:
            connections = [(11, 12), (11, 13), (13, 15), (12, 14), (14, 16), (0, 11), (0, 12)]
            for i in [0, 11, 12, 13, 14, 15, 16]: 
                lm_pt = target_landmarks[i]
                if lm_pt.visibility > 0.5:
                    tx, ty = int(lm_pt.x * w), int(lm_pt.y * h)
                    if i not in smoothed_landmarks: smoothed_landmarks[i] = [tx, ty]
                    else:
                        smoothed_landmarks[i][0] += (tx - smoothed_landmarks[i][0]) * 0.4
                        smoothed_landmarks[i][1] += (ty - smoothed_landmarks[i][1]) * 0.4
                elif i in smoothed_landmarks: del smoothed_landmarks[i]

            for p1, p2 in connections:
                if p1 in smoothed_landmarks and p2 in smoothed_landmarks:
                    x1, y1 = int(smoothed_landmarks[p1][0]), int(smoothed_landmarks[p1][1])
                    x2, y2 = int(smoothed_landmarks[p2][0]), int(smoothed_landmarks[p2][1])
                    cv2.line(frame, (x1, y1), (x2, y2), (255, 240, 0), 2, cv2.LINE_AA)
            for i in smoothed_landmarks:
                cv2.circle(frame, (int(smoothed_landmarks[i][0]), int(smoothed_landmarks[i][1])), 4, (53, 230, 163), -1)
        else: smoothed_landmarks.clear()

        if target_bbox:
            tx, ty, tw, th = target_bbox
            if smoothed_bbox is None: smoothed_bbox = [tx, ty, tw, th]
            else:
                smoothed_bbox[0] += (tx - smoothed_bbox[0]) * 0.3
                smoothed_bbox[1] += (ty - smoothed_bbox[1]) * 0.3
                smoothed_bbox[2] += (tw - smoothed_bbox[2]) * 0.3
                smoothed_bbox[3] += (th - smoothed_bbox[3]) * 0.3
            sx, sy, sw, sh = [int(v) for v in smoothed_bbox]
            cv2.rectangle(frame, (sx, sy), (sx + sw, sy + sh), (113, 113, 248), 2)
            cv2.putText(frame, "DISTRACTION DETECTED", (sx, sy - 10), cv2.FONT_HERSHEY_SIMPLEX, 0.45, (113, 113, 248), 1, cv2.LINE_AA)
        else: smoothed_bbox = None

        # Sidebar Palette: Background #07090E (14, 9, 7), Border #19202F (47, 32, 25)
        cv2.rectangle(frame, (580, 0), (800, 600), (14, 9, 7), -1)
        cv2.line(frame, (580, 0), (580, 600), (47, 32, 25), 1)
        
        def draw_btn(bbox, text, border_color=(255, 240, 0), text_color=(255, 240, 0)):
            cv2.rectangle(frame, (bbox[0], bbox[1]), (bbox[2], bbox[3]), (29, 19, 15), -1)
            cv2.rectangle(frame, (bbox[0], bbox[1]), (bbox[2], bbox[3]), border_color, 1)
            cv2.putText(frame, text, (bbox[0] + 16, bbox[1] + 26), cv2.FONT_HERSHEY_SIMPLEX, 0.52, text_color, 1, cv2.LINE_AA)

        draw_btn(BTN_USER, "USER PROFILE", (255, 240, 0), (255, 240, 0))
        draw_btn(BTN_SETTING, "AI DELAYS", (255, 240, 0), (255, 240, 0))
        draw_btn(BTN_SYNC, "SYNC CLOUD", (53, 230, 163), (53, 230, 163))
        draw_btn(BTN_REPORT, "ANALYTICS", (255, 240, 0), (255, 240, 0)) 
        draw_btn(BTN_EXIT, "TERMINATE", (113, 113, 248), (113, 113, 248))

        cv2.putText(frame, f"OPERATOR // {state['current_user'].upper()}", (12, 30), cv2.FONT_HERSHEY_SIMPLEX, 0.55, (255, 240, 0), 1, cv2.LINE_AA)
        
        state_color = (53, 230, 163) if final_state == "Good Posture" else ((36, 191, 251) if final_state == "Slouching" else (113, 113, 248))
        cv2.putText(frame, f"STATUS > {final_state.upper()}", (12, 65), cv2.FONT_HERSHEY_SIMPLEX, 0.65, state_color, 2, cv2.LINE_AA)

        def draw_bar(y, label, current, maximum, color):
            cv2.putText(frame, label, (12, y), cv2.FONT_HERSHEY_SIMPLEX, 0.44, (126, 110, 103), 1, cv2.LINE_AA)
            fill = int((min(current, maximum) / maximum) * 200)
            cv2.rectangle(frame, (12, y+8), (212, y+20), (29, 19, 15), -1)
            cv2.rectangle(frame, (12, y+8), (212, y+20), (47, 32, 25), 1)
            if fill > 0:
                cv2.rectangle(frame, (12, y+8), (12+fill, y+20), color, -1)

        draw_bar(115, f"SLOUCH LATENCY // {state['slouch_delay']}s", slouch_accumulated, state['slouch_delay'], (36, 191, 251))
        draw_bar(160, f"DROWSY LATENCY // {state['drowsy_delay']}s", drowsy_accumulated, state['drowsy_delay'], (36, 191, 251))
        draw_bar(205, f"PHONE LATENCY // {state['phone_delay']}s", phone_accumulated, state['phone_delay'], (113, 113, 248))
        
        cv2.putText(frame, "AI TELEMETRY", (12, 260), cv2.FONT_HERSHEY_SIMPLEX, 0.46, (255, 240, 0), 1, cv2.LINE_AA)
        ratio_color = (53, 230, 163) if current_slouch_ratio > 0.70 else (113, 113, 248)
        cv2.putText(frame, f"Spine Ratio: {current_slouch_ratio:.2f}", (12, 282), cv2.FONT_HERSHEY_SIMPLEX, 0.46, ratio_color, 1, cv2.LINE_AA)

        cv2.imshow('DeskBot Cyber-HUD v3.0', frame)
        if cv2.waitKey(1) & 0xFF == ord('q'): break

    cap.release()
    cv2.destroyAllWindows()
    db_conn.close()

if __name__ == "__main__":
    main()