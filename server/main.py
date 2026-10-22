from fastapi import FastAPI, File, UploadFile
from fastapi.responses import Response
from rembg import remove, new_session
import uvicorn
import io

app = FastAPI(title="SpectraTone Python AI Engine")

# Pre-load high quality ONNX AI segmentation model
session = new_session("u2netp")

@app.get("/")
def health():
    return {
        "status": "online",
        "app": "SpectraTone AI Service",
        "engine": "Python FastAPI + ONNX RMBG"
    }

@app.post("/remove-bg")
async def remove_background(file: UploadFile = File(...)):
    """Receives image from Android Kotlin App and returns cutout PNG"""
    input_data = await file.read()
    output_data = remove(input_data, session=session)
    return Response(content=output_data, media_type="image/png")

if __name__ == "__main__":
    uvicorn.run("main:app", host="0.0.0.0", port=7860, reload=False)
