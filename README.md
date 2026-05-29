# Yokai

This is my personal fork of Yokai/Mihon/Tachiyomi-style manga reader.

I am mostly using this as a fun experiment. A lot of the recent work here was me using AI to make the app behave closer to what I personally wanted, especially around the Suggestions page.

I am not releasing APKs. If you want to try it, build it yourself.

## What I Changed

The main thing I worked on is the Suggestions page. Both Suggestions V1 and V2 are my additions, with V2 being the newer experiment I have been actively improving.

It is not perfect. There are still flaws, some sources are weird, and suggestions can still be thin or slow sometimes. But for my use case, it gets the job done.

Suggestions page stuff:

* Full custom Suggestions page.
* Suggestions V1 and Suggestions V2 modes.
* New Suggestions V2 page.
* Pull-to-refresh to replace suggestion rows.
* Popular and Latest sorting.
* Tag-based sections based on what you read.
* Better source rotation during refresh.
* Better filtering for manga already in your library or recent history.
* Blacklisted tags are respected when the app can verify the manga tags.
* Larger "View more" sheet for a section.
* Progressive loading so results can appear sooner instead of waiting for every source.

I also made changes to the Recents page, including extra categorizing/grouping changes to make it easier for me to browse what changed recently.

If you use it and something feels broken, repetitive, slow, or just annoying, let me know.

## Build It Yourself

Clone the repo:

```bash
git clone https://github.com/MrKK4/yokai.git
cd yokai
```

Build the dev debug APK:

```bash
./gradlew assembleDevDebug
```

On Windows:

```powershell
.\gradlew.bat assembleDevDebug
```

The APK will be generated here:

```text
app/build/outputs/apk/dev/debug/
```

Requires Android 6.0 or higher.

## Credits

This is based on Yokai, Mihon/Tachiyomi, J2K, and the wider manga reader ecosystem. Credit goes to the original developers and contributors.

## Disclaimer

This app does not host any content. Sources/extensions are separate, and any content comes from those providers.

## License

Apache License 2.0. See [LICENSE](LICENSE).
