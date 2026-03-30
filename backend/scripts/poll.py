#!/usr/bin/env python3
# Minimal Python poller (requires requests)
import time, sys
import requests

UPLOAD_URL='http://127.0.0.1:8080/api/scan/upload'
POLL_URL='http://127.0.0.1:8080/api/scan/{}'
FILEPATH='/tmp/deepshield_sample.png'

r = requests.post(UPLOAD_URL, files={'file': open(FILEPATH,'rb')})
if r.status_code != 201:
    print('Upload failed', r.status_code, r.text)
    sys.exit(1)
job = r.json()
print('Upload response:', job)
job_id = job.get('id')
for i in range(30):
    r2 = requests.get(POLL_URL.format(job_id))
    print('Poll', i+1, r2.status_code, r2.json())
    status = r2.json().get('status')
    if status in ('COMPLETE','FAILED'):
        break
    time.sleep(2)
