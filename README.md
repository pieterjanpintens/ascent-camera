# Ascent Camera

A small Android app that turns a phone into a table camera for [Ascent](https://github.com/pieterjanpintens/descent-engine).
The phone sits in a holder behind the table with its screen off. When Ascent wants to know where the heroes are,
it asks the phone for one photo: the camera switches on, takes the picture, sends it and switches off again.

No streaming, no tapping on the phone, and nearly no battery use between photos.

## Using it

1. Install the APK. Android will ask you to allow installing apps from this source once.
2. Open **Ascent Camera** and press **Start the camera**. Allow the camera and notifications.
3. Press **Allow running with the screen off**. On Samsung phones also set the app's battery use to **Unrestricted**
   (Settings > Apps > Ascent Camera > Battery), or the phone may put it to sleep.
4. **Take a test photo** to check the framing.
5. Type the **address** and **pairing code** into Ascent, lock the phone and put it in its holder.

The phone and the PC must be on the same network.

## How it works

- `CameraService`: an Android foreground service of type *camera* (with a notification), so it may use the camera while
  the screen is off. It must be started from the app's screen. It holds a partial wake lock so the network listener keeps
  answering.
- `PhotoServer`: a tiny HTTP server on port 8765.
- `PhotoTaker`: opens the back camera (CameraX) only for the photo and closes it right after.

## Protocol (version 1)

Plain HTTP on port 8765, one request per connection, `GET` only.

| Request | Needs the code | Answer |
|---|---|---|
| `GET /hello` | no | `{"app":"ascent-camera","protocol":1}`, to recognise the app |
| `GET /status` | yes | `{"app":"ascent-camera","protocol":1,"version":"0.1.0","device":"..."}` |
| `GET /photo` | yes | the photo as `image/jpeg` |

- The pairing code goes in the header `X-Key: 123456`. A wrong or missing code gets `401` after a short delay.
- `/photo` answers with the header `X-Rotation: 0|90|180|270`: turn the picture that many degrees clockwise to see it as
  the phone is held in portrait. Use this header, not the JPEG's EXIF data.
- `503` = a photo is already being taken; `500` = the camera failed (the reason is in the body).
- A photo takes about 1 to 3 seconds; use a generous timeout (30 s).

## Building

Open the folder in Android Studio, or run `gradlew assembleDebug`. Needs JDK 17 or newer.

## License

MIT, see [LICENSE](LICENSE).
