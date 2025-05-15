# 📱 AppCoder: Turn Your App Ideas into Reality – Right On Your Phone!

**AppCoder** is a mobile Android app that empowers you to build apps directly on your phone — no programming experience required!

---

## 🚀 Project Status

> ⚠️ **IMPORTANT:** This project is actively under development. There is no downloadable `.apk` file available for direct installation yet. Please stay tuned for updates!

> ✅ **Minimum Viable Product (MVP) is Ready for Testing using Android Studio!**
> Here's how you can try it out:
> 1. Clone this repository.
> 2. Connect your Android smartphone to your computer.
> 3. Build and install the AppCoder APK onto your phone (Android Studio).
> 4. Follow the on-device installation process for AppCoder.
> 5. On the AppCoder main screen, you'll see a blue plus (+) button. Tap it.
> 6. Enter an app name, a description for your app, and your Gemini API Key, then tap "Continue."
> 7. The code for your described app will now be generated. After the code generation by the LLM is done, you can proceed with the build process (takes a few minitues on the first build).
> 8. Install your newly generated app.

---

## 💡 What is AppCoder?

AppCoder transforms your plain English descriptions into real, working app code. This is powered by advanced large language models (LLMs) like **Google's Gemini**.

Simply describe your app vision. AppCoder (with Gemini working behind the scenes) takes care of generating the code.

**Who is AppCoder for?**
*   **Non-programmers:** Create apps without facing technical barriers.
*   **Experienced Developers:** Use it for rapid prototyping or testing new concepts quickly.

When you're ready to take your app further, you can export the generated code and continue development in a desktop environment using professional programming tools.

---

## 🔑 Setting Up Your Free Gemini API Key

AppCoder utilizes the Gemini API from Google for several good reasons:
1.  **Free Tier:** It offers a generous free usage tier suitable for many use cases.
2.  **Powerful for Code:** Gemini is one of the leading models for code generation tasks.
3.  **Optimal Integration:** Using a Google LLM for Android programming is a natural fit.

**How to get your API Key (required by the AppCoder app):**
1.  Visit the Google AI Studio website: [https://aistudio.google.com/](https://aistudio.google.com/)
2.  Sign in with your Google account.
3.  Click on "Create API key" (or a similar option; the UI may evolve).
4.  Copy your generated API key.
5.  In the AppCoder app, paste this key into the "Gemini API Key" field.

This gives AppCoder access to one of the best LLMs at no extra cost and without complex registration processes (beyond your likely pre-existing Google account).

---

## 🛠️ Troubleshooting & Development Environment

Here are some tips if you encounter issues while running the project:

**My Development Setup (Just in case you have issues with your setup):**
*   **Editor Line Endings:** Ensure your code editor uses **LF** (Linux/macOS) instead of CRLF (Windows) for line endings, especially for script files. Most editors have a setting for this (often in the status bar or File/Edit menus). (This issue should however be fixed with the last commit)
*   **Android Studio:** I use Android Studio Meerkat 2024.3.2".
*   **Gradle JDK:** JDK 21.

**Known Issues and Solutions:**
1.  **Gradle build fails in Android Studio:** Simply try running the build process again. This can sometimes resolve temporary glitches.
2.  **Build of the generated app on the phone fails (during the terminal run):** Uninstall the (partially built) app from your phone and restart the build process within AppCoder.
3.  **AppCoder gets stuck on the initial screen (if you exited the installation process of a generated app prematurely):** Uninstall AppCoder and reinstall it.

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
