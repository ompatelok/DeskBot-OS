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
# --- 1. SETTINGS, STATE & PROFILES ---
# ==========================================
root = tk.Tk()
root.withdraw()

state = {
    "current_user": "Om Kasvala",
    "profiles": ["Om Kasvala", "Guest"],
    "slouch_delay": 5,     
    "drowsy_delay": 3,     
    "phone_delay": 10,     
    "running": True        
}

BTN_USER = (600, 30, 780, 70)
BTN_SETTING = (600, 90, 780, 130)
BTN_SYNC = (600, 150, 780, 190)
BTN_REPORT = (600, 210, 780, 250) 
BTN_EXIT = (600, 530, 780, 570)

# ==========================================
# --- 2. LOCAL DATABASE (PRIVACY FIRST) ---
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
# --- 3. ADVANCED ANALYTICS ENGINE ---
# ==========================================
def format_time(seconds):
    """Formats seconds into intelligent H/M/S strings based on duration."""
    if seconds < 3600:
        return f"{int(seconds // 60)}m {int(seconds % 60)}s"
    else:
        hours = int(seconds // 3600)
        minutes = int((seconds % 3600) // 60)
        return f"{hours}h {minutes}m"

def generate_report(user_name, time_range):
    print(f"Generating {time_range} local report...")
    cursor = db_conn.cursor()
    
    # Dynamic SQL Queries based on user selection
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
    
    # --- Advanced Time Filtering ---
    for i in range(len(logs) - 1):
        t1 = datetime.strptime(logs[i][0], '%Y-%m-%d %H:%M:%S')
        t2 = datetime.strptime(logs[i+1][0], '%Y-%m-%d %H:%M:%S')
        duration = (t2 - t1).total_seconds()
        
        # Gap Filter: If gap is > 1 hour, assume user left the desk without logging out
        if duration < 3600: 
            logged_state = logs[i][1]
            if logged_state in state_durations:
                state_durations[logged_state] += duration

    # Live Delta: Add time from the very last log to right *now*, if it happened recently
    last_log_time = datetime.strptime(logs[-1][0], '%Y-%m-%d %H:%M:%S')
    time_to_now = (datetime.now() - last_log_time).total_seconds()
    if time_to_now < 3600:
        last_logged_state = logs[-1][1]
        if last_logged_state in state_durations:
            state_durations[last_logged_state] += time_to_now

    # Check for empty data
    total_tracked_time = sum(state_durations.values())
    if total_tracked_time < 5: 
        messagebox.showinfo("Report Error", "Gathering data... please work for a few more seconds.", parent=root)
        return

    # --- Prepare Graph Data ---
    labels, sizes, colors, bar_labels, bar_sizes = [], [], [], [], []
    color_map = {
        "Good Posture": '#00e676',         
        "Slouching": '#ff9100',            
        "Phone Distraction": '#ff1744',    
        "Drowsy / Sleeping": '#d500f9',    
        "Not At Desk": '#78909c'           
    }

    # Sort data for the bar chart (highest duration first)
    sorted_states = sorted(state_durations.items(), key=lambda item: item[1], reverse=True)

    for st, dur in sorted_states:
        if dur > 0:
            formatted_dur = format_time(dur)
            labels.append(f"{st} ({formatted_dur})")
            sizes.append(dur)
            colors.append(color_map[st])
            
            # Data for Bar Chart
            bar_labels.append(st)
            bar_sizes.append(dur / 60) # Convert to minutes for bar chart scale

    efficiency_score = int((state_durations["Good Posture"] / total_tracked_time) * 100)

    # --- DRAW DUAL-PANE PROFESSIONAL DASHBOARD ---
    plt.style.use('dark_background')
    fig = plt.figure(figsize=(12, 6))
    fig.canvas.manager.set_window_title('DeskBot OS - Executive Analytics')
    
    # Create a 1x2 grid (Donut on left, Bar on right)
    gs = GridSpec(1, 2, width_ratios=[1.2, 1])
    ax1 = fig.add_subplot(gs[0])
    ax2 = fig.add_subplot(gs[1])
    
    # --- PANE 1: The Efficiency Donut ---
    wedges, texts, autotexts = ax1.pie(sizes, colors=colors, autopct='%1.1f%%', startangle=140, 
                                      pctdistance=0.82, explode=[0.02]*len(sizes),
                                      wedgeprops=dict(width=0.4, edgecolor='#121212', linewidth=2))
    plt.setp(autotexts, size=10, weight="bold", color="white")
    ax1.legend(wedges, labels, title="State Breakdown", loc="center left", bbox_to_anchor=(0.9, 0, 0.5, 1),
              frameon=False, fontsize=10, title_fontsize=12)
    ax1.text(0, 0, f"EFFICIENCY\n{efficiency_score}%", ha='center', va='center', fontsize=20, fontweight='bold', color='#00FFFF')
    ax1.set_title(f"{title_text} Overview", fontsize=14, fontweight='bold', color="white")

    # --- PANE 2: The Absolute Duration Bar Chart ---
    y_pos = np.arange(len(bar_labels))
    bars = ax2.barh(y_pos, bar_sizes, color=colors, edgecolor='#121212', height=0.6)
    ax2.set_yticks(y_pos)
    ax2.set_yticklabels(bar_labels, fontsize=10)
    ax2.invert_yaxis()  # Highest value at the top
    ax2.set_xlabel('Total Minutes', fontsize=10, color='gray')
    ax2.set_title("Absolute Time Distribution", fontsize=14, fontweight='bold', color="white")
    
    # Add value labels to the end of the bars
    for bar in bars:
        width = bar.get_width()
        ax2.text(width + (max(bar_sizes)*0.02), bar.get_y() + bar.get_height()/2, 
                 f'{int(width)}m', ha='left', va='center', fontsize=9, color='white')
    
    # Remove top and right spines for a cleaner look
    ax2.spines['top'].set_visible(False)
    ax2.spines['right'].set_visible(False)
    ax2.spines['left'].set_color('#333333')
    ax2.spines['bottom'].set_color('#333333')
    ax2.xaxis.grid(True, linestyle='--', alpha=0.3)

    # --- Global Formatting ---
    current_time_str = datetime.now().strftime("%B %d, %Y | %I:%M %p")
    fig.suptitle(f"DeskBot Advanced Analytics\nUser: {user_name.upper()}", fontsize=18, fontweight='bold', y=0.98)
    fig.text(0.5, 0.02, f"Total Tracked Time: {format_time(total_tracked_time)}  |  Report Generated Locally: {current_time_str}", ha='center', fontsize=10, color='gray')
    
    plt.tight_layout(rect=[0, 0.05, 1, 0.9])
    plt.show() 

# ==========================================
# --- 4. UI MOUSE CALLBACK ---
# ==========================================
def mouse_click(event, x, y, flags, param):
    global state, db_conn, mqtt_client
    
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
            if sl is not None: state["slouch_delay"] = sl
            if dr is not None: state["drowsy_delay"] = dr
            if ph is not None: state["phone_delay"] = ph

        elif BTN_SYNC[0] < x < BTN_SYNC[2] and BTN_SYNC[1] < y < BTN_SYNC[3]:
            cursor = db_conn.cursor()
            cursor.execute("SELECT id, user, timestamp, ai_state FROM session_logs WHERE synced_to_cloud = 0")
            rows = cursor.fetchall()
            if rows:
                for row in rows:
                    payload = json.dumps({"user": row[1], "time": row[2], "state": row[3]})
                    try:
                        mqtt_client.publish(AWS_TOPIC, payload, 1)
                        cursor.execute("UPDATE session_logs SET synced_to_cloud = 1 WHERE id = ?", (row[0],))
                    except Exception as e:
                        pass
                db_conn.commit()
                messagebox.showinfo("AWS Sync", f"Successfully synced {len(rows)} records to cloud.", parent=root)
            else:
                messagebox.showinfo("AWS Sync", "All local data is already synced.", parent=root)
                
        elif BTN_REPORT[0] < x < BTN_REPORT[2] and BTN_REPORT[1] < y < BTN_REPORT[3]:
            # Pop up a dialog to ask which report to run
            report_choice = simpledialog.askinteger("Analytics Engine", "Select Report Type:\n\n1 = Daily (Today)\n2 = Weekly (Last 7 Days)\n3 = Monthly (Last 30 Days)", parent=root, minvalue=1, maxvalue=3)
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
AWS_ENDPOINT = "a21v06g95kktaa-ats.iot.us-east-1.amazonaws.com" 
AWS_PORT = 8883
AWS_CLIENT_ID = "DeskBot_Laptop"
AWS_TOPIC = "deskbot/posture"
ROOT_CA_PATH = "AmazonRootCA1.pem"
PRIVATE_KEY_PATH = "84e1153d043d6ed1be7d450c5592100b803381db789cf0d702ff9ae86572b8b1-private.pem.key"
CERTIFICATE_PATH = "84e1153d043d6ed1be7d450c5592100b803381db789cf0d702ff9ae86572b8b1-certificate.pem.crt"

mqtt_client = AWSIoTMQTTClient(AWS_CLIENT_ID)
mqtt_client.configureEndpoint(AWS_ENDPOINT, AWS_PORT)
mqtt_client.configureCredentials(ROOT_CA_PATH, PRIVATE_KEY_PATH, CERTIFICATE_PATH)
try:
    mqtt_client.connect()
except:
    pass

# ==========================================
# --- 6. GEOMETRY MATH ---
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
# --- 7. MAIN ENGINE ---
# ==========================================
def main():
    download_model("https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/1/pose_landmarker_lite.task", 'models/pose_landmarker_lite.task')
    download_model("https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/int8/1/efficientdet_lite0.tflite", 'efficientdet_lite0.tflite')
    download_model("https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task", 'face_landmarker.task')

    model_data = joblib.load('models/posture_model_v3.pkl')
    rf_model, scaler = model_data['model'], model_data['scaler']

    BaseOptions = mp.tasks.BaseOptions
    VisionRunningMode = mp.tasks.vision.RunningMode

    landmarker = mp.tasks.vision.PoseLandmarker.create_from_options(mp.tasks.vision.PoseLandmarkerOptions(base_options=BaseOptions(model_asset_path='models/pose_landmarker_lite.task'), running_mode=VisionRunningMode.VIDEO))
    object_detector = mp.tasks.vision.ObjectDetector.create_from_options(mp.tasks.vision.ObjectDetectorOptions(base_options=BaseOptions(model_asset_path='efficientdet_lite0.tflite'), running_mode=VisionRunningMode.VIDEO, max_results=2, category_allowlist=['cell phone'], score_threshold=0.30))
    face_landmarker = mp.tasks.vision.FaceLandmarker.create_from_options(mp.tasks.vision.FaceLandmarkerOptions(base_options=BaseOptions(model_asset_path='face_landmarker.task'), running_mode=VisionRunningMode.VIDEO, num_faces=1))

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

    smoothed_landmarks = {}
    smoothed_bbox = None
    target_landmarks = None
    target_bbox = None

    print("Starting Flawless Cyber-HUD Vision...")

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
            
            # --- Phone ---
            obj_result = object_detector.detect_for_video(mp_image, timestamp_ms)
            if obj_result.detections:
                bbox = obj_result.detections[0].bounding_box
                target_bbox = [bbox.origin_x, bbox.origin_y, bbox.width, bbox.height]
                phone_accumulated += delta_time * 3
            else:
                target_bbox = None
                phone_accumulated = max(0.0, phone_accumulated - delta_time * 3)

            # --- Drowsiness ---
            face_result = face_landmarker.detect_for_video(mp_image, timestamp_ms)
            is_drowsy_frame = False
            if face_result.face_landmarks:
                if calculate_ear(face_result.face_landmarks[0], w, h) < 0.22:
                    drowsy_accumulated += delta_time * 3
                    is_drowsy_frame = True
            if not is_drowsy_frame: drowsy_accumulated = max(0.0, drowsy_accumulated - delta_time * 6)

            # --- Posture ---
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
                
                if raw_prediction == "Slouching":
                    if current_slouch_ratio > 0.70: 
                        raw_prediction = "Good Posture"

                prediction_history.append(raw_prediction)
                
                if raw_prediction == "Slouching": slouch_accumulated += delta_time * 3
                else: slouch_accumulated = max(0.0, slouch_accumulated - delta_time * 6)
            else:
                target_landmarks = None
                prediction_history.append("Not At Desk")
                slouch_accumulated = 0.0
                current_slouch_ratio = 0.0

        # --- Master State Decision ---
        final_state = "Good Posture"
        if len(prediction_history) > 0:
            if prediction_history.count("Not At Desk") > 5: final_state = "Not At Desk"
            elif phone_accumulated >= state["phone_delay"]: final_state = "Phone Distraction"
            elif drowsy_accumulated >= state["drowsy_delay"]: final_state = "Drowsy / Sleeping"
            elif slouch_accumulated >= state["slouch_delay"]: final_state = "Slouching"

        if final_state != last_logged_state and final_state != "Not At Desk":
            db_conn.cursor().execute("INSERT INTO session_logs (user, ai_state) VALUES (?, ?)", (state["current_user"], final_state))
            db_conn.commit()
            last_logged_state = final_state
            
            payload = json.dumps({"state": final_state})
            try:
                mqtt_client.publish(AWS_TOPIC, payload, 1)
            except Exception as e:
                pass

        # ==========================================
        # --- HUD DRAWING ENGINE ---
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
                    cv2.line(frame, (x1, y1), (x2, y2), (255, 255, 0), 2)
            for i in smoothed_landmarks:
                cv2.circle(frame, (int(smoothed_landmarks[i][0]), int(smoothed_landmarks[i][1])), 5, (0, 255, 255), -1)
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
            cv2.rectangle(frame, (sx, sy), (sx + sw, sy + sh), (0, 0, 255), 2)
            cv2.putText(frame, "DISTRACTION DETECTED", (sx, sy - 10), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0, 0, 255), 2)
        else: smoothed_bbox = None

        cv2.rectangle(frame, (580, 0), (800, 600), (20, 20, 20), -1)
        
        def draw_btn(bbox, text, color=(100, 50, 50)):
            cv2.rectangle(frame, (bbox[0], bbox[1]), (bbox[2], bbox[3]), color, -1)
            cv2.rectangle(frame, (bbox[0], bbox[1]), (bbox[2], bbox[3]), (255, 255, 255), 1)
            cv2.putText(frame, text, (bbox[0] + 20, bbox[1] + 25), cv2.FONT_HERSHEY_SIMPLEX, 0.6, (255, 255, 255), 1)

        draw_btn(BTN_USER, "ADD USER", (100, 50, 0))
        draw_btn(BTN_SETTING, "SETTINGS", (50, 100, 0))
        draw_btn(BTN_SYNC, "SYNC AWS", (0, 100, 100))
        draw_btn(BTN_REPORT, "ANALYTICS", (100, 0, 100)) 
        draw_btn(BTN_EXIT, "EXIT SYSTEM", (0, 0, 150))

        cv2.putText(frame, f"USER: {state['current_user'].upper()}", (10, 30), cv2.FONT_HERSHEY_DUPLEX, 0.7, (255, 255, 0), 1)
        cv2.putText(frame, f"MASTER STATE: {final_state.upper()}", (10, 70), cv2.FONT_HERSHEY_DUPLEX, 0.7, (0, 255, 0) if final_state == "Good Posture" else (0, 0, 255), 2)

        def draw_bar(y, label, current, maximum, color):
            cv2.putText(frame, label, (10, y), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (200, 200, 200), 1)
            fill = int((min(current, maximum) / maximum) * 200)
            cv2.rectangle(frame, (10, y+10), (210, y+25), (50, 50, 50), -1)
            cv2.rectangle(frame, (10, y+10), (10+fill, y+25), color, -1)

        draw_bar(120, f"Slouch Timer ({state['slouch_delay']}s)", slouch_accumulated, state['slouch_delay'], (0, 165, 255))
        draw_bar(170, f"Drowsy Timer ({state['drowsy_delay']}s)", drowsy_accumulated, state['drowsy_delay'], (255, 0, 0))
        draw_bar(220, f"Phone Timer ({state['phone_delay']}s)", phone_accumulated, state['phone_delay'], (0, 0, 255))
        
        cv2.putText(frame, "AI DIAGNOSTICS", (10, 280), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (200, 200, 200), 1)
        ratio_color = (0, 255, 0) if current_slouch_ratio > 0.70 else (0, 0, 255)
        cv2.putText(frame, f"Spine Ratio: {current_slouch_ratio:.2f}", (10, 305), cv2.FONT_HERSHEY_SIMPLEX, 0.5, ratio_color, 1)

        cv2.imshow('DeskBot Cyber-HUD v3.0', frame)
        if cv2.waitKey(1) & 0xFF == ord('q'): break

    cap.release()
    cv2.destroyAllWindows()
    db_conn.close()

if __name__ == "__main__":
    main()