"""OBSOLETO. Este script generaba modelos con datos ALEATORIOS (sin sentido).

Ahora los modelos se entrenan con el espacio de features REALES de la red del
dispositivo. Usa en su lugar:

    python entrenar_modelos.py

Se deja este archivo solo para no romper referencias. No sobrescribe nada.
"""
import sys

if __name__ == "__main__":
    print(__doc__)
    print("Ejecuta:  python entrenar_modelos.py")
    sys.exit(0)
