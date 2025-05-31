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
    *   See the **[latest release page](https://github.com/ChristophGeske/AppCoder/releases)** for instructions.

---

## 💡 What is AppCoder?

AppCoder transforms your plain English descriptions into working app code using advanced LLMs like **Google's Gemini 2.5 Flash Preview**. 

Simply describe your app vision, and AppCoder handles the code generation.

**Who is AppCoder for?**
*   **Non-programmers:** Create apps without technical barriers.
*   **Experienced Developers:** Rapidly prototype ideas.

Export the generated code to continue development on a desktop with professional tools.

**What is AppCoder able to do?**

* Currently, the limiting factor determining how complex the app can be is the LLM running in the background.

* AppCoder uses Gemini 2.5 Flash with the thinking budget maximized as the default. This free model can generate simple apps like to-do lists, tic-tac-toe, or even a Tetris game. In recent benchmarks, it performs well in benchmarks but my tests show that it often fails to generate working code in the initial app heneration and that it fails to fix errors in the code reliably. Gemini 2.5 Pro, is significantly more reliable. However the Flash model is completly free and a good choice for this free open-source project. 

* A snake game is near the complexity limit. It may work on the first try or may require multiple iterations, using the previous build's error messages for correction in each iteration.

* You also have the option to choose Gemini 2.5 Pro in the dropdown menu. While it allows for more complex apps, it is not free and requires a Google Cloud billing account. It takes the LLM about 3 minutes to generate the snake game code using Pro, compared to about 20 seconds with Flash. Even with Pro, multiple iterations might be needed, and generating a single snake app costs around $0.30 in cloud usage.

* In my testing I saw the best performance using Gemini 2.5 Pro, DeepSeek R1 and Claude Sonnet 4 with or without artifacts enabled. In the Anthropic app, you can itterate on your app idea quickly using HTML in the browser first. Once you're done itterating on your app idea, you can ask Claude to translate the HTML into Android code, then paste that code into AppCoder. Gemini 2.5 Flash can usually generate a working Android app from the Claude code copied in the app description input field. For API use DeepSeek R1 would be the cheapest option but AppCoder currently only allows you to switch to Gemini 2.5 Pro if you require better results.

* However, if the app is too complex, even the most advanced LLMs will generate unusable code, fail to build, crash on install, or contain major bugs.

* The first build takes about 10 minutes. Follow-up builds typically take ~60 seconds on modern phones, from description to installation.

* You can export the generated code and continue development on a desktop using professional tools.

---

## ✨ Alternatives / Existing Tools

The space of programming helpers that use LLMs is relatively new and rapidly becoming crowded, as LLMs and programming are a natural match. The performance of LLMs on programming and math tasks is expected to improve significantly, with some predicting that all coding tasks will eventually be handled by LLMs.

The most well-known tools are IDEs with advanced LLM and agent integrations, designed to help both professional and beginner programmers generate better code more efficiently.

In contrast, no-code tools receive less attention, likely due to LLMs’ current limitations in common sense reasoning, which still requires human oversight. Currently, LLMs perform best at low-level tasks but are increasingly capable of implementing entire features. Non-programmers, who can manage higher-level design and testing, are becoming more effective collaborators for guiding these more capable LLMs. As LLMs continue to improve, a growing share of apps will be developed by non-programmers.

AppCoder, the focus of this project, targets non-programmers, as coding directly on a phone is not ideal. A desktop IDE is generally more powerful and flexible, but a phone-based IDE offers ease of use. Combined with an LLM that handles the coding, this creates a practical system for quickly building apps on mobile devices.

### 📱 Alternative App Creation Tools for Non-Programmers
*  **[Kiki.dev former Appacella](https://www.kiki.dev/)** is a commercial non-open source app for coding apps. It has 30 free itterations a month and 5 per day. The model seems less advanced than the gemini 2.5 flash model used in this project. Build time is fast and similar to this project. It uses an additional [Expo Go App](https://play.google.com/store/apps/details?id=host.exp.exponent)** to transfear the build app to your phone.
*  **[Rork](https://rork.com)** is a commercial non-open source app for coding apps. It has 7 free itterations per day and you will likely need all of them just to build one simple tetris game. The model they use seems less advanced than the gemini 2.5 flash model used in this project. Build time is fast and similar to this project. It uses an additional [Expo Go App](https://play.google.com/store/apps/details?id=host.exp.exponent)** to transfear the build app to your phone.

### 💻 Alternative Full IDEs primarily focused on programmers 
* Cursor
* Windsurf (bought by OpenAI)
* Github Copiliot (free for students)

---

## 🛠️ For more serious Developers: Building AppCoder from Source

This section is for those who want to compile AppCoder from its source code. **If you just downloaded the APK, you can skip this.**

This might be interested for you if you want to implement your own LLM API calls or if you want to implement advanced LLM Agent features to improve the code generation like automated testing and such features.

1.  **Clone this repository.**
2.  **Connect your Android smartphone** to your computer.
3.  **Build and install the AppCoder APK** onto your phone use **Android Studio Meerkat 2024.3.2** to match my setup and avoide version issues.
4.  **Development Setup Notes:**
    *   **Gradle JDK:** JDK 21.
5.  **Troubleshooting Builds from Source:**
    *   **Gradle build fails in Android Studio:** Try running the build process again.
    *   **Build of the *generated app* (by AppCoder) on the phone fails (during its terminal run):** Uninstall the partially built app and restart the build process within AppCoder.
6. You find the LLM related Code under AppCoder\core\app\src\main\java\com\itsaky\androidide\dialogs the rest of the app is basically the original AndroidIDE code only modefied in a few areas for example getting it streamlined to better work for LLM based development.

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
