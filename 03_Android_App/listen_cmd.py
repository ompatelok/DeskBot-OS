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

client = AWSIoTMQTTClient("diag_cmd_listener", useWebsocket=True)
client.configureEndpoint('a21v06g95kktaa-ats.iot.us-east-1.amazonaws.com', 443)
client.configureCredentials(certifi.where())
client.configureIAMCredentials(creds['AccessKeyId'], creds['SecretKey'], creds['SessionToken'])

commands_received = []
def on_cmd(c, u, m):
    print(f"COMMAND RECEIVED ON {m.topic}: {m.payload}")
    commands_received.append(m.payload)

print("Connecting...")
client.connect()
print("Subscribing to deskbot/device/cmd...")
client.subscribe("deskbot/device/cmd", 1, on_cmd)

print("Listening for 15 seconds...")
for i in range(15):
    time.sleep(1)
    if commands_received:
        print(f"Got {len(commands_received)} commands so far!")

print("Total commands received:", len(commands_received))
client.disconnect()
