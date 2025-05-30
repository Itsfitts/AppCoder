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
*   Currently the limiting factor determining how complex the app can get is the LLM running in the background.
*   The default LLM in this app is Gemini 2.5 Flash with Thinking budget maximised. This Gemini flash model is free and able to do simple apps like a ToDo list app, a tic-tac-toe or even Tetris game. A simple snake game is around the limit what is possible and can take mutliple itteration with the LLM to get a working version running (sometimes I got it working 0 shot sometimes it took me multiple itterations posting the build failiours and describing the errors seen in the installed snake app).
*   Using Gemini 2.5 Pro should make more complex apps possible but it is not free and would requires you to set up a google billing account. Also it takes Gemini 2.5 Pro around almost 3 minutes to generate the code of a snake app while 2.5 Flash only takes 20 seconds. Testing it on a simple snake game even Gemini 2.5 Pro needed multiple itterations to make it work and resulted in cloud costs of around 0.3 doller for one snake game. It would require more testing to know for sure if it is worth paying for 2.5 Pro instead of simply using 2.5 Flash for android app development. 
*   A free and fast alternative way to make more complex apps possible is by using Claude Sonnet 4 with artefacts enabled. In the Antropic app you can quickly itterate over your app idea using the artifacts view. By programming in HTML you see the results almost immediately and quicker then itterating in the AppCoder app where one itteration with Gemini 2.5 Flash + build time takes around 60 seconds. If Claude is done making the HTML version of your app you can prompt Claude to translate the HTML code into Android code. Then copy and past the code into the prompt field of AppCoder. Gemini 2.5 Flash running inside AppCoder seems capable enough to create a working android app from this code. The Sonnet 4 model probably creates better code compared to Gemini 2.5 Flash. This way more complex apps become possible for free.
*   If the app is to complex however the LLM can fail by not generating usefull code at all or introduce to many errors so that the build fails or the installation fails or the installed app crashes on start or the app contains major bugs. In short there are many ways failiours can happen.
*   After the first build which takes much longer around 10 minutes, follow up builds take around 60 seconds on a modern phone from posting your app description to having the finished app installed on your phone.

---

## ✨ Alternatives / Existing Tools

The space of programming helpers that use LLMs is relatively new and rapidly becoming crowded, as LLMs and programming are a natural match. The performance of LLMs on programming and math tasks is expected to improve significantly, with some predicting that all coding tasks will eventually be handled by LLMs.

The most well-known tools are IDEs with advanced LLM and agent integrations, designed to help both professional and beginner programmers generate better code more efficiently.

In contrast, no-code tools receive less attention, likely due to LLMs’ current limitations in common sense reasoning, which still requires human oversight. Currently, LLMs perform best at low-level tasks but are increasingly capable of implementing entire features. Non-programmers, who can manage higher-level design and testing, are becoming more effective collaborators for guiding these more capable LLMs. As LLMs continue to improve, a growing share of apps will be developed by non-programmers.

AppCoder, the focus of this project, targets non-programmers, as coding directly on a phone is not ideal. A desktop IDE is generally more powerful and flexible, but a phone-based IDE offers ease of use. Combined with an LLM that handles the coding, this creates a practical system for quickly building apps on mobile devices.

### 💻 Alternative App Creation Tools for Non-Programmers
*  **[Kiki.dev former Appacella](https://www.kiki.dev/)** 

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
