# KindlePost

Android app for sending compressed photos to a Kindle personal-document email address.

## MVP
- Select photos only (Android Photo Picker; multi-select).
- Compress images locally to JPEG before sending.
- Configure a QQ sender mailbox, SMTP authorization code, and Kindle recipient address.
- Show original/compressed sizes and send result.
- Keep mailbox settings on-device; never commit credentials.

## Build
GitHub Actions builds a debug APK on pushes to `main` and manual dispatch.

## Mail delivery
The sender address must be added to the approved personal document e-mail list in the Amazon account associated with the Kindle address. QQ Mail SMTP uses an app-specific authorization code, not the account password.

## Privacy
Photos are processed locally and attached directly to the email. No photo upload service or analytics is included.
