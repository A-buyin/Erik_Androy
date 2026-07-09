"""Genera modelos placeholder sintéticos para que Errik cargue sin errores.

Ejecuta una sola vez:
    python crear_modelos_placeholder.py

Crea:
    Modelos_IDS/ids_random_forest.pkl   (scikit-learn RandomForest)
    Modelos_IDS/deep_model_1.h5         (Keras secuencial)
    Modelos_IDS/deep_model_2.pt         (state_dict de PyTorch)
"""
from pathlib import Path
import numpy as np

BASE_DIR = Path(__file__).resolve().parent
MODELS_DIR = BASE_DIR / "Modelos_IDS"
MODELS_DIR.mkdir(exist_ok=True)

rng = np.random.default_rng(42)
N, D = 200, 10
X = rng.standard_normal((N, D)).astype("float32")
y = (X.sum(axis=1) > 0).astype("int64")

# 1) scikit-learn RandomForest
import joblib
from sklearn.ensemble import RandomForestClassifier

rf = RandomForestClassifier(n_estimators=10, random_state=42)
rf.fit(X, y)
rf_path = MODELS_DIR / "ids_random_forest.pkl"
joblib.dump(rf, rf_path)
print(f"OK RandomForest -> {rf_path}")

# 2) Keras / TensorFlow
import tensorflow as tf

keras_model = tf.keras.Sequential([
    tf.keras.layers.Input(shape=(D,)),
    tf.keras.layers.Dense(16, activation="relu"),
    tf.keras.layers.Dense(2, activation="softmax"),
])
keras_model.compile(optimizer="adam", loss="sparse_categorical_crossentropy", metrics=["accuracy"])
keras_model.fit(X, y, epochs=2, verbose=0)
keras_path = MODELS_DIR / "deep_model_1.h5"
keras_model.save(keras_path)
print(f"OK Keras -> {keras_path}")

# 3) PyTorch
import torch
import torch.nn as nn

class TinyNet(nn.Module):
    def __init__(self, d):
        super().__init__()
        self.net = nn.Sequential(nn.Linear(d, 16), nn.ReLU(), nn.Linear(16, 2))

    def forward(self, x):
        return self.net(x)

torch_model = TinyNet(D)
opt = torch.optim.Adam(torch_model.parameters(), lr=1e-2)
loss_fn = nn.CrossEntropyLoss()
Xt = torch.from_numpy(X)
yt = torch.from_numpy(y)
for _ in range(20):
    opt.zero_grad()
    out = torch_model(Xt)
    loss = loss_fn(out, yt)
    loss.backward()
    opt.step()

torch_path = MODELS_DIR / "deep_model_2.pt"
torch.save(torch_model.state_dict(), torch_path)
print(f"OK PyTorch -> {torch_path}")

print("\nListo. Modelos guardados en:", MODELS_DIR)
