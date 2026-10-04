import serial, time, sys
s = serial.Serial('/dev/ttyUSB0', 115200, timeout=0.2)
# Forzar modo RUN al abrir: si no, el toggle por defecto de DTR/RTS puede dejar
# al ESP32 en modo download (GPIO0 bajo) y el firmware nunca arranca (serial mudo).
# EN=RTS, GPIO0=DTR: GPIO0 alto (run) + pulso de reset en EN.
s.setDTR(False)
s.setRTS(True)
time.sleep(0.1)
s.setRTS(False)
time.sleep(0.3)
try:
    while True:
        b = s.read(512)
        if b:
            sys.stdout.write(b.decode('utf-8', errors='replace'))
            sys.stdout.flush()
except KeyboardInterrupt:
    pass
