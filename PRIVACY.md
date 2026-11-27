# Privacy

Scribella has no account, no advertising and no analytics of its own. The person who makes Scribella receives nothing from your device.

## Your notebooks

Your notebooks, recordings and settings are kept in the app's private storage on your device. They leave it only when you export or share them, or when you set up sync or backups with a folder or a WebDAV server you choose, or Instant sync with a server of your own. They then go to that place and nowhere else.

## What goes to Google

Scribella reads handwriting and printed text with Google's ML Kit library. The reading happens on your device, and ML Kit doesn't send your ink, images or the recognised text to Google.

ML Kit does send Google usage and performance figures, over an encrypted connection:

- device model, Android version and available machine-learning hardware
- this app's name and version
- an identifier for the installation or device
- the handwriting languages you set up
- how long recognition took, the image format and resolution, input and output sizes
- what happened (for example start, model download, recognition) and any error codes

Google uses this for diagnostics and to measure and improve ML Kit, as described in its [ML Kit terms](https://developers.google.com/ml-kit/terms), its [data disclosure for ML Kit](https://developers.google.com/ml-kit/android-data-disclosure) and its [privacy policy](https://policies.google.com/privacy). ML Kit also contacts Google for updates, and to download a handwriting language when you ask for one. Scribella can't turn these figures off.

Where your device has Google Play services, Scan can use Google's document scanner, and Instant sync can read a sync code's QR code with Google's code scanner. Both run on your device inside Google Play services, and Scribella receives only the finished pages or the code. Like ML Kit above, they may send Google usage and performance figures.

## Instant sync

Instant sync is off until you set it up. Each change is encrypted on your device with the key in your sync code, sent to a small server you run on your own Cloudflare account, and passed from there to your other devices. The server and Cloudflare see only encrypted changes, their sizes and times, and your network address, never your notes; Cloudflare handles that under its [privacy policy](https://www.cloudflare.com/privacypolicy/). Keep your sync code private: anyone who has it can read and change the notebooks you sync.

## Checking for updates

Settings → Privacy & storage → Check for updates asks GitHub for this project's list of released versions when you tap it. If you turn on the automatic check (off until you do, in the same place or in the What's new note), the app makes the same request by itself, at most once a day, when you open the Library. Nothing about you or your notebooks is sent with it. GitHub sees the request and your network address, as any website would, under its [privacy statement](https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement). A new version opens in your browser; the app never downloads or installs anything by itself.

## Permissions

| Permission | Why |
| --- | --- |
| Microphone | Recording audio into a notebook, when you start a recording |
| Camera | Scanning paper, when you start a scan |
| Biometrics | The optional app lock |
| Internet and network state | Sync and Instant sync you set up, language downloads you ask for, update checks you ask for or turn on, and ML Kit as described above |
| Run after restart | Keeping sync you set up going after the device restarts |
| Keep awake and foreground service | Android's background-work library asks for these so that sync can finish; Instant sync uses a foreground service to receive changes in the background, when you turn that on |
| Notifications | The notice Android shows while Instant sync receives in the background |

## Questions

Open an issue on this project's GitHub page. Please don't post anything private there.
