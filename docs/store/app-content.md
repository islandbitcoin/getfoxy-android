# App content answers

The answers to give in Play Console (*Policy and programs → App content*) for
Foxy Wallet, each with the fact in the code it rests on. Where the code does
not settle a question, the entry says so and gives the recommended answer.

## Privacy policy

https://github.com/islandbitcoin/getfoxy-android/blob/main/docs/PRIVACY.md

## App access (sign-in details)

**All functionality is available without special access.** Foxy has no account
and no login: the wallet is created on the phone at first launch. No
credentials to give the reviewer. The Flashcard screens need a Foxy NFC card,
and tap to pay needs a second phone; both are optional features, reachable
and explained without them.

## Ads

**No, my app does not contain ads.** No ad SDK is in the build
(`gradle/libs.versions.toml`), and the merged manifest has no `AD_ID`
permission.

## Content rating (IARC questionnaire)

Email: apple@islandbitcoin.com. Category: **All other app types** (a wallet utility). The answers:

| Question area | Answer | Why |
|---|---|---|
| Violence (realistic, fantasy, blood, gore) | No | Illustrations of a fox, a car and snow; nothing violent |
| Fear, horror | No | |
| Sexuality, nudity | No | |
| Language (profanity, crude humour) | No | The app's own text is wallet instructions |
| Controlled substances (drugs, alcohol, tobacco) | No | |
| Discrimination, hate | No | |
| Gambling: simulated gambling | No | |
| Gambling: real-money wagering, or content that encourages it | No | Payments only: no bets, odds, games of chance or prizes |
| Users can interact or exchange content | **Yes** (recommended; see the note) | Two people can pay each other through a payment request delivered over Nostr relays or a Tor onion address, and an invoice or a payment can carry a short note the other person sees. There is no chat, no profile, no feed and no public posting |
| Shares the user's current physical location with other users | No | No location permission; the Bluetooth scan is `neverForLocation` |
| Allows users to purchase digital goods | No | No in-app purchases or billing library; Foxy is free |
| Unrestricted internet access (a browser or search engine) | No | The page has no network and no navigation (SECURITY-INVARIANTS S8, S16); the four GET DOLLARS links open fixed URLs in the system browser |
| Promotes or sells age-restricted products | No | |

Note on *users interact*: the code allows a short note to travel with a
payment between two people, so **Yes** is the accurate answer. It adds the
"Users Interact" notice and does not raise the age rating. Answering **No**
would rest on reading a payment note as part of the payment. This is a human
decision; Yes is recommended.

Expected result: the lowest rating in each region (Everyone / PEGI 3 / USK 0),
with "Users Interact" if answered Yes. The target audience below is 18+
regardless.

## Target audience and content

- Target age groups: **18 and over** only. Foxy moves real money and its
  terms with mints are for adults.
- Could the app unintentionally appeal to children? **No.** The fox art is the
  brand; the app is a bitcoin wallet with a risk warning before first use.
- The app is not in the Designed for Families program.

## Data safety

From docs/RELEASE.md §5, true of the code at this commit:

| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **No** |
| Is all of the user data collected by your app encrypted in transit? | Yes (Tor, and HTTPS outside it) |
| Do you provide a way for users to request that their data is deleted? | Not applicable: the developer holds no data. Uninstalling removes everything on the phone. |

Why: Island Bitcoin runs no server for Foxy and the app sends nothing to the
developer. It contains no analytics, crash reporter, ads, push service,
Firebase or Google Play services library. What the app sends goes, over its own
Tor, to the services the person uses through it: the Cashu mints, Lightning
address and LNURL servers, price and chart hosts, block explorers, Nostr
relays and other phones' onion addresses, and the Tor network. The seed,
wallet, camera frames, Bluetooth, NFC, screen lock and notifications stay on
the phone. docs/PRIVACY.md says the same for the public.

**The fallback (a judgement call, not settled by the code).** Google counts
data transmitted off the device as *collected* even when it goes to a third
party, with exemptions for transfers the user initiates and expects. Invoices
and payment requests sent to a mint or LNURL server the person chose, to make
the payment the person asked for, are the closest thing Foxy has to collected
data. If review or the submitter reads them as collected, declare:
*Financial info → Other financial info*: collected, not shared, processed
ephemerally, required, purpose *App functionality*, not linked to an identity.

## Government apps

**No.** Island Bitcoin LLC is a private company; Foxy is not made for or on
behalf of a government.

## Financial features

Declare the app as a **software (non-custodial) cryptocurrency wallet**, and
under payments, a **digital wallet** if the form lists it. The facts
(docs/RELEASE.md §7):

- Foxy is a software wallet for bitcoin over Lightning and Cashu ecash.
- The seed is generated and kept on the phone and never leaves it. Island
  Bitcoin holds no funds and no keys.
- Cashu ecash is a bearer claim on the mint that issued it: the mint, a third
  party the person chooses, holds the bitcoin behind it. The listing and the
  app's own mint notice say so.
- Exchange: none. No trading, order book, or fiat on-ramp or off-ramp in the
  app. The GET DOLLARS links open Cash App, Strike, River and Flash in the
  system browser; the app has no integration with them.
- Lending, credit, investment products, insurance: none.
- Payments to the developer or in-app purchases: none.

Human decision: Play's form and some countries treat a wallet whose balance a
third-party mint holds differently from a plain self-custody wallet. Which
options to tick, and whether any country listing needs a registration, is the
account owner's call on the current form.

## Health

**No** health features, and no health or fitness data.

## Other declarations, if asked

- News app: No.
- COVID-19 contact tracing or status: No.
- Advertising ID: No (no ads, no Play services, no `AD_ID` permission).

## Category and tags

- Category: **Finance**.
- Tags (pick up to five from Play's list as it stands): *Payments*,
  *Digital wallet* or *Mobile payments* if offered, *Cryptocurrency* or
  *Bitcoin* if offered, *Privacy* if offered. Choose the ones Play's list
  actually contains; avoid tags for exchanges, trading or investing.
