from fastapi import FastAPI
from pydantic import BaseModel
import torch
import cv2
import numpy as np
import httpx
import os

os.environ["CUDA_VISIBLE_DEVICES"]=""

app = FastAPI()

# Load YOLOv5 model 
model = torch.hub.load('ultralytics/yolov5', 'yolov5s')

class ImageUrl(BaseModel):
    image_url: str

@app.post('/process')
async def process_image(image_url: ImageUrl):
    async with httpx.AsyncClient() as client:
        response = await client.get(image_url.image_url)

    if response.status_code != 200:
        return {'error': 'Failed to download image, status code: ' + str(response.status_code)}

    image = cv2.imdecode(np.frombuffer(response.content, np.uint8), cv2.IMREAD_COLOR)

    if image is None or len(image.shape) != 3 or image.shape[2] != 3:
        return {'error': 'Invalid image format'}

    try:
        image_rgb = cv2.cvtColor(image, cv2.COLOR_BGR2RGB)
        results = model(image_rgb)
        classifications = extract_classifications(results)
        return {'classifications': classifications}
    except Exception as e:
        return {'error': 'Error processing image: ' + str(e)}

def extract_classifications(results):
    labels = results.pred[0][:, -1].numpy()
    names = results.names
    classifications = {}

    for label in labels:
        class_name = names[int(label)]
        if class_name in classifications:
            classifications[class_name] += 1
        else:
            classifications[class_name] = 1

    return classifications

if __name__ == '__main__':
    app.run(debug=True, threaded=True)