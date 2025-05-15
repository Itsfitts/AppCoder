# AppCoder

**AppCoder** is a mobile app that lets you build fully functional apps directly on your phone — no programming experience required.

---

## Project Status

> **CURRENTLY THIS PROJECT IS STILL IN A DEVELOPMENT PHASE AND THERE IS NO .APK FILE YET TO DOWNLOAD. STAY TUNED FOR FURTHER UPDATES.**

> **However the minimal viable product state is ready. When cloning the project, connecting your phone and build the apk to your phone. After that simply follow the installation process. When the instalation is done you are on the main screen there you see a blue plus button. Press the button and fill out the app name app description API Key field and press continue. The code is now generated and after a few second you can contuinue with the build process of the app you just described. TWhen the build priocess is done follow the installation process.**

---

## Description

Powered by advanced large language models like **ChatGPT**, AppCoder turns plain English descriptions into real, working code.  
Simply describe what you envision, engage in a bit of back and forth to let ChatGPT iron out any bugs, and your app is ready.

AppCoder is designed for non-programmers, removing all barriers to app creation. It also serves as a great tool for experienced developers who need to rapidly prototype an idea or test concepts.

When you're ready to take your app further, you can export the code and continue development on your desktop environment using more advanced programming tools.

---

## Setting up the Free API

The current project uses the Gemini API because:
1. you get it for free
2. Gemini is the best codeing model as of now
3. I think it is a good idea to go with a Google LLM for Android programming

To get a Google Gemini APIKey, which you will need in the app, go to the AIStudio website [https://aistudio.google.com](https://aistudio.google.com/apikey). On the top of the page you find the "Get API key" button. Use it and generate your free API Key. In the AppCoder app you will be asked to past in this API Key under "Gemini API Key". This way the APPCoder gets access to one of the best LLMs for no cost and no registration except your google account which you probably already have.

---

## When Running the Project and it Fails Try the Following 

My settings and software:
1. Most of these editors have a way to change line endings. Look in the bottom status bar or in the File/Edit menus for an option like "Line Separators," "Line Endings," or similar, and change it from "CRLF" to "LF".
2. Use Android Studio Meerkat 2024.3.2
3. Gradle jdk-21

Issues I Experienced when Running the IDE App:
1. Somtimes the gradle build fails in android studio - simply run the graidle build again
2. Sometimes the build on the phone fails during the terminal run - uninstall and run again
3. If you are stuck in the initial screen due to leaving the installation process you also need to uninstall and rerun the app installation


---

## Alternative Terms

- **App Maker**
- **Vibe Coding**
- **Cursor / Windsurf for Android**

---

## License

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
