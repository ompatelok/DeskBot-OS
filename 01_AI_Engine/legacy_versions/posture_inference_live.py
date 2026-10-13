# posture_inference_v2.py
# This script is designed for local execution with a webcam.

import cv2
import mediapipe as mp
from mediapipe.tasks import python
from mediapipe.tasks.python import vision
import joblib
import numpy as np
import os

def main():
    # Define the path to your model file (adjust if you move it locally)
    model_path = 'models/posture_model_v3.pkl'

    # 1. & 2. Load the model and scaler from the pickle file
    try:
        if not os.path.exists(model_path):
            raise FileNotFoundError(f"Error: '{model_path}' not found. Please ensure the model file is in the same directory as this script or provide the full path.")

        model_data = joblib.load(model_path)
        model = model_data['model']
        scaler = model_data['scaler']
        print("Model and Scaler loaded successfully.")

        # Pose landmarker model
        pose_model_path = 'models/pose_landmarker_lite.task'
        if not os.path.exists(pose_model_path):
            raise FileNotFoundError(f"Error: '{pose_model_path}' not found. Please download the pose landmarker model.")

        # Create the pose landmarker
        BaseOptions = mp.tasks.BaseOptions
        PoseLandmarker = mp.tasks.vision.PoseLandmarker
        PoseLandmarkerOptions = mp.tasks.vision.PoseLandmarkerOptions
        VisionRunningMode = mp.tasks.vision.RunningMode

        options = PoseLandmarkerOptions(
            base_options=BaseOptions(model_asset_path=pose_model_path),
            running_mode=VisionRunningMode.VIDEO,
            min_pose_detection_confidence=0.7,
            min_pose_presence_confidence=0.7,
            min_tracking_confidence=0.7
        )

        with PoseLandmarker.create_from_options(options) as landmarker:
            # 4. Set up video capture
            cap = cv2.VideoCapture(0) # 0 usually refers to the default webcam
            if not cap.isOpened():
                print("Error: Could not open webcam. Make sure it's connected and not in use.")
                print("This script requires a local webcam to function.")
                return

            print("Starting real-time inference. Press 'q' key in the video window to quit.")

            timestamp_ms = 0

            while cap.isOpened():
                ret, frame = cap.read()
                if not ret:
                    print("Ignoring empty camera frame or stream ended.")
                    break

                # Convert the frame to RGB for MediaPipe
                rgb_frame = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)

                # Create mp.Image
                mp_image = mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb_frame)

                # Detect pose
                result = landmarker.detect_for_video(mp_image, timestamp_ms)
                timestamp_ms += 33  # approx 30 fps

                posture_prediction = "Detecting..."

                if result.pose_landmarks:
                    # Take the first pose
                    lm = result.pose_landmarks[0]
                    # Extract X, Y for specified landmarks: Nose, L/R Eye, L/R Shoulder
                    # Indices: NOSE=0, LEFT_EYE=1, RIGHT_EYE=2, LEFT_SHOULDER=11, RIGHT_SHOULDER=12
                    features = [
                        lm[0].x, lm[0].y,
                        lm[1].x, lm[1].y,
                        lm[2].x, lm[2].y,
                        lm[11].x, lm[11].y,
                        lm[12].x, lm[12].y
                    ]

                    # Scale the extracted features using the loaded scaler
                    features_scaled = scaler.transform([features])

                    # Make a prediction using the loaded model
                    prediction = model.predict(features_scaled)[0]
                    posture_prediction = f"Status: {prediction}"

                # Overlay prediction text on the frame
                cv2.putText(frame, posture_prediction, (10, 50),
                            cv2.FONT_HERSHEY_SIMPLEX, 1, (0, 255, 0), 2, cv2.LINE_AA)

                # Display the resulting frame
                cv2.imshow('Real-Time Posture Detection (v2)', frame)

                # Break the loop if 'q' is pressed
                if cv2.waitKey(1) & 0xFF == ord('q'):
                    break

    except FileNotFoundError as e:
        print(f"An error occurred: {e}")
    except Exception as e:
        print(f"An unexpected error occurred during inference: {e}")
    finally:
        # Release resources
        if 'cap' in locals() and cap.isOpened():
            cap.release()
        cv2.destroyAllWindows()
        print("Real-time inference ended and resources released.")

if __name__ == "__main__":
    main()