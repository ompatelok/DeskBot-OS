import os, time, boto3, certifi
os.environ['AWS_SHARED_CREDENTIALS_FILE'] = 'NUL'
os.environ['AWS_CONFIG_FILE'] = 'NUL'
from botocore import UNSIGNED
from botocore.client import Config
from AWSIoTPythonSDK.MQTTLib import AWSIoTMQTTClient

ci = boto3.client('cognito-identity', region_name='us-east-1', config=Config(signature_version=UNSIGNED))
resp = ci.get_id(IdentityPoolId='us-east-1:48245b37-8f77-4002-b31d-a2c09e59ca08')
identity_id = resp['IdentityId']
creds = ci.get_credentials_for_identity(IdentityId=identity_id)['Credentials']

client_id = "DeskBot_Android_123456789012"
client = AWSIoTMQTTClient(client_id, useWebsocket=True)
client.configureEndpoint('a21v06g95kktaa-ats.iot.us-east-1.amazonaws.com', 443)
client.configureCredentials(certifi.where())
client.configureIAMCredentials(creds['AccessKeyId'], creds['SecretKey'], creds['SessionToken'])
client.configureAutoReconnectBackoffTime(1, 32, 20)
client.configureConnectDisconnectTimeout(10)
client.configureMQTTOperationTimeout(5)

def on_msg(c, u, m):
    print("Received:", m.topic, m.payload)

print("Connecting with client_id:", client_id)
connected = client.connect()
print("Connected:", connected)
if connected:
    print("Subscribing to topics...")
    s1 = client.subscribe("deskbot/posture/live", 0, on_msg)
    print("Sub deskbot/posture/live:", s1)
    s2 = client.subscribe("deskbot/device/water_ack", 1, on_msg)
    print("Sub deskbot/device/water_ack:", s2)
    s3 = client.subscribe("deskbot/touch/water_ack", 1, on_msg)
    print("Sub deskbot/touch/water_ack:", s3)
    
    print("Subscribed! Listening for 6 seconds...")
    for i in range(6):
        time.sleep(1)
        print("Still connected tick", i+1)
    client.disconnect()
print("Done test.")
