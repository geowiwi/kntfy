# kntfy

**ntfy.sh notifications for Karoo rides**

kntfy is a lightweight Karoo extension that sends **automatic push notifications via ntfy.sh** when you start or finish a ride.

It is designed to be simple, reliable, and privacy‑friendly, using ntfy topics instead of third‑party messaging platforms.

---

## Key Features

* Automatic notifications on **ride start** and **ride end**
* Fully customizable notification messages
* Optional **Karoo Live Track** link
* Anti‑spam protection with configurable minimum delay
* Supports **public or self‑hosted ntfy servers**
* Languages: English, Spanish

---

## Compatibility

* Karoo 2
* Karoo 3
* Karoo OS **1.524.2003 or later**

An active internet connection is required:

* Karoo 3: via Companion App
* Karoo 2: via Wi‑Fi or mobile hotspot

---

## Setup Overview

1. Create an **ntfy topic** (for example: `my-karoo-rides`)
2. Subscribe to the topic using the ntfy app, web UI, or your own server
3. Open **kntfy** on your Karoo and configure:

    * Topic name
    * Notification events (start / end)
    * Custom messages (optional)
4. Use **Test Notification** to verify delivery

No interaction is required during the ride — notifications are sent automatically in the background.

---

## Message Customization

You can personalize the messages sent for each event.

Supported placeholder:

* `#dist#` → remaining route distance (requires a loaded route)

---

## Known Limitations

* Notifications may be delayed if connectivity is unstable
* Delivery depends on the availability of the ntfy service or your self‑hosted instance

---

## Privacy

* All configuration is stored **locally on the Karoo device**
* Notification content is sent only to the configured ntfy topic
* kntfy has **no affiliation with ntfy.sh**
* Firebase Crashlytics is used solely for crash reporting

---

## Developer

Developed by **geowiwi**
[https://github.com/geowiwi/kntfy](https://github.com/geowiwi/kntfy) based
on [https://github.com/enderthor/kactions](https://github.com/enderthor/kactions)

Built using the **Karoo Extensions Framework** by Hammerhead

---

## Links

* ntfy.sh: [https://ntfy.sh](https://ntfy.sh)
* Karoo Extensions Framework: [https://github.com/hammerheadnav/karoo-ext](https://github.com/hammerheadnav/karoo-ext)
