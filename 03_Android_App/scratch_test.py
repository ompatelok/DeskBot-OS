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

client = AWSIoTMQTTClient('test_pub_intervals', useWebsocket=True)
client.configureEndpoint('a21v06g95kktaa-ats.iot.us-east-1.amazonaws.com', 443)
client.configureCredentials(certifi.where())
client.configureIAMCredentials(creds['AccessKeyId'], creds['SecretKey'], creds['SessionToken'])

print('Connecting...')
client.connect()
print('Connected!')
payload = '{"test":1}'
print('Publishing to deskbot/settings/intervals...')
res = client.publish('deskbot/settings/intervals', payload, 1)
print('Publish result:', res)
time.sleep(3)
print('Publishing to deskbot/notifications/stream...')
res2 = client.publish('deskbot/notifications/stream', payload, 1)
print('Publish result 2:', res2)
time.sleep(3)
print('Done!')
client.disconnect()
