/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.activities.editor

/**
 * @author Akash Yadav
 */
class EditorActivityKt : EditorHandlerActivity() {

    companion object {
        /**
         * A boolean extra used by MainActivity to instruct this activity
         * to automatically trigger a build as soon as the project is initialized.
         * The logic to handle this extra is in the parent class, EditorHandlerActivity.
         */
        const val EXTRA_AUTO_BUILD_PROJECT = "com.itsaky.androidide.AUTO_BUILD_PROJECT"
    }
}

// TODO: Should we adjust to the display cutout insets?