# KindlePost

Android app for sending compressed photos to a Kindle personal-document email address.

## Features
- Select up to 20 photos per email using Android Photo Picker.
- Correct EXIF orientation and resize large photos locally before JPEG compression.
- Keep the total compressed attachments within 18 MB per email.
- Configure QQ sender mailbox, SMTP authorization code, and Kindle recipient address.
- Display original and compressed attachment sizes.
- Store mailbox settings locally using Android encrypted preferences.

## Build and release
GitHub Actions builds a signed release APK on pushes to `main` and publishes it as the `continuous` prerelease. Pushing a `v*` tag creates a versioned GitHub Release.

Add these repository Actions secrets before the workflow can build:
- `KEYSTORE_BASE64`: Base64 text from the supplied signing kit's `KEYSTORE_BASE64.txt`
- `KEYSTORE_PASSWORD`: keystore password
- `KEY_ALIAS`: signing key alias
- `KEY_PASSWORD`: private-key password

Keep the signing kit and its backup offline and private. Never commit signing files or credentials.

## One-time update from the initial test build
The first KindlePost test APK used an ephemeral Android debug certificate. It cannot be updated in place by the new persistent release certificate. Uninstall the old test APK once, then install the first signed release. Re-enter the mailbox settings after reinstalling. All future releases signed with the persistent key can update in place.

## Mail delivery
The sender address must be added to the approved personal document e-mail list in the Amazon account associated with the Kindle address. QQ Mail SMTP uses an app-specific authorization code, not the account password.

## Privacy
Photos are processed locally and attached directly to the email. No photo upload service or analytics is included.

## In-app updates
The app checks the public `version.json` manifest on the `main` branch. When a newer version is available, it downloads the APK from the `continuous` GitHub Release. The user must approve Android's package installation prompt. The APK is signed with the same persistent release key, so future versions can update in place.
