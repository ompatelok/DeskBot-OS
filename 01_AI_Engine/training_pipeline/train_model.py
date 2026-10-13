import pandas as pd
import numpy as np
from sklearn.model_selection import train_test_split
from sklearn.ensemble import RandomForestClassifier
from sklearn.preprocessing import StandardScaler
from sklearn.metrics import classification_report, accuracy_score, confusion_matrix
import joblib

def calculate_geometric_features(row):
    """
    Converts raw X/Y coordinates into mathematical ratios and angles.
    This makes the AI immune to different desk heights and camera distances.
    """
    # FIX: If the person is not at the desk, pad the 4 engineered features with 0.0
    # This guarantees EVERY row has exactly 14 columns, preventing the numpy crash.
    if row['label'] == 'Not At Desk':
        return [0.0, 0.0, 0.0, 0.0] + list(row[:-1]) 
    
    # Extract coordinates
    nose = np.array([row['nose_x'], row['nose_y']])
    shoulder_l = np.array([row['shoulder_l_x'], row['shoulder_l_y']])
    shoulder_r = np.array([row['shoulder_r_x'], row['shoulder_r_y']])
    
    # Feature 1: Shoulder Width (Proxy for distance to camera)
    shoulder_width = np.linalg.norm(shoulder_l - shoulder_r)
    
    # Feature 2: Midpoint of shoulders
    shoulder_midpoint = (shoulder_l + shoulder_r) / 2.0
    
    # Feature 3: Neck Length (Distance from nose to shoulder midpoint)
    neck_length = np.linalg.norm(nose - shoulder_midpoint)
    
    # Feature 4: The "Slouch Ratio" (Crucial for eliminating height differences)
    slouch_ratio = neck_length / (shoulder_width + 1e-6) # Add tiny number to prevent divide by zero
    
    # Feature 5: Shoulder Tilt Angle (Are they leaning on an armrest?)
    delta_y = shoulder_r[1] - shoulder_l[1]
    delta_x = shoulder_r[0] - shoulder_l[0]
    shoulder_tilt_angle = np.degrees(np.arctan2(delta_y, delta_x))

    # We feed the AI the 4 engineered geometric features PLUS the 10 raw coordinates (14 Total)
    features = [
        shoulder_width,
        neck_length,
        slouch_ratio,
        shoulder_tilt_angle,
        row['nose_x'], row['nose_y'],
        row['eye_l_x'], row['eye_l_y'],
        row['eye_r_x'], row['eye_r_y'],
        row['shoulder_l_x'], row['shoulder_l_y'],
        row['shoulder_r_x'], row['shoulder_r_y']
    ]
    return features

def main():
    print("--- DESKBOT AI: MODEL TRAINING PIPELINE ---")
    print("Loading 39,000+ rows of real-world data...")
    df = pd.read_csv('posture_data_real.csv')

    print("Engineering mathematical geometry features...")
    # Apply our custom math to every row in the dataset
    engineered_data = []
    labels = df['label'].values
    
    for index, row in df.iterrows():
        features = calculate_geometric_features(row)
        engineered_data.append(features)
        
    X = np.array(engineered_data)
    y = labels

    print("Splitting data (80% Training, 20% Blind Testing)...")
    X_train, X_test, y_train, y_test = train_test_split(X, y, test_size=0.2, random_state=42, stratify=y)

    print("Scaling coordinates...")
    scaler = StandardScaler()
    X_train_scaled = scaler.fit_transform(X_train)
    X_test_scaled = scaler.transform(X_test)

    print("Training Random Forest AI...")
    # 100 decision trees voting together
    model = RandomForestClassifier(n_estimators=100, random_state=42, n_jobs=-1)
    model.fit(X_train_scaled, y_train)

    print("\n--- GRADING THE AI ---")
    y_pred = model.predict(X_test_scaled)
    accuracy = accuracy_score(y_test, y_pred)
    
    print(f"Final Accuracy Score: {accuracy * 100:.2f}%\n")
    print("Detailed Classification Report:")
    print(classification_report(y_test, y_pred))

    # Save the finalized brain and the scaler
    print("\nPackaging and saving the model...")
    joblib.dump({'model': model, 'scaler': scaler}, 'models/posture_model_v3.pkl')
    print("Successfully saved as 'models/posture_model_v3.pkl'!")

if __name__ == "__main__":
    main()