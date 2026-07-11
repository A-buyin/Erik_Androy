"""Extractor de características (features) reales de la red del dispositivo.

Lee las conexiones TCP activas del sistema (en Android/Termux vía `ss` o
/proc/net/tcp) y calcula 10 métricas numéricas que resumen el estado de la red.
Esas 10 features son las que consumen los modelos IDS (Random Forest, Keras y
PyTorch) para predecir si la actividad es NORMAL o ANÓMALA.

Las MISMAS 10 features se usan al entrenar (entrenar_modelos.py) y al predecir
(Erik.py), por eso vive en un módulo compartido.

Uso rápido:
    python deteccion_red.py       # imprime las features reales de este equipo
"""
from __future__ import annotations

import subprocess
import shutil
from pathlib import Path

import numpy as np

# Orden fijo de las 10 features. NO cambiar el orden sin reentrenar los modelos.
NOMBRES_FEATURES = [
    "total_conexiones",       # nº total de sockets TCP
    "establecidas",           # ESTABLISHED
    "escuchando",             # LISTEN (puertos abiertos escuchando)
    "syn_sent",               # SYN_SENT (intentos salientes -> firma de escaneo/beacon)
    "time_wait",              # TIME_WAIT (conexiones cerrándose)
    "ips_remotas_unicas",     # nº de IPs remotas distintas
    "puertos_remotos_altos",  # conexiones a puerto remoto > 1024
    "puertos_remotos_priv",   # conexiones a puerto remoto <= 1024 (privilegiados)
    "conexiones_externas",    # conexiones a IP no loopback (salen del dispositivo)
    "ratio_ips_por_conexion", # ips_unicas / establecidas -> alto = escaneo/beacon
]

N_FEATURES = len(NOMBRES_FEATURES)

# Estados TCP en /proc/net/tcp (hex).
_PROC_STATES = {
    "01": "ESTAB",
    "02": "SYN_SENT",
    "03": "SYN_RECV",
    "04": "FIN_WAIT1",
    "05": "FIN_WAIT2",
    "06": "TIME_WAIT",
    "07": "CLOSE",
    "08": "CLOSE_WAIT",
    "09": "LAST_ACK",
    "0A": "LISTEN",
    "0B": "CLOSING",
}


def _es_loopback(ip: str) -> bool:
    """True si la IP es de loopback (127.x.x.x o ::1) o vacía."""
    if not ip:
        return True
    return ip.startswith("127.") or ip == "::1" or ip in ("0.0.0.0", "::")


def _conexiones_desde_ss():
    """Devuelve lista de (estado, puerto_local, ip_remota, puerto_remoto) usando `ss`.

    Devuelve None si `ss` no está disponible o falla.
    """
    if not shutil.which("ss"):
        return None
    try:
        salida = subprocess.check_output(
            ["ss", "-tan"], stderr=subprocess.DEVNULL, timeout=10
        ).decode("utf-8", "ignore")
    except Exception:
        return None

    conexiones = []
    for linea in salida.splitlines()[1:]:  # salta cabecera
        partes = linea.split()
        if len(partes) < 5:
            continue
        estado = partes[0].upper().replace("-", "_")
        local = partes[3]
        peer = partes[4]
        pl = _split_addr(local)
        pr = _split_addr(peer)
        if pl is None or pr is None:
            continue
        _, puerto_local = pl
        ip_remota, puerto_remoto = pr
        conexiones.append((estado, puerto_local, ip_remota, puerto_remoto))
    return conexiones


def _split_addr(addr: str):
    """Separa 'IP:PUERTO' (soporta IPv6 con []). Devuelve (ip, puerto) o None."""
    addr = addr.strip()
    if addr.startswith("["):  # IPv6 [::1]:443
        cierre = addr.rfind("]")
        ip = addr[1:cierre]
        resto = addr[cierre + 1:]
        puerto = resto.lstrip(":")
    else:
        idx = addr.rfind(":")
        if idx == -1:
            return None
        ip = addr[:idx]
        puerto = addr[idx + 1:]
    if puerto in ("*", ""):
        puerto_num = 0
    else:
        try:
            puerto_num = int(puerto)
        except ValueError:
            puerto_num = 0
    if ip == "*":
        ip = "0.0.0.0"
    return ip, puerto_num


