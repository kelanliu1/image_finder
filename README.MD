# Image Webscraper Powered w/ FastAPI and Machine Learning

Hey there! This project is a multi-threaded webscraper that uses JSoup to scrape images from a URL and its sub-pages. I also used FastAPI to develop a classifier service that can classify the images using a pre-trained YOLOv5 model found on https://pytorch.org/hub/ultralytics_yolov5/. 

Note that the global constants MAX_DEPTH and MAX_PAGES are both defaulted to 1. Since machine learning compute can be quite costly and slow (and this project is not production grade), these constants were minimized to reduce waiting time. You can change these constants at your own discretion. Please be patient, thanks!!!

You can also view the UI preview on ui_screenshot.png 

## Setup Classifier
Change to the 'classifier' directory:
>`cd classifier`

Install depedencies (FastAPI, PyTorch, OpenCV, etc.):
>`pip install -r requirements.txt`

Start FastAPI server:
>`uvicorn main:app --reload`

Nice! Now, the server should be defaulted to port 8000!

## Start Java Project
The structure and setup for the Java ImageFinder servlet was not changed. You can start it up the same way!
You can open up the application on port 8080 and use the test links found in imagefinder/test-links.txt.