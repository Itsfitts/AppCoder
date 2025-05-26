# 📱 AppCoder: Turn Your App Ideas into Reality – Right On Your Phone!

**AppCoder** is a mobile Android app that empowers you to build your own apps directly on your phone — no programming experience required! You only need to describe your app and it will be build automatically.

---

## 🚀 Get Started with the First Release!

**The First AppCoder Version is ready for you to test!**

1.  **⬇️ Download the APK:**
    *   Go to the **[latest release page](https://github.com/ChristophGeske/AppCoder/releases)**
    *   Download `app-arm64-v8a-release.apk` or another .apk according to your device type.

2.  **🔑 Get Your Free Gemini API Key:**
    *   AppCoder needs a Gemini API key to generate code automatically.
    *   Visit Google AI Studio: [https://aistudio.google.com/apikey](https://aistudio.google.com/apikey)
    *   Sign in and click "Create API key". Copy this key ideally to your phones clipboard.

3.  **📲 Install & Use AppCoder:**
    *   Transfer the downloaded `.apk` file to your Android phone.
    *   On your phone, open a file manager, find the `.apk`, and tap to install.
        *   You might need to **enable "Install from unknown sources"** in your phone's settings.
        *   On newer phones you can save some time by disabling the "Google Play Protect" feature since all it does is asking you for each install of your selfe build apps to confirm your identity.
    *   After AppCoder installs and completes its initial setup (grant permissions, wait for installation of all the dependencies):
        *   Tap the **🔵 blue plus (+) button**.
        *   Enter an **App Name**, a **simple App Description** (keep it simple for now more complex apps will be possible in the future), paste your **Gemini API Key** and press "Generate App".
    *   After code generation by the LLM, press **continue to build**.
    *   When installing your *generated app*, you might need to **expand the installation popup to find and select "Install anyway"**.
    *   When you have build your first app and tested its functionality you might want to modify it. You can do that the same way you created the new app but instead of choosing a new app name you search for the existing one you want to modefy. **!Attention! Currently this overrides the existing app and might break it, there is no "save previous version" feature implemented yet.** 

---

## 💡 What is AppCoder?

AppCoder transforms your plain English descriptions into working app code using advanced LLMs like **Google's Gemini 2.5 Flash Preview**. 

Simply describe your app vision, and AppCoder handles the code generation.

**Who is AppCoder for?**
*   **Non-programmers:** Create apps without technical barriers.
*   **Experienced Developers:** Rapidly prototype ideas.

Export the generated code to continue development on a desktop with professional tools.

**What is AppCoder able to do?**
*   **Not much yet:** Since it is the first version many issues are not resolved and you are only able to generate very simple apps.
*   Some examples that should worked in the current state are a Tiktokto game or a dice simulator for example
*   If the app is to complicated is to complex the LLM likely introduces to many errors and the build might fail or the installation fails or the installed app crashes on start or contains to major bugs.
*   Currently only existing files can be overriden limiting what you can do. For example a simple snake game would likely require more files and therefore always fails.
*   After the first build which takes longer follow up builds take around 50 seconds on a modern phone from posting your app description to having the finished app installed on your phone.
---


## ⚠️ Important Notes & Known Issues (MVP v1.0.0) [Bug fixes are planned 🛠️] 

*   **Simple App Ideas Work Best:** For this first version, if the LLM generates non-working code (e.g., from a complex description), you must build a new app with a **new app name** (overwriting isn't implemented yet). Try simplifying your initial idea if it fails.
*   **AppCoder's Initial Setup:**
    *   The first run of AppCoder installs necessary components. This can occasionally fail. **Reinstalling the AppCoder `.apk` usually fixes this.**
    *   When you close the app during the initial setup, you might get stuck on the initial start screen. If stuck during initial setup, reinstall AppCoder to start the setup process again. 
*   **Modify Function:** Modifying existing apps is not working currently. All apps are one shot they either work on first generation or not.

---

## 🛠️ For more serious Developers: Building AppCoder from Source

This section is for those who want to compile AppCoder from its source code. **If you just downloaded the APK, you can skip this.**

This might be interested for you if you want to implement your own LLM API calls or if you want to implement advanced LLM Agent features to improve the code generation like automated testing and such features.

1.  **Clone this repository.**
2.  **Connect your Android smartphone** to your computer.
3.  **Build and install the AppCoder APK** onto your phone using **Android Studio Meerkat 2024.3.2**. [No change to IDE name]
4.  **Development Setup Notes:**
    *   **Line Endings:** Use **LF** (not CRLF). (This issue should be fixed with the last commit via `.gitattributes`)
    *   **Gradle JDK:** JDK 21.
5.  **Troubleshooting Builds from Source:**
    *   **Gradle build fails in Android Studio:** Try running the build process again.
    *   **Build of the *generated app* (by AppCoder) on the phone fails (during its terminal run):** Uninstall the partially built app and restart the build process within AppCoder.
6. You find the LLM related Code under AppCoder\core\app\src\main\java\com\itsaky\androidide\dialogs the rest of the app is basically the original AndroidIDE code only modefied in a few areas for example getting it streamlined to better work for LLM based development.

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
