# 📱 AppCoder: Turn Your App Ideas into Reality – Right On Your Phone!

**AppCoder** is a mobile Android app that empowers you to build your own apps directly on your phone — no programming experience required! You only need to describe your app and it will be build automatically.

---

## 🚀 Get Started with the MVP Release!

**The AppCoder MVP (Minimum Viable Product) is ready for you to test!**

1.  **⬇️ Download the APK:**
    *   Go to the latest release: **[https://github.com/ChristophGeske/AppCoder/releases/tag/v1.0.0](https://github.com/ChristophGeske/AppCoder/releases/tag/v1.0.0)**
    *   Download `app-release.apk` (or `app-arm64-v8a-release.apk` if you prefer and know it's for your device).

2.  **🔑 Get Your Free Gemini API Key:**
    *   AppCoder needs a Gemini API key to generate code.
    *   Visit Google AI Studio: [https://aistudio.google.com/apikey](https://aistudio.google.com/apikey)
    *   Sign in and click "Create API key". Copy this key.

3.  **📲 Install & Use AppCoder:**
    *   Transfer the downloaded `.apk` file to your Android phone.
    *   On your phone, open a file manager, find the `.apk`, and tap to install.
        *   👉 You might need to **enable "Install from unknown sources"** in your phone's settings.
    *   After AppCoder installs and completes its initial setup (grant permissions, wait for components):
        *   Tap the **🔵 blue plus (+) button**.
        *   Enter an **App Name**, a **simple App Description** (keep ideas straightforward for this MVP!), and paste your **Gemini API Key**.
        *   Tap "Continue."
    *   After code generation by the LLM (this can take a moment, especially on the first app build), you'll be prompted to **build** your new app.
    *   When installing your *generated app*, you might need to **expand the installation prompt to find and select "Install anyway"**.

---

## 💡 What is AppCoder?

AppCoder transforms your plain English descriptions into working app code using advanced LLMs like **Google's Gemini 2.5 Flash Preview**. [No change, keeping your specified model]

Simply describe your app vision, and AppCoder handles the code generation.

**Who is AppCoder for?**
*   **Non-programmers:** Create apps without technical barriers.
*   **Experienced Developers:** Rapidly prototype ideas.

Export the generated code to continue development on a desktop with professional tools.

---

## ⚠️ Important Notes & Known Issues (MVP v1.0.0)

*   **Simple App Ideas Work Best:** For this MVP, if the LLM generates non-working code (e.g., from a complex description), you must build a new app with a **new app name** (overwriting isn't implemented). Try simplifying your initial idea if it fails.
*   **AppCoder's Initial Setup:**
    *   The first run of AppCoder installs necessary components. This can occasionally fail. **Reinstalling the AppCoder `.apk` usually fixes this.**
    *   During this initial setup, **use only on-screen buttons** (not system back/home buttons) to avoid getting stuck. If stuck, reinstall AppCoder. [Bug fix is planned 🛠️]
*   **Gemini API Key Required:** You must enter your key for AppCoder to function.

---

## 🛠️ For Developers: Building AppCoder from Source

This section is for those who want to compile AppCoder from its source code. **If you just downloaded the APK, you can skip this.**

1.  **Clone this repository.**
2.  **Connect your Android smartphone** to your computer.
3.  **Build and install the AppCoder APK** onto your phone using **Android Studio Meerkat 2024.3.2**. [No change to IDE name]
4.  **Development Setup Notes:**
    *   **Line Endings:** Use **LF** (not CRLF). (This issue should be fixed with the last commit via `.gitattributes`)
    *   **Gradle JDK:** JDK 21.
5.  **Troubleshooting Builds from Source:**
    *   **Gradle build fails in Android Studio:** Try running the build process again.
    *   **Build of the *generated app* (by AppCoder) on the phone fails (during its terminal run):** Uninstall the partially built app and restart the build process within AppCoder.

---
## ✨ Alternative Terms / Inspiration

*   App Maker
*   Vibe Coding
*   Cursor / Windsurf for Android

---

## 📜 License

```
AppCoder is based on AndroidIDE which is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

AndroidIDE is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
```
