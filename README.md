<p align="center">
  <img src="media/readme/owpengram_splash.png" alt="OwpenGram" width="440">
</p>

# 🤖 OwpenGram for Android

**One familiar app. Any server you trust.**

OwpenGram for Android is a multi-server messenger built on a fast, familiar
experience. Use the official network, your own private server, or any community
node — each account independent, all in one app. Private by design, comfortable
to use, and free from lock-in.

> 🤖 **Available now for Android.** iOS and a web client are planned.

> 🔗 Built on **MTProto API layer 229**.

<p align="center">
  <img src="media/readme/android_hero.png" alt="OwpenGram for Android — chats and calls" width="820">
</p>

---

## ✨ Why you'll like it

- 🌐 **Multi-server** — add accounts on different servers and switch between them freely.
- 🏠 **Bring your own server** — connect to a server you host and fully control.
- 🧠 **Familiar & comfortable** — the experience you already know, no learning curve.
- 🔒 **Private** — talk on infrastructure you trust, away from the cloud.
- 🛡️ **Censorship-resistant** — your own server stays reachable when others are blocked.
- 🆓 **Open source** — read it, audit it, build it yourself.

## 🌐 How multi-server works

Every account is tied to a server, and you choose that server when you sign in.
OwpenGram comes with ready-to-use options:

- **Telegram** — the official network (use your normal Telegram account)
- **OwpenGram** — the project's public server
- **Custom** — any server you or your community runs

Add several accounts on different servers and they stay cleanly separated —
different identities, different data, one app.

<p align="center">
  <img src="media/readme/android_multiserver.png" alt="Choose a server, and accounts grouped by server" width="620">
</p>

## 🔌 Connect your own server

On the **server selection screen** (shown when you log in or add a new account),
tap **➕ Add server** and fill in:

- **Name** — any label you like (e.g. *My Server*)
- **Host / IP address** — your server's IP or domain (e.g. `203.0.113.10` or `chat.example.com`)
- **Port** — `2398` (the default OwpenGram MTProto port)
- **Multi-DC mode** — leave **OFF** for a self-hosted server (turn ON only for true multi-datacenter, Telegram-style networks)
- **Main data center** — leave as `2` (the default) for a self-hosted server
- **RSA Public Key (PEM)** — leave **empty** unless your server uses a custom key

Then save, select the server, and log in as usual.

> The default OwpenGram server key is already built in, so the RSA field stays
> blank in almost all cases. Only paste a PEM public key if the server operator
> replaced the server's key with their own.

Don't have a server yet? Spin one up in one command:
👉 [owpengram-server](https://github.com/owpengram/owpengram-server)

## 🛠️ Build (Windows)

Run the interactive build script — double-click it or run from a terminal:

```bat
build-android.bat
```

It guides you through API credentials, the keystore, SDK setup and the Gradle
build, and remembers your answers in `.owpengram-build.local.json` (gitignored).

**Requirements:** JDK 17, Android SDK (API 35, build-tools 35.0.0,
NDK 21.4.7075529), Git.

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
