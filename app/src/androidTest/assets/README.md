# Тестовое локальное видео

`local-video.mp4` — синтетический 12-секундный H.264/AAC MP4 (640×360, 24 fps).
Используется для проверки platform decoding через MediaStore `content://`,
seek, завершения playback и освобождения сессии. Личных медиа в fixture нет.
Тесты создают собственную запись MediaStore и удаляют её после проверки.

Команда повторной генерации fixture (FFmpeg с encoder `libx264`):

```bash
ffmpeg -f lavfi -i 'testsrc2=size=640x360:rate=24' \
  -f lavfi -i 'sine=frequency=440:sample_rate=44100' \
  -t 12 -c:v libx264 -pix_fmt yuv420p -crf 30 \
  -c:a aac -af volume=0.02 -movflags +faststart local-video.mp4
```
