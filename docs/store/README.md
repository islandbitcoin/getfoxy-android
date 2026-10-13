# The Play listing

What goes where in Play Console for Foxy Wallet (`io.islandbitcoin.getfoxy`,
ENG-680). Every text here is checked against the code at the commit that adds
it (docs/RELEASE.md §5 to §7, docs/ROUTE.md §3). Change the code, check these.

## Grow users → Store presence → Main store listing (English, United States)

| Field | File | Limit |
|---|---|---|
| App name | `en-US/title.txt` | 30 characters |
| Short description | `en-US/short_description.txt` | 80 characters |
| Full description | `en-US/full_description.txt` | 4,000 characters, plain text |
| App icon | `graphics/icon-512.png` | 512 x 512, 32-bit PNG; Play rounds the corners |
| Feature graphic | `graphics/feature-graphic-1024x500.png` | 1024 x 500, PNG without alpha |
| Phone screenshots | `screenshots/phone-01-*.png` to `phone-08-*.png`, in order | 2 to 8; 1080 x 1920 (9:16) |

Paste the text files without their final newline. Leave the video, the tablet
and the Chromebook screenshots empty.

## Store settings

| Field | Value |
|---|---|
| App category | Finance (`app-content.md`, "Category and tags") |
| Tags | see `app-content.md` |
| Email | apple@islandbitcoin.com |
| Website | https://getfoxy.io |
| Phone | leave empty |
| External marketing | the account owner's choice |

## Policy and programs → App content

Every answer, with its reason in the code, is in `app-content.md`.

| Section | Answer in short |
|---|---|
| Privacy policy | https://github.com/islandbitcoin/getfoxy-android/blob/main/docs/PRIVACY.md (`docs/PRIVACY.md`) |
| App access | All functionality is available without special access |
| Ads | No ads |
| Content rating | IARC questionnaire, answers in `app-content.md` |
| Target audience | 18 and over |
| Data safety | No data collected or shared, with the note in `app-content.md` |
| Government apps | No |
| Financial features | Software wallet (self-custodial keys, mint-held ecash): see `app-content.md` |
| Health | No |

## How the files were made

- `graphics/`: `python3 tools/make-art.py --store <getfoxy/iOS clone>` cuts the
  icon from the iOS app icon and the feature graphic's fox from the intro's
  last frame, both at the commit in `core/PIN` (read with `git show`, so the
  clone's working tree is untouched), and sets the name in Inter Display
  SemiBold (SIL Open Font License) on the page's background colour.
  `app/art/NOTICE.md` has the sources' licence.
- `screenshots/`: a debug build on an emulator with the `FoxyAllowCapture`
  extra (docs/DEVICE-TESTS.md "Store screenshots"). A fresh wallet with a zero
  balance; the invoice on `phone-03` is a quote from the default mint that was
  never paid. The Tor badge and the price are live.

## Before rollout

- `docs/PRIVACY.md` must be on `main` for its URL to resolve.
- Re-read the description against the build being released: it describes
  0.1.x, minSdk 30.
