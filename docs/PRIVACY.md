# Foxy Wallet privacy policy

Effective date: 2026-10-13

This policy covers Foxy Wallet for Android (package `io.islandbitcoin.getfoxy`),
published by Island Bitcoin LLC. Questions go to apple@islandbitcoin.com.

## The short version

Foxy is a bitcoin wallet for Lightning and Cashu ecash whose seed stays on
your phone. The app talks directly to the services you pay and get paid
through. Island Bitcoin runs no server for Foxy and receives no data from the
app. Foxy works without an account. The app contains no analytics, advertising
or crash-reporting code, and no Google Play services or Firebase library.

## What leaves your phone, and where it goes

Foxy connects to these services to do what you ask of it. Each request goes
over Tor, which Foxy runs inside the app, so the service sees a Tor exit or an
onion connection in place of your IP address.

- **Cashu mints.** The mints your wallet uses: the default mints, and any mint
  you add (Foxy asks you first). A mint receives the ecash requests your wallet
  makes, the Lightning invoices you ask it to pay, and the invoices it creates
  for you to be paid.
- **Lightning address and LNURL servers.** When you pay a Lightning address or
  an LNURL, Foxy asks that server for an invoice (Foxy asks you first). The
  server receives the amount you are paying.
- **Price and chart sources.** A mempool onion service, Kraken, Bitstamp,
  blockchain.info, Gemini and Coinbase, for the bitcoin price and chart. These
  are public price requests, the same for everyone who asks.
- **Block explorers.** The mempool and Blockstream onion services, then
  mempool.space and blockstream.info, for the newest block header. Foxy uses it
  as the clock for the Foxy Flashcard.
- **Nostr relays and onion addresses.** When you pay or are paid through a
  payment request, the payment travels to the other person through Nostr relays
  or through a Tor onion address. Over Nostr the payment is encrypted to the
  other person (NIP-44 and NIP-59 gift wraps), so a relay sees an encrypted
  message. Over an onion address it travels inside Tor from end to end.
- **The Tor network.** Tor relays and directory authorities, and when a direct
  connection to Tor fails, Tor bridges (obfs4) or Snowflake. These carry
  everything above. Snowflake reaches Tor through a content delivery network,
  public STUN servers and a volunteer proxy, which see your IP address and that
  you are using Snowflake, as with any Snowflake user.

Two exceptions leave Tor:

- If Tor cannot connect, Foxy can offer to continue without Tor. This happens
  only when you choose it in a dialog, and it lasts until Tor connects or the
  app is closed. During that time mints, price sources and the other services
  above are reached over HTTPS from your own IP address. Payments to onion
  addresses and over Nostr are sent over Tor only.
- The GET DOLLARS links (Cash App, Strike, River and Flash) open in your phone's
  browser, outside Foxy. Those companies' own privacy policies apply there.

## What stays on your phone

- **Your seed and wallet.** Foxy creates your seed on the phone and seals it
  with a key held in the Android Keystore. The seed and your wallet's data are
  kept in Foxy's private storage on the phone and are excluded from Android's
  cloud backup and device-to-device transfer. Your recovery words are the only
  way to restore the wallet on another phone.
- **Camera.** Foxy uses the camera to scan QR codes. Frames are decoded on the
  phone and discarded right after.
- **Bluetooth.** Tap to pay passes a payment between two phones held close
  together over Bluetooth Low Energy, encrypted between the two phones. It
  works between Android phones and iPhones running Foxy, and only while the
  receive or pay screen asks for it. Foxy listens only for the one identifier
  of the payment in progress.
- **NFC.** Foxy talks to a Foxy Flashcard over NFC while you hold the card to
  the phone during a card session, and opens when a Flashcard is tapped.
- **Screen lock.** The PIN lock and the protection on your seed use Android's
  own biometric or screen-lock prompt. Foxy receives only whether it succeeded.
- **Notifications.** "Payment sent" and "Payment received" notifications are
  created on the phone itself.
- **Clipboard.** When you copy a sensitive value, such as a token, Foxy clears
  the clipboard 120 seconds later.
- **Diagnostic log.** Foxy keeps a log of what it is doing, in memory, with
  ecash tokens, invoices, payment requests, onion addresses and keys replaced
  by a placeholder. A short record of the connection (Tor and the screens) is
  kept on the phone for one relaunch, so a stuck start can be explained. You
  can read the log on the ERRORS and LOGS screens. It leaves the phone only if
  you tap COPY and paste it somewhere yourself.

Uninstalling Foxy deletes all of this from the phone. Write down your recovery
words first if you want to keep the wallet.

## Ecash and mints

Cashu ecash is a bearer claim on the mint that issued it. The mint holds the
bitcoin behind your ecash, and it can see the operations your wallet sends it,
though Cashu's blind signatures keep it from linking the ecash it issues to
the ecash it later redeems. Choose mints you trust with the amounts you hold.
Each mint has its own terms.

## Permissions

| Permission | Why |
|---|---|
| Internet, network state | Tor and the services above; Foxy pauses Tor while the phone has no network |
| Camera | Scanning QR codes, asked the first time you scan |
| Notifications | "Payment sent" and "Payment received", asked the first time one is due |
| Nearby devices (Bluetooth advertise, connect and scan; on Android 11, Bluetooth and Bluetooth admin) | Tap to pay, asked when you turn tap to pay on; the scan is declared `neverForLocation` |
| NFC | The Foxy Flashcard |
| Biometrics | The PIN lock and the seed's protection, through Android's own prompt |
| Detect screen capture | Android 14 and later tell Foxy when a screenshot is taken while it is open; Foxy's screens are protected from capture, so the screenshot is blank |

These are all the permissions Foxy asks for.

## Children

Foxy is for people 18 and over. It is a wallet for real money, made for
adults.

## Changes

When this policy changes, the new version is published at this address with a
new effective date, and the history of every change is public in this
repository.

## Contact

Island Bitcoin LLC
apple@islandbitcoin.com
https://getfoxy.io
