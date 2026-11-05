# Privacy

Scribella has no account, no advertising and no analytics of its own. The person who makes Scribella receives nothing from your device.

## Your notebooks

Your notebooks, recordings and settings are kept in the app's private storage on your device. They leave it only when you export or share them, or when you set up sync or backups with a folder or a WebDAV server you choose. They then go to that place and nowhere else.

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

## Checking for updates

Settings → Privacy & storage → Check for updates asks GitHub for this project's list of released versions, only when you tap it. Nothing about you or your notebooks is sent with it. GitHub sees the request and your network address, as any website would, under its [privacy statement](https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement). A new version opens in your browser; the app never downloads or installs anything by itself.

## Permissions

| Permission | Why |
| --- | --- |
| Microphone | Recording audio into a notebook, when you start a recording |
| Camera | Scanning paper, when you start a scan |
| Biometrics | The optional app lock |
| Internet and network state | Sync you set up, language downloads you ask for, update checks you ask for, and ML Kit as described above |
| Run after restart | Keeping sync you set up going after the device restarts |
| Keep awake and foreground service | Android's background-work library asks for these so that sync can finish |

## Questions

Open an issue on this project's GitHub page. Please don't post anything private there.
