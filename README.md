# DREAMS Screen 创客实验室屏幕

The big screen in the DREAMS Lab main studio. It runs on its own all day on the MAXHUB:

- **Recess Challenge** in English and Chinese, chosen from a list (no typing).
- **"Snap your build 拍作品"**: kids hold their project up to the MAXHUB camera and take a photo. Photos join the slideshow and delete themselves after 4 weeks.
- **How-to clips**: short silent videos, one after each round of photos.
- **Music** for making time, played from songs copied off a USB stick.
- **Clean-up, automatically, Monday to Friday**: the music stops, the screen shows the 4 clean-up steps and counts down to line-up time, then shows "Line up now!" for 4 minutes, until the half hour.

| Group | Clean-up starts | Line up at |
|---|---|---|
| Grade 2 | 11:52 | 11:56 |
| Grades 3–5 | 12:23 | 12:26 |

Change these in Setup any time.

---

## Install on the MAXHUB (once)

1. On the MAXHUB, open **Chrome** and go to
   **https://github.com/tspraul/dreams-screen/releases/latest/download/dreams-screen.apk**
2. Open the downloaded file and tap **Install**.
3. Open **DREAMS Screen**. The first time someone taps **Snap your build**, tap **Allow** for the camera.

**Updating:** download the same link again and install. Photos, clips, music and settings are kept.

**After the panel restarts**, the app tries to open by itself. If it doesn't, open **DREAMS Screen** from the app list. If the MAXHUB goes to sleep during the day, turn off its sleep/eco timer in the MAXHUB settings.

---

## For the TA: looking after the screen

**Open Setup:** press and hold the **clock** (top left) for 3 seconds. Tap **Done** when finished.

**Change the challenge:** Setup → **1. This week's challenge** → tap one. That's it.

**Change the "Ideas to try":** Setup → under the challenges, **Ideas to try for this challenge** → tap ideas to add or remove them (up to 4). The Chinese is already there. **Use the usual ideas** puts the original ones back.

**Add music, clips or photos from a USB stick:**
1. On a computer or iPad, make a folder called **DREAMS Screen** on the USB stick.
2. Put the files in it:
   - **Songs** (.mp3, .m4a) → music
   - **Videos** (.mp4, .mov) → how-to clips
   - **Pictures** (.jpg, .png) → photos
3. Plug the stick into the MAXHUB. Setup → **3. Copy from USB stick**.
4. The first time, a folder screen opens: tap the stick, tap the **DREAMS Screen** folder, then **Use this folder** (or **Allow**).
5. Wait for "Copied …", then unplug the stick.

**Remove something:** Setup → find it → tap **✕** twice.

**Clean-up screen when the room is empty** (for example, a grade 2 day with no grade 2s): tap **Teacher: hide 隐藏** in the bottom corner. It comes back tomorrow.

**Clean up early:** tap **Clean up now 收拾** on the bottom bar (6-minute countdown).

### Naming how-to clips

Name the video file with one of these and the Chinese title appears on screen. Any other name shows in English only.

Hot glue · Glue gun · Glue · Tape · Masking tape · Ways to join · Slot · Brad · Staple · Stapler · Tie · Scissors · Cutting cardboard · Cardboard cutter · Saw · Drill · Hammer · Sandpaper · Screwdriver · Sewing · Needle and thread · Clean up · Save your project · Bee-Bot · Dash · Robots · Circuits · LED · Coin cell · 3D printing · Strong shapes · Triangles · Wheels

Example: `Hot glue.mp4` shows **How to: Hot glue 热熔胶**.

**Steps beside the video.** The DREAMS Lab's own clips show their steps in English and Chinese next to the video, matched by file name: Hot glue, Cardboard cutter (or Canary cutter), Ways to join, Slot, Brad, Strong shapes, Save your project, Clean up.

Any other video can bring its own steps: put a text file with the **same name** next to it on the USB stick (for example `Robots.mp4` and `Robots.txt`). One step per line, up to 6. Add Chinese after a `|` if you have it:

```
Pick a robot | 选一个机器人
Press the buttons
Try a new path | 试一条新路线
```

A video with no steps plays full screen with just its title.

**Making a clip:** film on the iPad, 15 to 60 seconds, hands only, no talking needed (clips play muted). One skill per clip.

### Music

Instrumental music works best while kids build. The app shuffles every song in the list and keeps the volume you set in Setup → **6. Music**. Music stops by itself at clean-up time.

---

## How it works (for whoever maintains it)

- `app/src/main/assets/index.html` is the whole screen (slideshow, camera, clean-up, setup). Challenges and clip titles are lists at the top of its script; add new ones there.
- `MainActivity.java` shows the page full screen as `https://dreams.local/`, so the camera works, and serves media from the app's own folders at `/media/photos|clips|music/<file>`.
- `Library.java` keeps the three media folders, copies from a USB stick through Android's folder picker and deletes photos older than 28 days.
- `Music.java` plays the music folder, shuffled, with Android's media player.
- Settings (challenge, clean-up times, volume) are saved on the MAXHUB itself.
- Every push to `main` builds a new APK on GitHub and publishes it to the **latest** release. The signing key is in the repo on purpose, so updates install over the old version.
