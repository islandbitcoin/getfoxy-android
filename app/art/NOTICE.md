# The app's art

The launcher icon, the splash icon, the notification icon and the Play Store
icon are cut from the art of [getfoxy/iOS](https://github.com/getfoxy/iOS) at
the commit in `core/PIN` (`e35eacf7f9941f9a00b611d0a580d08d6defb426`), by
`tools/make-art.py`. Reusing it for Android was the person's decision of
2026-10-12 (ENG-665). `ArtTest` checks every file below.

| Source at that commit | sha256 | Cut into |
|---|---|---|
| `Foxy/Assets.xcassets/AppIcon.appiconset/foxy app icon.png` (1024 x 1024, a white fox head on black) | `582d6c1773a596e7eebab0d6bd39bae8bfa90ff76f07632a635b7a18740e4e26` | `app/src/main/res/mipmap-*/ic_launcher_foreground.png` (the adaptive icon's foreground and its monochrome layer: white, as opaque as the art is bright, 64 dp of the 108 dp canvas), `app/src/main/res/drawable-*/ic_notify.png` (the head, white on clear, 22 dp of 24 dp), `app/src/main/ic_launcher-playstore.png` (512 x 512) |
| `Web/foxy-splash-still.png` (600 x 1304, the intro's first frame; also `core/web/foxy-splash-still.png`, byte for byte) | `d21b711786d81aa20cb79b3aa823178230eb66b71fceb7478da12c6af6626144` | `app/src/main/res/drawable-nodpi/splash_fox.png` (the 600 x 600 square around the sleeping fox, pixel for pixel); the full-screen splash after it is the still itself, read from the page's assets |

The iOS repository's `Web/VENDOR.md` records the splash still's origin as not
recorded; the app icon has no entry there. Both are shipped by that
repository under its licence, which follows (the same text as
`core/UPSTREAM-LICENSE`, sha256
`832f93931e17bd1ae421ed20303088e1a700aedfe9d47c352278083f2ef1b736`).

```
MIT License

Copyright (c) 2026 getfoxy

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
