"""Entrena los 3 modelos IDS sobre el espacio de 10 features REALES de la red.

Reemplaza a crear_modelos_placeholder.py (que usaba ruido aleatorio sin sentido).

Los features son los de deteccion_red.NOMBRES_FEATURES, es decir, EXACTAMENTE los
que Errik extrae en vivo de las conexiones TCP del teléfono. Así las predicciones
son coherentes: el modelo aprende en el mismo espacio en el que luego predice.

Etiquetas:
    0 = NORMAL   -> navegación/apps típicas (pocas IPs, puertos de servidor 80/443,
                    apenas SYN_SENT, ratio ips/conexiones bajo)
    1 = ANÓMALO  -> firma de escaneo de puertos / beaconing C2 (muchos SYN_SENT,
                    muchas IPs remotas distintas, ratio alto, puertos altos)

No es un dataset de ataques capturados (el teléfono no tiene ataques etiquetados),
sino un modelo explícito de esas dos conductas. Es la versión honesta y ejecutable
de "detectar anomalías en la red del propio dispositivo".

Ejecuta una sola vez (o cuando quieras reentrenar):
    python entrenar_modelos.py

Genera en Modelos_IDS/:
    ids_random_forest.pkl   (scikit-learn RandomForest)
    deep_model_1.h5         (Keras / TensorFlow)
    deep_model_2.pt         (PyTorch, state_dict de TinyNet)
    scaler.pkl              (StandardScaler compartido por los 3 modelos)
"""
from pathlib import Path

import numpy as np

from deteccion_red import NOMBRES_FEATURES, N_FEATURES

BASE_DIR = Path(__file__).resolve().parent
MODELS_DIR = BASE_DIR / "Modelos_IDS"
MODELS_DIR.mkdir(exist_ok=True)

SEED = 42
rng = np.random.default_rng(SEED)


def _generar_normal(n):
    """Tráfico normal: navegación y apps. Vectores en el orden de NOMBRES_FEATURES."""
    establecidas = rng.poisson(6, n).clip(1, 40)
    escuchando = rng.integers(2, 7, n)
    syn_sent = rng.poisson(0.3, n).clip(0, 3)
    time_wait = rng.poisson(3, n).clip(0, 20)
    # En conexiones salientes normales el puerto remoto es el del servidor (80/443)
    # -> mayoría privilegiados; pocos a puertos altos.
    externas = establecidas
    ips_unicas = np.minimum(
        externas, np.round(externas * rng.uniform(0.4, 1.0, n))
    ).astype(int).clip(0, None)
    puertos_priv = np.round(externas * rng.uniform(0.6, 1.0, n)).astype(int)
    puertos_altos = (externas - puertos_priv).clip(0, None)
    total = establecidas + escuchando + time_wait + syn_sent + rng.integers(0, 4, n)
    ratio = ips_unicas / np.maximum(establecidas, 1)

    return np.stack([
        total, establecidas, escuchando, syn_sent, time_wait,
        ips_unicas, puertos_altos, puertos_priv, externas, ratio,
    ], axis=1).astype("float32")


def _generar_anomalo(n):
    """Escaneo de puertos / beaconing: muchos SYN_SENT, muchas IPs, ratio alto."""
    syn_sent = rng.integers(8, 90, n)
    establecidas = rng.poisson(4, n).clip(0, 15)
    escuchando = rng.integers(1, 6, n)
    time_wait = rng.integers(0, 15, n)
    ips_unicas = rng.integers(15, 160, n)
    externas = ips_unicas + rng.integers(0, 10, n)
    # Un escaneo golpea sobre todo puertos altos y variados.
    puertos_altos = np.round((syn_sent + externas) * rng.uniform(0.6, 1.0, n)).astype(int)
    puertos_priv = rng.integers(0, 8, n)
    total = establecidas + escuchando + time_wait + syn_sent + rng.integers(0, 6, n)
    ratio = ips_unicas / np.maximum(establecidas, 1)

    return np.stack([
        total, establecidas, escuchando, syn_sent, time_wait,
        ips_unicas, puertos_altos, puertos_priv, externas, ratio,
    ], axis=1).astype("float32")


