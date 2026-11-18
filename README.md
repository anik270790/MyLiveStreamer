# MyLiveStreamer

Free, open-source Android app that streams your phone screen live to several
platforms at once (YouTube, Facebook, Instagram, TikTok, Twitch, or any RTMP address).
No server and no domain needed: the phone sends the stream straight to each platform.

Built on [RootEncoder](https://github.com/pedroSG94/RootEncoder) (Apache-2.0).

## Features
- Screen capture, one encode sent to 1 or more platforms at the same time
- Resolution 480p to 4K, 30/60 fps, automatic bitrate lowering when internet is weak
- Audio: microphone, phone sound (Android 10+), or both; live mute and live switching
- Front camera overlay you can switch on/off any time
- Text overlay, logo overlay, live chat window on screen, live stats
- Floating control panel and notification buttons
- Interface in English and Bangla, plus any other language (translated on the phone with ML Kit)
- Stream keys are stored only in the app's private storage on your phone

## Get the APK (no computer tools needed)
1. Create a free GitHub account and a new repository (Public or Private).
2. Upload everything in this folder to it (or use `git push`).
3. Open the **Actions** tab, run **Build APK** (it also runs on every push).
4. When it finishes (about 5-10 minutes), open the run and download **MyLiveStreamer-apk**.
5. Unzip, copy the APK to your phone and install it (allow "install unknown apps").

Every build is signed with the same key (`app/mls-release.jks`), so a new APK installs over
the old one and keeps your settings. If you make the repo public, anyone could sign an update
with that key; for personal use this is fine, or make the repo Private.

## How to use
1. Add a platform, press **Get key** to open its live page in your browser, copy the stream key,
   then press **Paste key**. (YouTube and Facebook server URLs are filled in for you.)
2. Choose quality, audio and overlays.
3. Press **START LIVE**, allow the permissions, and allow screen capture.
4. Use the floating panel or the notification buttons to control the stream.

## Known limits
- The floating panel and chat window are part of the screen, so they appear in the stream.
  Turn the panel off and use the notification buttons for a clean stream.
- All platforms receive the same quality. Each extra platform multiplies your upload usage.
- Instagram and TikTok only give stream keys to some accounts. Threads has no RTMP streaming.
- Phone sound capture needs Android 10+. Some apps block it.
- Camera overlay rotation may need the "Camera rotation fix" setting changed once.

## License
MIT for this app's code. RootEncoder is Apache-2.0.