def _puerto_desde_hex(hex_port: str) -> int:
    try:
        return int(hex_port, 16)
    except ValueError:
        return 0


def _conexiones_desde_proc():
    """Lee /proc/net/tcp y /proc/net/tcp6 (Linux/Android).

    Devuelve lista de (estado, puerto_local, ip_remota, puerto_remoto) o None.
    """
    conexiones = []
    encontrado = False
    for nombre in ("/proc/net/tcp", "/proc/net/tcp6"):
        ruta = Path(nombre)
        if not ruta.exists():
            continue
        encontrado = True
        try:
            texto = ruta.read_text()
        except Exception:
            continue
        for linea in texto.splitlines()[1:]:
            partes = linea.split()
            if len(partes) < 4:
                continue
            local = partes[1]
            rem = partes[2]
            estado_hex = partes[3].upper()
            estado = _PROC_STATES.get(estado_hex, estado_hex)
            if ":" not in local or ":" not in rem:
                continue
            _, lport_hex = local.rsplit(":", 1)
            rip_hex, rport_hex = rem.rsplit(":", 1)
            puerto_local = _puerto_desde_hex(lport_hex)
            puerto_remoto = _puerto_desde_hex(rport_hex)
            # La IP remota queda como identificador hex (basta para unicidad).
            ip_remota = "loop" if rip_hex in ("0100007F", "0" * 32) else rip_hex
            conexiones.append((estado, puerto_local, ip_remota, puerto_remoto))
    return conexiones if encontrado else None


def obtener_conexiones():
    """Obtiene las conexiones TCP reales del sistema por el mejor método disponible."""
    conexiones = _conexiones_desde_ss()
    if conexiones is None:
        conexiones = _conexiones_desde_proc()
    return conexiones  # None si no se pudo en este sistema (p.ej. Windows sin ss)


def features_desde_conexiones(conexiones) -> np.ndarray:
    """Convierte una lista de conexiones en el vector de 10 features."""
    total = len(conexiones)
    establecidas = 0
    escuchando = 0
    syn_sent = 0
    time_wait = 0
    puertos_altos = 0
    puertos_priv = 0
    externas = 0
    ips_unicas = set()

    for estado, _puerto_local, ip_remota, puerto_remoto in conexiones:
        e = estado.upper()
        if e in ("ESTAB", "ESTABLISHED"):
            establecidas += 1
        elif e == "LISTEN":
            escuchando += 1
        elif e in ("SYN_SENT", "SYN-SENT"):
            syn_sent += 1
        elif e in ("TIME_WAIT", "TIME-WAIT"):
            time_wait += 1

        if not _es_loopback(ip_remota) and ip_remota not in ("loop",):
            externas += 1
            if puerto_remoto > 0:
                ips_unicas.add(ip_remota)
        if puerto_remoto > 1024:
            puertos_altos += 1
        elif 0 < puerto_remoto <= 1024:
            puertos_priv += 1

    n_ips = len(ips_unicas)
    ratio = n_ips / establecidas if establecidas > 0 else float(n_ips)

    vector = np.array([
        total,
        establecidas,
        escuchando,
        syn_sent,
        time_wait,
        n_ips,
        puertos_altos,
        puertos_priv,
        externas,
        ratio,
    ], dtype="float32")
    return vector


def extraer_features():
    """Extrae las 10 features reales de la red del dispositivo.

    Devuelve (vector_np[10], dict_legible) o (None, None) si no se pudo leer
    la red en este sistema.
    """
    conexiones = obtener_conexiones()
    if conexiones is None:
        return None, None
    vector = features_desde_conexiones(conexiones)
    legible = {n: float(v) for n, v in zip(NOMBRES_FEATURES, vector)}
    return vector, legible


if __name__ == "__main__":
    vec, legible = extraer_features()
    if vec is None:
        print("No se pudieron leer las conexiones de red en este sistema.")
        print("(En Windows falta `ss`/proc; en Android/Termux debería funcionar.)")
    else:
        print("Features reales de la red actual:")
        for nombre, valor in legible.items():
            print(f"  {nombre:24s} = {valor:g}")
