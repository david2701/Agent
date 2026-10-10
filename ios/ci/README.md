# Capturas de iOS en CI

- `mock_server.py`: responde el API de Kairo con los datos anonimizados de `KairoKit/Tests/KairoCoreTests/Fixtures`.
- `screenshots.sh`: instala la app en los simuladores de iPhone y iPad, inicia sesión contra el servidor de prueba
  (sólo en compilaciones Debug, con `KAIRO_AUTOLOGIN`) y toma capturas de cada sección en modo claro y oscuro.

Lo usa el workflow `.github/workflows/ios-capturas.yml`: se lanza a mano desde *Actions > iOS capturas* o al cambiar
esta carpeta. Las imágenes quedan en el artefacto `capturas-ios`.
