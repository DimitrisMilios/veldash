# Legal Notice, Intellectual Property & Disclaimers

## 1. Trademark & Copyright Notice (DC Comics / Warner Bros. Discovery)

**Batman**, the **Batmobile**, **Wayne Enterprises**, **Batcave**, **The Joker**, **Catwoman**, **The Riddler**, the **Bat-insignia (Bat-logo)**, and all related character names, vehicle designs, likenesses, and associated marks are registered trademarks and copyrights of **DC Comics, Inc.** and **Warner Bros. Entertainment Inc. (Warner Bros. Discovery)**.

- **Veldash** is an independent, open-source software project created solely for educational, personal research, and non-commercial fan demonstration purposes.
- This project is **not affiliated with, authorized by, sponsored by, or endorsed by** DC Comics, Warner Bros. Discovery, or any of their parent companies, subsidiaries, or affiliates.
- No trademark infringement or copyright infringement is intended. All references to Batman and Gotham-themed elements are used strictly under the principles of non-commercial fan art and nominative tribute.

---

## 2. Non-Commercial & Store Distribution Policy

> [!CAUTION]
> **DO NOT DISTRIBUTE BUILDS CONTAINING BATMAN ASSETS ON COMMERCIAL APP STORES.**
> Submitting this application to the Google Play Store, Amazon Appstore, Apple App Store, or any commercial platform with DC Comics trademarks, Batmobile assets, or themed badges violates platform policies and intellectual property law.

- **No Commercialization**: This project does not sell software, host paid subscriptions, display advertisements, or monetize copyrighted intellectual property in any form.
- **Personal & Fan Use Only**: Builds containing the Batmobile 3D sprites and themed UI badges are strictly intended for private sideloading onto personal aftermarket Android head units.
- **Clean Builds for Third Parties**: If you intend to distribute, repackage, or commercially adapt this navigation application, you **must remove all DC Comics assets and trademarks** and replace them with generic automotive icons and branding (see Section 4).

---

## 3. Third-Party Licenses & Attributions

The Veldash navigation platform integrates and references several open-source technologies, third-party assets, and open datasets:

| Component | Source / Author | License / Terms |
| :--- | :--- | :--- |
| **Batmobile 3D Model (1989)** | Pre-rendered from *"Batmobile Jet Car (1989)"* by [kulonee](https://sketchfab.com/ynesolefru) via [Sketchfab](https://sketchfab.com/3d-models/batmobile-jet-car-1989-2e82d07ed190408e95cdb406c44aefec) | [Creative Commons Attribution 4.0 (CC BY 4.0)](http://creativecommons.org/licenses/by/4.0/) |
| **Map Rendering** | [MapLibre Native Android SDK](https://github.com/maplibre/maplibre-native) | [BSD 2-Clause License](https://github.com/maplibre/maplibre-native/blob/main/LICENSE.md) |
| **Offline Routing** | [BRouter](https://github.com/abrensch/brouter) by Arndt Brenschede | [MIT License](https://github.com/abrensch/brouter/blob/master/LICENSE) |
| **Map Data** | [OpenStreetMap](https://www.openstreetmap.org/) contributors | [Open Database License (ODbL)](https://www.openstreetmap.org/copyright) |
| **Tile Generation** | [Planetiler](https://github.com/onthegomap/planetiler) by OpenMapTiles contributors | [Apache License 2.0](https://github.com/onthegomap/planetiler/blob/main/LICENSE) |
| **Networking** | [OkHttp](https://github.com/square/okhttp) by Square, Inc. | [Apache License 2.0](https://github.com/square/okhttp/blob/master/LICENSE.txt) |
| **TLS Security** | [Conscrypt](https://github.com/google/conscrypt) by Google LLC | [Apache License 2.0](https://github.com/google/conscrypt/blob/master/LICENSE) |

---

## 4. Modular De-Theming Guide (Generic Replacement)

The core navigation engine, vector rendering system, and routing algorithms in Veldash are completely decoupled from the Batman theme. You can convert Veldash into a generic navigation client in minutes:

1. **Vehicle Sprite**:
   - Replace the rendered frames in `app/src/main/assets/car/` with your own rendered vehicle frames, or replace `BatmobileMarker.kt` / `CarSprites.kt` with a standard navigation chevron/arrow canvas drawable.
2. **Waypoints & Badges**:
   - Replace the PNG files in `app/src/main/res/drawable-nodpi/`:
     - `bat_logo.png` -> Standard destination pin marker.
     - `logo_batcave.png`, `logo_catwoman.png`, `logo_joker.png`, `logo_riddler.png`, `logo_wayne.png` -> Generic place category icons (e.g., Home, Work, Gas, Food, Parking).
3. **Strings & Identifiers**:
   - Check `app/src/main/res/values/strings.xml` and replace any thematic strings with standard navigation labels.

---

## 5. Takedown Requests & Contact Information

If you are a copyright or trademark owner (or represent one) and believe that any content in this repository infringes upon your intellectual property rights, please contact the maintainer directly or open an issue on GitHub. 

Any disputed materials will be promptly removed or replaced upon verified notification.

- **Maintainer**: Dimitris Milios
- **Email**: [dimitrismilios1999@gmail.com](mailto:dimitrismilios1999@gmail.com)
- **Repository**: [https://github.com/DimitrisMilios/veldash](https://github.com/DimitrisMilios/veldash)
