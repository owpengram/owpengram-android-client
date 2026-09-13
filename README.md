<p align="center">
  <img src="media/readme/owpengram_splash.png" alt="OwpenGram" width="440">
</p>

# 🤖 OwpenGram for Android

**One familiar app. Any server you trust.**

OwpenGram for Android is a multi-server messenger built on a fast, familiar
experience. Use the official network, your own private server, or any community
node — each account independent, all in one app. Private by design, comfortable
to use, and free from lock-in.

> 🤖 **Available now for Android** — grab the APK from the
> [Releases](https://github.com/owpengram/owpengram-android-client/releases)
> tab. iOS and a web client are planned.

> 🔗 Built on **MTProto API layer 229**, on top of Telegram for Android `v12.10.1`.

<p align="center">
  <img src="media/readme/android_hero.png" alt="OwpenGram for Android — chats and calls" width="820">
</p>

---

## 📥 Download

The APK lives in the
[Releases](https://github.com/owpengram/owpengram-android-client/releases) tab —
download, allow install from your browser, open it. One universal APK covers
all four ABIs (`armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`), and it runs on
**Android 5.0+**.

It is the *unrestricted* build, the direct-download flavour rather than the
Play-Store one: no Google Play restrictions, no Firebase dependency, and push
delivered by the client's own keep-alive connection instead of a cloud service.
It shares its application id and signature with the Play build, so installing it
over an existing OwpenGram is an in-place update — your chats stay where they
are.

## 🚀 Try it without setting up anything

We run a **public OwpenGram server**, and it is already built into this client —
no address to type, no key to paste, no Docker. Install the APK, and on the
server-selection screen at login tap **OwpenGram** instead of Telegram, then
sign in as usual.

That account sits alongside your Telegram one, so you can try the project
without leaving anything behind. When you want your own server later, you add
it in the same app and keep both.

## ✨ Why you'll like it

- 🌐 **Multi-server** — add accounts on different servers and switch between them freely.
- 🏠 **Bring your own server** — connect to a server you host and fully control.
- 🔎 **Adding one takes an address** — type `host:port`, the app fetches the server's key, DC and identity itself.
- 🧠 **Familiar & comfortable** — the experience you already know, no learning curve.
- 🔒 **Private** — talk on infrastructure you trust, away from the cloud.
- 🛡️ **Censorship-resistant** — your own server stays reachable when others are blocked.
- 🆓 **Open source** — read it, audit it, build it yourself.

## 🌐 How multi-server works

Every account is tied to a server, and you choose that server when you sign in.
OwpenGram comes with ready-to-use options:

- **Telegram** — the official network (use your normal Telegram account)
- **OwpenGram** — our public server, live and ready to use, already in the list
- **Custom** — any server you or your community runs

Add several accounts on different servers and they stay cleanly separated —
different identities, different data, one app. Separation is real, not cosmetic:
each account's cached peers, files and ids are scoped to the server they came
from, so two servers that happen to hand out the same numeric id never bleed
into each other. Official Telegram accounts still count against Telegram's own
limit (3, or 5 with Premium); self-hosted ones aren't subject to that — the only
ceiling is the app's own **50 accounts in total**, across every server combined.

Remove a server and every account on it goes with it — no orphaned logins left
behind pointing at a host that no longer exists.

<p align="center">
  <img src="media/readme/android_multiserver.png" alt="Choose a server, and accounts grouped by server" width="620">
</p>

## 🔌 Connect your own server

On the **server selection screen** (shown when you log in or add a new account),
tap **➕ Add server** and type the address — `chat.example.com:2398`, or
`203.0.113.10:2398`. That is the only field you have to fill in.

The app then asks the server who it is (`/owpengram/server-info` on that same
port) and fills in the rest by itself: RSA public key, data-centre id, and the
name, description and icon the server's operator set. Rename it or swap the icon
if you like — those are yours to edit. Save, pick the server, log in as usual.

Everything it fetched is still editable under **Advanced** — multi-DC mode, DC
id, RSA key — for a server that doesn't answer that endpoint, or when you want
to pin the key yourself.

> Only `host:port` is ever taken from a link or typed in. The identity a server
> claims is fetched from that address directly, so nobody can hand you a link
> that misrepresents whose server you're about to trust.

Operators can hand out a ready-made **`owpg://addserver?host=...&port=...`**
link: opening it pops the Add server screen pre-filled with the address. By
design it carries nothing else — no key, name or DC.

Server name, description and icon refresh on their own whenever a server list is
shown, so a rebrand on the operator's side appears without you re-adding
anything. Only those cosmetics — host, port, key and DC id are never touched
after you've saved them.

Don't have a server yet? Spin one up in one command:
👉 [owpengram-server](https://github.com/owpengram/owpengram-server)

## 🛠️ Build (Windows)

Run the interactive build script — double-click it or run from a terminal:

```bat
build-android.bat
```

It guides you through API credentials, the keystore, SDK setup and the Gradle
build, and remembers your answers in `.owpengram-build.local.json` (gitignored).
It builds the `:TMessagesProj_AppStandalone` flavour — the unrestricted,
Firebase-free one that ships in Releases.

**Requirements:** JDK 17+, Android SDK (compile/target API 36, build-tools
36.0.0, NDK 27.2.12479018), Git. Minimum supported device API is 21
(Android 5.0).

## 📦 Part of the OwpenGram project

- 🚀 [Server](https://github.com/owpengram/owpengram-server)
- 💻 [Desktop client](https://github.com/owpengram/owpengram-desktop-client)
- 🌐 [GitHub organization](https://github.com/owpengram)

## 💬 Community

- 📢 Channel: [@owpengram](https://t.me/owpengram)
- 💬 Chat: [Join the discussion](https://t.me/+sVB6Ymv70jEwNTAy)

## 📄 License

Based on [Telegram for Android](https://github.com/DrKLO/Telegram) — licensed
under **GNU GPL v2 or later** ([LICENSE](LICENSE)).

---

⭐ If OwpenGram is useful to you, a star on GitHub helps a lot.