def construir_dataset(n_por_clase=1500):
    normal = _generar_normal(n_por_clase)
    anomalo = _generar_anomalo(n_por_clase)
    X = np.vstack([normal, anomalo]).astype("float32")
    y = np.concatenate([
        np.zeros(n_por_clase, dtype="int64"),
        np.ones(n_por_clase, dtype="int64"),
    ])
    # Baraja
    idx = rng.permutation(len(X))
    return X[idx], y[idx]


def main():
    assert N_FEATURES == 10, "El pipeline está diseñado para 10 features."
    X, y = construir_dataset()
    print(f"Dataset: {X.shape[0]} muestras, {X.shape[1]} features "
          f"({NOMBRES_FEATURES})")

    # Separación train/test para reportar accuracy real.
    n = len(X)
    corte = int(n * 0.8)
    Xtr, Xte = X[:corte], X[corte:]
    ytr, yte = y[:corte], y[corte:]

    # Escalado compartido (se ajusta SOLO con train).
    import joblib
    from sklearn.preprocessing import StandardScaler

    scaler = StandardScaler().fit(Xtr)
    Xtr_s = scaler.transform(Xtr).astype("float32")
    Xte_s = scaler.transform(Xte).astype("float32")
    scaler_path = MODELS_DIR / "scaler.pkl"
    joblib.dump(scaler, scaler_path)
    print(f"OK Scaler   -> {scaler_path}")

    # 1) scikit-learn RandomForest
    from sklearn.ensemble import RandomForestClassifier

    rf = RandomForestClassifier(n_estimators=100, random_state=SEED)
    rf.fit(Xtr_s, ytr)
    acc_rf = rf.score(Xte_s, yte)
    rf_path = MODELS_DIR / "ids_random_forest.pkl"
    joblib.dump(rf, rf_path)
    print(f"OK RandomForest -> {rf_path}   (accuracy test: {acc_rf:.3f})")

    # 2) Keras / TensorFlow
    import tensorflow as tf

    tf.keras.utils.set_random_seed(SEED)
    keras_model = tf.keras.Sequential([
        tf.keras.layers.Input(shape=(N_FEATURES,)),
        tf.keras.layers.Dense(16, activation="relu"),
        tf.keras.layers.Dense(2, activation="softmax"),
    ])
    keras_model.compile(optimizer="adam",
                        loss="sparse_categorical_crossentropy",
                        metrics=["accuracy"])
    keras_model.fit(Xtr_s, ytr, epochs=30, batch_size=32, verbose=0)
    _, acc_k = keras_model.evaluate(Xte_s, yte, verbose=0)
    keras_path = MODELS_DIR / "deep_model_1.h5"
    keras_model.save(keras_path)
    print(f"OK Keras     -> {keras_path}   (accuracy test: {acc_k:.3f})")

    # 3) PyTorch (TinyNet: Linear -> ReLU -> Linear). Mismo diseño que en Erik.py.
    import torch
    import torch.nn as nn

    torch.manual_seed(SEED)

    class TinyNet(nn.Module):
        def __init__(self, d):
            super().__init__()
            self.net = nn.Sequential(nn.Linear(d, 16), nn.ReLU(), nn.Linear(16, 2))

        def forward(self, x):
            return self.net(x)

    torch_model = TinyNet(N_FEATURES)
    opt = torch.optim.Adam(torch_model.parameters(), lr=1e-2)
    loss_fn = nn.CrossEntropyLoss()
    Xt = torch.from_numpy(Xtr_s)
    yt = torch.from_numpy(ytr)
    for _ in range(200):
        opt.zero_grad()
        out = torch_model(Xt)
        loss = loss_fn(out, yt)
        loss.backward()
        opt.step()
    with torch.no_grad():
        pred_te = torch_model(torch.from_numpy(Xte_s)).argmax(1).numpy()
    acc_t = float((pred_te == yte).mean())
    torch_path = MODELS_DIR / "deep_model_2.pt"
    torch.save(torch_model.state_dict(), torch_path)
    print(f"OK PyTorch   -> {torch_path}   (accuracy test: {acc_t:.3f})")

    print("\nListo. Modelos + scaler guardados en:", MODELS_DIR)


if __name__ == "__main__":
    main()
