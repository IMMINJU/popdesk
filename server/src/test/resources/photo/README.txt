Test images of server/src/test/java/io/popdesk/photo. PNG and JPEG are made by the test code
(PhotoSteps); the JDK cannot write WebP, so these files were made once with Pillow 12.3 and are
not taken from anywhere else.

lossy-640x480.webp           a colour gradient, lossy, quality 70
lossless-alpha-300x600.webp  the same gradient with an alpha checkerboard, lossless
exif-320x240.webp            the gradient with an EXIF chunk: ImageDescription
                             "popdesk-exif-marker-webp", Orientation 1
solid-4000x3000.webp         one colour, 12,000,000 pixels: the largest WebP the app takes
solid-4001x3000.webp         one colour, 12,003,000 pixels: over the WebP limit

Made with:
  from PIL import Image
  def gradient(w, h, alpha=False):
      im = Image.new("RGBA" if alpha else "RGB", (w, h))
      px = im.load()
      for y in range(h):
          for x in range(w):
              c = (x * 255 // (w - 1), y * 255 // (h - 1), (x + y) * 255 // (w + h - 2))
              px[x, y] = c + ((255 if (x // 40 + y // 40) % 2 else 96),) if alpha else c
      return im
  gradient(640, 480).save("lossy-640x480.webp", "WEBP", quality=70)
  gradient(300, 600, alpha=True).save("lossless-alpha-300x600.webp", "WEBP", lossless=True)
  exif = Image.Exif(); exif[0x010E] = "popdesk-exif-marker-webp"; exif[0x0112] = 1
  gradient(320, 240).save("exif-320x240.webp", "WEBP", quality=70, exif=exif.tobytes())
  Image.new("RGB", (4000, 3000), (40, 120, 90)).save("solid-4000x3000.webp", "WEBP", quality=50)
  Image.new("RGB", (4001, 3000), (40, 120, 90)).save("solid-4001x3000.webp", "WEBP", quality=50)
